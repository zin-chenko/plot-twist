package io.plottwist.domain.diagnostics;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServiceController {
  @GetMapping("/api/character")
  public Map<String, String> info() {
    return Map.of("service", "domain-service", "status", "running");
  }
}
