# PROJECT_STRUCTURE

## 当前版本概览

当前项目已经从“原生静态前端 + Java 轻量后端”升级为：

- 前端：`Vue 3 + Vite + TypeScript + Pinia + Tailwind CSS + Motion for Vue`
- 后端：轻量 Java HTTP 服务，目录在 `java-server/src/main/java/com/campuspulse/`
- 持久化：本地文件 `data/runtime/state.bin`
- 静态资源托管：Java 服务优先读取 `dist/`，若前端尚未构建则回退到旧 `public/`
- 智能体形态：主聊天智能体 + 剧情编排 + 好感评估 + 记忆/情绪/场景系统

默认启动：

```bash
npm start
```

这会先执行前端构建，再启动 Java 服务，最终访问地址仍是：

```text
http://localhost:3000
```

前端本地开发预留命令：

```bash
npm run dev:web
```

前端类型检查：

```bash
npm run check:web
```

后端测试：

```bash
npm test
```

---

## 根目录结构

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
│  ├─ main.ts
│  ├─ App.vue
│  ├─ styles.css
│  ├─ vite-env.d.ts
│  ├─ components/
│  ├─ stores/
│  ├─ lib/
│  └─ types.ts
├─ static/
│  └─ characters/
├─ public/
│  ├─ index.html
│  ├─ app.js
│  └─ styles.css
├─ dist/                        # 构建产物，运行时优先使用
├─ java-server/
│  └─ src/main/java/com/campuspulse/
├─ data/runtime/
│  ├─ state.bin
│  └─ state.json
├─ build/
├─ server/
└─ tests/
```

说明：

- `src/` 是当前真正维护中的前端源码目录。
- `static/` 是 Vite 的公开静态资源目录，目前主要存放角色图像。
- `dist/` 是 Vue 前端构建输出目录。
- `public/` 仍然保留旧版原生前端，作为未构建时的兜底静态目录。
- `server/` 与 `tests/` 中的旧 Node 结构不是当前默认运行链路。

---

## 前端结构

### `index.html`

Vite 前端入口文件，挂载 Vue 根节点 `#app`。

### `src/main.ts`

前端启动入口，负责：

- 创建 Vue 应用
- 注入 Pinia
- 挂载 `App.vue`
- 引入全局样式

### `src/App.vue`

当前前端总装配层，负责：

- 组织首屏 Hero、角色轨道、聊天主舞台
- 管理沉浸模式 / 观察模式切换
- 串联 `CheckpointSheet` 与 `InsightDrawer`
- 将当前选中角色的色板映射到全局氛围背景

### `src/components/`

当前主要组件：

- `HeroSection.vue`
  - 首页主视觉区
  - 展示主推角色海报、情绪化文案和主 CTA
- `AgentRail.vue`
  - 角色横向轨道
  - 展示角色立绘、标签、选中态和续聊入口
- `ChatStage.vue`
  - 聊天主舞台
  - 负责消息流、上下文条、关键选项、输入区与桌面端侧边信息区
- `MessageStack.vue`
  - 三层消息展示
  - `sceneText / actionText / speechText` 分离渲染
- `ComposerBar.vue`
  - 底部输入条
  - 结合当前角色、城市和场景提示
- `InsightDrawer.vue`
  - 沉浸模式下的底部抽屉
  - 展示关系、记忆、剧情、数据
- `CheckpointSheet.vue`
  - 每 10 拍的阶段总结弹层
  - 提供“继续推进 / 结算本阶段”
- `RelationshipMiniPanel.vue`
  - 关系阶段和三维好感的轻量展示
- `PlotMiniPanel.vue`
  - 当前剧情阶段、拍数、路线主题和心跳说明

### `src/stores/`

当前主要状态层：

- `session.ts`
  - 会话总状态中心
  - 管理 `visitor / agents / currentSession / analytics`
  - 对接 `/api/visitor/init`、`/api/session/start`、`/api/chat/send`、`/api/session/state`
- `presence.ts`
  - 在线心跳状态
  - 管理输入状态、页面可见性和 `/api/session/presence`
