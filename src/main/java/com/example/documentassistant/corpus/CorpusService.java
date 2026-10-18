package com.example.documentassistant.corpus;

import com.example.documentassistant.corpus.CorpusRepository.*;
import com.example.documentassistant.document.model.*;
import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class CorpusService {
  public static final String LEGACY = "specifications";
  private final CorpusRepository repo;
  private final CorpusProcesses processes;
  private final CorpusVectorStores stores;
  private final CorpusFileCleanup cleanup;
  private final JdbcTemplate db;
  private final TransactionTemplate transaction;
  private final String embeddingModel;
  private final int dimensions;
  private final long maxBytes;
  private final int maxFiles;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("corpus-processing").factory());

  public CorpusService(CorpusRepository repo, CorpusProcesses processes, CorpusVectorStores stores, CorpusFileCleanup cleanup, JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager transactions,
      @Value("${spring.ai.openai.embedding.model}") String embeddingModel,
      @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}") int dimensions,
      @Value("${document-assistant.corpora.max-file-bytes:52428800}") long maxBytes,
      @Value("${document-assistant.corpora.max-upload-files:100}") int maxFiles) {
    this.repo=repo;this.processes=processes;this.stores=stores;this.cleanup=cleanup;this.db=db;this.transaction=new TransactionTemplate(transactions);
    this.embeddingModel=embeddingModel;this.dimensions=dimensions;this.maxBytes=maxBytes;this.maxFiles=maxFiles;
  }
  public record View(String id, String name, String profile, UUID activeVersion, boolean pendingChanges, int documentCount, int chunkCount) {}
  public View view(Corpus corpus) {
    List<Paper> pending=repo.papers(corpus.id());
    Version active=corpus.activeVersion()==null?null:repo.version(corpus.id(),corpus.activeVersion());
    return new View(corpus.id().toString(),corpus.name(),"research",corpus.activeVersion(),active==null || !active.documents().equals(pending),pending.size(),active==null?0:active.chunkCount());
  }
  public Corpus create(String name) {
    if (name==null || name.isBlank() || name.length()>120) throw invalid("Corpus name must contain 1–120 characters");
    return repo.create(name.trim());
  }
  private Path pdfPath(UUID corpus, UUID document) {
    repo.corpus(corpus);
    if (repo.papers(corpus).stream().noneMatch(p -> p.id().equals(document))) throw CorpusRepository.missing("Document");
    return processes.corpus(corpus).resolve("pdfs").resolve(document + ".pdf");
  }
  public Path pdf(UUID corpus,UUID document) throws IOException {return processes.artifacts().require(pdfPath(corpus,document));}
  public boolean pdfAvailable(UUID corpus,UUID document) throws IOException {return processes.artifacts().exists(pdfPath(corpus,document));}
  public record DuplicateUpload(String sourceFile, UUID existingDocumentId, String existingSourceFile) {}
  public record UploadResult(List<Paper> uploaded, List<DuplicateUpload> duplicates, int documentCount) {}

  public synchronized UploadResult upload(UUID corpus, List<MultipartFile> files) throws IOException {
    repo.corpus(corpus);repo.assertIdle(corpus);
    if(files==null || files.isEmpty() || files.size()>maxFiles) throw invalid("Select 1–"+maxFiles+" PDFs");
    Path directory=processes.corpus(corpus).resolve("pdfs");Files.createDirectories(directory);
    List<DuplicateUpload> duplicates=new ArrayList<>();
    // Validate the complete upload before making any paper visible in the registry.
    Map<Paper,Path> staged=new LinkedHashMap<>();
    Map<String,Paper> checksums=new HashMap<>();repo.papers(corpus).forEach(p -> checksums.put(p.checksum(),p));
    try {
      for(MultipartFile file:files) {
        String name=file.getOriginalFilename();
        if(name==null || name.isBlank() || name.length()>250 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl) || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) throw invalid("Each file must have a PDF filename without path components");
        if(file.isEmpty() || file.getSize()>maxBytes) throw invalid("PDFs must be nonempty and at most "+maxBytes/1024/1024+" MiB each");
        Path temp=Files.createTempFile(directory,"upload-",".tmp");
        try {
          MessageDigest digest=MessageDigest.getInstance("SHA-256");
          try(InputStream input=new DigestInputStream(file.getInputStream(),digest);OutputStream output=Files.newOutputStream(temp)) {
            long copied=input.transferTo(output);
            if(copied>maxBytes) throw invalid("PDF exceeds the upload limit");
          }
          int pages;
          try(var pdf=Loader.loadPDF(temp.toFile())) {
            if(pdf.isEncrypted() || pdf.getNumberOfPages()<1) throw invalid("Encrypted or empty PDFs cannot be processed");
            pages=pdf.getNumberOfPages();
          } catch(ResponseStatusException e) {throw e;} catch(IOException e) {throw invalid("Invalid or unreadable PDF: "+name);}
          String hash=HexFormat.of().formatHex(digest.digest());
          Paper existing=checksums.get(hash);
          if(existing!=null) {duplicates.add(new DuplicateUpload(name,existing.id(),existing.sourceFile()));Files.deleteIfExists(temp);continue;}
          Paper paper=new Paper(UUID.randomUUID(),name,hash,pages);
          checksums.put(hash,paper);staged.put(paper,temp);
        } catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
        finally {if(staged.values().stream().noneMatch(temp::equals)) Files.deleteIfExists(temp);}
      }
      for(var entry:staged.entrySet()) Files.move(entry.getValue(),directory.resolve(entry.getKey().id()+".pdf"));
      // Do not register a PDF until its durable copy exists.
      for(Paper paper:staged.keySet()) processes.artifacts().publish(directory.resolve(paper.id()+".pdf"));
      transaction.executeWithoutResult(status -> staged.keySet().forEach(p -> repo.addPaper(corpus,p)));
      return new UploadResult(List.copyOf(staged.keySet()),List.copyOf(duplicates),repo.papers(corpus).size());
    } finally {
      for(var entry:staged.entrySet()) {
        Files.deleteIfExists(entry.getValue());
        if(repo.papers(corpus).stream().noneMatch(p -> p.id().equals(entry.getKey().id()))) {
          cleanup.queue(List.of(directory.resolve(entry.getKey().id()+".pdf")));cleanup.drain();
        }
      }
    }
  }
  public synchronized Job process(UUID corpus) {
    repo.corpus(corpus); List<Paper> papers=repo.papers(corpus);
    if(papers.isEmpty()) throw invalid("Upload PDFs before processing");
    UUID version=UUID.randomUUID();
    Job job=new Job(UUID.randomUUID(),corpus,"INDEX","QUEUED",false,"Waiting to process PDFs",null,0,papers.size(),0,Instant.now(),null,version);
    repo.insertJob(job);
    worker.submit(() -> index(job,papers));return job;
  }
  public synchronized View activate(UUID corpus,UUID version) {
    repo.assertIdle(corpus);selection(corpus.toString(),version.toString());repo.activate(corpus,version);return view(repo.corpus(corpus));
  }
  public record RemovalResult(View corpus,String removedDocument,int removedVersions,int removedBenchmarks,int removedEvaluations,boolean cleanupPending) {}
  public record DeletedCorpus(String name,boolean cleanupPending) {}

  public synchronized DeletedCorpus deleteCorpus(UUID corpus) {
    Corpus record=repo.corpus(corpus);repo.assertIdle(corpus);
    List<Version> versions=repo.versions(corpus);
    Set<String> tables=new LinkedHashSet<>();versions.forEach(v->tables.add(v.tableName()));
    repo.jobs(corpus).stream().filter(j->j.kind().equals("INDEX")).forEach(j->tables.add("corpus_"+j.referenceId().toString().replace("-","")));
    transaction.executeWithoutResult(status->{
      tables.forEach(stores::discard);
      repo.removeCorpus(corpus);cleanup.queue(List.of(processes.corpus(corpus)));
    });
    cleanup.drain();return new DeletedCorpus(record.name(),!repo.fileCleanups().isEmpty());
  }

  public synchronized RemovalResult deleteDocument(UUID corpus,UUID document) throws IOException {
    Corpus record=repo.corpus(corpus);repo.assertIdle(corpus);
    Paper paper=repo.papers(corpus).stream().filter(p->p.id().equals(document)).findFirst().orElseThrow(()->CorpusRepository.missing("Document"));
    List<Version> versions=repo.versions(corpus);
    List<Version> affected=versions.stream().filter(v->v.documents().stream().anyMatch(p->p.id().equals(document))).toList();
    Set<UUID> affectedIds=new HashSet<>();affected.forEach(v->affectedIds.add(v.id()));
    List<Benchmark> benchmarks=repo.benchmarks(corpus).stream().filter(b->affectedIds.contains(b.versionId())).toList();
    Set<UUID> benchmarkIds=new HashSet<>();benchmarks.forEach(b->benchmarkIds.add(b.id()));
    Set<UUID> knownVersions=new HashSet<>();versions.forEach(v->knownVersions.add(v.id()));
    List<Job> jobs=new ArrayList<>();
    Set<Path> paths=new LinkedHashSet<>();Path root=processes.corpus(corpus);
    paths.add(root.resolve("pdfs").resolve(document+".pdf"));
    Path processed=root.resolve("processed");
    processes.artifacts().restore(processed);
    if(Files.isDirectory(processed)) try(var files=Files.list(processed)) {files.filter(p->p.getFileName().toString().startsWith(document+"-")).forEach(paths::add);}
    affected.forEach(v->paths.add(versionDirectory(corpus,v.id())));
    benchmarks.forEach(b->paths.add(root.resolve("benchmarks").resolve(b.id().toString())));
    for(Job job:repo.jobs(corpus)) {
      boolean remove=switch(job.kind()) {
        // Failed/interrupted index snapshots may also contain this paper's text.
        case "INDEX" -> affectedIds.contains(job.referenceId()) || !knownVersions.contains(job.referenceId());
        case "EVALUATION" -> benchmarkIds.contains(job.referenceId()) || affectedIds.stream().anyMatch(v->v.toString().equals(repo.evaluationConfig(job.jobId()).path("versionId").asText()));
        case "BENCHMARK" -> benchmarkIds.contains(job.referenceId()) || benchmarkUsesVersion(root,job,affectedIds);
        default -> false;
      };
      if(remove) {
        jobs.add(job);
        paths.add(switch(job.kind()) {
          case "INDEX" -> versionDirectory(corpus,job.referenceId());
          case "BENCHMARK" -> root.resolve("benchmarks").resolve(job.referenceId().toString());
          default -> root.resolve("evaluations").resolve(job.jobId().toString());
        });
      }
    }
    Version active=record.activeVersion()==null?null:repo.version(corpus,record.activeVersion());
    boolean replace=active!=null && affectedIds.contains(active.id());
    List<Paper> retained=replace?active.documents().stream().filter(p->!p.id().equals(document)).toList():List.of();
    UUID replacement=replace && !retained.isEmpty()?UUID.randomUUID():null;
    Path replacementDirectory=replacement==null?null:versionDirectory(corpus,replacement);
    try {
      transaction.executeWithoutResult(status->{
        if(replacement!=null) {
          String table="corpus_"+replacement.toString().replace("-","");stores.create(table);
          // Copy stored vectors; deleting a paper must not make embedding API calls.
          db.update("INSERT INTO "+stores.qualified(table)+" (id,content,metadata,embedding) SELECT id,content,metadata::jsonb || jsonb_build_object('versionId',?::text),embedding FROM "+stores.qualified(active.tableName())+" WHERE metadata->>'documentId'<>?",replacement.toString(),document.toString());
          int count=db.queryForObject("SELECT count(*) FROM "+stores.qualified(table),Integer.class);
          try {
            Path chunks=replacementDirectory.resolve("chunks");Files.createDirectories(chunks);
            processes.artifacts().restore(versionDirectory(corpus,active.id()).resolve("chunks"));
            for(Paper keep:retained) Files.copy(versionDirectory(corpus,active.id()).resolve("chunks").resolve(keep.id()+"-chunks.json"),chunks.resolve(keep.id()+"-chunks.json"));
            processes.artifacts().publish(replacementDirectory);
          } catch(IOException e) {throw new UncheckedIOException(e);}
          repo.saveVersion(new Version(replacement,corpus,table,retained,active.config(),count,Instant.now()));
        }
        if(replace) repo.activate(corpus,replacement);
        // An interrupted build can have a pgvector staging table but no saved version.
        jobs.stream().filter(j->j.kind().equals("INDEX") && !knownVersions.contains(j.referenceId()))
            .forEach(j->stores.discard("corpus_"+j.referenceId().toString().replace("-","")));
        jobs.forEach(j->repo.removeJob(corpus,j.jobId()));
        for(Version version:affected) {stores.discard(version.tableName());repo.removeVersion(corpus,version.id());}
        repo.removePaper(corpus,document);
        if(replacement!=null) {
          int count=repo.version(corpus,replacement).chunkCount();
          Job removed=new Job(UUID.randomUUID(),corpus,"INDEX","COMPLETED",true,"Removed "+paper.sourceFile()+"; remaining indexed papers are ready",null,retained.size(),retained.size(),count,Instant.now(),Instant.now(),replacement);
          repo.insertJob(removed);repo.saveJob(removed);
        }
        cleanup.queue(paths);
      });
    } catch(RuntimeException e) {
      if(replacement!=null) {
        stores.discard("corpus_"+replacement.toString().replace("-",""));
        cleanup.queue(List.of(replacementDirectory));cleanup.drain();
      }
      if(e instanceof UncheckedIOException io) throw io.getCause();
      throw e;
    }
    cleanup.drain();
    return new RemovalResult(view(repo.corpus(corpus)),paper.sourceFile(),affected.size(),benchmarks.size(),(int)jobs.stream().filter(j->j.kind().equals("EVALUATION")).count(),!repo.fileCleanups().isEmpty());
  }
  private boolean benchmarkUsesVersion(Path root,Job job,Set<UUID> versions) throws IOException {
    Path request=root.resolve("benchmarks").resolve(job.referenceId().toString()).resolve("request.json");
    if(processes.artifacts().exists(request)) processes.artifacts().require(request);
    // A missing request on an incomplete draft cannot establish safe provenance.
    if(!Files.isRegularFile(request)) return !versions.isEmpty();
    String version=repo.tree(Files.readString(request)).path("versionId").asText();
    return versions.stream().anyMatch(id->id.toString().equals(version));
  }
  private void index(Job initial,List<Paper> papers) {
    String table="corpus_"+initial.referenceId().toString().replace("-","");
    Path directory=versionDirectory(initial.corpusId(),initial.referenceId());
    Job job=initial;
    boolean activated=false;
    try {
      processes.artifacts().restore(processes.corpus(initial.corpusId()).resolve("processed"));
      Path chunks=directory.resolve("chunks");Files.createDirectories(chunks);
      List<ChunkedDocument> documents=new ArrayList<>();int totalChunks=0;
      for(Paper paper:papers) {
        job=job.progress("EXTRACTING_AND_CHUNKING","Docling is processing "+paper.sourceFile(),false,paper.sourceFile(),documents.size(),papers.size(),totalChunks);repo.saveJob(job);
        Path cache=processes.corpus(initial.corpusId()).resolve("processed").resolve(paper.id()+"-hybrid-512.json");
        Path legacyCache=cache.resolveSibling(paper.id()+"-512-false.json");
        if(!Files.isRegularFile(cache) && Files.isRegularFile(legacyCache)) Files.copy(legacyCache,cache);
        if(!Files.isRegularFile(cache)) {
          try {processes.execute("process_paper.py",List.of("--input",pdf(initial.corpusId(),paper.id()).toString(),"--output",cache.toString(),"--document-id",paper.id().toString(),"--source-file",paper.sourceFile(),"--max-tokens","512"),directory.resolve(paper.id()+"-docling.log"),Map.of());}
          finally {processes.artifacts().publish(cache.getParent());processes.artifacts().publish(directory);}
        }
        ChunkedDocument document=repo.decode(Files.readString(cache),ChunkedDocument.class);
        validate(paper,document);
        Files.copy(cache,chunks.resolve(paper.id()+"-chunks.json"),StandardCopyOption.REPLACE_EXISTING);
        documents.add(document);totalChunks+=document.chunkCount();
      }
      job=job.progress("EMBEDDING","Embedding and storing the complete document collection",false,null,0,papers.size(),totalChunks);repo.saveJob(job);
      var store=stores.create(table);
      int completed=0;
      for(ChunkedDocument source:documents) {
        List<Document> vectors=new ArrayList<>();
        for(DocumentChunk chunk:source.chunks()) {
          Map<String,Object> metadata=new LinkedHashMap<>();
          metadata.put("corpusId",initial.corpusId().toString());metadata.put("versionId",initial.referenceId().toString());
          metadata.put("documentId",source.documentId());metadata.put("sourceFile",source.sourceFile());metadata.put("chunkIndex",chunk.chunkIndex());metadata.put("section",chunk.section());
          metadata.put("modelNumbers",List.of());metadata.put("pageNumbers",chunk.pageNumbers());metadata.put("sourceElementIds",chunk.sourceElementIds());
          String identity=initial.corpusId()+":"+initial.referenceId()+":"+source.documentId()+":"+chunk.chunkIndex();
          vectors.add(Document.builder().id(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString()).text(chunk.text()).metadata(metadata).build());
        }
        // Bound provider requests as well as JDBC inserts.
        for(int offset=0;offset<vectors.size();offset+=32) {
          int end=Math.min(vectors.size(),offset+32);
          job=job.progress("EMBEDDING","Embedding "+source.sourceFile()+" (chunks "+(offset+1)+"–"+end+" of "+vectors.size()+")",false,source.sourceFile(),completed,papers.size(),totalChunks);
          repo.saveJob(job);
          store.add(vectors.subList(offset,end));
        }
        job=job.progress("EMBEDDING","Stored "+source.sourceFile(),false,source.sourceFile(),++completed,papers.size(),totalChunks);repo.saveJob(job);
      }
      Integer count=db.queryForObject("SELECT count(*) FROM "+stores.qualified(table),Integer.class);
      if(count==null || count!=totalChunks) throw new IllegalStateException("Stored chunk count does not match processed documents");
      JsonNode config=repo.tree(repo.encode(Map.of("embeddingModel",embeddingModel,"dimensions",dimensions,"maxTokens",512,"processor","docling-hybrid")));
      Version version=new Version(initial.referenceId(),initial.corpusId(),table,papers,config,totalChunks,Instant.now());
      // Activate only after chunk files can survive replacement of this container.
      processes.artifacts().publish(directory);
      transaction.executeWithoutResult(status -> {repo.saveVersion(version);repo.activate(version.corpusId(),version.id());});
      activated=true;
      repo.saveJob(job.progress("COMPLETED","Corpus indexed and ready for questions",true,null,papers.size(),papers.size(),totalChunks));
    } catch(Exception e) {
      org.slf4j.LoggerFactory.getLogger(CorpusService.class).error("Corpus indexing job {} failed",initial.jobId(),e);
      if(e instanceof InterruptedException) Thread.currentThread().interrupt();
      if(!activated) try{stores.discard(table);}catch(Exception ignored){}
      repo.saveJob(job.progress("FAILED",failure(e),true,job.currentDocument(),job.completed(),job.total(),job.chunks()));
    }
  }
  static void validate(Paper paper,ChunkedDocument document) {
    if(!paper.id().toString().equals(document.documentId()) || !paper.sourceFile().equals(document.sourceFile()) || document.chunkCount()<1 || document.chunkCount()!=document.chunks().size()) throw invalid("Processed document does not match its PDF");
    for(int i=0;i<document.chunks().size();i++) {
      DocumentChunk c=document.chunks().get(i);
      if(c.chunkIndex()!=i || !c.documentId().equals(document.documentId()) || !c.sourceFile().equals(document.sourceFile()) || c.section()==null || c.text()==null || c.text().isBlank() || c.pageNumbers().isEmpty() || c.pageNumbers().stream().anyMatch(p->p<1||p>paper.pageCount())) throw invalid("Invalid processed chunk for "+paper.sourceFile());
    }
  }
  public Version selection(String corpus,String version) {
    UUID id=uuid(corpus);Corpus record=repo.corpus(id);
    UUID selected=version==null?record.activeVersion():uuid(version);
    if(selected==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Process this corpus before asking questions");
    Version result=repo.version(id,selected);
    if(!embeddingModel.equals(result.config().path("embeddingModel").asText()) || dimensions!=result.config().path("dimensions").asInt()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Embedding configuration changed; rebuild this corpus before querying");
    return result;
  }
  public Path versionDirectory(UUID corpus,UUID version) {return processes.corpus(corpus).resolve("versions").resolve(version.toString());}
  public List<ChunkedDocument> chunks(Version version) throws IOException {
    processes.artifacts().restore(versionDirectory(version.corpusId(),version.id()).resolve("chunks"));
    List<ChunkedDocument> result=new ArrayList<>();
    for(Paper paper:version.documents()) result.add(repo.decode(Files.readString(versionDirectory(version.corpusId(),version.id()).resolve("chunks").resolve(paper.id()+"-chunks.json")),ChunkedDocument.class));
    return result;
  }
  public static UUID uuid(String value) {try{return UUID.fromString(value);}catch(Exception e){throw invalid("Invalid corpus or version identifier");}}
  public static ResponseStatusException invalid(String detail) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,detail);}
  static String failure(Exception e) {
    // Provider exception messages may contain request details; keep those out of the browser.
    if(e instanceof IOException) return e.getMessage();
    if(e instanceof ResponseStatusException r) return r.getReason();
    return "Processing failed ("+e.getClass().getSimpleName()+"). Previous index retained; inspect local application logs.";
  }
  @PreDestroy void close() {worker.shutdownNow();}
}
