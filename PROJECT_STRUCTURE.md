# PROJECT_STRUCTURE

## 当前版本概览

当前真正运行的版本是：
- 前端：原生静态页面，目录在 `public/`
- 后端：轻量 Java HTTP 服务，目录在 `java-server/src/main/java/com/campuspulse/`
- 持久化：本地文件 `data/runtime/state.bin`
- 智能体形态：`主聊天智能体 + 剧情编排智能体 + 好感评分智能体`
- 记忆方案：`短期上下文 + 结构化长期记忆 + 强/弱/临时三层记忆`
- 主动机制：仅会话内主动，基于在线心跳、静默超时和长聊窗口

默认启动：

```bash
npm start
```

默认测试：

```bash
npm test
```

---

## 根目录结构

```text
C:\Users\Administrator\Desktop\chat
├─ package.json
├─ README.md
├─ PROJECT_STRUCTURE.md
├─ run-java.ps1
├─ test-java.ps1
├─ public/
│  ├─ index.html
│  ├─ app.js
│  └─ styles.css
├─ java-server/
│  └─ src/
│     ├─ main/java/com/campuspulse/
│     │  ├─ AdaptiveServices.java
│     │  ├─ ApiException.java
│     │  ├─ AppConfig.java
│     │  ├─ CampusPulseServer.java
│     │  ├─ Domain.java
│     │  ├─ EventNarrativeRegistry.java
│     │  ├─ ExpressiveLlmClient.java
│     │  ├─ Json.java
│     │  ├─ NarrativeRelationshipService.java
│     │  ├─ RuntimeNarrativeServices.java
│     │  └─ Services.java
│     └─ test/java/com/campuspulse/
│        └─ SmokeTest.java
├─ data/runtime/
│  ├─ state.bin
│  └─ state.json
├─ build/
├─ server/
└─ tests/
```

说明：
- `server/` 与 `tests/` 是更早的 Node 版遗留目录，不是当前默认运行后端。
- `data/runtime/state.bin` 是当前 Java 版本真正使用的持久化文件。
- `data/runtime/state.json` 只用于辅助查看，不是核心事实来源。

---

## 前端结构

### `public/index.html`

单页游戏界面的骨架，主要包含：
- 顶部标题与状态区
- 角色列表区
- 聊天主区域
- 右侧关系/情绪/剧情面板
- 城市与上下文设置区
- 关键剧情选项区

### `public/app.js`

前端主逻辑文件，负责：
- 初始化匿名访客
- 拉取角色列表
- 创建和恢复会话
- 发送聊天消息
- 提交剧情选项
- 上报在线状态心跳
- 保存用户时区与手选城市
- 渲染聊天记录、情绪、剧情、时间天气信息

当前比较关键的前端状态字段：

```js
const state = {
  visitorId: "",
  agents: [],
  currentAgentId: "",
  currentSession: null,
  analytics: null,
  pendingChoiceEvent: null,
  relationshipFeedback: "",
  endingCandidate: "",
  preferredCity: "",
  timezone: "",
  presenceTimer: null
};
```

关键函数：
- `boot()`
- `startSession(agentId)`
- `hydrateSession(sessionId)`
- `sendMessage()`
- `submitChoice(choiceId)`
- `syncVisitorContext()`
- `startPresenceHeartbeat()`
- `renderMessages()`
- `renderRelationshipPanel()`
- `renderEmotionPanel()`
- `renderPlotPanel()`

### `public/styles.css`

负责：
- 聊天气泡和布局
- 角色选择卡片
- 情绪面板、剧情面板、时间天气条
- 在线状态徽标
- 选项按钮与交互反馈

---

## 后端结构

### `AppConfig.java`

运行配置入口，负责读取：
- `PORT`
- `ARK_API_KEY`
- `ARK_MODEL`
- `ARK_BASE_URL`
- `ARK_TIMEOUT_MS`

若没有 `ARK_*`，会回退尝试 `OPENAI_*` 兼容变量。

### `CampusPulseServer.java`

服务入口，负责：
- 启动本地 HTTP 服务
- 路由分发
- 提供静态文件
- 把 API 请求转交给 `ChatOrchestrator`

当前注册的核心运行链路：
- `SocialMemoryService`
- `NarrativeRelationshipService`
- `AdaptiveSafetyService`
- `EventEngine`
- `ExpressiveLlmClient`

### `Domain.java`

领域模型中心，主要定义：
- `AgentProfile`
- `VisitorRecord`
- `SessionRecord`
- `ConversationMessage`
- `MemorySummary`
- `RelationshipState`
- `StoryEvent`
- `ChoiceOption`
- `StoryEventProgress`
- `LlmRequest`
- `LlmResponse`

