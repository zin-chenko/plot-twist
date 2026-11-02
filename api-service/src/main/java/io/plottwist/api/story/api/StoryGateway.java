package io.plottwist.api.story.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.plottwist.api.shared.application.port.DomainBackend;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stories")
public class StoryGateway {
  private final DomainBackend backend;

  public StoryGateway(DomainBackend backend) {
    this.backend = backend;
  }

  @GetMapping({"", "/{id}", "/{id}/comments", "/{id}/comments/{commentId}", "/{id}/author/photo"})
  ResponseEntity<byte[]> get(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return relay(jwt, request, null);
  }

  @PostMapping(
      value = {"", "/{id}/comments", "/{id}/reading"},
      consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<byte[]> post(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest request, @RequestBody JsonNode body) {
    return relay(jwt, request, body);
  }

  @PutMapping(
      value = {"/{id}", "/{id}/reaction", "/{id}/comments/{commentId}"},
      consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<byte[]> put(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest request, @RequestBody JsonNode body) {
    return relay(jwt, request, body);
  }

  @DeleteMapping({"/{id}", "/{id}/comments/{commentId}"})
  ResponseEntity<byte[]> delete(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return relay(jwt, request, null);
  }

  private ResponseEntity<byte[]> relay(Jwt jwt, HttpServletRequest request, Object body) {
    var headers = new LinkedHashMap<String, String>();
    for (String name : new String[] {"If-Match", "X-Request-ID"}) {
      var value = request.getHeader(name);
      if (value != null) headers.put(name, value);
    }
    String query = request.getQueryString();
    String path = request.getRequestURI() + (query == null ? "" : "?" + query);
    return backend.exchange(
        HttpMethod.valueOf(request.getMethod()),
        path,
        jwt.getTokenValue(),
        headers,
        body,
        body == null ? null : MediaType.APPLICATION_JSON);
  }
}
