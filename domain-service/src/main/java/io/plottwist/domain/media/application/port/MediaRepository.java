package io.plottwist.domain.media.application.port;

import io.plottwist.domain.media.model.SanitizedImage;
import java.util.UUID;

public interface MediaRepository {
  void reserve(UUID id, UUID owner, String key, SanitizedImage image);

  boolean activate(UUID id);

  void retire(UUID id);

  void failReservation(UUID id);
}
