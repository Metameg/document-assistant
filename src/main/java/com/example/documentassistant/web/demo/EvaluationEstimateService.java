package com.example.documentassistant.web.demo;

import com.example.documentassistant.retrieval.RetrievalProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Supplies workload/pricing assumptions; the browser recomputes estimates on input. */
@Service
@PropertySource("classpath:evaluation-runner.properties")
public class EvaluationEstimateService {
  public record ModelEstimate(String model, double inputUsdPerMillion,
      double outputUsdPerMillion, int inputTokensPerCall, int outputTokensPerCall) {}
  public record Settings(List<Integer> defaultKValues, int maxK, int questionCount,
      int minutesPerK, ModelEstimate answer, ModelEstimate judge) {}
  private final Path root;
  private final ObjectMapper mapper;
  private final RetrievalProperties retrieval;
  private final List<Integer> defaults;
  private final ModelEstimate model;

  public EvaluationEstimateService(ObjectMapper mapper, RetrievalProperties retrieval,
      @Value("${document-assistant.evaluation.project-root:.}") String projectRoot,
      @Value("${document-assistant.evaluation.k-values:5,10,20,30,40}") String kValues,
      @Value("${document-assistant.evaluation.estimate.input-tokens:25}") int inputTokens,
      @Value("${document-assistant.evaluation.estimate.output-tokens:100}") int outputTokens,
      @Value("${document-assistant.evaluation.estimate.input-usd-per-million:0.03}") double inputRate,
      @Value("${document-assistant.evaluation.estimate.output-usd-per-million:0.17}") double outputRate) {
    this.mapper = mapper;
    this.retrieval = retrieval;
    this.root = Path.of(projectRoot).toAbsolutePath().normalize();
    this.defaults = parseDefaults(kValues, retrieval.maxEvidenceLimit());
    if (inputTokens < 1 || outputTokens < 1 || !Double.isFinite(inputRate) || inputRate < 0
        || !Double.isFinite(outputRate) || outputRate < 0) {
      throw new IllegalArgumentException("Invalid evaluation estimate assumptions");
    }
    this.model = new ModelEstimate("openai/gpt-oss-120b", inputRate, outputRate,
        inputTokens, outputTokens);
  }

  public static List<Integer> validateKValues(List<Integer> values, int maxK) {
    if (values == null || values.isEmpty() || values.size() > 100
        || values.stream().anyMatch(k -> k == null || k < 1 || k > maxK)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Enter between 1 and 100 k values, each an integer from 1 to " + maxK);
    }
    return values.stream().distinct().sorted().toList();
  }

  public static List<Integer> parseDefaults(String text, int maxK) {
    try {
      return validateKValues(Arrays.stream(text.split(",", -1))
          .map(String::trim).map(Integer::valueOf).toList(), maxK);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Invalid default evaluation k values", exception);
    }
  }

  public Settings settings() {
    try {
      JsonNode cases = mapper.readTree(root.resolve("evaluation/questions.json").toFile());
      if (!cases.isArray() || cases.isEmpty()) throw new IllegalStateException("Question dataset is empty");
      return new Settings(defaults, retrieval.maxEvidenceLimit(), cases.size(), 10, model, model);
    } catch (Exception exception) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Could not prepare evaluation settings. Check the question dataset.");
    }
  }

}
