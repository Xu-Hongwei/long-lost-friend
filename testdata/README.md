# Test Data

这个目录用于保存 CampusPulse 的本地规则、真实复盘和人工评估数据。

需要注意：不同子目录的数据来源和用途不同，不能混在一起解释结果。

## 数据分层

- `local-rules/`：规则契约集。根据当前产品规则和公开数据集的标注维度改写生成，用来防止规则回归，不用来证明规则本身足够智能。
- `bug-replay/`：真实问题复盘集。来自实际聊天截图、导出调试数据或手动复盘，去除个人信息后记录输入、上下文、错误表现和期望结果。
- `human-labeled/`：人工标注集。先写自然聊天样例，再由人标注期望，不以现有规则为答案来源。
- `adversarial/`：对抗样例集。专门覆盖容易误判的句子，例如地点话题和真实移动、接受计划和延迟计划、害羞短答和敷衍短答。
- `external-rewrite/`：外部结构改写集。参考公开数据集的任务结构和标签体系，但改写成 CampusPulse 语境，不复制外部语料原文。
- `blind-eval/`：盲测集。先独立写样例和人工期望，再跑现有规则，用来暴露规则盲点。

## 使用原则

- `local-rules/` 主要回答“我们定义过的边界有没有被改坏”。
- `bug-replay/` 和 `human-labeled/` 主要回答“真实聊天里用户会不会觉得合理”。
- `adversarial/` 主要回答“规则在边界场景下会不会误触发”。
- `blind-eval/` 主要回答“现有规则在未知样例上的泛化如何”。

后续如果要评估规则质量，应优先看 `bug-replay/`、`human-labeled/`、`adversarial/` 和 `blind-eval/`；`local-rules/` 只作为回归底线。

## 运行方式

基础数据校验：

```powershell
python tools/dataset-mining/validate_local_rule_cases.py
```

真实规则回归 runner：

```powershell
.\run-local-rules.ps1
```

runner 会编译 Java 本地模块并逐条读取 `local-rules/*.jsonl`，输出 `pass / warn / fail` 汇总，同时把详细报告写入 `build/local-rule-report.json`。也可以只跑单个模块：

```powershell
.\run-local-rules.ps1 --module scene_move
```
