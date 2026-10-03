#!/usr/bin/env python3

import argparse
import hashlib
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Qrels:
    relevant: dict
    judged: dict
    metadata: dict

    def __getitem__(self, question_id):
        return self.relevant[question_id]




def load_dotenv(path):
    if not path.is_file():
        return

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()

        if not line or line.startswith("#"):
            continue

        if line.startswith("export "):
            line = line[7:].strip()

        if "=" not in line:
            continue

        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()

        if len(value) >= 2 and value[0] in ("'", '"') and value[-1] == value[0]:
            value = value[1:-1]
        else:
            value = value.split(" #", 1)[0].rstrip()

        if key:
            os.environ.setdefault(key, value)


def parse_arguments():
    parser = argparse.ArgumentParser(
        description="Evaluate answer quality and latency at multiple evidence limits."
    )
    parser.add_argument(
        "--judge-prompt",
        type=Path,
        default=PROJECT_ROOT / "evaluation" / "judge-system-prompt.txt",
        help="UTF-8 judge system prompt file. Defaults to the project evaluation directory.",
    )
    parser.add_argument("--dataset", default="evaluation/questions.json")
    parser.add_argument("--chunks-directory", type=Path,
                        default=PROJECT_ROOT / "data/chunked/specsheets")
    parser.add_argument("--qrels", type=Path, default=PROJECT_ROOT / "evaluation/qrels.tsv")
    parser.add_argument("--output", default="evaluation/results/multi-k.json")
    parser.add_argument("--application-url", default="http://localhost:8080")
    parser.add_argument(
        "--judge-url",
        default=os.environ.get(
            "EVALUATION_JUDGE_URL", "https://openrouter.ai/api/v1/chat/completions"
        ),
    )
    parser.add_argument(
        "--judge-model", default=os.environ.get("EVALUATION_JUDGE_MODEL", "openai/gpt-oss-120b")
    )
    parser.add_argument(
        "--k", type=int, nargs="+", default=[5, 10, 15, 20, 25, 30, 35, 40]
    )
    parser.add_argument("--limit", type=int)
    parser.add_argument("--delay", type=float, default=0.0)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--skip-judge", action="store_true")
    return parser.parse_args()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def fingerprint(value):
    encoded = json.dumps(value, sort_keys=True, ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def post_json(url, payload, api_key=None):
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    request = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST"
    )

    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        # Do not print provider response bodies that might contain credentials.
        raise RuntimeError(f"HTTP {error.code} from {url}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"Request failed: {error.reason}") from error


def validate_dataset(cases):
    if not isinstance(cases, list) or not cases:
        raise ValueError("Dataset must be a nonempty JSON array.")

    ids = set()

    for case in cases:
        if not isinstance(case, dict):
            raise ValueError("Each dataset case must be an object.")

        for field in ("id", "question", "referenceAnswer", "requiredClaims", "sourceChunkIds"):
            if field not in case:
                raise ValueError(f"Dataset case is missing {field}.")

        if not isinstance(case["id"], str) or not case["id"].strip():
            raise ValueError("Every case must have a nonempty string ID.")

        if case["id"] in ids:
            raise ValueError(f"Duplicate case ID: {case['id']}")
        ids.add(case["id"])

        if not isinstance(case["question"], str) or not case["question"].strip():
            raise ValueError(f"{case['id']}: question must not be blank.")

        claims = case["requiredClaims"]
        if (
            not isinstance(claims, list)
            or any(not isinstance(c, str) or not c.strip() for c in claims)
        ):
            raise ValueError(
                f"{case['id']}: requiredClaims must be an array of nonempty strings."
            )
        if not claims:
            raise ValueError(f"{case['id']}: requiredClaims must not be empty.")
        sources = case["sourceChunkIds"]
        if not isinstance(sources, list) or any(not isinstance(s, str) for s in sources):
            raise ValueError(f"{case['id']}: invalid sourceChunkIds.")
        if not isinstance(case["referenceAnswer"], str) or not case["referenceAnswer"].strip():
            raise ValueError(f"{case['id']}: referenceAnswer must not be blank.")


def load_corpus(directory):
    corpus = {}
    for path in sorted(directory.glob("*-chunks.json")):
        document = read_json(path)
        for chunk in document["chunks"]:
            key = f"{chunk['documentId']}:{chunk['chunkIndex']}"
            if key in corpus:
                raise ValueError(f"Duplicate corpus chunk: {key}")
            corpus[key] = {**chunk, "revision": document.get("revision")}
    if not corpus:
        raise ValueError(f"No chunks found in {directory}")
    return corpus


def validate_sources(cases, corpus):
    for case in cases:
        for key in case["sourceChunkIds"]:
            if key not in corpus:
                raise ValueError(f"{case['id']}: missing source chunk {key}")


def read_qrels(path, all_cases, selected_cases, corpus):
    if not path.is_file() or not path.with_suffix(".metadata.json").is_file():
        raise ValueError(
            "Required qrels are missing. Follow evaluation/POOLED-QRELS.md or "
            "run an exhaustive relevance labeler first."
        )
    metadata = read_json(path.with_suffix(".metadata.json"))
    if metadata.get("datasetHash") != fingerprint(all_cases):
        raise ValueError("Qrels belong to a different dataset. Regenerate labels.")
    if metadata.get("corpusHash") != fingerprint(corpus):
        raise ValueError("Qrels belong to a different corpus. Regenerate labels.")
    case_ids = {case["id"] for case in all_cases}
    labels = {case_id: {} for case_id in case_ids}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        fields = line.split()
        if len(fields) != 4:
            raise ValueError("Qrels lines must have: questionId 0 chunkId relevance")
        case_id, iteration, chunk_id, relevance = fields
        if case_id not in labels or iteration != "0" or chunk_id not in corpus or relevance not in ("0", "1"):
            raise ValueError(f"Invalid qrels entry: {line}")
        if chunk_id in labels[case_id]:
            raise ValueError(f"Duplicate qrels entry: {case_id} {chunk_id}")
        labels[case_id][chunk_id] = int(relevance)
    pooled = metadata.get("labelingMode") == "retrieved-pool-question-batch-codex"
    expected_by_case = metadata.get("judgedChunkIdsByQuestion") if pooled else None
    if pooled and (not isinstance(expected_by_case, dict)
                   or set(expected_by_case) != case_ids):
        raise ValueError("Pooled qrels metadata has an invalid judged-chunk map.")
    if pooled:
        for case_id, chunk_ids in expected_by_case.items():
            if (not isinstance(chunk_ids, list)
                    or len(chunk_ids) != len(set(chunk_ids))
                    or any(key not in corpus for key in chunk_ids)):
                raise ValueError(
                    f"Pooled qrels metadata has invalid chunks for {case_id}."
                )
    for case in selected_cases:
        expected = set(expected_by_case[case["id"]]) if pooled else set(corpus)
        if set(labels[case["id"]]) != expected:
            raise ValueError(
                f"{case['id']}: incomplete qrels. Finish relevance labeling first."
            )
    return Qrels(
        relevant={case_id: {key for key, value in entries.items() if value}
                  for case_id, entries in labels.items()},
        judged={case_id: set(entries) for case_id, entries in labels.items()},
        metadata=metadata,
    )


def validate_answer_response(response, k):
    for field in ("answer", "evidence", "retrievalDurationMs", "inferenceDurationMs"):
        if field not in response:
            raise ValueError(
                f"Backend response is missing {field}. "
                "Copy the updated HybridAnswerService.java and restart the application."
            )

    if not isinstance(response["evidence"], list):
        raise ValueError("Backend evidence must be an array.")
    if not isinstance(response["answer"], str):
        raise ValueError("Backend answer must be text.")

    if len(response["evidence"]) > k:
        raise ValueError(
            f"Requested k={k}, but backend returned "
            f"{len(response['evidence'])} evidence chunks."
        )

    for field in ("retrievalDurationMs", "inferenceDurationMs"):
        value = response[field]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
            raise ValueError(f"Invalid backend timing: {field}")


def judge_input(case, response):
    evidence = []

    for item in response["evidence"]:
        candidate = item["candidate"]
        evidence.append(
            {
                "sourceNumber": item["sourceNumber"],
                "documentId": candidate["documentId"],
                "chunkIndex": candidate["chunkIndex"],
                "text": candidate["text"],
            }
        )

    return {
        "question": case["question"],
        "referenceAnswer": case["referenceAnswer"],
        "requiredClaims": case["requiredClaims"],
        "referenceEvidence": case["referenceEvidence"],
        "answer": response["answer"],
        "retrievedEvidence": evidence,
    }


def parse_judgment(content):
    if not isinstance(content, str):
        raise ValueError("Judge returned no text content.")

    text = content.strip()

    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[-1].strip() == "```":
            text = "\n".join(lines[1:-1]).strip()

    result = json.loads(text)

    if not isinstance(result, dict):
        raise ValueError("Judge must return a JSON object.")

    return result


def validate_judgment(judgment, required_count):
    generated = judgment.get("generatedClaims")
    checks = judgment.get("requiredClaimChecks")

    if not isinstance(generated, list) or not isinstance(checks, list):
        raise ValueError("Judge claim fields must be arrays.")

    for claim in generated:
        if not isinstance(claim, dict):
            raise ValueError("Generated claim must be an object.")
        if not isinstance(claim.get("text"), str):
            raise ValueError("Generated claim text is missing.")
        for field in ("correct",):
            if type(claim.get(field)) is not bool:
                raise ValueError(f"Generated claim {field} must be boolean.")

    indices = []

    for check in checks:
        if not isinstance(check, dict):
            raise ValueError("Required claim check must be an object.")
        if type(check.get("index")) is not int:
            raise ValueError("Required claim index must be an integer.")
        indices.append(check["index"])

        for field in ("correctlyAnswered",):
            if type(check.get(field)) is not bool:
                raise ValueError(f"Required claim {field} must be boolean.")

    if sorted(indices) != list(range(required_count)):
        raise ValueError("Judge must return exactly one check for each required claim.")
    if not isinstance(judgment.get("reason"), str):
        raise ValueError("Judge must explain its assessment.")


def call_judge(args, api_key, case, response, judge_prompt):
    provider_response = post_json(
        args.judge_url,
        {
            "model": args.judge_model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": judge_prompt},
                {
                    "role": "user",
                    "content": json.dumps(
                        judge_input(case, response), ensure_ascii=False
                    ),
                },
            ],
        },
        api_key,
    )

    judgment = parse_judgment(provider_response["choices"][0]["message"]["content"])
    validate_judgment(judgment, len(case["requiredClaims"]))
    return judgment


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def claim_metrics(precision, recall):
    return {"precision": precision, "recall": recall}