本轮新增或扩展的关键字段：
- `VisitorRecord.timezone`
- `VisitorRecord.preferredCity`
- `ConversationMessage.replySource`
- `SessionRecord.emotionState`
- `SessionRecord.plotState`
- `SessionRecord.presenceState`
- `SessionRecord.lastProactiveMessageAt`
- `MemorySummary.callbackCandidates`
- `MemorySummary.assistantOwnedThreads`
- `MemorySummary.lastMemoryUseMode`
- `MemorySummary.lastMemoryRelevanceReason`
- `LlmRequest.timeContext`
- `LlmRequest.weatherContext`
- `LlmRequest.sceneFrame`
- `LlmRequest.memoryUsePlan`
- `LlmRequest.emotionState`
- `LlmRequest.replySource`

### `Services.java`

系统主编排文件，当前仍然承载：
- `StateRepository`
- `AgentConfigService`
- `AnalyticsService`
- `EventEngine`
- `MemoryService`
- `ChatOrchestrator`

其中最关键的是 `ChatOrchestrator`。

#### `ChatOrchestrator.sendMessage(...)`

当前单轮消息主链路：
1. 校验访客和会话
2. 执行输入安全检查
3. 记录用户消息
4. 加载角色、最近上下文、长期记忆、关系状态
5. 构建时间上下文与天气上下文
6. 执行好感微评分，更新三维好感与情绪状态
7. 判断剧情是否该推进
8. 生成记忆使用计划
9. 调用主聊天模型生成回复
10. 执行输出安全检查
11. 落库消息、记忆、关系、情绪、剧情、统计
12. 返回前端展示数据

#### `ChatOrchestrator.updatePresence(...)`

会话内主动消息入口，负责：
- 接收前端在线心跳
- 更新在线/聚焦/最近心跳时间
- 判断是否命中静默超时窗口
- 判断是否命中长聊心跳窗口
- 必要时直接生成一条主动消息

#### `ChatOrchestrator.updateVisitorContext(...)`

保存用户上下文来源：
- `timezone`
- `preferredCity`

#### `ChatOrchestrator.chooseEvent(...)`

处理关键剧情选项：
- 记录用户选项
- 结算关系与情绪变化
- 推进剧情状态
- 返回选项后的角色回复

### `RuntimeNarrativeServices.java`

这是本轮新增的运行时叙事服务集合，是当前智能体升级的核心文件。

包含：
- `EmotionState`
- `PlotState`
- `PresenceState`
- `MemoryUsePlan`
- `TimeContext`
- `WeatherContext`
- `PlotDecision`
- `AffectionScoreResult`
- `PresenceResult`
- `SocialMemoryService`
- `AffectionJudgeService`
- `PlotDirectorService`
- `PresenceHeartbeatService`
- `RealityContextService`

#### `SocialMemoryService`

作用：
- 在原有 `AdaptiveMemoryService` 上继续增强
- 保留结构化摘要与三层记忆
- 增加 `callbackCandidates`
- 增加 `assistantOwnedThreads`
- 生成 `memoryUsePlan`

记忆不是数据库，仍然持久化到：

```text
data/runtime/state.bin
```

#### `AffectionJudgeService`

作用：
- 每轮执行轻量微评分
- 输出 `TurnEvaluation`
- 同步更新 `EmotionState`

`EmotionState` 当前包含：
- `warmth`
- `safety`
- `longing`
- `initiative`
- `vulnerability`
- `currentMood`

#### `PlotDirectorService`

作用：
- 维护动态双层剧情节拍
- 用 `beatIndex` 控制约 10 拍收束
- 维护 `phase / sceneFrame / openThreads`
- 控制剧情软门槛与硬上限

当前阶段大致为：
- `相识`
- `升温`
- `拉近`
- `波动`
- `收束`

#### `PresenceHeartbeatService`

作用：
- 管理在线状态
- 控制静默心跳和长聊心跳

当前规则：
- 前端页面可见且聚焦时持续上报
- 连续 45 秒无有效心跳视为离开当前会话
- 静默 90 到 150 秒可能触发一次轻主动
- 长聊达到约 6 分钟后可能触发一次推进型主动
- 若当前存在待选剧情，不触发主动心跳

#### `RealityContextService`

作用：
- 生成时间语境
- 读取手选城市天气
- 维护天气缓存

当前天气源：
- `wttr.in`

当前策略：
- 城市天气缓存 10 分钟
- 拉取失败时只回退到时间语境，不中断聊天

### `AdaptiveServices.java`

