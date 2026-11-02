package io.plottwist.domain.chat.application;

public final class ChatException extends RuntimeException {
  public enum Reason {
    NOT_FOUND,
    INVALID,
    CONFLICT
  }

  private final Reason reason;

  public ChatException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