- `chat.ts`
  - 当前输入草稿与发送行为
  - 把输入和心跳状态联动起来
- `ui.ts`
  - 管理 `immersive / inspector` 模式和抽屉状态
- `checkpoint.ts`
  - 管理阶段总结面板是否打开、是否可继续、是否可结算

### `src/lib/`

- `api.ts`
  - 前端统一 API 调用封装
- `labels.ts`
  - 时间、情绪、回复来源、结局倾向等展示标签转换

### `src/types.ts`

前端类型中心，定义：

- `AgentProfile`
- `SessionRecord`
- `ConversationMessage`
- `RelationshipState`
- `MemorySummary`
- `EmotionState`
- `PlotState`
- `PlotArcState`
- `SceneState`
- `PresenceState`
- `AnalyticsOverview`

### `src/styles.css`

前端全局样式入口，负责：

- 引入 Tailwind CSS
- 定义基础视觉 token
- 处理全局背景、滚动条、图片和动效降级

---

## 角色视觉资源

### `static/characters/`

当前每个角色都已经补上了本地原创角色图像，目录结构如下：

```text
static/characters/
├─ healing/
│  ├─ portrait.svg
│  └─ cover.svg
├─ lively/
│  ├─ portrait.svg
│  └─ cover.svg
├─ cool/
│  ├─ portrait.svg
│  └─ cover.svg
├─ artsy/
│  ├─ portrait.svg
│  └─ cover.svg
└─ sunny/
   ├─ portrait.svg
   └─ cover.svg
```

当前角色视觉方向：

- `healing`：图书馆窗边、暖杏粉、温柔治愈
- `lively`：社团灯牌、夜市热闹、明亮橙金
- `cool`：夜色楼道、冷蓝灰、克制慢热
- `artsy`：黄昏桥边、雾紫奶灰、文艺镜头
- `sunny`：操场清风、清透雾蓝、行动派陪伴

这些图片在构建后会被复制到 `dist/characters/...` 下，由 Java 静态服务直接提供。

---

## 后端结构

### `AppConfig.java`

---

## 2026-04-22 本轮新增记录

### 后端人性化闭环

- `java-server/src/main/java/com/campuspulse/Domain.java`
  - 新增运行时类型：
    - `IntentState`
    - `ResponsePlan`
    - `UncertaintyState`
    - `InitiativeDecision`
    - `MemoryIntentBinding`
    - `SearchGroundingSummary`
    - `RealityEnvelope`
    - `RelationalTensionState`
    - `PlotGateDecision`
    - `SceneConsistencyAudit`
    - `RealityAudit`
    - `HumanizationAudit`
  - `SessionRecord` 新增：
    - `tensionState`
    - `lastIntentState`
    - `lastResponsePlan`
    - `lastHumanizationAudit`
    - `lastRealityAudit`
    - `lastPlotGateDecision`
  - `LlmRequest` 新增：
    - `intentState`
    - `responsePlan`
    - `uncertaintyState`
    - `initiativeDecision`
    - `memoryIntentBindings`
    - `realityEnvelope`
    - `tensionState`
    - `plotGateDecision`

- `java-server/src/main/java/com/campuspulse/Services.java`
  - `ChatOrchestrator` 已接入：
    - `IntentInferenceService`
    - `ResponsePlanningService`
    - `InitiativePolicyService`
    - `BoundaryResponseService`
    - `PlotGateService`
    - `RealityGuardService`
    - `HumanizationEvaluationService`
  - `sendMessage` 和 `updatePresence` 现在都会产出并落库：
    - 意图判断
    - 回复计划
    - 张力状态
    - 真实性审计
    - 人性化审计
    - 剧情门控结果
  - 新增返回字段：
    - `intent_state`
    - `response_plan`
    - `humanization_audit`
    - `reality_audit`
    - `plot_gate_reason`
    - `tension_state`

### 真实性与场景修复

