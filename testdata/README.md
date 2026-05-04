# Test Data

这个目录用于保存 CampusPulse 的本地规则、真实复盘和人工评估数据。

需要注意：不同子目录的数据来源和用途不同，不能混在一起解释结果。

## 数据分层

- `local-rules/`：规则契约集。根据当前产品规则和公开数据集的标注维度改写生成，用来防止规则回归，不用来证明规则本身足够智能。
- `bug-replay/`：真实问题复盘集。来自实际聊天截图、导出调试数据或手动复盘，去除个人信息后记录输入、上下文、错误表现和期望结果。
- `memory-pane-replay/`：会话记忆窗格与 Prompt Stack 固定回放集。用 mock 完整会话链路验证 `memory.session`、`world.info`、场景锚点、心跳和主回复上下文是否协同稳定。
- `human-labeled/`：人工标注集。先写自然聊天样例，再由人标注期望，不以现有规则为答案来源。
- `adversarial/`：对抗样例集。专门覆盖容易误判的句子，例如地点话题和真实移动、接受计划和延迟计划、害羞短答和敷衍短答。
- `external-rewrite/`：外部结构改写集。参考公开数据集的任务结构和标签体系，但改写成 CampusPulse 语境，不复制外部语料原文。
- `blind-eval/`：盲测集。先独立写样例和人工期望，再跑现有规则，用来暴露规则盲点。

## 使用原则

- `local-rules/` 主要回答“我们定义过的边界有没有被改坏”。
- `bug-replay/` 和 `human-labeled/` 主要回答“真实聊天里用户会不会觉得合理”。
- `memory-pane-replay/` 主要回答“当前几轮工作记忆是否真的进入主回复，并在关键场景里保持稳定”。
- `adversarial/` 主要回答“规则在边界场景下会不会误触发”。
- `blind-eval/` 主要回答“现有规则在未知样例上的泛化如何”。

后续如果要评估规则质量，应优先看 `bug-replay/`、`human-labeled/`、`adversarial/` 和 `blind-eval/`；`local-rules/` 只作为回归底线。

## 真实导出回放

`ExportReplayTool` 可以读取一次前端导出的 `session_debug_snapshot`，提取用户消息，用当前 Java 后端 mock 链路重新跑一遍，并按原始导出里的心跳时间间隔模拟 `updatePresence`。

```powershell
.\test-java.ps1
$java = Get-ChildItem "C:\Program Files\Java" -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "bin\java.exe" } | Where-Object { Test-Path $_ } | Select-Object -First 1
& $java -cp build\test-classes com.campuspulse.ExportReplayTool "C:\path\to\session-debug-export.json"
```

报告会写入 `build/debug-replay/`。这类回放适合判断“旧导出里的问题在当前规则下是否仍会复现”，但 mock 回复只用于规则和链路验证，不能完全代表远程模型最终文案质量。

## 回归验证怎么跑

建议从项目根目录 `C:\Users\Administrator\Desktop\chat` 执行。最小流程是三步：

1. 先校验 JSONL 样例格式和数量。
2. 再把样例喂给真实 Java 本地规则 runner。
3. 最后跑 Java smoke test，确认编译和基础服务逻辑没有被破坏。

```powershell
python tools/dataset-mining/validate_local_rule_cases.py
.\run-local-rules.ps1
.\test-java.ps1
```

其中 `.\test-java.ps1` 还会运行 `PromptSlotSimulationTest`，用模拟聊天覆盖主回复上下文组装：普通聊天应完整保留 slots，迟到纠错应把修正提示送入 `turn.understanding`，超长记忆应优先裁剪低优先级记忆摘要而不是挤掉核心人设和回复规则。

`.\test-java.ps1` 也会运行 `MemoryPaneReplayTest`，读取 `testdata/memory-pane-replay/cases.jsonl` 做固定回放，并把报告写入 `build/memory-pane-replay/report.json`。这组用例重点看 `memory.session / world.info` 是否注入、会话记忆窗格是否记录计划/场景/背景候选/修正、心跳是否承接上下文。

### 第一步：数据格式校验

这个命令只检查 `testdata/local-rules/*.jsonl` 的结构、模块名、数量分布和部分关键字段约束，不会证明规则判断正确。

```powershell
python tools/dataset-mining/validate_local_rule_cases.py
```

如果这里失败，通常是 JSONL 写错、字段缺失、模块名拼错或期望字段不合法，先修数据再跑 runner。

### 第二步：真实规则 runner

```powershell
.\run-local-rules.ps1
```

runner 会临时编译 Java 本地模块并逐条读取 `local-rules/*.jsonl`，输出 `pass / warn / fail` 汇总，同时把详细报告写入 `build/local-rule-report.json`。

状态含义：

- `fail`：`must` 或 `mustNot` 失败，通常优先按 bug 排查。
- `warn`：`should` 软期望没有命中，不一定是 bug，需要结合样例语义和 `WARN_TRIAGE_2026-04-27.md` 判断。
- `pass`：硬边界和软期望都命中。

也可以只跑单个模块，适合你刚改完某一类规则时快速复查：

```powershell
.\run-local-rules.ps1 --module scene_move
```

常用模块名：

- `scene_move`
- `turn_understanding`
- `quick_judge_trigger`
- `plot_signal`
- `heartbeat`
- `relationship_scoring`

查看详细报告时，可以直接打开 `build/local-rule-report.json`，或者用 PowerShell 快速按模块统计 warning：

```powershell
$r = Get-Content -Raw -Encoding UTF8 .\build\local-rule-report.json | ConvertFrom-Json
$r.cases | Where-Object { $_.status -eq 'warn' } |
  Group-Object module | Sort-Object Name |
  ForEach-Object { '{0} {1}' -f $_.Name, $_.Count }
```

### 第三步：Java smoke test

```powershell
.\test-java.ps1
```

这个命令用于确认 Java 侧基础编译和 smoke tests 仍然通过。它不能替代规则 runner，但适合作为提交前的最后一道基础检查。

## 结果怎么判断

- 如果出现 `fail`，先看 `build/local-rule-report.json` 里对应 case 的 `failures`，再回到样例的 `must / mustNot` 判断它是真 bug 还是样例期望写错。
- 如果只有 `warn`，不要急着改代码；先看 `testdata/local-rules/WARN_TRIAGE_2026-04-27.md`，确认它是规则缺口、runner/数据契约问题，还是可以保留观察的软标签差异。
- 如果某次改动让 `warn` 降低但引入更多 QuickJudge 误触发、轻聊天误判或剧情过推，不要保留这类改动。之前已经遇到过“宽关键词追零”导致回归变差的情况。
- 当前这批 `local-rules` 是规则契约集，不是真实独立评测集；真正判断智能程度，还需要后续补充 `bug-replay / human-labeled / adversarial / blind-eval`。
