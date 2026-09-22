package com.example.documentassistant.ingestion.vector;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class VectorIngestionConfiguration {

  @Bean
  ChunkDocumentMapper chunkDocumentMapper() {
    return new ChunkDocumentMapper();
  }

  @Bean
  ChunkVectorIngestionService chunkVectorIngestionService(
      ChunkDocumentMapper documentMapper,
      VectorStore vectorStore) {

    return new ChunkVectorIngestionService(
        documentMapper,
        vectorStore);
  }
}