- `java-server/src/main/java/com/campuspulse/RuntimeNarrativeServices.java`
  - 重写并稳定化：
    - `IntentInferenceService`
    - `ResponsePlanningService`
    - `InitiativePolicyService`
    - `BoundaryResponseService`
    - `PlotGateService`
    - `RealityGuardService`
    - `HumanizationEvaluationService`
    - `SocialMemoryService`
    - `EnhancedSocialMemoryService`
    - `PlotDirectorService`
    - `SceneDirectorService`
    - `SearchDecisionService`
    - `EnhancedPlotDirectorService`
    - `EnhancedPresenceHeartbeatService`
    - `RealityContextService`
  - 当前已明确修复的运行时问题：
    - 歌词/台词类问题默认不允许乱编
    - 下午场景下会修正“日落”类错位表达
    - 晴天场景下会修正“下雨/带伞”类错位表达
    - `face_to_face` / `mixed_transition` 场景会剔除“打字/发消息/看到你回复”这类线上措辞
    - 关键剧情新增 `PlotGateDecision`，会按场景、关系底线和间隔做门控
    - 连续冒犯会提升 `tensionState.guarded`，角色会明确表达不舒服并降主动
    - 记忆使用新增低价值过滤，避免把“你好啊”这类寒暄误当成高相关回调

### LLM 输出层

- `java-server/src/main/java/com/campuspulse/ExpressiveLlmClient.java`
  - 新增 `buildPolicySpeech(...)`
  - 当前主回复会优先处理：
    - 元对话纠偏
    - 歌词/台词拒绝乱编
    - 被冒犯后的边界回应
    - 初识阶段的慢速升温

### 前端调试字段

- `src/types.ts`
  - 新增：
    - `TensionState`
    - `IntentState`
    - `ResponsePlan`
    - `HumanizationAudit`
    - `RealityAudit`
    - `PlotGateDecision`

- `src/App.vue`
- `src/components/InsightDrawer.vue`
  - inspector 视图已接入：
    - 本轮判断
    - 闭环审计
    - 张力状态
    - 剧情门控

### 回归验证

- `java-server/src/test/java/com/campuspulse/HumanizationRegressionTest.java`
  - 新增脚本回归覆盖：
    - 歌词问题拒绝乱编
    - 面对面场景去除线上消息措辞
    - 下午 + 晴天场景修正“日落/下雨”错位
    - 连续冒犯进入 `guarded`
    - 场景不匹配时阻止关键剧情误触发

- `java-server/src/test/java/com/campuspulse/SmokeTest.java`
  - 已接入 `HumanizationRegressionTest.run(orchestrator)`

### 当前验证结论

- `powershell -ExecutionPolicy Bypass -File ./test-java.ps1`
  - 已通过
- `com.campuspulse.ExperienceReplay`
  - 已手动回放验证
  - 当前 mock 链路下，已明显减少：
    - 乱编事实
    - 场景错位
    - 线上/线下措辞混用
    - 低质量记忆回调

运行配置入口，负责读取：

- `PORT`
- `ARK_API_KEY`
- `ARK_MODEL`
- `ARK_BASE_URL`
- `ARK_TIMEOUT_MS`

本轮已扩展：

- 优先使用 `dist/` 作为静态前端目录
- 若 `dist/index.html` 不存在，则回退到 `public/`

### `CampusPulseServer.java`

Java HTTP 服务入口，负责：

- 路由分发
- API 请求处理
- 静态文件托管

本轮已扩展：

- 新增对 `.svg / .png / .jpg / .jpeg / .webp / .woff2` 的内容类型支持

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

本轮与前端视觉重构直接相关的新增点：

- `AgentProfile.portraitAsset`
- `AgentProfile.coverAsset`
- `AgentProfile.styleTags`
- `AgentProfile.moodPalette`
- `AgentVisualProfile`
  - 根据角色 `id` 自动补全视觉资源地址与风格标签

### `Services.java`

主编排文件，当前仍承载：

- `AgentConfigService`
- `AnalyticsService`
- `MemoryService`
- `EventEngine`
- `ChatOrchestrator`

本轮与前端重构直接相关的接口变化：

- `/api/agents` 现在额外返回：
  - `portraitAsset`
  - `coverAsset`
  - `styleTags`
  - `moodPalette`
