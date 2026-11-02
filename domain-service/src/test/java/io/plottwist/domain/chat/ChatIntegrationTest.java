package io.plottwist.domain.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import io.plottwist.domain.chat.application.*;
import io.plottwist.domain.chat.model.*;
import io.plottwist.domain.media.application.port.ObjectStorage;
import io.plottwist.domain.moderation.infrastructure.messaging.OutboxPublisher;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "spring.rabbitmq.dynamic=false",
      "recommendations.enabled=false"
    })
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CHAT_INTEGRATION", matches = "true")
class ChatIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired ChatService chats;
  @MockitoBean ObjectStorage storage;
  @MockitoBean OutboxPublisher publisher;
  UUID a = UUID.randomUUID(), b = UUID.randomUUID(), outsider = UUID.randomUUID();

  @BeforeEach
  void profiles() {
    for (var user : List.of(a, b, outsider))
      jdbc.update(
          "INSERT INTO user_profiles(account_id,display_name,given_name,bio) VALUES(?,?,'PRIVATE_NAME','public bio')",
          user,
          "User " + user);
  }

  private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      user(UUID id) {
    return jwt()
        .jwt(j -> j.subject(id.toString()).claim("account_id", id.toString()))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_api.read"),
            new SimpleGrantedAuthority("SCOPE_api.write"));
  }

  private String payload(Object body) throws Exception {
    return json.writeValueAsString(body);
  }

  private JsonNode response(org.springframework.test.web.servlet.ResultActions request)
      throws Exception {
    return json.readTree(request.andReturn().getResponse().getContentAsByteArray());
  }

  @Test
  void endpointsKeepContentAndReadPositionsPrivate() throws Exception {
    var opened =
        response(
            mvc.perform(
                    post("/api/v1/chats")
                        .with(user(a))
                        .contentType("application/json")
                        .content(payload(Map.of("recipientId", b))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));
    UUID id = UUID.fromString(opened.get("id").asText());
    assertEquals(id, chats.open(b, a).id());
    var message =
        Map.of(
            "clientMessageId", UUID.randomUUID(), "body", "<script>private text</script>\nПривет");
    var sent =
        response(
            mvc.perform(
                    post("/api/v1/chats/" + id + "/messages")
                        .with(user(a))
                        .contentType("application/json")
                        .content(payload(message)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store")));
    assertEquals(a.toString(), sent.get("senderId").asText());
    assertEquals(message.get("body"), sent.get("body").asText());
    assertEquals(1, chats.get(id, b).unreadCount());
    for (String suffix : List.of("", "/messages"))
      mvc.perform(get("/api/v1/chats/" + id + suffix).with(user(outsider)))
          .andExpect(status().isNotFound())
          .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            post("/api/v1/chats/" + id + "/messages")
                .with(user(outsider))
                .contentType("application/json")
                .content(payload(message)))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/api/v1/chats/" + id + "/read")
                .with(user(outsider))
                .contentType("application/json")
                .content("{\"throughSequence\":1}"))
        .andExpect(status().isNotFound());
    assertTrue(chats.inbox(outsider, null, 20).items().isEmpty());
    var page =
        response(
            mvc.perform(get("/api/v1/chats/" + id + "/messages").with(user(b)))
                .andExpect(status().isOk()));
    assertEquals(1, page.get("items").size());
    assertFalse(page.toString().contains("PRIVATE_NAME"));
    mvc.perform(
            put("/api/v1/chats/" + id + "/read")
                .with(user(b))
                .contentType("application/json")
                .content("{\"throughSequence\":1}"))
        .andExpect(status().isOk());
    assertEquals(0, chats.get(id, b).unreadCount());
    assertEquals(1, chats.get(id, a).peerReadThrough());
  }

  @Test
  void emptyConversationsAreHiddenUntilFirstMessageAndPairsAreUniqueUnderConcurrency()
      throws Exception {
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var tasks = new ArrayList<Future<UUID>>();
      for (int i = 0; i < 12; i++) {
        final boolean reverse = i % 2 == 0;
        tasks.add(
            pool.submit(
                () -> {
                  start.await();
                  return chats.open(reverse ? a : b, reverse ? b : a).id();
                }));
      }
      start.countDown();
      var ids = new HashSet<UUID>();
      for (var task : tasks) ids.add(task.get(15, TimeUnit.SECONDS));
      assertEquals(1, ids.size());
    }
    var single = chats.open(a, outsider);
    assertTrue(chats.inbox(a, null, 20).items().stream().anyMatch(c -> c.id().equals(single.id())));
    assertTrue(chats.inbox(outsider, null, 20).items().isEmpty());
    chats.send(single.id(), a, UUID.randomUUID(), "Hello");
    assertEquals(1, chats.inbox(outsider, null, 20).items().size());
    assertThrows(ChatException.class, () -> chats.open(a, a));
    assertThrows(
        io.plottwist.domain.profile.application.ProfileException.class,
        () -> chats.open(a, UUID.randomUUID()));
  }

  @Test
  void retryIsIdempotentEvenUnderConcurrencyAndChangedTextConflicts() throws Exception {
    var id = chats.open(a, b).id();
    var nonce = UUID.randomUUID();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<SendResult>>();
      for (int i = 0; i < 12; i++)
        tasks.add(pool.submit(() -> chats.send(id, a, nonce, "  Привет\r\nмир  ")));
      int created = 0;
      for (var task : tasks) {
        var result = task.get(15, TimeUnit.SECONDS);
        assertEquals(1, result.message().sequence());
        assertEquals("Привет\nмир", result.message().body());
        if (result.created()) created++;
      }
      assertEquals(1, created);
    }
    assertEquals(1, chats.get(id, b).unreadCount());
    assertEquals(
        ChatException.Reason.CONFLICT,
        assertThrows(ChatException.class, () -> chats.send(id, a, nonce, "different")).reason());
    assertEquals(
        2, chats.send(id, b, nonce, "same nonce belongs to another sender").message().sequence());
  }

  @Test
  void concurrentSendsHaveNoGapsAndPaginationHasNoOverlap() throws Exception {
    var id = chats.open(a, b).id();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<SendResult>>();
      for (int i = 0; i < 30; i++) {
        final int n = i;
        tasks.add(
            pool.submit(
                () -> chats.send(id, n % 2 == 0 ? a : b, UUID.randomUUID(), "message " + n)));
      }
      var sequences = new TreeSet<Long>();
      for (var task : tasks) sequences.add(task.get(20, TimeUnit.SECONDS).message().sequence());
      assertEquals(LongStream.rangeClosed(1, 30).boxed().toList(), new ArrayList<>(sequences));
    }
    assertEquals(15, chats.get(id, a).unreadCount());
    assertEquals(15, chats.get(id, b).unreadCount());
    var latest = chats.messages(id, b, null, null, 10);
    assertEquals(21, latest.items().getFirst().sequence());
    assertEquals(21, latest.nextBefore());
    assertFalse(latest.hasMoreNewer());
    var older = chats.messages(id, b, latest.nextBefore(), null, 10);
    assertEquals(11, older.items().getFirst().sequence());
    assertEquals(20, older.items().getLast().sequence());
    var first = chats.messages(id, b, older.nextBefore(), null, 10);
    assertEquals(1, first.items().getFirst().sequence());
    assertNull(first.nextBefore());
    var newer = chats.messages(id, b, null, 10L, 10);
    assertTrue(newer.hasMoreNewer());
    assertEquals(11, newer.items().getFirst().sequence());
    assertEquals(20, newer.items().getLast().sequence());
    assertFalse(chats.messages(id, b, null, 30L, 10).hasMoreNewer());
    assertThrows(ChatException.class, () -> chats.messages(id, b, 10L, 5L, 10));
    assertThrows(ChatException.class, () -> chats.messages(id, b, null, 31L, 10));
  }

  @Test
  void readMarkersCannotGoBackOrReadTheFutureAndOwnMessagesAreNotUnread() {
    var id = chats.open(a, b).id();
    chats.send(id, a, UUID.randomUUID(), "one");
    chats.send(id, b, UUID.randomUUID(), "two");
    chats.send(id, a, UUID.randomUUID(), "three");
    assertEquals(2, chats.get(id, b).unreadCount());
    assertEquals(1, chats.get(id, a).unreadCount());
    assertEquals(3, chats.read(id, b, 3).readThrough());
    assertEquals(3, chats.read(id, b, 1).readThrough());
    assertEquals(0, chats.get(id, b).unreadCount());
    assertEquals(1, chats.get(id, a).unreadCount());
    assertThrows(ChatException.class, () -> chats.read(id, b, 4));
    assertThrows(ChatException.class, () -> chats.read(id, b, -1));
  }

  @Test
  void inboxCursorIsViewerBoundAndPrivateChatSurvivesProfileRemoval() {
    var first = chats.open(a, b);
    var second = chats.open(a, outsider);
    var page = chats.inbox(a, null, 1);
    assertNotNull(page.nextCursor());
    var next = chats.inbox(a, page.nextCursor(), 1);
    assertEquals(1, next.items().size());
    assertNotEquals(page.items().getFirst().id(), next.items().getFirst().id());
    assertNull(next.nextCursor());
    assertThrows(ChatException.class, () -> chats.inbox(b, page.nextCursor(), 1));
    assertThrows(ChatException.class, () -> chats.inbox(a, "broken", 1));
    assertThrows(ChatException.class, () -> chats.inbox(a, null, 31));
    jdbc.update("DELETE FROM user_profiles WHERE account_id=?", b);
    assertEquals(first.id(), chats.open(a, b).id());
    assertEquals("Читатель", chats.get(first.id(), a).peer().displayName());
    assertEquals(b, chats.get(first.id(), a).peer().accountId());
    assertNotEquals(first.id(), second.id());
  }

  @Test
  void authenticationScopesAndRequestValidationAreEnforced() throws Exception {
    var id = chats.open(a, b).id();
    String path = "/api/v1/chats/" + id;
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/chats")
                .with(
                    jwt()
                        .jwt(j -> j.subject(a.toString()).claim("account_id", a.toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_api.read")))
                .contentType("application/json")
                .content(payload(Map.of("recipientId", b))))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path)
                .with(
                    jwt()
                        .jwt(j -> j.subject(a.toString()).claim("account_id", b.toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path + "/messages")
                .with(user(a))
                .contentType("application/json")
                .content(
                    payload(
                        Map.of(
                            "clientMessageId", UUID.randomUUID(), "body", "hello", "senderId", b))))
        .andExpect(status().isBadRequest());
    for (var body : List.of("", " ", "x".repeat(4001), "a\u0000b"))
      mvc.perform(
              post(path + "/messages")
                  .with(user(a))
                  .contentType("application/json")
                  .content(payload(Map.of("clientMessageId", UUID.randomUUID(), "body", body))))
          .andExpect(status().isBadRequest());
    mvc.perform(
            post(path + "/messages")
                .with(user(a))
                .contentType("application/json")
                .content("{\"body\":\"hello\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(path + "/messages").with(user(a)).param("limit", "101"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(path + "/messages").with(user(a)).param("before", "0"))
        .andExpect(status().isBadRequest());
    var nonce = UUID.randomUUID();
    var msg = payload(Map.of("clientMessageId", nonce, "body", "retry"));
    mvc.perform(post(path + "/messages").with(user(a)).contentType("application/json").content(msg))
        .andExpect(status().isCreated());
    mvc.perform(post(path + "/messages").with(user(a)).contentType("application/json").content(msg))
        .andExpect(status().isOk());
    mvc.perform(
            post(path + "/messages")
                .with(user(a))
                .contentType("application/json")
                .content(payload(Map.of("clientMessageId", nonce, "body", "changed"))))
        .andExpect(status().isConflict());
  }

  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  @Test
  void notificationsCommitWithMessagesAndHideUnopenedEmptyChats() {
    var id = chats.open(a, b).id();
    var recipients =
        jdbc.queryForMap(
            "SELECT recipient_low,recipient_high FROM chat_event_outbox WHERE chat_id=?", id);
    assertTrue(recipients.containsValue(a));
    assertFalse(recipients.containsValue(b));
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> {
          chats.send(id, a, UUID.randomUUID(), "rollback");
          status.setRollbackOnly();
        });
    assertEquals(0, chats.get(id, a).lastSequence());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM chat_event_outbox WHERE chat_id=?", Integer.class, id));
    var nonce = UUID.randomUUID();
    chats.send(id, a, nonce, "committed");
    chats.send(id, a, nonce, "committed");
    chats.read(id, b, 1);
    chats.read(id, b, 0);
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT count(*) FROM chat_event_outbox WHERE chat_id=?", Integer.class, id));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM chat_event_outbox WHERE chat_id=? AND recipient_low IS NOT NULL AND recipient_high IS NOT NULL",
            Integer.class,
            id));
  }
}
