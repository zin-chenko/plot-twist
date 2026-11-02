package io.plottwist.api.attachment.application.port;

import io.plottwist.api.attachment.model.StreamMetadata;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

public interface MediaBackend {
  void transfer(
      String method,
      String path,
      String token,
      Map<String, String> headers,
      InputStream input,
      long length,
      Consumer<StreamMetadata> metadata,
      OutputStream target)
      throws IOException;
}