- `/api/session/state` 中的 `agent` 现在额外返回：
  - `bio`
  - `likes`
  - `portraitAsset`
  - `coverAsset`
  - `styleTags`
  - `moodPalette`

---

## 当前运行链路

### 前端链路

1. `npm start`
2. 执行 `npm run build:web`
3. Vite 将前端构建到 `dist/`
4. Java 服务启动并优先托管 `dist/`
5. 浏览器访问 `http://localhost:3000`

### 聊天链路

1. 前端初始化访客：`POST /api/visitor/init`
2. 拉取角色列表：`GET /api/agents`
3. 开始或恢复会话：`POST /api/session/start` / `GET /api/session/state`
4. 用户发送消息：`POST /api/chat/send`
5. Presence 心跳：`POST /api/session/presence`
6. 关键剧情选项：`POST /api/event/choose`
7. 每 10 拍阶段总结：
   - `POST /api/session/checkpoint`
   - `POST /api/session/settle`

---

## 本轮新增 / 修改记录

### 前端重构

- 新建 Vue 前端工程：
  - `index.html`
  - `src/main.ts`
  - `src/App.vue`
  - `src/components/*`
  - `src/stores/*`
  - `src/lib/*`
  - `src/types.ts`
- 新增 Vite / TypeScript 配置：
  - `vite.config.ts`
  - `tsconfig.json`
- 接入：
  - `Vue 3`
  - `Pinia`
  - `Tailwind CSS`
  - `Motion for Vue`
- 页面布局改为：
  - Hero 主视觉
  - 角色横向轨道
  - 聊天主舞台
  - 沉浸模式抽屉
  - 阶段总结底部弹层

### 角色视觉

- 为 5 个角色补充本地原创 SVG 角色图
- 后端角色接口补充视觉资源字段

### 运行方式

- `package.json` 新增：
  - `build:web`
  - `dev:web`
  - `check:web`
- `npm start` / `npm test` 现在会先构建前端

### 文档

- `PROJECT_STRUCTURE.md` 已重写为干净可维护版
- 后续每次结构变化、入口调整、关键接口变化，都继续写入本文件

### 本轮界面微调补充

- `HeroSection.vue`
  - 主攻略对象大图缩小
  - Hero 改为更明显的自适应双栏比例
- `ChatStage.vue`
  - 聊天主舞台改为桌面端固定宽度
  - 上方上下文提示卡放宽折行条件
- `MessageStack.vue`
  - 聊天记录区改为固定高度、内部滚动
  - 旁白从绝对居中改为更贴近 AI 消息的对齐方式
  - 聊天气泡与动作条宽度放宽，减少过早换行
- `AgentRail.vue`
  - 角色卡底部按钮统一用 `mt-auto` 顶到同一基线
  - 修复顾遥卡片上“选她开场”位置不齐的问题

---

## 后续建议关注点

下一轮如果继续优化，优先建议：

1. 把旧 `public/` 前端彻底下线或移入 `legacy/`
2. 为角色图像补充更精细的 `thumb` 与移动端裁切版本
3. 增加前端反馈表单、空状态动画、请求骨架屏
4. 继续细化聊天页的节奏动效与输入区交互
5. 为 `InsightDrawer` 增加更完整的记忆、剧情、analytics 细节展示
---

## 2026-04-23 角色背景具体化

- `java-server/src/main/java/com/campuspulse/Domain.java`
  - 新增 `AgentBackstory` 结构化角色背景。
  - 每个角色补充：年龄、年级、专业、出生地、当前城市、校园常去地点、爱好、生活节奏、边界细节、情绪模式、隐藏经历、剧情钩子。
  - `AgentProfile` 新增 `backstory` 字段，并通过 `AgentBackstory.forAgent(agentId)` 自动挂载 5 个角色默认背景。
- `java-server/src/main/java/com/campuspulse/Services.java`
  - `GET /api/agents` 公开返回 `backstory`。
  - `GET /api/session/state` 的 `agent` 节点同步返回 `backstory`。
  - 新增 `AgentPresentation.backstoryMap(...)`，统一序列化角色背景。
