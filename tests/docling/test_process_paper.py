"""Offline regressions using the installed Docling and embedding tokenizer."""
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import tiktoken
from docling_core.transforms.chunker.tokenizer.openai import OpenAITokenizer
from docling_core.types.doc import (BoundingBox, DoclingDocument, ProvenanceItem,
                                    TableCell, TableData)

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from process_paper import bounded_text, chunk_document, convert_document


class ProcessPaperTest(unittest.TestCase):
    def setUp(self):
        self.tokenizer = OpenAITokenizer(tokenizer=tiktoken.get_encoding("cl100k_base"), max_tokens=512)

    def test_ordinary_hybrid_chunk_is_unchanged(self):
        text = "Methods\nThe experiment compares pruning strategies."
        self.assertEqual([text], bounded_text(text, self.tokenizer, 512))

    def test_oversized_context_preserves_every_character_and_unicode(self):
        text = "Results — 精度\n" + ("| αβ | pruning results 🧪 | 0.123456789 |\n" * 200)
        pieces = bounded_text(text, self.tokenizer, 512)
        self.assertGreater(len(pieces), 1)
        self.assertEqual(text, "".join(pieces))
        self.assertTrue(all(0 < self.tokenizer.count_tokens(p) <= 512 for p in pieces))

    def test_large_table_indexes_last_row_with_source_provenance(self):
        document = DoclingDocument(name="Research table")
        prov = ProvenanceItem(page_no=3, bbox=BoundingBox(l=0, t=100, r=100, b=0), charspan=(0, 1))
        heading = "Pruning comparison and experimental results across model configurations"
        document.add_heading(heading, prov=prov)
        cells = [TableCell(text=f"row{r}column{c} score 0.123456789", column_header=r == 0,
                           start_row_offset_idx=r, end_row_offset_idx=r + 1,
                           start_col_offset_idx=c, end_col_offset_idx=c + 1)
                 for r in range(60) for c in range(6)]
        table = document.add_table(TableData(num_rows=60, num_cols=6, table_cells=cells), prov=prov)
        result = chunk_document(document, "paper", "research.pdf")
        self.assertGreater(result["chunkCount"], 1)
        self.assertIn("row59column5", "".join(c["text"] for c in result["chunks"]))
        for index, chunk in enumerate(result["chunks"]):
            self.assertEqual(index, chunk["chunkIndex"])
            self.assertEqual([3], chunk["pageNumbers"])
            self.assertIn(table.self_ref, chunk["sourceElementIds"])
            self.assertEqual(heading, chunk["section"])
            self.assertLessEqual(self.tokenizer.count_tokens(chunk["text"]), 512)

    def test_conversion_disables_ocr_and_survives_a_chunking_retry(self):
        document = DoclingDocument(name="Cached conversion")
        result = SimpleNamespace(status=SimpleNamespace(value="success"), document=document)
        with tempfile.TemporaryDirectory() as directory, patch("docling.document_converter.DocumentConverter") as converter:
            cache = Path(directory) / "paper.docling.json"
            converter.return_value.convert.return_value = result
            convert_document(Path("paper.pdf"), cache)
            self.assertFalse(converter.call_args.kwargs["format_options"][next(iter(converter.call_args.kwargs["format_options"]))].pipeline_options.do_ocr)
            converter.return_value.convert.side_effect = AssertionError("Must reuse the saved conversion")
            self.assertEqual(document.name, convert_document(Path("paper.pdf"), cache).name)
            converter.return_value.convert.assert_called_once()


if __name__ == "__main__":
    unittest.main()
