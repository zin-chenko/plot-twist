package io.plottwist.api.diagnostics;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DomainClient {
  private final RestClient client;

  public DomainClient(
      RestClient.Builder builder,
      @Value("${domain.base-url:http://localhost:8082}") String baseUrl) {
    var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(5));
    client = builder.baseUrl(baseUrl).requestFactory(factory).build();
  }

  public Map<String, String> info(String requestId, String accessToken) {
    try {
      return client
          .get()
          .uri("/api/character")
          .headers(headers -> headers.setBearerAuth(accessToken))
          .header("X-Request-ID", requestId)
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, String>>() {});
    } catch (HttpClientErrorException error) {
      if (error.getStatusCode().value() == 401 || error.getStatusCode().value() == 403) {
        throw new ResponseStatusException(error.getStatusCode(), "Domain access denied", error);
      }
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid domain response", error);
    } catch (RestClientException error) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Domain service is unavailable", error);
    }
  }
}
