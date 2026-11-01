package io.plottwist.api.shared.application.port;

import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP relay boundary: preserves the domain response status, version and privacy headers. */
public interface DomainBackend {
  ResponseEntity<byte[]> exchange(
      HttpMethod method,
      String path,
      String accessToken,
      Map<String, String> conditionalHeaders,
      Object body,
      MediaType contentType);
}
