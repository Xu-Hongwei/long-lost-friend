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

推荐直接运行：

```bash
npm start
```

或使用 PowerShell：

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
- `ARK_API_KEY`：推荐使用，配置后优先走火山方舟
- `ARK_MODEL`：推荐使用，默认 `ep-20260418203515-nw4jb`
- `ARK_BASE_URL`：推荐使用，默认 `https://ark.cn-beijing.volces.com/api/v3`
- `ARK_TIMEOUT_MS`：可选，请求超时，默认 `12000`
- `OPENAI_API_KEY` / `OPENAI_MODEL` / `OPENAI_BASE_URL` / `OPENAI_TIMEOUT_MS`：兼容保留，只有在未设置 `ARK_*` 时才会使用

未配置远程模型时，系统会使用内置的角色化回复生成器，便于本地演示和联调。配置 `ARK_API_KEY` 后，后端会优先请求远程模型，失败时自动降级到本地 mock 回复。

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
- `run-java.ps1`：编译并启动 Java 服务
- `test-java.ps1`：编译并执行 Java smoke test
- `server/`：上一版 Node 实现，当前不作为默认启动入口
