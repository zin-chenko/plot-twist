package io.plottwist.domain.media.model;

public record SanitizedImage(byte[] bytes, String sha256) {
  @Override
  public String toString() {
    return "SanitizedImage[redacted]";
  }
}
