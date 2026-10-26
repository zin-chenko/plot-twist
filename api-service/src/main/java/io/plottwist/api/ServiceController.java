package io.plottwist.api;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServiceController {
    private final DomainClient domain;
    private final String instance;

    public ServiceController(DomainClient domain, @Value("${HOSTNAME:local}") String instance) {
        this.domain = domain;
        this.instance = instance;
    }

    @GetMapping("/api/story")
    public ResponseEntity<Map<String, String>> info() {
        return ResponseEntity.ok().header("X-Backend-Instance", instance)
            .body(Map.of("service", "api-service", "status", "running"));
    }

    @GetMapping("/api/character")
    public Map<String, String> character(
            @RequestHeader(value = "X-Request-ID", defaultValue = "") String requestId) {
        return domain.info(requestId);
    }
}
