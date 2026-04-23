package com.campuspulse;

import java.util.Map;

final class ClosedLoopAgentTest {
    private ClosedLoopAgentTest() {
    }

    static void run(ChatOrchestrator orchestrator) throws Exception {
        shouldCoordinateChatScoringAndPlotHold(orchestrator);
        shouldPrioritizeSceneTransitionOverPlotPush(orchestrator);
        shouldAllowPlotAdvanceAfterEnoughContext(orchestrator);
        shouldExposeThreeAgentStateInSession(orchestrator);
        shouldKeepAcceptedPlanInDialogueContinuity(orchestrator);
    }

    private static void shouldCoordinateChatScoringAndPlotHold(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "\u6211\u4eca\u5929\u6709\u70b9\u7d2f\uff0c\u4f46\u8fd8\u662f\u60f3\u6765\u627e\u4f60\u804a\u4f1a\u513f\u3002"
        ));

        assertNonBlank(result.get("reply_text"), "chat agent should produce a visible reply");
        assertTrue(result.get("affection_score") instanceof Number, "scoring agent should update affection score");
        assertTrue(castMap(result.get("affection_delta")).containsKey("total"), "scoring agent should expose turn delta");
        assertTrue(castMap(result.get("intent_state")).containsKey("primaryIntent"), "intent layer should expose primary intent");
        assertTrue(castMap(result.get("response_plan")).containsKey("coreTask"), "response planner should expose core task");
        Map<String, Object> turnContext = castMap(result.get("turn_context"));
        assertTrue(turnContext.containsKey("affectionDeltaTotal"), "shared turn context should expose scoring delta");
        assertTrue("hold_plot".equals(String.valueOf(turnContext.get("plotDirectorAction"))), "shared turn context should expose plot action");
        assertTrue(((Number) turnContext.get("plotDirectorConfidence")).intValue() > 0, "plot director should expose confidence");
        assertTrue(String.valueOf(result.get("plot_director_decision")).contains("hold_plot"), "plot director should hold early turns");
        assertTrue(!"plot_push".equals(String.valueOf(result.get("reply_source"))), "early chat should not be hijacked by plot");
    }

    private static void shouldPrioritizeSceneTransitionOverPlotPush(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "\u6211\u4eec\u53bb\u64cd\u573a\u901b\u901b\u5427\uff0c\u8fb9\u8d70\u8fb9\u804a\u3002"
        ));

        Map<String, Object> sceneState = castMap(result.get("scene_state"));
        assertTrue(String.valueOf(sceneState.get("location")).contains("\u64cd\u573a"), "scene agent should move location to playground");
        Map<String, Object> turnContext = castMap(result.get("turn_context"));
        assertTrue(String.valueOf(turnContext.get("sceneLocation")).contains("\u64cd\u573a"), "shared turn context should carry new scene location");
        assertTrue("transition_only".equals(String.valueOf(turnContext.get("plotDirectorAction"))), "shared turn context should mark transition-only");
        assertTrue(String.valueOf(result.get("plot_director_decision")).contains("transition_only"), "plot director should mark explicit transition only");
        assertTrue(!"plot_push".equals(String.valueOf(result.get("reply_source"))), "scene transition should not count as plot push");
    }

    private static void shouldAllowPlotAdvanceAfterEnoughContext(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");
        String visitorId = String.valueOf(visitor.get("visitorId"));
        String sessionId = String.valueOf(session.get("sessionId"));

        String[] turns = {
                "\u4eca\u5929\u6211\u6709\u70b9\u7d2f\uff0c\u4f46\u548c\u4f60\u8bf4\u8bdd\u4f1a\u8f7b\u677e\u4e00\u70b9\u3002",
                "\u4e0a\u6b21\u4f60\u8bf4\u7684\u70ed\u53ef\u53ef\u6211\u8fd8\u8bb0\u5f97\uff0c\u611f\u89c9\u633a\u6696\u7684\u3002",
                "\u5982\u679c\u4f60\u613f\u610f\uff0c\u6211\u60f3\u542c\u542c\u4f60\u4eca\u5929\u7684\u4e8b\u3002",
                "\u6211\u4eec\u597d\u50cf\u6162\u6162\u6709\u70b9\u9ed8\u5951\u4e86\u3002",
                "\u521a\u624d\u90a3\u4e2a\u8bdd\u9898\u53ef\u4ee5\u7ee7\u7eed\uff0c\u6211\u60f3\u8ba4\u771f\u63a5\u4f4f\u4f60\u3002",
                "\u8981\u4e0d\u6211\u4eec\u5c31\u987a\u7740\u8fd9\u4e2a\u6c14\u6c1b\u5f80\u524d\u8d70\u4e00\u5c0f\u6b65\uff1f",
                "\u6211\u4e0d\u60f3\u6025\uff0c\u4f46\u4e5f\u60f3\u8ba9\u8fd9\u6bb5\u804a\u5929\u771f\u7684\u7559\u4e0b\u4e00\u4e2a\u5c0f\u8282\u70b9\u3002"
        };

        Map<String, Object> latest = null;
        Map<String, Object> advanced = null;
        for (String turn : turns) {
            latest = orchestrator.sendMessage(Map.of(
                    "visitorId", visitorId,
                    "sessionId", sessionId,
                    "agentId", "healing",
                    "userMessage", turn
            ));
            if (String.valueOf(latest.get("plot_director_decision")).contains("advance_plot")) {
                advanced = latest;
            }
        }

        assertTrue(latest != null, "loop should produce a latest result");
        Map<String, Object> plotProgress = castMap(latest.get("plot_progress"));
        int beatIndex = ((Number) plotProgress.get("beatIndex")).intValue();
        assertTrue(beatIndex >= 0, "plot state should be present after long context");
        assertTrue(
                String.valueOf(latest.get("plot_director_decision")).contains("advance_plot")
                        || String.valueOf(latest.get("plot_director_decision")).contains("hold_plot"),
                "plot director should make an explicit decision after enough context"
        );
        Map<String, Object> turnContext = castMap(latest.get("turn_context"));
        assertTrue(((Number) turnContext.get("plotGap")).intValue() >= 0, "shared turn context should expose plot gap");
        assertTrue(((Number) turnContext.get("plotSignal")).intValue() >= 0, "shared turn context should expose plot signal");
        assertNonBlank(latest.get("reply_text"), "chat agent should still answer during plot decision");
        assertTrue(castMap(latest.get("emotion_state")).containsKey("currentMood"), "emotion state should keep feeding chat tone");
        assertTrue(advanced != null, "enough context should allow one plot beat to advance");
        Map<String, Object> advancedDelta = castMap(advanced.get("affection_delta"));
        assertTrue(((Number) advancedDelta.get("total")).intValue() >= 4, "plot advancement should apply a stronger macro score than ordinary micro chat");
        assertTrue(Json.asArray(advanced.get("behavior_tags")).stream().map(String::valueOf).anyMatch("plot_macro_score"::equals), "plot advancement should mark macro scoring");
    }

    private static void shouldExposeThreeAgentStateInSession(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "cool");
        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "cool",
                "userMessage", "\u6211\u521a\u624d\u8bf4\u8bdd\u53ef\u80fd\u6709\u70b9\u76f4\uff0c\u4f46\u6211\u60f3\u8ba4\u771f\u804a\u3002"
        ));

        Map<String, Object> state = orchestrator.getSessionState((String) session.get("sessionId"));
        assertTrue(castMap(state.get("relationshipState")).containsKey("affectionScore"), "session should expose scoring state");
        assertTrue(castMap(state.get("plotState")).containsKey("beatIndex"), "session should expose plot state");
        assertTrue(castMap(state.get("lastIntentState")).containsKey("primaryIntent"), "session should expose latest intent state");
        assertTrue(castMap(state.get("lastResponsePlan")).containsKey("dialogueMode"), "session should expose latest response plan");
        assertTrue(castMap(state.get("lastHumanizationAudit")).containsKey("feltHeard"), "session should expose humanization audit");
        assertTrue(castMap(state.get("lastTurnContext")).containsKey("plotDirectorAction"), "session should expose latest shared turn context");
    }

    private static void shouldKeepAcceptedPlanInDialogueContinuity(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");
        String visitorId = String.valueOf(visitor.get("visitorId"));
        String sessionId = String.valueOf(session.get("sessionId"));

        orchestrator.sendMessage(Map.of(
                "visitorId", visitorId,
                "sessionId", sessionId,
                "agentId", "healing",
                "userMessage", "\u6211\u5c31\u4e0d\u559d\u4e86\uff0c\u6211\u53bb\u7ed9\u4f60\u4e70\u4e00\u676f\uff0c\u4f60\u5728\u8fd9\u7b49\u6211\u4e00\u4e0b\u3002"
        ));

        Map<String, Object> accepted = orchestrator.sendMessage(Map.of(
                "visitorId", visitorId,
                "sessionId", sessionId,
                "agentId", "healing",
                "userMessage", "\u90a3\u53bb\u5427\uff0c\u5c31\u5f53\u591a\u7ec3\u7ec3\u4e00\u4f1a\u513f\u8eab\u4f53\u4e86\u3002"
        ));

        Map<String, Object> continuity = castMap(accepted.get("dialogue_continuity"));
        assertTrue(String.valueOf(continuity.get("currentObjective")).contains("\u70ed\u996e"), "continuity layer should lock the accepted hot drink plan");
        assertTrue(Boolean.TRUE.equals(continuity.get("sceneTransitionNeeded")), "accepted plan should require a scene/action transition");
        assertTrue(Json.asArray(continuity.get("mustNotContradict")).stream().map(String::valueOf).anyMatch(item -> item.contains("\u70ed\u996e")), "continuity guards should mention hot drink");
        String reply = String.valueOf(accepted.get("reply_text"));
        assertTrue(!reply.contains("\u4ece\u54ea\u5f00\u59cb\u901b"), "reply should not forget the accepted hot drink plan and turn into generic wandering");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object raw) {
        if (!(raw instanceof Map<?, ?>)) {
            throw new IllegalStateException("Expected map but got " + raw);
        }
        return (Map<String, Object>) raw;
    }

    private static void assertNonBlank(Object value, String message) {
        assertTrue(value != null && !String.valueOf(value).isBlank(), message);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
