package io.plottwist.moderation.processing.infrastructure.messaging;

import io.plottwist.moderation.contract.BrokerNames;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;

@Configuration
public class BrokerConfiguration {
  @Bean
  Declarables moderationTopology() {
    var exchange = new DirectExchange(BrokerNames.EXCHANGE, true, false);
    var dead =
        QueueBuilder.durable(BrokerNames.DEAD)
            .quorum()
            .ttl(604800000)
            .maxLength(10000)
            .overflow(QueueBuilder.Overflow.rejectPublish)
            .build();
    var tasks =
        QueueBuilder.durable(BrokerNames.TASKS)
            .quorum()
            .deadLetterExchange("")
            .deadLetterRoutingKey(BrokerNames.DEAD)
            .deliveryLimit(5)
            .withArgument("x-dead-letter-strategy", "at-least-once")
            .maxLength(10000)
            .maxLengthBytes(134217728)
            .overflow(QueueBuilder.Overflow.rejectPublish)
            .build();
    var results =
        QueueBuilder.durable(BrokerNames.RESULTS)
            .quorum()
            .deadLetterExchange("")
            .deadLetterRoutingKey(BrokerNames.DEAD)
            .deliveryLimit(5)
            .withArgument("x-dead-letter-strategy", "at-least-once")
            .maxLength(10000)
            .maxLengthBytes(134217728)
            .overflow(QueueBuilder.Overflow.rejectPublish)
            .build();
    return new Declarables(
        exchange,
        dead,
        tasks,
        results,
        BindingBuilder.bind(tasks).to(exchange).with(BrokerNames.TASKS),
        BindingBuilder.bind(results).to(exchange).with(BrokerNames.RESULTS));
  }
}
