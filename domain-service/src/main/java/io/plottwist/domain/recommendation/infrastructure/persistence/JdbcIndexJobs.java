package io.plottwist.domain.recommendation.infrastructure.persistence;

import io.plottwist.domain.recommendation.application.port.IndexJobs;
import io.plottwist.domain.recommendation.model.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcIndexJobs implements IndexJobs {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcIndexJobs(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  public Optional<IndexJob> claim() {
    return jdbc
        .query(
            """
      WITH candidate AS (SELECT story_id FROM story_vector_jobs WHERE NOT dead AND available_at<=now()
      AND (locked_until IS NULL OR locked_until<now()) ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED)
      UPDATE story_vector_jobs j SET attempts=j.attempts+1,locked_until=now()+interval '2 minutes',lease_token=gen_random_uuid()
      FROM candidate c WHERE j.story_id=c.story_id RETURNING j.*
      """,
            (row, n) ->
                new IndexJob(
                    row.getObject("story_id", UUID.class),
                    row.getLong("generation"),
                    row.getObject("lease_token", UUID.class),
                    row.getInt("attempts")))
        .stream()
        .findFirst();
  }

  public Optional<IndexJob> claimDeleted() {
    return jdbc
        .query(
            """
      WITH candidate AS (SELECT story_id FROM story_vector_jobs WHERE tombstone AND NOT dead AND NOT EXISTS(SELECT 1 FROM stories s WHERE s.id=story_vector_jobs.story_id) AND available_at<=now()
      AND (locked_until IS NULL OR locked_until<now()) ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED)
      UPDATE story_vector_jobs j SET attempts=j.attempts+1,locked_until=now()+interval '2 minutes',lease_token=gen_random_uuid()
      FROM candidate c WHERE j.story_id=c.story_id RETURNING j.*
      """,
            (row, n) ->
                new IndexJob(
                    row.getObject("story_id", UUID.class),
                    row.getLong("generation"),
                    row.getObject("lease_token", UUID.class),
                    row.getInt("attempts")))
        .stream()
        .findFirst();
  }

  public Optional<IndexDocument> document(UUID id) {
    return jdbc
        .query(
            "SELECT id,author_id,version,title,body FROM stories WHERE id=?",
            (row, n) ->
                new IndexDocument(
                    row.getObject("id", UUID.class),
                    row.getObject("author_id", UUID.class),
                    row.getLong("version"),
                    row.getString("title"),
                    row.getString("body")),
            id)
        .stream()
        .findFirst();
  }

  public boolean owned(IndexJob job) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM story_vector_jobs WHERE story_id=? AND generation=? AND lease_token=? AND locked_until>now())",
            Boolean.class,
            job.storyId(),
            job.generation(),
            job.token()));
  }

  public void complete(IndexJob job, IndexDocument document, EmbeddingModel model, UUID point) {
    transactions.executeWithoutResult(
        status -> {
          // Match the writers' lock order: story, then outbox row. No HTTP runs in this
          // transaction.
          var versions =
              jdbc.queryForList(
                  "SELECT version FROM stories WHERE id=? FOR KEY SHARE",
                  Long.class,
                  job.storyId());
          var locked =
              jdbc.queryForList(
                  "SELECT story_id FROM story_vector_jobs WHERE story_id=? AND generation=? AND lease_token=? FOR UPDATE",
                  UUID.class,
                  job.storyId(),
                  job.generation(),
                  job.token());
          if (locked.isEmpty()
              || document != null
                  && (versions.isEmpty() || versions.getFirst() != document.version())) {
            release(job);
            return;
          }
          if (document != null)
            jdbc.update(
                "INSERT INTO story_vector_state(story_id,story_version,point_id,model_key) VALUES(?,?,?,?) ON CONFLICT(story_id) DO UPDATE SET story_version=excluded.story_version,point_id=excluded.point_id,model_key=excluded.model_key,indexed_at=now()",
                document.id(),
                document.version(),
                point,
                model.key());
          jdbc.update(
              "DELETE FROM story_vector_jobs WHERE story_id=? AND generation=? AND lease_token=?",
              job.storyId(),
              job.generation(),
              job.token());
        });
  }

  public void release(IndexJob job) {
    jdbc.update(
        "UPDATE story_vector_jobs SET locked_until=NULL,lease_token=NULL WHERE story_id=? AND lease_token=?",
        job.storyId(),
        job.token());
  }

  public void failed(IndexJob job, String reason) {
    jdbc.update(
        "UPDATE story_vector_jobs SET locked_until=NULL,lease_token=NULL,dead=attempts>=10,last_error=?,available_at=now()+(? * interval '1 second') WHERE story_id=? AND generation=? AND lease_token=?",
        reason.substring(0, Math.min(100, reason.length())),
        Math.min(900, 2L << Math.min(job.attempts(), 9)),
        job.storyId(),
        job.generation(),
        job.token());
    release(job);
  }

  public void repair(EmbeddingModel model, boolean lostCollection) {
    if (lostCollection)
      jdbc.update("DELETE FROM story_vector_state WHERE model_key=?", model.key());
    jdbc.update(
        """
      INSERT INTO story_vector_jobs(story_id)
      SELECT s.id FROM stories s LEFT JOIN story_vector_state v ON v.story_id=s.id
      WHERE NOT EXISTS(SELECT 1 FROM story_vector_jobs j WHERE j.story_id=s.id)
      AND (v.story_id IS NULL OR v.model_key<>? OR v.story_version<>s.version) LIMIT 500
      ON CONFLICT DO NOTHING
      """,
        model.key());
  }

  public Set<UUID> obsolete(List<VectorHit> hits) {
    var valid = new HashMap<UUID, Long>();
    if (hits.isEmpty()) return Set.of();
    var ids = hits.stream().map(VectorHit::storyId).distinct().toList();
    jdbc.query(
        "SELECT id,version FROM stories WHERE id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ")",
        row -> {
          valid.put(row.getObject("id", UUID.class), row.getLong("version"));
        },
        ids.toArray());
    var stale = new HashSet<UUID>();
    for (var h : hits)
      if (!Objects.equals(valid.get(h.storyId()), h.version())) stale.add(h.pointId());
    return stale;
  }

  public long pending() {
    return jdbc.queryForObject("SELECT count(*) FROM story_vector_jobs WHERE NOT dead", Long.class);
  }

  public long dead() {
    return jdbc.queryForObject("SELECT count(*) FROM story_vector_jobs WHERE dead", Long.class);
  }
}
