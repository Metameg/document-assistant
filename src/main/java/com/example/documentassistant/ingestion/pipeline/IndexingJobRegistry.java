package com.example.documentassistant.ingestion.pipeline;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class IndexingJobRegistry {

  private final ConcurrentHashMap<UUID, IndexingJob> jobs = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Consumer<IndexingJobStatus>>> listeners = new ConcurrentHashMap<>();

  private final Object activeJobMonitor = new Object();

  private UUID activeJobId;

  IndexingJobStatus createJob() {
    synchronized (activeJobMonitor) {
      if (activeJobId != null) {
        IndexingJob activeJob = jobs.get(activeJobId);

        if (activeJob != null
            && !activeJob.snapshot().terminal()) {
          throw new IndexingJobAlreadyRunningException(
              activeJobId);
        }

        activeJobId = null;
      }

      UUID jobId = UUID.randomUUID();
      IndexingJob job = new IndexingJob(jobId);

      jobs.put(jobId, job);
      activeJobId = jobId;

      return job.snapshot();
    }
  }

  public IndexingJobStatus getStatus(
      UUID jobId) {

    return requireJob(jobId).snapshot();
  }

  public Optional<IndexingJobStatus> getActiveJob() {

    synchronized (activeJobMonitor) {
      if (activeJobId == null) {
        return Optional.empty();
      }

      IndexingJob job = jobs.get(activeJobId);

      if (job == null) {
        activeJobId = null;
        return Optional.empty();
      }

      IndexingJobStatus status = job.snapshot();

      if (status.terminal()) {
        activeJobId = null;
        return Optional.empty();
      }

      return Optional.of(status);
    }
  }

  IndexingJobStatus update(
      UUID jobId,
      Consumer<IndexingJob> update) {

    IndexingJob job = requireJob(jobId);

    update.accept(job);

    IndexingJobStatus status = job.snapshot();

    if (status.terminal()) {
      releaseActiveJob(jobId);
    }

    publish(status);

    return status;
  }

  public Runnable addListener(
      UUID jobId,
      Consumer<IndexingJobStatus> listener) {

    requireJob(jobId);

    CopyOnWriteArrayList<Consumer<IndexingJobStatus>> jobListeners = listeners.computeIfAbsent(
        jobId,
        ignored -> new CopyOnWriteArrayList<>());

    jobListeners.add(listener);

    return () -> {
      jobListeners.remove(listener);

      if (jobListeners.isEmpty()) {
        listeners.remove(
            jobId,
            jobListeners);
      }
    };
  }

  private IndexingJob requireJob(
      UUID jobId) {

    IndexingJob job = jobs.get(jobId);

    if (job == null) {
      throw new IndexingJobNotFoundException(
          jobId);
    }

    return job;
  }

  private void releaseActiveJob(
      UUID jobId) {

    synchronized (activeJobMonitor) {
      if (jobId.equals(activeJobId)) {
        activeJobId = null;
      }
    }
  }

  private void publish(
      IndexingJobStatus status) {

    var jobListeners = listeners.get(status.jobId());

    if (jobListeners == null) {
      return;
    }

    for (Consumer<IndexingJobStatus> listener : jobListeners) {
      try {
        listener.accept(status);
      } catch (RuntimeException ignored) {
        // One disconnected listener must not
        // stop the indexing job or other listeners.
      }
    }
  }
}
