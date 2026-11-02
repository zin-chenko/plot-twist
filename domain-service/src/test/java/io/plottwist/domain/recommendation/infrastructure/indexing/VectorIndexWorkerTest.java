package io.plottwist.domain.recommendation.infrastructure.indexing;

import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.plottwist.domain.recommendation.application.port.*;
import io.plottwist.domain.recommendation.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class VectorIndexWorkerTest {
  @Test
  void aLostLeaseCannotPublishOrAcknowledgeAnOldRevision() {
    var jobs = mock(IndexJobs.class);
    var embedding = mock(EmbeddingGateway.class);
    var index = mock(VectorIndex.class);
    var model = new EmbeddingModel("a".repeat(64), 384);
    var id = UUID.randomUUID();
    var job = new IndexJob(id, 1, UUID.randomUUID(), 1);
    var doc = new IndexDocument(id, UUID.randomUUID(), 0, "Title", "Body");
    when(embedding.model()).thenReturn(model);
    when(jobs.claim()).thenReturn(Optional.of(job), Optional.empty());
    when(jobs.document(id)).thenReturn(Optional.of(doc));
    when(embedding.embed(doc, model)).thenReturn(new float[384]);
    when(jobs.owned(job)).thenReturn(false);
    when(index.scan(model, null, 128)).thenReturn(new VectorIndex.VectorPage(List.of(), null));
    new VectorIndexWorker(jobs, embedding, index, new SimpleMeterRegistry(), true).run();
    verify(jobs).release(job);
    verify(index, never()).upsert(any(), any(), any(), any());
    verify(jobs, never()).complete(any(), any(), any(), any());
  }

  @Test
  void deletedStoriesAreRemovedAndFailuresRemainRetryable() {
    var jobs = mock(IndexJobs.class);
    var embedding = mock(EmbeddingGateway.class);
    var index = mock(VectorIndex.class);
    var model = new EmbeddingModel("b".repeat(64), 384);
    var job = new IndexJob(UUID.randomUUID(), 2, UUID.randomUUID(), 1);
    when(embedding.model()).thenReturn(model);
    when(jobs.claim()).thenReturn(Optional.of(job), Optional.empty());
    when(jobs.document(job.storyId())).thenReturn(Optional.empty());
    when(jobs.owned(job)).thenReturn(true);
    doThrow(new RuntimeException("unavailable")).when(index).deleteEveryModelStory(job.storyId());
    when(index.scan(model, null, 128)).thenReturn(new VectorIndex.VectorPage(List.of(), null));
    new VectorIndexWorker(jobs, embedding, index, new SimpleMeterRegistry(), true).run();
    verify(jobs).failed(job, "RuntimeException");
    verify(jobs, never()).complete(any(), any(), any(), any());
  }
}
