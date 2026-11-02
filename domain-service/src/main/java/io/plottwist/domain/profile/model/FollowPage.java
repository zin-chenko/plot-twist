package io.plottwist.domain.profile.model;

import java.util.List;

public record FollowPage(List<UserSummary> items, String nextCursor) {}
