from __future__ import annotations

import csv
import json
import zipfile
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "raw-datasets"
OUT = ROOT / "testdata" / "local-rules" / "derived-candidates"


def write_json(name: str, payload: dict) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {path}")


def sample_crosswoz(limit: int = 100) -> dict:
    base = RAW / "CrossWOZ" / "data" / "crosswoz"
    result: dict = {"dataset": "CrossWOZ", "available": base.exists()}
    if not base.exists():
        return result

    for vocab_name in ("usr_da_voc.json", "sys_da_voc.json"):
        path = base / vocab_name
        if path.exists():
            values = json.loads(path.read_text(encoding="utf-8"))
            result[vocab_name] = {
                "count": len(values),
                "sample": values[:20],
            }

    zip_path = base / "train.json.zip"
    if zip_path.exists():
        act_counter: Counter[str] = Counter()
        domain_counter: Counter[str] = Counter()
        type_counter: Counter[str] = Counter()
        goal_domains: Counter[str] = Counter()
        with zipfile.ZipFile(zip_path) as archive:
            name = archive.namelist()[0]
            data = json.loads(archive.read(name).decode("utf-8"))
        for _, dialog in list(data.items())[:limit]:
            type_counter[str(dialog.get("type", ""))] += 1
            for goal_item in dialog.get("goal", []) or []:
                if len(goal_item) > 1:
                    goal_domains[str(goal_item[1])] += 1
            for message in dialog.get("messages", []) or []:
                for act in message.get("dialog_act", []) or []:
                    if len(act) > 0:
                        act_counter[str(act[0])] += 1
                    if len(act) > 1:
                        domain_counter[str(act[1])] += 1
        result["train_sample"] = {
            "dialogues": min(limit, len(data)),
            "dialogTypes": dict(type_counter.most_common()),
            "goalDomains": dict(goal_domains.most_common()),
            "dialogActs": dict(act_counter.most_common(30)),
            "domains": dict(domain_counter.most_common(30)),
        }
    return result


def sample_cped(limit: int = 2000) -> dict:
    path = RAW / "CPED" / "data" / "CPED" / "train_split.csv"
    result: dict = {"dataset": "CPED", "available": path.exists()}
    if not path.exists():
        return result

    counters = {
        "Gender": Counter(),
        "Age": Counter(),
        "Scene": Counter(),
        "Sentiment": Counter(),
        "Emotion": Counter(),
        "DA": Counter(),
    }
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        for index, row in enumerate(reader):
            if index >= limit:
                break
            for key, counter in counters.items():
                counter[str(row.get(key, ""))] += 1
    result["train_sample"] = {
        "utterances": limit,
        "labels": {key: dict(counter.most_common(30)) for key, counter in counters.items()},
    }
    return result


def sample_naturalconv() -> dict:
    path = RAW / "NaturalConvDataSet" / "README.md"
    result: dict = {"dataset": "NaturalConvDataSet", "available": path.exists()}
    if path.exists():
        text = path.read_text(encoding="utf-8", errors="ignore")
        result["readmeSignals"] = {
            "mentionsOfficialDownload": "offical NaturalConv Dataset" in text or "official NaturalConv Dataset" in text,
            "mentionsNonCommercial": "non-commerical" in text or "non-commercial" in text,
            "mentionsGroundingDocuments": "grounding documents" in text,
        }
    return result


def main() -> None:
    payload = {
        "note": "This file stores structure summaries only. It should not contain copied external utterances.",
        "crosswoz": sample_crosswoz(),
        "cped": sample_cped(),
        "naturalconv": sample_naturalconv(),
    }
    write_json("raw_structure_summary.json", payload)


if __name__ == "__main__":
    main()
