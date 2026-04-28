# Dataset Mining For Local Rules

This folder contains helper scripts for building CampusPulse local-rule test cases from public dialogue dataset structures.

The project should not commit raw external corpora or copied utterances. The intended pipeline is:

```text
public dataset
  -> local raw download under raw-datasets/              # ignored by git
  -> optional derived candidate notes                    # ignored by git
  -> rewritten CampusPulse-style local rule cases         # committed under testdata/local-rules/
```

## Scripts

- `fetch_sources.ps1`
  - Creates `raw-datasets/`.
  - Clones public dataset repositories where practical.
  - This is optional and can be run manually when network access is available.

- `generate_local_rule_cases.py`
  - Generates a deterministic rewritten test set from dataset-inspired label mappings.
  - Does not read or copy raw external utterances.
  - Writes JSONL files into `testdata/local-rules/`.

- `sample_raw_structures.py`
  - Reads downloaded raw datasets when available.
  - Writes structure summaries into ignored `testdata/local-rules/derived-candidates/`.
  - Does not preserve original utterance text.

- `validate_local_rule_cases.py`
  - Validates committed JSONL case files.
  - Checks required fields, duplicate IDs, module counts, JSON syntax, and key scoring-consistency constraints.

- `rewrite_guidelines.md`
  - Explains how to turn raw dataset structures into safe CampusPulse-style cases.

## Suggested Commands

From project root:

```powershell
python tools/dataset-mining/generate_local_rule_cases.py
python tools/dataset-mining/validate_local_rule_cases.py
```

Optional raw dataset fetch:

```powershell
powershell -ExecutionPolicy Bypass -File tools/dataset-mining/fetch_sources.ps1
python tools/dataset-mining/sample_raw_structures.py
```

## Scale

The committed target is a larger dataset-inspired diagnostic set:

- `scene_move`: about 180
- `turn_understanding`: about 180
- `quick_judge_trigger`: about 120
- `plot_signal`: about 150
- `heartbeat`: about 90
- `relationship_scoring`: about 240

The total target is about 960 cases. This is intentionally larger than a core
regression suite, but still small enough to regenerate and validate quickly on a
local workstation.

Later we can add a runner with two modes:

- `core`: small high-signal regression subset.
- `full`: larger diagnostic set.

## Expect Schema

Generated cases use layered expectations:

- `must`: hard assertions.
- `should`: soft expectations and warning-level drift checks.
- `mustNot`: forbidden outcomes.
- `diagnostic`: category, severity, and human-readable reason.

The generator intentionally keeps most secondary labels in `should` because CampusPulse local rules are interconnected. Only safety/continuity boundaries become hard assertions.
