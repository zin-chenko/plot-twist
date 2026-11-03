package io.plottwist.domain.story.model;

import java.time.Instant;
import java.util.UUID;

public record StoryComment(
    UUID id,
    UUID storyId,
    UUID authorId,
    String authorName,
    String body,
    Instant createdAt,
    Instant updatedAt,
    long version,
    UUID parentId,
    UUID rootId,
    String replyToAuthorName,
    long likes,
    boolean myLiked,
    long replyCount,
    boolean deleted) {}
