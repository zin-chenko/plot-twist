package io.plottwist.domain.media.application;

public final class MediaException extends RuntimeException {
  public enum Reason {
    INVALID_IMAGE,
    TOO_LARGE,
    BUSY,
    UNSUPPORTED_FORMAT,
    UNAVAILABLE
  }

  private final Reason reason;

  public MediaException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
