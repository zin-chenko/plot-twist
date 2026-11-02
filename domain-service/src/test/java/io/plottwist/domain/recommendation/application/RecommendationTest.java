package io.plottwist.domain.recommendation.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.plottwist.domain.recommendation.application.port.*;
import io.plottwist.domain.recommendation.model.*;
import io.plottwist.domain.story.application.StoryException;
import io.plottwist.domain.story.application.port.StoryRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecommendationTest {
  @Test
  void semanticRelevanceOutranksUnrelatedPopularityAndAuthorsAreDiversified() {
    var now = Instant.parse("2026-11-01T00:00:00Z");
    var author = UUID.randomUUID();
    var relevant =
        new RecommendationCandidate(UUID.randomUUID(), author, 0, now.minusSeconds(86400), 0, 0);
    var unrelated =
        new RecommendationCandidate(UUID.randomUUID(), UUID.randomUUID(), 0, now, 10000, 0);
    var ranked =
        RecommendationRanker.rank(
            List.of(unrelated, relevant), Map.of(relevant.id(), .95, unrelated.id(), .05), now);
    assertEquals(relevant.id(), ranked.getFirst().id());
    var candidates = new ArrayList<RecommendationCandidate>();
    var scores = new HashMap<UUID, Double>();
    for (int i = 0; i < 6; i++) {
      var c =
          new RecommendationCandidate(
              UUID.randomUUID(), i < 4 ? author : UUID.randomUUID(), 0, now, 0, 0);
      candidates.add(c);
      scores.put(c.id(), i < 4 ? .95 : .9);
    }
    var diverse = RecommendationRanker.rank(candidates, scores, now);
    assertTrue(
        diverse.subList(0, 3).stream().map(RecommendationCandidate::author).distinct().count() > 1);
  }

  @Test
  void unavailableModelReturnsExplicitFallbackAndCursorsCannotCrossAccounts() {
    var store = mock(RecommendationStore.class);
    var stories = mock(StoryRepository.class);
    var embedding = mock(EmbeddingGateway.class);
    var index = mock(VectorIndex.class);
    var viewer = UUID.randomUUID();
    var batch = new RecommendationBatch(UUID.randomUUID(), "unavailable", "fallback");
    when(embedding.model()).thenThrow(new RecommendationUnavailable("offline"));
    when(store.recent(viewer, 0, "unavailable")).thenReturn(Optional.empty());
    when(store.candidates(eq(viewer), isNull(), anyInt())).thenReturn(List.of());
    when(store.save(eq(viewer), eq(0L), eq("unavailable"), eq("fallback"), anyList()))
        .thenReturn(batch);
    when(store.page(eq(viewer), eq(batch.id()), eq(0), anyInt())).thenReturn(List.of());
    var service =
        new RecommendationService(
            store, stories, embedding, index, new SimpleMeterRegistry(), true);
    assertEquals("fallback", service.feed(viewer, null, 10).mode());
    verifyNoInteractions(index);
    String cursor =
        Base64.getUrlEncoder()
            .encodeToString((batch.id() + ":1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID stranger = UUID.randomUUID();
    when(store.batch(stranger, batch.id())).thenReturn(Optional.empty());
    assertEquals(
        StoryException.Reason.INVALID_INPUT,
        assertThrows(StoryException.class, () -> service.feed(stranger, cursor, 10)).reason());
    assertThrows(StoryException.class, () -> service.feed(viewer, "not-cursor", 10));
    assertThrows(StoryException.class, () -> service.feed(viewer, null, 21));
  }
}
