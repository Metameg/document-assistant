package com.example.documentassistant.ingestion.chunking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Separate offline entry point. Deliberately has no @SpringBootApplication:
 * no component scan, web server, JDBC, or AI auto-configuration is activated.
 * The normal DocumentAssistantApplication entry point stays unchanged.
 */
public class ChunkingApplication {
  private static final Logger log = LoggerFactory.getLogger(ChunkingApplication.class);

  public static void main(String[] args) {
    // Context closure happens inside execute(), before the JVM exits.
    System.exit(execute(args));
  }

  static int execute(String... args) {
    try (ConfigurableApplicationContext context = start(args)) {
      // Spring has already called ChunkingRunner.run() by this point.
      log.info("Offline chunking completed successfully; closing application context.");
      return 0;
    } catch (Exception exception) {
      log.error("Offline chunking failed: {}", exception.getMessage());
      return 1;
    }
  }

  static ConfigurableApplicationContext start(String... args) {
    SpringApplication application = new SpringApplication(BatchConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setAdditionalProfiles("ingestion");
    // Use optional chunking.properties, not the web application's properties.
    application.setDefaultProperties(Map.of(
        "spring.config.name", "chunking",
        "spring.main.web-application-type", "none",
        "spring.main.banner-mode", "off"));
    return application.run(args);
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("ingestion")
  @ConditionalOnNotWebApplication
  public static class BatchConfiguration {
    @Bean
    JsonMapper chunkingJsonMapper() {
      return JsonMapper.builder().build();
    }

    @Bean
    DocumentChunkingService documentChunkingService(JsonMapper mapper) {
      return new DocumentChunkingService(mapper);
    }

    @Bean
    ChunkingRunner chunkingRunner(
        DocumentChunkingService service,
        @Value("${app.chunking.input-directory:data/processed/specsheets}") String inputDirectory,
        @Value("${app.chunking.output-directory:target/chunk-runs}") String outputDirectory) {
      return new ChunkingRunner(service, inputDirectory, outputDirectory);
    }
  }
}
