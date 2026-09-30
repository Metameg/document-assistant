#!/usr/bin/env python3
"""Read-only inventory of specifications in processed document JSON."""

from collections import Counter, defaultdict
from dataclasses import dataclass
import json
from pathlib import Path
import re
import sys


@dataclass(frozen=True)
class Observation:
    document_id: str
    revision: str
    model: str
    section: str
    name: str
    value: str
    units: tuple[str, ...]
    page: int
    element_id: str


def normalized_name(name: str) -> str:
    return re.sub(r"\s+", " ", name.strip()).casefold()


def read_observations(directory: Path) -> tuple[list[Path], list[Observation]]:
    files = sorted(directory.glob("*.json"))
    observations = []

    for path in files:
        with path.open(encoding="utf-8") as source:
            document = json.load(source)

        if document.get("schemaVersion") != "1.3":
            raise ValueError(f"{path}: expected schema version 1.3")

        document_id = document["documentId"]
        revision = (document.get("revision") or {}).get("revision") or "—"

        for section in document["sections"]:
            section_models = section["modelNumbers"]

            for element in section["elements"]:
                if element.get("type") != "specification":
                    continue

                shared_value = element.get("sharedValue")
                per_model = element.get("valuesByModel") or {}

                if shared_value is not None:
                    values = ((model, shared_value) for model in section_models)
                else:
                    values = per_model.items()

                for model, value in values:
                    if value is None or str(value).strip() == "":
                        continue

                    observations.append(
                        Observation(
                            document_id=document_id,
                            revision=revision,
                            model=model,
                            section=section["title"],
                            name=element["name"],
                            value=str(value),
                            units=tuple(element.get("units") or ()),
                            page=element["pageNumber"],
                            element_id=element["elementId"],
                        )
                    )

    return files, observations


def print_inventory(observations: list[Observation]) -> None:
    fields = defaultdict(list)
    model_sources = defaultdict(set)

    for item in observations:
        fields[normalized_name(item.name)].append(item)
        model_sources[item.model].add((item.document_id, item.revision))

    print(f"Specification observations: {len(observations)}")
    print(f"Distinct specification names: {len(fields)}")
    print(f"Distinct model numbers: {len(model_sources)}")
    print()

    print("FIELDS")
    print("Name | Sections | Models | Documents | Units | Example values")

    for key in sorted(fields):
        items = fields[key]
        display_name = Counter(item.name for item in items).most_common(1)[0][0]
        sections = ", ".join(sorted({item.section for item in items}))
        models = len({item.model for item in items})
        documents = len({item.document_id for item in items})
        units = (
            ", ".join(sorted({unit for item in items for unit in item.units})) or "—"
        )
        examples = ", ".join(dict.fromkeys(item.value for item in items))
        if len(examples) > 100:
            examples = examples[:97] + "..."

        print(
            f"{display_name} | {sections} | {models} | "
            f"{documents} | {units} | {examples}"
        )

    print()
    print("MODELS APPEARING IN MULTIPLE DOCUMENT/REVISION PAIRS")
    repeated = {
        model: sources for model, sources in model_sources.items() if len(sources) > 1
    }
    print(f"Count: {len(repeated)}")

    for model, sources in sorted(repeated.items())[:15]:
        locations = ", ".join(
            f"{document_id} rev {revision}" for document_id, revision in sorted(sources)
        )
        print(f"{model}: {locations}")


def main() -> None:
    directory = Path(sys.argv[1] if len(sys.argv) > 1 else "data/processed/specsheets")

    if not directory.is_dir():
        raise SystemExit(f"Directory does not exist: {directory}")

    files, observations = read_observations(directory)

    if not files:
        raise SystemExit(f"No JSON files found in: {directory}")

    print(f"Processed documents: {len(files)}")
    print_inventory(observations)


if __name__ == "__main__":
    main()
