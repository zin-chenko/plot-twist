package io.plottwist.api;

import java.io.IOException;
import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileGateway {
    private final RestClient client;
    private static final int MAX_IMAGE = 5 * 1024 * 1024;

    ProfileGateway(RestClient.Builder builder, @Value("${domain.base-url:http://localhost:8082}") String url) {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(20));
        client = builder.baseUrl(url).requestFactory(factory).build();
    }

    @GetMapping
    ResponseEntity<byte[]> profile(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return relay(HttpMethod.GET, "/api/v1/profile", jwt, request, null, null);
    }

    @PutMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> save(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
            @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return relay(HttpMethod.PUT, "/api/v1/profile", jwt, request, body, MediaType.APPLICATION_JSON);
    }

    @GetMapping("/photo")
    ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return relay(HttpMethod.GET, "/api/v1/profile/photo", jwt, request, null, null);
    }

    @PutMapping(value="/photo", consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<byte[]> upload(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) throws IOException {
        byte[] image = request.getInputStream().readNBytes(MAX_IMAGE + 1);
        if (image.length > MAX_IMAGE) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Maximum image size is 5 MiB");
        return relay(HttpMethod.PUT, "/api/v1/profile/photo", jwt, request, image, MediaType.APPLICATION_OCTET_STREAM);
    }

    @DeleteMapping
    ResponseEntity<byte[]> deleteProfile(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return relay(HttpMethod.DELETE, "/api/v1/profile", jwt, request, null, null);
    }

    @DeleteMapping("/photo")
    ResponseEntity<byte[]> deletePhoto(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return relay(HttpMethod.DELETE, "/api/v1/profile/photo", jwt, request, null, null);
    }

    private ResponseEntity<byte[]> relay(HttpMethod method, String path, Jwt jwt, HttpServletRequest incoming,
                                         Object body, MediaType contentType) {
        try {
            var request = client.method(method).uri(path).headers(headers -> {
                headers.setBearerAuth(jwt.getTokenValue());
                for (String name : new String[]{"If-Match", "If-None-Match", "X-Request-ID"}) {
                    String value = incoming.getHeader(name);
                    if (value != null) headers.set(name, value);
                }
            });
            if (contentType != null) request.contentType(contentType);
            if (body != null) request.body(body);
            return request.exchange((outgoing, response) -> {
                var headers = new HttpHeaders();
                for (String name : new String[]{"Content-Type", "Location", "ETag", "Cache-Control", "Content-Disposition", "Retry-After", "X-Content-Type-Options"}) {
                    if (response.getHeaders().containsKey(name)) headers.put(name, response.getHeaders().get(name));
                }
                byte[] bytes = response.getBody().readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length > 2 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unexpected domain response size");
                return new ResponseEntity<>(bytes, headers, response.getStatusCode());
            });
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Profile service is temporarily unavailable");
        }
    }
}
