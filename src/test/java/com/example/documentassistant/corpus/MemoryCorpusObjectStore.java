package com.example.documentassistant.corpus;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class MemoryCorpusObjectStore implements CorpusObjectStore {
  final Map<String,byte[]> objects=new ConcurrentHashMap<>();
  boolean failWrites,failDeletes;
  public boolean exists(String key) {return objects.containsKey(key);}
  public void download(String key,Path destination) throws IOException {
    byte[] bytes=objects.get(key);if(bytes==null)throw new NoSuchFileException(key);Files.write(destination,bytes);
  }
  public void upload(String key,Path source) throws IOException {
    if(failWrites)throw new IOException("Simulated object store write failure");objects.put(key,Files.readAllBytes(source));
  }
  public List<String> list(String prefix) {return objects.keySet().stream().filter(k->k.startsWith(prefix)).sorted().toList();}
  public void delete(String key) throws IOException {
    if(failDeletes)throw new IOException("Simulated object store delete failure");objects.remove(key);
  }
}
