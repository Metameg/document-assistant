package com.example.documentassistant.rag;

import com.example.documentassistant.retrieval.HybridCandidateService;
import com.example.documentassistant.retrieval.RetrievalProperties;
import com.example.documentassistant.retrieval.HybridCandidateService.Candidate;
import com.example.documentassistant.retrieval.HybridCandidateService.CandidateResponse;
import com.example.documentassistant.retrieval.RetrievalRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class HybridAnswerService {

  private static final String SYSTEM_INSTRUCTIONS = """
      Answer using only the supplied document excerpts.

      Cite factual claims using only the source numbers provided, such as [1].
      Never invent citations or use line citations such as [L1-L3].
      If the excerpts do not support an answer, say what information is missing.

      Preserve relevant conditions such as model, fuel, load, operating mode,
      units, and document revision. Include supported ties. Do not confuse
      engine displacement, engine output, and generator electrical output. Do not treat engine
      displacement, engine output, and generator electrical output
      as the same measurement.

      The excerpts may not represent the entire corpus. Do not make
      corpus-wide claims unless the supplied evidence establishes them.

      If information needed for a definitive answer is missing,
      say exactly what is missing and provide useful supported
      facts or a conditional method. Do not invent measurements,
      certifications, prices, or performance claims.
      """;

  private final HybridCandidateService candidateService;
  private final ObjectProvider<ChatClient.Builder> chatClientBuilders;
  private final RetrievalProperties retrievalProperties;

  public HybridAnswerService(
      HybridCandidateService candidateService,
      ObjectProvider<ChatClient.Builder> chatClientBuilders,
      RetrievalProperties retrievalProperties) {

    this.candidateService = Objects.requireNonNull(candidateService);
    this.chatClientBuilders = Objects.requireNonNull(chatClientBuilders);
    this.retrievalProperties = Objects.requireNonNull(retrievalProperties);
  }

  public HybridAnswerResponse answer(
      RetrievalRequest request) {

    Objects.requireNonNull(request, "request must not be null");

    long retrievalStarted = System.nanoTime();

    // Keep retrieval depth fixed; request.topK controls answer evidence.
    CandidateResponse candidates = candidateService.search(
        new RetrievalRequest(request.query(), 20));

    double retrievalDurationMs = elapsedMilliseconds(retrievalStarted);

    int evidenceLimit = request.topK() == null
        ? retrievalProperties.defaultEvidenceLimit()
        : request.topK();

    if (evidenceLimit > retrievalProperties.maxEvidenceLimit()) {
      throw new IllegalArgumentException(
          "topK must not exceed "
              + retrievalProperties.maxEvidenceLimit());
    }

    List<Evidence> evidence = selectEvidence(
        candidates.candidates(),
        evidenceLimit);

    if (evidence.isEmpty()) {
      return new HybridAnswerResponse(
          request.query(),
          "I could not find relevant source material to answer this question.",
          candidates.candidates().size(),
          0,
          0,
          retrievalDurationMs,
          0.0,
          evidence);
    }

    ChatClient.Builder builder = chatClientBuilders.getIfAvailable();

    if (builder == null) {
      throw new IllegalStateException(
          "A chat model is not configured for answer generation");
    }

    long inferenceStarted = System.nanoTime();
    String answer = builder.build()
        .prompt()
        .system(SYSTEM_INSTRUCTIONS)
        .user(formatPrompt(request.query(), evidence))
        .call()
        .content();

    double inferenceDurationMs = elapsedMilliseconds(inferenceStarted);

    if (answer == null || answer.isBlank()) {
      throw new IllegalStateException(
          "The chat model returned an empty answer");
    }

    int evidenceDocumentCount = (int) evidence.stream()
        .map(item -> item.candidate().documentId())
        .distinct()
        .count();

    return new HybridAnswerResponse(
        request.query(),
        answer,
        candidates.candidates().size(),
        evidence.size(),
        evidenceDocumentCount,
        retrievalDurationMs,
        inferenceDurationMs,
        evidence);
  }

  /** Retrieves every final evidence list requested, with one shared candidate search. */
  public EvidencePoolResponse evidencePool(
      EvidencePoolRequest request) {

    Objects.requireNonNull(request, "request must not be null");

    if (request.query() == null || request.query().isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }

    if (request.kValues() == null || request.kValues().isEmpty()
        || request.kValues().size() > 100
        || request.kValues().stream().anyMatch(
            k -> k == null || k < 1
                || k > retrievalProperties.maxEvidenceLimit())) {
      throw new IllegalArgumentException(
          "kValues must contain 1 to 100 integers from 1 to "
              + retrievalProperties.maxEvidenceLimit());
    }

    List<Integer> kValues = request.kValues().stream()
        .distinct()
        .sorted()
        .toList();

    long retrievalStarted = System.nanoTime();
    CandidateResponse candidates = candidateService.search(
        new RetrievalRequest(request.query(), 20));

    List<EvidenceAtK> evidenceByK = kValues.stream()
        .map(k -> new EvidenceAtK(
            k,
            selectEvidence(candidates.candidates(), k)))
        .toList();

    return new EvidencePoolResponse(
        request.query(),
        candidates.candidates().size(),
        elapsedMilliseconds(retrievalStarted),
        evidenceByK);
  }

  private double elapsedMilliseconds(
      long startedAtNanos) {

    return (System.nanoTime() - startedAtNanos)
        / 1_000_000.0;
  }

  private List<Evidence> selectEvidence(
      List<Candidate> candidates,
      int evidenceLimit) {

    /*
     * Keep source-diverse matches first. Then add the strongest
     * remaining hybrid candidates. The map preserves this order
     * and prevents a chunk from appearing twice.
     */
    Map<String, Candidate> selected = new LinkedHashMap<>();

    for (Candidate candidate : candidates) {
      if (candidate.selectedByDiversity()
          && selected.size() < evidenceLimit) {
        selected.putIfAbsent(key(candidate), candidate);
      }
    }

    for (Candidate candidate : candidates) {
      if (selected.size() >= evidenceLimit) {
        break;
      }
      selected.putIfAbsent(key(candidate), candidate);
    }

    List<Evidence> evidence = new ArrayList<>();
    int usedCharacters = 0;

    for (Candidate candidate : selected.values()) {
      int nextLength = candidate.text().length();

      if (usedCharacters + nextLength > retrievalProperties.maxContextCharacters()) {
        continue;
      }

      usedCharacters += nextLength;

      evidence.add(new Evidence(
          evidence.size() + 1,
          candidate));
    }

    return List.copyOf(evidence);
  }

  private String key(Candidate candidate) {
    return candidate.documentId()
        + ":" + candidate.chunkIndex();
  }

  private String formatPrompt(
      String question,
      List<Evidence> evidence) {

    StringBuilder prompt = new StringBuilder();

    prompt.append("Question:\n")
        .append(question)
        .append("\n\nSource excerpts:\n");

    for (Evidence item : evidence) {
      Candidate candidate = item.candidate();

      prompt.append("\n[")
          .append(item.sourceNumber())
          .append("] Document ID: ")
          .append(candidate.documentId())
          .append("\nFilename: ")
          .append(candidate.sourceFile())
          .append("\nSection: ")
          .append(candidate.section())
          .append("\nChunk index: ")
          .append(candidate.chunkIndex())
          .append("\nText:\n")
          .append(candidate.text())
          .append('\n');
    }

    return prompt.toString();
  }

  public record Evidence(
      int sourceNumber,
      Candidate candidate) {
  }

  public record EvidencePoolRequest(
      String query,
      List<Integer> kValues) {

    public EvidencePoolRequest {
      kValues = kValues == null ? null : List.copyOf(kValues);
    }
  }

  public record EvidenceAtK(
      int k,
      List<Evidence> evidence) {

    public EvidenceAtK {
      evidence = List.copyOf(evidence);
    }
  }

  public record EvidencePoolResponse(
      String query,
      int retrievedCandidateCount,
      double retrievalDurationMs,
      List<EvidenceAtK> evidenceByK) {

    public EvidencePoolResponse {
      evidenceByK = List.copyOf(evidenceByK);
    }
  }

  public record HybridAnswerResponse(
      String query,
      String answer,
      int retrievedCandidateCount,
      int evidenceChunkCount,
      int evidenceDocumentCount,
      double retrievalDurationMs,
      double inferenceDurationMs,
      List<Evidence> evidence) {

    public HybridAnswerResponse {
      evidence = List.copyOf(evidence);
    }
  }
}