保留的旧层，主要包含：
- `AdaptiveMemoryService`
- `AdaptiveSafetyService`
- 若干旧版辅助逻辑

它现在仍然重要，因为：
- `SocialMemoryService` 继承自它
- `AdaptiveSafetyService` 仍在当前运行链路中生效

### `NarrativeRelationshipService.java`

当前关系系统主实现，负责：
- 三维好感 `closeness / trust / resonance`
- 行为标签与风险标签
- 阶段门槛
- 停滞判断
- 结局倾向
- 关键剧情选项结算

### `EventNarrativeRegistry.java`

作用：
- 对基础角色事件进行二次细化
- 补充路线标签、反馈文案、后续方向

### `ExpressiveLlmClient.java`

当前实际使用的回复生成层。

职责：
- 统一封装远程大模型调用
- 支持 ARK 兼容接口
- 构造系统提示词
- 接收新的上下文输入：
  - 时间
  - 天气
  - 场景
  - 记忆使用计划
  - 情绪状态
  - 回复来源
- 远程失败时回退本地 mock

当前 `replySource` 主要包含：
- `user_turn`
- `plot_push`
- `silence_heartbeat`
- `long_chat_heartbeat`
- `choice_result`

### `Json.java`

轻量 JSON 解析与序列化工具。

### `ApiException.java`

统一 API 异常结构。

---

## 数据持久化

### 核心状态文件

```text
data/runtime/state.bin
```

持久化内容包括：
- 访客信息
- 会话信息
- 消息记录
- 记忆摘要
- 关系状态
- 情绪状态
- 剧情状态
- 在线状态
- 统计事件

### 会话级关键对象

`SessionRecord` 当前至少包括：
- 基础会话信息
- `relationshipState`
- `memorySummary`
- `storyEventProgress`
- `emotionState`
- `plotState`
- `presenceState`
- `pendingChoiceEventId`
- `pendingChoices`
- `lastProactiveMessageAt`

---

## 当前 API

### 已有接口

- `POST /api/visitor/init`
- `GET /api/agents`
- `POST /api/session/start`
- `POST /api/chat/send`
- `GET /api/session/state`
- `GET /api/session/history`
- `POST /api/feedback`
- `POST /api/event/choose`

### 本轮新增接口

- `POST /api/visitor/context`
- `POST /api/session/presence`

### `POST /api/chat/send` 当前关键返回字段

- `reply_text`
- `affection_score`
- `affection_delta`
- `relationship_stage`
- `triggered_event`
- `interaction_mode`
- `choices`
- `relationship_feedback`
- `ending_candidate`
- `emotion_state`
- `plot_progress`
- `scene_frame`
- `reply_source`
- `memory_expire_at`
- `fallback_used`

### `GET /api/session/state` 当前关键返回字段

- `relationshipState`
- `memorySummary`
- `storyEventProgress`
- `emotionState`
- `plotState`
- `presenceState`
- `visitorContext`
- `timeContext`
- `weatherContext`
- `pendingChoices`

---

## 当前记忆机制说明

### 短期记忆

来自最近 18 条上下文消息，用于当前轮生成。

### 长期记忆

当前主要由 `MemorySummary` 承载，包含：
- 用户偏好
- 身份信息
- 约定事项
- 共同经历
- 待回应线索
- 讨论主题
- 情绪摘要
- 里程碑

### 三层记忆

- `strongMemories`
- `weakMemories`
- `temporaryMemories`

### 本轮新增的“自然使用”

- `memoryUsePlan.useMode`
- `memoryUsePlan.relevanceReason`
- `callbackCandidates`
- `assistantOwnedThreads`

目标是让角色不是每次都机械提旧信息，而是更像“知道什么时候轻轻带回”。

---

## 当前剧情机制说明

当前已经不是只靠固定事件池硬触发，而是混合了两层：

### 第一层：受控骨架

由 `PlotDirectorService` 控制：
- 节拍推进
- 阶段切换
- 最迟推进轮次
- 当前场景框架

### 第二层：局部动态展开

由主聊天层根据以下信息自然写出：
- 当前场景
- 角色情绪
- 记忆使用计划
- 时间天气语境

这样做的目标是：
- 不会完全失控
- 又不至于只有硬编码事件文本

---

## 当前主动消息机制说明

主动消息只在用户在线时发生，不做离线补发。

触发来源：
- `silence_heartbeat`
- `long_chat_heartbeat`
- `plot_push`

主动消息的生成仍然只由主聊天智能体对外输出，但会参考：
- 当前场景
- 当前情绪状态
- 回调候选记忆
- 角色自己挂念的线程

---

## 测试结构

### `java-server/src/test/java/com/campuspulse/SmokeTest.java`

