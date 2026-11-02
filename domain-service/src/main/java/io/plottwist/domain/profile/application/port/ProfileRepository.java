package io.plottwist.domain.profile.application.port;

import io.plottwist.domain.profile.model.Profile;
import io.plottwist.domain.profile.model.ProfileDetails;
import io.plottwist.domain.profile.model.PublicProfile;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository {
  Optional<Profile> find(UUID owner);

  Optional<PublicProfile> findPublic(UUID account, UUID viewer);

  boolean create(UUID owner, ProfileDetails details);

  boolean update(UUID owner, long version, ProfileDetails details);

  Optional<LockedProfile> lock(UUID owner);

  boolean linkPhoto(UUID owner, long version, UUID photoId);

  boolean delete(UUID owner, long version);

  String activePhotoKey(UUID owner);

  record LockedProfile(UUID photoId) {}
}
