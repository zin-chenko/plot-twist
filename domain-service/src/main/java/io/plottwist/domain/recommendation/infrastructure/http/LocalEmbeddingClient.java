package io.plottwist.domain.recommendation.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.plottwist.domain.recommendation.application.RecommendationUnavailable;
import io.plottwist.domain.recommendation.application.port.EmbeddingGateway;
import io.plottwist.domain.recommendation.model.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LocalEmbeddingClient implements EmbeddingGateway {
  private final RestClient client;
  private final RestClient metadata;
  private volatile EmbeddingModel cached;
  private volatile long validUntil;

  public LocalEmbeddingClient(
      RestClient.Builder builder,
      @Value("${recommendations.embedding-url:http://localhost:8090}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(30));
    client = builder.baseUrl(url).requestFactory(factory).build();
    var fast =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofMillis(250)).build());
    fast.setReadTimeout(Duration.ofMillis(500));
    metadata = builder.baseUrl(url).requestFactory(fast).build();
  }

  public EmbeddingModel model() {
    var current = cached;
    if (System.nanoTime() < validUntil) {
      if (current == null) throw new RecommendationUnavailable("Embedding service unavailable");
      return current;
    }
    synchronized (this) {
      if (System.nanoTime() < validUntil) {
        if (cached == null) throw new RecommendationUnavailable("Embedding service unavailable");
        return cached;
      }
      try {
        var result = metadata.get().uri("/healthz").retrieve().body(JsonNode.class);
        if (result == null || !result.path("ready").asBoolean()) throw new IllegalStateException();
        var model =
            new EmbeddingModel(
                result.path("model_key").asText(), result.path("dimensions").asInt());
        cached = model;
        validUntil = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        return model;
      } catch (RuntimeException error) {
        validUntil = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        if (cached != null) return cached;
        throw new RecommendationUnavailable("Embedding service unavailable");
      }
    }
  }

  public float[] embed(IndexDocument document, EmbeddingModel model) {
    try {
      var result =
          client
              .post()
              .uri("/embed")
              .body(
                  Map.of(
                      "text",
                      document.title()
                          + "\n\n"
                          + io.plottwist.moderation.contract.MediaMarkup.textForEmbedding(
                              document.body()),
                      "model_key",
                      model.key()))
              .retrieve()
              .body(JsonNode.class);
      if (result == null
          || !model.key().equals(result.path("model_key").asText())
          || result.path("vector").size() != model.dimensions()) throw new IllegalStateException();
      float[] vector = new float[model.dimensions()];
      double norm = 0;
      for (int i = 0; i < vector.length; i++) {
        vector[i] = (float) result.path("vector").get(i).asDouble(Double.NaN);
        if (!Float.isFinite(vector[i])) throw new IllegalStateException();
        norm += vector[i] * vector[i];
      }
      if (Math.abs(norm - 1) > 0.02) throw new IllegalStateException();
      return vector;
    } catch (RuntimeException error) {
      throw new RecommendationUnavailable("Embedding inference failed");
    }
  }
}
