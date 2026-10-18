"""No-network tests for benchmark generation, pool boundaries, and judge validation."""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import prepare_benchmark as workflow
from evaluate_rag import read_qrels


class CorpusWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.chunks = self.root / "chunks"
        self.chunks.mkdir()
        data = {"documentId": "paper", "revision": None, "chunks": [
            {"documentId": "paper", "chunkIndex": i, "sourceFile": "paper.pdf", "pageNumbers": [i + 1], "text": f"Source evidence {i}"}
            for i in range(3)]}
        (self.chunks / "paper-chunks.json").write_text(json.dumps(data))
        self.case = {"id": "q1", "question": "What is the finding?", "referenceAnswer": "Source evidence 0",
                     "requiredClaims": ["Source evidence 0"], "sourceChunkIds": ["paper:0"]}

    def test_generation_reuses_completed_questions(self):
        args = argparse.Namespace(chunks_directory=self.chunks, output=self.root / "questions.json", count=2, judge_model="test")
        with patch.object(workflow, "model_json", side_effect=lambda model, prompt, payload: {**self.case, "sourceChunkIds": [payload["passages"][0]["chunkId"]]}) as model:
            workflow.generate(args)
            workflow.generate(args)
        self.assertEqual(2, model.call_count)
        self.assertEqual(["question-001", "question-002"], [q["id"] for q in json.loads(args.output.read_text())])

    def test_pool_includes_reference_sources_and_can_be_resumed(self):
        dataset = self.root / "questions.json"
        dataset.write_text(json.dumps([self.case]))
        args = argparse.Namespace(chunks_directory=self.chunks, dataset=dataset, output=self.root / "run",
                                  judge_model="test", corpus_id="corpus", version_id="version", k=[1, 2], application_url="http://local")
        response = {"evidenceByK": [{"k": 1, "evidence": [{"candidate": {"documentId": "paper", "chunkIndex": 1}}]}]}
        def labels(model, prompt, payload):
            return {"labels": [{"chunkId": c["chunkId"], "relevant": c["chunkId"] == "paper:0", "reason": "Source-specific"} for c in payload["candidates"]]}
        with patch.object(workflow, "post_json", return_value=response) as retrieval, patch.object(workflow, "model_json", side_effect=labels) as model:
            workflow.label(args)
            workflow.label(args)
        self.assertEqual(1, retrieval.call_count)
        self.assertEqual(1, model.call_count)
        corpus = workflow.load_corpus(self.chunks)
        qrels = read_qrels(args.output / "qrels.tsv", [self.case], [self.case], corpus)
        self.assertEqual({"paper:0"}, qrels.relevant["q1"])
        self.assertEqual({"paper:0", "paper:1"}, qrels.judged["q1"])

    def test_pool_rejects_unknown_corpus_chunks(self):
        dataset = self.root / "questions.json"
        dataset.write_text(json.dumps([self.case]))
        args = argparse.Namespace(chunks_directory=self.chunks, dataset=dataset, output=self.root / "run", judge_model="test", corpus_id="corpus", version_id="version", k=[1], application_url="http://local")
        response = {"evidenceByK": [{"evidence": [{"candidate": {"documentId": "another-corpus", "chunkIndex": 0}}]}]}
        with patch.object(workflow, "post_json", return_value=response), self.assertRaisesRegex(ValueError, "another corpus"):
            workflow.label(args)

    def test_judge_retries_invalid_json_without_accepting_it(self):
        with patch.dict(os.environ, {"OPENROUTER_API_KEY": "test-only"}), patch.object(workflow, "post_json", return_value={"choices": [{"message": {"content": "invalid"}}]}) as provider, patch.object(workflow.time, "sleep"):
            with self.assertRaisesRegex(ValueError, "three attempts"):
                workflow.model_json("test", "system", {})
        self.assertEqual(3, provider.call_count)


if __name__ == "__main__":
    unittest.main()
