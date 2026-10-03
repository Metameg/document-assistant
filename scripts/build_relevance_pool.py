#!/usr/bin/env python3
"""Build a frozen pool from the final evidence selected at every requested k."""

import argparse
import json
import sys
from pathlib import Path

from evaluate_rag import (
    PROJECT_ROOT, fingerprint, load_corpus, post_json, read_json,
    validate_dataset, validate_sources,
)


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path,
                        default=PROJECT_ROOT / "evaluation/questions.json")
    parser.add_argument("--chunks-directory", type=Path,
                        default=PROJECT_ROOT / "data/chunked/specsheets")
    parser.add_argument("--application-url", default="http://localhost:8080")
    parser.add_argument("--k", type=int, nargs="+", default=[5, 10, 20, 30, 40])
    parser.add_argument("--output", type=Path,
                        default=PROJECT_ROOT / "evaluation/relevance-pool.json")
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def validate_response(response, question, k_values, corpus):
    if not isinstance(response, dict) or response.get("query") != question:
        raise ValueError("Evidence-pool endpoint returned the wrong question.")
    sets = response.get("evidenceByK")
    if not isinstance(sets, list) or [item.get("k") for item in sets] != k_values:
        raise ValueError("Evidence-pool endpoint returned unexpected k values.")
    pooled = []
    seen = set()
    for item in sets:
        evidence = item.get("evidence")
        if not isinstance(evidence, list) or len(evidence) > item["k"]:
            raise ValueError("Evidence-pool endpoint returned an invalid evidence list.")
        for selected in evidence:
            candidate = selected.get("candidate", {})
            key = f"{candidate.get('documentId')}:{candidate.get('chunkIndex')}"
            if key not in corpus:
                raise ValueError(f"Evidence-pool endpoint returned unknown chunk {key}.")
            if key not in seen:
                seen.add(key)
                pooled.append(key)
    return pooled


def main():
    args = arguments()
    k_values = sorted(set(args.k))
    if not k_values or any(k < 1 for k in k_values):
        raise ValueError("Every k must be a positive integer.")
    if args.output.exists() and not args.overwrite:
        raise ValueError(f"{args.output} exists. Use --overwrite or choose another path.")

    cases = read_json(args.dataset)
    validate_dataset(cases)
    corpus = load_corpus(args.chunks_directory)
    validate_sources(cases, corpus)
    questions = {}
    for index, case in enumerate(cases, 1):
        print(f"[{index}/{len(cases)}] retrieving {case['id']}", flush=True)
        response = post_json(
            args.application_url.rstrip("/") + "/api/rag/evidence-pool",
            {"query": case["question"], "kValues": k_values},
        )
        questions[case["id"]] = validate_response(
            response, case["question"], k_values, corpus
        )

    pool = {
        "schemaVersion": 1,
        "datasetHash": fingerprint(cases),
        "corpusHash": fingerprint(corpus),
        "kValues": k_values,
        "questions": questions,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(
        json.dumps(pool, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    temporary.replace(args.output)
    counts = [len(ids) for ids in questions.values()]
    print(
        f"Pool saved to {args.output}: {sum(counts)} pairs, "
        f"{min(counts)}-{max(counts)} chunks/question.",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
