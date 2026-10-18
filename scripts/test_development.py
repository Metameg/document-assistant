#!/usr/bin/env python3
"""Run Java + isolated pgvector integration tests and no-network Python tests."""
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
from evaluate_rag import load_dotenv

root = Path(__file__).resolve().parent.parent
os.chdir(root)
load_dotenv(root / ".env")
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
docling_python = root / ".venv-docling/bin/python"
for command in (["./mvnw", "-o", "-DrunCorpusIntegrationTests=true", "test"],
                [sys.executable, "-B", "-m", "unittest", "discover", "-s", "tests/python", "-v"],
                [str(docling_python) if docling_python.is_file() else sys.executable, "-B", "-m", "unittest", "discover", "-s", "tests/docling", "-v"],
                ["node", "--test", "tests/js/corpus-jobs.test.mjs", "tests/js/corpus-upload.test.mjs"]):
    result = subprocess.run(command)
    if result.returncode:
        sys.exit(result.returncode)
