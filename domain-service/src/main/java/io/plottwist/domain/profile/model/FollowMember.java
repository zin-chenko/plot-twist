package io.plottwist.domain.profile.model;

/** Persistence result with the ordering key; relationship timestamps are not exposed in JSON. */
public record FollowMember(UserSummary user, FollowPosition position) {}
