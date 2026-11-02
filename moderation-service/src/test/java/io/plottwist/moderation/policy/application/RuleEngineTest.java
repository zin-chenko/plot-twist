package io.plottwist.moderation.policy.application;

import static org.junit.jupiter.api.Assertions.*;

import io.plottwist.moderation.contract.*;
import io.plottwist.moderation.contract.ModerationDecision.Outcome;
import java.util.*;
import org.junit.jupiter.api.Test;

class RuleEngineTest {
  private RuleEngine engine() throws Exception {
    try (var input = getClass().getResourceAsStream("/policy/basic-v1.tsv")) {
      return new RuleEngine(input.readAllBytes());
    }
  }

  private ModerationTask task(String body) {
    return new ModerationTask(
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        ContentHash.of("История", body),
        "История",
        body,
        BrokerNames.POLICY);
  }

  @Test
  void matchesWordsWithoutSubstringFalsePositives() throws Exception {
    assertEquals(
        Outcome.APPROVE,
        engine()
            .evaluate(task("Страхуй напарника. Рубля хватило на педикюр и учебник по истории."))
            .outcome());
    assertEquals(Outcome.BLOCK, engine().evaluate(task("Вот это блять поворот")).outcome());
  }

  @Test
  void detectsCaseUnicodeMaskingAndMixedAlphabet() throws Exception {
    for (String text : List.of("БЛЯЯЯДЬ", "бл​ядь", "х*у*й", "нaхyй", "бл*ть"))
      assertEquals(Outcome.BLOCK, engine().evaluate(task(text)).outcome(), text);
  }

  @Test
  void ambiguousWordsRequireReviewAndDoNotBanAnAccount() throws Exception {
    var result = engine().evaluate(task("У заводчика сука принесла щенков."));
    assertEquals(Outcome.REVIEW, result.outcome());
    assertEquals(List.of("ru-suka"), result.ruleIds());
  }

  @Test
  void blocksTakePriorityAndDoNotReturnRawText() throws Exception {
    var result = engine().evaluate(task("сука блять"));
    assertEquals(Outcome.BLOCK, result.outcome());
    assertTrue(result.ruleIds().stream().allMatch(s -> s.startsWith("ru-")));
    assertEquals(64, result.rulesHash().length());
  }
}
