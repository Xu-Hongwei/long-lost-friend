# Bug Replay: 沈砚操场长聊心跳与错位修正

来源：`campus-pulse-沈砚-session_223ef711f1f3495f-2026-04-26T10-43-20-088Z.json`

## 对话切片

```text
U: 今天还好，不是太冷，对了，你每天都呆这么久吗？
A(user_turn): 也不是每天都这样。偶尔会多待一会儿，写代码或者整理一下东西。你呢，经常晚上出来走走？
A(long_chat_heartbeat): 看情况，（偏头看了眼你）有时候事情多就会呆久一点。你呢？总在这吹晚风，不怕着凉？
U: 这是什么情况？
```

## 原始问题

- 长聊心跳插入同一轮后，重复回答上一轮已经回答过的问题，并再次追问。
- 用户用“这是什么情况？”质疑异常回复时，旧判断容易把它当普通问题或天气/场景话题，没有稳定进入 `meta_repair`。
- 错位质疑不应触发剧情推进，也不应继续泛泛陪伴。

## 当前期望

- 心跳应复盘角色自己上一句，不能重新回答已经回答过的问题。
- “这是什么情况？”在上一条 assistant 存在且不含天气/风景等明确实体问题时，应识别为短句错位质疑。
- 该轮应 `hold_plot`，回复先轻修正，再回到当前面对面场景和共同目标。

## 当前回放结果

用 `ExportReplayTool` 回放后：

- 第 8 轮长聊心跳改为轻承认“刚刚问得有点急”，不再重复“每天待多久”。
- 第 9 轮“这是什么情况？”进入修正口径，`plot=hold_plot`。
- 报告输出位置：`build/debug-replay/session_223ef711f1f3495f-replay-report.json`

## 回放命令

```powershell
.\test-java.ps1
$java = Get-ChildItem "C:\Program Files\Java" -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "bin\java.exe" } | Where-Object { Test-Path $_ } | Select-Object -First 1
& $java -cp build\test-classes com.campuspulse.ExportReplayTool "C:\Users\Administrator\Downloads\campus-pulse-沈砚-session_223ef711f1f3495f-2026-04-26T10-43-20-088Z.json"
```
