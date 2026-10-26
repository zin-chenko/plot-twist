package io.plottwist.domain.profile.application;

public final class ProfileException extends RuntimeException {
  public enum Reason {
    NOT_FOUND,
    VERSION_CHANGED,
    INVALID_INPUT,
    UPLOAD_EXPIRED,
    UNAVAILABLE
  }

  private final Reason reason;

  public ProfileException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public static ProfileException versionChanged() {
    return new ProfileException(
        Reason.VERSION_CHANGED,
        "Resource version changed; fetch it and retry with the current ETag");
  }
}
