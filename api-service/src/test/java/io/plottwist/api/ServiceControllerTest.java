package io.plottwist.api;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ServiceController.class)
@Import(SecurityConfig.class)
class ServiceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean DomainClient domain;

    @Test
    void identifiesService() throws Exception {
        mvc.perform(get("/api/story").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Backend-Instance"))
            .andExpect(jsonPath("$.service").value("api-service"));
    }

    @Test
    void callsDomainAndPropagatesRequestId() throws Exception {
        when(domain.info("test-request", "token")).thenReturn(Map.of("service", "domain-service"));
        mvc.perform(get("/api/character").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.read"))).header("X-Request-ID", "test-request"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("domain-service"));
        verify(domain).info("test-request", "token");
    }

    @Test
    void reportsUnavailableDomain() throws Exception {
        when(domain.info("", "token")).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        mvc.perform(get("/api/character").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.read")))).andExpect(status().isServiceUnavailable());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/story")).andExpect(status().isUnauthorized());
    }

    @Test
    void requiresReadScope() throws Exception {
        mvc.perform(get("/api/story").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_other"))))
            .andExpect(status().isForbidden());
    }
}
