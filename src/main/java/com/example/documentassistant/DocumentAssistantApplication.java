package com.example.documentassistant;

import com.example.documentassistant.document.catalog.DocumentCatalogProperties;
import com.example.documentassistant.retrieval.RetrievalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    RetrievalProperties.class,
    DocumentCatalogProperties.class
})
public class DocumentAssistantApplication {

  public static void main(String[] args) {
    SpringApplication.run(
        DocumentAssistantApplication.class,
        args);
  }
}
