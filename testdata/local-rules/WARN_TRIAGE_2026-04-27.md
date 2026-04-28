# 2026-04-27 warn 分类审查

本文件用于记录 `.\run-local-rules.ps1` 的 `warn` 性质判断。结论先说：当前没有 `fail`，硬边界暂时稳定；`warn` 主要是测试契约、runner 映射和少数本地规则覆盖不足混在一起，不能简单按数量判断代码质量。

初次审查结果：

```text
heartbeat                warn= 23
plot_signal              warn=127
quick_judge_trigger      warn= 39
relationship_scoring     warn= 92
scene_move               warn= 48
turn_understanding       warn=115
```

修正 runner/数据契约后的结果：

```text
heartbeat                warn= 11
plot_signal              warn= 28
quick_judge_trigger      warn= 11
relationship_scoring     warn= 92
scene_move               warn= 19
turn_understanding       warn=112
```

本轮契约修正已经消掉 171 个假 warning，主要来自 `suppressedReason` 单复数、`secondaryCandidate` 候选列表、剧情推进后信号归零、`plotPressureAfter` 相对比较、心跳 pending repair 承接、以及 `scene_move` 的 arrived/cancel 派生冲突。

第一轮真实规则补强后的结果：

```text
heartbeat                warn= 11
plot_signal              warn= 28
quick_judge_trigger      warn= 11
relationship_scoring     warn= 46
scene_move               warn= 19
turn_understanding       warn= 71
```

本轮真实规则补强主要减少了 `relationship_scoring` 和 `turn_understanding` 的 warning：关系行为层补充了关心、边界、记忆承接、控制/催促表达；TurnUnderstanding 优先识别计划邀请型问题，并把记忆核对、追问、暧昧探测纳入机会型 QuickJudge。

第二轮真实规则补强后的结果：

```text
heartbeat                warn= 11
plot_signal              warn= 29
quick_judge_trigger      warn= 11
relationship_scoring     warn=  0
scene_move               warn=  5
turn_understanding       warn= 50
```

本轮继续把 `relationship_scoring` 的尾部样例清到 0：补充了含蓄好感 `romantic_probe`、上下文/偏好承接、慢节奏边界表达。`scene_move` 主要补了“边走边聊”“过去看看”这类动态过渡识别，剩余 5 个 warn 更偏数据口径，例如“送你回宿舍”“回去的路上”在运行时按 `mixed_transition` 更自然，不建议为了样本改成 `face_to_face`。

第三轮真实规则补强后的结果：

```text
heartbeat                warn= 11
plot_signal              warn= 29
quick_judge_trigger      warn= 11
relationship_scoring     warn=  0
scene_move               warn=  5
turn_understanding       warn= 35
```

本轮重点修 `turn_understanding`：`advice_seek` 优先走回答问题，避免“继续复习/要不要参加/怎么拒绝”被移动或拒绝词抢走；active objective 下显式改地点会保留 `counter_offer` 候选；第三方观察如“有人跑过去”不再触发用户移动；无 active objective 的地点边界句按 `topic_only` 处理。曾尝试把广义问候统一升级成 `answer_question`、把记忆核对直接归入 `clarify`，但会让普通轻聊天和 QuickJudge urgent 误触发增加，已撤回。

## 总体判断

### 更像真实规则问题

- `turn_understanding` 剩余 warning 主要集中在混合回复 exact 主类、广义问候后的“回答/闲聊”边界、以及追问是否机会型触发。这些不宜继续靠宽关键词追零。
- `scene_move / plot_signal` 对否定移动的保护还有边界问题：例如“刚才那个话题我还想继续，不用马上换到别的地方”不应触发 `transition_only`。

### 更像 runner 或数据契约问题

- `quick_judge_trigger` 的 `suppressedReason` 期望是单数，但 runner 实际输出 `suppressedReasons` 数组。实际结果已经包含 `plain_light_chat`，所以这是字段契约不一致，不是 QuickJudge 逻辑 bug。
- `scene_move` 样例期望 `localConflict`，但 `scene_move` runner 只调用 `SceneMoveIntentService`，不会生成 `TurnUnderstandingService.localConflicts`。这类期望应挪到 `turn_understanding`，或 runner 明确增加组合测试。
- `turn_understanding` 样例期望 `secondaryCandidate`，但 runner 只输出 `candidates` 数组。很多实际结果已经包含 `counter_offer`，只是没有单独字段。
- `plot_signal` 的 `minPlotSignal / minPlotGap` 大量 warning 来自“推进成功后信号被消费归零”。runner 现在输出的是消费后的 `plotSignal / plotGap`，数据却在检查消费前强度。
- `plot_signal` 的 `plotPressureAfter: "not_increase_by_transition"` 是自然语言期望，runner 目前不能理解“相对当前值不增加”，只能做字面比较。
- `heartbeat.replyFocus` 和 `scene_move.interactionMode` 有很多 warning 是软标签过细，例如 `soft_reassure / answer_or_repair`、`face_to_face / mixed_transition`，不宜直接当成 bug。

### 暂时保留观察

