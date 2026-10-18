#!/usr/bin/env python3
"""Docling PDF conversion and native token-bounded chunking for the application."""
import argparse
import json
from pathlib import Path


def convert_document(source, cache=None):
    from docling.datamodel.base_models import InputFormat
    from docling.datamodel.pipeline_options import PdfPipelineOptions, TableFormerMode
    from docling.document_converter import DocumentConverter, PdfFormatOption
    from docling_core.types.doc import DoclingDocument

    if cache is not None and cache.is_file():
        return DoclingDocument.load_from_json(cache)
    options = PdfPipelineOptions(do_ocr=False, do_table_structure=True)
    options.table_structure_options.mode = TableFormerMode.ACCURATE
    converter = DocumentConverter(allowed_formats=[InputFormat.PDF], format_options={
        InputFormat.PDF: PdfFormatOption(pipeline_options=options)})
    converted = converter.convert(source)
    if str(converted.status.value) != "success":
        raise ValueError(f"Docling conversion did not succeed: {converted.status}")
    if cache is not None:
        cache.parent.mkdir(parents=True, exist_ok=True)
        temporary = cache.with_suffix(".tmp")
        converted.document.save_as_json(temporary)
        temporary.replace(cache)
    return converted.document


def bounded_text(text, tokenizer, max_tokens):
    """Keep HybridChunker output intact unless enriched table text exceeds its budget."""
    if tokenizer.count_tokens(text) <= max_tokens:
        return [text]
    from docling_core.transforms.chunker.line_chunker import LineBasedTokenChunker

    # Docling's table splitter budgets table rows before contextualize adds headings.
    # Re-split only overflowing output using Docling's character-safe token splitter.
    # Every character and the parent chunk's provenance are retained.
    splitter = LineBasedTokenChunker(tokenizer=tokenizer)
    pieces = []
    remaining = text
    while remaining:
        head, remaining = splitter.split_by_token_limit(remaining, max_tokens)
        if not head or tokenizer.count_tokens(head) > max_tokens:
            raise ValueError("Unable to split Docling text within the token limit")
        pieces.append(head)
    return pieces


def chunk_document(document, document_id, source_file, max_tokens=512):
    import tiktoken
    from docling.chunking import HybridChunker
    from docling_core.transforms.chunker.tokenizer.openai import OpenAITokenizer

    tokenizer = OpenAITokenizer(tokenizer=tiktoken.get_encoding("cl100k_base"), max_tokens=max_tokens)
    chunker = HybridChunker(tokenizer=tokenizer, merge_peers=True)
    chunks = []
    for chunk in chunker.chunk(dl_doc=document):
        pages = sorted({prov.page_no for item in chunk.meta.doc_items for prov in item.prov})
        if not pages:
            raise ValueError("Docling produced a chunk without PDF page provenance")
        # Contextualized text retains heading/table context and uses the embedding tokenizer.
        text = chunker.contextualize(chunk)
        if not text.strip():
            continue
        for piece in bounded_text(text, tokenizer, max_tokens):
            if not piece.strip():
                continue
            chunks.append({"documentId": document_id, "sourceFile": source_file,
                           "chunkIndex": len(chunks), "section": " / ".join(chunk.meta.headings or []) or "Document",
                           "modelNumbers": [], "pageNumbers": pages,
                           "sourceElementIds": sorted({item.self_ref for item in chunk.meta.doc_items}), "text": piece})
    if not chunks:
        raise ValueError("No extractable text found. Upload a PDF with selectable text.")
    return {"chunkSchemaVersion": "1", "sourceSchemaVersion": "docling-hybrid-1",
            "documentId": document_id, "sourceFile": source_file, "revision": None,
            "chunkCount": len(chunks), "longestChunkCharacters": max(len(c["text"]) for c in chunks), "chunks": chunks}


def process(source, document_id, source_file, max_tokens=512, conversion_cache=None):
    return chunk_document(convert_document(source, conversion_cache), document_id, source_file, max_tokens)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--document-id", required=True)
    parser.add_argument("--source-file", required=True)
    parser.add_argument("--max-tokens", type=int, default=512)
    args = parser.parse_args()
    result = process(args.input, args.document_id, args.source_file, args.max_tokens,
                     conversion_cache=args.output.with_suffix(".docling.json"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(args.output)
    print(f"Processed {args.source_file}: {result['chunkCount']} chunks", flush=True)


if __name__ == "__main__":
    main()
