package io.plottwist.domain.moderation.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.plottwist.domain.moderation.infrastructure.persistence.DecisionStore;
import io.plottwist.moderation.contract.*;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DecisionListener {
  private final ObjectMapper json;
  private final DecisionStore store;
  private final MeterRegistry metrics;

  public DecisionListener(ObjectMapper json, DecisionStore store, MeterRegistry metrics) {
    this.json = json;
    this.store = store;
    this.metrics = metrics;
  }

  @RabbitListener(queues = BrokerNames.RESULTS)
  public void handle(Message message) {
    ModerationDecision decision;
    try {
      if (message.getBody().length > 16384) throw new IllegalArgumentException();
      decision = json.readValue(message.getBody(), ModerationDecision.class);
    } catch (Exception invalid) {
      metrics.counter("moderation.result.invalid").increment();
      throw new AmqpRejectAndDontRequeueException("Invalid moderation result");
    }
    boolean applied = store.apply(decision);
    metrics
        .counter(
            "moderation.result.applied", "outcome", applied ? decision.outcome().name() : "STALE")
        .increment();
  }
}
