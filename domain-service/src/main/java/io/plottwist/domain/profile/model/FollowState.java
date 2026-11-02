package io.plottwist.domain.profile.model;

/** Counts describe the viewed account; following describes the current viewer's outgoing edge. */
public record FollowState(boolean following, long followersCount, long followingCount) {}
