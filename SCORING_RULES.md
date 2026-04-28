# CampusPulse 评分规则与智能体协作说明

本文档记录当前项目里仍在使用的评分机制、剧情时间推进规则，以及它们和 QuickJudge、PlotDirector、SceneDirector、ExpressiveLlmClient 的协作边界。

这里的“评分”分成三类，不能混用：

- `RelationshipScore`：关系评分，决定亲近、信任、共鸣、关系阶段和结局倾向。
- `QuickJudgeTriggerScore`：轻判断触发分，只决定要不要启动 QuickJudge，不直接修改关系分。
- `PlotSignal / PlotPressure / PlotMacroScore`：当前轮是否值得推进的信号、跨轮累计的剧情蓄力，以及剧情推进成功后的关系加成。

## 1. 关系评分目标

关系评分描述用户和角色之间的关系进度，不是简单的“好感度 +1”。它由三维组成：

| 维度 | 含义 | 典型正向来源 |
| --- | --- | --- |
| `closeness` | 亲近感 | 主动靠近、温暖表达、具体关心、自然提问 |
| `trust` | 信任感 | 真诚分享、尊重边界、稳定承诺、不强迫 |
| `resonance` | 共鸣感 | 记忆承接、共同计划、场景默契、角色偏好共振 |

总分计算：

```text
affectionScore = closeness + trust + resonance
```

主要实现位置：

- `NarrativeRelationshipService.evaluateTurn(...)`
- `RelationshipCalibrationService`
- `ChatOrchestrator.applyRelationshipCalibration(...)`
- `ChatOrchestrator.applyPlotMacroScore(...)`

## 2. 本地关系评分主裁判

本地评分每个用户回合同步执行，是当前轮立即生效的主裁判。

### 2.1 基础信号

| 信号 | 作用 |
| --- | --- |
| `warmTouch` | 温暖表达，提升亲近感 |
| `honestShare` | 真诚、脆弱、自我披露，提升信任 |
| `initiative` | 一起、下次、计划、见面等主动靠近，提升亲近与共鸣 |
| `boundaryRespect` | 不急、尊重节奏、不勉强，提升信任 |
| `dismissive` | 随便、算了、无所谓等敷衍抽离，降低亲近与共鸣 |
| `offensive` | 冒犯、恶意、边界攻击，显著降低信任 |
| `memorySignal` | 承接上次、之前、角色记忆，提升信任与共鸣 |
| `questionBonus` | 用户主动提问，提升亲近感 |
| `eventBonus` | 剧情事件附带的关系加成 |

### 2.2 当前基础公式

```text
closenessDelta =
  clamp(1 + warmTouch + initiative + questionBonus
        - dismissive - offensive
        + roleClosenessDelta + localClosenessDelta,
        -3, 5)

trustDelta =
  clamp(honestShare + boundaryRespect + min(1, memorySignal)
        - dismissive - offensive * 2
        + roleTrustDelta + localTrustDelta,
        -3, 6)

resonanceDelta =
  clamp(memorySignal + initiative + eventBonus
        - dismissive
        + roleResonanceDelta + localResonanceDelta,
        -2, 7)

totalDelta = closenessDelta + trustDelta + resonanceDelta
```

维护原则：

- `closeness` 默认有一点基础互动分，但风险和敷衍会抵消。
- `trust` 对冒犯更敏感，因为安全感比热闹更关键。
- `memorySignal` 对 `trust` 的即时加成最多为 `+1`，避免单纯复读记忆刷分。
- 每个维度都有上下限，避免一轮对话把关系推爆。
- 每个明显加减分都应该进入 `scoreReasons`，方便前端和调试面板解释。

## 3. UserRelationalAct 结构化行为层

为了减少零散关键词互相打架，本地评分会先抽取 `UserRelationalAct`，再统一折算到三维 delta。

