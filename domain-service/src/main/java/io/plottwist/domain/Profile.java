package io.plottwist.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.constraints.*;

public record Profile(UUID accountId, String displayName, String givenName, String familyName,
                      String bio, LocalDate birthDate, long version, Instant createdAt,
                      Instant updatedAt, Photo photo) {
    public record Input(@NotBlank @Size(max=80) String displayName,
                        @Size(max=80) String givenName, @Size(max=80) String familyName,
                        @Size(max=2000) String bio, @PastOrPresent LocalDate birthDate) {}
    public record Photo(String sha256, int sizeBytes, String contentType, String url) {}
}
