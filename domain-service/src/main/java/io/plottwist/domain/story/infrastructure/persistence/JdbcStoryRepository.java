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
  private static final String SELECT =
      """
      SELECT s.*, COALESCE(p.display_name,'Читатель') AS author_name, m.sha256 AS author_photo_sha256,
      (SELECT count(*) FROM story_reactions r WHERE r.story_id=s.id AND r.value=1) AS likes,
      (SELECT count(*) FROM story_reactions r WHERE r.story_id=s.id AND r.value=-1) AS dislikes,
      COALESCE((SELECT r.value FROM story_reactions r WHERE r.story_id=s.id AND r.account_id=?),0) AS my_reaction,
      (SELECT count(*) FROM story_comments c WHERE c.story_id=s.id) AS comment_count
      FROM stories s LEFT JOIN user_profiles p ON p.account_id=s.author_id
      LEFT JOIN media_objects m ON m.id=p.photo_id AND m.state='ACTIVE'
      """;

  public JdbcStoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Story> feed(UUID viewer, UUID author, boolean liked, PagePosition before, int limit) {
    String sql = SELECT + " WHERE true";
    var args = new ArrayList<Object>();
    args.add(viewer);
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
    args.add(viewer);
    args.addAll(ids);
    return jdbc.query(
        SELECT + " WHERE s.id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")",
        this::story,
        args.toArray());
  }

  @Override
  public Optional<Story> find(UUID id, UUID viewer) {
    return jdbc.query(SELECT + " WHERE s.id=?", this::story, viewer, id).stream().findFirst();
  }

  @Override
  public void create(UUID id, UUID author, StoryContent content) {
    jdbc.update(
        "INSERT INTO stories(id,author_id,title,body) VALUES (?,?,?,?)",
        id,
        author,
        content.title(),
        content.body());
  }

  @Override
  public boolean update(UUID id, UUID author, long version, StoryContent content) {
    return jdbc.update(
            "UPDATE stories SET title=?,body=?,version=version+1,updated_at=now() WHERE id=? AND author_id=? AND version=?",
            content.title(),
            content.body(),
            id,
            author,
            version)
        == 1;
  }

  @Override
  public boolean delete(UUID id, UUID author, long version) {
    return jdbc.update(
            "DELETE FROM stories WHERE id=? AND author_id=? AND version=?", id, author, version)
        == 1;
  }

  @Override
  public boolean lockForInteraction(UUID id) {
    return !jdbc.queryForList("SELECT id FROM stories WHERE id=? FOR KEY SHARE", UUID.class, id)
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
        r.getLong("comment_count"));
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
