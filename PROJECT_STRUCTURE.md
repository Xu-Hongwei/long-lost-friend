# PROJECT_STRUCTURE

## 当前版本概览

当前真正运行的版本是：
- 前端：原生静态页面，目录在 `public/`
- 后端：轻量 Java HTTP 服务，目录在 `java-server/src/main/java/com/campuspulse/`
- 持久化：本地文件 `data/runtime/state.bin`
- 智能体形态：单角色智能体 + 记忆系统 + 关系阶段机 + 事件池 + 关键选项

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
│     │  ├─ AppConfig.java
│     │  ├─ CampusPulseServer.java
│     │  ├─ Domain.java
│     │  ├─ Json.java
│     │  ├─ Services.java
│     │  ├─ AdaptiveServices.java
│     │  ├─ NarrativeRelationshipService.java
│     │  ├─ EventNarrativeRegistry.java
│     │  └─ ExpressiveLlmClient.java
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
- `server/` 和 `tests/` 是旧 Node 版遗留目录，不是当前默认后端。
- `data/runtime/state.bin` 是当前 Java 版真正使用的持久化文件。

---

## 前端结构

### `public/index.html`

单页试玩界面的骨架，主要包含：
- 顶部信息区
- 角色选择区
- 聊天主区域
- 右侧状态区
- 关键剧情选项区

### `public/app.js`

前端核心逻辑文件，负责：
- 初始化匿名访客
- 拉取角色与会话状态
- 发送聊天消息
- 提交剧情选项
- 渲染聊天记录、关系状态、记忆面板、剧情选项

当前关键状态：

```js
const state = {
  visitorId: "",
  agents: [],
  currentAgentId: "",
  currentSession: null,
  analytics: null,
  pendingChoiceEvent: null,
  relationshipFeedback: "",
  endingCandidate: ""
};
```

关键函数：
- `boot()`
- `startSession(agentId)`
- `hydrateSession(sessionId)`
- `renderMessages()`
- `renderRelationship()`
- `renderChoicePanel()`
- `submitChoice(choiceId)`

### `public/styles.css`

负责整体视觉风格、聊天气泡、关系面板、选项按钮和状态展示。

---

## 后端结构

### `AppConfig.java`

配置入口，负责读取：
- `PORT`
- `ARK_API_KEY`
- `ARK_MODEL`
- `ARK_BASE_URL`
- `ARK_TIMEOUT_MS`

如果没有 `ARK_*`，会回退读取 `OPENAI_*`。

### `CampusPulseServer.java`

服务入口，负责：
- 启动本地 HTTP 服务
- 路由分发
- 静态文件服务
- API 请求转交给 `ChatOrchestrator`

当前默认接入的运行链路：
- `AdaptiveMemoryService`
- `NarrativeRelationshipService`
- `AdaptiveSafetyService`
- `EventEngine`
- `ExpressiveLlmClient`

### `Domain.java`

领域模型与固定内容中心，主要定义：
- `AgentProfile`
- `StoryEvent`
- `ChoiceOption`
- `EventEffect`
- `SessionRecord`
- `ConversationMessage`
- `MemorySummary`
- `RelationshipState`
- `StoryEventProgress`
- `LlmRequest`
- `LlmResponse`

同时包含 5 个角色的人设与基础事件池。

### `Json.java`

轻量 JSON 解析与序列化工具。

### `Services.java`

核心业务编排文件，当前仍承担：
- `StateRepository`
- `AgentConfigService`
- `AnalyticsService`
- `EventEngine`
- `ChatOrchestrator`
- 基础 `LlmClient` / `CompositeLlmClient`

其中最重要的是：
- `ChatOrchestrator.sendMessage(...)`
- `ChatOrchestrator.chooseEvent(...)`

#### `sendMessage(...)` 当前主链路

1. 校验访客和会话
2. 检查是否存在待处理剧情选项
3. 记录用户消息
4. 执行输入安全检查
5. 从事件池中挑选当前最匹配事件
6. 执行关系评估
7. 召回短期上下文和长期记忆
8. 生成回复
9. 执行输出安全检查
10. 落库消息、关系状态、记忆、统计
11. 返回给前端

### `AdaptiveServices.java`

保留自适应记忆与安全逻辑，主要包括：
- `AdaptiveMemoryService`
- `AdaptiveSafetyService`
- 旧的 `AdaptiveCompositeLlmClient`

说明：
- 现在运行时已经不再默认使用 `AdaptiveCompositeLlmClient`
- 当前保留它，主要是为了参考和向后兼容

### `NarrativeRelationshipService.java`

当前关系系统的主实现，负责：
- 三维好感度评估：`closeness / trust / resonance`
- 行为标签和风险标记
- 阶段门槛与阶段跃迁限制
- 停滞状态
- 结局倾向判定
- 关键剧情选项结算

它让关系不再只是“关键词加减分”，而是更像：
- 主动回应
- 真诚分享
- 接住情绪
- 尊重边界
- 敷衍或冒犯

