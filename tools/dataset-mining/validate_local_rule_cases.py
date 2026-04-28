from __future__ import annotations

import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = ROOT / "testdata" / "local-rules"
REQUIRED = {"id", "module", "userMessage", "context", "expect", "sourceInspiredBy", "note"}
EXPECT_KEYS = {"must", "should", "mustNot", "diagnostic"}
ALLOWED_MODULES = {
    "scene_move",
    "turn_understanding",
    "quick_judge_trigger",
    "plot_signal",
    "heartbeat",
    "relationship_scoring",
}
QUICK_JUDGE_TIERS = {"urgent", "opportunistic", "background", "skip"}
TURN_PRIMARY_ACTS = {
    "answer_question",
    "accept_plan",
    "reject",
    "clarify",
    "counter_offer",
    "defer",
    "scene_move",
    "scene_stay",
    "topic_only",
    "romantic_probe",
    "emotion_share",
    "small_talk",
    "meta_repair",
    "advice_seek",
}
RELATIONSHIP_POSITIVE_DELTAS = {
    "QUALITY_QUESTION": {"closenessDeltaMin"},
    "CONCRETE_CARE_ACTION": {"closenessDeltaMin", "trustDeltaMin"},
    "CONTINUITY_ANCHOR": {"resonanceDeltaMin"},
    "BOUNDARY_RESPECT": {"trustDeltaMin"},
}
RELATIONSHIP_RISK_DELTAS = {
    "PACE_OR_CONTROL_PRESSURE": {"trustDeltaMax"},
    "LOW_EFFORT_DISMISSIVE": {"closenessDeltaMax", "resonanceDeltaMax"},
}
RELATIONSHIP_DELTA_MINS = {"closenessDeltaMin", "trustDeltaMin", "resonanceDeltaMin"}


def compact_len(text: object) -> int:
    return len("".join(str(text or "").split()))


def as_list(value: object) -> list:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def validate_semantics(item: dict, location: str, errors: list[str]) -> None:
    module = str(item.get("module", ""))
    if module not in ALLOWED_MODULES:
        errors.append(f"{location}: unknown module {module}")
        return

    context = item.get("context") if isinstance(item.get("context"), dict) else {}
    expect = item.get("expect") if isinstance(item.get("expect"), dict) else {}
    must = expect.get("must") if isinstance(expect.get("must"), dict) else {}
    should = expect.get("should") if isinstance(expect.get("should"), dict) else {}
    must_not = expect.get("mustNot") if isinstance(expect.get("mustNot"), dict) else {}

    if module == "quick_judge_trigger":
        tier = must.get("tier", should.get("tier"))
        tiers = as_list(tier)
        should_start = must.get("shouldStart", should.get("shouldStart"))
        for current_tier in tiers:
            if current_tier not in QUICK_JUDGE_TIERS:
                errors.append(f"{location}: unknown quick judge tier {current_tier}")
        current_turn = context.get("currentTurn", 0)
        periodic_background = (
            isinstance(current_turn, int)
            and current_turn > 0
            and current_turn % 4 == 0
            and compact_len(item.get("userMessage", "")) <= 120
        )
        if periodic_background and ("skip" in tiers or should_start is False):
            errors.append(f"{location}: skip expectation conflicts with periodic background review turn {current_turn}")
        if "skip" in tiers and should_start is True:
            errors.append(f"{location}: quick judge cannot both start and be skip")
        if any(current_tier in {"urgent", "opportunistic", "background"} for current_tier in tiers) and should_start is False:
            errors.append(f"{location}: started tier {tier} conflicts with shouldStart=false")
        if "background" in tiers and should.get("waitMs", 0) != 0:
            errors.append(f"{location}: background quick judge should not wait on the main path")

    elif module == "relationship_scoring":
        constraints = {**must, **should}
        acts = should.get("acts", [])
        if acts and not isinstance(acts, list):
            errors.append(f"{location}: relationship acts must be an array")
            return
        expected_min_keys: set[str] = set()
        expected_max_keys: set[str] = set()
        for act in acts:
            if act in RELATIONSHIP_POSITIVE_DELTAS:
                expected_min_keys.update(RELATIONSHIP_POSITIVE_DELTAS[act])
            elif act in RELATIONSHIP_RISK_DELTAS:
                expected_max_keys.update(RELATIONSHIP_RISK_DELTAS[act])
            else:
                errors.append(f"{location}: unknown relationship act {act}")

        for key in expected_min_keys:
            if constraints.get(key, 0) < 1:
                errors.append(f"{location}: act list requires {key}>=1")
        for key in expected_max_keys:
            if key not in constraints:
                errors.append(f"{location}: risk act list requires {key}")
        for key in RELATIONSHIP_DELTA_MINS - expected_min_keys:
            value = constraints.get(key)
            if value not in (None, 0):
                errors.append(f"{location}: {key} is not implied by declared acts {acts}")

    elif module == "turn_understanding":
        acts = should.get("primaryAct", [])
        if isinstance(acts, str):
            acts = [acts]
        if not isinstance(acts, list):
            errors.append(f"{location}: primaryAct must be string or array")
        else:
            for act in acts:
                if act not in TURN_PRIMARY_ACTS:
                    errors.append(f"{location}: unknown turn primaryAct {act}")

    elif module == "plot_signal":
        advanced = must.get("advanced", should.get("advanced"))
        action = must.get("plotDirectorAction", should.get("plotDirectorAction"))
        blocked_actions = must_not.get("plotDirectorAction", [])
        blocked_actions = blocked_actions if isinstance(blocked_actions, list) else [blocked_actions]
        if advanced is False and action == "advance_plot":
            errors.append(f"{location}: advanced=false conflicts with advance_plot")
        if advanced is True and action == "hold_plot":
            errors.append(f"{location}: advanced=true conflicts with hold_plot")
        if action in blocked_actions:
            errors.append(f"{location}: plotDirectorAction is also listed in mustNot")


