package io.plottwist.domain.story.application;

public class StoryException extends RuntimeException {
  public enum Reason {
    NOT_FOUND,
    FORBIDDEN,
    VERSION_CHANGED,
    INVALID_INPUT
  }

  private final Reason reason;

  public StoryException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