| Act | 触发倾向 | 分数影响 | 标签 |
| --- | --- | --- | --- |
| `QUALITY_QUESTION` | 用户提出有方向的问题，如“为什么”“你觉得”“能不能讲讲”“推荐” | `closeness +1` | `rel_act:quality_question` |
| `CONCRETE_CARE_ACTION` | 用户给出具体照顾或共同动作，如“我陪你”“陪你坐一会儿”“给你带”“先休息”“陪你慢慢来” | `closeness +1`, `trust +1` | `rel_act:concrete_care_action` |
| `CONTINUITY_ANCHOR` | 用户承接记忆、场景或上下文，如“上次”“刚才”“已经在”“我们不是”“你担心”“你喜欢靠窗” | `trust +1`, `resonance +1` | `rel_act:continuity_anchor` |
| `BOUNDARY_RESPECT` | 用户尊重节奏，如“不急”“慢慢来”“不逼你”“不想说也没关系”“不用勉强” | `trust +1` | `rel_act:boundary_respect` |
| `ROMANTIC_PROBE` | 用户害羞或含蓄回应好感，如“其实有一点”“有点吧”“不是讨厌”“会紧张” | 不直接加分，仅作为解释标签 | `rel_act:romantic_probe` |
| `PACE_OR_CONTROL_PRESSURE` | 用户催促或控制，如“快点”“必须”“别废话”“跟我走就行”“你就说” | `trust -1` | `rel_act:pace_or_control_pressure` |
| `LOW_EFFORT_DISMISSIVE` | 用户敷衍抽离，如“随便”“算了”“无所谓”“你开心就好”“懒得想”“想太多” | `closeness -1`, `resonance -1` | `rel_act:low_effort_dismissive` |

这层不直接替代基础关键词，而是给本地评分增加一层更可解释的结构化判断。
低投入敷衍不能因为“继续聊天”本身被误判为亲近；只有它同时包含具体照顾、认真回应或记忆承接时，才允许被其他正向 act 抵消。
害羞短答不能因为字数短被当作低质量回复；如果它包含含蓄好感或紧张承认，应作为 `ROMANTIC_PROBE` 留给前端调试和后续评分观察。

## 4. 角色偏好评分

每个角色有自己的加分偏好。角色偏好不替代通用评分，只在通用评分上增加差异。

| 角色 id | 正向偏好 | 风险偏好 |
| --- | --- | --- |
| `healing` 林晚栀 | 慢节奏、倾听、陪伴、安静场景、温柔照顾 | 催促、轻视脆弱 |
| `lively` 许朝暮 | 接梗、行动、社团感、热闹参与、说到做到 | 冷场、敷衍 |
| `cool` 沈砚 | 稳定、认真、记得细节、不逼问 | 强迫表态、逼他说 |
| `artsy` 顾遥 | 画面感、诗意、审美共鸣、理解表达 | 粗暴打断、否定表达 |
| `sunny` 周燃 | 具体行动、运动陪伴、计划、休息与不硬撑 | 持续消极、摆烂、行动停滞 |

角色偏好输出：

- `roleSignal.closenessDelta`
- `roleSignal.trustDelta`
- `roleSignal.resonanceDelta`
- `roleSignal.behaviorTag`
- `roleSignal.riskFlag`
- `roleSignal.scoreReasons`

## 5. 关系阶段门槛

关系阶段不能只看总分，必须同时看三维是否均衡。

| 阶段 | 门槛 |
| --- | --- |
| `初识` | 默认阶段 |
| `升温` | `closeness >= 5`, `trust >= 3`, `resonance >= 3`, `affectionScore >= 12` |
| `心动` | `closeness >= 10`, `trust >= 9`, `resonance >= 9`, `affectionScore >= 32` |
| `靠近` | `closeness >= 18`, `trust >= 16`, `resonance >= 16`, `affectionScore >= 55` |
| `确认关系` | `closeness >= 24`, `trust >= 24`, `resonance >= 22`, `affectionScore >= 78` |

阶段保护：

