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
    SecurityFilterChain authorizationServer(HttpSecurity http) throws Exception {
        var server = OAuth2AuthorizationServerConfigurer.authorizationServer();
        return http.securityMatcher(server.getEndpointsMatcher())
            .with(server, authorization -> authorization.oidc(Customizer.withDefaults()))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
            .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML))).build();
    }

    @Bean @Order(2)
    SecurityFilterChain login(HttpSecurity http) throws Exception {
        // CSRF is enabled for the login form and session-backed user interaction.
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                .anyRequest().authenticated())
            .formLogin(Customizer.withDefaults()).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    JdbcUserDetailsManager users(DataSource source) {
        return new JdbcUserDetailsManager(source);
    }

    @Bean
    RegisteredClientRepository clients(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizations(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService consents(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    ApplicationRunner bootstrap(JdbcUserDetailsManager users, RegisteredClientRepository clients,
            PasswordEncoder encoder, @Value("${auth.bootstrap.username}") String username,
            @Value("${auth.bootstrap.password}") String password,
            @Value("${auth.client-secret}") String clientSecret,
            @Value("${auth.redirect-uri}") String redirectUri) {
        return args -> {
            if (!users.userExists(username)) {
                users.createUser(User.withUsername(username).password(encoder.encode(password))
                    .roles("USER").build());
            }
            // Client configuration is reconciled on startup; users are only bootstrapped once.
            clients.save(RegisteredClient.withId("plot-twist-cli")
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
                .redirectUri(redirectUri).scope("openid").scope("api.read")
                .clientSettings(ClientSettings.builder().requireProofKey(true)
                    .requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build())
                .build());
        };
    }

    @Bean
    JWKSource<SecurityContext> signingKey(@Value("${auth.signing-key}") String path) throws Exception {
        var keys = new JWKSet(SigningKeys.loadOrCreate(Path.of(path)));
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
    OAuth2TokenCustomizer<JwtEncodingContext> accessTokenClaims() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                // Use the JDBC/Jackson-supported collection type for persisted token metadata.
                context.getClaims().audience(new ArrayList<>(List.of("plot-twist-api", "plot-twist-domain")));
            }
        };
    }
}
