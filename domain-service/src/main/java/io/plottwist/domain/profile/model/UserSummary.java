package io.plottwist.domain.profile.model;

import java.util.UUID;

public record UserSummary(
    UUID accountId, String displayName, String photoSha256, boolean following) {}
