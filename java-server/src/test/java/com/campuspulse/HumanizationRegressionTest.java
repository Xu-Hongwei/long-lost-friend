package com.campuspulse;

import java.lang.reflect.Method;
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
        shouldIncludePendingRepairCueInPrompt();
        shouldPreferDirectorSceneForFinalCleanup(orchestrator);
        shouldStripDuplicatedSceneFromSpeech(orchestrator);
        shouldStripLeadingScenePrefixBeforeDialogue(orchestrator);
        shouldNotRepeatPendingSceneText();
        shouldSettleSceneTransitionWhenTargetReached();
        shouldNotReuseArrivedLibraryPlanOnAcceptance();
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

    private static void shouldPreferDirectorSceneForFinalCleanup(ChatOrchestrator orchestrator) throws Exception {
        Method method = ChatOrchestrator.class.getDeclaredMethod("selectSceneText", String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        String scene = "\u4e24\u4eba\u5403\u5b8c\u996d\u540e\uff0c\u624b\u7275\u624b\u5f80\u8fd9\u91cc\u8d70\u53bb\u3002";
        String speech = "\u4e24\u4eba\u5403\u5b8c\u996d\u540e\uff0c\u624b\u7275\u624b\u5f80\u8fd9\u91cc\u8d70\u53bb\uff0c\u4f60\u60f3\u7ee7\u7eed\u5750\u5728\u4e4b\u524d\u7684\u4f4d\u7f6e\u4e0a\u5417\uff1f";

        String selected = String.valueOf(method.invoke(orchestrator, scene, "", speech, "transition_only"));

        assertTrue(scene.equals(selected), "director scene should be preserved so final cleanup can strip it from speech");
    }

    private static void shouldStripDuplicatedSceneFromSpeech(ChatOrchestrator orchestrator) throws Exception {
        Method method = ChatOrchestrator.class.getDeclaredMethod("removeSceneTextFromSpeech", String.class, String.class);
        method.setAccessible(true);
        String speech = "嗯……不知道聊聊我们的大学生活吧？你有没有参加什么有趣的社团活动呀？ 两人走在操场的小道上，夕阳将身影拉得长长的";
        String scene = "两人在操场的小道上，夕阳将身影拉得长长的。";

        String cleaned = String.valueOf(method.invoke(orchestrator, speech, scene));

        assertTrue(cleaned.contains("大学生活"), "dialogue content should be preserved");
        assertTrue(!cleaned.contains("夕阳将身影拉得长长的"), "duplicated scene sentence should be stripped from speech");
    }

    private static void shouldStripLeadingScenePrefixBeforeDialogue(ChatOrchestrator orchestrator) throws Exception {
        Method method = ChatOrchestrator.class.getDeclaredMethod("removeSceneTextFromSpeech", String.class, String.class);
        method.setAccessible(true);
        String scene = "\u4e24\u4eba\u5403\u5b8c\u996d\u540e\uff0c\u624b\u7275\u624b\u5f80\u8fd9\u91cc\u8d70\u53bb\uff0c\u508d\u665a\u7684\u5fae\u98ce\u8f7b\u62c2\u8fc7\uff0c\u5e26\u6765\u4e1d\u4e1d\u51c9\u610f\u3002";
        String speech = "\u4e24\u4eba\u5403\u5b8c\u996d\u540e\uff0c\u624b\u7275\u624b\u5f80\u8fd9\u91cc\u8d70\u53bb\uff0c\u508d\u665a\u7684\u5fae\u98ce\u8f7b\u62c2\u8fc7\uff0c\u5e26\u6765\u4e1d\u4e1d\u51c9\u610f\uff0c\u4f60\u60f3\u7ee7\u7eed\u5750\u5728\u4e4b\u524d\u7684\u4f4d\u7f6e\u4e0a\u5417\uff1f";

        String cleaned = String.valueOf(method.invoke(orchestrator, speech, scene));

        assertTrue("\u4f60\u60f3\u7ee7\u7eed\u5750\u5728\u4e4b\u524d\u7684\u4f4d\u7f6e\u4e0a\u5417\uff1f".equals(cleaned), "leading duplicated scene prefix should be stripped before dialogue");
    }

    private static void shouldNotRepeatPendingSceneText() {
        SceneDirectorService service = new SceneDirectorService();
        SceneState previous = new SceneState();
        previous.location = "食堂";
        previous.interactionMode = "face_to_face";
        previous.sceneSummary = "场景被轻轻带到了食堂这一侧。";

        SceneState next = new SceneState();
        next.location = "食堂";
        next.interactionMode = "face_to_face";
        next.transitionPending = true;
        next.sceneSummary = "场景被轻轻带到了食堂这一侧。";

        String repeated = service.buildSceneText(previous, next);
        assertTrue(repeated.isBlank(), "pending scene should not be emitted again without a fresh location change");

        next.location = "操场";
        String moved = service.buildSceneText(previous, next);
        assertTrue(!moved.isBlank() && moved.contains("操场"), "fresh location changes should still emit a scene line");
    }

    private static void shouldSettleSceneTransitionWhenTargetReached() {
        DialogueContinuityService service = new DialogueContinuityService();
        DialogueContinuityState state = new DialogueContinuityState();
        state.currentObjective = "\u4e00\u8d77\u53bb\u98df\u5802\u3002";
        state.acceptedPlan = "\u4e00\u8d77\u53bb\u98df\u5802\u3002";
        state.sceneTransitionNeeded = true;
        state.nextBestMove = "transition first";
        state.confidence = 80;

        SceneState scene = new SceneState();
        scene.location = "\u98df\u5802";
        scene.interactionMode = "face_to_face";

        DialogueContinuityState settled = service.settleSceneTransitionIfArrived(state, scene, Instant.now().toString());

        assertTrue(!settled.sceneTransitionNeeded, "arrived scene should no longer require transition");
        assertTrue(settled.currentObjective.isBlank(), "settling should complete the movement objective");
        assertTrue(settled.acceptedPlan.isBlank(), "settling should clear the accepted movement plan");
        assertTrue(settled.confidence >= 65, "settling should keep enough confidence for downstream guards");
    }

    private static void shouldNotReuseArrivedLibraryPlanOnAcceptance() {
        DialogueContinuityService service = new DialogueContinuityService();
        DialogueContinuityState state = new DialogueContinuityState();
        state.currentObjective = "\u4e00\u8d77\u53bb\u56fe\u4e66\u9986\u3002";
        state.acceptedPlan = "\u4e00\u8d77\u53bb\u56fe\u4e66\u9986\u3002";
        state.sceneTransitionNeeded = true;
        state.confidence = 80;

        SceneState scene = new SceneState();
        scene.location = "\u56fe\u4e66\u9986";
        scene.sceneSummary = "\u4f60\u4eec\u5df2\u7ecf\u5728\u56fe\u4e66\u9986\u91cc\u5b89\u9759\u5750\u4e0b\u6765\u3002";
        scene.interactionMode = "face_to_face";

        DialogueContinuityState settled = service.settleSceneTransitionIfArrived(state, scene, Instant.now().toString());
        DialogueContinuityState next = service.update(
                settled,
                "\u53ef\u4ee5\uff0c\u90a3\u4f60\u8bb2\u8bb2\u5427",
                List.of(
                        new ConversationSnippet("user", "\u6211\u4e0d\u77e5\u9053\u8be5\u54ea\u4e00\u4e9b\u4ec0\u4e48\u4e86"),
                        new ConversationSnippet("assistant", "\u6709\u65f6\u5019\u5b89\u9759\u5730\u5750\u5728\u4e00\u8d77\u4e5f\u662f\u4e00\u79cd\u4eab\u53d7\uff0c\u8981\u4e0d\u6211\u8bb2\u4e2a\u5fc3\u7406\u5b66\u8bfe\u4e0a\u7684\u5c0f\u77e5\u8bc6\uff1f")
                ),
                scene,
                Instant.now().toString()
        );

        assertTrue(!next.sceneTransitionNeeded, "accepting a talk topic in the library should not restart library transition");
        assertTrue(!String.valueOf(next.currentObjective).contains("\u56fe\u4e66\u9986"), "arrived library movement objective should not be reused");
        assertTrue(!String.valueOf(next.acceptedPlan).contains("\u56fe\u4e66\u9986"), "arrived library accepted plan should not be reused");
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

    private static void shouldIncludePendingRepairCueInPrompt() throws Exception {
        Path root = Files.createTempDirectory("campus-pulse-repair-cue");
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
        Method method = ExpressiveLlmClient.class.getDeclaredMethod("buildSystemPrompt", LlmRequest.class);
        method.setAccessible(true);

        AgentProfile agent = Domain.buildAgents().stream()
                .filter(item -> "healing".equals(item.id))
                .findFirst()
                .orElseThrow();
        RelationshipState relationshipState = new RelationshipState();
        relationshipState.relationshipStage = "\u521d\u8bc6";
        PendingRepairCue cue = new PendingRepairCue();
        cue.type = "scene_repair";
        cue.instruction = "\u521a\u521a\u628a\u91cd\u70b9\u5e26\u56de\u573a\u666f\u4e86\uff0c\u672c\u8f6e\u8f7b\u8f7b\u4fee\u6b63\u540e\u7ee7\u7eed\u8bb2\u5fc3\u7406\u5b66\u5c0f\u77e5\u8bc6\u3002";
        cue.confidence = 88;
        cue.createdAt = Instant.now().toString();

        LlmRequest request = new LlmRequest(
                agent,
                relationshipState,
                List.of(),
                "",
                "none",
                "",
                "neutral",
                "steady_flow",
                "",
                null,
                "\u7ee7\u7eed",
                null,
                null,
                "\u56fe\u4e66\u9986\uff0c\u9762\u5bf9\u9762\u7ee7\u7eed\u804a\u5929\u3002",
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
                null,
                cue
        );

        String prompt = String.valueOf(method.invoke(client, request));

        assertTrue(prompt.contains("\u4e0a\u4e00\u8f6e\u7406\u89e3\u4fee\u6b63"), "prompt should include pending repair cue");
        assertTrue(prompt.contains("\u4e0d\u8981\u63d0\u6a21\u578b"), "repair cue should hide internal mechanisms");
        assertTrue(prompt.contains("\u5fc3\u7406\u5b66\u5c0f\u77e5\u8bc6"), "repair cue instruction should be passed to the main reply");
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
