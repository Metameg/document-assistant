package com.example.documentassistant.document.parser;

import com.example.documentassistant.document.model.BlockType;
import com.example.documentassistant.document.model.DocumentBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

@Service
public class PostgresHtmlParser {

  public List<DocumentBlock> parse(Path htmlPath) throws IOException {

    Document document = Jsoup.parse(
        htmlPath.toFile(),
        StandardCharsets.UTF_8.name());

    Element article = document.selectFirst("#docContent > .sect1");

    if (article == null) {
      throw new IOException(
          "Could not find PostgreSQL article content in " + htmlPath);
    }

    article.select("a.id_link").remove();

    List<DocumentBlock> blocks = new ArrayList<>();

    for (Element element : article.select("h1, h2, h3, h4, h5, h6, p, pre")) {

      BlockType type;
      String text;

      if (element.tagName().equals("pre")) {
        type = BlockType.CODE;
        text = element.wholeText().strip();

      } else if (element.tagName().matches("h[1-6]")) {
        type = BlockType.HEADING;
        text = element.text().strip();

      } else {
        type = BlockType.PARAGRAPH;
        text = element.text().strip();
      }

      if (!text.isBlank()) {
        blocks.add(
            new DocumentBlock(type, text));
      }
    }

    return blocks;
  }
}
