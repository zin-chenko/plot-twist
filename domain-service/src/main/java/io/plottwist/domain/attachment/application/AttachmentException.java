package io.plottwist.domain.attachment.application;

public class AttachmentException extends RuntimeException {
  public enum Reason {
    NOT_FOUND,
    INVALID,
    TOO_LARGE,
    UNSUPPORTED,
    BUSY,
    CONFLICT,
    UNAVAILABLE
  }

  private final Reason reason;

  public AttachmentException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