def calculate_metrics(judgment):
    generated = judgment["generatedClaims"]
    required = judgment["requiredClaimChecks"]

    metrics = {
        "correctness": claim_metrics(
            ratio(sum(c["correct"] for c in generated), len(generated)),
            ratio(sum(c["correctlyAnswered"] for c in required), len(required)),
        ),
    }
    return metrics


def calculate_ranking_metrics(relevant, response, k, judged=None):
    seen = set()
    hits = 0
    precision_sum = 0.0
    first_rank = None
    # Score exactly the selected excerpts supplied to this answer prompt.
    for rank, item in enumerate(response["evidence"][:k], 1):
        chunk = item["candidate"]
        key = f"{chunk['documentId']}:{chunk['chunkIndex']}"
        if judged is not None and key not in judged:
            raise ValueError(
                f"Retrieved chunk {key} is outside the frozen relevance pool. "
                "Rebuild and relabel the pool for these retrieval settings."
            )
        if key in relevant and key not in seen:
            hits += 1
            precision_sum += hits / rank
            if first_rank is None:
                first_rank = rank
        seen.add(key)
    return {
        "precisionAtK": hits / k if relevant else None,
        "recallAtK": hits / len(relevant) if relevant else None,
        "apAtK": precision_sum / len(relevant) if relevant else None,
        "reciprocalRankAtK": (1 / first_rank if first_rank else 0.0) if relevant else None,
        "relevantRetrievedCount": hits,
        "goldRelevantCount": len(relevant),
    }


def mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def percentile(values, fraction):
    if not values:
        return None

    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)

    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def summarize(results, k_values):
    by_k = {}

    for k in k_values:
        runs = [r for r in results if r["k"] == k]
        answered = [r for r in runs if r.get("response") is not None]
        judged = [r for r in runs if r.get("metrics") is not None]

        summary = {
            "caseCount": len(answered),
            "judgedCaseCount": len(judged),
            "errorCount": sum(r.get("error") is not None for r in runs),
            "meanEvidenceChunkCount": mean(
                [len(r["response"]["evidence"]) for r in answered]
            ),
            "latencyMs": {},
        }

        for field in ("retrievalDurationMs", "inferenceDurationMs"):
            values = [r["response"][field] for r in answered]
            summary["latencyMs"][field] = {
                "mean": mean(values),
                "p95": percentile(values, 0.95),
            }

        if judged:
            for dimension in ("correctness",):
                summary[dimension] = {
                    metric: mean([r["metrics"][dimension][metric] for r in judged])
                    for metric in ("precision", "recall")
                }
                summary[dimension + "CaseCounts"] = {
                    metric: sum(r["metrics"][dimension][metric] is not None for r in judged)
                    for metric in ("precision", "recall")
                }

        ranked = [r for r in runs if r.get("rankingMetrics") is not None]
        summary["rankingCaseCount"] = sum(r["rankingMetrics"]["goldRelevantCount"] > 0 for r in ranked)
        summary["zeroRelevantCaseCount"] = sum(r["rankingMetrics"]["goldRelevantCount"] == 0 for r in ranked)
        summary["evidenceRanking"] = None if not ranked else {
            name: mean([r["rankingMetrics"][source] for r in ranked])
            for name, source in (("precisionAtK", "precisionAtK"),
                                 ("recallAtK", "recallAtK"),
                                 ("mapAtK", "apAtK"),
                                 ("mrrAtK", "reciprocalRankAtK"))
        }

        by_k[str(k)] = summary

    return {"byK": by_k}


