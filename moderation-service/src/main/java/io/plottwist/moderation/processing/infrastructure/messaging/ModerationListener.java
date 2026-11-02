package io.plottwist.moderation.processing.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.plottwist.moderation.contract.*;
import io.plottwist.moderation.policy.application.RuleEngine;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ModerationListener {
  private final ObjectMapper json;
  private final RuleEngine engine;
  private final ConfirmedPublisher publisher;
  private final MeterRegistry metrics;

  public ModerationListener(
      ObjectMapper json, RuleEngine engine, ConfirmedPublisher publisher, MeterRegistry metrics) {
    this.json = json;
    this.engine = engine;
    this.publisher = publisher;
    this.metrics = metrics;
  }

  @RabbitListener(queues = BrokerNames.TASKS)
  public void handle(Message message) {
    ModerationTask task;
    try {
      if (message.getBody().length > 131072) throw new IllegalArgumentException();
      task = json.readValue(message.getBody(), ModerationTask.class);
    } catch (Exception invalid) {
      metrics.counter("moderation.invalid").increment();
      throw new AmqpRejectAndDontRequeueException("Invalid moderation envelope");
    }
    var started = metrics.timer("moderation.processing");
    var decision = started.record(() -> engine.evaluate(task));
    publisher.send(BrokerNames.RESULTS, decision);
    metrics.counter("moderation.decisions", "outcome", decision.outcome().name()).increment();
    // AUTO acknowledgment happens only after publisher confirm; duplicates are harmless.
  }
}
