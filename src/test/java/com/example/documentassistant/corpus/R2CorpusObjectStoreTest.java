package com.example.documentassistant.corpus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK HTTP traffic against a local S3 protocol fixture; no cloud credentials or bucket. */
class R2CorpusObjectStoreTest {
  @TempDir Path directory;
  @Test void roundTripsPrivateObjectsWithoutChunkedTransferAndDistinguishesMissingFromDenied() throws Exception {
    Map<String,byte[]> objects=new ConcurrentHashMap<>();var chunked=new AtomicBoolean();var signed=new AtomicBoolean();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      String path=exchange.getRequestURI().getPath();String query=exchange.getRequestURI().getRawQuery();
      signed.set(exchange.getRequestHeaders().getFirst("Authorization")!=null);
      if(path.endsWith("/denied")) {exchange.sendResponseHeaders(403,-1);exchange.close();return;}
      int status=200;byte[] body=new byte[0];boolean head=exchange.getRequestMethod().equals("HEAD");
      if(query!=null && query.contains("list-type=2")) {
        String prefix=Arrays.stream(query.split("&")).filter(s->s.startsWith("prefix=")).map(s->URLDecoder.decode(s.substring(7),StandardCharsets.UTF_8)).findFirst().orElse("");
        StringBuilder xml=new StringBuilder("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><IsTruncated>false</IsTruncated>");
        objects.keySet().stream().filter(k->k.substring("/private/".length()).startsWith(prefix)).forEach(k->xml.append("<Contents><Key>").append(k.substring("/private/".length())).append("</Key><Size>3</Size></Contents>"));
        body=xml.append("</ListBucketResult>").toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/xml");
      } else switch(exchange.getRequestMethod()) {
        case "PUT" -> {
          chunked.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding")!=null || "aws-chunked".equals(exchange.getRequestHeaders().getFirst("Content-Encoding")));
          objects.put(path,exchange.getRequestBody().readAllBytes());exchange.getResponseHeaders().set("ETag","\"test\"");
        }
        case "HEAD", "GET" -> {if(!objects.containsKey(path)) status=404;else body=objects.get(path);}
        case "DELETE" -> {objects.remove(path);status=204;}
        default -> status=405;
      }
      exchange.sendResponseHeaders(status,head || status==204?-1:body.length);
      if(!head && status!=204)exchange.getResponseBody().write(body);exchange.close();
    });server.start();
    try {
      var client=S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:"+server.getAddress().getPort()))
          .region(Region.of("auto")).forcePathStyle(true)
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access-key","test-secret-key")))
          .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
          .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
          .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED).build();
      var store=new R2CorpusObjectStore(client,"private");
      try {
        Path input=directory.resolve("input.pdf"),output=directory.resolve("output.pdf");Files.write(input,new byte[]{1,2,3});
        store.upload("prefix/paper.pdf",input);assertTrue(signed.get());assertFalse(chunked.get());assertTrue(store.exists("prefix/paper.pdf"));
        store.download("prefix/paper.pdf",output);assertArrayEquals(Files.readAllBytes(input),Files.readAllBytes(output));
        assertEquals(List.of("prefix/paper.pdf"),store.list("prefix/"));
        assertThrows(java.io.IOException.class,()->store.exists("denied"));
        store.delete("prefix/paper.pdf");assertFalse(store.exists("prefix/paper.pdf"));
      } finally {store.close();}
    } finally {server.stop(0);}
  }
}
