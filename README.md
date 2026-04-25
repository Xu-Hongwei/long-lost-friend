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

推荐使用统一启动指令：

```bash
npm start
```

这条命令会先构建前端，再编译并启动 Java 服务。

如果你已经提前构建好了前端，只想单独启动后端，也可以用：

```powershell
.\run-java.ps1
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
- `DASHSCOPE_API_KEY`：主回复推荐使用，配置后主回复会优先走阿里百炼
- `DASHSCOPE_MODEL`：主回复模型，可选；未设置时默认 `qwen-plus-character`
- `DASHSCOPE_BASE_URL` / `DASHSCOPE_BASE`：主回复兼容地址，推荐 `https://dashscope.aliyuncs.com/compatible-mode/v1`
- `DASHSCOPE_TIMEOUT_MS`：主回复或百炼兼容链路超时，默认 `12000`
- `ARK_API_KEY` / `ARK_MODEL` / `ARK_BASE_URL` / `ARK_TIMEOUT_MS`：兼容保留；当主回复未配置百炼时会继续用于主回复，同时默认也会被 `PlotDirector` / `QuickJudge` 这类 `plotLlm` 链路复用
- `OPENAI_API_KEY` / `OPENAI_MODEL` / `OPENAI_BASE_URL` / `OPENAI_TIMEOUT_MS`：兼容保留；仅在未配置 `DASHSCOPE_*` 和 `ARK_*` 时用于主回复回退
- `QUICK_JUDGE_FORCE_ALL`：诊断用开关，设为 `true` 后，每轮非空消息都尝试触发 `QuickJudgeService`；日常运行不建议开启
- `QUICK_JUDGE_WAIT_MS`：可选，主程序准备发出主回复请求前，最多额外等待 quick judge 结果的毫秒数，默认 `120`，最小 `60`，最大 `1000`；日常运行建议保持默认，`1000` 更适合压测或排查

未配置远程模型时，系统会使用内置的角色化回复生成器，便于本地演示和联调。配置 `DASHSCOPE_API_KEY` 后，主回复会优先请求百炼兼容模型；若未配置百炼，则继续按 `ARK_*` / `OPENAI_*` 回退。请求失败时会自动降级到本地 mock 回复。

PowerShell 合并启动示例：

```powershell
$env:DASHSCOPE_API_KEY="你的百炼密钥"
$env:DASHSCOPE_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:DASHSCOPE_MODEL="qwen-plus-character"
$env:ARK_API_KEY="你的密钥"
$env:ARK_MODEL="ep-20260418203515-nw4jb"
$env:ARK_BASE_URL="https://ark.cn-beijing.volces.com/api/v3"
npm start
```

如果你想恢复默认 quick judge 行为，或确认没有开启诊断配置：

```powershell
Remove-Item Env:QUICK_JUDGE_FORCE_ALL -ErrorAction SilentlyContinue
Remove-Item Env:QUICK_JUDGE_WAIT_MS -ErrorAction SilentlyContinue
npm start
```

QuickJudge 压测或排查示例：

```powershell
$env:QUICK_JUDGE_FORCE_ALL="true"
$env:QUICK_JUDGE_WAIT_MS="1000"
npm start
```

注意：上面这组配置会让 quick judge 尽量每轮启动，并允许主回复额外等到 1 秒。它适合观察命中率、超时和融合状态，不适合作为日常聊天默认配置。

## 智能体协作流程

当前推荐的协作方向是：`PlotDirector` 继续负责当前轮剧情结构，`QuickJudge` 作为机会型并行助手，`ExpressiveLlmClient` 最后收口生成自然回复。

```text
用户输入
  -> IntentInference 本地意图初判
  -> DialogueContinuity 初判
  -> LocalCorrection 第一次收敛
  -> 判断是否模糊/高价值轮
  -> 条件异步启动 QuickJudge

  -> PlotDirector 当前轮决策
  -> SceneDirector 场景更新
  -> LocalCorrection 第二次收敛

  -> 如果 QuickJudge 已返回且置信度够高
       融合 QuickJudge
       LocalCorrection 第三次收敛
     否则
       不阻塞或只极短等待
       晚到结果进入下一轮修正槽

  -> ExpressiveLlmClient 生成主回复
  -> RealityGuard / Humanization / Scene 去重清洗
  -> 返回前端
```

如果 QuickJudge 晚到但置信度足够高，结果会进入下一轮修正槽。下一轮主回复可以自然承认轻微理解偏差，例如“刚才我好像理解偏了一点”，但不会暴露 `QuickJudge`、系统提示词或内部模块名。

## 目录

- `java-server/`：Java 后端源码
- `public/`：前端单页试玩界面
- `run-java.ps1`：编译并启动 Java 服务
- `test-java.ps1`：编译并执行 Java smoke test
- `server/`：上一版 Node 实现，当前不作为默认启动入口
