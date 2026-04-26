# PROJECT_STRUCTURE

本文档只记录当前有效的项目结构、运行方式和核心协作链路。历史试验、已删除接口、旧 Node 后端、旧原生前端和一次性排错记录不再保留在这里。

## 1. 项目定位

Campus Pulse 是一个面向校园关系模拟的聊天型 Web 应用：

- 前端：Vue 3 + Vite + TypeScript + Pinia + Tailwind CSS + Motion for Vue。
- 后端：轻量 Java HTTP 服务，无 Spring 依赖。
- 持久化：本地文件 `data/runtime/state.bin`。
- 静态资源：Vite 从 `static/` 复制公开资源，构建后由 Java 服务托管 `dist/`。
- 核心体验：选择角色，进入持续会话，由主回复、剧情导演、场景导演、意图修正、记忆与关系系统共同驱动对话。

默认访问地址：

```text
http://localhost:3000
```

## 2. 常用命令

```bash
npm start
```

先执行 `npm run build:web`，再通过 `run-java.ps1` 编译并启动 Java 服务。

```bash
npm run dev:web
```

仅启动 Vite 前端开发服务，适合单独调前端。

```bash
npm run check:web
```

执行 Vue/TypeScript 类型检查。

```bash
npm run build:web
```

构建前端到 `dist/`。

```bash
npm test
```

先构建前端，再执行 Java smoke / regression 测试。

```powershell
.\test-java.ps1
```

只编译 Java 并运行 Java 测试。

## 3. 根目录结构

```text
C:\Users\Administrator\Desktop\chat
├─ package.json
├─ package-lock.json
├─ tsconfig.json
├─ vite.config.ts
├─ index.html
├─ README.md
├─ PROJECT_STRUCTURE.md
├─ run-java.ps1
├─ test-java.ps1
├─ src/
├─ static/
├─ java-server/
├─ data/runtime/
├─ dist/          # 前端构建产物，可删除后重新生成
├─ build/         # Java 编译/测试产物，可删除后重新生成
└─ node_modules/  # npm 依赖，不建议随手删除
```

当前不再使用的旧目录：

- `public/`：旧原生前端，已删除。
- `server/`：旧 Node 后端，已删除。
- `tests/`：旧 Node 测试，已删除。
- 旧桥接脚本：已删除。

## 4. 前端结构

### 4.1 入口

- `index.html`：Vite HTML 入口，挂载 `#app`。
- `src/main.ts`：创建 Vue 应用、注册 Pinia、挂载 `App.vue`。
- `src/App.vue`：页面总装配层，串联 Hero、角色选择、聊天舞台、抽屉和全局氛围色。
- `src/styles.css`：Tailwind 入口和全局视觉样式。

### 4.2 组件

- `src/components/HeroSection.vue`
  - 首页主视觉区。
  - 展示当前选中角色的海报、主标题、副标题和开始/续聊按钮。

- `src/components/AgentRail.vue`
  - 角色横向选择区。
  - 点击角色会同步切换 Hero、聊天窗口和当前会话。

- `src/components/ChatStage.vue`
  - 聊天主舞台。
  - 管理沉浸/观察布局、Quick Judge 面板、消息流、输入区和侧栏信息。

- `src/components/MessageStack.vue`
  - 消息渲染层。
  - `sceneText` 作为场景气泡展示。
  - `actionText` 已融合进对话气泡正文，不再单独展示动作气泡。
  - `speechText` / `text` 是角色或用户真正说的话。

- `src/components/ComposerBar.vue`
  - 输入框和发送按钮。

- `src/components/InsightDrawer.vue`
  - 观察/沉浸辅助信息抽屉。
  - 展示关系、记忆、剧情、场景、Quick Judge 等状态。

- `src/components/CheckpointSheet.vue`
  - 每阶段关键节点的总结和继续/结算入口。

- `src/components/RelationshipMiniPanel.vue`
  - 关系阶段和关系数值展示。

- `src/components/PlotMiniPanel.vue`
  - 剧情阶段、拍数、路线和心跳说明展示。

### 4.3 Store

- `src/stores/session.ts`
  - 会话总状态。
  - 负责角色列表、当前角色、当前会话、访客 ID、城市上下文、Quick Judge 配置和主要 API 调用。
  - Quick Judge 前端模式：`off`、`smart`、`always`。
  - Quick Judge 等待时间：前端以秒为单位传入，当前范围为 `0.06s` 到 `5s`，默认 `0.3s`。

