package io.plottwist.domain.chat.infrastructure.persistence;

import io.plottwist.domain.chat.application.port.ChatRepository;
import io.plottwist.domain.chat.model.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcChatRepository implements ChatRepository {
  private final JdbcTemplate jdbc;

  public JdbcChatRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String SELECT =
      """
      WITH viewer AS (SELECT ?::uuid AS id)
      SELECT c.id,c.updated_at,c.last_sequence,
        CASE WHEN c.user_low=v.id THEN c.user_high ELSE c.user_low END AS peer_id,
        COALESCE(p.display_name,'Читатель') AS display_name,m.sha256,
        CASE WHEN c.user_low=v.id THEN c.low_read_sequence ELSE c.high_read_sequence END AS read_through,
        CASE WHEN c.user_low=v.id THEN c.high_read_sequence ELSE c.low_read_sequence END AS peer_read_through,
        (SELECT count(*) FROM direct_messages d WHERE d.chat_id=c.id AND d.sender_id=CASE WHEN c.user_low=v.id THEN c.user_high ELSE c.user_low END
          AND d.sequence>CASE WHEN c.user_low=v.id THEN c.low_read_sequence ELSE c.high_read_sequence END) AS unread_count,
        lm.sequence AS preview_sequence,lm.sender_id AS preview_sender,LEFT(lm.body,160) AS preview_body,lm.created_at AS preview_at
      FROM direct_chats c CROSS JOIN viewer v
      LEFT JOIN user_profiles p ON p.account_id=CASE WHEN c.user_low=v.id THEN c.user_high ELSE c.user_low END
      LEFT JOIN media_objects m ON m.id=p.photo_id AND m.state='ACTIVE'
      LEFT JOIN direct_messages lm ON lm.chat_id=c.id AND lm.sequence=c.last_sequence
      WHERE (c.user_low=v.id OR c.user_high=v.id)
      """;

  public void lockPair(UUID low, UUID high) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,49))",
        r -> {},
        "chat:" + low + ":" + high);
  }

  public Optional<UUID> pair(UUID low, UUID high) {
    return jdbc
        .query(
            "SELECT id FROM direct_chats WHERE user_low=? AND user_high=?",
            (r, n) -> r.getObject(1, UUID.class),
            low,
            high)
        .stream()
        .findFirst();
  }

  public void create(UUID id, UUID low, UUID high, UUID actor) {
    jdbc.update(
        "INSERT INTO direct_chats(id,user_low,user_high,low_visible,high_visible) VALUES(?,?,?,?,?)",
        id,
        low,
        high,
        actor.equals(low),
        actor.equals(high));
  }

  public void show(UUID id, UUID actor) {
    jdbc.update(
        "UPDATE direct_chats SET low_visible=low_visible OR user_low=?,high_visible=high_visible OR user_high=? WHERE id=? AND (user_low=? OR user_high=?)",
        actor,
        actor,
        id,
        actor,
        actor);
  }

  public Optional<ChatSummary> summary(UUID id, UUID actor) {
    return jdbc.query(SELECT + " AND c.id=?", this::summaryRow, actor, id).stream().findFirst();
  }

  public List<ChatSummary> inbox(UUID actor, ChatPosition before, int limit) {
    String sql =
        SELECT
            + " AND ((c.user_low=v.id AND c.low_visible) OR (c.user_high=v.id AND c.high_visible))";
    var args = new ArrayList<Object>();
    args.add(actor);
    if (before != null) {
      sql += " AND (c.updated_at,c.id)<(?,?)";
      args.add(Timestamp.from(before.updatedAt()));
      args.add(before.id());
    }
    args.add(limit);
    return jdbc.query(
        sql + " ORDER BY c.updated_at DESC,c.id DESC LIMIT ?", this::summaryRow, args.toArray());
  }

  public Optional<LockedChat> lock(UUID id, UUID actor) {
    return jdbc
        .query(
            "SELECT id,last_sequence FROM direct_chats WHERE id=? AND (user_low=? OR user_high=?) FOR UPDATE",
            (r, n) -> new LockedChat(r.getObject(1, UUID.class), r.getLong(2)),
            id,
            actor,
            actor)
        .stream()
        .findFirst();
  }

  public Optional<ChatMessage> existing(UUID chat, UUID actor, UUID clientId) {
    return jdbc
        .query(
            "SELECT * FROM direct_messages WHERE chat_id=? AND sender_id=? AND client_message_id=?",
            this::messageRow,
            chat,
            actor,
            clientId)
        .stream()
        .findFirst();
  }

  public ChatMessage insert(UUID chat, long sequence, UUID actor, UUID clientId, String body) {
    return jdbc.queryForObject(
        "INSERT INTO direct_messages(chat_id,sequence,sender_id,client_message_id,body) VALUES(?,?,?,?,?) RETURNING *",
        this::messageRow,
        chat,
        sequence,
        actor,
        clientId,
        body);
  }

  public void advance(UUID chat, long sequence, Instant createdAt) {
    jdbc.update(
        "UPDATE direct_chats SET last_sequence=?,updated_at=GREATEST(updated_at,?),low_visible=true,high_visible=true WHERE id=?",
        sequence,
        Timestamp.from(createdAt),
        chat);
  }

  public List<ChatMessage> messages(UUID chat, Long before, Long after, int limit) {
    if (after != null)
      return jdbc.query(
          "SELECT * FROM direct_messages WHERE chat_id=? AND sequence>? ORDER BY sequence ASC LIMIT ?",
          this::messageRow,
          chat,
          after,
          limit);
    if (before != null)
      return jdbc.query(
          "SELECT * FROM direct_messages WHERE chat_id=? AND sequence<? ORDER BY sequence DESC LIMIT ?",
          this::messageRow,
          chat,
          before,
          limit);
    return jdbc.query(
        "SELECT * FROM direct_messages WHERE chat_id=? ORDER BY sequence DESC LIMIT ?",
        this::messageRow,
        chat,
        limit);
  }

  public void read(UUID chat, UUID actor, long through) {
    jdbc.update(
        "UPDATE direct_chats SET low_read_sequence=CASE WHEN user_low=? THEN GREATEST(low_read_sequence,?) ELSE low_read_sequence END,high_read_sequence=CASE WHEN user_high=? THEN GREATEST(high_read_sequence,?) ELSE high_read_sequence END WHERE id=? AND (user_low=? OR user_high=?)",
        actor,
        through,
        actor,
        through,
        chat,
        actor,
        actor);
  }

  private ChatMessage messageRow(ResultSet r, int n) throws SQLException {
    return new ChatMessage(
        r.getLong("sequence"),
        r.getObject("sender_id", UUID.class),
        r.getObject("client_message_id", UUID.class),
        r.getString("body"),
        r.getTimestamp("created_at").toInstant());
  }

  private ChatSummary summaryRow(ResultSet r, int n) throws SQLException {
    return new ChatSummary(
        r.getObject("id", UUID.class),
        new ChatPeer(
            r.getObject("peer_id", UUID.class), r.getString("display_name"), r.getString("sha256")),
        r.getTimestamp("updated_at").toInstant(),
        r.getLong("last_sequence"),
        r.getLong("read_through"),
        r.getLong("peer_read_through"),
        r.getLong("unread_count"),
        r.getObject("preview_sequence") == null
            ? null
            : new MessagePreview(
                r.getLong("preview_sequence"),
                r.getObject("preview_sender", UUID.class),
                r.getString("preview_body"),
                r.getTimestamp("preview_at").toInstant()));
  }
}
