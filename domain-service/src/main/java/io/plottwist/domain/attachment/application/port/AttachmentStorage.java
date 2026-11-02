package io.plottwist.domain.attachment.application.port;

import java.io.InputStream;
import java.nio.file.Path;

public interface AttachmentStorage {
  void put(String key, Path file, String contentType, String sha256);

  interface Read extends AutoCloseable {
    InputStream stream();

    void abort();

    void close() throws java.io.IOException;
  }

  Read open(String key, long start, long end);

  void delete(String key);
}
