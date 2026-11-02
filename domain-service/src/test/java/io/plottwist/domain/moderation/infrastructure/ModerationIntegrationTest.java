package io.plottwist.domain.moderation.infrastructure;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import io.plottwist.domain.moderation.infrastructure.persistence.DecisionStore;
import io.plottwist.domain.recommendation.infrastructure.persistence.JdbcIndexJobs;
import io.plottwist.moderation.contract.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MODERATION_INTEGRATION", matches = "true")
class ModerationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired DecisionStore decisions;
  @Autowired JdbcIndexJobs index;
  @Autowired RabbitListenerEndpointRegistry listeners;
  UUID author = UUID.randomUUID(), reader = UUID.randomUUID();

  private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      user(UUID id) {
    return jwt()
        .jwt(j -> j.subject(id.toString()).claim("account_id", id.toString()))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_api.read"),
            new SimpleGrantedAuthority("SCOPE_api.write"));
  }

  private JsonNode create(String body) throws Exception {
    return json.readTree(
        mvc.perform(
                post("/api/v1/stories")
                    .with(user(author))
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("title", "Title", "body", body))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  private JsonNode fetch(UUID id, UUID user) throws Exception {
    return json.readTree(
        mvc.perform(get("/api/v1/stories/" + id).with(user(user)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  private void state(UUID id, String status) {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () ->
                status.equals(
                    jdbc.queryForObject(
                        "SELECT moderation_status FROM stories WHERE id=?", String.class, id)));
  }

  private ModerationDecision decision(UUID id) {
    return jdbc.queryForObject(
        "SELECT * FROM stories WHERE id=?",
        (row, n) ->
            new ModerationDecision(
                1,
                row.getObject("moderation_job_id", UUID.class),
                id,
                row.getLong("version"),
                row.getString("moderation_hash"),
                BrokerNames.POLICY,
                "a".repeat(64),
                ModerationDecision.Outcome.APPROVE,
                List.of()),
        id);
  }

  @AfterEach
  void cleanup() {
    listeners.start();
    jdbc.update("DELETE FROM stories WHERE author_id=?", author);
  }

  @Test
  void actualRabbitWorkerPublishesOnlyApprovedTextAndReviewsAmbiguity() throws Exception {
    var safe = UUID.fromString(create("Добрая история о прогулке.").get("id").asText());
    state(safe, "PUBLISHED");
    assertEquals("Добрая история о прогулке.", fetch(safe, reader).get("body").asText());
    var bad = UUID.fromString(create("История содержит блять.").get("id").asText());
    state(bad, "REJECTED");
    mvc.perform(get("/api/v1/stories/" + bad).with(user(reader))).andExpect(status().isNotFound());
    assertTrue(index.document(bad).isEmpty());
    var review = UUID.fromString(create("Сука принесла щенков.").get("id").asText());
    state(review, "REVIEW_REQUIRED");
    mvc.perform(get("/api/v1/stories/" + review).with(user(reader)))
        .andExpect(status().isNotFound());
  }

  @Test
  void pendingIsPrivateAndDelayedResultCannotApproveAnotherRevision() throws Exception {
    listeners.stop();
    var id = UUID.fromString(create("Первая версия").get("id").asText());
    var old = decision(id);
    mvc.perform(get("/api/v1/stories/" + id).with(user(reader))).andExpect(status().isNotFound());
    var feed =
        json.readTree(
            mvc.perform(get("/api/v1/stories").with(user(reader)))
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
    assertFalse(feed.toString().contains(id.toString()));
    mvc.perform(
            put("/api/v1/stories/" + id)
                .with(user(author))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"title\":\"Title\",\"body\":\"Новая версия\"}"))
        .andExpect(status().isAccepted());
    assertFalse(decisions.apply(old));
    assertTrue(index.document(id).isEmpty());
    listeners.start();
    state(id, "PUBLISHED");
    assertEquals("Новая версия", fetch(id, reader).get("body").asText());
  }

  @Test
  void rejectedEditKeepsOldPublicationAndApprovalIsIdempotent() throws Exception {
    listeners.stop();
    var id = UUID.fromString(create("Старая версия").get("id").asText());
    var initial = decision(id);
    assertTrue(decisions.apply(initial));
    assertFalse(decisions.apply(initial));
    mvc.perform(
            put("/api/v1/stories/" + id)
                .with(user(author))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"title\":\"Draft\",\"body\":\"блять\"}"))
        .andExpect(status().isAccepted());
    assertEquals("Старая версия", fetch(id, reader).get("body").asText());
    assertEquals("блять", fetch(id, author).get("body").asText());
    assertEquals(0, index.document(id).orElseThrow().version());
    listeners.start();
    state(id, "REJECTED");
    assertEquals("Старая версия", fetch(id, reader).get("body").asText());
    assertEquals("PUBLISHED", fetch(id, reader).get("moderationStatus").asText());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM moderation_decisions WHERE job_id=?",
            Integer.class,
            initial.jobId()));
  }

  @Test
  void deletionCannotBeResurrectedByQueuedDecision() throws Exception {
    listeners.stop();
    var id = UUID.fromString(create("Удалённая история").get("id").asText());
    var result = decision(id);
    mvc.perform(delete("/api/v1/stories/" + id).with(user(author)).header("If-Match", "\"0\""))
        .andExpect(status().isNoContent());
    assertFalse(decisions.apply(result));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM moderation_outbox WHERE story_id=?", Integer.class, id));
  }

  @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
  @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin admin;
  @Autowired io.plottwist.domain.moderation.infrastructure.messaging.ConfirmedPublisher publisher;

  @Test
  void malformedMessagesReachDeadLetterQueueAndUnroutablePublishIsNotConfirmed() {
    int before = admin.getQueueInfo(BrokerNames.DEAD).getMessageCount();
    rabbit.send(
        BrokerNames.EXCHANGE,
        BrokerNames.TASKS,
        new org.springframework.amqp.core.Message(
            "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    rabbit.send(
        BrokerNames.EXCHANGE,
        BrokerNames.RESULTS,
        new org.springframework.amqp.core.Message(
            "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> assertTrue(admin.getQueueInfo(BrokerNames.DEAD).getMessageCount() >= before + 2));
    var task =
        new ModerationTask(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            ContentHash.of("Title", "Text"),
            "Title",
            "Text",
            BrokerNames.POLICY);
    assertThrows(IllegalStateException.class, () -> publisher.send("unroutable-test", task));
  }

  @Test
  void authorCannotAccumulateUnlimitedPendingPosts() throws Exception {
    listeners.stop();
    for (int i = 0; i < 20; i++) create("Pending " + i);
    mvc.perform(
            post("/api/v1/stories")
                .with(user(author))
                .contentType("application/json")
                .content("{\"title\":\"Title\",\"body\":\"Overflow\"}"))
        .andExpect(status().isTooManyRequests());
  }
}
