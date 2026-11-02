package io.plottwist.domain.attachment;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import io.plottwist.domain.attachment.application.port.AttachmentStorage;
import io.plottwist.domain.attachment.infrastructure.cleanup.AttachmentCleanup;
import io.plottwist.domain.moderation.infrastructure.messaging.OutboxPublisher;
import io.plottwist.domain.moderation.infrastructure.persistence.DecisionStore;
import io.plottwist.moderation.contract.*;
import java.awt.image.BufferedImage;
import java.io.*;
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
@EnabledIfEnvironmentVariable(named = "STORY_MEDIA_INTEGRATION", matches = "true")
class AttachmentIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired DecisionStore decisions;
  @Autowired AttachmentCleanup cleanup;
  @Autowired AttachmentStorage storage;
  @MockitoBean OutboxPublisher publisher;
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

  private byte[] png() throws Exception {
    var output = new ByteArrayOutputStream();
    var img = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
    img.setRGB(0, 0, 0xffaa0011);
    ImageIO.write(img, "PNG", output);
    return output.toByteArray();
  }

  private UUID upload() throws Exception {
    return UUID.fromString(
        json.readTree(
                mvc.perform(
                        post("/api/v1/story-media")
                            .with(user(author))
                            .contentType("application/octet-stream")
                            .content(png()))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray())
            .get("id")
            .asText());
  }

  private UUID story(String body) throws Exception {
    return UUID.fromString(
        json.readTree(
                mvc.perform(
                        post("/api/v1/stories")
                            .with(user(author))
                            .contentType("application/json")
                            .content(
                                json.writeValueAsBytes(Map.of("title", "Story", "body", body))))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray())
            .get("id")
            .asText());
  }

  private String link(UUID asset, UUID viewer) throws Exception {
    return json.readTree(
            mvc.perform(get("/api/v1/story-media/" + asset + "/access").with(user(viewer)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray())
        .get("url")
        .asText();
  }

  private void approve(UUID id) {
    var result =
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
    assertTrue(decisions.apply(result));
  }

  @AfterEach
  void removeTestData() {
    jdbc.update("DELETE FROM stories WHERE author_id IN (?,?)", author, reader);
    var files =
        jdbc.queryForList(
            "SELECT object_key FROM story_attachments WHERE owner_id IN (?,?)",
            String.class,
            author,
            reader);
    for (var key : files) storage.delete(key);
    jdbc.update("DELETE FROM story_attachments WHERE owner_id IN (?,?)", author, reader);
  }

  @Test
  void actualS3UploadSupportsPrivatePreviewNativeRangesAndHead() throws Exception {
    var id = upload();
    String url = link(id, author);
    mvc.perform(get("/api/v1/story-media/" + id + "/access").with(user(reader)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/story-media/" + id + "/content")).andExpect(status().isNotFound());
    var full =
        mvc.perform(get(url))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertNotNull(ImageIO.read(new ByteArrayInputStream(full)));
    var range =
        mvc.perform(get(url).header("Range", "bytes=0-1"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes 0-1/" + full.length))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertArrayEquals(Arrays.copyOf(full, 2), range);
    mvc.perform(head(url))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Length", String.valueOf(full.length)))
        .andExpect(content().bytes(new byte[0]));
    mvc.perform(get(url).header("Range", "bytes=99999999-"))
        .andExpect(status().isRequestedRangeNotSatisfiable());
  }

  @Test
  void snapshotAccessIsPreservedDuringEditsThenRevokedAndFilesCannotBeStolen() throws Exception {
    var asset = upload();
    var marker = "Before\n![photo](attachment:" + asset + ")\nAfter";
    var id = story(marker);
    mvc.perform(get("/api/v1/stories/" + id).with(user(reader))).andExpect(status().isNotFound());
    approve(id);
    String oldLink = link(asset, reader);
    var publicStory =
        json.readTree(
            mvc.perform(get("/api/v1/stories/" + id).with(user(reader)))
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
    assertEquals(asset.toString(), publicStory.get("attachments").get(0).get("id").asText());
    assertFalse(publicStory.toString().contains("objectKey"));
    mvc.perform(
            post("/api/v1/stories")
                .with(user(reader))
                .contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("title", "Stolen", "body", marker))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/api/v1/stories/" + id)
                .with(user(author))
                .header("If-Match", "\"0\"")
                .contentType("application/json")
                .content("{\"title\":\"Story\",\"body\":\"Without attachments\"}"))
        .andExpect(status().isAccepted());
    mvc.perform(get(oldLink).header("Range", "bytes=0-1")).andExpect(status().isPartialContent());
    mvc.perform(delete("/api/v1/story-media/" + asset).with(user(author)))
        .andExpect(status().isConflict());
    approve(id);
    mvc.perform(get(oldLink)).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/story-media/" + asset).with(user(author)))
        .andExpect(status().isNoContent());
    cleanup.clean();
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM story_attachments WHERE id=?", Integer.class, asset));
  }

  @Test
  void deletionInvalidatesLinksAndRejectsActiveFormats() throws Exception {
    var asset = upload();
    var id = story("![photo](attachment:" + asset + ")");
    approve(id);
    var url = link(asset, reader);
    mvc.perform(delete("/api/v1/stories/" + id).with(user(author)).header("If-Match", "\"0\""))
        .andExpect(status().isNoContent());
    mvc.perform(get(url)).andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/story-media")
                .with(user(author))
                .contentType("application/octet-stream")
                .content("<svg onload=alert(1)></svg>"))
        .andExpect(status().isUnsupportedMediaType());
  }
}
