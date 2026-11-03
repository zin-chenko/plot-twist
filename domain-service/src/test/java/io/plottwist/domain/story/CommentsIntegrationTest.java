package io.plottwist.domain.story;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import io.plottwist.domain.media.application.port.ObjectStorage;
import io.plottwist.domain.moderation.infrastructure.messaging.OutboxPublisher;
import io.plottwist.domain.moderation.infrastructure.persistence.DecisionStore;
import io.plottwist.domain.story.application.*;
import io.plottwist.moderation.contract.*;
import java.util.*;
import java.util.concurrent.*;
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
@EnabledIfEnvironmentVariable(named = "COMMENTS_INTEGRATION", matches = "true")
class CommentsIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired DecisionStore decisions;
  @Autowired CommentService comments;
  @MockitoBean ObjectStorage storage;
  @MockitoBean OutboxPublisher publisher;
  UUID author = UUID.randomUUID(), reader = UUID.randomUUID(), other = UUID.randomUUID();

  @BeforeEach
  void profiles() {
    for (UUID id : List.of(author, reader, other))
      jdbc.update(
          "INSERT INTO user_profiles(account_id,display_name) VALUES(?,?)",
          id,
          id.equals(author) ? "Alice" : id.equals(reader) ? "Bob" : "Carol");
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

  private String body(Object body) throws Exception {
    return json.writeValueAsString(body);
  }

  private UUID story(boolean published) throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/stories")
                    .with(user(author))
                    .contentType("application/json")
                    .content("{\"title\":\"Comments\",\"body\":\"Approved story\"}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse();
    UUID id = UUID.fromString(json.readTree(response.getContentAsByteArray()).get("id").asText());
    if (published) {
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
    return id;
  }

  private String path(UUID story) {
    return "/api/v1/stories/" + story + "/comments";
  }

  private JsonNode getJson(String path, UUID viewer) throws Exception {
    return json.readTree(
        mvc.perform(get(path).with(user(viewer)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  private UUID create(UUID story, UUID account, String text, UUID parent) throws Exception {
    var fields = new HashMap<String, Object>();
    fields.put("body", text);
    if (parent != null) fields.put("parentId", parent);
    var response =
        mvc.perform(
                post(path(story))
                    .with(user(account))
                    .contentType("application/json")
                    .content(body(fields)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    return UUID.fromString(json.readTree(response.getContentAsByteArray()).get("id").asText());
  }

  private List<UUID> ids(JsonNode page) {
    var ids = new ArrayList<UUID>();
    page.get("items").forEach(c -> ids.add(UUID.fromString(c.get("id").asText())));
    return ids;
  }

  private JsonNode like(UUID story, UUID comment, UUID actor, boolean on) throws Exception {
    return json.readTree(
        mvc.perform(
                request(
                        on
                            ? org.springframework.http.HttpMethod.PUT
                            : org.springframework.http.HttpMethod.DELETE,
                        path(story) + "/" + comment + "/like")
                    .with(user(actor)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  private void remove(UUID story, UUID comment, UUID actor) throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    path(story) + "/" + comment)
                .with(user(actor)))
        .andExpect(status().isNoContent());
  }

  @Test
  void likesAreIdempotentAndCounterStaysCorrectUnderConcurrencyWithoutChangingEditVersion()
      throws Exception {
    UUID story = story(true), comment = create(story, author, "Root", null);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 20; i++)
        tasks.add(pool.submit(() -> comments.like(story, comment, reader, true)));
      for (var task : tasks) task.get(15, TimeUnit.SECONDS);
    }
    assertTrue(like(story, comment, other, true).get("myLiked").asBoolean());
    var row = getJson(path(story) + "/" + comment, reader);
    assertEquals(2, row.get("likes").asLong());
    assertTrue(row.get("myLiked").asBoolean());
    assertEquals(0, row.get("version").asLong());
    assertFalse(getJson(path(story) + "/" + comment, author).get("myLiked").asBoolean());
    mvc.perform(
            put(path(story) + "/" + comment)
                .with(user(author))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"body\":\"Edited after like\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likes").value(2))
        .andExpect(jsonPath("$.version").value(1));
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 12; i++)
        tasks.add(pool.submit(() -> comments.like(story, comment, reader, false)));
      for (var task : tasks) task.get(15, TimeUnit.SECONDS);
    }
    assertEquals(1, getJson(path(story) + "/" + comment, reader).get("likes").asLong());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM comment_likes WHERE comment_id=?", Integer.class, comment));
  }

  @Test
  void chronologicalAndPopularPagesAreBoundToViewerStoryAndSortAndPopularOrderIsStable()
      throws Exception {
    UUID story = story(true),
        a = create(story, author, "A", null),
        b = create(story, author, "B", null),
        c = create(story, reader, "C", null),
        d = create(story, other, "D", null);
    int time = 1;
    for (UUID id : List.of(a, b, c, d))
      jdbc.update(
          "UPDATE story_comments SET created_at=TIMESTAMPTZ '2026-11-01 00:00:00+00'+?*interval '1 second' WHERE id=?",
          time++,
          id);
    for (UUID user : List.of(author, reader, other)) like(story, a, user, true);
    for (UUID user : List.of(author, reader)) like(story, b, user, true);
    like(story, c, other, true);
    assertEquals(List.of(d, c, b, a), ids(getJson(path(story) + "?sort=newest", reader)));
    assertEquals(List.of(a, b, c, d), ids(getJson(path(story) + "?sort=oldest", reader)));
    var first = getJson(path(story) + "?sort=popular&limit=2", reader);
    assertEquals(List.of(a, b), ids(first));
    String cursor = first.get("nextCursor").asText();
    like(story, d, author, true);
    like(story, d, reader, true);
    like(story, d, other, true);
    like(story, a, author, false);
    var last = getJson(path(story) + "?sort=popular&limit=2&cursor=" + cursor, reader);
    assertEquals(List.of(c, d), ids(last));
    assertTrue(last.get("nextCursor").isNull());
    assertEquals(d, ids(getJson(path(story) + "?sort=popular", reader)).getFirst());
    mvc.perform(get(path(story)).with(user(other)).param("sort", "popular").param("cursor", cursor))
        .andExpect(status().isBadRequest());
    mvc.perform(get(path(story)).with(user(reader)).param("sort", "oldest").param("cursor", cursor))
        .andExpect(status().isBadRequest());
    UUID otherStory = story(true);
    mvc.perform(
            get(path(otherStory))
                .with(user(reader))
                .param("sort", "popular")
                .param("cursor", cursor))
        .andExpect(status().isBadRequest());
    String batch =
        new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8)
            .split("\\|")[5];
    jdbc.update(
        "UPDATE comment_rank_batches SET expires_at=now()-interval '1 second' WHERE id=?",
        UUID.fromString(batch));
    mvc.perform(
            get(path(story)).with(user(reader)).param("sort", "popular").param("cursor", cursor))
        .andExpect(status().isBadRequest());
    String date = getJson(path(story) + "?sort=newest&limit=1", reader).get("nextCursor").asText();
    mvc.perform(get(path(story)).with(user(reader)).param("sort", "oldest").param("cursor", date))
        .andExpect(status().isBadRequest());
  }

  @Test
  void repliesToRepliesRemainInOneChronologicalThreadAndCannotCrossStories() throws Exception {
    UUID story = story(true),
        root = create(story, author, "Root", null),
        one = create(story, reader, "Reply one", root),
        two = create(story, other, "Reply to reply", one);
    var roots = getJson(path(story), reader);
    assertEquals(List.of(root), ids(roots));
    assertEquals(2, roots.get("items").get(0).get("replyCount").asLong());
    var replies = getJson(path(story) + "/" + root + "/replies?limit=1", reader);
    assertEquals(List.of(one), ids(replies));
    String cursor = replies.get("nextCursor").asText();
    var last = getJson(path(story) + "/" + root + "/replies?limit=1&cursor=" + cursor, reader);
    assertEquals(List.of(two), ids(last));
    assertEquals(root.toString(), last.get("items").get(0).get("rootId").asText());
    assertEquals(one.toString(), last.get("items").get(0).get("parentId").asText());
    assertEquals("Bob", last.get("items").get(0).get("replyToAuthorName").asText());
    assertEquals(3, getJson("/api/v1/stories/" + story, reader).get("commentCount").asInt());
    UUID different = story(true);
    mvc.perform(
            post(path(different))
                .with(user(reader))
                .contentType("application/json")
                .content(body(Map.of("body", "cross story", "parentId", root))))
        .andExpect(status().isNotFound());
    mvc.perform(get(path(story) + "/" + one + "/replies").with(user(reader)))
        .andExpect(status().isBadRequest());
    UUID anotherRoot = create(story, author, "Another root", null);
    mvc.perform(
            get(path(story) + "/" + anotherRoot + "/replies")
                .with(user(reader))
                .param("cursor", cursor))
        .andExpect(status().isBadRequest());
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO story_comments(id,story_id,author_id,body,parent_id,root_id) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID(),
                different,
                reader,
                "bad FK",
                root,
                root));
  }

  @Test
  void deletingParentsPreservesForeignRepliesAndDeletedTextIsErased() throws Exception {
    UUID story = story(true),
        root = create(story, author, "SECRET_DELETED_PARENT", null),
        one = create(story, reader, "Surviving Bob", root),
        two = create(story, other, "Surviving Carol", one),
        leaf = create(story, author, "Leaf", root);
    like(story, root, reader, true);
    remove(story, root, author);
    var placeholder = getJson(path(story) + "/" + root, reader);
    assertTrue(placeholder.get("deleted").asBoolean());
    assertEquals("", placeholder.get("body").asText());
    assertEquals(0, placeholder.get("likes").asLong());
    assertEquals(3, placeholder.get("replyCount").asLong());
    assertFalse(
        getJson(path(story) + "/" + root + "/replies", reader)
            .toString()
            .contains("SECRET_DELETED_PARENT"));
    mvc.perform(put(path(story) + "/" + root + "/like").with(user(other)))
        .andExpect(status().isNotFound());
    mvc.perform(
            post(path(story))
                .with(user(other))
                .contentType("application/json")
                .content(body(Map.of("body", "reply to deleted", "parentId", root))))
        .andExpect(status().isNotFound());
    mvc.perform(delete(path(story) + "/" + one).with(user(author)))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(path(story) + "/" + one)
                .with(user(author))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"body\":\"stolen\"}"))
        .andExpect(status().isForbidden());
    UUID finalReply = create(story, author, "Still can reply to Carol", two);
    remove(story, leaf, author);
    mvc.perform(get(path(story) + "/" + leaf).with(user(reader))).andExpect(status().isNotFound());
    remove(story, one, reader);
    assertTrue(getJson(path(story) + "/" + one, other).get("deleted").asBoolean());
    assertEquals(
        "удалённый комментарий",
        getJson(path(story) + "/" + two, other).get("replyToAuthorName").asText());
    assertEquals(2, getJson("/api/v1/stories/" + story, reader).get("commentCount").asInt());
    remove(story, two, other);
    remove(story, finalReply, author);
    assertTrue(ids(getJson(path(story), reader)).isEmpty());
    assertEquals(0, getJson("/api/v1/stories/" + story, reader).get("commentCount").asInt());
    mvc.perform(delete("/api/v1/stories/" + story).with(user(author)).header("If-Match", "\"0\""))
        .andExpect(status().isNoContent());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM story_comments WHERE story_id=?", Integer.class, story));
  }

  @Test
  void ownershipVersionsScopesAndInputValidationRemainEnforced() throws Exception {
    UUID story = story(true), root = create(story, reader, "<script>plain text</script>", null);
    mvc.perform(
            put(path(story) + "/" + root)
                .with(user(reader))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"body\":\"updated\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            put(path(story) + "/" + root)
                .with(user(reader))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"body\":\"stale\"}"))
        .andExpect(status().isPreconditionFailed());
    mvc.perform(
            put(path(story) + "/" + root)
                .with(user(reader))
                .contentType("application/json")
                .content("{\"body\":\"no version\"}"))
        .andExpect(status().isPreconditionRequired());
    for (String text : List.of(" ", "x".repeat(2001), "bad\u0000text"))
      mvc.perform(
              post(path(story))
                  .with(user(reader))
                  .contentType("application/json")
                  .content(body(Map.of("body", text))))
          .andExpect(status().isBadRequest());
    mvc.perform(
            post(path(story))
                .with(user(reader))
                .contentType("application/json")
                .content(body(Map.of("body", "spoof", "authorId", author))))
        .andExpect(status().isBadRequest());
    mvc.perform(get(path(story))).andExpect(status().isUnauthorized());
    mvc.perform(put(path(story) + "/" + root + "/like")).andExpect(status().isUnauthorized());
    mvc.perform(
            put(path(story) + "/" + root + "/like")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(reader.toString()).claim("account_id", reader.toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(path(story) + "/" + root + "/like")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.write"))))
        .andExpect(status().isForbidden());
    for (String query : List.of("sort=invalid", "limit=0", "limit=21", "cursor=bad"))
      mvc.perform(get(path(story) + "?" + query).with(user(reader)))
          .andExpect(status().isBadRequest());
    UUID pending = story(false);
    mvc.perform(get(path(pending)).with(user(author))).andExpect(status().isNotFound());
    mvc.perform(
            post(path(pending))
                .with(user(author))
                .contentType("application/json")
                .content("{\"body\":\"not published\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void concurrentReplyCreationAndParentDeletionNeverLoseAnotherUsersReply() throws Exception {
    for (int n = 0; n < 6; n++) {
      UUID story = story(true), root = create(story, author, "Parent", null);
      var start = new CountDownLatch(1);
      try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<Boolean> child =
            pool.submit(
                () -> {
                  start.await();
                  try {
                    comments.create(story, reader, "Kept reply", root);
                    return true;
                  } catch (StoryException e) {
                    assertEquals(StoryException.Reason.NOT_FOUND, e.reason());
                    return false;
                  }
                });
        Future<?> deleted =
            pool.submit(
                () -> {
                  start.await();
                  comments.delete(story, root, author);
                  return null;
                });
        start.countDown();
        boolean replied = child.get(15, TimeUnit.SECONDS);
        deleted.get(15, TimeUnit.SECONDS);
        if (replied) {
          var roots = getJson(path(story), reader);
          assertEquals(1, roots.get("items").size());
          assertTrue(roots.get("items").get(0).get("deleted").asBoolean());
          assertEquals(
              "Kept reply",
              getJson(path(story) + "/" + root + "/replies", reader)
                  .get("items")
                  .get(0)
                  .get("body")
                  .asText());
        } else assertTrue(ids(getJson(path(story), reader)).isEmpty());
      }
    }
  }
}
