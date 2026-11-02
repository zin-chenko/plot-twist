package io.plottwist.domain.profile.infrastructure.persistence;

import io.plottwist.domain.profile.application.port.FollowRepository;
import io.plottwist.domain.profile.model.FollowState;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFollowRepository implements FollowRepository {
  private final JdbcTemplate jdbc;

  public JdbcFollowRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lock(UUID viewer, UUID target) {
    // Serialize this edge across replicas without locking every subscriber of a popular author.
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,48))", viewer + "/" + target);
  }

  public void setFollowing(UUID viewer, UUID target, boolean following) {
    if (following)
      jdbc.update(
          "INSERT INTO user_follows(follower_id,following_id) VALUES(?,?) ON CONFLICT DO NOTHING",
          viewer,
          target);
    else
      jdbc.update(
          "DELETE FROM user_follows WHERE follower_id=? AND following_id=?", viewer, target);
  }

  public FollowState state(UUID viewer, UUID target) {
    return jdbc.queryForObject(
        """
        SELECT EXISTS(SELECT 1 FROM user_follows WHERE follower_id=? AND following_id=?) AS following,
          (SELECT count(*) FROM user_follows WHERE following_id=?) AS followers_count,
          (SELECT count(*) FROM user_follows WHERE follower_id=?) AS following_count
        """,
        (row, n) ->
            new FollowState(
                row.getBoolean("following"),
                row.getLong("followers_count"),
                row.getLong("following_count")),
        viewer,
        target,
        target,
        target);
  }
}