- 单轮最多只允许上升一个阶段。
- 即使总分足够，如果某一维明显不足，也不能跳到更亲密阶段。
- 异步 LLM 校准和剧情加分都必须复用这套三维门槛，不能只用总分判断阶段。

## 6. 异步 LLM 关系校准

异步 LLM 评分不是主裁判，而是“复盘校准器”。

### 6.1 触发条件

`RelationshipCalibrationService.shouldStart(...)` 当前规则：

- 远程模型配置可用。
- 当前用户回合数满足 `currentTurn % 4 == 0`。
- 用户消息非空。
- 压缩后消息长度不超过 `180`。
- 本地评分已经产生 `TurnEvaluation`。

### 6.2 输入内容

异步 LLM 会看到：

- 当前 turn。
- 用户消息。
- 最近最多 6 条上下文摘要。
- 当前角色 `id/name/archetype`。
- 上一轮关系状态。
- 本地评分 delta。
- 本地 `scoreReasons`。
- `behaviorTags` 和 `riskFlags`。

### 6.3 输出限制

远程只允许输出三个维度的小校准：

```json
{
  "closenessDelta": -2,
  "trustDelta": 0,
  "resonanceDelta": 1,
  "confidence": 80,
  "reason": "short_snake_case_reason"
}
```

限制：

- 每个维度必须在 `-2..2`。
- 总修正绝对值通常不超过 `2`。
- `confidence < 70` 不采用。
- 如果本地评分合理，应返回零修正。
- 不奖励泛泛闲聊，除非它体现照顾、记忆、边界尊重或具体行动。
- 不惩罚害羞或短回复，只要它确实回答了当前上下文。

### 6.4 采用方式

异步 LLM 结果不阻塞当前轮。它晚到后写入：

```text
SessionRecord.pendingRelationshipCalibration
```

下一轮评分时：

1. 先取出 pending calibration。
2. 本地评分照常执行。
3. 如果 calibration `shouldApply()`，在本地 delta 上做小幅修正。
4. 重新计算 `closeness/trust/resonance/affectionScore/stage`。
5. 阶段判断必须走三维门槛和单轮最多升一阶保护。
6. 在 `scoreReasons` 追加 `llm_calibration ...`。
7. 在 `behaviorTags` 追加 `llm_score_calibrated`。

## 7. QuickJudge 与本地结构化理解

QuickJudge 之前会先经过 `TurnUnderstandingService`。这不是远程模型，而是本地轻量结构层，用来判断用户这一句在上一轮语境里的动作。

### 7.1 TurnUnderstanding 输出

| 字段 | 含义 |
| --- | --- |
| `primaryAct` | 当前最可能的用户回应动作 |
| `confidence` | 本地动作判断置信度 |
| `candidates` | 多个候选动作及分数 |
| `assistantObligation` | 上一轮助手义务，包含类型、来源、优先级和预期用户动作 |
| `recommendedQuickJudgeTier` | 建议 QuickJudge 使用 `urgent / opportunistic / background / skip` |
| `sceneMoveKind` | 结构化移动类型，如 `move_to / stay / cancel_move / return_to / arrived / topic_only / no_change` |
| `sceneMoveTarget` | 场景目标地点 |
| `localConflicts` | 本地冲突列表，包含冲突类型、严重程度、来源和推荐处理动作 |

主要候选动作：

- `answer_question`
- `accept_plan`
- `reject`
- `defer`
- `clarify`
- `counter_offer`
- `scene_move`
- `scene_stay`
- `topic_only`
- `emotion_share`
- `romantic_probe`
- `small_talk`

场景移动结构化边界：