当前覆盖的重点包括：
- 结构化记忆写入
- 记忆召回
- 短输入时的主动延展
- 场景承接
- 访客上下文保存
- 在线心跳触发主动消息
- 事件权重与阶段门槛
- 关键选项交互
- 选项后的关系/情绪结算
- 安全拦截

本轮验证结果：

```text
npm test -> passed
node --check public/app.js -> passed
```

---

## 本轮新增/修改总结

本轮重点新增：
- 新文件 `RuntimeNarrativeServices.java`
- 三智能体协作所需的运行时类型与服务
- 会话内主动心跳机制
- 时间 + 手选城市天气上下文
- 情绪状态模块
- 动态剧情节拍状态
- 访客上下文接口
- presence 心跳接口
- 前端在线心跳与上下文设置 UI
- `replySource / emotionState / plotState / presenceState` 全链路打通

本轮同步修复：
- `PROJECT_STRUCTURE.md` 乱码重写
- `timeContextMap(...)` 空值映射更稳
- 记忆中“下次/一起/旧场景”回调提取增强
- `ExpressiveLlmClient.java` 重写为干净中文版本，强化主动延展、场景承接与主动心跳文案
- `EventNarrativeRegistry.java` 重写为干净中文版本，清理关键剧情选项、反馈文案与路线说明
- 新一轮聊天体验优化：明确要求“先接球再推进”、弱化括号式旁白、主动心跳避免在剧情推进后紧贴触发
- 新增回归测试：直接问题先回答、剧情推进后不叠加主动问候

---

## 后续建议优先级

如果下一轮继续完善，建议优先顺序：

1. 进一步清理仍留在旧角色文案里的历史乱码文本
2. 把剧情骨架的 phase 文案再细化成更自然的校园场景模板
3. 给主动消息增加更细的限频与最近主动原因展示
4. 补一个简易调试面板，直接查看 `emotionState / plotState / memoryUsePlan`
5. 视数据量再决定是否从 `state.bin` 迁移数据库
---

## 2026-04-21 本次追加

- 前端聊天消息现在支持“动作”和“台词”分层展示：
  动作会以较轻的说明条显示在消息正文上方，正文只保留真正对用户说的话。
- 前端新增 assistant 消息拆分逻辑：
  会优先识别前置动作、场景描述、问句前的动作铺垫，尽量避免整段内容都挤成一句话。
- `plot_push` 的语义已收紧：
  它现在表示“当前聊天里顺势推进剧情”，不再默认等价于“角色在聊天外给你发来一条消息”。
- `ExpressiveLlmClient.java` 已新增明确约束：
  `plot_push` 禁止写成“给你发消息 / 看到你回复 / 屏幕那头”这类异步联系口吻；
  只有 `silence_heartbeat` 和 `long_chat_heartbeat` 才属于真正的主动消息。
- 新增回归测试：
  校验剧情推进时不会把同一段对话错误写成异步发消息场景。

---

## 2026-04-21 继续调优

- 回复生成层现在从后端直接输出结构化字段：
  `action_text / speech_text`，不再只靠前端启发式拆分。
- `ConversationMessage` 与 `LlmResponse` 已增加动作/台词分层字段：
  历史消息、主动消息和当前轮回复都能保留这两层信息。
- 短期上下文现在会优先使用“动作 + 台词”的组合文本进入模型上下文，
  避免只记住一句被裁剪后的正文。
- `ExpressiveLlmClient.java` 本轮继续优化：
  - 新增 `ReplyParts` 结构和动作标记解析
  - 增加更自然的提问/不提问节奏判断，降低每轮都反问的模板感
  - 增加“好感 / 吸引 / 喜欢我”这类直球问题的直接回答
  - 记忆回调改为人话化，不再把 `强记忆 / 阶段重点 / 剧情` 这类内部摘要原样说出来
  - 主动心跳消息也会先做人话化记忆处理
- `RuntimeNarrativeServices.java` 本轮继续优化：
  - 初始 `sceneFrame` 改成更像当下环境的场景文案
  - 剧情场景从“关系元叙事”进一步转向“更具体的环境/语气/动作氛围”
  - `buildAmbientScene(...)` 不再每轮重复拼接天气和时间，减少场景文案膨胀
- 新增整轮体验回放器：
  `java-server/src/test/java/com/campuspulse/ExperienceReplay.java`
  可直接打印一整段模拟聊天、`cool` 短句场景和主动心跳触发结果。
- 新增/调整回归测试：
  - 校验结构化回复字段存在
  - 校验“你对我也有好感吗”会直接回答
  - 校验剧情推进边界保持在当前聊天语境内
