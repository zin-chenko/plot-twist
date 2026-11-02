package io.plottwist.domain.moderation.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.plottwist.moderation.contract.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DecisionStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public DecisionStore(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional
  public boolean apply(ModerationDecision d) {
    // Lock story before outbox, matching edits/deletion. No broker call in this transaction.
    var rows =
        jdbc.queryForList(
            "SELECT moderation_job_id FROM stories WHERE id=? AND version=? AND moderation_hash=? AND moderation_status='PENDING_MODERATION' FOR UPDATE",
            UUID.class,
            d.storyId(),
            d.revision(),
            d.contentHash());
    if (rows.isEmpty()
        || !d.jobId().equals(rows.getFirst())
        || !BrokerNames.POLICY.equals(d.policyVersion())) return false;
    String rules;
    try {
      rules = json.writeValueAsString(d.ruleIds());
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(impossible);
    }
    jdbc.update(
        "INSERT INTO moderation_decisions(job_id,story_id,revision,content_hash,policy_version,rules_hash,outcome,rule_ids) VALUES(?,?,?,?,?,?,?,?::jsonb)",
        d.jobId(),
        d.storyId(),
        d.revision(),
        d.contentHash(),
        d.policyVersion(),
        d.rulesHash(),
        d.outcome().name(),
        rules);
    String status =
        switch (d.outcome()) {
          case APPROVE -> "PUBLISHED";
          case BLOCK -> "REJECTED";
          case REVIEW -> "REVIEW_REQUIRED";
        };
    String reason =
        switch (d.outcome()) {
          case APPROVE -> null;
          case BLOCK -> "Текст содержит запрещённые выражения. Измените его и отправьте снова.";
          case REVIEW -> "Нужна ручная проверка неоднозначных выражений.";
        };
    if (d.outcome() == ModerationDecision.Outcome.APPROVE)
      jdbc.update(
          "UPDATE stories SET moderation_status=?,moderation_reason=NULL,published_title=title,published_body=body,published_version=version,published_updated_at=now() WHERE id=?",
          status,
          d.storyId());
    else
      jdbc.update(
          "UPDATE stories SET moderation_status=?,moderation_reason=? WHERE id=?",
          status,
          reason,
          d.storyId());
    jdbc.update("DELETE FROM moderation_outbox WHERE job_id=?", d.jobId());
    return true;
  }
}
