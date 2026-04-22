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

---

## 后续建议关注点

下一轮如果继续优化，优先建议：

1. 把旧 `public/` 前端彻底下线或移入 `legacy/`
2. 为角色图像补充更精细的 `thumb` 与移动端裁切版本
3. 增加前端反馈表单、空状态动画、请求骨架屏
4. 继续细化聊天页的节奏动效与输入区交互
5. 为 `InsightDrawer` 增加更完整的记忆、剧情、analytics 细节展示