- `java-server/src/main/java/com/campuspulse/ExpressiveLlmClient.java`
  - 主聊天模型提示词新增“角色具体背景”上下文。
  - 增加约束：背景只作为说话习惯、兴趣、边界和情绪反应的底色，不要像简历一样主动报设定；隐藏经历只在关系推进、用户主动问起或剧情自然触发时露出。
- `src/types.ts`
  - 新增 `AgentBackstory` 前端类型。
  - `AgentProfile` 新增可选 `backstory` 字段。
- `src/components/AgentRail.vue`
  - 角色卡增加年级、专业、出生地的轻量展示。
- `java-server/src/test/java/com/campuspulse/SmokeTest.java`
  - 新增回归测试：验证 5 个角色都暴露结构化背景，且会话状态中的角色信息也包含 `backstory`。
---

## 2026-04-23 剧情导演智能体与场景优先级修复

- `java-server/src/main/java/com/campuspulse/RuntimeNarrativeServices.java`
  - 新增 `PlotDirectorAgentService`，作为后台剧情导演智能体。
  - 新增 `PlotDirectorAgentDecision`，结构化输出：`action`、`reason`、`sceneCue`、`shouldAdvance`。
  - 剧情推进改为先由剧情导演判断：`hold_plot` / `transition_only` / `advance_plot` / `heartbeat_nudge`。
  - 明确场景转移优先级：用户提出“去操场 / 回宿舍 / 去食堂 / 去图书馆”等时，先执行 `transition_only`，不抢跑剧情。
  - 剧情推进节奏收紧：默认最早强制推进窗口从 4 轮调整到 7 轮，两次剧情推进至少间隔约 4 轮；短反馈和明确转场不触发剧情推进。
  - 场景识别补充正常中文关键词，避免“去操场”无法从图书馆切换到操场。
- `java-server/src/main/java/com/campuspulse/Services.java`
  - `POST /api/chat/send` 与 presence 主动消息返回新增 `plot_director_decision`，便于调试剧情为什么推进或阻塞。
- `java-server/src/main/java/com/campuspulse/ExpressiveLlmClient.java`
  - 对“哈哈哈/嘿嘿”等短反馈增加上下文承接逻辑：如果上一轮助手在唱歌、歌词或音乐上下文中，优先承接上一句，不误判成新的“有什么好笑事情要分享”。
- `src/types.ts`
  - 聊天响应类型新增 `plot_director_decision`。
- `java-server/src/test/java/com/campuspulse/SmokeTest.java`
  - 新增回归：第二轮不应过早 `plot_push`；明确“去操场”应先转场，不作为剧情推进。
- `java-server/src/test/java/com/campuspulse/HumanizationRegressionTest.java`
  - 新增回归：上一轮唱歌后用户只回复“哈哈哈”，回复必须仍承接唱歌/歌词上下文，不能误判成新分享。

---

## 2026-04-23 剧情智能体接入 DashScope / OpenAI-Compatible 模型

- `java-server/src/main/java/com/campuspulse/AppConfig.java`
  - 新增剧情智能体专用配置：`plotLlmBaseUrl`、`plotLlmApiKey`、`plotLlmModel`、`plotLlmTimeout`。
  - 主聊天模型的 OpenAI-compatible Base URL 兼容 `OPENAI_API_BASE`、`OPENAI_BASE_URL`、`OPENAI_BASE`，避免 base/key 不匹配。
  - 支持环境变量优先级：
    - Base URL：`PLOT_LLM_BASE_URL` / `DASHSCOPE_BASE_URL` / `DASHSCOPE_BASE` / `OPENAI_API_BASE` / `OPENAI_BASE_URL` / `OPENAI_BASE`
    - API Key：`PLOT_LLM_API_KEY` / `DASHSCOPE_API_KEY` / `OPENAI_API_KEY`
    - Model：`PLOT_LLM_MODEL` / `DASHSCOPE_MODEL` / `OPENAI_MODEL`，未配置时默认 `qwen-plus`
  - 若剧情模型没有显式配置 Base URL，会默认使用 `https://dashscope.aliyuncs.com/compatible-mode/v1`，避免 DashScope Key 误打到 Ark 默认地址。
  - 保留原主聊天模型配置，剧情模型可以和主聊天模型分开。
