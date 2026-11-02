package io.plottwist.domain.chat.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.plottwist.domain.chat.infrastructure.persistence.JdbcChatOutbox;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat.live.enabled", havingValue = "true", matchIfMissing = true)
public class ChatEventPublisher {
  private final JdbcChatOutbox outbox;
  private final RabbitTemplate rabbit;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicBoolean running = new AtomicBoolean();

  public ChatEventPublisher(
      JdbcChatOutbox outbox,
      ConnectionFactory connections,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.outbox = outbox;
    this.json = json;
    this.metrics = metrics;
    rabbit = new RabbitTemplate(connections);
    rabbit.setMandatory(false);
    metrics.gauge("chat.outbox.pending", outbox, JdbcChatOutbox::pending);
  }

  @Scheduled(fixedDelay = 100)
  public void tick() {
    if (!running.compareAndSet(false, true)) return;
    try {
      executor.submit(
          () -> {
            try {
              process();
            } catch (RuntimeException unavailable) {
              metrics.counter("chat.outbox.failures").increment();
            } finally {
              running.set(false);
            }
          });
    } catch (RejectedExecutionException stopped) {
      running.set(false);
    }
  }

  public void process() {
    for (int n = 0; n < 64; n++) {
      var item = outbox.claim();
      if (item.isEmpty()) return;
      var d = item.get();
      try {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
        var correlation = new CorrelationData(d.change().eventId().toString());
        rabbit.send(
            ChatLiveConfiguration.EXCHANGE,
            "",
            new Message(json.writeValueAsBytes(d.change()), properties),
            correlation);
        if (!correlation.getFuture().get(5, TimeUnit.SECONDS).isAck())
          throw new IllegalStateException("Chat event not confirmed");
        outbox.sent(d);
        metrics.counter("chat.outbox.sent").increment();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        outbox.failed(d);
        return;
      } catch (Exception failure) {
        outbox.failed(d);
        metrics.counter("chat.outbox.failures").increment();
        return;
      }
    }
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }
}
