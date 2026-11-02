package io.plottwist.auth.config;

import io.plottwist.auth.oauth.infrastructure.security.BrowserRedirectValidator;
import io.plottwist.auth.oauth.infrastructure.web.AuthorizationRequestCache;
import io.plottwist.auth.oauth.infrastructure.web.BrowserReauthenticationFilter;
import io.plottwist.auth.oauth.infrastructure.web.TokenEndpointTransactions;
import io.plottwist.auth.security.web.AuthWorkLimiter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
public class SecurityConfig {
  @Bean
  @Order(1)
  SecurityFilterChain authorizationServer(
      HttpSecurity http,
      AuthWorkLimiter limiter,
      TokenEndpointTransactions tokenTransactions,
      RequestCache requestCache,
      RegisteredClientRepository clients)
      throws Exception {
    var server = OAuth2AuthorizationServerConfigurer.authorizationServer();
    java.util.function.Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext>
        browserRedirect = BrowserRedirectValidator::validate;
    return http.addFilterBefore(
            limiter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .addFilterAfter(tokenTransactions, AuthWorkLimiter.class)
        .addFilterAfter(
            new BrowserReauthenticationFilter(requestCache, clients),
            org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .securityMatcher(server.getEndpointsMatcher())
        .requestCache(cache -> cache.requestCache(requestCache))
        .with(
            server,
            authorization ->
                authorization
                    .oidc(Customizer.withDefaults())
                    .authorizationEndpoint(
                        endpoint ->
                            endpoint.authenticationProviders(
                                providers ->
                                    providers.forEach(
                                        provider -> {
                                          if (provider
                                              instanceof
                                              OAuth2AuthorizationCodeRequestAuthenticationProvider
                                                  codeRequests)
                                            codeRequests.setAuthenticationValidator(
                                                browserRedirect.andThen(
                                                    new OAuth2AuthorizationCodeRequestAuthenticationValidator()));
                                        }))))
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
        .exceptionHandling(
            errors ->
                errors.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain accountApi(HttpSecurity http, AuthWorkLimiter limiter) throws Exception {
    return http.addFilterBefore(
            limiter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .securityMatcher("/auth/v1/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(org.springframework.http.HttpMethod.POST, "/auth/v1/**")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain login(
      HttpSecurity http,
      AuthWorkLimiter limiter,
      RequestCache requestCache,
      @Value("${auth.browser-session-duration:4h}") Duration sessionDuration)
      throws Exception {
    // CSRF is enabled for the login form and session-backed user interaction.
    var success = new SavedRequestAwareAuthenticationSuccessHandler();
    success.setRequestCache(requestCache);
    return http.requestCache(cache -> cache.requestCache(requestCache))
        .addFilterBefore(
            limiter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/prometheus",
                        "/error",
                        "/default-ui.css",
                        "/.well-known/appspecific/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            form ->
                form.loginPage("/login")
                    .permitAll()
                    .successHandler(
                        (request, response, authentication) -> {
                          // Existing JDBC sessions retain their old timeout until a fresh login.
                          request
                              .getSession()
                              .setMaxInactiveInterval(Math.toIntExact(sessionDuration.toSeconds()));
                          success.onAuthenticationSuccess(request, response, authentication);
                        })
                    .failureUrl("/login?error"))
        .build();
  }

  @Bean
  RequestCache authorizationRequestCache() {
    return new AuthorizationRequestCache();
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<AuthWorkLimiter> limiterRegistration(
      AuthWorkLimiter filter) {
    var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
    registration.setEnabled(
        false); // Installed inside SecurityFilterChain, never twice as a servlet filter.
    return registration;
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<TokenEndpointTransactions>
      transactionRegistration(TokenEndpointTransactions filter) {
    var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
