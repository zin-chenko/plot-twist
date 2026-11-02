package io.plottwist.domain.recommendation.infrastructure.indexing;

import io.micrometer.core.instrument.MeterRegistry;
import io.plottwist.domain.recommendation.application.port.*;
import io.plottwist.domain.recommendation.model.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VectorIndexWorker {
  private final IndexJobs jobs;
  private final EmbeddingGateway embedding;
  private final VectorIndex index;
  private final MeterRegistry metrics;
  private final boolean enabled;
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("vector-index-", 0).factory());
  private final AtomicBoolean running = new AtomicBoolean();

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }

  private EmbeddingModel ready;
  private long ensureAfter;
  private UUID gcOffset;
  private long gcAfter;

  public VectorIndexWorker(
      IndexJobs jobs,
      EmbeddingGateway embedding,
      VectorIndex index,
      MeterRegistry metrics,
      @Value("${recommendations.enabled:true}") boolean enabled) {
    this.jobs = jobs;
    this.embedding = embedding;
    this.index = index;
    this.metrics = metrics;
    this.enabled = enabled;
    metrics.gauge("recommendation.index.pending", jobs, j -> j.pending());
    metrics.gauge("recommendation.index.dead", jobs, j -> j.dead());
  }

  @Scheduled(fixedDelayString = "${recommendations.worker-delay:2000}")
  public void tick() {
    if (!running.compareAndSet(false, true)) return;
    try {
      executor.submit(
          () -> {
            try {
              run();
            } finally {
              running.set(false);
            }
          });
    } catch (RejectedExecutionException stopped) {
      running.set(false);
    }
  }

  public synchronized void run() {
    if (!enabled) return;
    try {
      var model = embedding.model();
      if (!model.equals(ready) || System.nanoTime() > ensureAfter) {
        boolean created = index.ensure(model);
        jobs.repair(model, created);
        ready = model;
        ensureAfter = System.nanoTime() + 60_000_000_000L;
      }
      for (int i = 0; i < 4; i++) {
        var claimed = jobs.claim();
        if (claimed.isEmpty()) break;
        var job = claimed.get();
        try {
          var document = jobs.document(job.storyId()).orElse(null);
          UUID point = null;
          if (document == null) {
            if (!jobs.owned(job)) {
              jobs.release(job);
              continue;
            }
            index.deleteEveryModelStory(job.storyId());
          } else {
            var vector = embedding.embed(document, model);
            if (!jobs.owned(job)) {
              jobs.release(job);
              continue;
            }
            // Revisions have different IDs: a delayed HTTP write cannot overwrite a newer vector.
            point =
                UUID.nameUUIDFromBytes(
                    (document.id() + ":" + document.version() + ":" + model.key())
                        .getBytes(StandardCharsets.UTF_8));
            index.upsert(model, document, point, vector);
          }
          jobs.complete(job, document, model, point);
          metrics.counter("recommendation.index.processed").increment();
        } catch (RuntimeException failure) {
          jobs.failed(job, failure.getClass().getSimpleName());
          metrics.counter("recommendation.index.failures").increment();
        }
      }
      if (System.nanoTime() > gcAfter) {
        var page = index.scan(model, gcOffset, 128);
        index.deletePoints(model, new ArrayList<>(jobs.obsolete(page.items())));
        gcOffset = page.next();
        gcAfter = System.nanoTime() + 30_000_000_000L;
        jobs.repair(model, false);
      }
    } catch (RuntimeException unavailable) {
      metrics.counter("recommendation.index.unavailable").increment();
    }
  }
}
