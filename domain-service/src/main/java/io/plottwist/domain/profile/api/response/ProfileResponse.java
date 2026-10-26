package io.plottwist.domain.profile.api.response;

import io.plottwist.domain.profile.model.Profile;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProfileResponse(
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
  public record Photo(String sha256, int sizeBytes, String contentType, String url) {}

  public static ProfileResponse from(Profile profile) {
    var photo = profile.photo();
    return new ProfileResponse(
        profile.accountId(),
        profile.displayName(),
        profile.givenName(),
        profile.familyName(),
        profile.bio(),
        profile.birthDate(),
        profile.version(),
        profile.createdAt(),
        profile.updatedAt(),
        photo == null
            ? null
            : new Photo(
                photo.sha256(), photo.sizeBytes(), photo.contentType(), "/api/v1/profile/photo"));
  }
}