- `move_to / return_to` 必须有真实移动或返回意图，不能只靠地点名触发。
- “你喜欢图书馆吗”“要是真去食堂”“只是聊到操场”这类句子应优先视为 `topic_only`。
- “图书馆听起来不错，但我还没说要去”“先把话说完”“不是要换地方”这类带地点的边界句，在没有 active movement objective 时优先按 `topic_only / defer` 处理。
- “有人跑过去”“路人经过”这类第三方观察不是用户移动意图，不应触发 `move_to`。
- “已经到了”“现在就在这里”应优先视为 `arrived`，用于清理重复目标。
- “先别去”“不去了”“不想动”“不用送”应优先视为 `cancel_move / stay / defer`，用于暂停当前移动目标。

上一轮助手义务识别边界：

- “要不要去食堂”“我们去图书馆看看吗”“要不换个地方”这类带问号的计划邀请，优先识别为 `accept_plan` 义务，而不是普通 `answer_question`。
- `advice_seek` 优先进入 `answer_question`，避免“继续复习”“要不要参加”里的动作/否定词抢成场景移动或拒绝。
- active objective 下，如果用户显式改到另一个地点，`scene_move` 仍可做主候选，但应保留 `counter_offer` 候选，方便 QuickJudge 或主回复理解“用户在改约目标”。
- 记忆核对、连续性追问和暧昧探测属于高价值本地理解信号，通常应给 `QuickJudge` 一个 `opportunistic` 机会。

### 7.2 localConflicts

`localConflicts` 是下游判断的唯一结构化冲突来源，不再对外保留旧的 `conflictFlags / understandingConflicts` 字段。

当前重要类型：

| type | 含义 | 推荐处理 |
| --- | --- | --- |
| `ambiguous_short_reply` | 短回复候选差距小 | 启动 QuickJudge 或按上一轮义务解释 |
| `question_vs_plan_ambiguous` | 用户可能在回答问题，也可能在接计划 | 先回答问题，再处理场景 |
| `user_cancels_active_objective` | 用户取消或延后当前目标 | 清理或暂停移动目标 |
| `scene_target_already_current` | 目标地点已经是当前位置 | 清理重复转场 |
| `user_self_rescue` | 用户正在纠错或质疑回复 | 先修正，再回答 |

### 7.3 QuickJudge 触发等级

| tier | 含义 | 等待策略 |
| --- | --- | --- |
| `urgent` | 用户正在纠错、质疑、指出理解偏差 | 使用前端配置等待时间 |
| `opportunistic` | 本轮判断价值较高 | 使用前端配置等待时间 |
| `background` | 周期性后台复盘 | 不等待当前轮 |
| `always` | 诊断强制模式 | 使用前端配置等待时间 |
| `skip` | 不启动 | 无 |

触发分只决定是否启动 QuickJudge，不直接改关系分。
记忆和连续性检查也属于高价值轻判断来源，例如“你还记得吗”“我刚才问的是什么”“你是不是没听懂”。这类 `question_check` 应进入 `opportunistic`，但如果用户明确指出错误、质疑理解或要求先纠正，则升级为 `urgent`。

### 7.4 QuickJudge 的修正范围

QuickJudge 不直接修改 `closeness/trust/resonance`。它主要修正：

- `IntentState`
- `DialogueContinuityState`
- `ResponsePlan`
- 下一轮 `pendingRepairCue`

间接影响：

- 修正意图后，`PlotDirector` 可能不再错误推进剧情。
- 修正连续目标后，主回复不再重复错误场景。
- 晚到结果进入下一轮后，主回复可以自然纠错，避免持续偏题。

## 8. 剧情时间、PlotSignal 与 PlotPressure

这里的“剧情时间”不是现实钟表时间，而是故事节奏时间。

项目里有两套时间：

- `Story Beat Time`：剧情拍点时间，由 `beatIndex / arcIndex / lastPlotTurn / forcePlotAtTurn / plotPressure` 控制。
- `Scene Reality Time`：场景现实时间，由 `timeOfScene / weatherMood / transitionLockUntilTurn / lastConfirmedSceneTurn` 控制。

两者关系：

- 场景变化不一定推进剧情拍点。
- 剧情拍点推进也不一定强制换场。
- 用户当前意图优先于剧情时间。

