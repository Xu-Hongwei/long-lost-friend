# Local Rules Test Data

这个目录用于沉淀 CampusPulse 本地规则的测试样例。

目标不是保存真实用户聊天，也不是复制外部公开数据集原文，而是把公开数据集/论文里的标注维度转化成适合本项目的校园聊天短句，用来持续验证本地规则是否稳定。

## 定位说明

这一批数据是 `rule_contract`，也就是规则契约集。它的作用是确认我们已经定义过的规则边界没有被改坏，例如地点话题不能误判成移动、用户纠错不能继续推剧情、低投入敷衍不能增加关系分。

它不是独立真实评测集，也不能单独证明规则足够智能。真正用于发现规则盲点的数据应放在上一级目录的 `bug-replay/`、`human-labeled/`、`adversarial/`、`external-rewrite/` 和 `blind-eval/` 中。

## 使用原则

- 每条样例必须是项目自造短句，不直接搬运网络对话原文。
- 每条样例都要写清楚输入、上下文和期望结果。
- 新发现的坏例子先加入测试集，再改规则，再跑回归。
- 如果一个样例来自截图或手动复盘，需要去掉任何真实个人信息。
- 期望值可以先写成宽松范围，等 runner 完成后再逐步收紧。

## 文件说明

- `SOURCES.md`：公开资料来源和借鉴维度。
- `scene_move_cases.jsonl`：场景移动、地点话题、已到达、取消移动。
- `turn_understanding_cases.jsonl`：用户回应动作、上一轮义务、模糊短句。
- `quick_judge_trigger_cases.jsonl`：QuickJudge 触发等级和等待策略。
- `plot_signal_cases.jsonl`：本轮信号、剧情蓄力、剧情推进保护。
- `heartbeat_cases.jsonl`：长聊心跳是否承接用户和角色自己上一句。
- `relationship_scoring_cases.jsonl`：关系评分结构化 act 和分数方向。
- `RULE_AUDIT_2026-04-27.md`：2026-04-27 本地规则回归审计，记录 runner 结果、warn/fail 含义、已修正范围和后续维护方式。
- `WARN_TRIAGE_2026-04-27.md`：当前 warning 的分类审查，区分真实规则问题、runner/数据契约问题和暂时观察项。

## 当前规模

当前大样例集由 `tools/dataset-mining/generate_local_rule_cases.py` 生成，共 960 条：

- `scene_move`: 180
- `turn_understanding`: 180
- `quick_judge_trigger`: 120
- `plot_signal`: 150
- `heartbeat`: 90
- `relationship_scoring`: 240

这些样例不是外部语料原句，而是根据 CPED / CrossWOZ / NaturalConv / MPDD 的标签和任务结构改写成 CampusPulse 校园聊天语境。

## 通用字段

```json
{
  "id": "stable_case_id",
  "module": "scene_move",
  "userMessage": "用户本轮输入",
  "context": {},
  "expect": {
    "must": {},
    "should": {},
    "mustNot": {},
    "diagnostic": {}
  },
  "sourceInspiredBy": ["NaturalConv"],
  "note": "为什么要有这个样例"
}
```

## Expect Schema

为了避免大规模样例误报，`expect` 分成四层：

- `must`：硬边界。失败就是 bug，例如地点偏好问题不能触发移动、用户纠错不能继续推进剧情。
- `should`：软期望。失败时记为 warning，用于观察规则漂移，例如最好识别成 `topic_only`，但 `no_change` 也可能合理。
- `mustNot`：禁止结果。失败就是 bug，例如不能把 `sceneMoveKind` 判成 `move_to`。
- `diagnostic`：诊断信息，不参与判定，用来说明分类、严重度和风险。

推荐 runner 输出三种状态：

- `fail`：`must` 或 `mustNot` 失败。
- `warn`：`should` 不符合。
- `pass`：硬规则通过，且软期望也符合。

数字类字段尽量写成趋势或范围，不写死绝对值。例如 `minPlotSignal`、`trustDeltaMin`、`closenessDeltaMax`。

## 推荐工作流

1. 从实际聊天问题或规则改动中提炼样例。
2. 先把样例写进对应 jsonl 文件。
3. 再改本地规则或提示词。
4. 之后接入 runner，自动喂给本地模块检查。

第一阶段只维护数据，不强制接入自动测试；现在已经有 Java runner 可以把这些样例自动喂给本地规则模块。

格式与关键评分一致性校验：

```powershell
python tools/dataset-mining/validate_local_rule_cases.py
```

真实规则回归：

```powershell
.\run-local-rules.ps1
```

详细报告会写入 `build/local-rule-report.json`。
