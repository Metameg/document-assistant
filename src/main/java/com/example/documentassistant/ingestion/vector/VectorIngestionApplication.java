package com.example.documentassistant.ingestion.vector;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableConfigurationProperties(VectorIngestionProperties.class)
@Import({
    VectorIngestionConfiguration.class,
    VectorIngestionRunner.class
})
public class VectorIngestionApplication {

  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(VectorIngestionApplication.class);

    application.setWebApplicationType(WebApplicationType.NONE);

    ConfigurableApplicationContext context = application.run(args);

    int exitCode = SpringApplication.exit(context);
    System.exit(exitCode);
  }
}
