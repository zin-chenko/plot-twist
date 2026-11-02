package io.plottwist.domain.recommendation.infrastructure.indexing;

import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.plottwist.domain.recommendation.application.port.*;
import io.plottwist.domain.recommendation.model.IndexJob;
import java.util.*;
import org.junit.jupiter.api.Test;

class VectorDeletionWorkerTest {
  @Test
  void deletesAcrossModelVersionsAndExpiresSnapshotsWithoutInference() {
    var jobs = mock(IndexJobs.class);
    var index = mock(VectorIndex.class);
    var store = mock(RecommendationStore.class);
    var job = new IndexJob(UUID.randomUUID(), 3, UUID.randomUUID(), 1);
    when(jobs.claimDeleted()).thenReturn(Optional.of(job), Optional.empty());
    when(jobs.owned(job)).thenReturn(true);
    new VectorDeletionWorker(jobs, index, new SimpleMeterRegistry(), store).process();
    verify(store).cleanup();
    verify(index).deleteEveryModelStory(job.storyId());
    verify(jobs).complete(job, null, null, null);
  }
}
