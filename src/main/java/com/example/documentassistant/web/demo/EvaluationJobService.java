package com.example.documentassistant.web.demo;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.example.documentassistant.retrieval.RetrievalProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Runs trusted project scripts without a shell. One job at a time per application. */
@Service
@PropertySource("classpath:evaluation-runner.properties")
public class EvaluationJobService {
  public static final Set<String> PLOT_NAMES = Set.of(
      "answer-correctness.png", "retrieval-map-mrr.png",
      "retrieval-latency.png", "inference-latency.png", "retrieval-ranking.png");

  public record Status(UUID jobId, String stage, boolean terminal,
                       Instant startedAt, Instant finishedAt, String message, List<Integer> kValues) {}

  private final Path root;
  private final Path results;
  private final String python;
  private final String applicationUrl;
  private final String judgeModel;
  private final String apiKey;
  private final List<Integer> defaultKValues;
  private final int maxK;
  private final long timeoutSeconds;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "rag-evaluation");
    thread.setDaemon(true);
    return thread;
  });
  private volatile Status latest;
  private Process runningProcess;
  private boolean stopping;

  public EvaluationJobService(
      @Value("${document-assistant.evaluation.project-root:.}") String projectRoot,
      @Value("${document-assistant.evaluation.python-executable:python}") String python,
      @Value("${document-assistant.evaluation.application-url:http://localhost:8080}") String applicationUrl,
      @Value("${document-assistant.evaluation.judge-model:openai/gpt-oss-120b}") String judgeModel,
      @Value("${spring.ai.openai.api-key:}") String apiKey,
      @Value("${document-assistant.evaluation.k-values:5,10,20,30,40}") String kValues,
      @Value("${document-assistant.evaluation.process-timeout-seconds:21600}") long timeoutSeconds,
      RetrievalProperties retrievalProperties) {
    this.root = Path.of(projectRoot).toAbsolutePath().normalize();
    this.results = root.resolve("evaluation/results");
    this.python = python;
    this.applicationUrl = applicationUrl;
    this.judgeModel = judgeModel;
    this.apiKey = apiKey;
    this.maxK = retrievalProperties.maxEvidenceLimit();
    this.defaultKValues = EvaluationEstimateService.parseDefaults(kValues, maxK);
    if (timeoutSeconds < 1) throw new IllegalArgumentException("Evaluation timeout must be positive");
    this.timeoutSeconds = timeoutSeconds;
  }

  public synchronized Status start(List<Integer> requestedKValues) {
    List<Integer> runKValues = EvaluationEstimateService.validateKValues(
        requestedKValues == null ? defaultKValues : requestedKValues, maxK);
    if (stopping) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Application is stopping");
    if (latest != null && !latest.terminal()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "An evaluation is already running");
    }
    for (String name : List.of("scripts/evaluate_rag.py", "scripts/plot_evaluation.py",
        "evaluation/questions.json", "evaluation/judge-system-prompt.txt",
        "evaluation/qrels.tsv", "evaluation/qrels.metadata.json")) {
      if (!Files.isRegularFile(root.resolve(name))) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Required evaluation file is missing: " + name);
      }
    }
    UUID id = UUID.randomUUID();
    latest = new Status(id, "EVALUATING", false, Instant.now(), null,
        "Evaluating answers at k=" + runKValues + "…", runKValues);
    Status initial = latest;
    worker.submit(() -> run(id, initial.startedAt(), runKValues));
    return initial;
  }

  public Status current() { return latest; }

  public Status get(UUID id) {
    Status status = latest;
    if (status == null || !status.jobId().equals(id)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation job is no longer available");
    }
    return status;
  }

  private void run(UUID id, Instant startedAt, List<Integer> runKValues) {
    Path directory = results;
    try {
      Files.createDirectories(directory);
      Path report = directory.resolve("report.json");
      List<String> evaluation = new ArrayList<>(List.of(python, "-u",
          root.resolve("scripts/evaluate_rag.py").toString(),
          "--dataset", root.resolve("evaluation/questions.json").toString(),
          "--judge-prompt", root.resolve("evaluation/judge-system-prompt.txt").toString(),
          "--application-url", applicationUrl, "--judge-model", judgeModel,
          "--output", report.toString(), "--k"));
      evaluation.addAll(runKValues.stream().map(String::valueOf).toList());
      execute(evaluation, directory.resolve("evaluation.log"));
      latest = new Status(id, "PLOTTING", false, startedAt, null, "Evaluation finished. Generating plots…", runKValues);
      Path plots = directory.resolve("plots");
      execute(List.of(python, "-u", root.resolve("scripts/plot_evaluation.py").toString(),
          "--input", report.toString(), "--output-directory", plots.toString()),
          directory.resolve("plotting.log"));
      for (String name : PLOT_NAMES) {
        if (!Files.isRegularFile(plots.resolve(name)) || Files.size(plots.resolve(name)) == 0) {
          throw new IOException("A required plot was not generated");
        }
      }
      latest = new Status(id, "COMPLETED", true, startedAt, Instant.now(), "Evaluation and plots updated.", runKValues);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      latest = new Status(id, "FAILED", true, startedAt, Instant.now(), "Evaluation interrupted; previous plots retained.", runKValues);
    } catch (Exception exception) {
      // No subprocess output or credentials are exposed through the API.
      latest = new Status(id, "FAILED", true, startedAt, Instant.now(),
          "Evaluation failed. Check Python setup and the logs in evaluation/results.", runKValues);
    }
  }

  private void execute(List<String> command, Path log) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
    builder.redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().put("MPLBACKEND", "Agg");
    builder.environment().put("MPLCONFIGDIR", results.resolve("matplotlib-cache").toString());
    // Allow an explicitly configured judge key; otherwise reuse the app's provider key.
    String judgeKey = builder.environment().get("OPENROUTER_API_KEY");
    if ((judgeKey == null || judgeKey.isBlank()) && !apiKey.isBlank()) {
      builder.environment().put("OPENROUTER_API_KEY", apiKey);
    }
    Process process;
    synchronized (this) {
      if (stopping) throw new InterruptedException("Application is stopping");
      process = builder.start();
      runningProcess = process;
    }
    try {
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        throw new IOException("Evaluation process timed out");
      }
      if (process.exitValue() != 0) throw new IOException("Evaluation script exited unsuccessfully");
    } finally {
      if (process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
      synchronized (this) { if (runningProcess == process) runningProcess = null; }
    }
  }

  public Path plot(String name) {
    if (!PLOT_NAMES.contains(name)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown plot");
    try {
      Path file = results.resolve("plots").resolve(name);
      if (!Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plot not available yet");
      return file;
    } catch (SecurityException exception) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not access the published plots");
    }
  }

  @PreDestroy
  public synchronized void close() {
    stopping = true;
    worker.shutdownNow();
    if (runningProcess != null) {
      runningProcess.descendants().forEach(ProcessHandle::destroyForcibly);
      runningProcess.destroyForcibly();
    }
  }
}
