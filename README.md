# Document Assistant

A retrieval-augmented generation (RAG) demo for exploring PDFs through natural-language questions. Built around nine Generac generator specsheets, it also supports uploading and indexing research-paper collections.

The app combines semantic and keyword search to ground answers in document excerpts, provides clickable source citations, and evaluates retrieval quality, answer correctness, and latency.

## Architecture

Spring Boot coordinates the app, Docling processes research PDFs, PostgreSQL with pgvector stores and searches chunks, Cloudflare R2 stores files, and OpenRouter provides embedding and chat models.

<!-- Drag and drop your architecture image here in GitHub's Markdown editor.
Alternatively, upload docs/architecture.png and replace the placeholder below with:
![Application architecture](docs/architecture.png)
-->

*Architecture diagram coming soon.*
