package io.plottwist.domain.chat.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.plottwist.domain.chat.application.port.ChatLiveUpdates;
import io.plottwist.domain.chat.model.ChatChange;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;

@Configuration
@ConditionalOnProperty(name = "chat.live.enabled", havingValue = "true", matchIfMissing = true)
public class ChatLiveConfiguration {
  public static final String EXCHANGE = "plot-twist.chat.events.v1";
  private final ChatLiveUpdates updates;
  private final ObjectMapper json;
  private SimpleMessageListenerContainer container;
  private String queueName;

  public ChatLiveConfiguration(ChatLiveUpdates updates, ObjectMapper json) {
    this.updates = updates;
    this.json = json;
  }

  @Bean
  AnonymousQueue chatLiveQueue() {
    var queue =
        new AnonymousQueue(
            java.util.Map.of(
                "x-max-length",
                10000,
                "x-max-length-bytes",
                4194304,
                "x-overflow",
                "reject-publish"));
    queueName = queue.getName();
    return queue;
  }

  @Bean
  Declarables chatLiveTopology(AnonymousQueue chatLiveQueue) {
    var exchange = new FanoutExchange(EXCHANGE, true, false);
    return new Declarables(exchange, BindingBuilder.bind(chatLiveQueue).to(exchange));
  }

  @Bean
  SimpleMessageListenerContainer chatLiveContainer(
      ConnectionFactory connections,
      AnonymousQueue chatLiveQueue,
      @Value("${chat.live.auto-startup:${spring.rabbitmq.listener.simple.auto-startup:true}}")
          boolean startup) {
    container = new SimpleMessageListenerContainer(connections);
    container.setQueues(chatLiveQueue);
    container.setPrefetchCount(100);
    container.setRecoveryInterval(2000);
    container.setAutoStartup(startup);
    container.setDefaultRequeueRejected(false);
    container.setMessageListener(
        (MessageListener)
            message -> {
              try {
                if (message.getBody().length > 1024) throw new IllegalArgumentException();
                var change = json.readValue(message.getBody(), ChatChange.class);
                if (change.eventId() == null
                    || change.chatId() == null
                    || change.recipients() == null
                    || change.recipients().isEmpty()
                    || change.recipients().size() > 2
                    || change.recipients().stream().anyMatch(java.util.Objects::isNull))
                  throw new IllegalArgumentException();
                updates.changed(change);
              } catch (Exception invalid) {
                updates.transportReady(false);
                throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(
                    "Invalid private notification");
              }
            });
    return container;
  }

  @EventListener
  void ready(ConsumeOkEvent event) {
    if (event.getQueue().equals(queueName)) updates.transportReady(true);
  }

  @EventListener
  void failed(ListenerContainerConsumerFailedEvent event) {
    if (event.getSource() == container) updates.transportReady(false);
  }

  @EventListener
  void stopped(AsyncConsumerStoppedEvent event) {
    if (event.getSource() == container) updates.transportReady(false);
  }
}
