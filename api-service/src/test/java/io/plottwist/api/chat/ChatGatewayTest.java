package io.plottwist.api.chat;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.plottwist.api.chat.api.ChatGateway;
import io.plottwist.api.config.SecurityConfig;
import io.plottwist.api.shared.application.port.DomainBackend;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatGateway.class)
@Import(SecurityConfig.class)
class ChatGatewayTest {
  @Autowired MockMvc mvc;
  @MockitoBean JwtDecoder decoder;
  @MockitoBean DomainBackend domain;
  UUID actor = UUID.randomUUID(), id = UUID.randomUUID();

  private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      user(String scope) {
    return jwt()
        .jwt(j -> j.subject(actor.toString()).claim("account_id", actor.toString()))
        .authorities(new SimpleGrantedAuthority("SCOPE_api." + scope));
  }

  @Test
  void relaysReadPathsAndPagingWithoutLosingPrivacyHeaders() throws Exception {
    when(domain.exchange(
            eq(HttpMethod.GET), anyString(), eq("token"), eq(Map.of()), isNull(), isNull()))
        .thenReturn(ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("{}".getBytes()));
    mvc.perform(
            get("/api/v1/chats").param("cursor", "a+b&c").param("limit", "2").with(user("read")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(domain)
        .exchange(
            HttpMethod.GET,
            "/api/v1/chats?limit=2&cursor=a%2Bb%26c",
            "token",
            Map.of(),
            null,
            null);
    mvc.perform(get("/api/v1/chats/" + id).with(user("read"))).andExpect(status().isOk());
    mvc.perform(get("/api/v1/chats/" + id + "/messages").param("after", "5").with(user("read")))
        .andExpect(status().isOk());
    verify(domain)
        .exchange(
            HttpMethod.GET,
            "/api/v1/chats/" + id + "/messages?limit=50&after=5",
            "token",
            Map.of(),
            null,
            null);
  }

  @Test
  void relaysOnlyExplicitWriteRoutesAndPreservesConflictResponses() throws Exception {
    when(domain.exchange(
            any(), anyString(), eq("token"), eq(Map.of()), any(), eq(MediaType.APPLICATION_JSON)))
        .thenReturn(
            ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body("{}".getBytes()));
    for (String path : List.of("/api/v1/chats", "/api/v1/chats/" + id + "/messages")) {
      mvc.perform(post(path).with(user("write")).contentType("application/json").content("{} "))
          .andExpect(status().isConflict());
      mvc.perform(post(path).with(user("read")).contentType("application/json").content("{}"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            put("/api/v1/chats/" + id + "/read")
                .with(user("write"))
                .contentType("application/json")
                .content("{\"throughSequence\":5}"))
        .andExpect(status().isConflict())
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(domain)
        .exchange(
            eq(HttpMethod.PUT),
            eq("/api/v1/chats/" + id + "/read"),
            eq("token"),
            eq(Map.of()),
            any(),
            eq(MediaType.APPLICATION_JSON));
  }

  @Test
  void anonymousMachineAndMismatchedIdentityCannotAccessAnyChatRoute() throws Exception {
    for (String path :
        List.of("/api/v1/chats", "/api/v1/chats/" + id, "/api/v1/chats/" + id + "/messages")) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
          .andExpect(status().isForbidden());
      mvc.perform(get(path).with(user("write"))).andExpect(status().isForbidden());
    }
    mvc.perform(
            post("/api/v1/chats")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.write")))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/chats/" + id)
                .with(
                    jwt()
                        .jwt(j -> j.subject(actor.toString()).claim("account_id", id.toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(domain);
  }
}
