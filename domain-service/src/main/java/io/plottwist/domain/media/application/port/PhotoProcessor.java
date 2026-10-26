package io.plottwist.domain.media.application.port;

import io.plottwist.domain.media.model.SanitizedImage;

public interface PhotoProcessor {
  int MAX_INPUT = 5 * 1024 * 1024;
  int MAX_OUTPUT = 1024 * 1024;

  SanitizedImage sanitize(byte[] input);
}