def main() -> None:
    seen_ids: set[str] = set()
    counts: Counter[str] = Counter()
    errors: list[str] = []

    for path in sorted(DATA_DIR.glob("*.jsonl")):
        file_count = 0
        with path.open("r", encoding="utf-8") as handle:
            for line_number, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                file_count += 1
                try:
                    item = json.loads(line)
                except json.JSONDecodeError as exc:
                    errors.append(f"{path.name}:{line_number}: invalid json: {exc}")
                    continue

                missing = REQUIRED - set(item)
                if missing:
                    errors.append(f"{path.name}:{line_number}: missing {sorted(missing)}")
                case_id = str(item.get("id", ""))
                if not case_id:
                    errors.append(f"{path.name}:{line_number}: empty id")
                elif case_id in seen_ids:
                    errors.append(f"{path.name}:{line_number}: duplicate id {case_id}")
                seen_ids.add(case_id)

                module = str(item.get("module", ""))
                if not module:
                    errors.append(f"{path.name}:{line_number}: empty module")
                counts[module] += 1

                if not isinstance(item.get("context"), dict):
                    errors.append(f"{path.name}:{line_number}: context must be object")
                expect = item.get("expect")
                if not isinstance(expect, dict):
                    errors.append(f"{path.name}:{line_number}: expect must be object")
                else:
                    missing_expect = EXPECT_KEYS - set(expect)
                    if missing_expect:
                        errors.append(f"{path.name}:{line_number}: expect missing layers {sorted(missing_expect)}")
                    for key in EXPECT_KEYS:
                        if key in expect and not isinstance(expect.get(key), dict):
                            errors.append(f"{path.name}:{line_number}: expect.{key} must be object")
                    diagnostic = expect.get("diagnostic", {}) if isinstance(expect.get("diagnostic"), dict) else {}
                    if not diagnostic.get("category") or not diagnostic.get("reason"):
                        errors.append(f"{path.name}:{line_number}: expect.diagnostic needs category and reason")
                if not isinstance(item.get("sourceInspiredBy"), list):
                    errors.append(f"{path.name}:{line_number}: sourceInspiredBy must be array")
                validate_semantics(item, f"{path.name}:{line_number}", errors)

        print(f"{path.name}: {file_count} cases")

    print("module counts:")
    for module, count in sorted(counts.items()):
        print(f"  {module}: {count}")
    print(f"total: {sum(counts.values())} cases")

    if errors:
        print("")
        print("errors:")
        for error in errors:
            print(f"  - {error}")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
