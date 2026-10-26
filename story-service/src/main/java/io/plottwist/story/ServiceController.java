package io.plottwist.story;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServiceController {
    @GetMapping("/api/story")
    public Map<String, String> info() {
        return Map.of("service", "story-service", "status", "running");
    }
}
