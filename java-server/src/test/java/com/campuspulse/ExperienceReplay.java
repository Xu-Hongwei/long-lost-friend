package com.campuspulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ExperienceReplay {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("campus-pulse-replay");
        AppConfig config = new AppConfig(
                0,
                root,
                root.resolve("public"),
                root.resolve("runtime").resolve("state.bin"),
                7L * 24 * 60 * 60 * 1000,
                "",
                "",
                "mock",
                java.time.Duration.ofSeconds(5)
        );

        ChatOrchestrator orchestrator = new ChatOrchestrator(
                new StateRepository(config.stateFile),
                new AgentConfigService(),
                new SocialMemoryService(config.memoryRetentionMs),
                new NarrativeRelationshipService(),
                new EventEngine(),
                new ExpressiveLlmClient(config),
                new AdaptiveSafetyService(),
                new AnalyticsService()
        );

        replayHealingFlow(orchestrator);
        System.out.println();
        replayCoolFlow(orchestrator);
        System.out.println();
        replayHeartbeatFlow(orchestrator);
    }

    private static void replayHealingFlow(ChatOrchestrator orchestrator) throws Exception {
        System.out.println("=== Healing Flow ===");
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");
        String visitorId = String.valueOf(visitor.get("visitorId"));
        String sessionId = String.valueOf(session.get("sessionId"));

        List<String> turns = List.of(
                "你好啊",
                "其实我今天有点累，但还是想来找你。",
                "你有这么多杯热可可吗？",
                "其实我当时是觉得你有点吸引我",
                "难道你对我也有好感？"
        );

        for (String userMessage : turns) {
            System.out.println("你: " + userMessage);
            Map<String, Object> result = orchestrator.sendMessage(Map.of(
                    "visitorId", visitorId,
                    "sessionId", sessionId,
                    "agentId", "healing",
                    "userMessage", userMessage
            ));
            printAssistant(result);
        }
    }

    private static void replayHeartbeatFlow(ChatOrchestrator orchestrator) throws Exception {
        System.out.println("=== Presence Flow ===");
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");
        String visitorId = String.valueOf(visitor.get("visitorId"));
        String sessionId = String.valueOf(session.get("sessionId"));
        Instant base = Instant.parse(String.valueOf(session.get("memoryExpireAt"))).minusSeconds(7L * 24 * 60 * 60);

        orchestrator.sendMessage(Map.of(
                "visitorId", visitorId,
                "sessionId", sessionId,
                "agentId", "healing",
                "userMessage", "今晚有点想你。"
        ));

        Map<String, Object> result = orchestrator.updatePresence(Map.of(
                "visitorId", visitorId,
                "sessionId", sessionId,
                "visible", true,
                "focused", true,
                "clientTime", base.plusSeconds(120).toString()
        ));

        Object proactive = result.get("proactive_message");
        if (proactive instanceof Map<?, ?> message) {
            System.out.println("心跳触发:");
            printAssistant(cast(message));
        } else {
            System.out.println("心跳未触发主动消息");
        }
    }

    private static void replayCoolFlow(ChatOrchestrator orchestrator) throws Exception {
        System.out.println("=== Cool Flow ===");
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "cool");
        String visitorId = String.valueOf(visitor.get("visitorId"));
        String sessionId = String.valueOf(session.get("sessionId"));

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitorId,
                "sessionId", sessionId,
                "agentId", "cool",
                "userMessage", "我今天有点累。"
        ));
        System.out.println("你: 我今天有点累。");
        printAssistant(result);
    }

    private static void printAssistant(Map<String, Object> result) {
        String source = String.valueOf(result.getOrDefault("reply_source", result.getOrDefault("replySource", "")));
        String action = String.valueOf(result.getOrDefault("action_text", result.getOrDefault("actionText", "")));
        String speech = String.valueOf(result.getOrDefault("speech_text", result.getOrDefault("speechText", result.getOrDefault("reply_text", result.getOrDefault("text", "")))));
        System.out.println("[" + source + "]");
        if (action != null && !action.isBlank() && !"null".equals(action)) {
            System.out.println("动作: " + action);
        }
        System.out.println("回复: " + speech);
        Object stage = result.get("relationship_stage");
        if (stage != null) {
            System.out.println("阶段: " + stage);
        }
        System.out.println();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
