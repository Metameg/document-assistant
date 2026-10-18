package com.example.documentassistant.corpus;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Trusted Python entry points only; arguments are passed directly, without a shell. */
@Component
public class CorpusProcesses {
  public final Path root;
  public final Path storage;
  private CorpusArtifacts artifacts;
  @Value("${document-assistant.access.internal-token:}") private String internalToken="";
  @Autowired void configureArtifacts(CorpusArtifacts artifacts) {this.artifacts=artifacts;}
  public CorpusArtifacts artifacts() {return artifacts;}
  private final String python;
  private final String apiKey;
  private final long timeout;
  private final Set<Process> processes = ConcurrentHashMap.newKeySet();
  public CorpusProcesses(@Value("${document-assistant.corpora.project-root:.}") String root,
      @Value("${document-assistant.corpora.storage-directory:data/corpora}") String storage,
      @Value("${document-assistant.corpora.python-executable:python}") String python,
      @Value("${spring.ai.openai.api-key:}") String apiKey,
      @Value("${document-assistant.corpora.process-timeout-seconds:21600}") long timeout) {
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.storage = this.root.resolve(storage).normalize();
    this.artifacts = CorpusArtifacts.local(this.storage);
    this.python = python; this.apiKey = apiKey; this.timeout = timeout;
  }
  public Path corpus(UUID id) { return storage.resolve(id.toString()); }
  public void execute(String script, List<String> arguments, Path log, Map<String,String> environment) throws IOException, InterruptedException {
    Files.createDirectories(log.getParent());
    List<String> command = new ArrayList<>(List.of(python, "-u", root.resolve("scripts").resolve(script).toString()));
    command.addAll(arguments);
    ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().put("OPENROUTER_API_KEY", apiKey);
    builder.environment().put("MPLBACKEND", "Agg");
    builder.environment().put("MPLCONFIGDIR", storage.resolve("matplotlib-cache").toString());
    builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
    builder.environment().put("HF_HOME", storage.resolve("model-cache").toString());
    if(!internalToken.isBlank()) builder.environment().put("DOCUMENT_ASSISTANT_WORKER_TOKEN",internalToken);
    builder.environment().putAll(environment);
    Process process = builder.start(); processes.add(process);
    try {
      if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException("Processing timed out");
      if (process.exitValue() != 0) throw new IOException("Python processing failed; inspect " + log.getFileName() + " in this corpus's local artifacts");
    } finally {
      terminate(process); processes.remove(process);
      // Preserve completed outputs and partial reports on normal failure as well as success.
      artifacts.publish(log.getParent());
    }
  }
  private void terminate(Process process) {
    if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
  }
  @PreDestroy void close() { processes.forEach(this::terminate); }
}
