package io.plottwist.api.shared.infrastructure.http;

import io.plottwist.api.shared.application.port.DomainBackend;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DomainHttpClient implements DomainBackend {
  private final RestClient client;

  public DomainHttpClient(
      RestClient.Builder builder, @Value("${domain.base-url:http://localhost:8082}") String url) {
    var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(20));
    client = builder.baseUrl(url).requestFactory(factory).build();
  }

  @Override
  public ResponseEntity<byte[]> exchange(
      HttpMethod method,
      String path,
      String accessToken,
      Map<String, String> conditionalHeaders,
      Object body,
      MediaType contentType) {
    try {
      var request =
          client
              .method(method)
              .uri(path)
              .headers(
                  headers -> {
                    headers.setBearerAuth(accessToken);
                    conditionalHeaders.forEach(headers::set);
                  });
      if (contentType != null) request.contentType(contentType);
      if (body != null) request.body(body);
      return request.exchange(
          (outgoing, response) -> {
            var headers = new HttpHeaders();
            for (String name :
                new String[] {
                  "Content-Type",
                  "Location",
                  "ETag",
                  "Cache-Control",
                  "Content-Disposition",
                  "Retry-After",
                  "X-Content-Type-Options"
                }) {
              if (response.getHeaders().containsKey(name))
                headers.put(name, response.getHeaders().get(name));
            }
            byte[] bytes = response.getBody().readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024)
              throw new ResponseStatusException(
                  HttpStatus.BAD_GATEWAY, "Unexpected domain response size");
            return new ResponseEntity<>(bytes, headers, response.getStatusCode());
          });
    } catch (RestClientException unavailable) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Domain service is temporarily unavailable");
    }
  }
}
