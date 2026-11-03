package io.plottwist.domain.story.infrastructure.persistence;

import io.plottwist.domain.story.application.port.CommentRepository;
import io.plottwist.domain.story.model.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCommentRepository implements CommentRepository {
  private final JdbcTemplate jdbc;

  public JdbcCommentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String VISIBLE =
      "(c.deleted_at IS NULL OR EXISTS(SELECT 1 FROM story_comments child WHERE child.story_id=c.story_id AND child.root_id=c.id AND child.deleted_at IS NULL) OR c.root_id IS NOT NULL)";
  private static final String SELECT =
      """
    SELECT c.*,COALESCE(p.display_name,'Читатель') AS author_name,
      EXISTS(SELECT 1 FROM comment_likes l WHERE l.comment_id=c.id AND l.account_id=?) AS my_liked,
      CASE WHEN parent.deleted_at IS NOT NULL THEN 'удалённый комментарий' ELSE COALESCE(pp.display_name,'Читатель') END AS reply_to_name,
      (SELECT count(*) FROM story_comments child WHERE child.story_id=c.story_id AND child.deleted_at IS NULL AND
        CASE WHEN c.root_id IS NULL THEN child.root_id=c.id ELSE child.parent_id=c.id END) AS reply_count
    FROM story_comments c LEFT JOIN user_profiles p ON p.account_id=c.author_id
    LEFT JOIN story_comments parent ON parent.id=c.parent_id
    LEFT JOIN user_profiles pp ON pp.account_id=parent.author_id
    """;

  public List<StoryComment> page(
      UUID story, UUID viewer, UUID root, CommentSort sort, PagePosition position, int limit) {
    var args = new ArrayList<Object>();
    args.add(viewer);
    args.add(story);
    String sql =
        SELECT
            + " WHERE c.story_id=? AND "
            + VISIBLE
            + (root == null ? " AND c.parent_id IS NULL" : " AND c.root_id=?");
    if (root != null) args.add(root);
    boolean ascending = sort == CommentSort.OLDEST;
    if (position != null) {
      sql += " AND (c.created_at,c.id)" + (ascending ? ">" : "<") + "(?,?)";
      args.add(Timestamp.from(position.createdAt()));
      args.add(position.id());
    }
    args.add(limit);
    return jdbc.query(
        sql
            + " ORDER BY c.created_at "
            + (ascending ? "ASC" : "DESC")
            + ",c.id "
            + (ascending ? "ASC" : "DESC")
            + " LIMIT ?",
        this::map,
        args.toArray());
  }

  public Optional<StoryComment> find(UUID story, UUID comment, UUID viewer) {
    return jdbc
        .query(
            SELECT + " WHERE c.story_id=? AND c.id=? AND " + VISIBLE,
            this::map,
            viewer,
            story,
            comment)
        .stream()
        .findFirst();
  }

  public boolean lock(UUID story, UUID comment) {
    return !jdbc.queryForList(
            "SELECT id FROM story_comments WHERE story_id=? AND id=? FOR UPDATE",
            UUID.class,
            story,
            comment)
        .isEmpty();
  }

  public void create(UUID id, UUID story, UUID author, String body, UUID parent, UUID root) {
    jdbc.update(
        "INSERT INTO story_comments(id,story_id,author_id,body,parent_id,root_id) VALUES(?,?,?,?,?,?)",
        id,
        story,
        author,
        body,
        parent,
        root);
  }

  public boolean update(UUID story, UUID comment, UUID author, long version, String body) {
    return jdbc.update(
            "UPDATE story_comments SET body=?,version=version+1,updated_at=now() WHERE story_id=? AND id=? AND author_id=? AND version=? AND deleted_at IS NULL",
            body,
            story,
            comment,
            author,
            version)
        == 1;
  }

  public void delete(UUID story, UUID comment) {
    jdbc.update("DELETE FROM comment_likes WHERE comment_id=?", comment);
    if (jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM story_comments WHERE story_id=? AND parent_id=?)",
        Boolean.class,
        story,
        comment))
      jdbc.update(
          "UPDATE story_comments SET body='',deleted_at=now(),updated_at=now(),version=version+1 WHERE story_id=? AND id=?",
          story,
          comment);
    else jdbc.update("DELETE FROM story_comments WHERE story_id=? AND id=?", story, comment);
  }

  public void like(UUID comment, UUID account, boolean liked) {
    if (liked)
      jdbc.update(
          "INSERT INTO comment_likes(comment_id,account_id) VALUES(?,?) ON CONFLICT DO NOTHING",
          comment,
          account);
    else
      jdbc.update(
          "DELETE FROM comment_likes WHERE comment_id=? AND account_id=?", comment, account);
  }

  @Transactional
  public UUID createRanking(UUID story, UUID viewer) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO comment_rank_batches(id,story_id,account_id) VALUES(?,?,?)",
        id,
        story,
        viewer);
    jdbc.update(
        """
      WITH ranked AS (SELECT c.id,c.like_count,c.created_at FROM story_comments c WHERE c.story_id=? AND c.parent_id IS NULL AND
      """
            + VISIBLE
            + """
      ORDER BY c.like_count DESC,c.created_at DESC,c.id DESC LIMIT 500)
      INSERT INTO comment_rank_items(batch_id,position,comment_id)
      SELECT ?,row_number() OVER(ORDER BY like_count DESC,created_at DESC,id DESC),id FROM ranked
      """,
        story,
        id);
    return id;
  }

  public boolean rankingExists(UUID batch, UUID story, UUID viewer) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM comment_rank_batches WHERE id=? AND story_id=? AND account_id=? AND expires_at>now())",
        Boolean.class,
        batch,
        story,
        viewer);
  }

  public List<RankedComment> rankedPage(UUID batch, UUID viewer, int after, int limit) {
    return jdbc.query(
        SELECT.replace("SELECT c.*", "SELECT i.position,c.*")
            + " JOIN comment_rank_items i ON i.comment_id=c.id JOIN comment_rank_batches b ON b.id=i.batch_id WHERE b.id=? AND b.account_id=? AND b.expires_at>now() AND i.position>? AND "
            + VISIBLE
            + " ORDER BY i.position LIMIT ?",
        (r, n) -> new RankedComment(map(r, n), r.getInt("position")),
        viewer,
        batch,
        viewer,
        after,
        limit);
  }

  private StoryComment map(ResultSet r, int n) throws SQLException {
    return new StoryComment(
        r.getObject("id", UUID.class),
        r.getObject("story_id", UUID.class),
        r.getObject("author_id", UUID.class),
        r.getString("author_name"),
        r.getString("body"),
        r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("updated_at").toInstant(),
        r.getLong("version"),
        r.getObject("parent_id", UUID.class),
        r.getObject("root_id", UUID.class),
        r.getObject("parent_id") == null ? null : r.getString("reply_to_name"),
        r.getLong("like_count"),
        r.getBoolean("my_liked"),
        r.getLong("reply_count"),
        r.getTimestamp("deleted_at") != null);
  }
}