- `src/stores/chat.ts`
  - 输入草稿和发送行为。
  - 与 presence typing 状态联动。

- `src/stores/presence.ts`
  - 页面可见性、焦点、输入状态和心跳上报。

- `src/stores/ui.ts`
  - 沉浸模式、观察模式、抽屉开关。

- `src/stores/checkpoint.ts`
  - 阶段总结弹层状态。

### 4.4 类型与工具

- `src/types.ts`
  - 前端 API 数据类型中心。
  - 重点类型包括 `AgentProfile`、`SessionRecord`、`ConversationMessage`、`SceneState`、`DialogueContinuityState`、`QuickJudgeStatus`。

- `src/lib/api.ts`
  - 前端统一 fetch 封装。

- `src/lib/labels.ts`
  - 回复来源、时间、情绪、阶段等 UI 标签转换。

## 5. 静态资源

角色图片放在 `static/characters/`。Vite 构建时会复制到 `dist/characters/`，后端通过静态文件服务返回。

```text
static/characters/
├─ healing/portrait.png
├─ lively/portrait.png
├─ cool/portrait.png
├─ artsy/portrait.png
└─ sunny/portrait.png
```

角色 ID 与当前视觉方向：

- `healing`：林晚栀，图书馆窗边，温柔治愈。
- `lively`：许朝暮，社团夜市，活泼元气。
- `cool`：沈砚，夜色楼道，冷静慢热。
- `artsy`：顾遥，黄昏桥边，文艺内敛。
- `sunny`：周燃，操场清风，阳光运动。

角色设定的后端源头在 `java-server/src/main/java/com/campuspulse/Domain.java`，前端不要把 UI 标签当成设定真源。

## 6. 后端结构

后端源码目录：

```text
java-server/src/main/java/com/campuspulse/
├─ CampusPulseServer.java
├─ AppConfig.java
├─ Domain.java
├─ Services.java
├─ RuntimeNarrativeServices.java
├─ AdaptiveServices.java
├─ ExpressiveLlmClient.java
├─ NarrativeRelationshipService.java
├─ EventNarrativeRegistry.java
├─ Json.java
└─ ApiException.java
```

### 6.1 `CampusPulseServer.java`

轻量 HTTP 入口，负责：

- 启动 socket 服务。
- 托管 `dist/` 静态文件。
- 路由 `/api/*` 请求。
- 组装核心服务并创建 `ChatOrchestrator`。

当前 API：

- `GET /api/health`
- `GET /api/agents`
- `POST /api/visitor/init`
- `POST /api/visitor/context`
- `POST /api/session/start`
- `GET /api/session/state`
- `POST /api/chat/send`
- `POST /api/session/presence`
- `POST /api/session/checkpoint`
- `POST /api/session/settle`
- `POST /api/event/choose`
- `GET /api/analytics/overview`

### 6.2 `AppConfig.java`

运行配置中心，负责读取：

- `PORT`
- `DASHSCOPE_*`
- `ARK_*`
- `OPENAI_*`
- `PLOT_LLM_*`
- `QUICK_JUDGE_*`

当前静态目录固定为 `dist/`。如果没有先构建前端，Java 服务会找不到前端页面。

### 6.3 `Domain.java`

领域模型与角色设定中心，负责：

- 5 个角色的基础资料、性别代词、视觉资源、人物背景。
- 会话、消息、记忆、关系、剧情、场景、意图、Quick Judge 状态等序列化模型。
- `AppState` 本地持久化结构。

修改角色设定时优先改这里。

### 6.4 `Services.java`

主业务编排层，核心类是 `ChatOrchestrator`，负责：

- 初始化访客和会话。
- 处理用户消息。
- 调用记忆、关系、安全、剧情、场景、Quick Judge 和 LLM。
- 写入状态文件。
- 生成前端需要的响应 payload。
- 处理 presence 心跳、checkpoint、事件选择和 analytics。

### 6.5 `RuntimeNarrativeServices.java`

运行时叙事服务集合，包含：

