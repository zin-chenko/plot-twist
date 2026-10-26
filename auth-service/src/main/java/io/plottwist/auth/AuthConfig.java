package io.plottwist.auth;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import javax.sql.DataSource;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
public class AuthConfig {
    @Bean @Order(1)
    SecurityFilterChain authorizationServer(HttpSecurity http, AuthWorkLimiter limiter, TokenEndpointTransactions tokenTransactions) throws Exception {
        var server = OAuth2AuthorizationServerConfigurer.authorizationServer();
        return http.addFilterBefore(limiter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
            .addFilterAfter(tokenTransactions, AuthWorkLimiter.class).securityMatcher(server.getEndpointsMatcher())
            .with(server, authorization -> authorization.oidc(Customizer.withDefaults()))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
            .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML))).build();
    }

    @Bean @Order(2)
    SecurityFilterChain accountApi(HttpSecurity http, AuthWorkLimiter limiter) throws Exception {
        return http.addFilterBefore(limiter, org.springframework.security.web.context.SecurityContextHolderFilter.class).securityMatcher("/auth/v1/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers(org.springframework.http.HttpMethod.POST, "/auth/v1/**").permitAll().anyRequest().denyAll())
            .build();
    }

    @Bean @Order(3)
    SecurityFilterChain login(HttpSecurity http, AuthWorkLimiter limiter) throws Exception {
        // CSRF is enabled for the login form and session-backed user interaction.
        return http.addFilterBefore(limiter, org.springframework.security.web.context.SecurityContextHolderFilter.class).authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus", "/error").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form.failureHandler((request, response, error) -> response.sendRedirect("/login?error"))).build();
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<AuthWorkLimiter> limiterRegistration(AuthWorkLimiter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Installed inside SecurityFilterChain, never twice as a servlet filter.
        return registration;
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<TokenEndpointTransactions> transactionRegistration(TokenEndpointTransactions filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.password.DelegatingPasswordEncoder("argon2", java.util.Map.of(
            "argon2", new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 19456, 2),
            "bcrypt", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()));
    }

    @Bean
    JdbcUserDetailsManager users(DataSource source, JdbcTemplate jdbc) {
        return new JdbcUserDetailsManager(source) {
            @Override
            public org.springframework.security.core.userdetails.UserDetails loadUserByUsername(String username) {
                var details = super.loadUserByUsername(AccountService.email(username));
                Integer locked = jdbc.queryForObject("SELECT count(*) FROM login_attempts WHERE username = ? AND locked_until > now()", Integer.class, details.getUsername());
                return User.withUserDetails(details).accountLocked(locked != null && locked > 0).build();
            }
        };
    }

    @Bean
    RegisteredClientRepository clients(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizations(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients) {
            @Override
            public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
                if (type != null && "code".equals(type.getValue())) {
                    if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                        throw new IllegalStateException("Authorization code redemption requires a transaction");
                    jdbc.queryForList("SELECT id FROM oauth2_authorization WHERE authorization_code_value = ? FOR UPDATE", String.class, token);
                }
                return super.findByToken(token, type);
            }
        };
    }

    @Bean
    OAuth2AuthorizationConsentService consents(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    ApplicationRunner bootstrap(JdbcUserDetailsManager users, RegisteredClientRepository clients,
            PasswordEncoder encoder, @Value("${auth.bootstrap.username:}") String username,
            @Value("${auth.bootstrap.password:}") String password,
            @Value("${auth.client-secret:}") String clientSecret,
            @Value("${auth.bootstrap.enabled:false}") boolean bootstrapEnabled,
            @Value("${auth.machine-client.enabled:false}") boolean machineEnabled,
            @Value("${auth.redirect-uri}") String redirectUri) {
        return args -> {
            if (bootstrapEnabled && !users.userExists(username)) {
                users.createUser(User.withUsername(username).password(encoder.encode(password))
                    .roles("USER").build());
            }
            // Client configuration is reconciled on startup; users are only bootstrapped once.
            if (machineEnabled) clients.save(RegisteredClient.withId("plot-twist-cli")
                .clientId("plot-twist-cli").clientSecret(encoder.encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("api.read")
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build())
                .build());
            clients.save(RegisteredClient.withId("plot-twist-web")
                .clientId("plot-twist-web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri).scope("openid").scope("api.read").scope("api.write")
                .clientSettings(ClientSettings.builder().requireProofKey(true)
                    .requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build())
                .build());
        };
    }

    @Bean
    JWKSource<SecurityContext> signingKey(@Value("${auth.signing-key}") String path, @Value("${auth.auto-create-key:false}") boolean autoCreate) throws Exception {
        var keys = new JWKSet(SigningKeys.load(Path.of(path), autoCreate));
        return (selector, context) -> selector.select(keys);
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> source) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(source);
    }

    @Bean
    AuthorizationServerSettings settings(@Value("${auth.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> accessTokenClaims(JdbcTemplate jdbc) {
        return context -> {
            if (!AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                var ids = jdbc.queryForList("SELECT id FROM accounts WHERE username = ? AND verified = true", java.util.UUID.class, context.getPrincipal().getName());
                if (!ids.isEmpty()) {
                    context.getClaims().subject(ids.getFirst().toString());
                    context.getClaims().claim("account_id", ids.getFirst().toString());
                }
            }
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                // Use the JDBC/Jackson-supported collection type for persisted token metadata.
                context.getClaims().audience(new ArrayList<>(List.of("plot-twist-api", "plot-twist-domain")));
            }
        };
    }
}
