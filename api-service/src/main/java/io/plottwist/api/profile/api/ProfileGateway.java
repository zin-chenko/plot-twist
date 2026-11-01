package io.plottwist.api.profile.api;

import io.plottwist.api.shared.application.port.DomainBackend;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileGateway {
  private final DomainBackend backend;
  private static final int MAX_IMAGE = 5 * 1024 * 1024;

  public ProfileGateway(DomainBackend backend) {
    this.backend = backend;
  }

  @GetMapping
  ResponseEntity<byte[]> profile(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return relay(HttpMethod.GET, "/api/v1/profile", jwt, request, null, null);
  }

  @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<byte[]> save(
      @AuthenticationPrincipal Jwt jwt,
      HttpServletRequest request,
      @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
    return relay(HttpMethod.PUT, "/api/v1/profile", jwt, request, body, MediaType.APPLICATION_JSON);
  }

  @GetMapping("/photo")
  ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return relay(HttpMethod.GET, "/api/v1/profile/photo", jwt, request, null, null);
  }

  @PutMapping(value = "/photo", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  ResponseEntity<byte[]> upload(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request)
      throws IOException {
    byte[] image = request.getInputStream().readNBytes(MAX_IMAGE + 1);
    if (image.length > MAX_IMAGE)
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "Maximum image size is 5 MiB");
    return relay(
        HttpMethod.PUT,
        "/api/v1/profile/photo",
        jwt,
        request,
        image,
        MediaType.APPLICATION_OCTET_STREAM);
  }

  @DeleteMapping
  ResponseEntity<byte[]> deleteProfile(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return relay(HttpMethod.DELETE, "/api/v1/profile", jwt, request, null, null);
  }

  @DeleteMapping("/photo")
  ResponseEntity<byte[]> deletePhoto(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return relay(HttpMethod.DELETE, "/api/v1/profile/photo", jwt, request, null, null);
  }

  private ResponseEntity<byte[]> relay(
      HttpMethod method,
      String path,
      Jwt jwt,
      HttpServletRequest incoming,
      Object body,
      MediaType contentType) {
    var headers = new java.util.LinkedHashMap<String, String>();
    for (String name : new String[] {"If-Match", "If-None-Match", "X-Request-ID"}) {
      String value = incoming.getHeader(name);
      if (value != null) headers.put(name, value);
    }
    return backend.exchange(method, path, jwt.getTokenValue(), headers, body, contentType);
  }
}
