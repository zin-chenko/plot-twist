package io.plottwist.auth.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.plottwist.auth.oauth.infrastructure.web.AuthorizationRequestCache;
import io.plottwist.auth.security.web.AuthWorkLimiter;
import io.plottwist.auth.security.web.LoginPageController;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.WebAttributes;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = LoginFeedbackTest.LoginConfiguration.class)
@WebAppConfiguration
class LoginFeedbackTest {
  @Autowired WebApplicationContext context;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest
  @ValueSource(strings = {"member", "unknown-account"})
  void wrongPasswordAndUnknownLoginDisplayTheSameSafeError(String username) throws Exception {
    var session = new MockHttpSession();
    mvc.perform(
            post("/login")
                .session(session)
                .with(csrf())
                .param("username", username)
                .param("password", "PRIVATE_WRONG_PASSWORD"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/login?error"));
    var html =
        mvc.perform(get("/login?error").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(html.contains(LoginPageController.ERROR_MESSAGE));
    assertTrue(html.contains("role=\"alert\""));
    assertFalse(html.contains("PRIVATE_WRONG_PASSWORD"));
    assertFalse(html.contains("Bad credentials"));
  }

  @Test
  void retryPreservesAuthorizationRequestAndSuccessfulLoginClearsTheError() throws Exception {
    var session = new MockHttpSession();
    var authorize =
        new org.springframework.mock.web.MockHttpServletRequest("GET", "/oauth2/authorize");
    authorize.setSession(session);
    authorize.setQueryString("client_id=browser&state=original&code_challenge=pkce");
    var cache = new AuthorizationRequestCache();
    cache.saveRequest(authorize, new org.springframework.mock.web.MockHttpServletResponse());
    String continuation = cache.getRequest(authorize, null).getRedirectUrl();
    mvc.perform(
            post("/login")
                .session(session)
                .with(csrf())
                .param("username", "member")
                .param("password", "wrong"))
        .andExpect(redirectedUrl("/login?error"));
    assertEquals(continuation, cache.getRequest(authorize, null).getRedirectUrl());
    mvc.perform(
            post("/login")
                .session(session)
                .with(csrf())
                .param("username", "member")
                .param("password", "correct-test-password"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl(continuation));
    assertNull(session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION));
  }

  @Test
  void formStillRequiresCsrf() throws Exception {
    mvc.perform(
            post("/login").param("username", "member").param("password", "correct-test-password"))
        .andExpect(status().isForbidden());
  }

  @Configuration
  @EnableWebSecurity
  @EnableWebMvc
  @org.springframework.context.annotation.Import(LoginPageController.class)
  static class LoginConfiguration {
    @Bean
    SecurityFilterChain login(HttpSecurity http) throws Exception {
      return new SecurityConfig()
          .login(http, new AuthWorkLimiter(), new AuthorizationRequestCache(), Duration.ofHours(4));
    }

    @Bean
    UserDetailsService users() {
      return new InMemoryUserDetailsManager(
          User.withUsername("member")
              .password("{noop}correct-test-password")
              .roles("USER")
              .build());
    }
  }
}
