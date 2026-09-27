package com.example.documentassistant.ingestion.pipeline;

import com.example.documentassistant.ingestion.chunking.DocumentChunkingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class CorpusIndexingConfiguration {

  @Bean(name = "corpusIndexingExecutor")
  ThreadPoolTaskExecutor corpusIndexingExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix(
        "corpus-indexing-");

    executor.setWaitForTasksToCompleteOnShutdown(
        true);

    executor.setAwaitTerminationSeconds(30);
    executor.initialize();

    return executor;
  }

  @Bean
  @ConditionalOnMissingBean(DocumentChunkingService.class)
  DocumentChunkingService webDocumentChunkingService() {

    return new DocumentChunkingService(
        JsonMapper.builder().build());
  }
}
