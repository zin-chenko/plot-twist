package io.plottwist.domain.profile.api.request;

import io.plottwist.domain.profile.model.ProfileDetails;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record UpdateProfileRequest(
    @NotBlank @Size(max = 80) String displayName,
    @Size(max = 80) String givenName,
    @Size(max = 80) String familyName,
    @Size(max = 2000) String bio,
    @PastOrPresent LocalDate birthDate) {
  public ProfileDetails toDetails() {
    return new ProfileDetails(displayName, givenName, familyName, bio, birthDate);
  }
}
