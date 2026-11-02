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
import io.plottwist.moderation.contract.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
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
    jdbc.update("DELETE FROM stories WHERE author_id IN (?,?,?)", author, reader, other);
    jdbc.update("DELETE FROM user_profiles WHERE account_id IN (?,?,?)", author, reader, other);
    jdbc.update("DELETE FROM media_objects WHERE owner_id IN (?,?,?)", author, reader, other);
  }

  @Test
  void onlyPublicFieldsAndSanitizedPhotoAreVisibleToOtherUsers() throws Exception {
    profile();
    var view = getJson("/api/v1/profiles/" + author, reader);
    assertEquals(4, view.size());
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
}
