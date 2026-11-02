package io.plottwist.domain.profile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import io.plottwist.domain.media.application.port.ObjectStorage;
import io.plottwist.domain.media.model.ImageDigest;
import io.plottwist.domain.moderation.infrastructure.messaging.OutboxPublisher;
import io.plottwist.domain.moderation.infrastructure.persistence.DecisionStore;
import io.plottwist.domain.profile.application.FollowService;
import io.plottwist.moderation.contract.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
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
@EnabledIfEnvironmentVariable(named = "PUBLIC_PROFILE_INTEGRATION", matches = "true")
class PublicProfileIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired DecisionStore decisions;
  @Autowired FollowService follows;
  @MockitoBean OutboxPublisher publisher;
  @MockitoBean ObjectStorage storage;
  UUID author = UUID.randomUUID(), reader = UUID.randomUUID(), other = UUID.randomUUID();

  private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      user(UUID id) {
    return jwt()
        .jwt(j -> j.subject(id.toString()).claim("account_id", id.toString()))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_api.read"),
            new SimpleGrantedAuthority("SCOPE_api.write"));
  }

  private void profile() {
    jdbc.update(
        "INSERT INTO user_profiles(account_id,display_name,bio,given_name,family_name,birth_date) VALUES(?,?,?, ?,?,DATE '1990-01-02')",
        author,
        "Автор",
        "Открытое описание\n<script>plain text</script>",
        "PRIVATE_GIVEN_NAME",
        "PRIVATE_FAMILY_NAME");
  }

  private UUID story(UUID owner, String body, boolean published) throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/stories")
                    .with(user(owner))
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("title", body, "body", body))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse();
    UUID id = UUID.fromString(json.readTree(result.getContentAsByteArray()).get("id").asText());
    if (published) {
      var decision =
          jdbc.queryForObject(
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
      assertTrue(decisions.apply(decision));
    }
    return id;
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

  @AfterEach
  void cleanup() {
    jdbc.update(
        "DELETE FROM user_follows WHERE follower_id IN (?,?,?) OR following_id IN (?,?,?)",
        author,
        reader,
        other,
        author,
        reader,
        other);
    jdbc.update("DELETE FROM stories WHERE author_id IN (?,?,?)", author, reader, other);
    jdbc.update("DELETE FROM user_profiles WHERE account_id IN (?,?,?)", author, reader, other);
    jdbc.update("DELETE FROM media_objects WHERE owner_id IN (?,?,?)", author, reader, other);
  }

  @Test
  void onlyPublicFieldsAndSanitizedPhotoAreVisibleToOtherUsers() throws Exception {
    profile();
    var view = getJson("/api/v1/profiles/" + author, reader);
    assertEquals(7, view.size());
    assertEquals(author.toString(), view.get("accountId").asText());
    assertEquals("Автор", view.get("displayName").asText());
    assertTrue(view.get("bio").asText().contains("Открытое описание"));
    assertFalse(view.toString().contains("PRIVATE_"));
    assertFalse(view.has("birthDate"));
    assertFalse(view.has("email"));
    assertFalse(view.has("version"));
    assertTrue(getJson("/api/v1/profiles/" + author + "/stories", reader).get("items").isEmpty());
    mvc.perform(get("/api/v1/profiles/" + author + "/photo").with(user(reader)))
        .andExpect(status().isNotFound());
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "JPEG", output);
    byte[] image = output.toByteArray();
    var mediaId = UUID.randomUUID();
    String key = "avatars/" + author + "/private-key.jpg", hash = ImageDigest.sha256(image);
    jdbc.update(
        "INSERT INTO media_objects(id,owner_id,object_key,sha256,size_bytes,state) VALUES(?,?,?,?,?,'ACTIVE')",
        mediaId,
        author,
        key,
        hash,
        image.length);
    jdbc.update("UPDATE user_profiles SET photo_id=? WHERE account_id=?", mediaId, author);
    when(storage.get(key)).thenReturn(image);
    assertEquals(hash, getJson("/api/v1/profiles/" + author, reader).get("photoSha256").asText());
    mvc.perform(get("/api/v1/profiles/" + author + "/photo").with(user(reader)))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/jpeg"))
        .andExpect(header().string("ETag", "\"" + hash + "\""))
        .andExpect(content().bytes(image));
    mvc.perform(
            put("/api/v1/profiles/" + author)
                .with(user(reader))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/v1/profiles/" + author).with(user(reader)))
        .andExpect(status().isForbidden());
  }

  @Test
  void authorFeedPaginatesOnlyPublishedSnapshotsEvenForTheAuthor() throws Exception {
    profile();
    var published = new HashSet<UUID>();
    for (int i = 0; i < 3; i++) published.add(story(author, "Approved story " + i, true));
    UUID edited = published.iterator().next();
    story(author, "PRIVATE_UNPUBLISHED_STORY", false);
    story(other, "OTHER_AUTHOR", true);
    mvc.perform(
            put("/api/v1/stories/" + edited)
                .with(user(author))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"title\":\"PRIVATE_EDIT\",\"body\":\"PRIVATE_DRAFT\"}"))
        .andExpect(status().isAccepted());
    jdbc.update(
        "INSERT INTO story_reactions(story_id,account_id,value) VALUES(?,?,1)", edited, reader);
    for (UUID viewer : List.of(author, reader)) {
      String cursor = null;
      var seen = new HashSet<UUID>();
      do {
        var request =
            get("/api/v1/profiles/" + author + "/stories").param("limit", "1").with(user(viewer));
        if (cursor != null) request.param("cursor", cursor);
        var page =
            json.readTree(
                mvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray());
        assertEquals(1, page.get("items").size());
        assertFalse(page.toString().contains("PRIVATE_"));
        assertFalse(page.toString().contains("OTHER_AUTHOR"));
        var item = page.get("items").get(0);
        assertEquals("PUBLISHED", item.get("moderationStatus").asText());
        assertEquals(0, item.get("version").asLong());
        if (item.get("id").asText().equals(edited.toString()))
          assertEquals(viewer.equals(reader) ? 1 : 0, item.get("myReaction").asInt());
        assertTrue(seen.add(UUID.fromString(item.get("id").asText())));
        cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
      } while (cursor != null);
      assertEquals(published, seen);
    }
    mvc.perform(
            get("/api/v1/profiles/" + author + "/stories").with(user(reader)).param("limit", "21"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/profiles/" + author + "/stories")
                .with(user(reader))
                .param("cursor", "bad"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownProfilesAndPrivateOnlyAuthorsDoNotRevealAccounts() throws Exception {
    String route = "/api/v1/profiles/" + author;
    mvc.perform(get(route).with(user(reader))).andExpect(status().isNotFound());
    mvc.perform(get(route + "/stories").with(user(reader))).andExpect(status().isNotFound());
    UUID draft = story(author, "PRIVATE", false);
    mvc.perform(get(route).with(user(reader))).andExpect(status().isNotFound());
    jdbc.update("DELETE FROM stories WHERE id=?", draft);
    story(author, "Visible", true);
    assertEquals("Читатель", getJson(route, reader).get("displayName").asText());
    assertTrue(getJson(route, reader).get("bio").isNull());
    assertEquals(1, getJson(route + "/stories", reader).get("items").size());
  }

  @Test
  void userIdentityAndReadScopeAreRequired() throws Exception {
    profile();
    for (String suffix : List.of("", "/photo", "/stories")) {
      String route = "/api/v1/profiles/" + author + suffix;
      mvc.perform(get(route)).andExpect(status().isUnauthorized());
      mvc.perform(get(route).with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
          .andExpect(status().isForbidden());
      mvc.perform(
              get(route)
                  .with(
                      jwt()
                          .jwt(
                              j ->
                                  j.subject(reader.toString())
                                      .claim("account_id", reader.toString()))
                          .authorities(new SimpleGrantedAuthority("SCOPE_api.write"))))
          .andExpect(status().isForbidden());
    }
  }

  private JsonNode follow(UUID actor, UUID target, boolean subscribed) throws Exception {
    String route = "/api/v1/profiles/" + target + "/follow";
    return json.readTree(
        mvc.perform((subscribed ? put(route) : delete(route)).with(user(actor)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  @Test
  void subscriptionsAreIdempotentIndependentAndSurviveDeletingProfileDetails() throws Exception {
    profile();
    for (int i = 0; i < 3; i++) {
      var state = follow(reader, author, true);
      assertTrue(state.get("following").asBoolean());
      assertEquals(1, state.get("followersCount").asLong());
    }
    var own = getJson("/api/v1/profiles/" + reader, reader);
    assertEquals("Читатель", own.get("displayName").asText());
    assertEquals(1, own.get("followingCount").asLong());
    assertTrue(getJson("/api/v1/profiles/" + author, reader).get("following").asBoolean());
    assertFalse(getJson("/api/v1/profiles/" + author, other).get("following").asBoolean());
    follow(other, author, true);
    assertEquals(2, getJson("/api/v1/profiles/" + author, reader).get("followersCount").asLong());
    follow(author, reader, true); // mutual subscriptions are valid.
    assertEquals(1, getJson("/api/v1/profiles/" + author, author).get("followingCount").asLong());
    mvc.perform(delete("/api/v1/profile").with(user(author)).header("If-Match", "\"0\""))
        .andExpect(status().isNoContent());
    var fallback = getJson("/api/v1/profiles/" + author, reader);
    assertEquals("Читатель", fallback.get("displayName").asText());
    assertTrue(fallback.get("bio").isNull());
    assertEquals(2, fallback.get("followersCount").asLong());
    assertTrue(fallback.get("following").asBoolean());
    for (int i = 0; i < 3; i++) {
      var state = follow(reader, author, false);
      assertFalse(state.get("following").asBoolean());
      assertEquals(1, state.get("followersCount").asLong());
    }
    assertTrue(getJson("/api/v1/profiles/" + author, other).get("following").asBoolean());
    assertEquals(0, getJson("/api/v1/profiles/" + reader, reader).get("followingCount").asLong());
  }

  @Test
  void concurrentDuplicateAndOpposingRequestsNeverDuplicateOrCorruptTheEdge() throws Exception {
    profile();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var subscriptions = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 12; i++)
        subscriptions.add(() -> follows.setFollowing(reader, author, true).following());
      for (var result : executor.invokeAll(subscriptions)) assertTrue(result.get());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM user_follows WHERE follower_id=? AND following_id=?",
              Long.class,
              reader,
              author));
      var changes = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 16; i++) {
        boolean desired = i % 2 == 0;
        changes.add(
            () -> {
              var state = follows.setFollowing(reader, author, desired);
              return state.following() == desired && state.followersCount() == (desired ? 1 : 0);
            });
      }
      for (var result : executor.invokeAll(changes)) assertTrue(result.get());
      assertFalse(follows.setFollowing(reader, author, false).following());
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM user_follows WHERE follower_id=? AND following_id=?",
              Long.class,
              reader,
              author));
    }
  }

  @Test
  void selfUnknownTargetsAndUntrustedActorsCannotCreateSubscriptions() throws Exception {
    profile();
    String path = "/api/v1/profiles/" + author + "/follow";
    mvc.perform(put(path).with(user(author))).andExpect(status().isBadRequest());
    mvc.perform(delete(path).with(user(author))).andExpect(status().isBadRequest());
    mvc.perform(put("/api/v1/profiles/" + UUID.randomUUID() + "/follow").with(user(reader)))
        .andExpect(status().isNotFound());
    for (var method : List.of("PUT", "DELETE")) {
      mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path))
          .andExpect(status().isUnauthorized());
      mvc.perform(
              request(org.springframework.http.HttpMethod.valueOf(method), path)
                  .with(user(reader).authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
          .andExpect(status().isForbidden());
      mvc.perform(
              request(org.springframework.http.HttpMethod.valueOf(method), path)
                  .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.write"))))
          .andExpect(status().isForbidden());
      mvc.perform(
              request(org.springframework.http.HttpMethod.valueOf(method), path)
                  .with(
                      jwt()
                          .jwt(
                              j ->
                                  j.subject(reader.toString())
                                      .claim("account_id", other.toString()))
                          .authorities(new SimpleGrantedAuthority("SCOPE_api.write"))))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            put(path)
                .with(user(reader))
                .contentType("application/json")
                .content("{\"followerId\":\"" + other + "\"}"))
        .andExpect(status().isOk());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM user_follows WHERE follower_id=? AND following_id=?",
            Long.class,
            reader,
            author));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM user_follows WHERE follower_id=?", Long.class, other));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO user_follows(follower_id,following_id) VALUES(?,?)", reader, reader));
  }

  @Test
  void subscriptionFeedUsesApprovedSnapshotsAndCurrentEdgesWithKeysetPagination() throws Exception {
    profile();
    var published = new HashSet<UUID>();
    for (int i = 0; i < 3; i++) published.add(story(author, "Subscribed story " + i, true));
    UUID edited = published.iterator().next();
    story(author, "PRIVATE_UNPUBLISHED", false);
    story(other, "NOT_FOLLOWED", true);
    story(reader, "OWN_POST", true);
    mvc.perform(
            put("/api/v1/stories/" + edited)
                .with(user(author))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"title\":\"PRIVATE_EDIT\",\"body\":\"PRIVATE_DRAFT\"}"))
        .andExpect(status().isAccepted());
    assertTrue(getJson("/api/v1/stories/following", reader).get("items").isEmpty());
    follow(reader, author, true);
    jdbc.update(
        "INSERT INTO story_reactions(story_id,account_id,value) VALUES(?,?,1)", edited, reader);
    var seen = new HashSet<UUID>();
    String cursor = null;
    do {
      var request = get("/api/v1/stories/following").with(user(reader)).param("limit", "1");
      if (cursor != null) request.param("cursor", cursor);
      var page =
          json.readTree(
              mvc.perform(request)
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsByteArray());
      assertEquals(1, page.get("items").size());
      assertFalse(page.toString().contains("PRIVATE_"));
      assertFalse(page.toString().contains("NOT_FOLLOWED"));
      assertFalse(page.toString().contains("OWN_POST"));
      var item = page.get("items").get(0);
      assertEquals("PUBLISHED", item.get("moderationStatus").asText());
      assertEquals(0, item.get("version").asLong());
      if (item.get("id").asText().equals(edited.toString()))
        assertEquals(1, item.get("myReaction").asInt());
      assertTrue(seen.add(UUID.fromString(item.get("id").asText())));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
    } while (cursor != null);
    assertEquals(published, seen);
    assertTrue(
        getJson("/api/v1/stories/following?accountId=" + reader, other).get("items").isEmpty());
    mvc.perform(get("/api/v1/stories/following").with(user(reader)).param("limit", "21"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/stories/following").with(user(reader)).param("cursor", "bad"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/stories/following")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/stories/following")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_api.read"))))
        .andExpect(status().isForbidden());
    follow(reader, author, false);
    assertTrue(getJson("/api/v1/stories/following", reader).get("items").isEmpty());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM story_reactions WHERE story_id=? AND account_id=?",
            Long.class,
            edited,
            reader));
  }
}
