package com.example.documentassistant.corpus;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

/** Durable registry. Vector table identifiers are generated here, never supplied by clients. */
@Repository
public class CorpusRepository {
  private final JdbcTemplate db;
  private final JsonMapper json;

  public CorpusRepository(JdbcTemplate db, JsonMapper json) { this.db = db; this.json = json; }

  @PostConstruct
  void initialize() {
    db.execute("CREATE TABLE IF NOT EXISTS rag_corpora (id uuid PRIMARY KEY, name text NOT NULL, active_version uuid, created_at timestamptz NOT NULL DEFAULT now())");
    db.execute("CREATE TABLE IF NOT EXISTS rag_documents (id uuid PRIMARY KEY, corpus_id uuid NOT NULL REFERENCES rag_corpora, filename text NOT NULL, checksum text NOT NULL, pages integer NOT NULL, UNIQUE(corpus_id, checksum))");
    db.execute("CREATE TABLE IF NOT EXISTS rag_versions (id uuid PRIMARY KEY, corpus_id uuid NOT NULL REFERENCES rag_corpora, table_name text NOT NULL UNIQUE, manifest jsonb NOT NULL, config jsonb NOT NULL, chunks integer NOT NULL, created_at timestamptz NOT NULL DEFAULT now())");
    db.execute("CREATE TABLE IF NOT EXISTS rag_jobs (id uuid PRIMARY KEY, corpus_id uuid NOT NULL REFERENCES rag_corpora, terminal boolean NOT NULL, data jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now())");
    db.execute("CREATE UNIQUE INDEX IF NOT EXISTS rag_one_job_per_corpus ON rag_jobs(corpus_id) WHERE terminal = false");
    db.execute("CREATE TABLE IF NOT EXISTS rag_benchmarks (id uuid PRIMARY KEY, corpus_id uuid NOT NULL REFERENCES rag_corpora, version_id uuid NOT NULL REFERENCES rag_versions, state text NOT NULL, dataset jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now())");
    db.execute("CREATE TABLE IF NOT EXISTS rag_evaluations (id uuid PRIMARY KEY REFERENCES rag_jobs, corpus_id uuid NOT NULL REFERENCES rag_corpora, benchmark_id uuid NOT NULL REFERENCES rag_benchmarks, config jsonb NOT NULL)");
    // No corpus FK: cleanup must remain retryable after the corpus itself is deleted.
    db.execute("CREATE TABLE IF NOT EXISTS rag_file_cleanup (id uuid PRIMARY KEY, paths jsonb NOT NULL)");
    for (Job job : db.query("SELECT data::text FROM rag_jobs WHERE terminal = false", (r, n) -> decode(r.getString(1), Job.class))) {
      saveJob(job.progress("INTERRUPTED", "Application restarted; retry this job to retain completed processing artifacts.", true, job.currentDocument(), job.completed(), job.total(), job.chunks()));
    }
  }

  public record Corpus(UUID id, String name, UUID activeVersion, Instant createdAt) {}
  public record Paper(UUID id, String sourceFile, String checksum, int pageCount) {}
  public record Version(UUID id, UUID corpusId, String tableName, List<Paper> documents, JsonNode config, int chunkCount, Instant createdAt) {}
  public record Benchmark(UUID id, UUID corpusId, UUID versionId, String state, JsonNode dataset, Instant createdAt) {}
  public record Job(UUID jobId, UUID corpusId, String kind, String stage, boolean terminal, String message,
                    String currentDocument, int completed, int total, int chunks, Instant startedAt, Instant finishedAt, UUID referenceId) {
    public Job progress(String stage, String message, boolean terminal, String document, int completed, int total, int chunks) {
      return new Job(jobId, corpusId, kind, stage, terminal, message, document, completed, total, chunks, startedAt, terminal ? Instant.now() : null, referenceId);
    }
  }

  public String encode(Object value) { return json.writeValueAsString(value); }
  public <T> T decode(String value, Class<T> type) { return json.readValue(value, type); }
  public JsonNode tree(String value) { return json.readTree(value); }

