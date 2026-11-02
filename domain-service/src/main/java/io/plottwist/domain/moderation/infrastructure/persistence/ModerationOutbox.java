package io.plottwist.domain.moderation.infrastructure.persistence;

import io.plottwist.moderation.contract.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ModerationOutbox {
  private final JdbcTemplate jdbc;

  public record Delivery(ModerationTask task, UUID token, int attempts) {}

  public ModerationOutbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Delivery> claim() {
    return jdbc
        .query(
            """
   WITH candidate AS (SELECT o.job_id FROM moderation_outbox o JOIN stories s ON s.id=o.story_id AND s.moderation_job_id=o.job_id
    WHERE s.moderation_status='PENDING_MODERATION' AND NOT o.dead AND o.available_at<=now()
    AND (o.lease_until IS NULL OR o.lease_until<now()) AND (o.sent_at IS NULL OR o.sent_at<now()-interval '15 minutes')
    ORDER BY o.available_at LIMIT 1 FOR UPDATE OF o SKIP LOCKED)
   UPDATE moderation_outbox o SET lease_token=gen_random_uuid(),lease_until=now()+interval '60 seconds',attempts=o.attempts+1
   FROM candidate c WHERE c.job_id=o.job_id RETURNING o.*
   """,
            (row, n) ->
                new Delivery(
                    new ModerationTask(
                        1,
                        row.getObject("job_id", UUID.class),
                        row.getObject("story_id", UUID.class),
                        row.getLong("revision"),
                        row.getString("content_hash"),
                        row.getString("title"),
                        row.getString("body"),
                        row.getString("policy_version")),
                    row.getObject("lease_token", UUID.class),
                    row.getInt("attempts")))
        .stream()
        .findFirst();
  }

  public void sent(Delivery d) {
    jdbc.update(
        "UPDATE moderation_outbox SET sent_at=now(),dead=attempts>=25,lease_token=NULL,lease_until=NULL WHERE job_id=? AND lease_token=?",
        d.task().jobId(),
        d.token());
  }

  public void failed(Delivery d) {
    jdbc.update(
        "UPDATE moderation_outbox SET dead=attempts>=25,lease_token=NULL,lease_until=NULL,available_at=now()+(?*interval '1 second') WHERE job_id=? AND lease_token=?",
        Math.min(300, 2L << Math.min(d.attempts(), 8)),
        d.task().jobId(),
        d.token());
  }

  public long pending() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM stories WHERE moderation_status='PENDING_MODERATION'", Long.class);
  }

  public long review() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM stories WHERE moderation_status='REVIEW_REQUIRED'", Long.class);
  }

  public long dead() {
    return jdbc.queryForObject("SELECT count(*) FROM moderation_outbox WHERE dead", Long.class);
  }

  public double age() {
    return jdbc.queryForObject(
        "SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(created_at)),0) FROM moderation_outbox",
        Double.class);
  }

  public void cleanup() {
    jdbc.update(
        "DELETE FROM moderation_decisions WHERE id IN(SELECT id FROM moderation_decisions WHERE created_at<now()-interval '90 days' LIMIT 1000)");
  }
}
