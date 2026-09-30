package com.example.documentassistant.retrieval;

import com.example.documentassistant.retrieval.KeywordChunkSearchService.KeywordCandidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class HybridCandidateService {

  private static final int RRF_CONSTANT = 60;

  private final ChunkRetrievalService vectorSearch;
  private final KeywordChunkSearchService keywordSearch;
  private final RetrievalProperties properties;

  public HybridCandidateService(
      ChunkRetrievalService vectorSearch,
      KeywordChunkSearchService keywordSearch,
      RetrievalProperties properties) {

    this.vectorSearch = Objects.requireNonNull(vectorSearch);
    this.keywordSearch = Objects.requireNonNull(keywordSearch);
    this.properties = Objects.requireNonNull(properties);
  }

  public CandidateResponse search(RetrievalRequest request) {
    Objects.requireNonNull(request, "request must not be null");

    int topK = request.topK() == null
        ? properties.defaultTopK()
        : request.topK();

    if (topK > properties.maxTopK()) {
      throw new IllegalArgumentException(
          "topK must not exceed " + properties.maxTopK());
    }

    int keywordLimit = properties.keywordLimit();
    int candidateLimit = properties.candidateLimit();

    RetrievalResponse vector = vectorSearch.search(
        new RetrievalRequest(request.query(), topK));

    List<KeywordCandidate> keyword = keywordSearch.search(
        request.query(), keywordLimit);

    List<KeywordCandidate> acrossDocuments = keywordSearch.searchAcrossDocuments(
        request.query(),
        properties.diversityChunksPerDocument(),
        properties.diversityDocumentLimit());

    Map<String, Accumulator> byChunk = new LinkedHashMap<>();

    for (int i = 0; i < vector.results().size(); i++) {
      RetrievedChunk chunk = vector.results().get(i);
      String id = chunkId(
          chunk.documentId(), chunk.chunkIndex());

      Accumulator candidate = byChunk.computeIfAbsent(
          id,
          ignored -> new Accumulator(
              chunk.documentId(),
              chunk.sourceFile(),
              chunk.chunkIndex(),
              chunk.section(),
              chunk.text()));

      candidate.channels.add("vector");
      candidate.vectorSimilarityScore = chunk.similarityScore();
      candidate.rrfScore += rrfContribution(i);
    }

    for (int i = 0; i < keyword.size(); i++) {
      KeywordCandidate chunk = keyword.get(i);
      Accumulator candidate = addKeyword(byChunk, chunk);

      candidate.channels.add("keyword");
      candidate.keywordScore = chunk.keywordScore();
      candidate.rrfScore += rrfContribution(i);
    }

    /*
     * This lane guarantees a bounded amount of source diversity.
     * It does not add another RRF contribution: these are results
     * from the same keyword search method.
     */
    for (KeywordCandidate chunk : acrossDocuments) {
      Accumulator candidate = addKeyword(byChunk, chunk);
      candidate.channels.add("source-diverse keyword");
      candidate.selectedByDiversity = true;

      if (candidate.keywordScore == null) {
        candidate.keywordScore = chunk.keywordScore();
      }
    }

    /*
     * Reserve places for the source-diverse candidates first.
     * Fill remaining places by fused vector/keyword rank.
     */
    Set<String> selectedIds = new LinkedHashSet<>();

    for (KeywordCandidate chunk : acrossDocuments) {
      if (selectedIds.size() >= candidateLimit) {
        break;
      }
      selectedIds.add(chunkId(
          chunk.documentId(), chunk.chunkIndex()));
    }

    List<Accumulator> byRank = new ArrayList<>(byChunk.values());

    byRank.sort(
        Comparator.comparingDouble(
            (Accumulator candidate) -> candidate.rrfScore)
            .reversed()
            .thenComparing(candidate -> candidate.documentId)
            .thenComparingInt(candidate -> candidate.chunkIndex));

    for (Accumulator candidate : byRank) {
      if (selectedIds.size() >= candidateLimit) {
        break;
      }
      selectedIds.add(chunkId(
          candidate.documentId, candidate.chunkIndex));
    }

    List<Candidate> selected = selectedIds.stream()
        .map(byChunk::get)
        .sorted(
            Comparator.comparingDouble(
                (Accumulator candidate) -> candidate.rrfScore)
                .reversed()
                .thenComparing(candidate -> candidate.documentId)
                .thenComparingInt(candidate -> candidate.chunkIndex))
        .map(Accumulator::toCandidate)
        .toList();

    int documentCount = (int) selected.stream()
        .map(Candidate::documentId)
        .distinct()
        .count();

    return new CandidateResponse(
        request.query(),
        topK,
        keywordLimit,
        properties.diversityDocumentLimit(),
        properties.diversityChunksPerDocument(),
        candidateLimit,
        vector.resultCount(),
        keyword.size(),
        acrossDocuments.size(),
        documentCount,
        selected);
  }

  private Accumulator addKeyword(
      Map<String, Accumulator> byChunk,
      KeywordCandidate chunk) {

    String id = chunkId(
        chunk.documentId(), chunk.chunkIndex());

    return byChunk.computeIfAbsent(
        id,
        ignored -> new Accumulator(
            chunk.documentId(),
            chunk.sourceFile(),
            chunk.chunkIndex(),
            chunk.section(),
            chunk.text()));
  }

  private String chunkId(
      String documentId,
      int chunkIndex) {

    return documentId + ":" + chunkIndex;
  }

  private double rrfContribution(int zeroBasedRank) {
    return 1.0 / (RRF_CONSTANT + zeroBasedRank + 1);
  }

  private static class Accumulator {

    private final String documentId;
    private final String sourceFile;
    private final int chunkIndex;
    private final String section;
    private final String text;
    private final Set<String> channels = new LinkedHashSet<>();

    private Double vectorSimilarityScore;
    private Double keywordScore;
    private double rrfScore;
    private boolean selectedByDiversity;

    private Accumulator(
        String documentId,
        String sourceFile,
        int chunkIndex,
        String section,
        String text) {

      this.documentId = documentId;
      this.sourceFile = sourceFile;
      this.chunkIndex = chunkIndex;
      this.section = section;
      this.text = text;
    }

    private Candidate toCandidate() {
      return new Candidate(
          documentId,
          sourceFile,
          chunkIndex,
          section,
          text,
          List.copyOf(channels),
          vectorSimilarityScore,
          keywordScore,
          rrfScore,
          selectedByDiversity);
    }
  }

  public record Candidate(
      String documentId,
      String sourceFile,
      int chunkIndex,
      String section,
      String text,
      List<String> retrievalChannels,
      Double vectorSimilarityScore,
      Double keywordScore,
      double rrfScore,
      boolean selectedByDiversity) {

    public Candidate {
      retrievalChannels = List.copyOf(retrievalChannels);
    }
  }

  public record CandidateResponse(
      String query,
      int vectorTopK,
      int keywordLimit,
      int documentLimit,
      int chunksPerDocument,
      int candidateLimit,
      int vectorResultCount,
      int keywordResultCount,
      int sourceDiverseResultCount,
      int selectedDocumentCount,
      List<Candidate> candidates) {

    public CandidateResponse {
      candidates = List.copyOf(candidates);
    }
  }
}
