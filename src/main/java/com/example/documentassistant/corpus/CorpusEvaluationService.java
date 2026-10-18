package com.example.documentassistant.corpus;

import com.example.documentassistant.corpus.CorpusRepository.*;
import com.example.documentassistant.retrieval.RetrievalProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class CorpusEvaluationService {
  private final CorpusRepository repo;
  private final CorpusService corpora;
  private final CorpusProcesses processes;
  private final RetrievalProperties retrieval;
  private final String judgeModel;
  private final String answerModel;
  private final String applicationUrl;
  private final String judgeUrl;
  private final TransactionTemplate transaction;
  private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("corpus-evaluation").factory());
  public CorpusEvaluationService(CorpusRepository repo,CorpusService corpora,CorpusProcesses processes,RetrievalProperties retrieval,
      org.springframework.transaction.PlatformTransactionManager transactions,
      @Value("${document-assistant.evaluation.judge-model:openai/gpt-oss-120b}") String judgeModel,
      @Value("${spring.ai.openai.chat.model}") String answerModel,
      @Value("${document-assistant.evaluation.application-url:http://localhost:8080}") String applicationUrl,
      @Value("${document-assistant.evaluation.judge-url:https://openrouter.ai/api/v1/chat/completions}") String judgeUrl) {
    this.repo=repo;this.corpora=corpora;this.processes=processes;this.retrieval=retrieval;this.judgeModel=judgeModel;
    this.answerModel=answerModel;this.applicationUrl=applicationUrl;this.judgeUrl=judgeUrl;this.transaction=new TransactionTemplate(transactions);
  }
  public Map<String,Object> settings() {
    return Map.of("answerModel",answerModel,"judgeModel",judgeModel,"defaultQuestionCount",20,"maxK",retrieval.maxEvidenceLimit(),"defaultKValues",List.of(5,10,20,30,40).stream().filter(k->k<=retrieval.maxEvidenceLimit()).toList());
  }
  public Job generate(UUID corpus,int count) throws IOException {
    synchronized(corpora) {
      if(count<1 || count>100) throw CorpusService.invalid("Choose 1–100 questions");
      Version version=corpora.selection(corpus.toString(),null);
      UUID benchmark=UUID.randomUUID();
      Job job=new Job(UUID.randomUUID(),corpus,"BENCHMARK","QUEUED",false,"Preparing benchmark draft",null,0,count,0,Instant.now(),null,benchmark);
      Path dir=benchmarkDirectory(corpus,benchmark);Files.createDirectories(dir);
      Files.writeString(dir.resolve("request.json"),repo.encode(Map.of("versionId",version.id(),"count",count,"judgeModel",judgeModel)));
      repo.insertJob(job);worker.submit(()->generate(job,version,count));return job;
    }
  }
  private void generate(Job job,Version version,int count) {
    try {
      repo.saveJob(job.progress("GENERATING_BENCHMARK","Generating source-grounded questions; model API credits are used",false,null,0,count,0));
      Path dir=benchmarkDirectory(job.corpusId(),job.referenceId());Path output=dir.resolve("questions.json");
      processes.execute("prepare_benchmark.py",List.of("generate","--chunks-directory",corpora.versionDirectory(version.corpusId(),version.id()).resolve("chunks").toString(),"--output",output.toString(),"--judge-model",judgeModel,"--count",Integer.toString(count)),dir.resolve("generation.log"),environment());
      JsonNode dataset=repo.tree(Files.readString(output));validateDataset(version,dataset);
      if(dataset.size()!=count) throw new IOException("Generated benchmark question count does not match request");
      repo.createBenchmark(new Benchmark(job.referenceId(),job.corpusId(),version.id(),"DRAFT",dataset,Instant.now()));
      repo.saveJob(job.progress("COMPLETED","Benchmark draft is ready for review",true,null,count,count,0));
    } catch(Exception e) {failed(job,e);}
  }
  public Benchmark revise(UUID corpus,UUID previous,JsonNode dataset) throws IOException {
    synchronized(corpora) {
      repo.assertIdle(corpus);repo.benchmark(corpus,previous);
      Version active=corpora.selection(corpus.toString(),null);validateDataset(active,dataset);
      Benchmark result=new Benchmark(UUID.randomUUID(),corpus,active.id(),"DRAFT",dataset,Instant.now());repo.createBenchmark(result);return repo.benchmark(corpus,result.id());
    }
  }
  public Benchmark approve(UUID corpus,UUID id) throws IOException {
    synchronized(corpora) {
      repo.assertIdle(corpus);Benchmark benchmark=repo.benchmark(corpus,id);
      Version version=corpora.selection(corpus.toString(),null);
      if(!benchmark.versionId().equals(version.id())) throw CorpusService.invalid("This benchmark belongs to an older index; save a reviewed revision for the active corpus first");
      validateDataset(version,benchmark.dataset());repo.approve(corpus,id);return repo.benchmark(corpus,id);
    }
  }
  public void validateDataset(Version version,JsonNode dataset) throws IOException {
    if(dataset==null || !dataset.isArray() || dataset.isEmpty() || dataset.size()>100) throw CorpusService.invalid("Benchmark must contain 1–100 questions");
    Set<String> chunks=new HashSet<>();
    corpora.chunks(version).forEach(d->d.chunks().forEach(c->chunks.add(c.documentId()+":"+c.chunkIndex())));
    Set<String> ids=new HashSet<>();
    for(JsonNode question:dataset) {
      for(String field:List.of("id","question","referenceAnswer")) if(!question.path(field).isString() || question.path(field).asText().isBlank()) throw CorpusService.invalid("Every question needs a nonempty "+field);
      if(!ids.add(question.path("id").asText())) throw CorpusService.invalid("Question IDs must be unique");
      JsonNode claims=question.path("requiredClaims"),sources=question.path("sourceChunkIds");
      if(!claims.isArray() || claims.isEmpty() || !sources.isArray() || sources.isEmpty()) throw CorpusService.invalid("Every question needs expected claims and supporting source chunks");
      for(JsonNode claim:claims) if(!claim.isString() || claim.asText().isBlank()) throw CorpusService.invalid("Expected claims must be nonempty strings");
      for(JsonNode source:sources) if(!source.isString() || !chunks.contains(source.asText())) throw CorpusService.invalid("Unknown supporting chunk: "+source.asText());
    }
  }
  public Job evaluate(UUID corpus,UUID benchmarkId,List<Integer> requested) throws IOException {
    synchronized(corpora) {
      Benchmark benchmark=repo.benchmark(corpus,benchmarkId);
      Version version=corpora.selection(corpus.toString(),null);
      if(!benchmark.state().equals("APPROVED") || !benchmark.versionId().equals(version.id())) throw CorpusService.invalid("Approve a benchmark for the active index before evaluating");
      List<Integer> ks=com.example.documentassistant.web.demo.EvaluationEstimateService.validateKValues(requested,retrieval.maxEvidenceLimit());
      Job job=new Job(UUID.randomUUID(),corpus,"EVALUATION","QUEUED",false,"Preparing frozen relevance pool",null,0,benchmark.dataset().size()*ks.size(),0,Instant.now(),null,benchmarkId);
      Map<String,Object> config=new LinkedHashMap<>();config.put("corpusId",corpus);config.put("versionId",version.id());config.put("benchmarkId",benchmarkId);
      config.put("kValues",ks);config.put("answerModel",answerModel);config.put("judgeModel",judgeModel);config.put("retrieval",retrieval);
      config.put("answerInstructions",Files.readString(processes.root.resolve("src/main/java/com/example/documentassistant/rag/HybridAnswerService.java")));
      config.put("judgePrompt",Files.readString(processes.root.resolve("evaluation/research-judge-system-prompt.txt")));
      transaction.executeWithoutResult(status->{repo.insertJob(job);repo.addEvaluation(job.jobId(),corpus,benchmarkId,config);});
      worker.submit(()->evaluate(job,benchmark,version,ks));return job;
    }
  }
  private void evaluate(Job job,Benchmark benchmark,Version version,List<Integer> ks) {
    try {
      Path dir=evaluationDirectory(job.corpusId(),job.jobId());Files.createDirectories(dir);
      Path dataset=dir.resolve("questions.json");Files.writeString(dataset,repo.encode(benchmark.dataset()));
      Files.writeString(dir.resolve("run-config.json"),repo.encode(repo.evaluationConfig(job.jobId())));
      Path chunks=corpora.versionDirectory(version.corpusId(),version.id()).resolve("chunks");
      repo.saveJob(job.progress("LABELING_RELEVANCE","Freezing the retrieval pool and judging relevance",false,null,0,job.total(),0));
      List<String> label=new ArrayList<>(List.of("label","--chunks-directory",chunks.toString(),"--dataset",dataset.toString(),"--output",dir.toString(),"--judge-model",judgeModel,"--application-url",applicationUrl,"--corpus-id",job.corpusId().toString(),"--version-id",version.id().toString(),"--k"));ks.forEach(k->label.add(k.toString()));
      processes.execute("prepare_benchmark.py",label,dir.resolve("relevance.log"),environment());
      repo.saveJob(job.progress("JUDGING_ANSWERS","Generating and judging answers at each evidence limit",false,null,0,job.total(),0));
      Path report=dir.resolve("report.json");
      List<String> command=new ArrayList<>(List.of("--dataset",dataset.toString(),"--chunks-directory",chunks.toString(),"--qrels",dir.resolve("qrels.tsv").toString(),"--judge-prompt",processes.root.resolve("evaluation/research-judge-system-prompt.txt").toString(),"--application-url",applicationUrl,"--corpus-id",job.corpusId().toString(),"--version-id",version.id().toString(),"--judge-model",judgeModel,"--judge-url",judgeUrl,"--output",report.toString(),"--resume","--k"));ks.forEach(k->command.add(k.toString()));
      processes.execute("evaluate_rag.py",command,dir.resolve("evaluation.log"),environment());
      repo.saveJob(job.progress("PLOTTING","Generating evaluation charts",false,null,job.total(),job.total(),0));
      processes.execute("plot_evaluation.py",List.of("--input",report.toString(),"--output-directory",dir.resolve("plots").toString()),dir.resolve("plotting.log"),environment());
      for(String name:com.example.documentassistant.web.demo.EvaluationJobService.PLOT_NAMES) if(!Files.isRegularFile(dir.resolve("plots").resolve(name))) throw new IOException("An evaluation chart was not generated");
      repo.saveJob(job.progress("COMPLETED","Evaluation complete; retrieval recall is measured against the judged pool",true,null,job.total(),job.total(),0));
    }catch(Exception e){failed(job,e);}
  }
  public Job retry(UUID corpus,UUID id) throws IOException {
    synchronized(corpora) {
      repo.assertIdle(corpus);Job old=repo.job(corpus,id);
      if(!old.terminal() || !Set.of("FAILED","INTERRUPTED").contains(old.stage())) throw CorpusService.invalid("Only failed or interrupted jobs can be retried");
      if(old.kind().equals("INDEX")) {
        return corpora.process(corpus);
      }
      Job job=old.progress("QUEUED","Retrying with saved successful outputs",false,null,old.completed(),old.total(),old.chunks());
      if(old.kind().equals("BENCHMARK")) {
        JsonNode config=repo.tree(Files.readString(benchmarkDirectory(corpus,old.referenceId()).resolve("request.json")));
        if(!judgeModel.equals(config.path("judgeModel").asText())) throw CorpusService.invalid("Judge configuration changed; generate a new benchmark");
        Version version=corpora.selection(corpus.toString(),config.path("versionId").asText());
        repo.saveJob(job);worker.submit(()->generate(job,version,config.path("count").asInt()));
      } else {
        JsonNode config=repo.evaluationConfig(id);
        if(!judgeModel.equals(config.path("judgeModel").asText()) || !answerModel.equals(config.path("answerModel").asText()) || !repo.tree(repo.encode(retrieval)).equals(config.path("retrieval"))
            || !Files.readString(processes.root.resolve("evaluation/research-judge-system-prompt.txt")).equals(config.path("judgePrompt").asText())
            || !Files.readString(processes.root.resolve("src/main/java/com/example/documentassistant/rag/HybridAnswerService.java")).equals(config.path("answerInstructions").asText())) throw CorpusService.invalid("Evaluation settings changed; start a new run");
        Version version=corpora.selection(corpus.toString(),config.path("versionId").asText());
        Benchmark benchmark=repo.benchmark(corpus,old.referenceId());
        List<Integer> ks=new ArrayList<>();config.path("kValues").forEach(k->ks.add(k.asInt()));
        repo.saveJob(job);worker.submit(()->evaluate(job,benchmark,version,ks));
      }
      return job;
    }
  }
  public Path evaluationDirectory(UUID corpus,UUID run) {return processes.corpus(corpus).resolve("evaluations").resolve(run.toString());}
  private Path benchmarkDirectory(UUID corpus,UUID id) {return processes.corpus(corpus).resolve("benchmarks").resolve(id.toString());}
  private Map<String,String> environment(){return Map.of("EVALUATION_JUDGE_URL",judgeUrl);}
  private void failed(Job job,Exception e) {org.slf4j.LoggerFactory.getLogger(CorpusEvaluationService.class).error("Corpus evaluation job {} failed",job.jobId(),e);if(e instanceof InterruptedException)Thread.currentThread().interrupt();repo.saveJob(job.progress("FAILED",CorpusService.failure(e),true,null,job.completed(),job.total(),job.chunks()));}
  @PreDestroy void close(){worker.shutdownNow();}
}
