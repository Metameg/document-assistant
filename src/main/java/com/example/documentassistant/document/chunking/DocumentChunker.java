package com.example.documentassistant.document.chunking;

import com.example.documentassistant.document.model.BlockType;
import com.example.documentassistant.document.model.DocumentBlock;
import com.example.documentassistant.document.model.DocumentChunk;

import java.util.ArrayList;
import java.util.List;

public class DocumentChunker {

  private final int maxChunkSize;

  public DocumentChunker(int maxChunkSize) {
    this.maxChunkSize = maxChunkSize;
  }

  public List<DocumentChunk> chunk(List<DocumentBlock> blocks) {
    List<DocumentChunk> chunks = new ArrayList<>();
    StringBuilder currentChunk = new StringBuilder();

    int chunkIndex = 0;

    for (DocumentBlock block : blocks) {

      if (block.type() == BlockType.HEADING
          && !currentChunk.isEmpty()) {

        chunks.add(new DocumentChunk(
            chunkIndex,
            currentChunk.toString().strip()));

        chunkIndex++;
        currentChunk.setLength(0);
      }

      int additionalLength = block.text().length();

      if (!currentChunk.isEmpty()) {
        additionalLength += 2;
      }

      if (!currentChunk.isEmpty()
          && currentChunk.length() + additionalLength > maxChunkSize) {

        chunks.add(new DocumentChunk(
            chunkIndex,
            currentChunk.toString().strip()));

        chunkIndex++;
        currentChunk.setLength(0);
      }

      if (!currentChunk.isEmpty()) {
        currentChunk.append("\n\n");
      }

      currentChunk.append(block.text());
    }

    if (!currentChunk.isEmpty()) {
      chunks.add(new DocumentChunk(
          chunkIndex,
          currentChunk.toString().strip()));
    }

    return chunks;
  }
}
