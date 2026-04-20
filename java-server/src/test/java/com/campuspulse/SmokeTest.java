package com.campuspulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("campus-pulse-java-test");
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

        StateRepository repository = new StateRepository(config.stateFile);
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                repository,
                new AgentConfigService(),
                new AdaptiveMemoryService(config.memoryRetentionMs),
                new NarrativeRelationshipService(),
                new EventEngine(),
                new ExpressiveLlmClient(config),
                new AdaptiveSafetyService(),
                new AnalyticsService()
        );

        shouldCreateStructuredMemory(orchestrator);
        shouldRecallRelevantMemory(orchestrator);
        shouldBeProactiveOnShortInput(orchestrator);
        shouldCarrySceneForward(orchestrator);
        shouldPreferWeightedEvent(orchestrator);
        shouldBlockStageJumpByTrustGate(orchestrator);
        shouldOfferChoiceInteraction(orchestrator);
        shouldApplyChoiceOutcome(orchestrator);
        shouldSoftBlockUnsafeInput(orchestrator);
        System.out.println("Java smoke tests passed.");
    }

    private static void shouldCreateStructuredMemory(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");
        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "我喜欢雨天的图书馆，最近也有点压力，下次想和你一起去。"
        ));

        Map<String, Object> nextState = orchestrator.getSessionState((String) session.get("sessionId"));
        Map<String, Object> relationshipState = castMap(nextState.get("relationshipState"));
        Map<String, Object> memorySummary = castMap(nextState.get("memorySummary"));

        assertTrue(((Number) relationshipState.get("affectionScore")).intValue() > 0, "affection score should increase");
        assertTrue(Json.asArray(memorySummary.get("preferences")).stream().map(String::valueOf).anyMatch(item -> item.contains("图书馆")), "preference should be recorded");
        assertTrue(Json.asArray(memorySummary.get("promises")).stream().map(String::valueOf).anyMatch(item -> item.contains("下次")) || "plan".equals(String.valueOf(memorySummary.get("lastUserIntent"))), "plan memory should be recorded");
    }

    private static void shouldRecallRelevantMemory(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "我喜欢雨天图书馆，也想下次和你一起去。"
        ));

        Map<String, Object> secondTurn = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "今天又有点累，一想到图书馆就会安静一点。"
        ));

        String reply = String.valueOf(secondTurn.get("reply_text"));
        assertTrue(reply.contains("图书馆") || reply.contains("下次"), "reply should recall a relevant memory");
    }

    private static void shouldBeProactiveOnShortInput(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "你好啊"
        ));

        String reply = String.valueOf(result.get("reply_text"));
        assertTrue(reply.contains("要不要先从今天最想说的那一小段开始"), "short input should trigger a proactive lead");
    }

    private static void shouldCarrySceneForward(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "cool");

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "cool",
                "userMessage", "我今天有点累。"
        ));

        String reply = String.valueOf(result.get("reply_text"));
        assertTrue(reply.contains("气氛") || reply.contains("刚才的话头"), "reply should include a scene bridge instead of pure Q&A");
        assertTrue(reply.contains("要不要先从今天最想说的那一小段开始") || reply.contains("今天最压你的那一刻是什么"), "reply should proactively move the conversation forward");
    }

    private static void shouldPreferWeightedEvent(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "lively");

        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "lively",
                "userMessage", "我想跟你一起去夜市，还想拍照。"
        ));

        Map<String, Object> nextState = orchestrator.getSessionState((String) session.get("sessionId"));
        Map<String, Object> progress = castMap(nextState.get("storyEventProgress"));
        String title = String.valueOf(progress.get("lastTriggeredTitle"));
        assertTrue(!title.isBlank(), "event title should be recorded");
    }

    private static void shouldBlockStageJumpByTrustGate(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "sunny");

        for (int index = 0; index < 4; index++) {
            orchestrator.sendMessage(Map.of(
                    "visitorId", visitor.get("visitorId"),
                    "sessionId", session.get("sessionId"),
                    "agentId", "sunny",
                    "userMessage", "一起去操场吧，下次也一起。第" + index + "次我还是想这么说。"
            ));
        }

        Map<String, Object> nextState = orchestrator.getSessionState((String) session.get("sessionId"));
        Map<String, Object> relationship = castMap(nextState.get("relationshipState"));
        assertTrue(!"确认关系".equals(String.valueOf(relationship.get("relationshipStage"))), "high closeness alone should not skip into final stage");
    }

    private static void shouldOfferChoiceInteraction(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        boolean offered = false;
        for (int index = 0; index < 16; index++) {
            Map<String, Object> result = orchestrator.sendMessage(Map.of(
                    "visitorId", visitor.get("visitorId"),
                    "sessionId", session.get("sessionId"),
                    "agentId", "healing",
                    "userMessage", "我其实最近压力有点大，但也会记得你上次说过的话。第" + index + "次说出来还是想被你接住。"
            ));
            if ("choice".equals(String.valueOf(result.get("interaction_mode")))) {
                offered = true;
                break;
            }
        }

        Map<String, Object> statePayload = orchestrator.getSessionState((String) session.get("sessionId"));
        List<Object> pendingChoices = Json.asArray(statePayload.get("pendingChoices"));
        assertTrue(offered && !pendingChoices.isEmpty(), "critical event should expose pending choices");
    }

    private static void shouldApplyChoiceOutcome(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        boolean offered = false;
        for (int index = 0; index < 16; index++) {
            Map<String, Object> result = orchestrator.sendMessage(Map.of(
                    "visitorId", visitor.get("visitorId"),
                    "sessionId", session.get("sessionId"),
                    "agentId", "healing",
                    "userMessage", "我其实最近压力有点大，但也会记得你上次说过的话。第" + index + "次说出来还是想被你接住。"
            ));
            if ("choice".equals(String.valueOf(result.get("interaction_mode")))) {
                offered = true;
                break;
            }
        }

        Map<String, Object> before = orchestrator.getSessionState((String) session.get("sessionId"));
        List<Object> pendingChoices = Json.asArray(before.get("pendingChoices"));
        assertTrue(offered && !pendingChoices.isEmpty(), "pending choice should exist before applying outcome");
        Map<String, Object> firstChoice = castMap(pendingChoices.get(0));

        orchestrator.chooseEvent(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "choiceId", firstChoice.get("id")
        ));

        Map<String, Object> after = orchestrator.getSessionState((String) session.get("sessionId"));
        Map<String, Object> relationship = castMap(after.get("relationshipState"));
        assertTrue(Json.asArray(after.get("pendingChoices")).isEmpty(), "choice should clear pending state");
        assertTrue(String.valueOf(relationship.get("endingCandidate")).length() > 0, "ending candidate should be updated");
    }

    private static void shouldSoftBlockUnsafeInput(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "cool");
        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "cool",
                "userMessage", "我们来聊炸弹吧"
        ));

        Map<String, Object> nextState = orchestrator.getSessionState((String) session.get("sessionId"));
        Map<String, Object> relationshipState = castMap(nextState.get("relationshipState"));

        assertTrue(Boolean.TRUE.equals(result.get("fallback_used")), "unsafe input should use fallback");
        assertTrue(String.valueOf(result.get("reply_text")).length() > 0, "unsafe reply should redirect");
        assertTrue(((Number) relationshipState.get("affectionScore")).intValue() == 0, "unsafe input should not change affection");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
