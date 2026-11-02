package io.plottwist.moderation.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MediaMarkupTest {
  @Test
  void extractsOnlyTypedOwnedAssetReferencesAndBoundsTheCount() {
    var id = UUID.randomUUID();
    var marker = "![подпись](attachment:" + id + ")";
    assertEquals(List.of(id), MediaMarkup.ids("До\n" + marker + "\nПосле"));
    assertEquals("До\nподпись\nПосле", MediaMarkup.textForEmbedding("До\n" + marker + "\nПосле"));
    assertTrue(MediaMarkup.ids("<img src=x> ![external](https://example.test/a.gif)").isEmpty());
    assertTrue(MediaMarkup.ids("attachment:" + id + ")").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> MediaMarkup.ids(marker.repeat(9)));
  }
}
