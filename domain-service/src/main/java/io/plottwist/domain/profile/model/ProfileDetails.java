package io.plottwist.domain.profile.model;

import java.time.LocalDate;

public record ProfileDetails(
    String displayName, String givenName, String familyName, String bio, LocalDate birthDate) {}
