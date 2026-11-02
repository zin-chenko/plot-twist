package io.plottwist.moderation.contract;

import java.util.*;

public record ModerationDecision(
    int schemaVersion,
    UUID jobId,
    UUID storyId,
    long revision,
    String contentHash,
    String policyVersion,
    String rulesHash,
    Outcome outcome,
    List<String> ruleIds) {
  public enum Outcome {
    APPROVE,
    BLOCK,
    REVIEW
  }

  public ModerationDecision {
    if (schemaVersion != 1
        || jobId == null
        || storyId == null
        || revision < 0
        || contentHash == null
        || !contentHash.matches("[a-f0-9]{64}")
        || policyVersion == null
        || !policyVersion.matches("[a-z0-9-]{1,40}")
        || rulesHash == null
        || !rulesHash.matches("[a-f0-9]{64}")
        || outcome == null
        || ruleIds == null
        || ruleIds.size() > 10
        || ruleIds.stream().anyMatch(s -> s == null || !s.matches("[a-z0-9-]{1,64}")))
      throw new IllegalArgumentException("Invalid moderation decision");
    ruleIds = List.copyOf(ruleIds);
  }
}