- `java-server/src/main/java/com/campuspulse/CampusPulseServer.java`
  - 启动时把 `new PlotDirectorAgentService(config)` 注入 `ChatOrchestrator`，让正式服务使用远程剧情裁判。
- `java-server/src/main/java/com/campuspulse/Services.java`
  - `ChatOrchestrator` 新增可注入 `PlotDirectorAgentService` 的构造函数。
  - 测试环境默认仍可使用本地剧情裁判，避免回归测试依赖外部网络。
- `java-server/src/main/java/com/campuspulse/RuntimeNarrativeServices.java`
  - `PlotDirectorAgentService` 升级为“本地硬门控 + 可选远程剧情智能体”。
  - 正式服务优先通过 `scripts/plot-director-agent.mjs` 使用 Node `openai` SDK 调用 DashScope OpenAI-compatible 接口。
  - 若 Node 桥接脚本不存在，则回退到 Java 内置 HTTP client；远程失败时再回退本地剧情裁判。
  - 明确转场、短反应、剧情间隔过短、非用户/长聊窗口等情况仍由本地规则直接拦截，不交给模型乱推剧情。
  - 远程调用使用 OpenAI-compatible `/chat/completions`，要求模型只返回 JSON：`action`、`reason`、`sceneCue`、`shouldAdvance`。
  - 远程失败、返回非 JSON、动作非法或被安全规则阻塞时，自动回退本地剧情裁判。
- `scripts/plot-director-agent.mjs`
  - 新增剧情智能体 Node SDK 桥接脚本，使用 `openai` 包。
  - 读取 `PLOT_LLM_*` / `DASHSCOPE_*` / `OPENAI_API_BASE` / `OPENAI_API_KEY` / `OPENAI_MODEL`。
  - 从 stdin 接收 Java 传入的 chat completion payload，只向 stdout 输出模型返回的剧情 JSON 内容。

---

## 2026-04-23 三智能体协作闭环优化

- `java-server/src/main/java/com/campuspulse/Domain.java`
  - 新增 `TurnContext`，作为主聊天智能体、好感评分智能体、剧情导演智能体共享的本轮上下文。
  - `SessionRecord` 新增 `lastTurnContext`，方便 inspector 和回归测试观察本轮协作结果。
- `java-server/src/main/java/com/campuspulse/Services.java`
  - `POST /api/chat/send` 返回新增 `turn_context`。
  - `GET /api/session/state` 返回新增 `lastTurnContext`。
  - `TurnContext` 汇总：用户意图、清晰度、用户情绪、好感 delta、行为标签、风险标签、场景位置、互动模式、剧情间隔、剧情信号、剧情导演动作与置信度。
- `java-server/src/main/java/com/campuspulse/RuntimeNarrativeServices.java`
  - 剧情导演智能体输入新增 `turnContext`，让剧情模型能看到评分智能体对本轮的判断，而不是只看用户原话。
  - 远程剧情 JSON schema 扩展：`confidence`、`riskIfAdvance`、`requiredUserSignal`。
  - 剧情推进新增低置信度保护：远程模型若给出 `advance_plot` 但置信度低于 60，会自动降级为 `hold_plot`。
  - `plot_director_decision` 调试字符串追加 `confidence / risk / need`，用于解释为什么推进或阻塞。
- `java-server/src/test/java/com/campuspulse/ClosedLoopAgentTest.java`
  - 新增并接入闭环回归：验证聊天、评分、剧情共享上下文可用。
  - 覆盖：早期不抢剧情、明确转场优先、长上下文后剧情导演有明确决策、会话状态暴露三智能体协作字段。
- `java-server/src/test/java/com/campuspulse/SmokeTest.java`
  - 将 `ClosedLoopAgentTest.run(...)` 纳入 Java smoke test 主入口。

---

## 2026-04-23 三智能体协作二次优化：剧情解释与宏评分

