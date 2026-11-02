package io.plottwist.domain.chat;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import io.plottwist.domain.chat.application.ChatService;
import io.plottwist.domain.chat.infrastructure.messaging.ChatLiveConfiguration;
import io.plottwist.domain.chat.model.ChatChange;
import io.plottwist.domain.media.application.port.ObjectStorage;
import io.plottwist.domain.moderation.infrastructure.messaging.OutboxPublisher;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "chat.live.enabled=true",
      "chat.live.auto-startup=true",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "recommendations.enabled=false"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "CHAT_SOCKET_INTEGRATION", matches = "true")
class ChatSocketIntegrationTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired ChatService chats;
  @Autowired JdbcTemplate jdbc;
  @Autowired AmqpAdmin admin;
  @Autowired ConnectionFactory connections;
  @MockitoBean JwtDecoder decoder;
  @MockitoBean ObjectStorage storage;
  @MockitoBean OutboxPublisher moderationPublisher;
  final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), other = UUID.randomUUID();
  final List<Client> clients = new ArrayList<>();
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void prepare() {
    for (var id : List.of(a, b, other))
      jdbc.update(
          "INSERT INTO user_profiles(account_id,display_name) VALUES(?,?)",
          id,
          "socket-test " + id);
    when(decoder.decode(anyString()))
        .thenAnswer(
            invocation -> {
              String token = invocation.getArgument(0);
              UUID account =
                  switch (token) {
                    case "fixture-a", "fixture-readonly", "fixture-short" -> a;
                    case "fixture-b" -> b;
                    case "fixture-other" -> other;
                    default -> null;
                  };
              if (account == null) throw new BadJwtException("Invalid test token");
              var builder =
                  Jwt.withTokenValue(token)
                      .header("alg", "RS256")
                      .subject(account.toString())
                      .claim("account_id", account.toString())
                      .issuedAt(Instant.now().minusSeconds(1))
                      .expiresAt(
                          Instant.now().plusSeconds(token.equals("fixture-short") ? 1 : 120));
              // Spring Authorization Server emits an array; a space-separated scope also works.
              return builder
                  .claim(
                      "scope",
                      token.equals("fixture-readonly")
                          ? "api.read"
                          : List.of("api.read", "api.write"))
                  .build();
            });
  }

  @AfterEach
  void close() {
    for (var client : clients) if (client.socket != null) client.socket.abort();
  }

  final class Client implements WebSocket.Listener {
    WebSocket socket;
    final List<JsonNode> frames = new CopyOnWriteArrayList<>();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    final StringBuilder text = new StringBuilder();

    Client(String origin, String query) {
      var builder = http.newWebSocketBuilder();
      if (origin != null) builder.header("Origin", origin);
      socket =
          builder
              .buildAsync(URI.create("ws://localhost:" + port + "/ws/chats" + query), this)
              .join();
      clients.add(this);
    }

    public void onOpen(WebSocket socket) {
      socket.request(1);
    }

    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      text.append(data);
      if (last) {
        try {
          frames.add(json.readTree(text.toString()));
        } catch (Exception invalid) {
          throw new IllegalStateException(invalid);
        }
        text.setLength(0);
      }
      socket.request(1);
      return null;
    }

    public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
      closed.complete(status);
      return null;
    }

    void send(Object frame) throws Exception {
      socket.sendText(json.writeValueAsString(frame), true).join();
    }

    JsonNode find(String type, String requestId) {
      await()
          .atMost(Duration.ofSeconds(8))
          .until(
              () ->
                  frames.stream()
                      .anyMatch(
                          f ->
                              f.path("type").asText().equals(type)
                                  && (requestId == null
                                      || f.path("requestId").asText().equals(requestId))));
      return frames.stream()
          .filter(
              f ->
                  f.path("type").asText().equals(type)
                      && (requestId == null || f.path("requestId").asText().equals(requestId)))
          .findFirst()
          .orElseThrow();
    }

    JsonNode command(String type, UUID chat, Map<String, Object> extra, String result)
        throws Exception {
      String requestId = UUID.randomUUID().toString();
      var frame = new HashMap<String, Object>(extra);
      frame.put("type", type);
      frame.put("chatId", chat);
      frame.put("requestId", requestId);
      send(frame);
      return find(result, requestId);
    }

    void changed(UUID id) {
      await()
          .atMost(Duration.ofSeconds(8))
          .until(
              () ->
                  frames.stream()
                      .anyMatch(
                          f ->
                              f.path("type").asText().equals("changed")
                                  && f.path("chatId").asText().equals(id.toString())));
    }
  }

  Client connect(String token) throws Exception {
    var client = new Client("http://localhost:4200", "");
    client.send(Map.of("type", "auth", "token", token));
    client.find("ready", null);
    return client;
  }

  HttpResponse<String> get(UUID id, String token) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/chats/" + id + "/messages"))
            .header("Authorization", "Bearer " + token)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void realSocketSendsAcknowledgesRetriesAndNotifiesOnlyParticipants() throws Exception {
    UUID id = chats.open(a, b).id();
    var sender = connect("fixture-a");
    var receiver = connect("fixture-b");
    var outsider = connect("fixture-other");
    var nonce = UUID.randomUUID();
    var payload =
        Map.<String, Object>of(
            "clientMessageId", nonce, "body", "  Привет\r\n<script>private</script>  ");
    var ack = sender.command("send", id, payload, "ack");
    assertEquals(1, ack.path("message").path("sequence").asLong());
    assertEquals(a.toString(), ack.path("message").path("senderId").asText());
    receiver.changed(id);
    sender.changed(id);
    assertEquals(ack.path("message"), sender.command("send", id, payload, "ack").path("message"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM direct_messages WHERE chat_id=?", Integer.class, id));
    var response = get(id, "fixture-b");
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    assertTrue(response.body().contains("private"));
    assertEquals(404, get(id, "fixture-other").statusCode());
    assertEquals(
        404,
        outsider
            .command(
                "send",
                id,
                Map.of("clientMessageId", UUID.randomUUID(), "body", "intrusion"),
                "error")
            .path("status")
            .asInt());
    assertEquals(
        404,
        outsider.command("read", id, Map.of("throughSequence", 1), "error").path("status").asInt());
    assertTrue(
        outsider.frames.stream()
            .noneMatch(
                f ->
                    f.path("type").asText().equals("changed")
                        && f.path("chatId").asText().equals(id.toString())));
    assertEquals(
        409,
        sender
            .command("send", id, Map.of("clientMessageId", nonce, "body", "different"), "error")
            .path("status")
            .asInt());
    assertTrue(
        receiver.frames.stream()
            .filter(f -> f.path("type").asText().equals("changed"))
            .allMatch(f -> !f.toString().contains("private")));
  }

  @Test
  void readReceiptsAreLiveMonotonicAndCannotPointToTheFuture() throws Exception {
    UUID id = chats.open(a, b).id();
    var sender = connect("fixture-a");
    var receiver = connect("fixture-b");
    sender.command("send", id, Map.of("clientMessageId", UUID.randomUUID(), "body", "one"), "ack");
    receiver.changed(id);
    assertEquals(1, chats.get(id, b).unreadCount());
    var read = receiver.command("read", id, Map.of("throughSequence", 1), "ack");
    assertEquals(0, read.path("summary").path("unreadCount").asLong());
    assertEquals(1, chats.get(id, a).peerReadThrough());
    assertEquals(
        1,
        receiver
            .command("read", id, Map.of("throughSequence", 0), "ack")
            .path("summary")
            .path("readThrough")
            .asLong());
    assertEquals(
        400,
        receiver.command("read", id, Map.of("throughSequence", 2), "error").path("status").asInt());
  }

  @Test
  void originAndTokenQueryAreRejectedBeforeUpgrade() {
    for (String origin : Arrays.asList(null, "https://untrusted.example")) {
      var failure = assertThrows(CompletionException.class, () -> new Client(origin, ""));
      assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
      assertEquals(
          403, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
    }
    var failure =
        assertThrows(
            CompletionException.class,
            () -> new Client("http://localhost:4200", "?token=fixture-a"));
    assertEquals(
        403, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
  }

  @Test
  void commandsBeforeAuthAndInvalidTokensCannotReadOrWrite() throws Exception {
    UUID id = chats.open(a, b).id();
    var unauth = new Client("http://localhost:4200", "");
    unauth.send(
        Map.of(
            "type",
            "send",
            "chatId",
            id,
            "requestId",
            UUID.randomUUID(),
            "clientMessageId",
            UUID.randomUUID(),
            "body",
            "no auth"));
    assertEquals(4401, unauth.closed.get(5, TimeUnit.SECONDS));
    var invalid = new Client("http://localhost:4200", "");
    invalid.send(Map.of("type", "auth", "token", "invalid"));
    assertEquals(4401, invalid.closed.get(5, TimeUnit.SECONDS));
    var readonly = connect("fixture-readonly");
    assertEquals(
        403,
        readonly
            .command(
                "send",
                id,
                Map.of("clientMessageId", UUID.randomUUID(), "body", "write forbidden"),
                "error")
            .path("status")
            .asInt());
    assertEquals(0, chats.get(id, a).lastSequence());
    var spoof = connect("fixture-a");
    spoof.send(
        Map.of(
            "type",
            "send",
            "requestId",
            UUID.randomUUID(),
            "chatId",
            id,
            "senderId",
            b,
            "clientMessageId",
            UUID.randomUUID(),
            "body",
            "spoof"));
    assertEquals(1007, spoof.closed.get(5, TimeUnit.SECONDS));
  }

  @Test
  void expirationClosesAnAlreadyAuthenticatedConnection() throws Exception {
    var client = connect("fixture-short");
    assertEquals(4401, client.closed.get(5, TimeUnit.SECONDS));
  }

  @Test
  void rabbitFanoutFeedsTwoIndependentQueuesAndOutboxContainsNoText() throws Exception {
    var queue = new AnonymousQueue();
    admin.declareQueue(queue);
    admin.declareBinding(
        BindingBuilder.bind(queue).to(new FanoutExchange(ChatLiveConfiguration.EXCHANGE)));
    var events = new CopyOnWriteArrayList<ChatChange>();
    var replica = new SimpleMessageListenerContainer(connections);
    replica.setQueues(queue);
    replica.setMessageListener(
        (MessageListener)
            message -> {
              try {
                events.add(json.readValue(message.getBody(), ChatChange.class));
              } catch (Exception invalid) {
                throw new IllegalStateException(invalid);
              }
            });
    try {
      replica.start();
      await()
          .atMost(Duration.ofSeconds(8))
          .until(() -> admin.getQueueInfo(queue.getName()).getConsumerCount() == 1);
      UUID id = chats.open(a, b).id();
      var sender = connect("fixture-a");
      var receiver = connect("fixture-b");
      sender.command(
          "send",
          id,
          Map.of("clientMessageId", UUID.randomUUID(), "body", "PRIVATE_BODY_NOT_BROKER_DATA"),
          "ack");
      receiver.changed(id);
      await()
          .atMost(Duration.ofSeconds(8))
          .until(
              () ->
                  events.stream()
                      .anyMatch(
                          e -> e.chatId().equals(id) && e.recipients().containsAll(List.of(a, b))));
      assertFalse(json.writeValueAsString(events).contains("PRIVATE_BODY_NOT_BROKER_DATA"));
      await()
          .atMost(Duration.ofSeconds(8))
          .until(
              () ->
                  jdbc.queryForObject(
                          "SELECT count(*) FROM chat_event_outbox WHERE chat_id=?",
                          Integer.class,
                          id)
                      == 0);
    } finally {
      replica.stop();
      admin.deleteQueue(queue.getName());
    }
  }

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("chatLiveContainer")
  SimpleMessageListenerContainer liveContainer;

  @Test
  void restartingTheFanoutConsumerClosesSocketsAndReconnectRecoversHistory() throws Exception {
    UUID id = chats.open(a, b).id();
    var receiver = connect("fixture-b");
    try {
      liveContainer.stop();
      assertEquals(1012, receiver.closed.get(5, TimeUnit.SECONDS));
      chats.send(id, a, UUID.randomUUID(), "sent during reconnect");
    } finally {
      liveContainer.start();
    }
    await()
        .atMost(Duration.ofSeconds(8))
        .until(
            () -> {
              var info = admin.getQueueInfo(liveContainer.getQueueNames()[0]);
              return info != null && info.getConsumerCount() == 1;
            });
    var recovered = connect("fixture-b");
    assertTrue(get(id, "fixture-b").body().contains("sent during reconnect"));
    var sender = connect("fixture-a");
    sender.command(
        "send", id, Map.of("clientMessageId", UUID.randomUUID(), "body", "after reconnect"), "ack");
    recovered.changed(id);
    assertEquals(2, chats.get(id, b).lastSequence());
  }
}
