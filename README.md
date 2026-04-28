# 大学生攻略游戏网站 MVP

当前主运行时已经切到 Java 版本，前端保持静态单页，后端提供：

- 5 个固定人设 agent
- 匿名访客与 7 天续玩
- 短期上下文 + 长期结构化记忆
- 好感度与关系阶段状态机
- 剧情事件触发
- 本轮信号与剧情蓄力调试
- 安全拦截与兜底回复
- 试点数据概览
- 会话调试数据导出
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
- `QUICK_JUDGE_FORCE_ALL`：后端诊断用开关，设为 `true` 后，每轮非空消息都尝试触发 `QuickJudgeService`；日常建议优先使用前端 Quick Judge 面板控制
- `QUICK_JUDGE_WAIT_MS`：后端兜底等待窗口，默认 `120` 毫秒，最小 `60`，最大 `5000`；前端 Quick Judge 面板会按秒传入本轮等待时间并覆盖该兜底值

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

注意：上面这组配置会让 quick judge 尽量每轮启动，并允许主回复额外等到 1 秒。它适合观察命中率、超时和融合状态；正常使用时可以直接在非沉浸模式的 Quick Judge 面板里选择 `高价值轮`、`每轮` 或 `关闭`，并用秒为单位调整最多等待时间。

## 智能体协作流程

当前推荐的协作方向是：`PlotDirector` 继续负责当前轮剧情结构，`QuickJudge` 作为机会型并行助手，`ExpressiveLlmClient` 最后收口生成自然回复。

```text
用户输入
  -> IntentInference 本地意图初判
  -> DialogueContinuity 初判
  -> LocalCorrection 第一次收敛
  -> 判断 QuickJudge 触发等级
  -> 条件异步启动 QuickJudge

  -> PlotDirector 当前轮决策
  -> SceneDirector 场景更新
  -> LocalCorrection 第二次收敛

  -> 如果 QuickJudge 已返回且置信度够高
       融合 QuickJudge
       LocalCorrection 第三次收敛
     否则
       按触发等级决定是否等待；除 background 外共享前端设置的等待时间
       晚到结果进入下一轮修正槽

  -> ExpressiveLlmClient 生成主回复
  -> RealityGuard / Humanization / Scene 去重清洗
  -> 返回前端
```

如果 QuickJudge 晚到但置信度足够高，结果会进入下一轮修正槽。下一轮主回复可以自然承认轻微理解偏差，例如“刚才我好像理解偏了一点”，但不会暴露 `QuickJudge`、系统提示词或内部模块名。

QuickJudge 的 `smart` 模式不是只靠高价值轮触发，而是三层触发：

- 用户纠错/困惑/场景不一致：`urgent`，主回复前按前端设置的等待时间短等。
- 模糊意图、关系试探、情绪承接、场景推进：`opportunistic`，同样共享前端设置的等待时间。
- 每 4 个用户回合：`background`，后台巡检，不阻塞当前轮。

非沉浸模式的 Quick Judge 面板会展示本地触发分数、触发原因和抑制原因，方便判断这一轮为什么启动、为什么跳过，或为什么只进入后台巡检。

剧情调试面板里有两个容易混淆的字段：

- `本轮信号 / plotSignal`：只表示当前这一轮有没有推进剧情的上下文信号；剧情真正推进后会被消费并归零。
- `剧情蓄力 / plotPressure`：跨轮累计的剧情压力，用来避免一轮信号不足但连续几轮都在铺垫时剧情永远不动；剧情推进后同样会归零。

关系评分采用“双层协调”：

- 本地评分每轮同步执行，立即更新 `RelationshipState`、`scoreReasons`、`behaviorTags` 和 `riskFlags`。
- 本地评分会先抽取 `UserRelationalAct`，例如具体照顾、记忆承接、边界尊重、催促控制或敷衍抽离，再统一折算为分数。
- 异步 LLM 评分每 4 个用户回合后台触发一次，不阻塞当前轮回复。
- 异步结果晚到后进入 `pendingRelationshipCalibration`，下一轮本地评分完成后才小幅融合。
- 单次校准总修正控制在约 `±2`，只作为复盘校准，不直接接管本地评分。

调试导出：

- 非沉浸模式可点击“导出调试数据”，导出当前会话的消息、最新智能体信号、Quick Judge 状态、剧情蓄力和关系评分摘要。
- 后端接口为 `GET /api/session/export?session_id=...`，用于复盘“为什么这一轮推剧情/没推剧情”“为什么 Quick Judge 没采用”等问题。

本地规则测试数据：

- `testdata/local-rules/` 保存 960 条改写后的 CampusPulse 风格 JSONL 样例，覆盖场景移动、回应动作、QuickJudge 触发、剧情信号、心跳和关系评分。
- `tools/dataset-mining/` 保存数据集挖掘和样例生成工具；原始公开数据集只应下载到被忽略的 `raw-datasets/`，不要提交原始语料。
- 可以用 `python tools/dataset-mining/validate_local_rule_cases.py` 校验样例格式、数量分布和关键评分一致性。
- 可以用 `.\run-local-rules.ps1` 编译并运行 Java 本地规则 runner，把样例喂给真实本地模块，输出 `pass / warn / fail`，报告写入 `build/local-rule-report.json`。

## 目录

- `java-server/`：Java 后端源码
- `src/`：Vue 前端源码
- `static/`：Vite 公开静态资源，当前存放角色图像
- `testdata/local-rules/`：本地规则测试样例库
- `tools/dataset-mining/`：公开数据集启发的样例生成工具
- `run-java.ps1`：编译并启动 Java 服务
- `run-local-rules.ps1`：编译并运行本地规则 runner
- `test-java.ps1`：编译并执行 Java smoke test
- `SCORING_RULES.md`：关系评分、QuickJudge 触发分、剧情本轮信号/蓄力和剧情推进协作规则
