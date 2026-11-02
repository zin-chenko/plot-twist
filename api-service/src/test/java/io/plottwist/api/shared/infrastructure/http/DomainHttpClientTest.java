package io.plottwist.api.shared.infrastructure.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class DomainHttpClientTest {
  @Test
  void encodedSearchSurvivesTheRelayWithoutDoubleEncodingOrParameterInjection() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var query = new AtomicReference<String>();
    var authorization = new AtomicReference<String>();
    server.createContext(
        "/api/v1/profiles/example/followers",
        exchange -> {
          query.set(exchange.getRequestURI().getRawQuery());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("Cache-Control", "no-store");
          byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var client =
          new DomainHttpClient(
              RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
      String path =
          UriComponentsBuilder.fromPath("/api/v1/profiles/example/followers")
              .queryParam("q", "{search}")
              .encode()
              .buildAndExpand("Анна + & 100%_")
              .toUriString();
      var response = client.exchange(HttpMethod.GET, path, "token", Map.of(), null, null);
      assertEquals(200, response.getStatusCode().value());
      assertEquals(path.substring(path.indexOf('?') + 1), query.get());
      assertEquals("Bearer token", authorization.get());
      assertEquals("no-store", response.getHeaders().getCacheControl());
    } finally {
      server.stop(0);
    }
  }
}
