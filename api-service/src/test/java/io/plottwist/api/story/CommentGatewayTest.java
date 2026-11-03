package io.plottwist.api.story;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.plottwist.api.config.SecurityConfig;
import io.plottwist.api.shared.application.port.DomainBackend;
import io.plottwist.api.story.api.StoryGateway;
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

@WebMvcTest(StoryGateway.class)
@Import(SecurityConfig.class)
class CommentGatewayTest {
  @Autowired MockMvc mvc;
  @MockitoBean JwtDecoder decoder;
  @MockitoBean DomainBackend domain;
  UUID story = UUID.randomUUID(), comment = UUID.randomUUID();

  private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      user(String scope) {
    return jwt()
        .jwt(
            j ->
                j.subject("33333333-3333-4333-8333-333333333333")
                    .claim("account_id", "33333333-3333-4333-8333-333333333333"))
        .authorities(new SimpleGrantedAuthority("SCOPE_api." + scope));
  }

  @Test
  void forwardsReplyAndSortQueriesAndKeepsPrivateResponseHeaders() throws Exception {
    when(domain.exchange(
            eq(HttpMethod.GET), anyString(), eq("token"), eq(Map.of()), isNull(), isNull()))
        .thenReturn(
            ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag("\"2\"")
                .body("{}".getBytes()));
    var path = "/api/v1/stories/" + story + "/comments";
    mvc.perform(get(path + "?sort=popular&cursor=opaque&limit=2").with(user("read")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(domain)
        .exchange(
            HttpMethod.GET,
            path + "?sort=popular&cursor=opaque&limit=2",
            "token",
            Map.of(),
            null,
            null);
    mvc.perform(get(path + "/" + comment + "/replies?cursor=next&limit=2").with(user("read")))
        .andExpect(status().isOk());
    verify(domain)
        .exchange(
            HttpMethod.GET,
            path + "/" + comment + "/replies?cursor=next&limit=2",
            "token",
            Map.of(),
            null,
            null);
  }

  @Test
  void likeAndUnlikeAcceptEmptyBodiesAndRequireWriteScope() throws Exception {
    String path = "/api/v1/stories/" + story + "/comments/" + comment + "/like";
    when(domain.exchange(any(), eq(path), eq("token"), eq(Map.of()), isNull(), isNull()))
        .thenReturn(ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("{}".getBytes()));
    for (var method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
      mvc.perform(request(method, path)).andExpect(status().isUnauthorized());
      mvc.perform(request(method, path).with(user("read"))).andExpect(status().isForbidden());
      mvc.perform(request(method, path).with(user("write"))).andExpect(status().isOk());
      verify(domain).exchange(method, path, "token", Map.of(), null, null);
    }
  }
}
