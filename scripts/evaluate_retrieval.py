#!/usr/bin/env python3

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_ENDPOINT = "http://localhost:8080/api/retrieval/search"

DEFAULT_GOLD_FILE = Path("evaluation/retrieval-gold.json")

DEFAULT_REPORT_FILE = Path("target/retrieval-evaluation/report.json")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Evaluate document-assistant retrieval "
            "against manually reviewed gold evidence."
        )
    )

    parser.add_argument(
        "--endpoint",
        default=DEFAULT_ENDPOINT,
        help=(f"Retrieval endpoint. Default: {DEFAULT_ENDPOINT}"),
    )

    parser.add_argument(
        "--gold",
        type=Path,
        default=DEFAULT_GOLD_FILE,
        help=(f"Gold evaluation JSON. Default: {DEFAULT_GOLD_FILE}"),
    )

    parser.add_argument(
        "--report",
        type=Path,
        default=DEFAULT_REPORT_FILE,
        help=(f"Output report path. Default: {DEFAULT_REPORT_FILE}"),
    )

    parser.add_argument(
        "--timeout",
        type=float,
        default=30.0,
        help="HTTP timeout in seconds. Default: 30",
    )

    parser.add_argument(
        "--no-fail",
        action="store_true",
        help=("Always exit successfully, even when quality thresholds are missed."),
    )

    return parser.parse_args()


