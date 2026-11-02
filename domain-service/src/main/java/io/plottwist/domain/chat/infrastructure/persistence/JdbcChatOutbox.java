package io.plottwist.domain.chat.infrastructure.persistence;

import io.plottwist.domain.chat.application.port.ChatEvents;
import io.plottwist.domain.chat.model.ChatChange;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class JdbcChatOutbox implements ChatEvents {
  private final JdbcTemplate jdbc;

  public JdbcChatOutbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void changed(UUID chat) {
    jdbc.update(
        """
      INSERT INTO chat_event_outbox(id,chat_id,recipient_low,recipient_high)
      SELECT ?,id,CASE WHEN low_visible THEN user_low END,CASE WHEN high_visible THEN user_high END
      FROM direct_chats WHERE id=?
      """,
        UUID.randomUUID(),
        chat);
  }

  public record Delivery(ChatChange change, UUID lease) {}

  public Optional<Delivery> claim() {
    return jdbc
        .query(
            """
      WITH candidate AS (SELECT id FROM chat_event_outbox WHERE available_at<=now()
        AND (lease_until IS NULL OR lease_until<now()) ORDER BY available_at,created_at
        LIMIT 1 FOR UPDATE SKIP LOCKED)
      UPDATE chat_event_outbox o SET lease_token=gen_random_uuid(),lease_until=now()+interval '30 seconds',attempts=attempts+1
      FROM candidate c WHERE o.id=c.id RETURNING o.*
      """,
            (r, n) -> {
              var recipients = new ArrayList<UUID>();
              UUID low = r.getObject("recipient_low", UUID.class),
                  high = r.getObject("recipient_high", UUID.class);
              if (low != null) recipients.add(low);
              if (high != null) recipients.add(high);
              return new Delivery(
                  new ChatChange(
                      r.getObject("id", UUID.class),
                      r.getObject("chat_id", UUID.class),
                      List.copyOf(recipients)),
                  r.getObject("lease_token", UUID.class));
            })
        .stream()
        .findFirst();
  }

  public void sent(Delivery d) {
    jdbc.update(
        "DELETE FROM chat_event_outbox WHERE id=? AND lease_token=?",
        d.change().eventId(),
        d.lease());
  }

  public void failed(Delivery d) {
    jdbc.update(
        "UPDATE chat_event_outbox SET lease_token=NULL,lease_until=NULL,available_at=now()+LEAST(30,attempts*2)*interval '1 second' WHERE id=? AND lease_token=?",
        d.change().eventId(),
        d.lease());
  }

  public long pending() {
    return jdbc.queryForObject("SELECT count(*) FROM chat_event_outbox", Long.class);
  }
}
