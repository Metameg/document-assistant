package com.example.documentassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocumentAssistantApplication {

  public static void main(String[] args) {
    SpringApplication.run(
        DocumentAssistantApplication.class,
        args);
  }
}
