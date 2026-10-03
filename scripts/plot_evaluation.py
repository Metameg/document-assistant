#!/usr/bin/env python3

import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt


def arguments():
    parser = argparse.ArgumentParser(
        description="Plot a multi-K RAG evaluation report."
    )
    parser.add_argument("--input", default="evaluation/results/multi-k.json")
    parser.add_argument("--output-directory", default="evaluation/results/plots")
    return parser.parse_args()


def load_summary(path):
    report = json.loads(path.read_text(encoding="utf-8"))
    by_k = report["summary"]["byK"]
    k_values = sorted(int(value) for value in by_k)
    return k_values, by_k


def save_quality_plot(k_values, by_k, dimension, title, output):
    colors = {"precision": "#2563eb", "recall": "#16a34a"}

    plt.figure(figsize=(9, 5.5))
    for metric in ["precision", "recall"]:
        values = [by_k[str(k)][dimension][metric] for k in k_values]
        plt.plot(
            k_values,
            values,
            marker="o",
            linewidth=2,
            label=("Precision@k" if metric == "precision" else "Recall@k"),
            color=colors[metric],
        )

    plt.title(title)
    plt.xlabel("Requested final evidence limit (k)")
    plt.ylabel("Answer claim score")
    plt.ylim(0, 1.05)
    plt.xticks(k_values)
    plt.grid(alpha=0.25)
    plt.legend()
    plt.tight_layout()
    plt.savefig(output, dpi=180)
    plt.close()


def save_latency_plot(k_values, by_k, timing, title, output):
    colors = {"mean": "#2563eb", "p95": "#dc2626"}

    plt.figure(figsize=(9, 5.5))
    for statistic in ["mean", "p95"]:
        values = [
            (by_k[str(k)]["latencyMs"][timing][statistic] / 1000.0
             if by_k[str(k)]["latencyMs"][timing][statistic] is not None else None)
            for k in k_values
        ]
        plt.plot(
            k_values,
            values,
            marker="o",
            linewidth=2,
            label=statistic.upper(),
            color=colors[statistic],
        )

    plt.title(title)
    plt.xlabel("Requested final evidence limit (k)")
    plt.ylabel("Seconds")
    plt.xticks(k_values)
    plt.grid(alpha=0.25)
    plt.legend()
    plt.tight_layout()
    plt.savefig(output, dpi=180)
    plt.close()


def main():
    args = arguments()
    input_path = Path(args.input)
    output_directory = Path(args.output_directory)
    output_directory.mkdir(parents=True, exist_ok=True)

    k_values, by_k = load_summary(input_path)

    judged_k = [k for k in k_values if "correctness" in by_k[str(k)]]
    if judged_k:
        save_quality_plot(
            judged_k,
            by_k,
            "correctness",
            "Answer correctness (factual claims)",
            output_directory / "answer-correctness.png",
        )
    ranked_k = [k for k in k_values if by_k[str(k)].get("evidenceRanking")]
    if ranked_k:
        ranking = {str(k): by_k[str(k)]["evidenceRanking"] for k in ranked_k}
        save_additional_plot(
            ranked_k, ranking,
            {"precisionAtK": "Precision@k", "recallAtK": "Recall@k"},
            "Chunk retrieval: precision and recall",
            output_directory / "retrieval-ranking.png")
        save_additional_plot(
            ranked_k, ranking,
            {"mapAtK": "mAP@k", "mrrAtK": "MRR@k"},
            "Chunk retrieval: mAP and MRR",
            output_directory / "retrieval-map-mrr.png")

    save_latency_plot(
        k_values,
        by_k,
        "retrievalDurationMs",
        "Hybrid retrieval latency by evidence depth",
        output_directory / "retrieval-latency.png",
    )
    save_latency_plot(
        k_values,
        by_k,
        "inferenceDurationMs",
        "Answer-model latency by evidence depth",
        output_directory / "inference-latency.png",
    )

    print(f"Plots written to {output_directory}")


def save_additional_plot(k_values, by_k, metrics, title, output):
    plt.figure(figsize=(9, 5.5))
    for metric, label in metrics.items():
        plt.plot(k_values, [by_k[str(k)].get(metric) for k in k_values],
                 marker="o", linewidth=2, label=label)
    plt.title(title)
    plt.xlabel("Requested final evidence limit (k)")
    plt.ylabel("Score")
    plt.ylim(0, 1.05)
    plt.xticks(k_values)
    plt.grid(alpha=0.25)
    plt.legend()
    plt.tight_layout()
    plt.savefig(output, dpi=180)
    plt.close()


if __name__ == "__main__":
    main()
