package io.plottwist.domain.story.infrastructure.persistence;

import io.plottwist.domain.story.application.port.StoryRepository;
import io.plottwist.domain.story.model.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStoryRepository implements StoryRepository {
  private final JdbcTemplate jdbc;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private static final String SELECT =
      """
      SELECT s.id,s.author_id,s.created_at,
      CASE WHEN s.author_id=? THEN s.title ELSE s.published_title END AS title,
      CASE WHEN s.author_id=? THEN s.body ELSE s.published_body END AS body,
      CASE WHEN s.author_id=? THEN s.version ELSE s.published_version END AS version,
      CASE WHEN s.author_id=? THEN s.updated_at ELSE s.published_updated_at END AS updated_at,
      CASE WHEN s.author_id=? THEN s.moderation_status ELSE 'PUBLISHED' END AS moderation_status,
      CASE WHEN s.author_id=? THEN s.moderation_reason ELSE NULL END AS moderation_reason,
      COALESCE((SELECT jsonb_agg(jsonb_build_object('id',a.id,'kind',a.kind,'contentType',a.content_type,'sizeBytes',a.size_bytes,'width',a.width,'height',a.height,'durationSeconds',a.duration_seconds) ORDER BY ref.position)
      FROM story_attachment_refs ref JOIN story_attachments a ON a.id=ref.attachment_id AND a.state='READY'
      WHERE ref.story_id=s.id AND ref.revision=CASE WHEN s.author_id=? THEN s.version ELSE s.published_version END),'[]'::jsonb) AS attachments,
      s.published_version, COALESCE(p.display_name,'Читатель') AS author_name, m.sha256 AS author_photo_sha256,
      (SELECT count(*) FROM story_reactions r WHERE r.story_id=s.id AND r.value=1) AS likes,
      (SELECT count(*) FROM story_reactions r WHERE r.story_id=s.id AND r.value=-1) AS dislikes,
      COALESCE((SELECT r.value FROM story_reactions r WHERE r.story_id=s.id AND r.account_id=?),0) AS my_reaction,
      (SELECT count(*) FROM story_comments c WHERE c.story_id=s.id) AS comment_count
      FROM stories s LEFT JOIN user_profiles p ON p.account_id=s.author_id
      LEFT JOIN media_objects m ON m.id=p.photo_id AND m.state='ACTIVE'
      """;

  public JdbcStoryRepository(JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.json = json;
    this.jdbc = jdbc;
  }

  @Override
  public List<Story> feed(UUID viewer, UUID author, boolean liked, PagePosition before, int limit) {
    String sql =
        SELECT + (author == null ? " WHERE s.published_version IS NOT NULL" : " WHERE true");
    var args = new ArrayList<Object>();
    args.addAll(Collections.nCopies(8, viewer));
    if (liked) {
      sql +=
          " AND EXISTS (SELECT 1 FROM story_reactions liked WHERE liked.story_id=s.id AND liked.account_id=? AND liked.value=1)";
      args.add(viewer);
    }
    if (author != null) {
      sql += " AND s.author_id=?";
      args.add(author);
    }
    if (before != null) {
      sql += " AND (s.created_at,s.id) < (?,?)";
      args.add(OffsetDateTime.ofInstant(before.createdAt(), java.time.ZoneOffset.UTC));
      args.add(before.id());
    }
    sql += " ORDER BY s.created_at DESC,s.id DESC LIMIT ?";
    args.add(limit);
    return jdbc.query(sql, this::story, args.toArray());
  }

  @Override
  public List<Story> findMany(List<UUID> ids, UUID viewer) {
    if (ids.isEmpty()) return List.of();
    var args = new ArrayList<Object>();
    args.addAll(Collections.nCopies(8, viewer));
    args.addAll(ids);
    return jdbc.query(
        SELECT
            + " WHERE s.published_version IS NOT NULL AND s.id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ")",
        this::story,
        args.toArray());
  }

  @Override
  public List<Story> publishedByAuthor(UUID viewer, UUID author, PagePosition before, int limit) {
    // A public author page always shows the approved snapshot, even to its own author.
    var args = new ArrayList<Object>(Collections.nCopies(7, null));
    args.add(viewer); // my_reaction still belongs to the real viewer.
    args.add(author);
    String sql = SELECT + " WHERE s.published_version IS NOT NULL AND s.author_id=?";
    if (before != null) {
      sql += " AND (s.created_at,s.id) < (?,?)";
      args.add(OffsetDateTime.ofInstant(before.createdAt(), java.time.ZoneOffset.UTC));
      args.add(before.id());
    }
    args.add(limit);
    return jdbc.query(
        sql + " ORDER BY s.created_at DESC,s.id DESC LIMIT ?", this::story, args.toArray());
  }

  @Override
  public Optional<Story> find(UUID id, UUID viewer) {
    var args = new ArrayList<Object>();
    args.addAll(Collections.nCopies(8, viewer));
    args.add(id);
    args.add(viewer);
    return jdbc
        .query(
            SELECT + " WHERE s.id=? AND (s.author_id=? OR s.published_version IS NOT NULL)",
            this::story,
            args.toArray())
        .stream()
        .findFirst();
  }

  @Override
  public List<Story> following(UUID viewer, PagePosition before, int limit) {
    var args = new ArrayList<Object>(Collections.nCopies(7, null));
    args.add(viewer);
    args.add(viewer);
    String sql =
        SELECT
            + " WHERE s.published_version IS NOT NULL AND EXISTS(SELECT 1 FROM user_follows f WHERE f.follower_id=? AND f.following_id=s.author_id)";
    if (before != null) {
      sql += " AND (s.created_at,s.id) < (?,?)";
      args.add(OffsetDateTime.ofInstant(before.createdAt(), java.time.ZoneOffset.UTC));
      args.add(before.id());
    }
    args.add(limit);
    return jdbc.query(
        sql + " ORDER BY s.created_at DESC,s.id DESC LIMIT ?", this::story, args.toArray());
  }

  @Override
  public void create(UUID id, UUID author, StoryContent content) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))", author.toString());
    if (jdbc.queryForObject(
            "SELECT count(*) FROM stories WHERE author_id=? AND moderation_status='PENDING_MODERATION'",
            Long.class,
            author)
        >= 20)
      throw new io.plottwist.domain.story.application.StoryException(
          io.plottwist.domain.story.application.StoryException.Reason.TOO_MANY_PENDING,
          "Too many stories awaiting moderation; retry later");
    var assets = checkAttachments(author, content.body());
    jdbc.update(
        "INSERT INTO stories(id,author_id,title,body) VALUES (?,?,?,?)",
        id,
        author,
        content.title(),
        content.body());
    bind(id, 0, assets);
  }

  @Override
  public boolean update(UUID id, UUID author, long version, StoryContent content) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))", author.toString());
    if (jdbc.queryForObject(
            "SELECT count(*) FROM stories WHERE author_id=? AND id<>? AND moderation_status='PENDING_MODERATION'",
            Long.class,
            author,
            id)
        >= 20)
      throw new io.plottwist.domain.story.application.StoryException(
          io.plottwist.domain.story.application.StoryException.Reason.TOO_MANY_PENDING,
          "Too many stories awaiting moderation; retry later");

    var assets = checkAttachments(author, content.body());
    boolean updated =
        jdbc.update(
                "UPDATE stories SET title=?,body=?,version=version+1,updated_at=now() WHERE id=? AND author_id=? AND version=?",
                content.title(),
                content.body(),
                id,
                author,
                version)
            == 1;
    if (updated) bind(id, version + 1, assets);
    return updated;
  }

  @Override
  public boolean delete(UUID id, UUID author, long version) {
    return jdbc.update(
            "DELETE FROM stories WHERE id=? AND author_id=? AND version=?", id, author, version)
        == 1;
  }

  @Override
  public boolean lockForInteraction(UUID id) {
    return !jdbc.queryForList(
            "SELECT id FROM stories WHERE id=? AND published_version IS NOT NULL FOR KEY SHARE",
            UUID.class,
            id)
        .isEmpty();
  }

  @Override
  public void react(UUID story, UUID account, int value) {
    if (value == 0)
      jdbc.update("DELETE FROM story_reactions WHERE story_id=? AND account_id=?", story, account);
    else
      jdbc.update(
          "INSERT INTO story_reactions(story_id,account_id,value) VALUES (?,?,?) ON CONFLICT (story_id,account_id) DO UPDATE SET value=EXCLUDED.value",
          story,
          account,
          value);
  }

  @Override
  public List<StoryComment> comments(UUID story, PagePosition before, int limit) {
    String sql =
        "SELECT c.*,COALESCE(p.display_name,'Читатель') AS author_name FROM story_comments c LEFT JOIN user_profiles p ON p.account_id=c.author_id WHERE c.story_id=?";
    var args = new ArrayList<Object>();
    args.add(story);
    if (before != null) {
      sql += " AND (c.created_at,c.id) < (?,?)";
      args.add(OffsetDateTime.ofInstant(before.createdAt(), java.time.ZoneOffset.UTC));
      args.add(before.id());
    }
    sql += " ORDER BY c.created_at DESC,c.id DESC LIMIT ?";
    args.add(limit);
    return jdbc.query(sql, this::comment, args.toArray());
  }

  @Override
  public StoryComment comment(UUID id, UUID story, UUID author, String body) {
    jdbc.update(
        "INSERT INTO story_comments(id,story_id,author_id,body) VALUES (?,?,?,?)",
        id,
        story,
        author,
        body);
    return jdbc.query(
            "SELECT c.*,COALESCE(p.display_name,'Читатель') AS author_name FROM story_comments c LEFT JOIN user_profiles p ON p.account_id=c.author_id WHERE c.id=?",
            this::comment,
            id)
        .getFirst();
  }

  @Override
  public Optional<StoryComment> findComment(UUID story, UUID comment) {
    return jdbc
        .query(
            "SELECT c.*,COALESCE(p.display_name,'Читатель') AS author_name FROM story_comments c LEFT JOIN user_profiles p ON p.account_id=c.author_id WHERE c.story_id=? AND c.id=?",
            this::comment,
            story,
            comment)
        .stream()
        .findFirst();
  }

  @Override
  public boolean updateComment(UUID story, UUID comment, UUID author, long version, String body) {
    return jdbc.update(
            "UPDATE story_comments SET body=?,version=version+1,updated_at=now() WHERE story_id=? AND id=? AND author_id=? AND version=?",
            body,
            story,
            comment,
            author,
            version)
        == 1;
  }

  @Override
  public Optional<AuthorPhoto> authorPhoto(UUID story) {
    return jdbc
        .query(
            "SELECT m.object_key,m.sha256 FROM stories s JOIN user_profiles p ON p.account_id=s.author_id JOIN media_objects m ON m.id=p.photo_id AND m.state='ACTIVE' WHERE s.id=?",
            (row, n) -> new AuthorPhoto(row.getString("object_key"), row.getString("sha256")),
            story)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<UUID> commentAuthor(UUID story, UUID comment) {
    return jdbc
        .queryForList(
            "SELECT author_id FROM story_comments WHERE story_id=? AND id=?",
            UUID.class,
            story,
            comment)
        .stream()
        .findFirst();
  }

  @Override
  public boolean deleteComment(UUID story, UUID comment, UUID author) {
    return jdbc.update(
            "DELETE FROM story_comments WHERE story_id=? AND id=? AND author_id=?",
            story,
            comment,
            author)
        == 1;
  }

  private java.util.List<io.plottwist.domain.attachment.model.Attachment> attachments(
      String source) {
    try {
      return json.readValue(
          source,
          new com.fasterxml.jackson.core.type.TypeReference<
              java.util.List<io.plottwist.domain.attachment.model.Attachment>>() {});
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("Invalid attachment projection", invalid);
    }
  }

  private List<UUID> checkAttachments(UUID owner, String body) {
    var ids = io.plottwist.moderation.contract.MediaMarkup.ids(body);
    if (ids.isEmpty()) return ids;
    var args = new ArrayList<Object>();
    args.add(owner);
    args.addAll(ids);
    var ready =
        jdbc.queryForList(
            "SELECT id FROM story_attachments WHERE owner_id=? AND state='READY' AND id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ") FOR SHARE",
            UUID.class,
            args.toArray());
    if (ready.size() != ids.size())
      throw new io.plottwist.domain.story.application.StoryException(
          io.plottwist.domain.story.application.StoryException.Reason.INVALID_INPUT,
          "An attachment is unavailable or belongs to another account");
    return ids;
  }

  private void bind(UUID story, long revision, List<UUID> assets) {
    for (int i = 0; i < assets.size(); i++)
      jdbc.update(
          "INSERT INTO story_attachment_refs(story_id,revision,attachment_id,position) VALUES(?,?,?,?)",
          story,
          revision,
          assets.get(i),
          i);
  }

  private Story story(ResultSet r, int n) throws SQLException {
    return new Story(
        r.getObject("id", UUID.class),
        r.getObject("author_id", UUID.class),
        r.getString("author_name"),
        r.getString("author_photo_sha256"),
        r.getString("title"),
        r.getString("body"),
        r.getLong("version"),
        r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("updated_at").toInstant(),
        r.getLong("likes"),
        r.getLong("dislikes"),
        r.getInt("my_reaction"),
        r.getLong("comment_count"),
        r.getString("moderation_status"),
        r.getString("moderation_reason"),
        r.getObject("published_version", Long.class),
        attachments(r.getString("attachments")));
  }

  private StoryComment comment(ResultSet r, int n) throws SQLException {
    return new StoryComment(
        r.getObject("id", UUID.class),
        r.getObject("story_id", UUID.class),
        r.getObject("author_id", UUID.class),
        r.getString("author_name"),
        r.getString("body"),
        r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("updated_at").toInstant(),
        r.getLong("version"));
  }
}
