package io.plottwist.domain.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import io.plottwist.domain.media.application.port.ObjectStorage;
import io.plottwist.domain.moderation.infrastructure.messaging.OutboxPublisher;
import io.plottwist.domain.moderation.infrastructure.persistence.DecisionStore;
import io.plottwist.domain.recommendation.infrastructure.persistence.JdbcRecommendationStore;
import io.plottwist.domain.recommendation.model.*;
import io.plottwist.moderation.contract.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
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
@EnabledIfEnvironmentVariable(named = "RECOMMENDATION_SNAPSHOT_INTEGRATION", matches = "true")
class RecommendationSnapshotIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcRecommendationStore store;
  @Autowired DecisionStore decisions;
  @MockitoBean ObjectStorage storage;
  @MockitoBean OutboxPublisher publisher;
  final UUID author = UUID.randomUUID(), viewer = UUID.randomUUID(), other = UUID.randomUUID();

  private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      user(UUID id) {
    return jwt()
        .jwt(j -> j.subject(id.toString()).claim("account_id", id.toString()))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_api.read"),
            new SimpleGrantedAuthority("SCOPE_api.write"));
  }

  private UUID story(String title) throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/stories")
                    .with(user(author))
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of("title", title, "body", "Approved " + title))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse();
    var id = UUID.fromString(json.readTree(response.getContentAsByteArray()).get("id").asText());
    publish(id);
    return id;
  }

  private void publish(UUID id) {
    var decision =
        jdbc.queryForObject(
            "SELECT * FROM stories WHERE id=?",
            (r, n) ->
                new ModerationDecision(
                    1,
                    r.getObject("moderation_job_id", UUID.class),
                    id,
                    r.getLong("version"),
                    r.getString("moderation_hash"),
                    BrokerNames.POLICY,
                    "a".repeat(64),
                    ModerationDecision.Outcome.APPROVE,
                    List.of()),
            id);
    assertTrue(decisions.apply(decision));
  }

  private RecommendationBatch snapshot(UUID... ids) {
    return store.save(
        viewer,
        store.revision(viewer),
        "unavailable",
        "fallback",
        Arrays.stream(ids)
            .map(id -> new RecommendationCandidate(id, author, 0, Instant.now(), 0, 0))
            .toList());
  }

  private String cursor(RecommendationBatch batch, int position) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((batch.id() + ":" + position).getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode feed(String cursor, int limit, boolean refresh) throws Exception {
    var request =
        get("/api/v1/recommendations")
            .with(user(viewer))
            .param("limit", Integer.toString(limit))
            .param("refresh", Boolean.toString(refresh));
    if (cursor != null) request.param("cursor", cursor);
    return json.readTree(
        mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  private List<UUID> ids(JsonNode page) {
    var ids = new ArrayList<UUID>();
    page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("id").asText())));
    return ids;
  }

  private void react(UUID id, int value) throws Exception {
    mvc.perform(
            put("/api/v1/stories/" + id + "/reaction")
                .with(user(viewer))
                .contentType("application/json")
                .content("{\"value\":" + value + "}"))
        .andExpect(status().isOk());
  }

  @Test
  void ratingsAndReadsKeepTheActiveSnapshotStableButExcludeStoriesOnNextOpening() throws Exception {
    UUID a = story("A"), b = story("B"), c = story("C"), d = story("D");
    var batch = snapshot(a, b, c, d);
    var first = feed(null, 1, false);
    assertEquals(List.of(a), ids(first));
    String next = first.get("nextCursor").asText();
    react(a, 1);
    react(c, -1);
    mvc.perform(
            post("/api/v1/stories/" + d + "/reading")
                .with(user(viewer))
                .contentType("application/json")
                .content("{\"completed\":true,\"seconds\":25}"))
        .andExpect(status().isNoContent());
    var second = feed(next, 2, false);
    assertEquals(List.of(b, c), ids(second));
    assertEquals(-1, second.get("items").get(1).get("myReaction").asInt());
    var last = feed(second.get("nextCursor").asText(), 2, false);
    assertEquals(List.of(d), ids(last));
    assertTrue(last.get("nextCursor").isNull());
    var current = feed(cursor(batch, 0), 20, false);
    assertEquals(List.of(a, b, c, d), ids(current));
    assertEquals(1, current.get("items").get(0).get("myReaction").asInt());
    assertEquals(1, current.get("items").get(0).get("likes").asInt());
    var reopened = ids(feed(null, 20, false));
    assertTrue(reopened.contains(b));
    assertFalse(reopened.contains(a));
    assertFalse(reopened.contains(c));
    assertFalse(reopened.contains(d));
    var refreshed = ids(feed(null, 20, true));
    assertFalse(refreshed.contains(a));
    assertFalse(refreshed.contains(c));
    assertFalse(refreshed.contains(d));
    react(c, 0);
    assertTrue(ids(feed(null, 20, false)).contains(c));
    assertEquals(List.of(a, b, c, d), ids(feed(cursor(batch, 0), 20, false)));
  }

  @Test
  void snapshotStillEnforcesOwnerExpirationDeletionAndApprovedVersions() throws Exception {
    UUID a = story("Visible"), b = story("Deleted"), c = story("Edited");
    var batch = snapshot(a, b, c);
    mvc.perform(get("/api/v1/recommendations").with(user(other)).param("cursor", cursor(batch, 0)))
        .andExpect(status().isBadRequest());
    assertTrue(store.page(other, batch.id(), 0, 20).isEmpty());
    mvc.perform(delete("/api/v1/stories/" + b).with(user(author)).header("If-Match", "\"0\""))
        .andExpect(status().isNoContent());
    mvc.perform(
            put("/api/v1/stories/" + c)
                .with(user(author))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"title\":\"Edited new\",\"body\":\"Pending private changes\"}"))
        .andExpect(status().isAccepted());
    var pending = feed(cursor(batch, 0), 20, false);
    assertEquals(List.of(a, c), ids(pending));
    assertEquals("Approved Edited", pending.get("items").get(1).get("body").asText());
    publish(c);
    assertEquals(List.of(a), ids(feed(cursor(batch, 0), 20, false)));
    jdbc.update(
        "UPDATE recommendation_batches SET expires_at=now()-interval '1 second' WHERE id=?",
        batch.id());
    mvc.perform(get("/api/v1/recommendations").with(user(viewer)).param("cursor", cursor(batch, 0)))
        .andExpect(status().isBadRequest());
    assertTrue(store.page(viewer, batch.id(), 0, 20).isEmpty());
  }
}
