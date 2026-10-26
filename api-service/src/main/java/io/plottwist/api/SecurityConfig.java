package io.plottwist.api;

import java.time.Duration;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/**").hasAuthority("SCOPE_api.read")
                .anyRequest().denyAll())
            .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults())).build();
    }

    @Bean
    JwtDecoder jwtDecoder(RestTemplateBuilder builder,
            @Value("${auth.issuer:http://localhost:8080}") String issuer,
            @Value("${auth.jwks-uri:http://localhost:8083/oauth2/jwks}") String jwksUri) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
            .restOperations(builder.connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2)).build()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<List<String>>("aud", audiences ->
                audiences != null && audiences.contains("plot-twist-api"))));
        return decoder;
    }
}
