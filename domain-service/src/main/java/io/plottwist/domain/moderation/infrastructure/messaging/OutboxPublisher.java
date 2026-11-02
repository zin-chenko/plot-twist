package io.plottwist.domain.moderation.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.plottwist.domain.moderation.infrastructure.persistence.ModerationOutbox;
import io.plottwist.moderation.contract.BrokerNames;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
  private final ModerationOutbox outbox;
  private final ConfirmedPublisher publisher;
  private final MeterRegistry metrics;
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("moderation-outbox-", 0).factory());
  private final AtomicBoolean running = new AtomicBoolean();
  private long cleanupAfter;

  public OutboxPublisher(
      ModerationOutbox outbox, ConfirmedPublisher publisher, MeterRegistry metrics) {
    this.outbox = outbox;
    this.publisher = publisher;
    this.metrics = metrics;
    metrics.gauge("moderation.outbox.pending", outbox, ModerationOutbox::pending);
    metrics.gauge("moderation.outbox.dead", outbox, ModerationOutbox::dead);
    metrics.gauge("moderation.review.pending", outbox, ModerationOutbox::review);
    metrics.gauge("moderation.outbox.oldest.seconds", outbox, ModerationOutbox::age);
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    if (!running.compareAndSet(false, true)) return;
    try {
      executor.submit(
          () -> {
            try {
              process();
            } catch (RuntimeException unavailable) {
              metrics.counter("moderation.outbox.failures").increment();
            } finally {
              running.set(false);
            }
          });
    } catch (RejectedExecutionException stopped) {
      running.set(false);
    }
  }

  public void process() {
    for (int i = 0; i < 8; i++) {
      var candidate = outbox.claim();
      if (candidate.isEmpty()) break;
      var delivery = candidate.get();
      try {
        publisher.send(BrokerNames.TASKS, delivery.task());
        outbox.sent(delivery);
        metrics.counter("moderation.outbox.sent").increment();
      } catch (RuntimeException failure) {
        outbox.failed(delivery);
        metrics.counter("moderation.outbox.failures").increment();
        break;
      }
    }
    if (System.nanoTime() > cleanupAfter) {
      outbox.cleanup();
      cleanupAfter = System.nanoTime() + 60_000_000_000L;
    }
  }
}
