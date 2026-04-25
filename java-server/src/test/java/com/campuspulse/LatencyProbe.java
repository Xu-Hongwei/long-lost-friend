package com.campuspulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class LatencyProbe {
    public static void main(String[] args) throws Exception {
        AppConfig live = AppConfig.load();
        Path root = Files.createTempDirectory("campus-pulse-latency-probe");
        AppConfig config = new AppConfig(
                0,
                root,
                root.resolve("public"),
                root.resolve("runtime").resolve("state.bin"),
                7L * 24 * 60 * 60 * 1000,
                live.llmBaseUrl,
                live.llmApiKey,
                live.llmModel,
                live.llmTimeout,
                live.plotLlmBaseUrl,
                live.plotLlmApiKey,
                live.plotLlmModel,
                live.plotLlmTimeout
        );

        ChatOrchestrator orchestrator = new ChatOrchestrator(
                new StateRepository(config.stateFile),
                new AgentConfigService(),
                new SocialMemoryService(config.memoryRetentionMs),
                new NarrativeRelationshipService(),
                new EventEngine(),
                new ExpressiveLlmClient(config),
                new AdaptiveSafetyService(),
                new AnalyticsService(),
                new QuickJudgeService(config),
                new PlotDirectorAgentService(config)
        );

        System.out.println("mainModel=" + config.llmModel + ", plotModel=" + config.plotLlmModel
                + ", quickWaitMs=" + System.getenv().getOrDefault("QUICK_JUDGE_WAIT_MS", "<default>"));
        runSingleTurn(orchestrator, "light_chat", "你好呀");
        runSingleTurn(orchestrator, "question_check", "你平时喜欢看什么书？");
        runSingleTurn(orchestrator, "emotion_share", "我今天有点累，不知道该怎么调整。");
        runFlow(orchestrator, "library_flow", List.of(
                "我们去图书馆坐会儿吧",
                "可以，那你讲讲吧"
        ));
        runFlow(orchestrator, "canteen_flow", List.of(
                "我们去食堂吃点东西吧",
                "我其实还好，只要能吃饱就可以"
        ));
        runFlow(orchestrator, "plot_window_flow", List.of(
                "今天在图书馆待久了，脑子有点乱。",
                "但和你坐在一起的时候会安静一点。",
                "我想听你讲点轻松的事情。",
                "你可以讲讲你最近觉得有意思的事。",
                "如果你愿意，也可以讲一点自己的想法。",
                "嗯，我在听。"
        ));
    }

    private static void runSingleTurn(ChatOrchestrator orchestrator, String label, String message) throws Exception {
        runFlow(orchestrator, label, List.of(message));
    }

    private static void runFlow(ChatOrchestrator orchestrator, String label, List<String> messages) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession(String.valueOf(visitor.get("visitorId")), "healing");
        String visitorId = String.valueOf(visitor.get("visitorId"));
        String sessionId = String.valueOf(session.get("sessionId"));
        int turn = 0;
        for (String message : messages) {
            turn++;
            long started = System.nanoTime();
            Map<String, Object> result = orchestrator.sendMessage(Map.of(
                    "visitorId", visitorId,
                    "sessionId", sessionId,
                    "agentId", "healing",
                    "userMessage", message
            ));
            long wallMs = Math.round((System.nanoTime() - started) / 1_000_000.0);
            printTurn(label, turn, message, result, wallMs);
        }
    }

    private static void printTurn(String label, int turn, String message, Map<String, Object> result, long wallMs) {
        Map<String, Object> timing = castMap(result.get("agent_timing"));
        Map<String, Object> quick = castMap(result.get("quick_judge_status"));
        Map<String, Object> context = castMap(result.get("turn_context"));
        Map<String, Object> continuity = castMap(result.get("dialogue_continuity"));
        long mainMs = diff(timing, "mainReplyStartedMs", "mainReplyFinishedMs");
        long plotMs = diff(timing, "plotDirectorStartedMs", "plotDirectorFinishedMs");
        Object quickVsMain = timing.get("quickJudgeVsMainReplyStartMs");

        System.out.println(String.join(" | ",
                "case=" + label + "#" + turn,
                "wallMs=" + wallMs,
                "mainMs=" + mainMs,
                "plotMs=" + plotMs,
                "replySource=" + value(result, "reply_source"),
                "plotAction=" + value(context, "plotDirectorAction"),
                "sceneMove=" + value(context, "sceneMoveIntent") + ":" + value(context, "sceneMoveTarget"),
                "transitionNeeded=" + value(continuity, "sceneTransitionNeeded"),
                "quick=" + value(quick, "status") + "/" + value(quick, "reason"),
                "quickVsMainStartMs=" + quickVsMain,
                "message=" + message
        ));
    }

    private static long diff(Map<String, Object> map, String startKey, String endKey) {
        Number start = number(map.get(startKey));
        Number end = number(map.get(endKey));
        if (start == null || end == null) {
            return -1;
        }
        return Math.max(0, end.longValue() - start.longValue());
    }

    private static String value(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ");
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
