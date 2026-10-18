package com.example.documentassistant.corpus;

import com.example.documentassistant.corpus.CorpusRepository.*;
import com.example.documentassistant.document.model.*;
import com.example.documentassistant.retrieval.KeywordChunkSearchService;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL/pgvector, isolated schema, deterministic embeddings, no paid API calls. */
@EnabledIfSystemProperty(named="runCorpusIntegrationTests",matches="true")
class CorpusIntegrationTest {
  @TempDir Path directory;
  JdbcTemplate admin,db;
  String schema;
  CorpusRepository repo;
  CorpusService service;
  CorpusVectorStores stores;
  CorpusFileCleanup cleanup;
  AtomicBoolean embeddingFails=new AtomicBoolean();

  @BeforeEach void setup() throws Exception {
    String url=System.getProperty("corpusTestDatabaseUrl","jdbc:postgresql://127.0.0.1:5433/document_assistant_development");
    assertTrue(url.contains("development"),"Corpus integration tests require a development database");
    String password=System.getenv("POSTGRES_PASSWORD");assertNotNull(password,"Load POSTGRES_PASSWORD from local .env");
    admin=new JdbcTemplate(new DriverManagerDataSource(url,"rag_dev",password));
    schema="corpus_test_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
    db=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema="+schema+",public","rag_dev",password));
    JsonMapper json=JsonMapper.builder().build();repo=new CorpusRepository(db,json);repo.initialize();
    EmbeddingModel embedding=new EmbeddingModel() {
      public int dimensions(){return 4;}
      public float[] embed(Document document){return vector(document.getText());}
      public EmbeddingResponse call(EmbeddingRequest request){
        List<Embedding> out=new ArrayList<>();int i=0;
        for(String input:request.getInstructions())out.add(new Embedding(vector(input),i++));
        return new EmbeddingResponse(out);
      }
      private float[] vector(String text){if(embeddingFails.get())throw new IllegalStateException("Simulated provider failure");return new float[]{text.contains("alpha")?1:0,text.contains("beta")?1:0,0.25f,0.5f};}
    };
    stores=new CorpusVectorStores(db,embedding,schema,4);
    CorpusProcesses processor=new CorpusProcesses(directory.toString(),"storage","python","test",30) {
      @Override public void execute(String script,List<String> arguments,Path log,Map<String,String> environment) throws IOException {
        String id=arguments.get(arguments.indexOf("--document-id")+1),file=arguments.get(arguments.indexOf("--source-file")+1);
        Path output=Path.of(arguments.get(arguments.indexOf("--output")+1));Files.createDirectories(output.getParent());
        String text=file.contains("beta")?"beta research findings":"alpha research findings";
        DocumentChunk chunk=new DocumentChunk(id,file,0,"Findings",List.of(),List.of(1),List.of("#/texts/1"),text);
        Files.writeString(output,json.writeValueAsString(new ChunkedDocument("1","docling-hybrid-1",id,file,null,1,text.length(),List.of(chunk))));
      }
    };
    cleanup=new CorpusFileCleanup(repo,processor);
    service=new CorpusService(repo,processor,stores,cleanup,db,new DataSourceTransactionManager(db.getDataSource()),"test-embedding",4,1024*1024,10);
  }
  @AfterEach void cleanup(){if(service!=null)service.close();if(cleanup!=null)cleanup.close();if(admin!=null && schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}

  MockMultipartFile pdf(String name) throws IOException {
    try(PDDocument doc=new PDDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()) {doc.getDocumentInformation().setTitle(name);doc.addPage(new PDPage());doc.save(output);return new MockMultipartFile("files",name,"application/pdf",output.toByteArray());}
  }
  Job await(Job started) throws Exception {
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
    Job job=started;
    while(!job.terminal() && System.nanoTime()<deadline){Thread.sleep(20);job=repo.job(job.corpusId(),job.jobId());}
    assertTrue(job.terminal(),"Indexing did not finish");return job;
  }
  @Test void isolatesSameFilenamesAndBothRetrievalChannelsAcrossCorpora() throws Exception {
    Corpus alpha=service.create("Alpha papers"),beta=service.create("Beta papers");
    var first=service.upload(alpha.id(),List.of(pdf("paper.pdf"))).uploaded().getFirst();
    var second=service.upload(beta.id(),List.of(pdf("paper.pdf"))).uploaded().getFirst();
    assertNotEquals(service.pdf(alpha.id(),first.id()),service.pdf(beta.id(),second.id()));
    assertEquals("COMPLETED",await(service.process(alpha.id())).stage());
    assertEquals("COMPLETED",await(service.process(beta.id())).stage());
    Version a=service.selection(alpha.id().toString(),null),b=service.selection(beta.id().toString(),null);
    assertNotEquals(a.tableName(),b.tableName());
    var vector=stores.get(a.tableName()).similaritySearch(SearchRequest.builder().query("alpha").topK(5).build());
    assertEquals(1,vector.size());assertEquals(alpha.id().toString(),vector.getFirst().getMetadata().get("corpusId"));
    var keywords=new KeywordChunkSearchService(db,schema,a.tableName()).search("research",5);
    assertEquals(1,keywords.size());assertEquals(first.id().toString(),keywords.getFirst().documentId());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.selection(alpha.id().toString(),b.id().toString()));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.pdf(alpha.id(),second.id()));
  }
  @Test void failedRebuildRetainsActiveIndexAndSuccessfulRebuildCanBeRolledBack() throws Exception {
    Corpus corpus=service.create("Rebuild test");service.upload(corpus.id(),List.of(pdf("alpha.pdf")));
    Job original=await(service.process(corpus.id()));assertEquals("COMPLETED",original.stage());
    UUID active=repo.corpus(corpus.id()).activeVersion();
    embeddingFails.set(true);Job failed=await(service.process(corpus.id()));assertEquals("FAILED",failed.stage());
    assertEquals(active,repo.corpus(corpus.id()).activeVersion());
    assertEquals(1,repo.versions(corpus.id()).size());
    embeddingFails.set(false);assertEquals("COMPLETED",await(service.process(corpus.id())).stage());
    assertNotEquals(active,repo.corpus(corpus.id()).activeVersion());
    assertEquals(2,repo.versions(corpus.id()).size());
    repo.activate(corpus.id(),active);assertEquals(active,service.selection(corpus.id().toString(),null).id());
  }
  @Test void rejectsInvalidUploadsAndDeduplicatesContent() throws Exception {
    Corpus corpus=service.create("Upload validation");
    MockMultipartFile good=pdf("paper.pdf");service.upload(corpus.id(),List.of(good));
    var duplicate=service.upload(corpus.id(),List.of(new MockMultipartFile("files","copy.pdf","application/pdf",good.getBytes())));
    assertTrue(duplicate.uploaded().isEmpty());assertEquals(1,duplicate.documentCount());
    assertEquals("copy.pdf",duplicate.duplicates().getFirst().sourceFile());
    assertEquals("paper.pdf",duplicate.duplicates().getFirst().existingSourceFile());
    assertEquals(1,repo.papers(corpus.id()).size());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.upload(corpus.id(),List.of(new MockMultipartFile("files","bad.pdf","application/pdf","not a PDF".getBytes()))));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.upload(corpus.id(),List.of(pdf("../paper.pdf"))));
    assertEquals(1,repo.papers(corpus.id()).size());
  }
  @Test void addingAThirdPaperRebuildsAllThreeAndRetainsTheTwoPaperVersion() throws Exception {
    Corpus corpus=service.create("Add third paper");
    var initial=service.upload(corpus.id(),List.of(pdf("first.pdf"),pdf("second.pdf")));
    assertEquals(2,initial.uploaded().size());assertEquals(2,initial.documentCount());
    assertEquals("COMPLETED",await(service.process(corpus.id())).stage());
    UUID previous=repo.corpus(corpus.id()).activeVersion();
    var third=service.upload(corpus.id(),List.of(pdf("third.pdf")));
    assertEquals(1,third.uploaded().size());assertEquals(3,third.documentCount());
    assertTrue(service.view(repo.corpus(corpus.id())).pendingChanges());
    Job indexed=await(service.process(corpus.id()));
    assertEquals("COMPLETED",indexed.stage());assertEquals(3,indexed.total());assertEquals(3,indexed.completed());
    Version active=service.selection(corpus.id().toString(),null);
    assertEquals(3,active.documents().size());assertEquals(3,active.chunkCount());
    assertTrue(active.documents().stream().anyMatch(p->p.id().equals(third.uploaded().getFirst().id())));
    assertEquals(3,db.queryForObject("SELECT count(*) FROM "+stores.qualified(active.tableName()),Integer.class));
    assertEquals(2,repo.version(corpus.id(),previous).documents().size());
    assertFalse(service.view(repo.corpus(corpus.id())).pendingChanges());
  }
  @Test void routesTheSharedHybridPipelineAndPreservesResearchPageCitations() throws Exception {
    Corpus corpus=service.create("Shared retrieval");service.upload(corpus.id(),List.of(pdf("alpha.pdf")));await(service.process(corpus.id()));
    var properties=new com.example.documentassistant.retrieval.RetrievalProperties(5,40,0,60,12,2,40,30,40,70000);
    var legacy=org.mockito.Mockito.mock(org.springframework.ai.vectorstore.VectorStore.class);
    var vector=new com.example.documentassistant.retrieval.ChunkRetrievalService(legacy,properties);
    org.springframework.test.util.ReflectionTestUtils.setField(vector,"corpora",service);
    org.springframework.test.util.ReflectionTestUtils.setField(vector,"corpusStores",stores);
    var legacyKeywords=org.mockito.Mockito.mock(KeywordChunkSearchService.class);
    var hybrid=new com.example.documentassistant.retrieval.HybridCandidateService(vector,legacyKeywords,properties);
    org.springframework.test.util.ReflectionTestUtils.setField(hybrid,"corpora",service);
    org.springframework.test.util.ReflectionTestUtils.setField(hybrid,"corpusStores",stores);
    org.springframework.test.util.ReflectionTestUtils.setField(hybrid,"corpusJdbc",db);
    // A generator-like token must not impose a generator model filter on research papers.
    var response=hybrid.search(new com.example.documentassistant.retrieval.RetrievalRequest("G007144-0 research",5,corpus.id().toString()));
    assertEquals(1,response.candidates().size());
    assertEquals(List.of(1),response.candidates().getFirst().pageNumbers());
    assertTrue(response.candidates().getFirst().retrievalChannels().containsAll(List.of("vector","keyword")));
    org.mockito.Mockito.verifyNoInteractions(legacy,legacyKeywords);
  }
  Benchmark benchmark(Version version) throws IOException {
    UUID id=UUID.randomUUID();
    Benchmark benchmark=new Benchmark(id,version.corpusId(),version.id(),"APPROVED",repo.tree("[]"),java.time.Instant.now());repo.createBenchmark(benchmark);
    Path path=directory.resolve("storage").resolve(version.corpusId().toString()).resolve("benchmarks").resolve(id.toString());Files.createDirectories(path);Files.writeString(path.resolve("questions.json"),"fixture");
    return benchmark;
  }
  Job evaluation(Benchmark benchmark) throws IOException {
    UUID id=UUID.randomUUID();
    Job job=new Job(id,benchmark.corpusId(),"EVALUATION","COMPLETED",true,"Done",null,1,1,0,java.time.Instant.now(),java.time.Instant.now(),benchmark.id());
    repo.insertJob(job);repo.saveJob(job);repo.addEvaluation(id,job.corpusId(),benchmark.id(),Map.of("versionId",benchmark.versionId()));
    Path path=directory.resolve("storage").resolve(job.corpusId().toString()).resolve("evaluations").resolve(id.toString());Files.createDirectories(path);Files.writeString(path.resolve("report.json"),"fixture");
    return job;
  }
  @Test void removingAnIndexedPaperPreservesOtherVectorsAndUnaffectedHistoryWithoutEmbedding() throws Exception {
    Corpus corpus=service.create("Remove indexed paper");
    Paper alpha=service.upload(corpus.id(),List.of(pdf("alpha.pdf"))).uploaded().getFirst();await(service.process(corpus.id()));
    Version before=service.selection(corpus.id().toString(),null);Benchmark safe=benchmark(before);Job safeRun=evaluation(safe);
    Paper beta=service.upload(corpus.id(),List.of(pdf("beta.pdf"))).uploaded().getFirst();await(service.process(corpus.id()));
    Version both=service.selection(corpus.id().toString(),null);Benchmark affected=benchmark(both);Job affectedRun=evaluation(affected);
    embeddingFails.set(true);
    var result=service.deleteDocument(corpus.id(),beta.id());
    embeddingFails.set(false);
    assertEquals(1,result.corpus().documentCount());assertEquals(1,result.corpus().chunkCount());assertFalse(result.corpus().pendingChanges());
    assertEquals(1,result.removedVersions());assertEquals(1,result.removedBenchmarks());assertEquals(1,result.removedEvaluations());assertFalse(result.cleanupPending());
    Version active=service.selection(corpus.id().toString(),null);assertNotEquals(both.id(),active.id());assertEquals(List.of(alpha),active.documents());
    var vector=stores.get(active.tableName()).similaritySearch(SearchRequest.builder().query("research").topK(10).build());
    assertEquals(1,vector.size());assertEquals(alpha.id().toString(),vector.getFirst().getMetadata().get("documentId"));assertEquals(active.id().toString(),vector.getFirst().getMetadata().get("versionId"));
    assertTrue(new KeywordChunkSearchService(db,schema,active.tableName()).search("beta",5).isEmpty());
    assertEquals(alpha.id().toString(),service.chunks(active).getFirst().documentId());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.pdf(corpus.id(),beta.id()));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.activate(corpus.id(),both.id()));
    assertNull(db.queryForObject("SELECT to_regclass(?)",String.class,stores.qualified(both.tableName())));
    assertEquals(safe.id(),repo.benchmarks(corpus.id()).getFirst().id());assertEquals(safeRun.jobId(),repo.job(corpus.id(),safeRun.jobId()).jobId());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->repo.job(corpus.id(),affectedRun.jobId()));
    Path root=directory.resolve("storage").resolve(corpus.id().toString());
    assertFalse(Files.exists(root.resolve("pdfs").resolve(beta.id()+".pdf")));
    assertFalse(Files.exists(root.resolve("processed").resolve(beta.id()+"-hybrid-512.json")));
    assertFalse(Files.exists(root.resolve("benchmarks").resolve(affected.id().toString())));
    assertFalse(Files.exists(root.resolve("evaluations").resolve(affectedRun.jobId().toString())));
    assertTrue(Files.isRegularFile(service.pdf(corpus.id(),alpha.id())));
    assertEquals(before.id(),service.activate(corpus.id(),before.id()).activeVersion());
  }
  @Test void removingAPendingPaperLeavesTheActiveIndexAndClearsPartialSnapshots() throws Exception {
    Corpus corpus=service.create("Remove pending");service.upload(corpus.id(),List.of(pdf("alpha.pdf")));await(service.process(corpus.id()));
    UUID active=repo.corpus(corpus.id()).activeVersion();
    Paper pending=service.upload(corpus.id(),List.of(pdf("pending.pdf"))).uploaded().getFirst();
    UUID failedVersion=UUID.randomUUID();Job failed=new Job(UUID.randomUUID(),corpus.id(),"INDEX","FAILED",true,"Failed",null,0,2,0,java.time.Instant.now(),java.time.Instant.now(),failedVersion);repo.insertJob(failed);repo.saveJob(failed);
    String staging="corpus_"+failedVersion.toString().replace("-","");stores.create(staging);
    Path partial=service.versionDirectory(corpus.id(),failedVersion);Files.createDirectories(partial);Files.writeString(partial.resolve("private-text.txt"),"fixture");
    var result=service.deleteDocument(corpus.id(),pending.id());
    assertEquals(active,result.corpus().activeVersion());assertEquals(0,result.removedVersions());assertFalse(result.corpus().pendingChanges());
    assertFalse(Files.exists(partial));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->repo.job(corpus.id(),failed.jobId()));
    assertNull(db.queryForObject("SELECT to_regclass(?)",String.class,stores.qualified(staging)));
  }
  @Test void removingTheLastPaperResetsToAnEmptyCorpus() throws Exception {
    Corpus corpus=service.create("Empty after removal");Paper paper=service.upload(corpus.id(),List.of(pdf("alpha.pdf"))).uploaded().getFirst();await(service.process(corpus.id()));
    var result=service.deleteDocument(corpus.id(),paper.id());
    assertNull(result.corpus().activeVersion());assertEquals(0,result.corpus().documentCount());assertEquals(0,result.corpus().chunkCount());assertTrue(repo.versions(corpus.id()).isEmpty());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.selection(corpus.id().toString(),null));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.process(corpus.id()));
  }
  @Test void deletingACorpusPurgesAllItsTablesFilesAndDependentsWhilePreservingOtherCorpora() throws Exception {
    Corpus doomed=service.create("Disposable"),safe=service.create("Keep");
    Paper keep=service.upload(safe.id(),List.of(pdf("alpha.pdf"))).uploaded().getFirst();await(service.process(safe.id()));
    service.upload(doomed.id(),List.of(pdf("alpha.pdf")));await(service.process(doomed.id()));await(service.process(doomed.id()));
    List<Version> versions=repo.versions(doomed.id());evaluation(benchmark(versions.getFirst()));
    UUID interrupted=UUID.randomUUID();String staging="corpus_"+interrupted.toString().replace("-","");stores.create(staging);
    Job stopped=new Job(UUID.randomUUID(),doomed.id(),"INDEX","INTERRUPTED",true,"Interrupted",null,0,1,0,java.time.Instant.now(),java.time.Instant.now(),interrupted);repo.insertJob(stopped);repo.saveJob(stopped);
    assertFalse(service.deleteCorpus(doomed.id()).cleanupPending());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->repo.corpus(doomed.id()));
    for(String table:List.of("rag_documents","rag_versions","rag_jobs","rag_benchmarks","rag_evaluations")) assertEquals(0,db.queryForObject("SELECT count(*) FROM "+table+" WHERE corpus_id=?",Integer.class,doomed.id()));
    for(Version version:versions) assertNull(db.queryForObject("SELECT to_regclass(?)",String.class,stores.qualified(version.tableName())));
    assertNull(db.queryForObject("SELECT to_regclass(?)",String.class,stores.qualified(staging)));
    assertFalse(Files.exists(directory.resolve("storage").resolve(doomed.id().toString())));
    assertTrue(Files.isRegularFile(service.pdf(safe.id(),keep.id())));assertEquals(1,service.view(repo.corpus(safe.id())).chunkCount());
    assertTrue(repo.fileCleanups().isEmpty());
  }
  @Test void deletionRejectsCrossCorpusDocumentsAndAllKindsOfActiveJobs() throws Exception {
    Corpus a=service.create("A"),b=service.create("B");Paper paper=service.upload(a.id(),List.of(pdf("alpha.pdf"))).uploaded().getFirst();await(service.process(a.id()));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.deleteDocument(b.id(),paper.id()));
    for(String kind:List.of("INDEX","BENCHMARK","EVALUATION")) {
      Job job=new Job(UUID.randomUUID(),a.id(),kind,"QUEUED",false,"Active",null,0,1,0,java.time.Instant.now(),null,UUID.randomUUID());repo.insertJob(job);
      assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.deleteDocument(a.id(),paper.id())).getStatusCode().value());
      assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.deleteCorpus(a.id())).getStatusCode().value());
      assertTrue(Files.isRegularFile(service.pdf(a.id(),paper.id())));repo.saveJob(job.progress("FAILED","Fixture complete",true,null,0,1,0));
    }
    assertEquals(1,service.view(repo.corpus(a.id())).chunkCount());assertEquals(0,repo.papers(b.id()).size());
  }
  @Test void removalAfterRollbackPurgesTheInactiveVersionWithoutChangingTheActiveIndex() throws Exception {
    Corpus corpus=service.create("Removal after rollback");service.upload(corpus.id(),List.of(pdf("alpha.pdf")));await(service.process(corpus.id()));
    UUID original=repo.corpus(corpus.id()).activeVersion();Paper beta=service.upload(corpus.id(),List.of(pdf("beta.pdf"))).uploaded().getFirst();await(service.process(corpus.id()));
    UUID newer=repo.corpus(corpus.id()).activeVersion();service.activate(corpus.id(),original);
    var result=service.deleteDocument(corpus.id(),beta.id());
    assertEquals(original,result.corpus().activeVersion());assertFalse(result.corpus().pendingChanges());assertEquals(1,result.removedVersions());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.activate(corpus.id(),newer));
  }
  @Test void failedDatabaseDeletionRollsBackRegistryVectorsAndFileCleanup() throws Exception {
    Corpus corpus=service.create("Rollback deletion");Paper paper=service.upload(corpus.id(),List.of(pdf("alpha.pdf"),pdf("beta.pdf"))).uploaded().getFirst();await(service.process(corpus.id()));
    Version active=service.selection(corpus.id().toString(),null);Benchmark benchmark=benchmark(active);Job evaluation=evaluation(benchmark);
    db.execute("CREATE FUNCTION "+schema+".reject_document_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Simulated delete failure'; END; $$");
    db.execute("CREATE TRIGGER reject_document_delete BEFORE DELETE ON rag_documents FOR EACH ROW EXECUTE FUNCTION "+schema+".reject_document_delete()");
    assertThrows(org.springframework.dao.DataAccessException.class,()->service.deleteDocument(corpus.id(),paper.id()));
    assertEquals(active.id(),repo.corpus(corpus.id()).activeVersion());assertEquals(2,repo.papers(corpus.id()).size());assertEquals(1,repo.versions(corpus.id()).size());
    assertEquals(2,db.queryForObject("SELECT count(*) FROM "+stores.qualified(active.tableName()),Integer.class));
    assertTrue(Files.isRegularFile(service.pdf(corpus.id(),paper.id())));assertEquals(evaluation.jobId(),repo.job(corpus.id(),evaluation.jobId()).jobId());assertTrue(repo.fileCleanups().isEmpty());
  }
  @Test void queuedFileCleanupSurvivesRestartAndDoesNotFollowLinksOrTouchSharedCaches() throws Exception {
    Path root=directory.resolve("storage"),corpus=root.resolve(UUID.randomUUID().toString());Files.createDirectories(corpus);
    Path outside=directory.resolve("outside.txt");Files.writeString(outside,"keep");Files.createSymbolicLink(corpus.resolve("link"),outside);
    Path models=root.resolve("model-cache");Files.createDirectories(models);Files.writeString(models.resolve("keep.txt"),"keep");
    repo.queueFileCleanup(List.of(root.relativize(corpus).toString()));
    var restarted=new CorpusFileCleanup(repo,new CorpusProcesses(directory.toString(),"storage","python","test",30));restarted.drain();restarted.close();
    assertFalse(Files.exists(corpus));assertEquals("keep",Files.readString(outside));assertTrue(Files.exists(models.resolve("keep.txt")));assertTrue(repo.fileCleanups().isEmpty());
    assertThrows(IllegalArgumentException.class,()->cleanup.queue(List.of(root,models,outside)));
  }
  @Test void inaccessibleFilesStayQueuedUntilCleanupCanRetry() throws Exception {
    Path folder=directory.resolve("storage").resolve(UUID.randomUUID().toString()).resolve("locked");Files.createDirectories(folder);
    Path file=folder.resolve("paper.pdf");Files.writeString(file,"fixture");cleanup.queue(List.of(file));
    var permissions=Files.getPosixFilePermissions(folder);
    try {
      Files.setPosixFilePermissions(folder,Set.of());
      cleanup.drain();assertFalse(repo.fileCleanups().isEmpty());
    } finally {Files.setPosixFilePermissions(folder,permissions);}
    cleanup.drain();assertFalse(Files.exists(file));assertTrue(repo.fileCleanups().isEmpty());
  }
  @Test void preservesApprovedBenchmarksAndRejectsUnsupportedSourceIds() throws Exception {
    Corpus corpus=service.create("Benchmark validation");service.upload(corpus.id(),List.of(pdf("paper.pdf")));await(service.process(corpus.id()));
    Version version=service.selection(corpus.id().toString(),null);String chunk=version.documents().getFirst().id()+":0";
    var processes=new CorpusProcesses(directory.toString(),"storage","python","test",30);
    var evaluation=new CorpusEvaluationService(repo,service,processes,new com.example.documentassistant.retrieval.RetrievalProperties(5,40,0,60,12,2,40,30,40,70000),new DataSourceTransactionManager(db.getDataSource()),"judge","answer","http://localhost:8081","http://localhost/judge");
    var dataset=repo.tree(repo.encode(List.of(Map.of("id","q1","question","What was found?","referenceAnswer","alpha research findings","requiredClaims",List.of("alpha research findings"),"sourceChunkIds",List.of(chunk)))));
    UUID id=UUID.randomUUID();repo.createBenchmark(new Benchmark(id,corpus.id(),version.id(),"DRAFT",dataset,java.time.Instant.now()));
    // Retrying completed draft registration is idempotent after an interrupted job.
    repo.createBenchmark(new Benchmark(id,corpus.id(),version.id(),"DRAFT",dataset,java.time.Instant.now()));
    assertEquals("APPROVED",evaluation.approve(corpus.id(),id).state());
    Benchmark revised=evaluation.revise(corpus.id(),id,dataset);assertNotEquals(id,revised.id());assertEquals("DRAFT",revised.state());assertEquals("APPROVED",repo.benchmark(corpus.id(),id).state());
    var unsupported=repo.tree(dataset.toString().replace(chunk,"unknown:0"));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->evaluation.validateDataset(version,unsupported));
    await(service.process(corpus.id()));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->evaluation.approve(corpus.id(),revised.id()));
    evaluation.close();processes.close();
  }
}
