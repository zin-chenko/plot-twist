package io.plottwist.domain.recommendation.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.plottwist.domain.recommendation.application.RecommendationUnavailable;
import io.plottwist.domain.recommendation.application.port.VectorIndex;
import io.plottwist.domain.recommendation.model.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class QdrantIndex implements VectorIndex {
  private final RestClient client;

  public QdrantIndex(
      RestClient.Builder builder,
      @Value("${recommendations.vector-url:http://localhost:6333}") String url,
      @Value("${recommendations.vector-api-key:}") String key) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
    factory.setReadTimeout(Duration.ofSeconds(3));
    var configured = builder.baseUrl(url).requestFactory(factory);
    if (!key.isBlank()) configured.defaultHeader("api-key", key);
    client = configured.build();
  }

  private String path(EmbeddingModel model) {
    return "/collections/" + model.collection();
  }

  private JsonNode post(String path, Object body) {
    try {
      return client.post().uri(path).body(body).retrieve().body(JsonNode.class);
    } catch (RuntimeException error) {
      throw new RecommendationUnavailable("Vector index unavailable");
    }
  }

  public boolean ensure(EmbeddingModel model) {
    boolean created = false;
    JsonNode result;
    try {
      try {
        result = client.get().uri(path(model)).retrieve().body(JsonNode.class);
      } catch (RestClientResponseException error) {
        if (error.getStatusCode().value() != 404) throw error;
        try {
          client
              .put()
              .uri(path(model))
              .body(
                  Map.of(
                      "vectors",
                      Map.of("size", model.dimensions(), "distance", "Cosine", "on_disk", true),
                      "hnsw_config",
                      Map.of("on_disk", true)))
              .retrieve()
              .toBodilessEntity();
          created = true;
        } catch (RestClientResponseException race) {
          if (race.getStatusCode().value() != 409) throw race;
        }
        result = client.get().uri(path(model)).retrieve().body(JsonNode.class);
      }
      if (result == null
          || result
                  .path("result")
                  .path("config")
                  .path("params")
                  .path("vectors")
                  .path("size")
                  .asInt()
              != model.dimensions()
          || !"Cosine"
              .equals(
                  result
                      .path("result")
                      .path("config")
                      .path("params")
                      .path("vectors")
                      .path("distance")
                      .asText())) throw new IllegalStateException();
      for (String field : List.of("story_id", "author_id"))
        client
            .put()
            .uri(path(model) + "/index?wait=true")
            .body(Map.of("field_name", field, "field_schema", "keyword"))
            .retrieve()
            .toBodilessEntity();
      return created;
    } catch (RuntimeException error) {
      throw new RecommendationUnavailable("Vector collection unavailable or incompatible");
    }
  }

  public void upsert(EmbeddingModel model, IndexDocument document, UUID point, float[] vector) {
    try {
      client
          .put()
          .uri(path(model) + "/points?wait=true")
          .body(
              Map.of(
                  "points",
                  List.of(
                      Map.of(
                          "id",
                          point,
                          "vector",
                          vector,
                          "payload",
                          Map.of(
                              "story_id",
                              document.id(),
                              "author_id",
                              document.author(),
                              "version",
                              document.version())))))
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException error) {
      throw new RecommendationUnavailable("Vector write failed");
    }
  }

  public void deleteEveryModelStory(UUID story) {
    try {
      var result = client.get().uri("/collections").retrieve().body(JsonNode.class);
      if (result == null) throw new IllegalStateException();
      for (var collection : result.path("result").path("collections")) {
        String name = collection.path("name").asText();
        if (name.matches("stories_e5_[0-9a-f]{24}"))
          post(
              "/collections/" + name + "/points/delete?wait=true",
              Map.of(
                  "filter",
                  Map.of(
                      "must",
                      List.of(Map.of("key", "story_id", "match", Map.of("value", story))))));
      }
    } catch (RuntimeException error) {
      throw new io.plottwist.domain.recommendation.application.RecommendationUnavailable(
          "Vector deletion unavailable");
    }
  }

  public void deleteStory(EmbeddingModel model, UUID story) {
    post(
        path(model) + "/points/delete?wait=true",
        Map.of(
            "filter",
            Map.of("must", List.of(Map.of("key", "story_id", "match", Map.of("value", story))))));
  }

  public List<VectorHit> recommend(EmbeddingModel model, UUID viewer, Seeds seeds, int limit) {
    var excluded = new ArrayList<UUID>();
    excluded.addAll(seeds.positive());
    excluded.addAll(seeds.negative());
    var result =
        post(
            path(model) + "/points/query",
            Map.of(
                "query",
                Map.of(
                    "recommend",
                    Map.of(
                        "positive",
                        seeds.positive(),
                        "negative",
                        seeds.negative(),
                        "strategy",
                        "average_vector")),
                "filter",
                Map.of(
                    "must_not",
                    List.of(
                        Map.of("key", "author_id", "match", Map.of("value", viewer)),
                        Map.of("has_id", excluded))),
                "limit",
                limit,
                "with_payload",
                true,
                "with_vector",
                false));
    return hits(result.path("result").path("points"));
  }

  public VectorPage scan(EmbeddingModel model, UUID offset, int limit) {
    var body = new LinkedHashMap<String, Object>();
    body.put("limit", limit);
    body.put("with_payload", true);
    body.put("with_vector", false);
    if (offset != null) body.put("offset", offset);
    var result = post(path(model) + "/points/scroll", body).path("result");
    return new VectorPage(
        hits(result.path("points")),
        result.path("next_page_offset").isNull() || result.path("next_page_offset").isMissingNode()
            ? null
            : UUID.fromString(result.path("next_page_offset").asText()));
  }

  public void deletePoints(EmbeddingModel model, List<UUID> points) {
    if (!points.isEmpty()) post(path(model) + "/points/delete?wait=true", Map.of("points", points));
  }

  private List<VectorHit> hits(JsonNode values) {
    var hits = new ArrayList<VectorHit>();
    for (var value : values) {
      var payload = value.path("payload");
      double score = value.path("score").asDouble(0);
      if (!Double.isFinite(score)) continue;
      hits.add(
          new VectorHit(
              UUID.fromString(value.path("id").asText()),
              UUID.fromString(payload.path("story_id").asText()),
              payload.path("version").asLong(-1),
              score));
    }
    return hits;
  }
}
