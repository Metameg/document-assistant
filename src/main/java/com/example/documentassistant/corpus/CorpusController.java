package com.example.documentassistant.corpus;

import com.example.documentassistant.corpus.CorpusRepository.*;
import com.example.documentassistant.document.catalog.*;
import com.example.documentassistant.document.corpus.CorpusStatusService;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/corpora")
public class CorpusController {
  private final CorpusRepository repo;
  private final CorpusService service;
  private final CorpusEvaluationService evaluation;
  private final DocumentCatalogService catalog;
  private final CorpusStatusService legacyStatus;
  public CorpusController(CorpusRepository repo,CorpusService service,CorpusEvaluationService evaluation,DocumentCatalogService catalog,CorpusStatusService legacyStatus) {
    this.repo=repo;this.service=service;this.evaluation=evaluation;this.catalog=catalog;this.legacyStatus=legacyStatus;
  }
  public record CreateRequest(String name) {}
  public record GenerateRequest(Integer count) {}
  public record EvaluateRequest(UUID benchmarkId,List<Integer> kValues) {}
  public record RevisionRequest(JsonNode dataset) {}
  public record PaperView(String documentId,String sourceFile,int pageCount,List<String> modelNumbers,Object revision,boolean pdfAvailable,boolean indexed) {}
  @GetMapping public List<CorpusService.View> list() {
    List<CorpusService.View> result=new ArrayList<>();
    var status=legacyStatus.getStatus();
    result.add(new CorpusService.View(CorpusService.LEGACY,"Generac specifications","specifications",null,!status.inSync(),status.catalogDocumentCount(),status.storedChunkCount()));
    repo.corpora().forEach(c->result.add(service.view(c)));return result;
  }
  @PostMapping @ResponseStatus(HttpStatus.CREATED) public CorpusService.View create(@RequestBody CreateRequest request) {return service.view(service.create(request.name()));}
  @GetMapping("/{id}") public CorpusService.View get(@PathVariable UUID id) {return service.view(repo.corpus(id));}
  @GetMapping("/{id}/documents") public List<PaperView> documents(@PathVariable String id) throws IOException {
    if(CorpusService.LEGACY.equals(id)) return catalog.listDocuments().stream().map(p->new PaperView(p.documentId(),p.sourceFile(),p.pageCount(),p.modelNumbers(),p.revision(),p.pdfAvailable(),true)).toList();
    UUID corpus=CorpusService.uuid(id);Corpus record=repo.corpus(corpus);Set<UUID> indexed=new HashSet<>();
    if(record.activeVersion()!=null) repo.version(corpus,record.activeVersion()).documents().forEach(p->indexed.add(p.id()));
    List<PaperView> result=new ArrayList<>();
    for(Paper p:repo.papers(corpus)) result.add(new PaperView(p.id().toString(),p.sourceFile(),p.pageCount(),List.of(),null,service.pdfAvailable(corpus,p.id()),indexed.contains(p.id())));
    return result;
  }
  @PostMapping(value="/{id}/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public CorpusService.UploadResult upload(@PathVariable UUID id,@RequestParam("files") List<MultipartFile> files) throws IOException {return service.upload(id,files);}
  @DeleteMapping("/{id}/documents/{documentId}")
  public CorpusService.RemovalResult deleteDocument(@PathVariable UUID id,@PathVariable UUID documentId) throws IOException {return service.deleteDocument(id,documentId);}
  @DeleteMapping("/{id}") public CorpusService.DeletedCorpus deleteCorpus(@PathVariable UUID id) {return service.deleteCorpus(id);}
  @GetMapping(value="/{id}/documents/{documentId}/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<Resource> pdf(@PathVariable String id,@PathVariable String documentId) throws IOException {
    Resource resource;String filename;long size;
    if(CorpusService.LEGACY.equals(id)) {CatalogPdf p=catalog.openPdf(documentId);resource=p.resource();filename=p.sourceFile();size=p.contentLength();}
    else {UUID corpus=CorpusService.uuid(id),paper=CorpusService.uuid(documentId);Path path=service.pdf(corpus,paper);resource=new FileSystemResource(path);filename=repo.papers(corpus).stream().filter(p->p.id().equals(paper)).findFirst().orElseThrow().sourceFile();size=Files.size(path);}
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(size).cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(filename,StandardCharsets.UTF_8).build().toString()).header("X-Content-Type-Options","nosniff").body(resource);
  }
  @PostMapping("/{id}/processing-jobs") @ResponseStatus(HttpStatus.ACCEPTED)
  public Job process(@PathVariable UUID id) {return service.process(id);}
  @GetMapping("/{id}/jobs") public List<Job> jobs(@PathVariable UUID id) {repo.corpus(id);return repo.jobs(id);}
  @GetMapping("/{id}/jobs/{job}") public Job job(@PathVariable UUID id,@PathVariable UUID job) {return repo.job(id,job);}
  @PostMapping("/{id}/jobs/{job}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
  public Job retry(@PathVariable UUID id,@PathVariable UUID job) throws IOException {return evaluation.retry(id,job);}
  @GetMapping("/{id}/versions") public List<Version> versions(@PathVariable UUID id) {repo.corpus(id);return repo.versions(id);}
  @PostMapping("/{id}/versions/{version}/activate") public CorpusService.View activate(@PathVariable UUID id,@PathVariable UUID version) {
    return service.activate(id,version);
  }
  @GetMapping("/evaluation-settings") public Map<String,Object> settings() {return evaluation.settings();}
  @GetMapping("/{id}/benchmarks") public List<Benchmark> benchmarks(@PathVariable UUID id) {repo.corpus(id);return repo.benchmarks(id);}
  @PostMapping("/{id}/benchmarks/generate") @ResponseStatus(HttpStatus.ACCEPTED)
  public Job generate(@PathVariable UUID id,@RequestBody(required=false) GenerateRequest request) throws IOException {return evaluation.generate(id,request==null||request.count()==null?20:request.count());}
  @GetMapping("/{id}/benchmarks/{benchmark}/sources") public Map<String,Object> sources(@PathVariable UUID id,@PathVariable UUID benchmark) throws IOException {
    Benchmark b=repo.benchmark(id,benchmark);Version v=repo.version(id,b.versionId());Set<String> needed=new HashSet<>();
    b.dataset().forEach(q->q.path("sourceChunkIds").forEach(k->needed.add(k.asText())));Map<String,Object> result=new LinkedHashMap<>();
    service.chunks(v).forEach(d->d.chunks().forEach(c->{String key=c.documentId()+":"+c.chunkIndex();if(needed.contains(key))result.put(key,c);}));return result;
  }
  @PostMapping("/{id}/benchmarks/{benchmark}/revisions") @ResponseStatus(HttpStatus.CREATED)
  public Benchmark revise(@PathVariable UUID id,@PathVariable UUID benchmark,@RequestBody RevisionRequest request) throws IOException {return evaluation.revise(id,benchmark,request.dataset());}
  @PostMapping("/{id}/benchmarks/{benchmark}/approve") public Benchmark approve(@PathVariable UUID id,@PathVariable UUID benchmark) throws IOException {return evaluation.approve(id,benchmark);}
  @PostMapping("/{id}/evaluation-runs") @ResponseStatus(HttpStatus.ACCEPTED)
  public Job evaluate(@PathVariable UUID id,@RequestBody EvaluateRequest request) throws IOException {
    if(request.benchmarkId()==null) throw CorpusService.invalid("Select an approved benchmark");
    return evaluation.evaluate(id,request.benchmarkId(),request.kValues());
  }
  @GetMapping("/{id}/evaluation-runs") public List<Map<String,Object>> evaluations(@PathVariable UUID id) {
    repo.corpus(id);return repo.jobs(id).stream().filter(j->j.kind().equals("EVALUATION")).map(j->Map.<String,Object>of("job",j,"configuration",repo.evaluationConfig(j.jobId()))).toList();
  }
  @GetMapping("/{id}/evaluation-runs/{run}/report") public JsonNode report(@PathVariable UUID id,@PathVariable UUID run) throws IOException {
    evaluationJob(id,run);Path report=evaluation.evaluationDirectory(id,run).resolve("report.json");
    if(!evaluation.artifacts().exists(report)) throw CorpusRepository.missing("Report");return repo.tree(Files.readString(evaluation.artifacts().require(report)));
  }
  @GetMapping(value="/{id}/evaluation-runs/{run}/plots/{name}",produces=MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<Resource> plot(@PathVariable UUID id,@PathVariable UUID run,@PathVariable String name) throws IOException {
    evaluationJob(id,run);
    if(!com.example.documentassistant.web.demo.EvaluationJobService.PLOT_NAMES.contains(name)) throw CorpusRepository.missing("Plot");
    Path path=evaluation.evaluationDirectory(id,run).resolve("plots").resolve(name);
    if(!evaluation.artifacts().exists(path))throw CorpusRepository.missing("Plot");
    evaluation.artifacts().require(path);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(new FileSystemResource(path));
  }
  private void evaluationJob(UUID corpus,UUID run) {if(!repo.job(corpus,run).kind().equals("EVALUATION"))throw CorpusRepository.missing("Evaluation run");}
}
