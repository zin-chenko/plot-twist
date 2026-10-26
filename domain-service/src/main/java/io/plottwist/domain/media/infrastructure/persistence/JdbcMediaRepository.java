package io.plottwist.domain.media.infrastructure.persistence;

import io.plottwist.domain.media.application.port.MediaRepository;
import io.plottwist.domain.media.model.SanitizedImage;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMediaRepository implements MediaRepository {
  private final JdbcTemplate jdbc;

  public JdbcMediaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void reserve(UUID id, UUID owner, String key, SanitizedImage image) {
    jdbc.update(
        "INSERT INTO media_objects(id, owner_id, object_key, sha256, size_bytes, state) VALUES (?,"
            + " ?, ?, ?, ?, 'PENDING')",
        id,
        owner,
        key,
        image.sha256(),
        image.bytes().length);
  }

  @Override
  public boolean activate(UUID id) {
    return jdbc.update(
            "UPDATE media_objects SET state = 'ACTIVE' WHERE id = ? AND state = 'PENDING'", id)
        == 1;
  }

  @Override
  public void retire(UUID id) {
    jdbc.update(
        "UPDATE media_objects SET state = 'DELETING', delete_after = now() + interval '1 minute'"
            + " WHERE id = ?",
        id);
  }

  @Override
  public void failReservation(UUID id) {
    jdbc.update(
        "UPDATE media_objects SET state = 'DELETING', delete_after = now() + interval '1 minute'"
            + " WHERE id = ? AND state = 'PENDING'",
        id);
  }
}
