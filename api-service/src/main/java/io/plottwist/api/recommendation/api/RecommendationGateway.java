package io.plottwist.api.recommendation.api;

import io.plottwist.api.shared.application.port.DomainBackend;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class RecommendationGateway {
  private final DomainBackend backend;

  public RecommendationGateway(DomainBackend backend) {
    this.backend = backend;
  }

  @GetMapping("/api/v1/recommendations")
  ResponseEntity<byte[]> feed(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    String query = request.getQueryString();
    return backend.exchange(
        HttpMethod.GET,
        "/api/v1/recommendations" + (query == null ? "" : "?" + query),
        jwt.getTokenValue(),
        Map.of(),
        null,
        null);
  }
}