### 8.1 PlotDirector 前置保护

```text
if user explicitly requests scene transition:
  transition_only，不推进剧情拍点
else if localConflicts contains user_self_rescue or user_cancels_active_objective:
  hold_plot
else if sceneMoveKind is stay/cancel_move/arrived/topic_only:
  hold_plot or transition_only
else if user_turn and message is short reaction:
  hold_plot
else if replySource is not user_turn or long_chat_heartbeat:
  hold_plot
else if gapSinceLastPlot < 4:
  hold_plot
else:
  进入本地/远程剧情判断
```

含义：

- 显式换场优先交给场景移动，不等于剧情推进。
- 用户纠错、质疑理解、要求先回答、取消目标或已经到达目标地点时，`plotPressure` 不能强行覆盖。
- 地点话题、假设移动、停留和取消移动优先保护当前语义，不应被当成剧情推进机会。
- 用户短句反应要先接话，不应突然推剧情。
- 普通心跳和 presence 检测不应随便推剧情。
- 距离上次剧情推进不足 4 个用户回合时默认冷却。

### 8.2 PlotSignal 构成

`plotSignal` 是当前这一轮是否值得推进剧情的上下文信号，不是关系分，也不是长期累计值。它在前端显示为“本轮信号”。如果本轮剧情成功推进，`plotSignal` 会被消费并归零。

典型加分来源：

- 用户消息有实质内容。
- 提到上次、之前、后来、还记得。
- 提到一起、下次、以后、认真、靠近、答应、喜欢、在意。
- 当前有未闭合记忆线索。
- 情绪状态 `longing >= 32`。
- 用户提到天气且天气上下文可用。
- 用户提到今天、今晚、现在、刚才、明天等时间词且时间上下文可用。
- `long_chat_heartbeat`。
- 隐式移动意图。

### 8.3 PlotPressure 剧情蓄力

`plotPressure` 是跨轮累计的剧情压力，在前端显示为“剧情蓄力”。它解决的是另一类问题：单轮信号可能每次都不够强，但连续几轮都在铺垫同一段关系、场景或记忆时，剧情仍然应该慢慢往前走。

当前原则：

- `plotSignal` 偏向“这一轮够不够强”。
- `plotPressure` 偏向“最近几轮是不是已经铺垫到位”。
- 显式场景移动会优先视为 `transition_only`，不把单纯换地点误当剧情拍点推进。
- `sceneMoveKind` 是 `stay / cancel_move / arrived / topic_only` 时，会降低剧情推进倾向。
- 剧情成功推进后，`plotSignal`、`plotPressure`、`plotGap` 和信号拆分都会被消费并归零。
- 心跳重建 `TurnContext` 时会继承上一轮剧情信号，避免主动消息把调试面板数字误清零。

### 8.4 TurnContext 对 PlotSignal 的修正

`adjustSignalWithTurnContext(...)` 会结合关系评分和结构化理解再修正一次：

| 条件 | 修正 |
| --- | --- |
| 本轮 `affectionDeltaTotal > 0` | `+1` |
| 本轮有 `behaviorTags` | `+1` |
| 主意图是 `romantic_probe` 或 `scene_push` | `+1` |
| 主意图是 `meta_repair` | `-1` |
| 本轮存在 `riskFlags` | `-1` |
| 达到 `forcePlotAtTurn` | `+1` |
| `userReplyAct` 是 `answer_question / topic_only / scene_stay / reject / defer / clarify` | 降低推进倾向 |
| `sceneMoveKind` 是 `stay / cancel_move / arrived / topic_only` | 降低推进倾向 |
| 存在 `localConflicts` | 降低推进倾向 |

### 8.5 PlotDirector 当前推进阈值

PlotDirector 先执行保护规则，再判断是否推进。当前本地推进阈值是：

