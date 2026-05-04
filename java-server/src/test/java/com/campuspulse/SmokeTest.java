package com.campuspulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
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
        shouldExportSessionDebugSnapshot(orchestrator);
        shouldPersistVisitorContext(orchestrator);
        shouldTriggerPresenceHeartbeat(orchestrator);
        shouldHoldPlotOnEarlyTurns(orchestrator);
        shouldTransferSceneBeforePlot(orchestrator);
        shouldKeepPlotPushInsideCurrentConversation(orchestrator);
        shouldPreferWeightedEvent(orchestrator);
        shouldBlockStageJumpByTrustGate(orchestrator);
        shouldUseDimensionGateForInternalStageUpdates(orchestrator);
        shouldApplyOnlyMeaningfulQuickJudgeCorrections();
        shouldKeepSceneSummaryWhenAlreadyAtTarget();
        shouldAnchorReferentialFollowupBeforeSearch();
        shouldKeepFuturePlanOutOfCurrentSceneTransition();
        shouldBuildTransitionLineFromStructuredSceneCandidate();
        shouldPreferDynamicStoryEventCandidates();
        shouldOfferChoiceInteraction(orchestrator);
        shouldApplyChoiceOutcome(orchestrator);
        shouldSoftBlockUnsafeInput(orchestrator);
        shouldClassifyReplyConfusionAsMetaRepair();
        PromptSlotSimulationTest.run();
        MemoryPaneReplayTest.run();
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

    private static void shouldExportSessionDebugSnapshot(ChatOrchestrator orchestrator) throws Exception {
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession((String) visitor.get("visitorId"), "healing");
        orchestrator.sendMessage(Map.of(
                "visitorId", visitor.get("visitorId"),
                "sessionId", session.get("sessionId"),
                "agentId", "healing",
                "userMessage", "\u6211\u4eca\u5929\u6709\u70b9\u7d2f\uff0c\u4f46\u8fd8\u662f\u60f3\u548c\u4f60\u804a\u4e00\u4f1a\u3002"
        ));

        Map<String, Object> snapshot = orchestrator.exportSessionDebugData(String.valueOf(session.get("sessionId")));
        assertTrue("session_debug_snapshot".equals(String.valueOf(snapshot.get("purpose"))), "debug export should identify its purpose");
        assertTrue(castMap(snapshot.get("summary")).containsKey("quickJudgeStatus"), "debug export should include summary signals");
        List<Object> turnTimeline = Json.asArray(snapshot.get("turnTimeline"));
        assertTrue(!turnTimeline.isEmpty(), "debug export should include turn timeline");
        boolean foundPromptSlots = false;
        for (Object turn : turnTimeline) {
            for (Object reply : Json.asArray(castMap(turn).get("assistantReplies"))) {
                Map<String, Object> assistantReply = castMap(reply);
                if (!Json.asArray(assistantReply.get("promptSlotsUsed")).isEmpty()) {
                    assertTrue(castMap(assistantReply.get("promptSlotSummary")).containsKey("included"), "debug export should include prompt slot summary");
                    foundPromptSlots = true;
                    break;
                }
            }
            if (foundPromptSlots) {
                break;
            }
        }
        assertTrue(foundPromptSlots, "debug export should include prompt slots used");
        assertTrue(castMap(snapshot.get("latestSignals")).containsKey("turnContext"), "debug export should include latest signal snapshot");
        Map<String, Object> sessionPayload = castMap(snapshot.get("session"));
        assertTrue(sessionPayload.containsKey("history"), "debug export should include full session payload");
        List<Object> latestPromptSlots = Json.asArray(sessionPayload.get("lastPromptSlotsUsed"));
        assertTrue(!latestPromptSlots.isEmpty(), "session payload should expose latest prompt stack");
        boolean foundSessionMemorySlot = false;
        for (Object slotValue : latestPromptSlots) {
            Map<String, Object> slot = castMap(slotValue);
            if ("memory.session".equals(String.valueOf(slot.get("key")))) {
                foundSessionMemorySlot = true;
                assertTrue(Boolean.TRUE.equals(slot.get("included")), "memory.session should be included in latest prompt stack");
                break;
            }
        }
        assertTrue(foundSessionMemorySlot, "latest prompt stack should include memory.session");
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
        assertTrue(result.get("proactive_message") != null
                        || "awaiting_user_answer".equals(String.valueOf(result.get("blocked_reason"))),
                "presence heartbeat should emit or intentionally wait for user answer");
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

    private static void shouldUseDimensionGateForInternalStageUpdates(ChatOrchestrator orchestrator) throws Exception {
        Method method = ChatOrchestrator.class.getDeclaredMethod("relationshipStageForState", RelationshipState.class, String.class);
        method.setAccessible(true);

        RelationshipState oneDimensional = new RelationshipState();
        oneDimensional.closeness = 90;
        oneDimensional.trust = 0;
        oneDimensional.resonance = 0;
        oneDimensional.affectionScore = 90;
        String gated = String.valueOf(method.invoke(orchestrator, oneDimensional, "\u521d\u8bc6"));
        assertTrue("\u521d\u8bc6".equals(gated), "internal stage update should require all three dimensions, not only total score");

        RelationshipState allHigh = new RelationshipState();
        allHigh.closeness = 30;
        allHigh.trust = 30;
        allHigh.resonance = 30;
        allHigh.affectionScore = 90;
        String singleStep = String.valueOf(method.invoke(orchestrator, allHigh, "\u521d\u8bc6"));
        assertTrue("\u5347\u6e29".equals(singleStep), "internal stage update should advance at most one stage per turn");
    }

    private static void shouldApplyOnlyMeaningfulQuickJudgeCorrections() {
        QuickJudgeDecision noop = new QuickJudgeDecision(
                true,
                "",
                "none",
                "neutral",
                "",
                false,
                "",
                "none",
                90,
                "noop"
        );
        assertTrue(!noop.shouldApply(), "quick judge noop values should not count as an applied correction");

        QuickJudgeDecision sceneOnly = new QuickJudgeDecision(
                true,
                "",
                "",
                "",
                "",
                true,
                "",
                "",
                90,
                "scene_only"
        );
        assertTrue(sceneOnly.shouldApply(), "scene transition correction should apply even without objective text");

        QuickJudgeDecision nextMoveOnly = new QuickJudgeDecision(
                true,
                "",
                "",
                "",
                "",
                false,
                "\u5148\u56de\u7b54\u7528\u6237\u521a\u624d\u7684\u95ee\u9898",
                "",
                90,
                "next_move_only"
        );
        assertTrue(nextMoveOnly.shouldApply(), "nextBestMove-only correction should apply");
    }

    private static void shouldKeepSceneSummaryWhenAlreadyAtTarget() {
        SceneDirectorService sceneDirector = new SceneDirectorService();
        SceneState current = new SceneState();
        current.location = "\u56fe\u4e66\u9986";
        current.interactionMode = "face_to_face";
        current.sceneSummary = "\u4f60\u4eec\u5df2\u7ecf\u5728\u56fe\u4e66\u9986\u91cc\u5b89\u9759\u5730\u804a\u7740\u3002";
        current.updatedAt = "2026-04-26T00:00:00Z";

        SceneState next = sceneDirector.evolve(
                current,
                "\u6211\u4eec\u53bb\u56fe\u4e66\u9986\u5427",
                null,
                null,
                null,
                3,
                "2026-04-26T00:01:00Z"
        );

        assertTrue("\u56fe\u4e66\u9986".equals(next.location), "duplicate scene move should keep current location");
        assertTrue(current.sceneSummary.equals(next.sceneSummary), "duplicate scene move should keep existing summary");
    }

    private static void shouldAnchorReferentialFollowupBeforeSearch() {
        TurnUnderstandingService understandingService = new TurnUnderstandingService();
        SearchDecisionService searchDecisionService = new SearchDecisionService();
        IntentState intentState = new IntentState();
        intentState.primaryIntent = "question_check";
        DialogueContinuityState continuity = new DialogueContinuityState();
        SceneState sceneState = new SceneState();
        List<ConversationSnippet> recentContext = List.of(
                new ConversationSnippet("assistant", "好呀，我觉得那首诗有意思的地方，是它把离别写得很轻，却让人后劲很深。")
        );

        TurnUnderstandingState understanding = understandingService.understand(
                "这是什么原因呢？",
                recentContext,
                intentState,
                continuity,
                sceneState,
                IsoTimes.now()
        );
        assertTrue(understanding.referentialFollowup, "deictic follow-up should anchor to the last assistant message");
        assertTrue(understanding.referentAnchor.contains("离别"), "referent anchor should preserve the last assistant topic");

        TurnContext turnContext = new TurnContext();
        turnContext.referentialFollowup = understanding.referentialFollowup;
        turnContext.referentAnchor = understanding.referentAnchor;
        SearchDecision anchored = searchDecisionService.decide("这是什么原因呢？", "user_turn", sceneState, intentState, turnContext);
        assertTrue(!anchored.enabled && "referential_followup".equals(anchored.reason), "anchored follow-up should not trigger unrelated factual search");

        TurnUnderstandingState standaloneUnderstanding = understandingService.understand(
                "什么是认知失调？",
                recentContext,
                intentState,
                continuity,
                sceneState,
                IsoTimes.now()
        );
        assertTrue(!standaloneUnderstanding.referentialFollowup, "standalone factual question should not be treated as a deictic follow-up");
        SearchDecision standalone = searchDecisionService.decide("什么是认知失调？", "user_turn", sceneState, intentState, null);
        assertTrue(standalone.enabled && "factual".equals(standalone.reason), "standalone factual question should still be searchable");
    }

    private static void shouldBuildTransitionLineFromStructuredSceneCandidate() {
        PlotDirectorAgentService director = new PlotDirectorAgentService();
        TurnContext context = new TurnContext();
        context.sceneMoveKind = "move_to";
        context.sceneMoveTarget = "图书馆";

        PlotDirectorAgentDecision decision = director.decide(
                "我们去图书馆吧",
                "user_turn",
                3,
                0,
                7,
                true,
                0,
                0,
                new EmotionState(),
                new RelationshipState(),
                new MemorySummary(),
                context
        );

        assertTrue("transition_only".equals(decision.action), "explicit scene move should stay transition-only");
        assertTrue(decision.transitionLine.contains("图书馆"), "transition line should use structured scene target");
        assertTrue(!decision.transitionLine.contains("脚步都不自觉放轻"), "transition line should no longer come from hard-coded location template");
    }

    private static void shouldKeepFuturePlanOutOfCurrentSceneTransition() {
        SceneMoveIntentService sceneMoveIntentService = new SceneMoveIntentService();
        DialogueContinuityService continuityService = new DialogueContinuityService();
        SceneState sceneState = new SceneState();
        sceneState.location = "聊天现场";
        sceneState.interactionMode = "face_to_face";

        SceneMoveIntent futurePlan = sceneMoveIntentService.classify(
                "多呆几天，我们一起去玩怎么样？",
                new DialogueContinuityState(),
                sceneState
        );
        assertTrue(!futurePlan.shouldMove, "future travel proposal should not change the current scene");
        assertTrue("no_change".equals(futurePlan.moveType), "future travel proposal should stay in no_change scene axis");
        assertTrue(!"聊天现场".equals(futurePlan.targetLocation), "future travel proposal should not use current scene as fake target");

        DialogueContinuityState continuity = continuityService.update(
                new DialogueContinuityState(),
                "多呆几天，我们一起去玩怎么样？",
                List.of(),
                sceneState,
                IsoTimes.now()
        );
        assertTrue(!continuity.sceneTransitionNeeded, "future travel plan should not require immediate scene transition");
        assertTrue(continuity.currentObjective.contains("出去玩"), "future travel plan should still become shared objective");

        SceneMoveIntent concreteMove = sceneMoveIntentService.classify(
                "我们去图书馆吧",
                new DialogueContinuityState(),
                sceneState
        );
        assertTrue(concreteMove.shouldMove, "concrete local destination should still change scene");
        assertTrue("图书馆".equals(concreteMove.targetLocation), "concrete local destination should keep target");

        SceneMoveIntent externalDestination = sceneMoveIntentService.classify(
                "那就去日光山谷吧",
                continuity,
                sceneState
        );
        assertTrue(!externalDestination.shouldMove, "external future destination should update plan without current scene jump");
        assertTrue("日光山谷".equals(externalDestination.targetLocation), "external destination should be retained as plan target");
    }

    private static void shouldPreferDynamicStoryEventCandidates() {
        EventEngine engine = new EventEngine();
        AgentProfile agent = new AgentConfigService().getAgentById("healing");
        SessionRecord session = new SessionRecord();
        session.userTurnCount = 0;
        session.relationshipState = new RelationshipState();
        session.relationshipState.affectionScore = 0;
        session.relationshipState.relationshipStage = "初识";
        session.memorySummary = new MemorySummary();
        session.storyEventProgress = new StoryEventProgress();
        session.dynamicStoryEvents = new java.util.ArrayList<>();
        session.dynamicStoryEvents.add(Domain.event(
                "dynamic_test_library",
                "临场图书馆候选",
                1,
                0,
                "顺着用户刚刚提出的图书馆方向自然生成的临场事件。",
                List.of("图书馆"),
                2
        ));

        StoryEvent event = engine.findTriggeredEvent(agent, session, "我们去图书馆吧");
        assertTrue(event != null && event.id.startsWith("dynamic_"), "dynamic story candidate should be preferred over static keyword pool");
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

    private static void shouldClassifyReplyConfusionAsMetaRepair() {
        IntentInferenceService service = new IntentInferenceService();
        IntentState confused = service.infer(
                "这是什么情况？",
                List.of(new ConversationSnippet("assistant", "那……你觉得那边会有什么不一样的风景呢？")),
                new RelationshipState(),
                new SceneState(),
                new RelationalTensionState(),
                new MemorySummary(),
                IsoTimes.now()
        );
        assertTrue("meta_repair".equals(confused.primaryIntent), "reply confusion should be classified as meta_repair");

        IntentState weatherQuestion = service.infer(
                "今天天气什么情况？",
                List.of(new ConversationSnippet("assistant", "晚上操场人少，比较安静。")),
                new RelationshipState(),
                new SceneState(),
                new RelationalTensionState(),
                new MemorySummary(),
                IsoTimes.now()
        );
        assertTrue(!"meta_repair".equals(weatherQuestion.primaryIntent), "weather question should not be classified as meta_repair");
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
