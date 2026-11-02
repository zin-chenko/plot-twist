package io.plottwist.moderation.contract;

import java.util.*;

public record ModerationTask(
    int schemaVersion,
    UUID jobId,
    UUID storyId,
    long revision,
    String contentHash,
    String title,
    String body,
    String policyVersion) {
  public ModerationTask {
    if (schemaVersion != 1
        || jobId == null
        || storyId == null
        || revision < 0
        || title == null
        || title.isBlank()
        || title.length() > 160
        || body == null
        || body.isBlank()
        || body.length() > 20000
        || policyVersion == null
        || !policyVersion.matches("[a-z0-9-]{1,40}")
        || !ContentHash.of(title, body).equals(contentHash))
      throw new IllegalArgumentException("Invalid moderation task");
  }
}