| 条件 | 结果 | 说明 |
| --- | --- | --- |
| `currentTurn >= forcePlotAtTurn && gap >= 5 && (signal >= 2 || plotPressure >= 4)` | `advance_plot` | 到了强制窗口，但仍要求有一定上下文信号或剧情蓄力 |
| `plotPressure >= 7 && gap >= 3` | `advance_plot` | 剧情蓄力已经很高；但受前置 `gap < 4` 硬保护影响，当前实际至少要到 `gap >= 4` 才会生效 |
| `plotPressure >= 5 && gap >= 5` | `advance_plot` | 剧情蓄力中高，且距离上次推进已经足够远 |
| `signal >= 4 && gap >= 4` | `advance_plot` | 当前轮信号很强，且满足基础冷却 |
| `replySource == long_chat_heartbeat && gap >= 6 && signal >= 2` | `heartbeat_nudge` | 长聊后的轻推，只补氛围，不开大剧情 |

这些阈值之前还有硬保护：

- 显式换场：只做 `transition_only`，不推进剧情拍点。
- 用户短反应：优先接话，`hold_plot`。
- 非用户回合且不是 `long_chat_heartbeat`：`hold_plot`。
- `gap < 4`：默认冷却，`hold_plot`。

因此，“剧情蓄力变高”不等于立刻推进；它还要同时过冷却、意图、场景移动和风险保护。

## 9. PlotMacroScore

剧情推进本身也会影响关系分，但不是每轮都加。

`applyPlotMacroScore(...)` 只有在以下条件都满足时生效：

- `plotDecision.advanced == true`
- 当前张力状态不是 `guarded`
- 当前回合没有 `riskFlags`

加成规则：

| 类型 | closeness | trust | resonance |
| --- | --- | --- | --- |
| 普通剧情推进 | `+2` | `+1` | `+2` |
| 长聊心跳轻推 | `+1` | `+0` | `+1` |

额外修正：

- 如果 `primaryIntent == romantic_probe`，`trust +1`。
- 如果 `primaryIntent == scene_push`，`resonance +1`。

阶段判断必须复用三维门槛，不能只看总分。

## 10. SceneDirector 场景现实时间

`SceneDirectorService.evolve(...)` 每轮都会更新场景现实信息，但这不等于剧情拍点推进。

它更新：

- `timeOfScene`
- `weatherMood`
- `location`
- `subLocation`
- `interactionMode`
- `transitionPending`
- `transitionLockUntilTurn`
- `lastConfirmedSceneTurn`
- `sceneSummary`

场景移动规则：

```text
if moveIntent.shouldMove and targetLocation differs from current location:
  location = targetLocation
  transitionPending = true
  transitionLockUntilTurn = currentTurn + 2
  lastConfirmedSceneTurn = currentTurn
else if currentTurn >= transitionLockUntilTurn:
  transitionPending = false
```

摘要更新原则：

- `move_to / return_to` 且目标地点改变时，可以改位置摘要。
- `topic_only / stay / cancel_move / arrived / no_change` 不应因为地点关键词改位置摘要。
- 但 `online_chat / phone_call` 这类互动模式变化仍然可以更新摘要。

## 11. 主链路协作顺序

当前用户回合主链路：

```text
用户输入
  -> SafetyService 安全检查
  -> IntentInferenceService 本地意图初判
  -> DialogueContinuityService 连续性初判
  -> 消费 pendingQuickJudgeCorrection
  -> 消费 pendingRelationshipCalibration
  -> QuickJudgeLocalCorrection 第一次收敛
  -> TurnUnderstandingService 本地结构化理解
  -> QuickJudge 条件异步启动

  -> NarrativeRelationshipService 本地关系评分
  -> applyRelationshipCalibration 融合上一轮异步校准
  -> RelationshipCalibrationService 条件异步启动本轮评分复盘

  -> BoundaryResponseService 张力判断
  -> PlotDirector 当前轮剧情决策
  -> DialogueContinuity settle arrived
  -> QuickJudgeLocalCorrection 第二次收敛
  -> TurnUnderstandingService 第二次重算
  -> applyPlotMacroScore 剧情推进关系加成
  -> PlotGateService 事件闸门

  -> 主回复请求前 resolve QuickJudge
  -> 如果 QuickJudge 已返回且可用，融合修正
  -> QuickJudgeLocalCorrection 第三次收敛
  -> TurnUnderstandingService 第三次重算
  -> 如果 QuickJudge 超时或 background，则晚到结果进入下一轮修正槽

  -> ExpressiveLlmClient 生成主回复
  -> RealityGuard / Humanization / Scene 去重清洗
  -> 写回 SessionRecord
  -> 返回前端
```

