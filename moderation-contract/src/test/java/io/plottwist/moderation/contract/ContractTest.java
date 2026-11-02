package io.plottwist.moderation.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ContractTest {
  @Test
  void rejectsTamperingAndUnsupportedSchemas() {
    var id = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModerationTask(
                1, id, id, 0, ContentHash.of("a", "b"), "a", "changed", BrokerNames.POLICY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModerationTask(
                2, id, id, 0, ContentHash.of("a", "b"), "a", "b", BrokerNames.POLICY));
    assertNotEquals(ContentHash.of("ab", "c"), ContentHash.of("a", "bc"));
  }
}
