package io.plottwist.api.attachment.infrastructure.http;

import io.plottwist.api.attachment.application.port.MediaBackend;
import io.plottwist.api.attachment.model.StreamMetadata;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class StreamingMediaBackend implements MediaBackend {
  private final RestClient client;

  public StreamingMediaBackend(
      RestClient.Builder builder, @Value("${domain.base-url:http://localhost:8082}") String base) {
    var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(120));
    client = builder.baseUrl(base).requestFactory(factory).build();
  }

  public void transfer(
      String method,
      String path,
      String token,
      Map<String, String> headers,
      InputStream input,
      long length,
      Consumer<StreamMetadata> metadata,
      OutputStream target)
      throws IOException {
    var oversized = new AtomicBoolean();
    try {
      var call =
          client
              .method(HttpMethod.valueOf(method))
              .uri(path)
              .headers(
                  h -> {
                    if (token != null) h.setBearerAuth(token);
                    headers.forEach(h::set);
                  });
      if (input != null) {
        if (length > 52428800)
          throw new ResponseStatusException(
              HttpStatus.PAYLOAD_TOO_LARGE, "Maximum file size is 50 MiB");
        call.contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (length >= 0) call.contentLength(length);
        call.body(
            (StreamingHttpOutputMessage.Body)
                out -> {
                  byte[] buffer = new byte[65536];
                  long count = 0;
                  int n;
                  while ((n = input.read(buffer)) != -1) {
                    count += n;
                    if (count > 52428800) {
                      oversized.set(true);
                      throw new IOException("Upload limit reached");
                    }
                    out.write(buffer, 0, n);
                  }
                });
      }
      call.exchange(
          (request, response) -> {
            var copied = new LinkedHashMap<String, List<String>>();
            for (var name :
                List.of(
                    "Content-Type",
                    "Content-Length",
                    "Content-Range",
                    "Accept-Ranges",
                    "ETag",
                    "Cache-Control",
                    "Content-Disposition",
                    "Content-Security-Policy",
                    "Referrer-Policy",
                    "X-Content-Type-Options",
                    "Retry-After",
                    "Location")) {
              var values = response.getHeaders().get(name);
              if (values != null) copied.put(name, List.copyOf(values));
            }
            metadata.accept(new StreamMetadata(response.getStatusCode().value(), copied));
            long max =
                response.getStatusCode().is2xxSuccessful() && input == null ? 52428800 : 65536;
            byte[] buffer = new byte[65536];
            long count = 0;
            int n;
            while ((n = response.getBody().read(buffer)) != -1) {
              count += n;
              if (count > max) throw new IOException("Unexpected backend response size");
              target.write(buffer, 0, n);
            }
            return null;
          });
    } catch (RestClientException unavailable) {
      if (oversized.get())
        throw new ResponseStatusException(
            HttpStatus.PAYLOAD_TOO_LARGE, "Maximum file size is 50 MiB");
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Media service is temporarily unavailable");
    }
  }
}
