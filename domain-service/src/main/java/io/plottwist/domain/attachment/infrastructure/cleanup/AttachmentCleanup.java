package io.plottwist.domain.attachment.infrastructure.cleanup;

import io.micrometer.core.instrument.MeterRegistry;
import io.plottwist.domain.attachment.application.port.AttachmentStorage;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AttachmentCleanup {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final AttachmentStorage storage;
  private final MeterRegistry metrics;
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("attachment-cleanup-", 0).factory());
  private final AtomicBoolean running = new AtomicBoolean();

  public AttachmentCleanup(
      JdbcTemplate jdbc, TransactionTemplate tx, AttachmentStorage storage, MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.storage = storage;
    this.metrics = metrics;
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }

  @Scheduled(fixedDelay = 30000)
  public void tick() {
    if (!running.compareAndSet(false, true)) return;
    try {
      executor.submit(
          () -> {
            try {
              clean();
            } catch (RuntimeException failure) {
              metrics.counter("story.media.cleanup.failures").increment();
            } finally {
              running.set(false);
            }
          });
    } catch (RejectedExecutionException stopped) {
      running.set(false);
    }
  }

  public void clean() {
    for (int i = 0; i < 10; i++) {
      var rows =
          tx.execute(
              status ->
                  jdbc.query(
                      """
   WITH candidate AS(SELECT a.id FROM story_attachments a WHERE
     (a.state='DELETE_PENDING' AND a.delete_after<now() OR a.state='UPLOADING' AND a.created_at<now()-interval '1 hour'
      OR a.state='READY' AND a.created_at<now()-interval '24 hours')
     AND NOT EXISTS(SELECT 1 FROM story_attachment_refs ref WHERE ref.attachment_id=a.id)
     ORDER BY a.created_at LIMIT 1 FOR UPDATE OF a SKIP LOCKED)
   UPDATE story_attachments a SET state='DELETE_PENDING',lease_token=gen_random_uuid(),delete_after=now()+interval '2 minutes'
   FROM candidate c WHERE c.id=a.id RETURNING a.id,a.object_key,a.lease_token
   """,
                      (row, n) ->
                          new Lease(
                              row.getObject("id", UUID.class),
                              row.getString("object_key"),
                              row.getObject("lease_token", UUID.class))));
      if (rows == null || rows.isEmpty()) return;
      var job = rows.getFirst();
      try {
        storage.delete(job.key());
        jdbc.update(
            "DELETE FROM story_attachments WHERE id=? AND lease_token=? AND state='DELETE_PENDING'",
            job.id(),
            job.token());
        metrics.counter("story.media.deleted").increment();
      } catch (RuntimeException unavailable) {
        jdbc.update(
            "UPDATE story_attachments SET delete_after=now()+interval '1 minute',lease_token=NULL WHERE id=? AND lease_token=?",
            job.id(),
            job.token());
        metrics.counter("story.media.cleanup.failures").increment();
      }
    }
  }

  private record Lease(UUID id, String key, UUID token) {}
}