def save_report(path, configuration, results, k_values):
    report = {
        "configuration": configuration,
        "summary": summarize(results, k_values),
        "results": results,
    }

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    temporary.replace(path)


def main():
    load_dotenv(PROJECT_ROOT / ".env")
    args = parse_arguments()

    judge_prompt = None
    if not args.skip_judge:
        try:
            judge_prompt = args.judge_prompt.read_text(encoding="utf-8").strip()
        except OSError as error:
            raise ValueError(f"Could not read judge prompt: {args.judge_prompt}") from error
        if not judge_prompt:
            raise ValueError(f"Judge prompt is empty: {args.judge_prompt}")

    k_values = sorted(set(args.k))
    if not k_values or any(k < 1 for k in k_values):
        raise ValueError("All k values must be positive.")
    if args.limit is not None and args.limit < 1:
        raise ValueError("--limit must be positive.")

    cases = read_json(Path(args.dataset))
    validate_dataset(cases)

    corpus = load_corpus(args.chunks_directory)
    validate_sources(cases, corpus)

    all_cases = cases
    if args.limit is not None:
        cases = cases[: args.limit]
    qrels = read_qrels(args.qrels, all_cases, cases, corpus)
    if qrels.metadata.get("poolKValues") is not None \
            and not set(k_values).issubset(set(qrels.metadata["poolKValues"])):
        raise ValueError(
            "Evaluation k values are outside the frozen relevance pool. "
            "Rebuild and relabel the pool."
        )

    api_key = os.environ.get("OPENROUTER_API_KEY")
    if not args.skip_judge:
        if not api_key:
            raise ValueError("OPENROUTER_API_KEY is missing from .env.")
        if not args.judge_model:
            raise ValueError("EVALUATION_JUDGE_MODEL is missing from .env.")

    configuration = {
        "schemaVersion": 8,
        "datasetHash": fingerprint(cases),
        "corpusHash": fingerprint(corpus),
        "rankingStage": "Final evidence order supplied to the answer model",
        "qrelsHash": fingerprint(args.qrels.read_text(encoding="utf-8")),
        "relevanceJudgmentScope": qrels.metadata.get(
            "labelingMode", "exhaustive-corpus"
        ),
        "poolKValues": qrels.metadata.get("poolKValues"),
        "apDenominator": "Total relevant judged chunks in the qrels scope (not min(R,k))",
        "judgePromptHash": fingerprint(judge_prompt) if judge_prompt is not None else None,
        "applicationUrl": args.application_url,
        "judgeUrl": args.judge_url,
        "judgeModel": None if args.skip_judge else args.judge_model,
        "judgeEnabled": not args.skip_judge,
        "kValues": k_values,
        "questionCount": len(cases),
    }

    output = Path(args.output)
    results = {}

    if output.exists():
        if not args.resume:
            raise ValueError(
                f"{output} already exists. Use --resume or a new output path."
            )

        previous = read_json(output)
        if previous.get("configuration") != configuration:
            raise ValueError(
                "Existing report configuration differs. Use a new output path."
            )

        results = {(r["id"], r["k"]): r for r in previous["results"]}

    total = len(cases) * len(k_values)
    position = 0

    for case in cases:
        case["referenceEvidence"] = [
            {"chunkId": key, "text": corpus[key]["text"],
             "revision": corpus[key].get("revision"),
             "pageNumbers": corpus[key].get("pageNumbers", [])}
            for key in case["sourceChunkIds"]
        ]
        for k in k_values:
            position += 1
            key = (case["id"], k)
            existing = results.get(key)

            if (existing and existing.get("error") is None
                    and existing.get("response") is not None
                    and existing.get("rankingMetrics") is not None
                    and (args.skip_judge or existing.get("metrics") is not None)):
                print(
                    f"[{position}/{total}] {case['id']} k={k}: already complete",
                    flush=True,
                )
                continue

            print(f"[{position}/{total}] {case['id']} k={k}", flush=True)

            result = existing or {
                "id": case["id"],
                "question": case["question"],
                "category": case.get("category"),
                "k": k,
                "response": None,
                "judgment": None,
                "metrics": None,
                "rankingMetrics": None,
                "error": None,
            }
            result["error"] = None

            try:
                if result["response"] is None:
                    response = post_json(
                        args.application_url.rstrip("/") + "/api/rag/answer",
                        {"query": case["question"], "topK": k},
                    )
                    validate_answer_response(response, k)
                    result["response"] = response

                    # Save generation before spending money on judging.
                    results[key] = result
                    save_report(output, configuration, list(results.values()), k_values)

                result["rankingMetrics"] = calculate_ranking_metrics(
                    qrels[case["id"]], result["response"], k,
                    qrels.judged[case["id"]])

                if not args.skip_judge and result["judgment"] is None:
                    result["judgment"] = call_judge(
                        args, api_key, case, result["response"], judge_prompt
                    )

                if result["judgment"] is not None:
                    result["metrics"] = calculate_metrics(result["judgment"])

            except Exception as error:
                result["error"] = str(error)
                print(f"  ERROR: {error}", file=sys.stderr, flush=True)

            results[key] = result
            save_report(output, configuration, list(results.values()), k_values)

            if args.delay > 0:
                time.sleep(args.delay)

    final_results = list(results.values())
    print(json.dumps(summarize(final_results, k_values), indent=2))
    print(f"Report saved to {output}")

    return 1 if any(r.get("error") for r in final_results) else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