核心边界：

- `NarrativeRelationshipService` 决定本轮基础关系分。
- `RelationshipCalibrationService` 做异步小幅复盘，不阻塞。
- `TurnUnderstandingService` 给下游提供结构化本地理解，不直接生成回复。
- `QuickJudge` 只修正意图、连续性和回复计划，不直接打关系分。
- `PlotDirector` 决定当前轮剧情是否推进。
- `SceneDirector` 更新场景状态。
- `ExpressiveLlmClient` 负责自然表达，不应自己改关系分。

## 12. 前端可见调试字段

关系评分相关：

- `affection_score`
- `affection_delta`
- `score_reasons`
- `relationship`
- `pending_relationship_calibration`
- `pending_relationship_calibration_at`

QuickJudge 相关：

- `quick_judge_status.status`
- `quick_judge_status.reason`
- `quick_judge_status.confidence`
- `quick_judge_status.triggerScore`
- `quick_judge_status.triggerReasons`
- `quick_judge_status.suppressedReasons`

TurnContext 相关：

- `lastTurnContext.userReplyAct`
- `lastTurnContext.sceneMoveKind`
- `lastTurnContext.localConflicts`
- `lastTurnContext.scoreReasons`
- `lastTurnContext.plotSignal`
- `lastTurnContext.plotPressure`
- `lastTurnContext.plotGap`
- `lastTurnContext.plotSceneSignal`
- `lastTurnContext.plotRelationshipSignal`
- `lastTurnContext.plotEventSignal`
- `lastTurnContext.plotContinuitySignal`
- `lastTurnContext.plotRiskSignal`

排查建议：

- 关系分奇怪：先看 `score_reasons`。
- QuickJudge 没工作：先看 `triggerScore / triggerReasons / suppressedReasons`。
- 剧情推太快：先看 `plot_macro_score`、`plotSignal`、`plotPressure`、`plotGap` 和信号拆分。
- 剧情一直不推：先看是否被 `gap < 4`、短回复、显式换场、`sceneMoveKind` 或 `localConflicts` 压住。
- 心跳后数字变 0：先确认 `lastTurnContext.plotPressure / plotSignal / plotGap` 是否被正确继承；心跳正常不应无故清空剧情蓄力。
- 场景乱跳：先看 `sceneMoveKind` 和 `localConflicts`。
- 远程评分没生效：先看是否有 `pending_relationship_calibration`，以及下一轮是否出现 `llm_calibration`。

## 13. 维护原则

- 新增规则前先判断它属于关系评分、QuickJudge 触发分、本轮信号，还是剧情蓄力。
- 优先新增结构化 Act 或结构化冲突，不要继续堆散乱关键词。
- 本地评分必须可解释，明显加减分要进入 `scoreReasons`。
- 异步 LLM 只能小幅校准，不能接管主评分。
- 异步评分和剧情加分都必须复用三维阶段门槛。
- 剧情推进加成必须受风险状态约束，踩边界时不能继续加分。
- 除 QuickJudge 的 `urgent / opportunistic / always` 短等待窗口外，异步评分和 `background` 判断都不能阻塞主回复。
- 如果评分影响回复策略，应通过 `TurnContext / behaviorTags / riskFlags / localConflicts` 传递，不能让主回复模型自己猜。
