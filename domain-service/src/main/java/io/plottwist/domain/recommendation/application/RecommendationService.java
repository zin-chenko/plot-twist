package io.plottwist.domain.recommendation.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.plottwist.domain.recommendation.application.port.*;
import io.plottwist.domain.recommendation.model.*;
import io.plottwist.domain.story.application.StoryException;
import io.plottwist.domain.story.application.port.StoryRepository;
import io.plottwist.domain.story.model.Story;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
  private final RecommendationStore store;
  private final StoryRepository stories;
  private final EmbeddingGateway embedding;
  private final VectorIndex index;
  private final MeterRegistry metrics;
  private final boolean enabled;
  private volatile long vectorUnavailableUntil;
  private final Semaphore computing = new Semaphore(4);

  public RecommendationService(
      RecommendationStore store,
      StoryRepository stories,
      EmbeddingGateway embedding,
      VectorIndex index,
      MeterRegistry metrics,
      @Value("${recommendations.enabled:true}") boolean enabled) {
    this.enabled = enabled;
    this.store = store;
    this.stories = stories;
    this.embedding = embedding;
    this.index = index;
    this.metrics = metrics;
  }

  public RecommendationPage feed(UUID viewer, String cursor, int limit) {
    return feed(viewer, cursor, limit, false);
  }

  public RecommendationPage feed(UUID viewer, String cursor, int limit, boolean refresh) {
    if (limit < 1 || limit > 20) throw invalid("Page size must be between 1 and 20");
    RecommendationBatch batch;
    int after = 0;
    if (cursor != null) {
      try {
        if (cursor.length() > 100) throw new IllegalArgumentException();
        var parts =
            new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                .split(":", -1);
        if (parts.length != 2) throw new IllegalArgumentException();
        after = Integer.parseInt(parts[1]);
        if (after < 0 || after > 150) throw new IllegalArgumentException();
        batch =
            store
                .batch(viewer, UUID.fromString(parts[0]))
                .orElseThrow(
                    () -> invalid("Recommendation cursor expired or belongs to another account"));
      } catch (IllegalArgumentException error) {
        throw invalid("Invalid recommendation cursor");
      }
    } else batch = create(viewer, refresh);
    var positions = store.page(viewer, batch.id(), after, limit + 1);
    var selected = positions.subList(0, Math.min(limit, positions.size()));
    var loaded = new HashMap<UUID, Story>();
    stories
        .findMany(selected.stream().map(BatchPosition::storyId).toList(), viewer)
        .forEach(story -> loaded.put(story.id(), story));
    var items =
        selected.stream()
            .map(position -> loaded.get(position.storyId()))
            .filter(Objects::nonNull)
            .toList();
    String next =
        positions.size() > limit
            ? Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (batch.id() + ":" + selected.getLast().position())
                        .getBytes(StandardCharsets.UTF_8))
            : null;
    return new RecommendationPage(items, next, batch.mode());
  }

  private RecommendationBatch create(UUID viewer, boolean refresh) {
    EmbeddingModel model = null;
    try {
      if (enabled) model = embedding.model();
    } catch (RecommendationUnavailable offline) {
    }
    String key = model == null ? "unavailable" : model.key();
    long revision = store.revision(viewer);
    var cached = store.recent(viewer, revision, key);
    if (!refresh && cached.isPresent()) return cached.get();
    var scores = new HashMap<UUID, Double>();
    var candidates = new LinkedHashMap<UUID, RecommendationCandidate>();
    String mode = model == null ? "fallback" : "cold_start";
    if (model != null && System.nanoTime() > vectorUnavailableUntil && computing.tryAcquire()) {
      try {
        var seeds = store.seeds(viewer, model);
        if (!seeds.positive().isEmpty()) {
          var hits = index.recommend(model, viewer, seeds, 120);
          var versions = new HashMap<UUID, Long>();
          hits.forEach(
              hit -> {
                scores.put(hit.storyId(), hit.score());
                versions.put(hit.storyId(), hit.version());
              });
          for (var c : store.candidates(viewer, versions.keySet(), 120))
            if (Objects.equals(versions.get(c.id()), c.version())) candidates.put(c.id(), c);
          mode = "personalized";
          vectorUnavailableUntil = 0;
        }
      } catch (RecommendationUnavailable unavailable) {
        mode = "fallback";
        vectorUnavailableUntil = System.nanoTime() + 5_000_000_000L;
        scores.clear();
        candidates.clear();
      } finally {
        computing.release();
      }
    } else if (model != null) mode = "fallback";
    for (var c : store.candidates(viewer, null, 60)) candidates.putIfAbsent(c.id(), c);
    metrics.counter("recommendation.feed.created", "mode", mode).increment();
    return store.save(
        viewer,
        revision,
        key,
        mode,
        RecommendationRanker.rank(new ArrayList<>(candidates.values()), scores, Instant.now()));
  }

  public void reading(UUID viewer, UUID story, boolean completed, int seconds) {
    if (seconds < 0 || seconds > 600 || completed && seconds < 20)
      throw invalid("Invalid reading duration");
    if (!store.recordReading(viewer, story, completed))
      throw new StoryException(StoryException.Reason.NOT_FOUND, "Story not found");
  }

  private static StoryException invalid(String detail) {
    return new StoryException(StoryException.Reason.INVALID_INPUT, detail);
  }
}
