package com.example.documentassistant.corpus;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/** Not a VectorStore bean: Spring AI still configures the original specification store. */
@Component
public class CorpusVectorStores {
  private final JdbcTemplate db;
  private final EmbeddingModel embedding;
  private final String schema;
  private final int dimensions;
  private final ConcurrentHashMap<String,PgVectorStore> stores = new ConcurrentHashMap<>();
  public CorpusVectorStores(JdbcTemplate db, EmbeddingModel embedding,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schema,
      @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}") int dimensions) {
    if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid schema");
    this.db=db; this.embedding=embedding; this.schema=schema; this.dimensions=dimensions;
  }
  public String schema() { return schema; }
  public String qualified(String table) {
    if (!table.matches("corpus_[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid corpus table identifier");
    return schema + "." + table;
  }
  public PgVectorStore get(String table) { return stores.computeIfAbsent(table, t -> build(t, false)); }
  public PgVectorStore create(String table) {
    PgVectorStore store=build(table,true);
    stores.put(table,store);
    db.execute("CREATE INDEX " + table + "_fts ON " + qualified(table) + " USING GIN ((setweight(to_tsvector('english',coalesce(metadata->>'section','')),'A') || setweight(to_tsvector('english',coalesce(content,'')),'D')))");
    return store;
  }
  private PgVectorStore build(String table, boolean initialize) {
    qualified(table);
    PgVectorStore store=PgVectorStore.builder(db,embedding).schemaName(schema).vectorTableName(table)
        .dimensions(dimensions).initializeSchema(initialize).removeExistingVectorStoreTable(false)
        .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE).indexType(PgVectorStore.PgIndexType.HNSW)
        .maxDocumentBatchSize(32).build();
    store.afterPropertiesSet(); return store;
  }
  public void discard(String table) { stores.remove(table); db.execute("DROP TABLE IF EXISTS " + qualified(table)); }
}
