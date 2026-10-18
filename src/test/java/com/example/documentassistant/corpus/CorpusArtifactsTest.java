package com.example.documentassistant.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CorpusArtifactsTest {
  @TempDir Path root;
  @Test void restoresPdfChunksReportsAndPlotsAfterTheWorkingDiskIsLost() throws Exception {
    var remote=new MemoryCorpusObjectStore();var artifacts=new CorpusArtifacts(root,remote,"production");
    Path corpus=root.resolve(UUID.randomUUID().toString());
    List<String> names=List.of("pdfs/paper.pdf","processed/paper.docling.json","versions/version/chunks/paper.json",
        "benchmarks/benchmark/request.json","evaluations/run/report.json","evaluations/run/plots/plot.png");
    for(String name:names) {Path file=corpus.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,name);}
    artifacts.publish(corpus);CorpusFileCleanup.erase(corpus);
    assertTrue(artifacts.exists(corpus.resolve(names.getFirst())));assertFalse(Files.exists(corpus));
    artifacts.require(corpus.resolve(names.getFirst()));
    artifacts.restore(corpus);
    for(String name:names) assertEquals(name,Files.readString(corpus.resolve(name)));
    assertEquals(names.size(),remote.objects.size());
  }
  @Test void deletionUsesExactDirectoryBoundariesAndRetainsOtherCorporaAndEnvironments() throws Exception {
    var remote=new MemoryCorpusObjectStore();var artifacts=new CorpusArtifacts(root,remote,"production");
    Path corpus=root.resolve(UUID.randomUUID().toString());Files.createDirectories(corpus);
    Path doomed=corpus.resolve("versions/one/chunk.json"),kept=corpus.resolve("versions/one-extra/chunk.json");
    for(Path file:List.of(doomed,kept)) {Files.createDirectories(file.getParent());Files.writeString(file,"keep");}
    artifacts.publish(corpus);String foreign="staging/"+UUID.randomUUID()+"/pdfs/paper.pdf";remote.objects.put(foreign,new byte[]{1});
    artifacts.delete(doomed.getParent());assertFalse(artifacts.exists(doomed));assertTrue(artifacts.exists(kept));
    CorpusFileCleanup.erase(corpus);artifacts.delete(corpus);
    assertEquals(Set.of(foreign),remote.objects.keySet());
  }
  @Test void rejectsTraversalSymlinksSharedCachesAndMalformedRemotePaths() throws Exception {
    var remote=new MemoryCorpusObjectStore();var artifacts=new CorpusArtifacts(root,remote,"production");
    Path corpus=root.resolve(UUID.randomUUID().toString());Files.createDirectories(corpus);
    assertThrows(IllegalArgumentException.class,()->artifacts.publish(root.resolve("model-cache")));
    assertThrows(IllegalArgumentException.class,()->artifacts.delete(corpus.resolve("../../outside")));
    Path outside=root.resolve("outside");Files.writeString(outside,"private");Files.createSymbolicLink(corpus.resolve("link"),outside);
    assertThrows(IllegalArgumentException.class,()->artifacts.require(corpus.resolve("link")));
    remote.objects.put("production/"+corpus.getFileName()+"/versions/../../outside",new byte[]{1});
    assertThrows(Exception.class,()->artifacts.restore(corpus.resolve("versions")));
    artifacts.delete(corpus.resolve("link"));assertFalse(Files.isSymbolicLink(corpus.resolve("link")));
    assertEquals("private",Files.readString(outside));
  }
  @Test void failedDownloadsNeverLeaveAnApparentlyCompleteFile() throws Exception {
    var remote=new MemoryCorpusObjectStore();var artifacts=new CorpusArtifacts(root,remote,"production");
    Path file=root.resolve(UUID.randomUUID().toString()).resolve("pdfs/missing.pdf");
    assertThrows(java.io.IOException.class,()->artifacts.require(file));assertFalse(Files.exists(file));
    try(var files=Files.list(file.getParent())) {assertEquals(0,files.count());}
  }
}
