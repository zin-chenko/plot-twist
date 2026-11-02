package io.plottwist.domain.recommendation.infrastructure.indexing;

import io.micrometer.core.instrument.MeterRegistry;
import io.plottwist.domain.recommendation.application.port.*;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Tombstones must be processed even while inference is unavailable. */
@Component
public class VectorDeletionWorker {
  private final IndexJobs jobs;
  private final VectorIndex index;
  private final MeterRegistry metrics;
  private final RecommendationStore store;
  private long cleanupAfter;
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("vector-delete-", 0).factory());
  private final AtomicBoolean running = new AtomicBoolean();

  public VectorDeletionWorker(
      IndexJobs jobs, VectorIndex index, MeterRegistry metrics, RecommendationStore store) {
    this.jobs = jobs;
    this.index = index;
    this.metrics = metrics;
    this.store = store;
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }

  @Scheduled(fixedDelay = 2000)
  public void tick() {
    if (!running.compareAndSet(false, true)) return;
    try {
      executor.submit(
          () -> {
            try {
              process();
            } finally {
              running.set(false);
            }
          });
    } catch (RejectedExecutionException stopped) {
      running.set(false);
    }
  }

  public void process() {
    // Expire private snapshots even when the inference service has never been available.
    if (System.nanoTime() > cleanupAfter) {
      store.cleanup();
      cleanupAfter = System.nanoTime() + 60_000_000_000L;
    }
    for (int i = 0; i < 4; i++) {
      var candidate = jobs.claimDeleted();
      if (candidate.isEmpty()) return;
      var job = candidate.get();
      try {
        if (!jobs.owned(job)) {
          jobs.release(job);
          continue;
        }
        index.deleteEveryModelStory(job.storyId());
        jobs.complete(job, null, null, null);
        metrics.counter("recommendation.index.deleted").increment();
      } catch (RuntimeException failure) {
        jobs.failed(job, failure.getClass().getSimpleName());
        metrics.counter("recommendation.index.failures").increment();
      }
    }
  }
}
