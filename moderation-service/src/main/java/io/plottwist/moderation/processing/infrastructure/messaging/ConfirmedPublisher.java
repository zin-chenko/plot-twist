package io.plottwist.moderation.processing.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.plottwist.moderation.contract.BrokerNames;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ConfirmedPublisher {
  private final RabbitTemplate rabbit;
  private final ObjectMapper json;

  public ConfirmedPublisher(RabbitTemplate rabbit, ObjectMapper json) {
    this.rabbit = rabbit;
    this.json = json;
  }

  public void send(String route, Object body) {
    try {
      byte[] bytes = json.writeValueAsBytes(body);
      if (bytes.length > 131072) throw new IllegalArgumentException("Moderation message too large");
      var props = new MessageProperties();
      props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
      props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
      var confirm = new CorrelationData(UUID.randomUUID().toString());
      rabbit.send(BrokerNames.EXCHANGE, route, new Message(bytes, props), confirm);
      if (!confirm.getFuture().get(5, TimeUnit.SECONDS).isAck() || confirm.getReturned() != null)
        throw new IllegalStateException("Broker did not confirm routing");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Publication interrupted");
    } catch (Exception failure) {
      throw new IllegalStateException("Moderation broker publication failed", failure);
    }
  }
}