- `java-server/src/main/java/com/campuspulse/Services.java`
  - 在 `sendMessage` 主链路中确认顺序为：输入理解 -> 聊天微评分 -> 剧情导演判断 -> 剧情宏评分 -> 主聊天生成。
  - 新增 `applyPlotMacroScore(...)`：当剧情导演真正推进一拍时，在普通聊天微评分之外追加中等权重的关系结算。
  - 宏评分会更新 `closeness / trust / resonance / affectionScore / relationshipStage / relationshipFeedback`，并同步写回 `TurnContext`。
  - 若本轮处于 `guarded` 张力状态或存在风险标签，则不会因为剧情推进额外加分，避免冲突场景被误当成甜蜜推进。
- `src/components/PlotMiniPanel.vue`
  - 重写剧情面板展示逻辑，优先显示 `plotDirectorAction`、`plotDirectorConfidence`、`plotWhyNow`、`plotRiskIfAdvance`、`requiredUserSignal`。
  - 修复剧情面板中文乱码。
  - 心跳状态改为次级说明，不再覆盖剧情推进解释，避免误导为“剧情因为心跳没有触发”。
- `java-server/src/test/java/com/campuspulse/ClosedLoopAgentTest.java`
  - 增加剧情推进后的宏评分回归：长上下文足够时允许推进剧情，并验证 `affection_delta.total` 明显高于普通微评分。
  - 验证 `behavior_tags` 中会出现 `plot_macro_score`，证明评分智能体和剧情智能体的结果已经联动。
- 验证命令：
  - `powershell -ExecutionPolicy Bypass -File .\test-java.ps1`
  - `npm run check:web`
  - `npm run build:web`

---

## 2026-04-23 前端中文观察模式与城市上下文修复

- `src/App.vue`
  - 城市输入改为本地草稿态 `cityDraft`，支持回车/按钮保存，并显示“保存中 / 保存城市”。
  - 保存城市后继续通过 `/api/visitor/context` 同步时区与天气城市。
  - 观察抽屉新增传入 `plotState / plotArcState / sceneState / timeContext / weatherContext / emotionState / turnContext`，便于查看完整运行状态。
- `src/stores/session.ts`
  - 新增 `VisitorContextUpdateResult` 类型消费。
  - 修复城市保存后只更新 `visitorContext`、不刷新页面天气的问题：现在会立即写回 `timeContext` 和 `weatherContext`。
- `src/components/ChatStage.vue`
  - 将主要英文展示改为中文。
  - 非沉浸模式新增更多检查信息：本轮智能体闭环、现实上下文、情绪与张力、结局倾向等。
- `src/components/InsightDrawer.vue`
  - 重写观察抽屉，统一中文展示。
  - 新增关系、剧情门控、场景/时间/天气、记忆、本轮决策闭环、情绪张力、真实性审计、试玩数据 8 个信息组。
- `src/components/PlotMiniPanel.vue`
  - 统一改为中文展示：剧情与氛围、拍数、阶段、状态、剧情动作、置信度、风险、缺失用户信号、心跳状态。
- `src/types.ts`
  - 新增 `VisitorContextUpdateResult`。
- `java-server/src/test/java/com/campuspulse/SmokeTest.java`
  - 扩展城市上下文回归：保存城市后必须返回刷新后的 `timeContext` 和 `weatherContext.city`。
- 验证命令：
  - `powershell -ExecutionPolicy Bypass -File .\test-java.ps1`
  - `npm run check:web`
  - `npm run build:web`

---

## 2026-04-23 上下文智能层

- `java-server/src/main/java/com/campuspulse/Domain.java`
  - 新增 `DialogueContinuityState`，用于保存短期情景工作记忆。
  - 字段包括：`currentObjective`、`pendingUserOffer`、`acceptedPlan`、`lastAssistantQuestion`、`userAnsweredLastQuestion`、`sceneTransitionNeeded`、`nextBestMove`、`mustNotContradict`、`confidence`。
  - `SessionRecord` 新增 `dialogueContinuityState`，用于跨轮保存当前行动链。
  - `LlmRequest` 新增 `dialogueContinuityState`，主聊天模型每轮都能看到当前行动链摘要。
  - `TurnContext` 新增 `continuityObjective / continuityAcceptedPlan / continuityNextBestMove / continuityGuards`，剧情导演智能体也能读取上下文智能层结果。
