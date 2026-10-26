package io.plottwist.domain.diagnostics;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.plottwist.domain.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ServiceController.class)
@Import(SecurityConfig.class)
class ServiceControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean JwtDecoder decoder;

  @Test
  void identifiesService() throws Exception {
    mvc.perform(
            get("/api/character")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.service").value("domain-service"))
        .andExpect(jsonPath("$.status").value("running"));
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/api/character")).andExpect(status().isUnauthorized());
  }

  @Test
  void requiresReadScope() throws Exception {
    mvc.perform(
            get("/api/character")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_other"))))
        .andExpect(status().isForbidden());
  }
}
