# Local research corpora

The `development` worktree adds named research corpora alongside the original specification demo. Each successful research index has its own pgvector table and full-text index. PostgreSQL holds the registry, immutable index/benchmark versions, and job history; PDFs and processing/evaluation artifacts live under ignored `data/corpora/`.

## Start locally

1. Keep the existing `config/application-local.properties` and `.env` with your configured answer/embedding models and provider credentials. Do not commit them.
2. Create a Python environment and install the processing dependencies:

   ```sh
   uv venv .venv-docling --python 3.12
   uv pip install --python .venv-docling/bin/python -r requirements-docling.txt
   ```

   Docling needs PyTorch and downloads public layout/table models on first processing. CPU execution is supported; install a compatible CPU PyTorch wheel if the default platform resolution selects GPU dependencies. For this workstation, the development environment can also reuse the already installed Docling 2.129.0 environment from the parent project.

3. Copy `config/application-development.properties.example` to `config/application-development.properties`. The latter is ignored. Set `document-assistant.corpora.python-executable` if your Python executable differs.
4. Start the separate development database and build the app:

   ```sh
   docker compose -p document-assistant-development -f compose.development.yaml up -d --wait
   ./mvnw -o -DskipTests package
   python scripts/run_development.py
   ```

The launcher runs an immutable JAR snapshot so a later Maven build cannot invalidate the running classloader. To keep it running in a dedicated background session, use:

```sh
tmux -L document-assistant-development new-session -d -s app 'python scripts/run_development.py > /tmp/document-assistant-development.log 2>&1'
# Stop that development session:
tmux -L document-assistant-development kill-session -t app
```

Open <http://127.0.0.1:8081>. The separate database listens on 5433 and uses its own Docker volume. The existing database and main checkout are not migrated or reindexed. The specification corpus is registered through its existing files/table; its vectors must be rebuilt separately in a fresh development database.

## Use the workflow

- Choose **Corpus**, create a named collection, and upload PDFs. Files are validated before registration; identical content is reported with its existing filename and skipped. Process and index automatically uploads any selected files before taking the index snapshot. Identical filenames across corpora are safe. Limits default to 100 files, 50 MiB per file, and 500 MiB per request.
- Use PDFs with selectable text; image-only scans are not supported. **Process and index** uploads any selected PDFs first, then performs Docling conversion, 512-token native hybrid chunking, embedding with the configured model, count validation, and atomic activation. No generator models or generator-specific JSON are needed.
- Ask questions in **Ask Assistant**. Both semantic and keyword retrieval use the selected corpus. The answer model stays unchanged. Questions remain independent, and switching corpora clears visible exchanges.
- More uploads create pending changes. A rebuild produces a new version while the previous index remains queryable. Failed rebuilds retain the active version. **Index history and rollback** can activate an earlier successful version. Historical versions and artifacts are retained until explicitly removed; automatic pruning is not part of this local iteration.
- Use **Remove** next to a research paper to permanently delete it. The confirmation explains that historical indexes containing the paper and benchmarks/evaluations tied to those indexes are also removed. Remaining active vectors are copied into a new index without embedding calls; unaffected historical versions and evaluations stay available. Removing a pending upload leaves the active index unchanged. Removing the last indexed paper clears the active index; other pending uploads still need processing.
- Use **Delete corpus** in the research upload panel to remove the entire collection. Type its name to confirm. This deletes its PDFs, cached conversions/chunks, all vector tables, job history, benchmarks, and evaluations. Deletion is blocked while any corpus job is running. The built-in specification demo is not managed by these research-corpus controls.
- In **Evaluation**, generate a benchmark draft (20 questions by default). Review questions, reference answers, expected claims, and their source passages. Save a new reviewed draft or approve it. Approved versions remain immutable; edits create another draft.
- Run evaluation with chosen evidence limits. The workflow freezes a retrieved pool plus reference chunks, labels relevance through the judge API, runs RAG answers and answer judging, and creates all five plots. Reports include per-question answers, evidence, judgments, and timing. Retrieval recall/AP describe the judged pool, not exhaustive corpus relevance. LLM judgments remain estimates and source review matters.
- Rebuilding makes previous benchmarks stale for the active version. Generate another draft or review/save a revision against the new index. Historical evaluations remain visible with their index and benchmark versions.

## Failure recovery and artifacts

Jobs and progress are durable. An application restart marks unfinished jobs interrupted. Retry indexing reuses successfully processed papers and saved Docling conversions. Oversized contextualized table chunks are split with Docling's token splitter, preserving their text, section, page references, and source element IDs. Retry benchmark/evaluation jobs retains completed generation, relevance labels, answers, and judgments. Changing model/retrieval/prompt settings requires a new evaluation run. Partial reports remain available after failures.

Artifacts are grouped by corpus UUID: `pdfs/`, `processed/`, `versions/<version>/chunks/`, `benchmarks/<benchmark>/`, and `evaluations/<job>/`. Subprocess logs remain in their corresponding directories, while Java failures are logged by the application. Credentials are passed through the subprocess environment and are not saved in run configuration or API responses. Keep these directories and the database together when backing up.

Registry changes and vector-table removal commit together. File cleanup is queued in the same database transaction and normally finishes before the deletion response. Filesystem failures are reported as pending cleanup and retry every 30 seconds and on startup; they cannot restore a deleted paper to retrieval. Shared model caches and other corpora are not removed. Failed/interrupted index snapshots are discarded during document removal because they can also contain that paper's text.

## Validation

```sh
./mvnw -o test
python -B -m unittest discover -s tests/python -v
.venv-docling/bin/python -B -m unittest discover -s tests/docling -v
node --test tests/js/*.test.mjs
# Include real pgvector integration tests and load local .env automatically:
python -B scripts/test_development.py
```

Real pgvector integration tests are enabled with `-DrunCorpusIntegrationTests=true`; they require `POSTGRES_PASSWORD` in the environment and the development database on port 5433. Each test uses and removes a unique database schema and deterministic embeddings, without model API calls. These tests cover corpus/PDF isolation, both retrieval channels, failed rebuild retention, activation/rollback, upload validation/deduplication, benchmark immutability/staleness, document/corpus deletion, dependency cleanup, failed-deletion rollback, active-job protection, and durable file cleanup.

For a live smoke test, upload a public research PDF, process it, inspect page-linked source excerpts, ask a sourced question, generate and review a small benchmark, then evaluate at a small k before increasing the workload. Generation, embeddings, relevance labeling, and answer judging use provider credits.

This version is intended for local, single-user use. Production deployment, authentication, distributed workers, and storage lifecycle policies require a separate rollout.