- `IntentInferenceService`：本地意图初判。
- `DialogueContinuityService`：连续对话目标、承诺、追问和场景转移状态。
- `QuickJudgeLocalCorrectionService`：本地修正收敛。
- `QuickJudgeService`：条件远程轻判断，晚到结果可进入下一轮修正槽。
- `SceneMoveIntentService`：本地结构化判断“用户是否想移动/换场景”。
- `PlotDirectorAgentService`：剧情导演智能体，决定当前轮是否推进剧情或转场。
- `PlotDirectorService` / `EnhancedPlotDirectorService`：把导演结果落成 `PlotDecision`。
- `SceneDirectorService`：维护当前地点、互动模式、场景目标。
- `PresenceHeartbeatService` / `EnhancedPresenceHeartbeatService`：心跳回复。
- `RealityGuardService`：返回前事实与场景一致性修复。
- `HumanizationEvaluationService`：回复是否自然、是否过度机械的审计。
- `SearchDecisionService` / `RealityContextService`：时间、天气、搜索上下文。

### 6.6 `AdaptiveServices.java`

本地自适应能力扩展，包含：

- `AdaptiveMemoryService`
- `AdaptiveRelationshipService`
- `AdaptiveSafetyService`
- `AdaptiveCompositeLlmClient`

主要负责记忆提取、关系评分、安全检查和 LLM 降级组合。

### 6.7 `ExpressiveLlmClient.java`

主回复生成层，负责：

- 组装角色、关系、记忆、剧情、场景、Quick Judge 修正、时间天气等上下文。
- 调用远程模型或本地 mock。
- 解析 `[[SCENE]]...[[/SCENE]]`、`[[ACTION]]...[[/ACTION]]` 和正文。
- 屏蔽内部模块名，避免回复里出现 `QuickJudge`、`意图修正`、系统提示词等内部实现。
- 清洗重复句、重复场景、过度旁白和明显不自然表达。

### 6.8 关系与事件

- `NarrativeRelationshipService.java`
  - 关系数值和关系阶段更新。

- `EventNarrativeRegistry.java`
  - 角色事件、路线反馈、剧情事件叙事。

### 6.9 测试

```text
java-server/src/test/java/com/campuspulse/
├─ SmokeTest.java
├─ HumanizationRegressionTest.java
└─ ClosedLoopAgentTest.java
```

测试重点：

- API 基础链路。
- 角色回复人性化回归。
- 闭环剧情/关系/记忆行为。

## 7. 当前主对话链路

用户发送消息后，当前核心流程是：

```text
用户输入
  -> IntentInference 本地意图初判
  -> DialogueContinuity 初判
  -> QuickJudgeLocalCorrection 第一次收敛
  -> 判断 QuickJudge 模式与触发条件
  -> 条件异步启动 QuickJudge

  -> PlotDirector 当前轮剧情决策
  -> SceneDirector 场景更新
  -> QuickJudgeLocalCorrection 第二次收敛

  -> 如果 QuickJudge 已返回且置信度足够
       融合 QuickJudge
       QuickJudgeLocalCorrection 第三次收敛
     否则
       不阻塞或仅短暂等待
       晚到结果写入下一轮修正槽

  -> ExpressiveLlmClient 生成主回复
  -> RealityGuard / Humanization / Scene 清洗
  -> 保存状态并返回前端
```

设计原则：

- `PlotDirector` 是当前轮硬前置，因为剧情推进和转场不能完全等下一轮修。
- `QuickJudge` 是机会型并行助手，不应该长期卡住主回复。
- `SceneDirector` 吃结构化场景移动意图，负责更新当前地点和互动模式。
- `ExpressiveLlmClient` 最后收口，拿已经收敛过的状态生成自然语言。
- 晚到的 Quick Judge 结果进入 `pendingQuickJudgeCorrection`，下一轮由主回复自然承接，不暴露内部模块名。

## 8. Quick Judge 配置

前端非沉浸模式中可以调 Quick Judge：

- `off`：关闭远程轻判断。
- `smart`：默认模式，只在模糊/高价值轮尝试远程修正。
- `always`：每轮都尝试远程修正，适合调试或压测。

等待时间：

- 前端以秒为单位设置。
- 当前前端限制为 `0.06s` 到 `5s`。
- 默认值为 `0.3s`。
- 后端会把该值转换为毫秒，并在主回复请求前作为最多额外等待窗口。

环境变量：

- `QUICK_JUDGE_FORCE_ALL=true`
  - 后端诊断开关，强制非空消息都尝试 Quick Judge。
  - 日常更推荐用前端面板控制。

