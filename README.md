# Document Assistant

A retrieval-augmented generation (RAG) demo for exploring PDFs through natural-language questions. Built around nine Generac generator specsheets, it also supports uploading and indexing research-paper collections.

The app combines semantic and keyword search to ground answers in document excerpts, provides clickable source citations, and evaluates retrieval quality, answer correctness, and latency.

## Architecture

Spring Boot coordinates the app, Docling processes research PDFs, PostgreSQL with pgvector stores and searches chunks, Cloudflare R2 stores files, and OpenRouter provides embedding and chat models.

<img width="794" height="525" alt="image" src="https://github.com/user-attachments/assets/7e965f50-f5ca-4322-a9c3-27ce44f63a7b" />



*Architecture diagram coming soon.*
