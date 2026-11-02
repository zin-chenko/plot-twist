package io.plottwist.domain.profile.model;

import java.util.UUID;

/** Explicit public projection. Personal names, email and birth date never enter this model. */
public record PublicProfile(UUID accountId, String displayName, String bio, String photoSha256) {}
