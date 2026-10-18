package com.example.documentassistant.corpus;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** R2 is durable storage; the local directory is a disposable working cache for Python. */
@Component
public class CorpusArtifacts {
  private final Path root;
  private final CorpusObjectStore remote;
  private final String prefix;

  @Autowired
  public CorpusArtifacts(@Value("${document-assistant.corpora.project-root:.}") String project,
      @Value("${document-assistant.corpora.storage-directory:data/corpora}") String directory,
      @Value("${document-assistant.corpora.storage-provider:local}") String provider,
      @Value("${document-assistant.corpora.r2.prefix:document-assistant}") String prefix,
      ObjectProvider<CorpusObjectStore> stores) {
    this(Path.of(project).toAbsolutePath().resolve(directory).normalize(),stores.getIfAvailable(),prefix);
    if(!provider.equals("local") && !provider.equals("r2")) throw new IllegalArgumentException("Unknown corpus storage provider");
    if(provider.equals("r2") && remote==null) throw new IllegalArgumentException("R2 storage is not configured");
  }
  CorpusArtifacts(Path root,CorpusObjectStore remote,String prefix) {
    this.root=root.toAbsolutePath().normalize();this.remote=remote;
    if(!prefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) throw new IllegalArgumentException("Invalid R2 prefix");
    this.prefix=prefix+"/";
  }
  static CorpusArtifacts local(Path root) {return new CorpusArtifacts(root,null,"document-assistant");}
  private Path checked(Path path) {return checked(path,false);}
  private Path checked(Path path,boolean deletingLeaf) {
    Path normalized=path.toAbsolutePath().normalize();
    if(!normalized.startsWith(root) || normalized.equals(root)) throw new IllegalArgumentException("Invalid corpus artifact path");
    UUID.fromString(root.relativize(normalized).getName(0).toString());
    Path current=root;
    if(Files.isSymbolicLink(current)) throw new IllegalArgumentException("Artifact paths cannot traverse symbolic links");
    for(Path part:root.relativize(normalized)) {
      current=current.resolve(part);
      if(Files.isSymbolicLink(current) && !(deletingLeaf && current.equals(normalized)))
        throw new IllegalArgumentException("Artifact paths cannot traverse symbolic links");
    }
    return normalized;
  }
  private String key(Path path) {return prefix+root.relativize(checked(path)).toString().replace('\\','/');}
  public boolean exists(Path path) throws IOException {
    checked(path);return Files.isRegularFile(path) || remote!=null && remote.exists(key(path));
  }
  public Path require(Path path) throws IOException {
    path=checked(path);
    if(!Files.isRegularFile(path) && remote!=null) download(key(path),path);
    if(!Files.isRegularFile(path)) throw new NoSuchFileException(path.toString());
    return path;
  }
  private void download(String key,Path path) throws IOException {
    checked(path);Files.createDirectories(path.getParent());
    Path temp=Files.createTempFile(path.getParent(),"restore-",".tmp");Files.delete(temp);
    try {remote.download(key,temp);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    finally {Files.deleteIfExists(temp);}
  }
  public void restore(Path directory) throws IOException {
    String start=key(directory)+"/";
    if(remote==null) return;
    for(String object:remote.list(start)) {
      if(!object.startsWith(start)) throw new IOException("Object storage returned an unexpected key");
      Path destination=checked(root.resolve(object.substring(prefix.length())));
      if(!destination.startsWith(directory.toAbsolutePath().normalize()) || destination.equals(directory.toAbsolutePath().normalize()))
        throw new IOException("Object storage returned an invalid path");
      if(!Files.isRegularFile(destination)) download(object,destination);
    }
  }
  public void publish(Path path) throws IOException {
    checked(path);if(remote==null || !Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return;
    if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) {remote.upload(key(path),path);return;}
    try(var files=Files.walk(path)) {
      for(Path file:files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())
        if(!file.getFileName().toString().endsWith(".tmp")) remote.upload(key(file),file);
    }
  }
  public void delete(Path path) throws IOException {
    path=checked(path,true);
    String exact=prefix+root.relativize(path).toString().replace('\\','/');
    if(remote!=null) {
      for(String object:remote.list(exact+"/")) {
        if(!object.startsWith(exact+"/")) throw new IOException("Object storage returned an unexpected key");
        remote.delete(object);
      }
      remote.delete(exact);
    }
    CorpusFileCleanup.erase(path);
  }
}
