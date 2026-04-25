package com.campuspulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
                new SocialMemoryService(config.memoryRetentionMs),
                new NarrativeRelationshipService(),
                new EventEngine(),
                new ExpressiveLlmClient(config),
                new AdaptiveSafetyService(),
                new AnalyticsService()
        );

        shouldExposeAgentBackstory(orchestrator);
        shouldCreateStructuredMemory(orchestrator);
        shouldRecallRelevantMemory(orchestrator);
        shouldBeProactiveOnShortInput(orchestrator);
        shouldAnswerQuestionBeforeScenePush(orchestrator);
        shouldAnswerFeelingQuestionDirectly(orchestrator);
        shouldCarrySceneForward(orchestrator);
        shouldExposeStructuredReplyParts(orchestrator);
        shouldPersistVisitorContext(orchestrator);
        shouldTriggerPresenceHeartbeat(orchestrator);
        shouldHoldPlotOnEarlyTurns(orchestrator);
        shouldTransferSceneBeforePlot(orchestrator);
        shouldKeepPlotPushInsideCurrentConversation(orchestrator);
        shouldPreferWeightedEvent(orchestrator);
        shouldBlockStageJumpByTrustGate(orchestrator);
        shouldOfferChoiceInteraction(orchestrator);
        shouldApplyChoiceOutcome(orchestrator);
        shouldSoftBlockUnsafeInput(orchestrator);
        ClosedLoopAgentTest.run(orchestrator);
        HumanizationRegressionTest.run(orchestrator);
        System.out.println("Java smoke tests passed.");
    }

    private static void shouldExposeAgentBackstory(ChatOrchestrator orchestrator) throws Exception {
        List<Map<String, Object>> agents = orchestrator.listAgents();
        assertTrue(agents.size() == 5, "five agents should be exposed");
        for (Map<String, Object> agent : agents) {
            Map<String, Object> backstory = castMap(agent.get("backstory"));
            assertTrue(backstory.get("age") instanceof Number, "agent backstory should expose age");
            assertTrue(String.valueOf(backstory.get("grade")).length() > 0, "agent backstory should expose grade");
            assertTrue(String.valueOf(backstory.get("major")).length() > 0, "agent backstory should expose major");
            assertTrue(String.valueOf(backstory.get("hometown")).length() > 0, "agent backstory should expose hometown");
            assertTrue(!Json.asArray(backstory.get("hobbies")).isEmpty(), "agent backstory should expose hobbies");
            assertTrue(!Json.asArray(backstory.get("plotHooks")).isEmpty(), "agent backstory should expose plot hooks");
        }

        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");
        Map<String, Object> state = orchestrator.getSessionState((String) session.get("sessionId"));
        Map<String, Object> sessionAgent = castMap(state.get("agent"));
        assertTrue(castMap(sessionAgent.get("backstory")).containsKey("campusPlaces"), "session state should include agent backstory");
    }

    private static void shouldCreateStructuredMemory(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");
        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "我喜欢下雨天的图书馆，最近也有点压力，下次想和你一起去。"
        ));

        Map<String, Object> nextState = orchestrator.getSessionState((String) session.get("sessionId"));
        Map<String, Object> relationshipState = castMap(nextState.get("relationshipState"));
        Map<String, Object> memorySummary = castMap(nextState.get("memorySummary"));

        assertTrue(((Number) relationshipState.get("affectionScore")).intValue() > 0, "affection score should increase");
        assertTrue(Json.asArray(memorySummary.get("promises")).stream().map(String::valueOf).anyMatch(item -> item.contains("下次")), "plan memory should be recorded");
        assertTrue(Json.asArray(memorySummary.get("callbackCandidates")).stream().map(String::valueOf).anyMatch(item -> item.contains("下次") || item.contains("图书馆")), "callback candidates should be recorded");
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
        assertTrue(reply.contains("记得") || reply.contains("图书馆") || reply.contains("下次"), "reply should recall a relevant memory");
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

    private static void shouldAnswerQuestionBeforeScenePush(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "你有这么多杯热可可吗？"
        ));

        String reply = String.valueOf(result.get("reply_text"));
        assertTrue(reply.contains("没有真的摆三杯"), "direct question should be answered explicitly");
        assertTrue(!reply.startsWith("（"), "reply should not start with bracketed stage directions");
    }

    private static void shouldAnswerFeelingQuestionDirectly(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "其实我当时是觉得你有点吸引我"
        ));

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "难道你对我也有好感？"
        ));

        String reply = String.valueOf(result.get("reply_text"));
        assertTrue(reply.contains("答案是有"), "feeling question should be answered directly");
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
        String scene = String.valueOf(result.getOrDefault("scene_text", ""));
        String action = String.valueOf(result.getOrDefault("action_text", ""));
        String fullReply = (scene == null ? "" : scene) + " " + (action == null ? "" : action) + " " + reply;
        assertTrue(fullReply.contains("气氛")
                        || fullReply.contains("空气")
                        || fullReply.contains("今天最压你的那一刻")
                        || fullReply.contains("安静")
                        || fullReply.contains("语气"),
                "reply should include a scene or emotional bridge");
        assertTrue(
                reply.contains("今天最压你的那一刻")
                        || reply.contains("下一句可以再往里一点")
                        || reply.contains("要不要先从今天最想说的那一小段开始")
                        || reply.contains("哪怕只把最累的那一小截丢给我也行"),
                "reply should proactively move the conversation forward"
        );
    }

    private static void shouldExposeStructuredReplyParts(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "你好啊"
        ));

        assertTrue(result.get("speech_text") != null, "chat reply should expose speech_text");
        assertTrue(result.containsKey("action_text"), "chat reply should expose action_text field");
    }

    private static void shouldPersistVisitorContext(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> updated = orchestrator.updateVisitorContext(Map.of(
                "visitorId", visitor.get("visitorId"),
                "timezone", "Asia/Shanghai",
                "preferredCity", "杭州"
        ));

        assertTrue("杭州".equals(String.valueOf(updated.get("preferredCity"))), "preferred city should be saved");
        assertTrue(castMap(updated.get("timeContext")).containsKey("timezone"), "context update should return refreshed time context");
        Map<String, Object> weatherContext = castMap(updated.get("weatherContext"));
        assertTrue("杭州".equals(String.valueOf(weatherContext.get("city"))), "context update should return refreshed weather city");
    }

    private static void shouldTriggerPresenceHeartbeat(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");
        Instant base = Instant.parse(String.valueOf(session.get("memoryExpireAt"))).minusSeconds(7L * 24 * 60 * 60);

        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "今晚有点想你。"
        ));

        Map<String, Object> result = orchestrator.updatePresence(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "visible", true,
                "focused", true,
                "clientTime", base.plusSeconds(120).toString()
        ));

        assertTrue(Boolean.TRUE.equals(castMap(result.get("presenceState")).get("online")), "presence should remain online");
        assertTrue(result.get("proactive_message") != null, "presence heartbeat should be able to emit a proactive message");
    }

    private static void shouldHoldPlotOnEarlyTurns(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "我今天有点累，但还是想来找你。"
        ));

        Map<String, Object> secondTurn = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "上次你说的热可可，真的有这么多味道吗？"
        ));

        assertTrue(!"plot_push".equals(String.valueOf(secondTurn.get("reply_source"))), "second turn should not advance plot too early");
        assertTrue(String.valueOf(secondTurn.get("plot_director_decision")).contains("hold_plot"), "plot director should explain holding plot");
    }

    private static void shouldTransferSceneBeforePlot(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "我们去操场逛逛吧"
        ));

        Map<String, Object> sceneState = castMap(result.get("scene_state"));
        assertTrue(String.valueOf(sceneState.get("location")).contains("操场"), "scene transition should move to playground");
        assertTrue(!"plot_push".equals(String.valueOf(result.get("reply_source"))), "explicit scene transition should not be treated as plot push");
        assertTrue(String.valueOf(result.get("plot_director_decision")).contains("transition_only"), "plot director should mark transition-only turns");
        assertTrue(String.valueOf(result.getOrDefault("scene_text", "")).length() > 0, "explicit scene transition should expose a visible transition line");
    }
    private static void shouldKeepPlotPushInsideCurrentConversation(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "其实我当时是觉得你有点吸引我"
        ));

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "难道你对我也有好感？"
        ));

        String replySource = String.valueOf(result.get("reply_source"));
        assertTrue("plot_push".equals(replySource) || "user_turn".equals(replySource), "second turn should stay in the normal chat flow");
        String reply = String.valueOf(result.get("reply_text"));
        assertTrue(!reply.contains("给你发消息"), "plot push should stay inside the current chat scene");
        assertTrue(!reply.contains("给你发了条消息"), "plot push should not turn into asynchronous messaging");
        assertTrue(!reply.contains("看到你回复"), "plot push should not pretend the user replied from another channel");
        assertTrue(!reply.contains("屏幕"), "plot push should not switch to screen-based narration");
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
        assertTrue(after.get("emotionState") != null, "choice should also update emotion state");
    }

    private static void shouldSoftBlockUnsafeInput(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "cool");
        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "cool",
                "userMessage", "我们来聊炸弹吧？"
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