- `java-server/src/main/java/com/campuspulse/RuntimeNarrativeServices.java`
  - 新增 `DialogueContinuityService`。
  - 每轮识别用户是否接受上一轮提议、是否产生共同目标、是否需要场景过渡、下一句必须承接什么、哪些事实不能违背。
  - 重点修复“用户已同意一起去买热饮，下一轮却泛化成从哪开始逛”的断链问题。
  - 剧情导演输入中的 `turnContext` 增加上下文智能层字段，避免剧情推进忽略当前行动链。
- `java-server/src/main/java/com/campuspulse/Services.java`
  - `POST /api/chat/send` 中在意图识别后更新 `DialogueContinuityState`。
  - 主回复、presence 主动消息、会话状态与聊天响应均暴露上下文智能层字段。
  - 新增 `dialogueContinuityMap(...)` 用于 API 序列化。
- `java-server/src/main/java/com/campuspulse/ExpressiveLlmClient.java`
  - 系统提示词新增“上下文智能层”段落。
  - 明确要求优先遵守当前共同目标、已确认计划、下一句必须承接和禁止违背事实。
  - Mock 回复也会在“已确认一起去买热饮”时优先生成热饮相关承接和转场。
- `src/types.ts`
  - 新增 `DialogueContinuityState` 类型。
  - `SessionRecord` 与 presence 响应新增上下文智能层字段。
- `src/components/ChatStage.vue`
  - 观察模式新增“上下文智能层”卡片，展示共同目标、已确认计划、下一步承接、是否转场和置信度。
- `src/components/InsightDrawer.vue`
  - 观察抽屉新增“上下文智能层”分组，展示完整行动链与禁止违背事实。
- `java-server/src/test/java/com/campuspulse/ClosedLoopAgentTest.java`
  - 新增回归：用户提出给角色买热饮、随后接受一起去时，系统必须锁定“一起去买热饮”，并禁止回复泛化成“从哪开始逛”。
- 验证命令：
  - `powershell -ExecutionPolicy Bypass -File .\test-java.ps1`
  - `npm run check:web`
  - `npm run build:web`

---

## 2026-04-23 项目体检与无关文件清理记录

- 本轮检查命令：
  - `git status --short`
  - `git status --short --ignored`
  - `rg --files`
  - `rg -n "TODO|FIXME|console\.log|debugger|<<<<<<<|>>>>>>>|\uFFFD" src java-server server public scripts PROJECT_STRUCTURE.md`
  - `npm run check:web`
  - `powershell -ExecutionPolicy Bypass -File .\test-java.ps1`
  - `npm run build:web`
- 检查结果：
  - 前端 TypeScript/Vue 类型检查通过。
  - Java smoke 与闭环回归测试通过。
  - 前端生产构建通过。
  - 未发现 Git 冲突标记、`debugger`、明显乱码替换符。
  - 扫描到的 `console.log` 只存在于服务启动日志和剧情智能体 Node 桥接脚本标准输出，属于正常用途。
- 可安全清理项：
  - `build/`：Java 编译、测试、运行时生成目录，可由 `test-java.ps1` 或启动脚本重新生成。
  - `dist/`：Vite 前端构建产物，可由 `npm run build:web` 重新生成。
- 不建议删除项：
  - `node_modules/`：依赖目录，虽然可重装，但删除后需要重新 `npm install`。
  - `data/runtime/state.bin` 与 `data/runtime/state.json`：本地会话与记忆状态，删除会丢失当前试玩数据。
  - `scripts/plot-director-agent.mjs`：剧情智能体 OpenAI-compatible Node 桥接脚本，是当前三智能体协作链路的一部分。
- 本轮清理状态：
  - 已确认 `build/` 与 `dist/` 位于项目根目录且在 `.gitignore` 中。
  - 删除命令被用户侧拒绝执行，因此本轮没有实际删除文件。
