package io.plottwist.domain.moderation.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.plottwist.domain.moderation.infrastructure.persistence.*;
import io.plottwist.domain.recommendation.infrastructure.persistence.JdbcIndexJobs;
import io.plottwist.domain.story.infrastructure.persistence.JdbcStoryRepository;
import io.plottwist.domain.story.model.StoryContent;
import io.plottwist.moderation.contract.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "MODERATION_DATABASE_TEST", matches = "true")
class ModerationPersistenceTest {
  static JdbcTemplate jdbc;
  static TransactionTemplate tx;
  static JdbcStoryRepository stories;
  static DecisionStore decisions;
  static JdbcIndexJobs index;
  static ModerationOutbox outbox;
  static UUID legacy = UUID.randomUUID();
  UUID author = UUID.randomUUID();
  UUID reader = UUID.randomUUID();

  @BeforeAll
  static void setup() {
    var url = System.getenv("MODERATION_TEST_DB_URL");
    if (url == null || !url.contains("moderation_test_"))
      throw new IllegalStateException("A separate moderation_test_ database is required");
    var ds = new DriverManagerDataSource(url, "app_domain", "domain_dev_password");
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    Flyway.configure().dataSource(ds).target("6").load().migrate();
    jdbc.update(
        "INSERT INTO stories(id,author_id,title,body) VALUES(?,?,?,?)",
        legacy,
        UUID.randomUUID(),
        "Legacy",
        "Previously published");
    Flyway.configure().dataSource(ds).load().migrate();
    stories = new JdbcStoryRepository(jdbc, new ObjectMapper());
    decisions = new DecisionStore(jdbc, new ObjectMapper());
    index = new JdbcIndexJobs(jdbc, tx);
    outbox = new ModerationOutbox(jdbc);
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM stories WHERE author_id=?", author);
  }

  private UUID create(String body) {
    var id = UUID.randomUUID();
    tx.executeWithoutResult(s -> stories.create(id, author, new StoryContent("Title", body)));
    return id;
  }

  private ModerationDecision result(UUID id, ModerationDecision.Outcome outcome) {
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
                outcome,
                List.of()),
        id);
  }

  private boolean apply(ModerationDecision result) {
    return Boolean.TRUE.equals(tx.execute(s -> decisions.apply(result)));
  }

  @Test
  void migrationPreservesExistingPublications() {
    assertEquals("Previously published", stories.find(legacy, reader).orElseThrow().body());
    assertEquals("PUBLISHED", stories.find(legacy, reader).orElseThrow().moderationStatus());
  }

  @Test
  void queueAndTextRollbackTogetherAndPendingCannotLeak() {
    var rollbackId = UUID.randomUUID();
    tx.executeWithoutResult(
        s -> {
          stories.create(rollbackId, author, new StoryContent("Title", "Rolled back"));
          s.setRollbackOnly();
        });
    assertTrue(stories.find(rollbackId, author).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM moderation_outbox WHERE story_id=?", Integer.class, rollbackId));
    var id = create("Private pending");
    assertTrue(stories.find(id, reader).isEmpty());
    assertTrue(index.document(id).isEmpty());
    assertFalse(stories.lockForInteraction(id));
    var privateStory = stories.find(id, author).orElseThrow();
    assertEquals("PENDING_MODERATION", privateStory.moderationStatus());
    assertNull(privateStory.publishedVersion());
    assertFalse(
        stories.feed(reader, null, false, null, 20).stream()
            .anyMatch(s -> s.id().equals(privateStory.id())));
    var delivery = outbox.claim().orElseThrow();
    assertEquals(ContentHash.of("Title", "Private pending"), delivery.task().contentHash());
    outbox.sent(delivery);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM moderation_outbox WHERE job_id=? AND sent_at IS NOT NULL",
            Integer.class,
            delivery.task().jobId()));
  }

  @Test
  void approvedSnapshotSurvivesRejectedEditAndDelayedResults() {
    var id = create("Approved text");
    var old = result(id, ModerationDecision.Outcome.APPROVE);
    assertTrue(apply(old));
    assertFalse(apply(old));
    tx.executeWithoutResult(
        s ->
            assertTrue(
                stories.update(id, author, 0, new StoryContent("New title", "Rejected draft"))));
    assertFalse(apply(old));
    assertEquals("Approved text", stories.find(id, reader).orElseThrow().body());
    assertEquals("Rejected draft", stories.find(id, author).orElseThrow().body());
    assertEquals(0, index.document(id).orElseThrow().version());
    assertTrue(apply(result(id, ModerationDecision.Outcome.BLOCK)));
    assertEquals("REJECTED", stories.find(id, author).orElseThrow().moderationStatus());
    assertEquals("PUBLISHED", stories.find(id, reader).orElseThrow().moderationStatus());
    assertNull(stories.find(id, reader).orElseThrow().moderationReason());
    assertEquals("Approved text", stories.find(id, reader).orElseThrow().body());
    tx.executeWithoutResult(
        s ->
            assertTrue(
                stories.update(id, author, 1, new StoryContent("Fixed", "Approved new text"))));
    assertTrue(apply(result(id, ModerationDecision.Outcome.APPROVE)));
    assertEquals(2, stories.find(id, reader).orElseThrow().version());
    assertEquals("Approved new text", index.document(id).orElseThrow().body());
  }

  @Test
  void deleteAndDuplicateDecisionCannotResurrectContent() {
    var id = create("Delete me");
    var result = result(id, ModerationDecision.Outcome.APPROVE);
    tx.executeWithoutResult(s -> assertTrue(stories.delete(id, author, 0)));
    assertFalse(apply(result));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM moderation_outbox WHERE story_id=?", Integer.class, id));
  }
}
