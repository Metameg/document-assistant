package com.example.documentassistant.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = OpenRouterEmbeddingIntegrationTest.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.web-application-type=none",
    "spring.ai.model.chat=none"
})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "runOpenRouterIntegrationTests", matches = "true")
class OpenRouterEmbeddingIntegrationTest {

  private final EmbeddingModel embeddingModel;

  @Autowired
  OpenRouterEmbeddingIntegrationTest(
      EmbeddingModel embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  @Test
  void createsEmbeddingThroughOpenRouter() {
    String text = """
        Document: product_1
        Models: G007144-0
        Section: Engine

        [Page 1] Type of Engine: GENERAC G-FORCE 500 SERIES
        """;

    float[] embedding = embeddingModel.embed(text);

    assertNotNull(embedding);
    assertEquals(1536, embedding.length);
  }

  /**
   * Minimal Spring Boot application used only by this integration test.
   *
   * <p>
   * It enables Spring AI auto-configuration without scanning the regular
   * application or attempting to connect to PostgreSQL.
   * </p>
   */
  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
  static class TestApplication {
  }
}
