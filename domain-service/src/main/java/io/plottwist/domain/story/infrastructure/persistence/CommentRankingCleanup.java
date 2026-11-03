package io.plottwist.domain.story.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CommentRankingCleanup {
  private final JdbcTemplate jdbc;

  public CommentRankingCleanup(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void clean() {
    jdbc.update(
        "DELETE FROM comment_rank_batches WHERE id IN(SELECT id FROM comment_rank_batches WHERE expires_at<now() LIMIT 1000)");
  }
}