  public List<Corpus> corpora() {
    return db.query("SELECT * FROM rag_corpora ORDER BY created_at", (r, n) -> new Corpus(r.getObject("id", UUID.class), r.getString("name"), r.getObject("active_version", UUID.class), r.getTimestamp("created_at").toInstant()));
  }
  public Corpus corpus(UUID id) { return corpora().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(() -> missing("Corpus")); }
  public Corpus create(String name) {
    UUID id = UUID.randomUUID();
    db.update("INSERT INTO rag_corpora(id,name) VALUES (?,?)", id, name);
    return corpus(id);
  }
  public List<Paper> papers(UUID corpus) {
    return db.query("SELECT * FROM rag_documents WHERE corpus_id=? ORDER BY filename,id", (r,n) -> new Paper(r.getObject("id", UUID.class), r.getString("filename"), r.getString("checksum"), r.getInt("pages")), corpus);
  }
  public void addPaper(UUID corpus, Paper paper) {
    db.update("INSERT INTO rag_documents(id,corpus_id,filename,checksum,pages) VALUES (?,?,?,?,?)", paper.id(), corpus, paper.sourceFile(), paper.checksum(), paper.pageCount());
  }
  public void saveVersion(Version v) {
    db.update("INSERT INTO rag_versions(id,corpus_id,table_name,manifest,config,chunks) VALUES (?,?,?,?::jsonb,?::jsonb,?)", v.id(), v.corpusId(), v.tableName(), encode(v.documents()), encode(v.config()), v.chunkCount());
  }
  public void activate(UUID corpus, UUID version) { db.update("UPDATE rag_corpora SET active_version=? WHERE id=?", version, corpus); }
  public List<Version> versions(UUID corpus) {
    return db.query("SELECT * FROM rag_versions WHERE corpus_id=? ORDER BY created_at DESC", (r,n) -> new Version(r.getObject("id", UUID.class), corpus, r.getString("table_name"), List.of(decode(r.getString("manifest"), Paper[].class)), tree(r.getString("config")), r.getInt("chunks"), r.getTimestamp("created_at").toInstant()), corpus);
  }
  public Version version(UUID corpus, UUID id) { return versions(corpus).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(() -> missing("Index version")); }
  public void insertJob(Job job) {
    try { db.update("INSERT INTO rag_jobs(id,corpus_id,terminal,data) VALUES (?,?,false,?::jsonb)", job.jobId(), job.corpusId(), encode(job)); }
    catch (org.springframework.dao.DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "A job is already running for this corpus"); }
  }
  public void saveJob(Job job) { db.update("UPDATE rag_jobs SET terminal=?,data=?::jsonb WHERE id=?", job.terminal(), encode(job), job.jobId()); }
  public List<Job> jobs(UUID corpus) { return db.query("SELECT data::text FROM rag_jobs WHERE corpus_id=? ORDER BY created_at DESC", (r,n) -> decode(r.getString(1), Job.class), corpus); }
  public Job job(UUID corpus, UUID id) { return jobs(corpus).stream().filter(j -> j.jobId().equals(id)).findFirst().orElseThrow(() -> missing("Job")); }
  public void assertIdle(UUID corpus) {
    if (jobs(corpus).stream().anyMatch(j -> !j.terminal())) throw new ResponseStatusException(HttpStatus.CONFLICT, "A job is already running for this corpus");
  }
  public List<Benchmark> benchmarks(UUID corpus) {
    return db.query("SELECT * FROM rag_benchmarks WHERE corpus_id=? ORDER BY created_at DESC", (r,n) -> new Benchmark(r.getObject("id", UUID.class), corpus, r.getObject("version_id", UUID.class), r.getString("state"), tree(r.getString("dataset")), r.getTimestamp("created_at").toInstant()), corpus);
  }
  public Benchmark benchmark(UUID corpus, UUID id) { return benchmarks(corpus).stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow(() -> missing("Benchmark")); }
  public void createBenchmark(Benchmark b) {
    db.update("INSERT INTO rag_benchmarks(id,corpus_id,version_id,state,dataset) VALUES (?,?,?,?,?::jsonb) ON CONFLICT (id) DO NOTHING", b.id(), b.corpusId(), b.versionId(), b.state(), encode(b.dataset()));
  }
  public void approve(UUID corpus, UUID id) { db.update("UPDATE rag_benchmarks SET state='APPROVED' WHERE id=? AND corpus_id=? AND state='DRAFT'", id, corpus); }
  public void addEvaluation(UUID job, UUID corpus, UUID benchmark, Object config) {
    db.update("INSERT INTO rag_evaluations VALUES (?,?,?,?::jsonb)", job, corpus, benchmark, encode(config));
  }
  public JsonNode evaluationConfig(UUID job) { return tree(db.queryForObject("SELECT config::text FROM rag_evaluations WHERE id=?", String.class, job)); }
  public void removePaper(UUID corpus, UUID paper) {db.update("DELETE FROM rag_documents WHERE corpus_id=? AND id=?",corpus,paper);}
  public void removeJob(UUID corpus, UUID job) {
    db.update("DELETE FROM rag_evaluations WHERE corpus_id=? AND id=?",corpus,job);
    db.update("DELETE FROM rag_jobs WHERE corpus_id=? AND id=?",corpus,job);
  }
  public void removeVersion(UUID corpus, UUID version) {
    db.update("DELETE FROM rag_benchmarks WHERE corpus_id=? AND version_id=?",corpus,version);
    db.update("DELETE FROM rag_versions WHERE corpus_id=? AND id=?",corpus,version);
  }
  public void removeCorpus(UUID corpus) {
    db.update("DELETE FROM rag_evaluations WHERE corpus_id=?",corpus);
    db.update("DELETE FROM rag_benchmarks WHERE corpus_id=?",corpus);
    db.update("DELETE FROM rag_jobs WHERE corpus_id=?",corpus);
    db.update("DELETE FROM rag_versions WHERE corpus_id=?",corpus);
    db.update("DELETE FROM rag_documents WHERE corpus_id=?",corpus);
    db.update("DELETE FROM rag_corpora WHERE id=?",corpus);
  }
  public void queueFileCleanup(Collection<String> paths) {
    if(!paths.isEmpty()) db.update("INSERT INTO rag_file_cleanup VALUES (?,?::jsonb)",UUID.randomUUID(),encode(paths));
  }
  public Map<UUID,List<String>> fileCleanups() {
    Map<UUID,List<String>> result=new LinkedHashMap<>();
    db.query("SELECT id,paths::text FROM rag_file_cleanup",r->{result.put(r.getObject(1,UUID.class),List.of(decode(r.getString(2),String[].class)));});
    return result;
  }
  public void finishFileCleanup(UUID id) {db.update("DELETE FROM rag_file_cleanup WHERE id=?",id);}
  public static ResponseStatusException missing(String kind) { return new ResponseStatusException(HttpStatus.NOT_FOUND, kind + " not found"); }
}
