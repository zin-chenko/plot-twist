package io.plottwist.domain.story.model;

import java.time.Instant;
import java.util.UUID;

public record Story(
    UUID id,
    UUID authorId,
    String authorName,
    String authorPhotoSha256,
    String title,
    String body,
    long version,
    Instant createdAt,
    Instant updatedAt,
    long likes,
    long dislikes,
    int myReaction,
    long commentCount,
    String moderationStatus,
    String moderationReason,
    Long publishedVersion) {}
