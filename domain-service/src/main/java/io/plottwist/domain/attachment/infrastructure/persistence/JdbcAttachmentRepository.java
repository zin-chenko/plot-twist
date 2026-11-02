package io.plottwist.domain.attachment.infrastructure.persistence;

import io.plottwist.domain.attachment.application.*;
import io.plottwist.domain.attachment.application.port.AttachmentRepository;
import io.plottwist.domain.attachment.model.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAttachmentRepository implements AttachmentRepository {
  private final JdbcTemplate jdbc;

  public JdbcAttachmentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void reserve(StoredAttachment a) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text,43))", a.owner().toString());
    var usage =
        jdbc.queryForMap(
            "SELECT count(*) AS files,COALESCE(sum(size_bytes),0) AS bytes FROM story_attachments WHERE owner_id=?",
            a.owner());
    if (((Number) usage.get("files")).longValue() >= 200
        || ((Number) usage.get("bytes")).longValue() + a.media().sizeBytes() > 500L * 1024 * 1024)
      throw new AttachmentException(
          AttachmentException.Reason.BUSY, "Attachment storage quota reached");
    var m = a.media();
    jdbc.update(
        "INSERT INTO story_attachments(id,owner_id,object_key,kind,content_type,size_bytes,width,height,duration_seconds,sha256,state) VALUES(?,?,?,?,?,?,?,?,?,?,'UPLOADING')",
        m.id(),
        a.owner(),
        a.objectKey(),
        m.kind().name(),
        m.contentType(),
        m.sizeBytes(),
        m.width(),
        m.height(),
        m.durationSeconds(),
        a.sha256());
  }

  public boolean ready(UUID id) {
    return jdbc.update(
            "UPDATE story_attachments SET state='READY' WHERE id=? AND state='UPLOADING'", id)
        == 1;
  }

  public Optional<StoredAttachment> accessible(UUID id, UUID viewer) {
    return jdbc
        .query(
            """
  SELECT a.* FROM story_attachments a WHERE a.id=? AND a.state='READY'
  AND (a.owner_id=? OR EXISTS(SELECT 1 FROM story_attachment_refs ref JOIN stories s ON s.id=ref.story_id
    WHERE ref.attachment_id=a.id AND ref.revision=s.published_version))
  """,
            (row, n) ->
                new StoredAttachment(
                    new Attachment(
                        row.getObject("id", UUID.class),
                        Attachment.Kind.valueOf(row.getString("kind")),
                        row.getString("content_type"),
                        row.getLong("size_bytes"),
                        row.getInt("width"),
                        row.getInt("height"),
                        row.getObject("duration_seconds", Double.class)),
                    row.getObject("owner_id", UUID.class),
                    row.getString("object_key"),
                    row.getString("sha256")),
            id,
            viewer)
        .stream()
        .findFirst();
  }

  @Transactional
  public boolean requestDelete(UUID id, UUID owner) {
    var found =
        jdbc.queryForList(
            "SELECT id FROM story_attachments WHERE id=? AND owner_id=? AND state='READY' FOR UPDATE",
            UUID.class,
            id,
            owner);
    if (found.isEmpty()) return false;
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM story_attachment_refs WHERE attachment_id=?)",
            Boolean.class,
            id)))
      throw new AttachmentException(
          AttachmentException.Reason.CONFLICT,
          "Remove this attachment from all current and published story versions first");
    jdbc.update(
        "UPDATE story_attachments SET state='DELETE_PENDING',delete_after=now() WHERE id=?", id);
    return true;
  }
}
