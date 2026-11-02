package io.plottwist.api.attachment.infrastructure.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.plottwist.api.attachment.model.StreamMetadata;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class StreamingMediaBackendTest {
  @Test
  void forwardsUploadAndNativeRangesWithoutLeakingBearerTokens() throws Exception {
    byte[] file = new byte[16384];
    new Random(1).nextBytes(file);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var received = new AtomicReference<byte[]>();
    var authorization = new AtomicReference<String>();
    var requested = new AtomicReference<String>();
    server.createContext(
        "/api/v1/story-media",
        exchange -> {
          try {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestMethod().equals("POST")) {
              received.set(exchange.getRequestBody().readAllBytes());
              byte[] reply =
                  "{\"id\":\"fixture\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(201, reply.length);
              exchange.getResponseBody().write(reply);
            } else {
              requested.set(exchange.getRequestHeaders().getFirst("Range"));
              exchange.getResponseHeaders().set("Content-Type", "video/mp4");
              exchange.getResponseHeaders().set("Content-Range", "bytes 1-3/16384");
              exchange.sendResponseHeaders(206, 3);
              exchange.getResponseBody().write(file, 1, 3);
            }
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var backend =
          new StreamingMediaBackend(
              RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
      var metadata = new AtomicReference<StreamMetadata>();
      var result = new ByteArrayOutputStream();
      backend.transfer(
          "POST",
          "/api/v1/story-media",
          "fixture-token",
          Map.of(),
          new ByteArrayInputStream(file),
          file.length,
          metadata::set,
          result);
      assertArrayEquals(file, received.get());
      assertEquals("Bearer fixture-token", authorization.get());
      assertEquals(201, metadata.get().status());
      result.reset();
      backend.transfer(
          "GET",
          "/api/v1/story-media/fixture/content?ticket=fixture",
          null,
          Map.of("Range", "bytes=1-3"),
          null,
          0,
          metadata::set,
          result);
      assertNull(authorization.get());
      assertEquals("bytes=1-3", requested.get());
      assertEquals(206, metadata.get().status());
      assertArrayEquals(Arrays.copyOfRange(file, 1, 4), result.toByteArray());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsDeclaredOversizeBeforeSendingData() {
    var backend = new StreamingMediaBackend(RestClient.builder(), "http://127.0.0.1:1");
    var failure =
        assertThrows(
            ResponseStatusException.class,
            () ->
                backend.transfer(
                    "POST",
                    "/api/v1/story-media",
                    "fixture",
                    Map.of(),
                    InputStream.nullInputStream(),
                    52428801,
                    m -> {},
                    new ByteArrayOutputStream()));
    assertEquals(413, failure.getStatusCode().value());
  }
}
