#!/usr/bin/env python3
"""Evaluate natural-language questions against a local RAG answer API."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import time
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES = PROJECT_ROOT / "evaluation" / "rag_user_questions.json"
ENDPOINTS = "/api/rag/answer"

DASH_TRANSLATION = str.maketrans(
    {
        "\u2010": "-",
        "\u2011": "-",
        "\u2012": "-",
        "\u2013": "-",
        "\u2014": "-",
        "\u2015": "-",
        "\u2212": "-",
    }
)


def normalized(value):
    text = unicodedata.normalize("NFKC", str(value))
    text = text.translate(DASH_TRANSLATION).casefold()
    return " ".join(text.split())


def request_answer(base_url, endpoint, question, top_k):
    payload = json.dumps({"query": question, "topK": top_k}).encode("utf-8")
    request = Request(
        base_url.rstrip("/") + endpoint,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    for attempt in range(3):
        try:
            with urlopen(request, timeout=120) as response:
                return json.load(response)
        except HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            if exc.code in (429, 502, 503, 504) and attempt < 2:
                wait_seconds = 5 * (2**attempt)
                print(f"  HTTP {exc.code}; retrying in {wait_seconds}s")
                time.sleep(wait_seconds)
                continue
            raise RuntimeError(f"HTTP {exc.code}: {detail[:500]}") from exc
        except URLError as exc:
            raise RuntimeError(f"Connection failed: {exc.reason}") from exc

    raise RuntimeError("No response after retries")


def evidence_sources(response, endpoint):
    if endpoint == "/api/rag/answer":
        retrieval = response.get("retrieval")
        if not isinstance(retrieval, dict):
            raise ValueError("Response has no retrieval object")
        sources = retrieval.get("results")
        if not isinstance(sources, list):
            raise ValueError("Response retrieval has no results array")
        return [
            dict(source, sourceNumber=index) for index, source in enumerate(sources, 1)
        ]

    evidence = response.get("evidence")
    if not isinstance(evidence, list):
        raise ValueError("Hybrid response has no evidence array")

    sources = []
    for item in evidence:
        if not isinstance(item, dict) or not isinstance(item.get("candidate"), dict):
            raise ValueError("Hybrid evidence item has no candidate")
        number = item.get("sourceNumber")
        if not isinstance(number, int) or isinstance(number, bool) or number < 1:
            raise ValueError("Hybrid evidence has an invalid sourceNumber")
        sources.append(dict(item["candidate"], sourceNumber=number))

    numbers = [source["sourceNumber"] for source in sources]
    if len(numbers) != len(set(numbers)):
        raise ValueError("Hybrid evidence has duplicate source numbers")
    return sources


def source_models(source):
    models = source.get("modelNumbers")
    if isinstance(models, list):
        return [normalized(model) for model in models]

    # Hybrid candidates expose the chunk text, whose header lists models.
    text = source.get("text", "")
    match = re.search(r"^Models:[ \t]*([^\r\n]+)", text, flags=re.MULTILINE)
    if not match:
        return []
    return [normalized(model) for model in match.group(1).split(",")]


def source_matches(source, requirement):
    if normalized(requirement["model"]) not in source_models(source):
        return False

    section = requirement.get("section")
    if section and normalized(section) != normalized(source.get("section", "")):
        return False

    source_text = normalized(source.get("text", ""))
    return all(
        normalized(phrase) in source_text for phrase in requirement.get("contains", [])
    )


def check_citations(answer, source_numbers, require_citation):
    flags = []
    bracketed = [int(number) for number in re.findall(r"\[(\d+)\]", answer)]
    decorated = [int(number) for number in re.findall(r"【(\d+)(?:†[^】]*)?】", answer)]
    cited_numbers = bracketed + decorated

    if "【" in answer:
        flags.append("nonstandard_citation_format")
    if re.search(r"†L\d+", answer):
        flags.append("unsupported_line_numbers")

    invalid = sorted(set(cited_numbers) - set(source_numbers))
    if invalid:
        flags.append(f"source_numbers_out_of_range:{invalid}")
    if require_citation and source_numbers and not cited_numbers:
        flags.append("missing_source_citation")
    return flags


def evaluate_case(case, response, endpoint):
    sources = evidence_sources(response, endpoint)
    answer = response.get("answer")
    if not isinstance(answer, str):
        raise ValueError("Response has no answer string")

    evidence_checks = [
        {
            "requirement": requirement,
            "found": any(source_matches(source, requirement) for source in sources),
        }
        for requirement in case.get("evidence", [])
    ]

    answer_text = normalized(answer)
    answer_checks = [
        {
            "alternatives": alternatives,
            "found": any(
                normalized(alternative) in answer_text for alternative in alternatives
            ),
        }
        for alternatives in case.get("answer_any", [])
    ]

    if evidence_checks and not all(check["found"] for check in evidence_checks):
        status = "RETRIEVAL_MISS"
    elif answer_checks and not all(check["found"] for check in answer_checks):
        status = "ANSWER_MISS"
    elif answer_checks:
        status = "PASS"
    else:
        status = "REVIEW"

    citation_flags = check_citations(
        answer,
        [source["sourceNumber"] for source in sources],
        require_citation=case["category"] != "uncertainty",
    )

    return {
        "id": case["id"],
        "category": case["category"],
        "question": case["question"],
        "status": status,
        "answer": answer,
        "result_count": len(sources),
        "evidence_checks": evidence_checks,
        "answer_checks": answer_checks,
        "citation_flags": citation_flags,
        "response": response,
    }


def print_result(result):
    print(
        f"  {result['status']}"
        f" | sources={result.get('result_count', '-')}"
        f" | citation flags={result.get('citation_flags', [])}"
    )

    if result["status"] == "ERROR":
        print(f"  Error: {result['error']}")
        return

    print("  Answer:")
    for line in result["answer"].splitlines():
        print(f"    {line}")

    for check in result["evidence_checks"]:
        if not check["found"]:
            print(f"  Missing evidence: {check['requirement']}")

    for check in result["answer_checks"]:
        if not check["found"]:
            print(
                "  Missing answer phrase; expected one of: "
                + ", ".join(check["alternatives"])
            )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--endpoint", choices=ENDPOINTS, default=ENDPOINTS[0])
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--delay", type=float, default=3.0)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--ids", help="Comma-separated IDs, such as R1,R4,F1")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    if args.top_k < 1 or args.delay < 0:
        parser.error("--top-k must be positive and --delay cannot be negative")

    dataset = json.loads(args.cases.read_text(encoding="utf-8"))
    cases = dataset["cases"]

    if args.ids:
        selected = {case_id.strip() for case_id in args.ids.split(",")}
        cases = [case for case in cases if case["id"] in selected]

    if args.limit is not None:
        if args.limit < 1:
            parser.error("--limit must be positive")
        cases = cases[: args.limit]

    if not cases:
        parser.error("No cases selected")

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_name = "rag-user"
    output = args.output or (
        PROJECT_ROOT / "evaluation" / "runs" / f"{run_name}-{timestamp}.json"
    )

    results = []
    for index, case in enumerate(cases, start=1):
        print(f"[{index}/{len(cases)}] {case['id']}: {case['question']}")
        try:
            response = request_answer(
                args.base_url, args.endpoint, case["question"], args.top_k
            )
            result = evaluate_case(case, response, args.endpoint)
        except (RuntimeError, ValueError, KeyError) as exc:
            result = {
                "id": case["id"],
                "category": case["category"],
                "question": case["question"],
                "status": "ERROR",
                "error": str(exc),
            }

        results.append(result)
        print_result(result)

        if result["status"] == "ERROR" and "HTTP 429" in result["error"]:
            print("Stopping after a persistent HTTP 429.")
            break
        if index < len(cases):
            time.sleep(args.delay)

    summary = dict(Counter(result["status"] for result in results))
    report = {
        "created_at_utc": timestamp,
        "dataset": str(args.cases),
        "base_url": args.base_url,
        "endpoint": args.endpoint,
        "top_k_per_request": args.top_k,
        "planned_cases": len(cases),
        "completed_cases": len(results),
        "summary": summary,
        "results": results,
    }

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print(f"\nSummary: {summary}")
    print(f"Report: {output}")


if __name__ == "__main__":
    main()
