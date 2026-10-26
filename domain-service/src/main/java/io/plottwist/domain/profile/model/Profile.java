package io.plottwist.domain.profile.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Profile(
    UUID accountId,
    String displayName,
    String givenName,
    String familyName,
    String bio,
    LocalDate birthDate,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Photo photo) {
  public record Photo(String sha256, int sizeBytes, String contentType) {}
}
