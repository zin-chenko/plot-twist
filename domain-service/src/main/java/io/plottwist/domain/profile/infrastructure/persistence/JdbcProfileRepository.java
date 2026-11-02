package io.plottwist.domain.profile.infrastructure.persistence;

import io.plottwist.domain.profile.application.port.ProfileRepository;
import io.plottwist.domain.profile.model.Profile;
import io.plottwist.domain.profile.model.ProfileDetails;
import io.plottwist.domain.profile.model.PublicProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProfileRepository implements ProfileRepository {
  private static final String SELECT =
      """
      SELECT p.*, m.sha256, m.size_bytes FROM user_profiles p
      LEFT JOIN media_objects m ON m.id = p.photo_id AND m.state = 'ACTIVE' WHERE p.account_id = ?
      """;
  private final JdbcTemplate jdbc;

  public JdbcProfileRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Profile> find(UUID owner) {
    return jdbc.query(SELECT, this::map, owner).stream().findFirst();
  }

  @Override
  public Optional<PublicProfile> findPublic(UUID account) {
    return jdbc
        .query(
            """
        SELECT a.id,COALESCE(p.display_name,'Читатель') AS display_name,p.bio,m.sha256
        FROM (SELECT ?::uuid AS id) a
        LEFT JOIN user_profiles p ON p.account_id=a.id
        LEFT JOIN media_objects m ON m.id=p.photo_id AND m.state='ACTIVE'
        WHERE p.account_id IS NOT NULL OR EXISTS(
          SELECT 1 FROM stories s WHERE s.author_id=a.id AND s.published_version IS NOT NULL)
        """,
            (row, n) ->
                new PublicProfile(
                    row.getObject("id", UUID.class),
                    row.getString("display_name"),
                    row.getString("bio"),
                    row.getString("sha256")),
            account)
        .stream()
        .findFirst();
  }

  @Override
  public boolean create(UUID owner, ProfileDetails body) {
    return jdbc.update(
            """
            INSERT INTO user_profiles(account_id, display_name, given_name, family_name, bio, birth_date)
            VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
            """,
            owner,
            body.displayName().strip(),
            body.givenName(),
            body.familyName(),
            body.bio(),
            body.birthDate())
        == 1;
  }

  @Override
  public boolean update(UUID owner, long version, ProfileDetails body) {
    return jdbc.update(
            """
            UPDATE user_profiles SET display_name = ?, given_name = ?, family_name = ?, bio = ?, birth_date = ?,
                version = version + 1, updated_at = now() WHERE account_id = ? AND version = ?
            """,
            body.displayName().strip(),
            body.givenName(),
            body.familyName(),
            body.bio(),
            body.birthDate(),
            owner,
            version)
        == 1;
  }

  @Override
  public Optional<LockedProfile> lock(UUID owner) {
    return jdbc
        .query(
            "SELECT photo_id FROM user_profiles WHERE account_id = ? FOR UPDATE",
            (row, n) -> new LockedProfile(row.getObject("photo_id", UUID.class)),
            owner)
        .stream()
        .findFirst();
  }

  @Override
  public boolean linkPhoto(UUID owner, long version, UUID photoId) {
    return jdbc.update(
            "UPDATE user_profiles SET photo_id = ?, version = version + 1, updated_at = now() WHERE"
                + " account_id = ? AND version = ?",
            photoId,
            owner,
            version)
        == 1;
  }

  @Override
  public boolean delete(UUID owner, long version) {
    return jdbc.update(
            "DELETE FROM user_profiles WHERE account_id = ? AND version = ?", owner, version)
        == 1;
  }

  @Override
  public String activePhotoKey(UUID owner) {
    return jdbc.queryForObject(
        "SELECT m.object_key FROM user_profiles p JOIN media_objects m ON p.photo_id = m.id WHERE"
            + " p.account_id = ? AND m.state = 'ACTIVE'",
        String.class,
        owner);
  }

  private Profile map(ResultSet row, int index) throws SQLException {
    String hash = row.getString("sha256");
    return new Profile(
        row.getObject("account_id", UUID.class),
        row.getString("display_name"),
        row.getString("given_name"),
        row.getString("family_name"),
        row.getString("bio"),
        row.getObject("birth_date", LocalDate.class),
        row.getLong("version"),
        row.getTimestamp("created_at").toInstant(),
        row.getTimestamp("updated_at").toInstant(),
        hash == null ? null : new Profile.Photo(hash, row.getInt("size_bytes"), "image/jpeg"));
  }
}
