# 2026-04-27 本地规则回归审计

这份记录用于沉淀本轮本地规则测试与修正过程。它不是新的评分规则总纲，稳定规则仍以 `SCORING_RULES.md` 为准；这里主要记录数据集、runner 结果、问题分类和后续维护方式。

## 本轮目标

- 用 `testdata/local-rules` 下的 960 条 `rule_contract` 样例检查本地结构化规则。
- 优先消除 `fail`，也就是 `must / mustNot` 硬边界失败。
- 保留一部分 `warn` 作为观察信号，避免为了合成数据过度硬编码。
- 把确认稳定的规则同步到 `SCORING_RULES.md`，把实验过程留在本目录。

## 修正范围

本轮重点覆盖以下模块：

- `SceneMoveIntentService`：区分真实移动、地点话题、假设移动、已到达和取消移动。
- `TurnUnderstandingService`：加强纠错、追问、暂停、拒绝推进和短句歧义识别。
- `QuickJudgeService`：让记忆/连续性检查类问题触发 opportunistic 轻判断。
- `PlotDirectorAgentService`：用户正在纠错、暂停、要求先回答或目标地点已到达时，剧情推进必须 hold。
- `DialogueContinuityService`：识别助手上一轮已经执行过计划，避免心跳或下一轮重复同一动作。
- `NarrativeRelationshipService`：加强低投入敷衍表达的扣分识别，避免“想太多/你开心就好/懒得想”等句子被误加亲近。

## 结果对比

初期扫描时存在大量硬失败，典型问题是地点话题误判成移动、用户纠错时仍推进剧情、心跳不承接助手上一句、低投入敷衍被误加分。

本轮最终结果：

```text
heartbeat                total= 90 pass= 67 warn= 23 fail=  0
plot_signal              total=150 pass= 23 warn=127 fail=  0
quick_judge_trigger      total=120 pass= 81 warn= 39 fail=  0
relationship_scoring     total=240 pass=148 warn= 92 fail=  0
scene_move               total=180 pass=132 warn= 48 fail=  0
turn_understanding       total=180 pass= 65 warn=115 fail=  0
```

可复现命令：

```powershell
python tools/dataset-mining/validate_local_rule_cases.py
.\run-local-rules.ps1
.\test-java.ps1
```

详细报告输出到：

```text
build/local-rule-report.json
```

## warn 的含义

`warn` 不是 bug 结论，它表示 `should` 软期望没有完全命中。当前数据集是规则契约集，不是真实独立评测集，所以 `warn` 应作为观察点，而不是必须全部清零。

当前 `warn` 主要来自：

- `plot_signal`：很多样例希望更细分的 `minPlotSignal / preferAdvance`，但剧情推进本来受冷却、风险、场景移动和 `plotPressure` 共同影响。
- `turn_understanding`：部分短句可同时解释为回答问题、接受计划、情绪分享或闲聊，不宜只用单标签压死。
- `relationship_scoring`：部分 act 标签没有完全命中，但硬性 delta 方向已经正确。
- `heartbeat`：回复焦点的 `should` 更像风格期望，不应变成过硬规则。
- `quick_judge_trigger`：部分 opportunistic/background 边界是策略问题，适合结合真实日志继续调。

## 后续使用方式

推荐流程：

1. 先把实际坏例子写进对应 jsonl。
2. 判断它是硬边界还是软期望。
3. 硬边界写入 `must / mustNot`，软期望写入 `should`。
4. 跑 `.\run-local-rules.ps1` 看是否引入新失败。
5. 只有当规则稳定、可解释、跨样例有效时，才同步到 `SCORING_RULES.md`。

不要为了清空 `warn` 继续堆关键词。更好的方向是增加结构化字段、候选分解释和真实 `bug-replay / human-labeled / adversarial / blind-eval` 数据。

