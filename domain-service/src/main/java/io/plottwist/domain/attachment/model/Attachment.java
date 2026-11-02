package io.plottwist.domain.attachment.model;

import java.util.UUID;

public record Attachment(
    UUID id,
    Kind kind,
    String contentType,
    long sizeBytes,
    int width,
    int height,
    Double durationSeconds) {
  public enum Kind {
    IMAGE,
    GIF,
    VIDEO
  }
}