- `QuickJudge` 中部分 `opportunistic` 被升级成 `urgent`。如果用户明确纠错、说“我问的是”“不是真的要过去”“先把原因说清楚”，升级为 `urgent` 是合理的；但“要不换个地方吧”这类普通改计划是否也应 urgent，需要结合实际耗时和前端等待策略再定。
- `plot_transition_hold` 里 `transition_only` 后仍保留一定 `plotSignal`。只要没有 `advance_plot`，这不一定是 bug；如果前端把它误读成“剧情即将推进”，再考虑把显示字段拆成 `rawPlotSignal` 和 `visiblePlotSignal`。
- `scene_topic_matrix` 中“如果只聊天，不去图书馆”被判成 `cancel_move`。如果当前没有 active objective，更像 `topic_only`；如果已有移动目标，`cancel_move` 合理。后续数据应把这两种上下文拆开。

## 模块级结论

### heartbeat

结论：主要是数据/runner 期望过细，少量 runner 字段语义不准。

- `shouldPreferLastUserQuestion` 现在只检测问号和疑问词，但样例把 pending repair 也算进去了。
- 如果这个字段真实含义是“心跳要优先承接上一个用户问题或问题反馈”，应改名或扩展 runner 判断。
- `replyFocus` 是风格软标签，不建议硬追到完全一致。

建议：先改 runner/数据字段，不急着改运行时代码。

### plot_signal

结论：大部分是数据/runner 指标层问题，夹杂少量真实规则边界。

- 推进成功后 `plotSignal / plotGap / plotPressure` 会归零，所以 `minPlotSignal` 和 `minPlotGap` 检查消费后字段会天然 warn。
- `plotPressureAfter` 的“不因转场增加”需要 runner 支持相对比较，不能写成字符串。
- 真实可疑点是“否定换场/继续当前话题”仍被识别成 `transition_only`，这需要修 `SceneMoveIntentService` 或上游结构化判断。

建议：先给 runner 增加 `prePlotSignal / prePlotGap / prePlotPressure`，再保留少量真实边界样例。

### quick_judge_trigger

结论：多数是数据字段不一致和期望过细，少数是策略问题。

- `suppressedReason` 应改成 `suppressedReasons` 或 runner 支持数组包含判断。
- `reason` 现在常统一成 `user_self_rescue`，而数据想要更细的 `duplicate_reply / missed_question / plot_overpush`。这属于解释粒度不一致，不影响是否触发。
- 普通改计划是否从 `opportunistic` 升到 `urgent`，需要再结合真实等待耗时决定。

建议：先修数据字段和 reason 粒度；不要急着降低 urgent，除非实际对话变慢。

### relationship_scoring

结论：已完成当前数据集下的结构化覆盖，240 条样例全部通过。

- 已补充混合关心/记忆承接、边界照顾、控制/催促、害羞好感回应等结构化 act。
- `CONTINUITY_ANCHOR` 现在同时影响 `trust` 和 `resonance`，更贴近“被记住/被理解”的关系价值。
- `ROMANTIC_PROBE` 当前主要作为解释标签，不直接加分，避免把所有含糊短答都刷成高分。

建议：后续不要继续靠扩关键词追零，优先观察真实对话导出的评分原因是否自然。

### scene_move

结论：多数是数据/runner 问题，少数是上下文边界问题。

- `localConflict` 不属于单独 `SceneMoveIntentService` 的输出，放在 `scene_move` 样例里会天然 warn。
- `interactionMode` 软期望太细，除非前端强依赖，否则不应作为主要修复目标。
- 无 active objective 时，“如果只聊天，不去某地”更像话题/假设；有 active objective 时才更像取消移动。

建议：把 `localConflict` 期望迁到 `turn_understanding`，并拆分“无目标否定移动”和“有目标取消移动”。

### turn_understanding

结论：这里是真 bug 和数据过细混合，值得第二优先级修。

- `accept_plan` 识别偏弱，尤其是上一轮助手以问题形式发起计划时。
- 记忆核对、追问、暧昧探测的 `recommendedQuickJudgeTier` 偏保守。
- `secondaryCandidate` 是 runner 字段缺失；实际 `candidates` 里很多已经包含目标候选。
- 部分 `small_talk` 在上一轮有问题时被识别成 `answer_question`，不一定错，因为它确实在回答“今天怎么样”。

建议：先修 `assistantObligation` 的计划邀请识别和高价值轮 tier，再改测试字段。

## 建议下一步

优先顺序：

1. 已完成第一轮 runner/数据契约修正：`suppressedReasons`、`secondaryCandidate`、推进后信号消费、`plotPressureAfter` 相对比较、`localConflict` 所属模块。
2. 已完成第一轮真实规则修正：关系 act/risk 抽取扩展、计划邀请 obligation、记忆/追问/暧昧的 QuickJudge tier。
3. 已完成第二轮真实规则修正：关系评分尾部样例、动态场景过渡表达、无 active objective 时的否定移动话题保护。
4. 已完成第三轮真实规则修正：建议请求优先回答、改约目标保留 `counter_offer`、第三方观察不触发移动、地点边界句不误移动。
5. 下一轮优先看：剧情信号测试口径、混合回复数据是否需要重标，而不是继续用宽关键词降低 `turn_understanding` warning。
6. 最后才考虑清理软标签 warning：`replyFocus`、`interactionMode`、过细的 exact reason。

这样做的好处是：先把“假 warning”过滤掉，再看剩余 warning，才能真正判断本地规则哪里不够智能。
