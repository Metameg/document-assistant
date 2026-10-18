"""The internal evaluation credential must never be sent to external model providers."""
import io
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from evaluate_rag import post_json


class PrivateWorkerTest(unittest.TestCase):
    def test_worker_secret_is_limited_to_local_evaluation_endpoints(self):
        for url, api_key, expected in (
            ("http://localhost:8080/api/rag/answer", None, "worker-secret"),
            ("http://127.0.0.1:8080/api/rag/evidence-pool", None, "worker-secret"),
            ("https://openrouter.ai/api/v1/chat/completions", "provider-key", None),
            ("https://example.com/api/rag/answer", None, None),
            ("http://localhost:8080/api/corpora", None, None),
        ):
            with self.subTest(url=url), patch.dict(os.environ, {"DOCUMENT_ASSISTANT_WORKER_TOKEN": "worker-secret"}), \
                    patch("urllib.request.urlopen", return_value=io.BytesIO(b"{}")) as open_url:
                post_json(url, {}, api_key)
                request = open_url.call_args.args[0]
                self.assertEqual(expected, request.get_header("X-document-assistant-worker"))
                self.assertEqual(f"Bearer {api_key}" if api_key else None, request.get_header("Authorization"))