### `EventNarrativeRegistry.java`

事件二次精修层。

作用：
- 对基础事件池进行角色化改写
- 细化选项文案
- 细化成功 / 中性 / 失败反馈
- 细化路线标签和下一步期待方向

### `ExpressiveLlmClient.java`

这是当前运行中的对话生成层。

引入原因：
- 之前的回复更像“一问一答”
- 场景承接弱
- 主动性不够
- 容易写成括号动作 + 机械收尾

当前这个版本重点解决 4 件事：
- 主动推进：用户输入很短时，系统会主动给方向，而不是把压力丢回给用户
- 场景过渡：每轮尽量带一小段气氛承接，不让回复像孤立句子
- 记忆自然带出：相关记忆会被自然引用，而不是硬塞设定
- 少舞台说明：减少括号动作，把动作和氛围融进自然叙述

它包含两部分：
- 远程模型提示词构建
- 本地 mock 回复生成

#### `generateMockReply(...)`

当前 mock 回复结构大致是：

1. 开场接住
2. 情绪或语义确认
3. 场景桥接
4. 记忆承接
5. 主动延展
6. 收尾留口

这样做的目标是让本地 mock 也能更像“持续相处中的聊天”，而不是测试专用死模板。

#### `buildSystemPrompt(...)`

当前远程提示词会显式约束模型：
- 不要永远一问一答
- 每次回复尽量同时做到“接住 + 轻微推进 + 主动延展”
- 用户输入很短时由模型主动给方向
- 少用括号动作和舞台说明
- 回复通常 2 到 4 句，保持自然和连贯

---

## 记忆机制

当前记忆是“结构化记忆 + 三层记忆”并存。

### 结构化记忆

主要字段包括：
- `preferences`
- `identityNotes`
- `promises`
- `openLoops`
- `sharedMoments`
- `discussedTopics`
- `emotionalNotes`
- `milestones`
- `lastUserMood`
- `lastUserIntent`
- `responseCadence`

### 三层记忆

- `strongMemories`
- `weakMemories`
- `temporaryMemories`

当前逻辑是：
- 先做结构化抽取
- 再做相关记忆召回
- 召回结果会进入本轮回复生成

---

## 好感度与剧情机制

### 关系系统

三维好感度：
- `closeness`
- `trust`
- `resonance`

阶段推进：
- `初识`
- `升温`
- `心动`
- `靠近`
- `确认关系`

补充状态：
- `stageProgressHint`
- `stagnationLevel`
- `routeTag`
- `endingCandidate`
- `relationshipFeedback`

### 事件系统

每个 `StoryEvent` 当前支持：
- `eventId`
- `title`
- `theme`
- `category`
- `stageRange`
- `unlockConditions`
- `blockConditions`
- `weight`
- `cooldown`
- `choiceSet`
- `successEffects`
- `neutralEffects`
- `failEffects`
- `followupEventIds`
- `nextDirection`

### 交互模式

普通轮次：
- `interaction_mode = "chat"`

关键剧情：
- `interaction_mode = "choice"`

前端会展示 2 到 3 个选项按钮，提交到：

```text
POST /api/event/choose
```

---

## API 清单

当前核心接口：
- `GET /api/health`
- `GET /api/agents`
- `POST /api/visitor/init`
- `POST /api/session/start`
- `POST /api/chat/send`
- `POST /api/event/choose`
- `GET /api/session/state`
- `GET /api/session/history`
- `POST /api/feedback`
- `GET /api/analytics/overview`

`POST /api/chat/send` 关键返回字段：
- `reply_text`
- `affection_score`
- `affection_delta`
- `relationship_stage`
- `triggered_event`
- `interaction_mode`
- `choices`
- `relationship_feedback`
- `ending_candidate`
- `memory_expire_at`
- `fallback_used`

---

## 测试

测试入口：

```bash
npm test
```

当前 smoke test 覆盖：
- 结构化记忆写入
- 相关记忆召回
- 短输入时的主动推进
- 回复中的场景过渡
- 事件权重触发
- 阶段门槛阻止越级
- 关键剧情选项弹出
- 选项结算
- 危险输入柔性拦截

---

## 最近更新记录

### 本轮更新：主动推进与场景过渡增强

本轮新增：
- `java-server/src/main/java/com/campuspulse/ExpressiveLlmClient.java`

本轮调整：
- `CampusPulseServer.java`
  - 默认运行时对话生成层从 `AdaptiveCompositeLlmClient` 切换为 `ExpressiveLlmClient`
- `SmokeTest.java`
  - 新增“短输入主动推进”和“场景桥接”测试

本轮效果：
- 回复不再只是被动一问一答
- 用户输入很短时，角色会主动给一个容易接的话头
- 回复里会带轻微场景承接，不再像零散句子拼接
- 模型提示词和本地 mock 都更明确地避免括号动作堆叠
