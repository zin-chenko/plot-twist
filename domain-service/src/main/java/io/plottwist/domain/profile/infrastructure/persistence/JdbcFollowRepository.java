package io.plottwist.domain.profile.infrastructure.persistence;

import io.plottwist.domain.profile.application.port.FollowRepository;
import io.plottwist.domain.profile.model.*;
import io.plottwist.domain.profile.model.FollowState;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
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

  public List<FollowMember> members(
      UUID viewer, UUID account, boolean followers, FollowPosition before, int limit) {
    String member = followers ? "f.follower_id" : "f.following_id";
    String owner = followers ? "f.following_id" : "f.follower_id";
    String sql =
        "SELECT "
            + member
            + " AS account_id,COALESCE(p.display_name,'Читатель') AS display_name,m.sha256,f.created_at,"
            + "EXISTS(SELECT 1 FROM user_follows own WHERE own.follower_id=? AND own.following_id="
            + member
            + ") AS following "
            + "FROM user_follows f LEFT JOIN user_profiles p ON p.account_id="
            + member
            + " LEFT JOIN media_objects m ON m.id=p.photo_id AND m.state='ACTIVE' WHERE "
            + owner
            + "=?";
    var args = new ArrayList<Object>();
    args.add(viewer);
    args.add(account);
    if (before != null) {
      sql += " AND (f.created_at," + member + ") < (?,?)";
      args.add(OffsetDateTime.ofInstant(before.createdAt(), ZoneOffset.UTC));
      args.add(before.accountId());
    }
    args.add(limit);
    return jdbc.query(
        sql + " ORDER BY f.created_at DESC," + member + " DESC LIMIT ?",
        (row, n) -> {
          UUID id = row.getObject("account_id", UUID.class);
          return new FollowMember(
              new UserSummary(
                  id,
                  row.getString("display_name"),
                  row.getString("sha256"),
                  row.getBoolean("following")),
              new FollowPosition(row.getTimestamp("created_at").toInstant(), id));
        },
        args.toArray());
  }
}
