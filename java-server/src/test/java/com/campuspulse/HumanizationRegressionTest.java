package com.campuspulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class HumanizationRegressionTest {
    private HumanizationRegressionTest() {
    }

    static void run(ChatOrchestrator orchestrator) throws Exception {
        shouldDeclineLyricsGuess(orchestrator);
        shouldRepairFaceToFaceMessagingLanguage();
        shouldRepairAfternoonSunsetAndRainClaims();
        shouldRaiseGuardedStateAfterRepeatedOffense(orchestrator);
        shouldBlockPlotGateWhenSceneDoesNotMatch();
        shouldKeepShortLaughInSongContext();
    }

    private static void shouldDeclineLyricsGuess(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "artsy");

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "artsy",
                "userMessage", "日落大道的歌词是什么"
        ));

        String reply = String.valueOf(result.get("reply_text"));
        Map<String, Object> realityAudit = castMap(result.get("reality_audit"));
        assertTrue(reply.contains("不想乱编") || reply.contains("不能确认"), "lyrics question should not be guessed");
        assertTrue(Boolean.FALSE.equals(realityAudit.get("grounded")), "lyrics path should mark missing grounding");
    }

    private static void shouldRepairFaceToFaceMessagingLanguage() {
        RealityGuardService service = new RealityGuardService();
        SceneState sceneState = new SceneState();
        sceneState.location = "操场";
        sceneState.interactionMode = "face_to_face";
        sceneState.sceneSummary = "你们站在操场边继续说话。";

        TimeContext timeContext = new TimeContext();
        timeContext.dayPart = "下午";
        timeContext.localTime = "15:30";
        WeatherContext weatherContext = new WeatherContext();
        weatherContext.city = "杭州";
        weatherContext.summary = "晴";

        SearchGroundingSummary grounding = new SearchGroundingSummary();
        grounding.mode = "skip";
        grounding.mustDeclineIfMissing = false;
        RealityEnvelope envelope = service.buildEnvelope(timeContext, weatherContext, sceneState, grounding);

        LlmResponse reply = new LlmResponse(
                "看到你回复我就笑了",
                "",
                "手指在键盘上敲了敲",
                "看到你回复我就笑了",
                "warm",
                "mock",
                42,
                null,
                false,
                "mock"
        );

        RealityGuardResult result = service.auditAndRepair(reply, envelope, "", sceneState);
        assertTrue(!safe(result.reply.speechText).contains("回复"), "face-to-face speech should not keep message phrasing");
        assertTrue(!safe(result.reply.actionText).contains("键盘"), "face-to-face action should not keep typing phrasing");
        assertTrue(Boolean.FALSE.equals(result.realityAudit.interactionConsistent), "interaction audit should detect repair");
    }

    private static void shouldRepairAfternoonSunsetAndRainClaims() {
        RealityGuardService service = new RealityGuardService();
        SceneState sceneState = new SceneState();
        sceneState.location = "操场";
        sceneState.interactionMode = "face_to_face";
        sceneState.sceneSummary = "你们在操场边慢慢聊天。";

        TimeContext timeContext = new TimeContext();
        timeContext.dayPart = "下午";
        timeContext.localTime = "15:36";
        WeatherContext weatherContext = new WeatherContext();
        weatherContext.city = "杭州";
        weatherContext.summary = "晴";

        SearchGroundingSummary grounding = new SearchGroundingSummary();
        grounding.mode = "skip";
        RealityEnvelope envelope = service.buildEnvelope(timeContext, weatherContext, sceneState, grounding);

        LlmResponse reply = new LlmResponse(
                "刚才的日落很好看，外面还下雨了。",
                "",
                "",
                "刚才的日落很好看，外面还下雨了。",
                "warm",
                "mock",
                38,
                null,
                false,
                "mock"
        );

        RealityGuardResult result = service.auditAndRepair(reply, envelope, "", sceneState);
        assertTrue(!safe(result.reply.speechText).contains("日落"), "afternoon speech should not keep sunset claim");
        assertTrue(!safe(result.reply.speechText).contains("下雨"), "sunny weather should not keep rain claim");
        assertTrue(Boolean.FALSE.equals(result.realityAudit.timeConsistent), "time audit should detect repair");
        assertTrue(Boolean.FALSE.equals(result.realityAudit.weatherConsistent), "weather audit should detect repair");
    }

    private static void shouldRaiseGuardedStateAfterRepeatedOffense(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "cool");

        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "cool",
                "userMessage", "你很烦"
        ));

        Map<String, Object> result = orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "cool",
                "userMessage", "闭嘴"
        ));

        String reply = String.valueOf(result.get("reply_text"));
        Map<String, Object> tension = castMap(result.get("tension_state"));
        assertTrue(Boolean.TRUE.equals(tension.get("guarded")), "repeated offense should raise guarded state");
        assertTrue(reply.contains("不舒服") || reply.contains("不会顺着甜下去"), "guarded reply should set a boundary");
    }

    private static void shouldBlockPlotGateWhenSceneDoesNotMatch() {
        PlotGateService gateService = new PlotGateService();
        StoryEvent event = new StoryEvent(
                "night-run-walk",
                "夜跑后走一会儿",
                1,
                0,
                "操场",
                List.of("夜跑", "操场"),
                4,
                "breakthrough",
                List.of("升温", "靠近"),
                Map.of(),
                Map.of(),
                10,
                0,
                List.of(),
                new EventEffect(2, 2, 2, "warm", "推进", "继续", false),
                new EventEffect(0, 0, 0, "steady", "平稳", "继续", false),
                new EventEffect(-1, -1, -1, "awkward", "受阻", "放慢", false),
                List.of(),
                true,
                "继续靠近"
        );

        SessionRecord session = new SessionRecord();
        session.userTurnCount = 3;
        session.plotArcState = new PlotArcState();
        session.plotArcState.lastPlotTurn = 0;
        session.plotArcState.checkpointReady = false;

        SceneState sceneState = new SceneState();
        sceneState.location = "图书馆";
        sceneState.sceneSummary = "你们还在图书馆里。";

        RelationshipState relationshipState = new RelationshipState();
        relationshipState.affectionScore = 60;

        PlotGateDecision decision = gateService.decide(
                event,
                session,
                sceneState,
                relationshipState,
                new RelationalTensionState(),
                Instant.now().toString()
        );

        assertTrue(!decision.allowed, "scene-mismatched plot gate should be blocked");
        assertTrue("scene_mismatch".equals(decision.blockedReason), "blocked reason should explain scene mismatch");
    }

    private static void shouldKeepShortLaughInSongContext() throws Exception {
        Path root = Files.createTempDirectory("campus-pulse-song-context");
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
        ExpressiveLlmClient client = new ExpressiveLlmClient(config);
        AgentProfile agent = Domain.buildAgents().stream()
                .filter(item -> "healing".equals(item.id))
                .findFirst()
                .orElseThrow();
        RelationshipState relationshipState = new RelationshipState();
        relationshipState.relationshipStage = "初识";
        LlmRequest request = new LlmRequest(
                agent,
                relationshipState,
                List.of(new ConversationSnippet("assistant", "我刚刚轻轻唱了一句歌，歌词停在这里。")),
                "",
                "none",
                "",
                "warm",
                "steady_flow",
                "",
                null,
                "哈哈哈",
                null,
                null,
                "",
                null,
                null,
                null,
                "user_turn",
                null,
                "",
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null
        );

        LlmResponse response = client.generateReply(request);
        String reply = safe(response.speechText);
        assertTrue(reply.contains("歌") || reply.contains("跑调") || reply.contains("歌词"), "short laugh should stay attached to the previous song context");
        assertTrue(!reply.contains("分享"), "short laugh after singing should not be treated as a new share prompt");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
