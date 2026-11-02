package io.plottwist.domain.profile.application;

import static io.plottwist.domain.profile.application.ProfileException.Reason.*;

import io.plottwist.domain.media.application.port.*;
import io.plottwist.domain.media.model.ImageDigest;
import io.plottwist.domain.profile.application.port.ProfileRepository;
import io.plottwist.domain.profile.model.Profile;
import io.plottwist.domain.profile.model.ProfileDetails;
import io.plottwist.domain.profile.model.PublicProfile;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProfileService {
  private final ProfileRepository profiles;
  private final MediaRepository media;
  private final TransactionTemplate transactions;
  private final ObjectStorage storage;
  private final PhotoProcessor images;

  public ProfileService(
      ProfileRepository profiles,
      MediaRepository media,
      TransactionTemplate transactions,
      ObjectStorage storage,
      PhotoProcessor images) {
    this.profiles = profiles;
    this.media = media;
    this.transactions = transactions;
    this.storage = storage;
    this.images = images;
  }

  public Profile get(UUID owner) {
    return profiles
        .find(owner)
        .orElseThrow(() -> new ProfileException(NOT_FOUND, "Profile not found"));
  }

  public PublicProfile publicProfile(UUID account) {
    return publicProfile(account, null);
  }

  public PublicProfile publicProfile(UUID account, UUID viewer) {
    return profiles
        .findPublic(account, viewer)
        .orElseThrow(() -> new ProfileException(NOT_FOUND, "Profile not found"));
  }

  public byte[] publicPhoto(PublicProfile profile) {
    if (profile.photoSha256() == null) throw new ProfileException(NOT_FOUND, "Photo not found");
    byte[] bytes = storage.get(profiles.activePhotoKey(profile.accountId()));
    if (!ImageDigest.sha256(bytes).equals(profile.photoSha256()))
      throw new ProfileException(UNAVAILABLE, "Photo changed during read; retry");
    return bytes;
  }

  @Transactional
  public Profile save(UUID owner, ProfileDetails body, Long version, boolean create) {
    rejectNul(body.displayName(), body.givenName(), body.familyName(), body.bio());
    boolean changed = create ? profiles.create(owner, body) : profiles.update(owner, version, body);
    if (!changed) throw ProfileException.versionChanged();
    return get(owner);
  }

  public void checkVersion(UUID owner, long expected) {
    if (get(owner).version() != expected) throw ProfileException.versionChanged();
  }

  public Profile upload(UUID owner, long version, byte[] input) {
    checkVersion(owner, version);
    var image = images.sanitize(input);
    UUID id = UUID.randomUUID();
    String key = "avatars/" + owner + "/" + id + ".jpg";
    // Reservation commits before S3. Activation and profile CAS share a transaction.
    transactions.executeWithoutResult(status -> media.reserve(id, owner, key, image));
    try {
      storage.put(key, image.bytes());
      return transactions.execute(
          status -> {
            UUID old = lockPhoto(owner);
            if (!profiles.linkPhoto(owner, version, id)) throw ProfileException.versionChanged();
            if (!media.activate(id))
              throw new ProfileException(UPLOAD_EXPIRED, "Upload reservation expired; retry");
            if (old != null) media.retire(old);
            return get(owner);
          });
    } catch (RuntimeException failed) {
      transactions.executeWithoutResult(status -> media.failReservation(id));
      throw failed;
    }
  }

  public byte[] photo(UUID owner, Profile profile) {
    if (profile.photo() == null) throw new ProfileException(NOT_FOUND, "Photo not found");
    byte[] bytes = storage.get(profiles.activePhotoKey(owner));
    if (!ImageDigest.sha256(bytes).equals(profile.photo().sha256()))
      throw new ProfileException(UNAVAILABLE, "Photo changed during read; retry");
    return bytes;
  }

  @Transactional
  public Profile removePhoto(UUID owner, long version) {
    get(owner);
    checkVersion(owner, version);
    UUID id = lockPhoto(owner);
    if (!profiles.linkPhoto(owner, version, null)) throw ProfileException.versionChanged();
    if (id != null) media.retire(id);
    return get(owner);
  }

  @Transactional
  public void delete(UUID owner, long version) {
    UUID photo = lockPhoto(owner);
    if (!profiles.delete(owner, version)) throw ProfileException.versionChanged();
    if (photo != null) media.retire(photo);
  }

  private UUID lockPhoto(UUID owner) {
    return profiles.lock(owner).orElseThrow(ProfileException::versionChanged).photoId();
  }

  private static void rejectNul(String... values) {
    for (String value : values)
      if (value != null && value.indexOf(0) >= 0)
        throw new ProfileException(INVALID_INPUT, "NUL characters are not supported");
  }
}