def load_gold_suite(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ValueError(f"Gold file does not exist: {path}")

    with path.open(
        "r",
        encoding="utf-8",
    ) as source:
        suite = json.load(source)

    cases = suite.get("cases")

    if not isinstance(cases, list) or not cases:
        raise ValueError("Gold file must contain a nonempty 'cases' array")

    default_top_k = suite.get("defaultTopK", 10)

    if not isinstance(default_top_k, int) or default_top_k < 1:
        raise ValueError("defaultTopK must be a positive integer")

    for case in cases:
        validate_gold_case(case)

    return suite


def validate_gold_case(case: dict[str, Any]) -> None:
    case_id = case.get("id")
    question = case.get("question")
    acceptable = case.get("acceptableEvidence")

    if not isinstance(case_id, str) or not case_id:
        raise ValueError("Every evaluation case requires an id")

    if not isinstance(question, str) or not question.strip():
        raise ValueError(f"Case {case_id} requires a question")

    if not isinstance(acceptable, list) or not acceptable:
        raise ValueError(
            f"Case {case_id} requires at least one acceptableEvidence entry"
        )

    for expected in acceptable:
        if not isinstance(expected, dict):
            raise ValueError(f"Case {case_id} contains invalid acceptableEvidence")


def call_retrieval_endpoint(
    endpoint: str,
    question: str,
    top_k: int,
    timeout: float,
) -> tuple[dict[str, Any], float]:
    request_body = json.dumps(
        {
            "query": question,
            "topK": top_k,
        }
    ).encode("utf-8")

    request = urllib.request.Request(
        endpoint,
        data=request_body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )

    started = time.perf_counter()

    try:
        with urllib.request.urlopen(
            request,
            timeout=timeout,
        ) as response:
            response_body = response.read()

    except urllib.error.HTTPError as exception:
        response_body = exception.read().decode(
            "utf-8",
            errors="replace",
        )

        raise RuntimeError(f"HTTP {exception.code}: {response_body}") from exception

    except urllib.error.URLError as exception:
        raise RuntimeError(
            f"Could not reach retrieval endpoint: {exception.reason}"
        ) from exception

    elapsed_ms = (time.perf_counter() - started) * 1000.0

    try:
        parsed = json.loads(response_body.decode("utf-8"))
    except json.JSONDecodeError as exception:
        raise RuntimeError("Retrieval endpoint returned invalid JSON") from exception

    results = parsed.get("results")

    if not isinstance(results, list):
        raise RuntimeError("Retrieval response is missing its 'results' array")

    return parsed, elapsed_ms


def evidence_matches(
    result: dict[str, Any],
    expected: dict[str, Any],
) -> bool:
    expected_document = expected.get("documentId")

    if expected_document is not None and result.get("documentId") != expected_document:
        return False

    expected_section = expected.get("section")

    if expected_section is not None and normalize(result.get("section")) != normalize(
        expected_section
    ):
        return False

    expected_models = expected.get(
        "modelNumbersInclude",
        [],
    )

    actual_models = result.get(
        "modelNumbers",
        [],
    )

    if not isinstance(actual_models, list):
        return False

    if not set(expected_models).issubset(set(actual_models)):
        return False

    expected_pages = expected.get(
        "pageNumbersInclude",
        [],
    )

    actual_pages = result.get(
        "pageNumbers",
        [],
    )

    if not isinstance(actual_pages, list):
        return False

    if not set(expected_pages).issubset(set(actual_pages)):
        return False

    required_source_ids = expected.get(
        "sourceElementIdsInclude",
        [],
    )

    actual_source_ids = result.get(
        "sourceElementIds",
        [],
    )

    if not isinstance(actual_source_ids, list):
        return False

    if not set(required_source_ids).issubset(set(actual_source_ids)):
        return False

    actual_text = normalize(result.get("text"))

    required_fragments = expected.get(
        "textContainsAll",
        [],
    )

    for fragment in required_fragments:
        if normalize(fragment) not in actual_text:
            return False

    optional_fragments = expected.get(
        "textContainsAny",
        [],
    )

    if optional_fragments:
        if not any(
            normalize(fragment) in actual_text for fragment in optional_fragments
        ):
            return False

    return True


def normalize(value: Any) -> str:
    if value is None:
        return ""

    return " ".join(str(value).casefold().split())


def find_first_relevant_rank(
    results: list[dict[str, Any]],
    acceptable_evidence: list[dict[str, Any]],
) -> int | None:
    for index, result in enumerate(
        results,
        start=1,
    ):
        if any(evidence_matches(result, expected) for expected in acceptable_evidence):
            return index

    return None


def summarize_result(
    result: dict[str, Any],
    rank: int,
) -> dict[str, Any]:
    text = " ".join(str(result.get("text", "")).split())

    if len(text) > 240:
        text = text[:237] + "..."

    return {
        "rank": rank,
        "similarityScore": result.get("similarityScore"),
        "documentId": result.get("documentId"),
        "chunkIndex": result.get("chunkIndex"),
        "section": result.get("section"),
        "modelNumbers": result.get(
            "modelNumbers",
            [],
        ),
        "pageNumbers": result.get(
            "pageNumbers",
            [],
        ),
        "textPreview": text,
    }


def evaluate_case(
    endpoint: str,
    case: dict[str, Any],
    default_top_k: int,
    timeout: float,
) -> dict[str, Any]:
    top_k = case.get("topK", default_top_k)

    try:
        response, latency_ms = call_retrieval_endpoint(
            endpoint=endpoint,
            question=case["question"],
            top_k=top_k,
            timeout=timeout,
        )

        results = response["results"]

        first_rank = find_first_relevant_rank(
            results,
            case["acceptableEvidence"],
        )

        matched_result = None

        if first_rank is not None:
            matched_result = summarize_result(
                results[first_rank - 1],
                first_rank,
            )

        return {
            "id": case["id"],
            "question": case["question"],
            "goldAnswer": case.get("goldAnswer"),
            "topK": top_k,
            "requestSucceeded": True,
            "hitAt1": first_rank == 1,
            "hitAtK": first_rank is not None,
            "firstRelevantRank": first_rank,
            "reciprocalRank": (1.0 / first_rank if first_rank is not None else 0.0),
            "latencyMs": round(latency_ms, 2),
            "matchedResult": matched_result,
            "returnedResults": [
                summarize_result(result, rank)
                for rank, result in enumerate(
                    results,
                    start=1,
                )
            ],
            "error": None,
        }

    except Exception as exception:
        return {
            "id": case["id"],
            "question": case["question"],
            "goldAnswer": case.get("goldAnswer"),
            "topK": top_k,
            "requestSucceeded": False,
            "hitAt1": False,
            "hitAtK": False,
            "firstRelevantRank": None,
            "reciprocalRank": 0.0,
            "latencyMs": None,
            "matchedResult": None,
            "returnedResults": [],
            "error": str(exception),
        }


def calculate_metrics(
    evaluations: list[dict[str, Any]],
) -> dict[str, Any]:
    total = len(evaluations)

    hit_at_1_count = sum(1 for evaluation in evaluations if evaluation["hitAt1"])

    hit_at_k_count = sum(1 for evaluation in evaluations if evaluation["hitAtK"])

    request_success_count = sum(
        1 for evaluation in evaluations if evaluation["requestSucceeded"]
    )

    reciprocal_rank_total = sum(
        evaluation["reciprocalRank"] for evaluation in evaluations
    )

    latencies = [
        evaluation["latencyMs"]
        for evaluation in evaluations
        if evaluation["latencyMs"] is not None
    ]

    average_latency = sum(latencies) / len(latencies) if latencies else None

    return {
        "caseCount": total,
        "successfulRequests": request_success_count,
        "hitAt1Count": hit_at_1_count,
        "hitAtKCount": hit_at_k_count,
        "hitAt1": round(
            hit_at_1_count / total,
            4,
        ),
        "hitAtK": round(
            hit_at_k_count / total,
            4,
        ),
        "mrrAtK": round(
            reciprocal_rank_total / total,
            4,
        ),
        "averageLatencyMs": (
            round(average_latency, 2) if average_latency is not None else None
        ),
    }


def check_thresholds(
    metrics: dict[str, Any],
    thresholds: dict[str, Any],
) -> list[str]:
    failures = []

    comparisons = {
        "hitAt1": metrics["hitAt1"],
        "hitAtK": metrics["hitAtK"],
        "mrrAtK": metrics["mrrAtK"],
    }

    for name, actual in comparisons.items():
        required = thresholds.get(name)

        if required is None:
            continue

        if actual < required:
            failures.append(
                f"{name} was {actual:.4f}; required at least {required:.4f}"
            )

    return failures


def print_summary(
    evaluations: list[dict[str, Any]],
    metrics: dict[str, Any],
) -> None:
    print()
    print("Retrieval evaluation")
    print("=" * 80)

    for evaluation in evaluations:
        rank = evaluation["firstRelevantRank"]
        rank_display = str(rank) if rank is not None else "MISS"

        if evaluation["requestSucceeded"]:
            status = "PASS" if evaluation["hitAtK"] else "MISS"
        else:
            status = "ERROR"

        matched = evaluation["matchedResult"]

        score = matched.get("similarityScore") if matched is not None else None

        score_display = f"{score:.4f}" if isinstance(score, (int, float)) else "-"

        print(
            f"{status:5} "
            f"rank={rank_display:>4} "
            f"score={score_display:>7} "
            f"latency={str(evaluation['latencyMs']):>8} ms "
            f"{evaluation['id']}"
        )

        if evaluation["error"]:
            print(f"      error: {evaluation['error']}")

        elif not evaluation["hitAtK"]:
            print(f"      expected: {evaluation['goldAnswer']}")

            if evaluation["returnedResults"]:
                top = evaluation["returnedResults"][0]

                print(
                    "      top result: "
                    f"{top['documentId']} / "
                    f"{top['section']} / "
                    f"chunk {top['chunkIndex']}"
                )

    print("-" * 80)

    print(f"Cases:              {metrics['caseCount']}")

    print(f"Successful requests: {metrics['successfulRequests']}")

    print(
        f"Hit@1:              "
        f"{metrics['hitAt1']:.4f} "
        f"({metrics['hitAt1Count']}/"
        f"{metrics['caseCount']})"
    )

    print(
        f"Hit@K:              "
        f"{metrics['hitAtK']:.4f} "
        f"({metrics['hitAtKCount']}/"
        f"{metrics['caseCount']})"
    )

    print(f"MRR@K:              {metrics['mrrAtK']:.4f}")

    print(f"Average latency:     {metrics['averageLatencyMs']} ms")


def write_report(
    path: Path,
    suite: dict[str, Any],
    endpoint: str,
    evaluations: list[dict[str, Any]],
    metrics: dict[str, Any],
    threshold_failures: list[str],
) -> None:
    report = {
        "suiteName": suite.get("suiteName"),
        "evaluatedAt": datetime.now(timezone.utc).isoformat(),
        "endpoint": endpoint,
        "defaultTopK": suite.get(
            "defaultTopK",
            10,
        ),
        "thresholds": suite.get(
            "thresholds",
            {},
        ),
        "metrics": metrics,
        "thresholdFailures": threshold_failures,
        "cases": evaluations,
    }

    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    with path.open(
        "w",
        encoding="utf-8",
    ) as destination:
        json.dump(
            report,
            destination,
            indent=2,
            ensure_ascii=False,
        )

        destination.write("\n")


def main() -> int:
    arguments = parse_arguments()

    try:
        suite = load_gold_suite(arguments.gold)
    except Exception as exception:
        print(
            f"Could not load gold suite: {exception}",
            file=sys.stderr,
        )

        return 1

    default_top_k = suite.get(
        "defaultTopK",
        10,
    )

    evaluations = []

    for case in suite["cases"]:
        evaluation = evaluate_case(
            endpoint=arguments.endpoint,
            case=case,
            default_top_k=default_top_k,
            timeout=arguments.timeout,
        )

        evaluations.append(evaluation)

    metrics = calculate_metrics(evaluations)

    threshold_failures = check_thresholds(
        metrics,
        suite.get("thresholds", {}),
    )

    print_summary(
        evaluations,
        metrics,
    )

    write_report(
        path=arguments.report,
        suite=suite,
        endpoint=arguments.endpoint,
        evaluations=evaluations,
        metrics=metrics,
        threshold_failures=threshold_failures,
    )

    print()
    print(f"Detailed report: {arguments.report}")

    if threshold_failures:
        print()
        print("Quality thresholds missed:")

        for failure in threshold_failures:
            print(f"  - {failure}")

        if not arguments.no_fail:
            return 2

    request_errors = [
        evaluation for evaluation in evaluations if not evaluation["requestSucceeded"]
    ]

    if request_errors and not arguments.no_fail:
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
