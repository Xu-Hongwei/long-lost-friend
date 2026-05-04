# Memory Pane Replay

这个目录保存会话记忆窗格与 Prompt Stack 的固定回放脚本。

它和 `local-rules/` 不一样：

- `local-rules/` 验证本地结构化规则的输入输出契约。
- `memory-pane-replay/` 验证完整 mock 会话链路里，`memory.session`、`world.info`、`turn.understanding`、场景状态、心跳和主回复是否协同稳定。

## 当前覆盖

- 用户接受上一轮计划后，不应重新问无关问题。
- 已经到达图书馆/食堂后，不应重复启动同一场景移动。
- 地点偏好问题应保持 `topic_only`，不能误改真实场景。
- 场景转移后，正文不应复述完整场景过场。
- 静默心跳应同时参考用户上一句和角色上一句，并继续保留会话工作记忆。
- WorldInfo 命中后应作为候选背景进入 Prompt Stack 和记忆窗格，但不能直接替代结构化裁决。

## 运行方式

最简单方式：

```powershell
.\test-java.ps1
```

`SmokeTest` 会自动调用 `MemoryPaneReplayTest`。报告会写入：

```text
build/memory-pane-replay/report.json
```

也可以单独运行：

```powershell
.\test-java.ps1
$java = Get-ChildItem "C:\Program Files\Java" -Directory |
  Sort-Object Name -Descending |
  ForEach-Object { Join-Path $_.FullName "bin\java.exe" } |
  Where-Object { Test-Path $_ } |
  Select-Object -First 1
& $java -cp build\test-classes com.campuspulse.MemoryPaneReplayTest
```

## 判定方式

- `pass`：硬期望全部满足。
- `fail`：关键状态或主回复出现回归，需要优先排查。

这些用例是“固定回放哨兵”，不是独立真实评测集。新增真实问题时，优先把复盘描述放进 `bug-replay/`，再把可以自动检查的部分抽成这里的 JSONL 用例。
