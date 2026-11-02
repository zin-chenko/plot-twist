package io.plottwist.api.chat.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.plottwist.api.shared.application.port.DomainBackend;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/chats")
public class ChatGateway {
  private final DomainBackend domain;

  public ChatGateway(DomainBackend domain) {
    this.domain = domain;
  }

  @GetMapping
  ResponseEntity<byte[]> inbox(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    var uri = UriComponentsBuilder.fromPath("/api/v1/chats").queryParam("limit", limit);
    var values = new LinkedHashMap<String, Object>();
    if (cursor != null) {
      uri.queryParam("cursor", "{cursor}");
      values.put("cursor", cursor);
    }
    return relay(jwt, HttpMethod.GET, uri.encode().buildAndExpand(values).toUriString(), null);
  }

  @PostMapping
  ResponseEntity<byte[]> open(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
    return relay(jwt, HttpMethod.POST, "/api/v1/chats", body);
  }

  @GetMapping("/{id}")
  ResponseEntity<byte[]> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return relay(jwt, HttpMethod.GET, "/api/v1/chats/" + id, null);
  }

  @GetMapping("/{id}/messages")
  ResponseEntity<byte[]> messages(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(required = false) Long before,
      @RequestParam(required = false) Long after,
      @RequestParam(defaultValue = "50") int limit) {
    var uri =
        UriComponentsBuilder.fromPath("/api/v1/chats/" + id + "/messages")
            .queryParam("limit", limit);
    if (before != null) uri.queryParam("before", before);
    if (after != null) uri.queryParam("after", after);
    return relay(jwt, HttpMethod.GET, uri.build().toUriString(), null);
  }

  @PostMapping("/{id}/messages")
  ResponseEntity<byte[]> send(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody JsonNode body) {
    return relay(jwt, HttpMethod.POST, "/api/v1/chats/" + id + "/messages", body);
  }

  @PutMapping("/{id}/read")
  ResponseEntity<byte[]> read(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody JsonNode body) {
    return relay(jwt, HttpMethod.PUT, "/api/v1/chats/" + id + "/read", body);
  }

  private ResponseEntity<byte[]> relay(Jwt jwt, HttpMethod method, String path, JsonNode body) {
    try {
      String id = jwt.getClaimAsString("account_id");
      if (id == null || !id.equals(jwt.getSubject())) throw new IllegalArgumentException();
      UUID.fromString(id);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "A verified user account is required");
    }
    return domain.exchange(
        method,
        path,
        jwt.getTokenValue(),
        Map.of(),
        body,
        body == null ? null : MediaType.APPLICATION_JSON);
  }
}
