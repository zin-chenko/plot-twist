package io.plottwist.domain.recommendation.infrastructure.persistence;

import io.plottwist.domain.recommendation.application.port.RecommendationStore;
import io.plottwist.domain.recommendation.model.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcRecommendationStore implements RecommendationStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private static final String ELIGIBLE =
      """
      s.published_version IS NOT NULL AND s.author_id<>? AND NOT EXISTS(SELECT 1 FROM story_reactions r WHERE r.story_id=s.id AND r.account_id=?)
      AND NOT EXISTS(SELECT 1 FROM story_read_signals e WHERE e.story_id=s.id AND e.account_id=? AND e.read_at>now()-interval '30 days')
      """;

  public JdbcRecommendationStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  public long revision(UUID viewer) {
    return jdbc.queryForObject(
        "SELECT COALESCE((SELECT revision FROM recommendation_users WHERE account_id=?),0)",
        Long.class,
        viewer);
  }

  public Optional<RecommendationBatch> recent(UUID viewer, long revision, String model) {
    return jdbc
        .query(
            "SELECT id,model_key,mode FROM recommendation_batches WHERE account_id=? AND signal_revision=? AND model_key=? AND created_at>now()-interval '2 minutes' AND expires_at>now() ORDER BY (mode='personalized') DESC,created_at DESC LIMIT 1",
            (row, n) ->
                new RecommendationBatch(
                    row.getObject("id", UUID.class),
                    row.getString("model_key"),
                    row.getString("mode")),
            viewer,
            revision,
            model)
        .stream()
        .findFirst();
  }

  public Seeds seeds(UUID viewer, EmbeddingModel model) {
    var positives =
        jdbc.queryForList(
            """
      SELECT v.point_id FROM story_vector_state v JOIN stories s ON s.id=v.story_id
      LEFT JOIN story_reactions r ON r.story_id=s.id AND r.account_id=?
      LEFT JOIN story_read_signals e ON e.story_id=s.id AND e.account_id=?
      WHERE v.model_key=? AND v.story_version=s.published_version AND s.author_id<>?
      AND (r.value=1 OR r.value IS NULL AND e.read_at IS NOT NULL)
      ORDER BY CASE WHEN r.value=1 THEN 0 ELSE 1 END,COALESCE(r.reacted_at,e.read_at,s.updated_at) DESC,s.id LIMIT 18
      """,
            UUID.class,
            viewer,
            viewer,
            model.key(),
            viewer);
    var negatives =
        jdbc.queryForList(
            "SELECT v.point_id FROM story_vector_state v JOIN stories s ON s.id=v.story_id JOIN story_reactions r ON r.story_id=s.id WHERE r.account_id=? AND r.value=-1 AND v.model_key=? AND v.story_version=s.published_version ORDER BY r.reacted_at DESC,s.id LIMIT 12",
            UUID.class,
            viewer,
            model.key());
    return new Seeds(positives, negatives);
  }

  public List<RecommendationCandidate> candidates(UUID viewer, Collection<UUID> ids, int limit) {
    var args = new ArrayList<Object>();
    args.add(viewer);
    args.add(viewer);
    args.add(viewer);
    String predicate = ELIGIBLE;
    if (ids != null) {
      if (ids.isEmpty()) return List.of();
      predicate += " AND s.id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
      args.addAll(ids);
    }
    args.add(limit);
    return jdbc.query(
        """
      SELECT s.id,s.author_id,s.published_version AS version,s.created_at,
      (SELECT count(*) FROM story_reactions r WHERE r.story_id=s.id AND r.value=1) AS likes,
      (SELECT count(*) FROM story_reactions r WHERE r.story_id=s.id AND r.value=-1) AS dislikes
      FROM stories s WHERE
      """
            + predicate
            + " ORDER BY s.created_at DESC,s.id DESC LIMIT ?",
        (row, n) ->
            new RecommendationCandidate(
                row.getObject("id", UUID.class),
                row.getObject("author_id", UUID.class),
                row.getLong("version"),
                row.getTimestamp("created_at").toInstant(),
                row.getLong("likes"),
                row.getLong("dislikes")),
        args.toArray());
  }

  public RecommendationBatch save(
      UUID viewer, long revision, String model, String mode, List<RecommendationCandidate> ranked) {
    UUID id = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO recommendation_batches(id,account_id,signal_revision,model_key,mode) VALUES(?,?,?,?,?)",
              id,
              viewer,
              revision,
              model,
              mode);
          jdbc.batchUpdate(
              "WITH visible AS (SELECT id FROM stories WHERE id=? AND published_version=? FOR KEY SHARE) INSERT INTO recommendation_items(batch_id,position,story_id,story_version) SELECT ?,?,id,? FROM visible",
              ranked,
              100,
              (statement, c) -> {
                statement.setObject(1, c.id());
                statement.setLong(2, c.version());
                statement.setObject(3, id);
                statement.setInt(4, ranked.indexOf(c) + 1);
                statement.setLong(5, c.version());
              });
        });
    return new RecommendationBatch(id, model, mode);
  }

  public Optional<RecommendationBatch> batch(UUID viewer, UUID id) {
    return jdbc
        .query(
            "SELECT id,model_key,mode FROM recommendation_batches WHERE account_id=? AND id=? AND expires_at>now()",
            (row, n) ->
                new RecommendationBatch(
                    row.getObject("id", UUID.class),
                    row.getString("model_key"),
                    row.getString("mode")),
            viewer,
            id)
        .stream()
        .findFirst();
  }

  public List<BatchPosition> page(UUID viewer, UUID batch, int after, int limit) {
    return jdbc.query(
        "SELECT s.id,i.position FROM recommendation_items i JOIN stories s ON s.id=i.story_id AND s.published_version=i.story_version WHERE i.batch_id=? AND i.position>? AND "
            + ELIGIBLE
            + " ORDER BY i.position LIMIT ?",
        (row, n) -> new BatchPosition(row.getObject("id", UUID.class), row.getInt("position")),
        batch,
        after,
        viewer,
        viewer,
        viewer,
        limit);
  }

  public boolean recordReading(UUID viewer, UUID story, boolean read) {
    // Parent visibility/lock also serializes deletion with the FK write, without a network call.
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              if (jdbc.queryForList(
                      "SELECT id FROM stories WHERE id=? AND published_version IS NOT NULL FOR KEY SHARE",
                      UUID.class,
                      story)
                  .isEmpty()) return false;
              jdbc.update(
                  """
        INSERT INTO story_read_signals(account_id,story_id,read_at) VALUES(?,?,CASE WHEN ? THEN now() ELSE NULL END)
        ON CONFLICT(account_id,story_id) DO UPDATE SET opened_at=now(),
          read_at=CASE WHEN ? THEN now() ELSE story_read_signals.read_at END
        WHERE story_read_signals.opened_at<now()-interval '1 day'
          OR ? AND (story_read_signals.read_at IS NULL OR story_read_signals.read_at<now()-interval '1 day')
        """,
                  viewer,
                  story,
                  read,
                  read,
                  read);
              return true;
            }));
  }

  public void cleanup() {
    jdbc.update(
        "DELETE FROM recommendation_batches WHERE id IN (SELECT id FROM recommendation_batches WHERE expires_at<now() LIMIT 1000)");
    jdbc.update(
        "DELETE FROM story_read_signals WHERE (account_id,story_id) IN (SELECT account_id,story_id FROM story_read_signals WHERE opened_at<now()-interval '90 days' LIMIT 1000)");
  }
}
