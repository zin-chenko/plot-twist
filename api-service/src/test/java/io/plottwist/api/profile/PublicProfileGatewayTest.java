package io.plottwist.api.profile;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.plottwist.api.config.SecurityConfig;
import io.plottwist.api.profile.api.PublicProfileGateway;
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

@WebMvcTest({PublicProfileGateway.class, StoryGateway.class})
@Import(SecurityConfig.class)
class PublicProfileGatewayTest {
  @Autowired MockMvc mvc;
  @MockitoBean JwtDecoder decoder;
  @MockitoBean DomainBackend domain;
  final UUID viewer = UUID.randomUUID(), author = UUID.randomUUID();

  private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      user() {
    return jwt()
        .jwt(j -> j.subject(viewer.toString()).claim("account_id", viewer.toString()))
        .authorities(new SimpleGrantedAuthority("SCOPE_api.read"));
  }

  @Test
  void relaysTheUserTokenAndPreservesPrivacyHeadersAndAuthorPagination() throws Exception {
    when(domain.exchange(
            eq(HttpMethod.GET), anyString(), eq("token"), eq(Map.of()), isNull(), isNull()))
        .thenReturn(
            ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}".getBytes()));
    String route = "/api/v1/profiles/" + author;
    for (String suffix : List.of("", "/photo")) {
      mvc.perform(get(route + suffix).with(user()))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"));
      verify(domain).exchange(HttpMethod.GET, route + suffix, "token", Map.of(), null, null);
    }
    mvc.perform(
            get(route + "/stories")
                .with(user())
                .param("limit", "2")
                .param("cursor", "opaque-next-page"))
        .andExpect(status().isOk());
    verify(domain)
        .exchange(
            HttpMethod.GET,
            route + "/stories?limit=2&cursor=opaque-next-page",
            "token",
            Map.of(),
            null,
            null);
  }

  @Test
  void anonymousMachineAndWriteOnlyCallersCannotReadPublicProfiles() throws Exception {
    for (String suffix : List.of("", "/photo", "/stories", "/followers", "/following")) {
      String path = "/api/v1/profiles/" + author + suffix;
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
          .andExpect(status().isForbidden());
      mvc.perform(
              get(path)
                  .with(
                      jwt()
                          .jwt(
                              j ->
                                  j.subject(viewer.toString())
                                      .claim("account_id", viewer.toString()))
                          .authorities(new SimpleGrantedAuthority("SCOPE_api.write"))))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(domain);
  }

  @Test
  void publicEndpointsCannotBeUsedToWriteOtherProfiles() throws Exception {
    mvc.perform(
            put("/api/v1/profiles/" + author)
                .with(user())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/v1/profiles/" + author).with(user()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/profiles/not-a-uuid").with(user())).andExpect(status().isBadRequest());
    verifyNoInteractions(domain);
  }

  @Test
  void followingRequiresWriteScopeAndRelaysOnlyTheVerifiedViewerToken() throws Exception {
    String route = "/api/v1/profiles/" + author + "/follow";
    for (var method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
      mvc.perform(request(method, route)).andExpect(status().isUnauthorized());
      mvc.perform(request(method, route).with(user())).andExpect(status().isForbidden());
      mvc.perform(
              request(method, route)
                  .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.write"))))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(domain);
    for (var method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
      when(domain.exchange(method, route, "token", Map.of(), null, null))
          .thenReturn(
              ResponseEntity.ok()
                  .cacheControl(CacheControl.noStore())
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(
                      "{\"following\":true,\"followersCount\":1,\"followingCount\":0}".getBytes()));
      mvc.perform(
              request(method, route)
                  .with(user().authorities(new SimpleGrantedAuthority("SCOPE_api.write"))))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"));
      verify(domain).exchange(method, route, "token", Map.of(), null, null);
    }
  }

  @Test
  void subscriptionFeedHasAStaticRouteAndPreservesItsCursor() throws Exception {
    String path = "/api/v1/stories/following?limit=2&cursor=opaque-next-page";
    when(domain.exchange(HttpMethod.GET, path, "token", Map.of(), null, null))
        .thenReturn(ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("{}".getBytes()));
    mvc.perform(get(path).with(user())).andExpect(status().isOk());
    verify(domain).exchange(HttpMethod.GET, path, "token", Map.of(), null, null);
    mvc.perform(get("/api/v1/stories/following")).andExpect(status().isUnauthorized());
  }

  @Test
  void connectionListRoutesForwardTheCursorAndLimit() throws Exception {
    for (String kind : List.of("followers", "following")) {
      String route = "/api/v1/profiles/" + author + "/" + kind;
      when(domain.exchange(
              HttpMethod.GET, route + "?limit=2&cursor=opaque-page", "token", Map.of(), null, null))
          .thenReturn(
              ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("{}".getBytes()));
      mvc.perform(get(route).with(user()).param("limit", "2").param("cursor", "opaque-page"))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"));
      verify(domain)
          .exchange(
              HttpMethod.GET, route + "?limit=2&cursor=opaque-page", "token", Map.of(), null, null);
    }
  }
}
