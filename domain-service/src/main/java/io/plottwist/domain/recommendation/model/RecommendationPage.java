package io.plottwist.domain.recommendation.model;

import io.plottwist.domain.story.model.Story;
import java.util.List;

public record RecommendationPage(List<Story> items, String nextCursor, String mode) {}
