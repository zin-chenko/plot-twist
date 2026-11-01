package io.plottwist.domain.story.model;

import java.util.List;

public record FeedPage<T>(List<T> items, String nextCursor) {}
