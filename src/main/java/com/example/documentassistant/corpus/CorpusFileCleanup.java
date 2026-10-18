package com.example.documentassistant.corpus;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** File removal is queued in the same transaction as registry/vector deletion. */
@Component
public class CorpusFileCleanup {
  private final CorpusRepository repo;
  private final Path root;
  private final CorpusProcesses processes;
  private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("corpus-file-cleanup").factory());
  public CorpusFileCleanup(CorpusRepository repo,CorpusProcesses processes) {this.repo=repo;this.root=processes.storage;this.processes=processes;}
  @PostConstruct void initialize() {drain();worker.scheduleWithFixedDelay(this::drain,30,30,TimeUnit.SECONDS);}
  public void queue(Collection<Path> paths) {
    repo.queueFileCleanup(paths.stream().map(p->root.relativize(checked(p)).toString()).toList());
  }
  private Path checked(Path path) {
    Path normalized=path.toAbsolutePath().normalize();
    // Only corpus UUID directories, never the storage root or shared model caches.
    Path relative=root.relativize(normalized);
    if(!normalized.startsWith(root) || relative.getNameCount()<1) throw new IllegalArgumentException("Invalid corpus cleanup path");
    try {UUID.fromString(relative.getName(0).toString());} catch(IllegalArgumentException e) {throw new IllegalArgumentException("Invalid corpus cleanup path");}
    Path parent=root;
    for(int i=0;i<relative.getNameCount()-1;i++) {
      parent=parent.resolve(relative.getName(i));
      if(Files.isSymbolicLink(parent)) throw new IllegalArgumentException("Corpus cleanup cannot traverse symbolic links");
    }
    return normalized;
  }
  public synchronized void drain() {
    try {
      for(var task:repo.fileCleanups().entrySet()) {
        try {
          for(String path:task.getValue()) processes.artifacts().delete(checked(root.resolve(path)));
          repo.finishFileCleanup(task.getKey());
        } catch(Exception e) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("Corpus file cleanup {} will retry",task.getKey(),e);}
      }
    } catch(Exception e) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not read pending corpus file cleanup; will retry",e);}
  }
  static void erase(Path path) throws IOException {
    try {Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
    catch(NoSuchFileException e) {return;}
    // walk does not follow symlinks; delete a link itself rather than its target.
    try(var files=Files.walk(path)) {for(Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);}
  }
  @PreDestroy void close() {worker.shutdownNow();}
}
