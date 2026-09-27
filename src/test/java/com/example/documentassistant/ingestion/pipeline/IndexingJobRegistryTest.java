package com.example.documentassistant.ingestion.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingJobRegistryTest {

  @Test
  void tracksProgressAndPublishesSnapshots() {
    IndexingJobRegistry registry = new IndexingJobRegistry();

    IndexingJobStatus created = registry.createJob();

    List<IndexingJobStatus> events = new ArrayList<>();

    Runnable unsubscribe = registry.addListener(
        created.jobId(),
        events::add);

    registry.update(
        created.jobId(),
        IndexingJob::start);

    registry.update(
        created.jobId(),
        job -> job.documentsDiscovered(2));

    registry.update(
        created.jobId(),
        job -> job.startChunking(
            "product_1.json"));

    registry.update(
        created.jobId(),
        job -> job.documentChunked(
            "product_1",
            40));

    IndexingJobStatus status = registry.getStatus(
        created.jobId());

    assertEquals(
        IndexingJobStage.CHUNKING,
        status.stage());

    assertEquals(
        2,
        status.documentsDiscovered());

    assertEquals(
        1,
        status.documentsChunked());

    assertEquals(
        40,
        status.totalChunksGenerated());

    assertEquals(
        "product_1",
        status.currentDocument());

    assertFalse(status.terminal());
    assertEquals(4, events.size());

    assertTrue(
        events.getLast().sequence() > events.getFirst().sequence());

    unsubscribe.run();

    registry.update(
        created.jobId(),
        IndexingJob::startValidation);

    assertEquals(
        4,
        events.size());
  }

  @Test
  void rejectsSecondActiveJob() {
    IndexingJobRegistry registry = new IndexingJobRegistry();

    IndexingJobStatus first = registry.createJob();

    IndexingJobAlreadyRunningException exception = assertThrows(
        IndexingJobAlreadyRunningException.class,
        registry::createJob);

    assertEquals(
        first.jobId(),
        exception.activeJobId());

    assertTrue(
        registry.getActiveJob().isPresent());
  }

  @Test
  void permitsNewJobAfterCompletion() {
    IndexingJobRegistry registry = new IndexingJobRegistry();

    IndexingJobStatus first = registry.createJob();

    registry.update(
        first.jobId(),
        IndexingJob::start);

    registry.update(
        first.jobId(),
        IndexingJob::complete);

    assertFalse(
        registry.getActiveJob().isPresent());

    IndexingJobStatus second = registry.createJob();

    assertFalse(
        first.jobId().equals(
            second.jobId()));
  }

  @Test
  void permitsNewJobAfterFailure() {
    IndexingJobRegistry registry = new IndexingJobRegistry();

    IndexingJobStatus first = registry.createJob();

    registry.update(
        first.jobId(),
        IndexingJob::start);

    registry.update(
        first.jobId(),
        job -> job.fail("Embedding failed"));

    IndexingJobStatus failed = registry.getStatus(
        first.jobId());

    assertEquals(
        IndexingJobStage.FAILED,
        failed.stage());

    assertEquals(
        "Embedding failed",
        failed.errorMessage());

    assertTrue(failed.terminal());

    IndexingJobStatus second = registry.createJob();

    assertFalse(
        first.jobId().equals(
            second.jobId()));
  }

  @Test
  void rejectsUnknownJobId() {
    IndexingJobRegistry registry = new IndexingJobRegistry();

    UUID unknown = UUID.randomUUID();

    assertThrows(
        IndexingJobNotFoundException.class,
        () -> registry.getStatus(unknown));

    assertThrows(
        IndexingJobNotFoundException.class,
        () -> registry.addListener(
            unknown,
            ignored -> {
            }));
  }
}