- `QUICK_JUDGE_WAIT_MS`
  - 后端兜底等待窗口。
  - 当前前端传值会覆盖这一兜底值。

## 9. 心跳机制

前端通过 `presence.ts` 上报：

- 页面是否可见。
- 页面是否聚焦。
- 用户是否正在输入。
- 草稿长度。
- 最后输入时间。
- 客户端当前时间。

后端入口：

```text
POST /api/session/presence
```

心跳回复不会简单复用普通用户消息的完整推进逻辑，而是走 presence 专用路径：

- 先融合晚到 Quick Judge 修正。
- 再进行本地连续性和意图收敛。
- 控制心跳触发频率，避免频繁打扰。
- 生成更短、更贴近当前关系和上下文的主动陪伴回复。

## 10. LLM 与降级策略

主回复优先级：

1. 如果配置了 `DASHSCOPE_API_KEY` 或相关 `DASHSCOPE_*`，主回复优先使用 DashScope OpenAI-Compatible 链路。
2. 未配置 DashScope 时，回退到 `ARK_*`。
3. 再回退到 `OPENAI_*`。
4. 没有可用远程模型或请求失败时，使用本地 mock。

默认主回复模型：

```text
qwen-plus-character
```

剧情/轻判断链路可用 `PLOT_LLM_*` 独立覆盖；没有独立配置时复用主 LLM 配置。

常用环境变量：

- `DASHSCOPE_API_KEY`
- `DASHSCOPE_BASE_URL`
- `DASHSCOPE_MODEL`
- `DASHSCOPE_TIMEOUT_MS`
- `ARK_API_KEY`
- `ARK_MODEL`
- `ARK_BASE_URL`
- `ARK_TIMEOUT_MS`
- `OPENAI_API_KEY`
- `OPENAI_MODEL`
- `OPENAI_BASE_URL`
- `OPENAI_TIMEOUT_MS`
- `PLOT_LLM_API_KEY`
- `PLOT_LLM_MODEL`
- `PLOT_LLM_BASE_URL`
- `PLOT_LLM_TIMEOUT_MS`

PowerShell 示例：

```powershell
$env:DASHSCOPE_API_KEY="你的百炼密钥"
$env:DASHSCOPE_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:DASHSCOPE_MODEL="qwen-plus-character"
npm start
```

## 11. 数据文件

```text
data/runtime/state.bin
data/runtime/state.json
```

- `state.bin` 是运行时主要持久化文件。
- `state.json` 用于调试观察。
- 这两个文件包含本地会话状态，不应作为普通清理项随手删除。

如果必须重置本地试玩数据，先确认不需要保留当前会话。

## 12. 构建产物与清理规则

可以安全删除并重新生成：

- `build/`
- `dist/`

不建议随手删除：

- `node_modules/`
- `data/runtime/state.bin`
- `data/runtime/state.json`

已经下线并删除的旧资产：

- 旧 Node 后端。
- 旧原生前端。
- 旧 Node 测试。
- 旧桥接脚本。
- 旧 SVG 占位图。
- 独立 replay / latency 探针。
- 临时日志文件。

## 13. 修改建议入口

常见修改点：

- 改角色设定：优先看 `Domain.java`。
- 改主回复风格：优先看 `ExpressiveLlmClient.java`。
- 改智能体协作顺序：优先看 `Services.java` 的 `ChatOrchestrator.sendMessage(...)`。
- 改 Quick Judge 触发/等待：看 `RuntimeNarrativeServices.java` 的 `QuickJudgeService` 和 `src/stores/session.ts`。
- 改场景移动判断：看 `SceneMoveIntentService`、`SceneDirectorService`、`PlotDirectorAgentService`。
- 改聊天界面：看 `ChatStage.vue`、`MessageStack.vue`、`ComposerBar.vue`。
- 改首页/选人页：看 `HeroSection.vue`、`AgentRail.vue`。

## 14. 当前验证命令

修改结构、接口或智能体链路后，至少跑：

```bash
npm run check:web
npm run build:web
.\test-java.ps1
```

如果改了前端交互，还应在浏览器里手动验证：

- 首页选择角色是否同步 Hero 和聊天窗口。
- 新角色“选她/他开场”和已有会话“继续聊”是否区分正确。
- Quick Judge 面板是否能切换模式和等待时间。
- `sceneText` 是否只承载场景转移，不和主回复重复。
- `actionText` 是否融合进对话气泡正文。
