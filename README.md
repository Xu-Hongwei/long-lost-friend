# 大学生攻略游戏网站 MVP

当前主运行时已经切到 Java 版本，前端保持静态单页，后端提供：

- 5 个固定人设 agent
- 匿名访客与 7 天续玩
- 短期上下文 + 长期结构化记忆
- 好感度与关系阶段状态机
- 剧情事件触发
- 安全拦截与兜底回复
- 试点数据概览与反馈收集
- 可切换的大模型接入层

## 启动

推荐按模式启动，避免本地降级和远程模型混在一起。

### 远程模型模式

读取 `ARK_*` / `OPENAI_*` / `PLOT_LLM_*` / `DASHSCOPE_*` 环境变量，主聊天、剧情/语义/评分智能体会优先走远程模型。

```bash
npm start
```

等价命令：

```powershell
.\runtime\remote\run-remote.ps1
```

如果在 CMD 里运行：

```cmd
runtime\remote\start-remote.cmd
```

### 本地降级模式

本地模式会在当前启动进程里清掉大模型环境变量，只使用内置角色化兜底逻辑，适合离线调前端、调接口结构、排查非模型问题。

```bash
npm run start:local
```

等价命令：

```powershell
.\runtime\local\run-local.ps1
```

如果在 CMD 里运行：

```cmd
runtime\local\start-local.cmd
```

### 公共 Java 启动器

`run-java.ps1` 是公共底层启动器，一般不用直接调用；如果需要可以指定模式：

```powershell
.\run-java.ps1 -Mode local
.\run-java.ps1 -Mode remote
.\run-java.ps1 -Mode auto
```

启动后打开：

- [http://localhost:3000](http://localhost:3000)

## 测试

```bash
npm test
```

这会编译 Java 源码并执行基础 smoke test。

## 环境变量

- `PORT`：服务端口，默认 `3000`
- `ARK_API_KEY`：推荐使用，配置后优先走火山方舟
- `ARK_MODEL`：推荐使用，默认 `ep-20260418203515-nw4jb`
- `ARK_BASE_URL`：推荐使用，默认 `https://ark.cn-beijing.volces.com/api/v3`
- `ARK_TIMEOUT_MS`：可选，请求超时，默认 `12000`
- `OPENAI_API_KEY` / `OPENAI_MODEL` / `OPENAI_API_BASE` / `OPENAI_BASE_URL` / `OPENAI_TIMEOUT_MS`：兼容保留，只有在未设置 `ARK_*` 时才会使用
- `PLOT_LLM_API_KEY` / `PLOT_LLM_MODEL` / `PLOT_LLM_BASE_URL`：剧情、语义运行时与好感评分智能体专用配置
- `DASHSCOPE_API_KEY` / `DASHSCOPE_MODEL` / `DASHSCOPE_BASE_URL`：剧情、语义运行时与好感评分智能体的百炼兼容配置

远程模式缺少必要 key 会直接报错，避免你以为正在远程运行但其实一直降级。本地模式则会主动清除本进程的大模型环境变量。

PowerShell 示例：

```powershell
$env:ARK_API_KEY="你的密钥"
$env:ARK_MODEL="ep-20260418203515-nw4jb"
$env:ARK_BASE_URL="https://ark.cn-beijing.volces.com/api/v3"
npm start
```

## 目录

- `java-server/`：Java 后端源码
- `public/`：前端单页试玩界面
- `run-java.ps1`：公共 Java 编译与启动器，支持 `-Mode local/remote/auto`
- `runtime/local/run-local.ps1` / `runtime/local/start-local.cmd`：本地降级模式启动入口
- `runtime/remote/run-remote.ps1` / `runtime/remote/start-remote.cmd`：远程模型模式启动入口
- `test-java.ps1`：编译并执行 Java smoke test
- `server/`：上一版 Node 实现，当前不作为默认启动入口

## 本地/远程运行结构

Java 正式启动时会通过 `AgentRuntimeFactory` 按模式选择实现：

- 本地聊天：`LocalExpressiveLlmClient`
- 远程聊天：`RemoteExpressiveLlmClient`
- 本地剧情：`LocalPlotDirectorAgentService`
- 远程剧情：`RemotePlotDirectorAgentService`
- 本地语义运行时：`LocalSemanticRuntimeAgentService`
- 远程语义运行时：`RemoteSemanticRuntimeAgentService`
- 本地好感评分：`LocalAffectionJudgeService`
- 远程好感评分：`RemoteAffectionJudgeService`

`ExpressiveLlmClient`、`PlotDirectorAgentService`、`SemanticRuntimeAgentService`、`AffectionJudgeService` 保留为共享基类或 auto 兼容实现，正式 `local/remote` 启动入口会使用拆分后的类。

远程好感评分不是直接让模型决定最终关系阶段：百炼评分智能体负责判断本轮互动质量、置信度、影响等级、修复信号和冒犯等级；后端会再做分值上限、低置信混合、早期关系降速和阶段门槛保护。

## 运行入口文件夹

- `runtime/local/run-local.ps1`：本地降级模式 PowerShell 启动入口。
- `runtime/local/start-local.cmd`：本地降级模式 CMD 启动入口。
- `runtime/remote/run-remote.ps1`：远程模型模式 PowerShell 启动入口。
- `runtime/remote/start-remote.cmd`：远程模型模式 CMD 启动入口。
- `run-java.ps1`：local / remote / auto 共用的底层 Java 编译与启动器，保留在项目根目录。
