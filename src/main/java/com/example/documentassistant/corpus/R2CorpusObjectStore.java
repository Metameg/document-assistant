package com.example.documentassistant.corpus;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name="document-assistant.corpora.storage-provider", havingValue="r2")
final class R2CorpusObjectStore implements CorpusObjectStore {
  private final S3Client client;
  private final String bucket;

  @Autowired
  R2CorpusObjectStore(@Value("${document-assistant.corpora.r2.endpoint}") URI endpoint,
      @Value("${document-assistant.corpora.r2.bucket}") String bucket,
      @Value("${document-assistant.corpora.r2.access-key-id}") String accessKey,
      @Value("${document-assistant.corpora.r2.secret-access-key}") String secretKey) {
    if(!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getUserInfo()!=null
        || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank())
      throw new IllegalArgumentException("R2 requires an HTTPS endpoint, bucket, and credentials");
    this.bucket=bucket;
    this.client=S3Client.builder().endpointOverride(endpoint).region(Region.of("auto"))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey,secretKey)))
        .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .overrideConfiguration(c->c.apiCallTimeout(java.time.Duration.ofMinutes(5))
            .apiCallAttemptTimeout(java.time.Duration.ofSeconds(60))).build();
  }
  R2CorpusObjectStore(S3Client client,String bucket) {this.client=client;this.bucket=bucket;}
  @Override public boolean exists(String key) throws IOException {
    try {client.headObject(r->r.bucket(bucket).key(key));return true;}
    catch(S3Exception e) {if(e.statusCode()==404)return false;throw failure(e);}
    catch(RuntimeException e) {throw failure(e);}
  }
  @Override public void download(String key,Path destination) throws IOException {
    try {client.getObject(r->r.bucket(bucket).key(key),destination);}
    catch(RuntimeException e) {throw failure(e);}
  }
  @Override public void upload(String key,Path source) throws IOException {
    try {client.putObject(r->r.bucket(bucket).key(key),RequestBody.fromFile(source));}
    catch(RuntimeException e) {throw failure(e);}
  }
  @Override public List<String> list(String prefix) throws IOException {
    try {
      List<String> keys=new ArrayList<>();
      for(var page:client.listObjectsV2Paginator(r->r.bucket(bucket).prefix(prefix)))
        page.contents().forEach(o->keys.add(o.key()));
      return keys;
    } catch(RuntimeException e) {throw failure(e);}
  }
  @Override public void delete(String key) throws IOException {
    try {client.deleteObject(r->r.bucket(bucket).key(key));}
    catch(RuntimeException e) {throw failure(e);}
  }
  private IOException failure(RuntimeException cause) {return new IOException("R2 storage request failed",cause);}
  @PreDestroy void close() {client.close();}
}
