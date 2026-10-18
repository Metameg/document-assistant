package com.example.documentassistant.corpus;

import com.example.documentassistant.access.CloudflareAccessFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ProductionStorageConfigurationTest {
  @TempDir Path directory;
  @Test void wiresTheProductionComponentsWithoutACloudConnection() {
    new ApplicationContextRunner().withUserConfiguration(R2CorpusObjectStore.class,CorpusArtifacts.class,
        CorpusProcesses.class,CloudflareAccessFilter.class).withPropertyValues(
            "document-assistant.corpora.project-root="+directory,
            "document-assistant.corpora.storage-provider=r2",
            "document-assistant.corpora.r2.endpoint=https://example.r2.cloudflarestorage.com",
            "document-assistant.corpora.r2.bucket=private-test",
            "document-assistant.corpora.r2.access-key-id=test-key",
            "document-assistant.corpora.r2.secret-access-key=test-secret",
            "document-assistant.access.enabled=true",
            "document-assistant.access.team-domain=example.cloudflareaccess.com",
            "document-assistant.access.audience=test-application",
            "document-assistant.access.internal-token=a-random-test-token-at-least-32-characters")
        .run(context->{
          assertNull(context.getStartupFailure());
          assertSame(context.getBean(CorpusArtifacts.class),context.getBean(CorpusProcesses.class).artifacts());
          assertNotNull(context.getBean(CloudflareAccessFilter.class));
        });
  }
}
