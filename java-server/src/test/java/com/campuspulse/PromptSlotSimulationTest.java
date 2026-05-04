package com.campuspulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

final class PromptSlotSimulationTest {
    private PromptSlotSimulationTest() {
    }

    static void run() throws Exception {
        Path root = Files.createTempDirectory("campus-pulse-prompt-slot-test");
        AppConfig config = new AppConfig(
                0,
                root,
                root.resolve("public"),
                root.resolve("runtime").resolve("state.bin"),
                7L * 24 * 60 * 60 * 1000,
                "",
                "",
                "mock",
                Duration.ofSeconds(5)
        );
        AgentProfile agent = new AgentConfigService().getAgentById("healing");
        ExpressiveLlmClient client = new ExpressiveLlmClient(config);

        shouldComposeSlotsForOrdinaryTurn(client, agent);
        shouldKeepStructuredRepairContext(client, agent);
        shouldComposeWorldInfoCandidateSlot(client, agent);
        shouldTrimLowPrioritySlotsWhenMemoryIsHuge(client, agent);
        shouldRenderSlotPositionsDepthAndRoles();
    }

    private static void shouldComposeSlotsForOrdinaryTurn(ExpressiveLlmClient client, AgentProfile agent) throws Exception {
        LlmResponse response = client.generateReply(baseRequest(
                agent,
                "今天有点累，不过见到你就放松一点。",
                "用户说过喜欢雨天和图书馆，也希望聊天节奏不要太急。",
                "strong",
                "用户喜欢雨天图书馆。",
                null,
                "normal"
        ));

        assertIncluded(response, "persona.identity");
        assertIncluded(response, "persona.voice");
        assertIncluded(response, "memory.summary");
        assertIncluded(response, "memory.recall");
        assertIncluded(response, "memory.session");
        assertIncluded(response, "memory.directive");
        assertIncluded(response, "turn.understanding");
        assertIncluded(response, "response.rules");
        assertTrue(response.promptSlotsUsed.stream().noneMatch(slot -> slot != null && !slot.included),
                "ordinary prompt should not trim slots");
    }

    private static void shouldKeepStructuredRepairContext(ExpressiveLlmClient client, AgentProfile agent) throws Exception {
        PendingRepairCue repairCue = new PendingRepairCue();
        repairCue.type = "late_quick_judge";
        repairCue.instruction = "上一轮把用户的接受误读成重新发起计划，本轮先轻修正再回答。";
        repairCue.confidence = 86;
        repairCue.createdAt = IsoTimes.now();

        LlmResponse response = client.generateReply(baseRequest(
                agent,
                "不是，我是说就按刚刚那个来。",
                "用户已经接受一起去图书馆的计划。",
                "strong",
                "用户接受了刚刚的计划。",
                repairCue,
                "repair"
        ));

        ContextSlot slot = findSlot(response, "turn.understanding");
        assertTrue(slot != null && slot.included, "repair turn should include structured understanding");
        assertTrue(slot.content.contains("late_quick_judge") || slot.content.contains("重新发起计划"),
                "repair cue should be visible to the main reply slot");
    }

    private static void shouldComposeWorldInfoCandidateSlot(ExpressiveLlmClient client, AgentProfile agent) throws Exception {
        WorldInfoActivation activation = new WorldInfoActivation();
        activation.id = "healing.library-window";
        activation.title = "林晚栀的图书馆窗边";
        activation.content = "林晚栀在图书馆窗边会更放松。";
        activation.source = "keyword_signal:图书馆; world_info_is_candidate_not_order";
        activation.matchedKeywords.add("图书馆");
        activation.score = 92;
        activation.priority = 72;
        activation.depth = 3;
        activation.role = "system";
        activation.eventCandidate = true;

        LlmResponse response = client.generateReply(baseRequest(
                agent,
                "我们去图书馆窗边坐会儿吧。",
                "用户喜欢雨天图书馆。",
                "strong",
                "用户喜欢雨天图书馆。",
                null,
                "world_info",
                List.of(activation)
        ));

        ContextSlot slot = findSlot(response, "world.info");
        assertTrue(slot != null && slot.included, "world info candidate should be included");
        assertTrue(slot.content.contains("候选背景") && slot.content.contains("不是当前轮硬命令"),
                "world info slot should describe advisory semantics");
    }

    private static void shouldTrimLowPrioritySlotsWhenMemoryIsHuge(ExpressiveLlmClient client, AgentProfile agent) throws Exception {
        String hugeMemory = repeat("用户曾经提到一个很长的背景细节，需要被谨慎使用但不能挤掉当前轮边界。", 260);
        LlmResponse response = client.generateReply(baseRequest(
                agent,
                "你还记得我刚刚说的吗？",
                hugeMemory,
                "weak",
                "刚刚说过今天有点累。",
                null,
                "long_memory"
        ));

        assertExcluded(response, "memory.summary");
        assertIncluded(response, "persona.identity");
        assertIncluded(response, "turn.understanding");
        assertIncluded(response, "response.rules");
        assertIncluded(response, "memory.recall");
        assertIncluded(response, "memory.session");
    }

    private static void shouldRenderSlotPositionsDepthAndRoles() {
        PromptBundle bundle = PromptBundle.empty();
        bundle.add(new ContextSlot("system.rule", "system-rule", "SYSTEM", "system", 0, 100, 1));
        bundle.add(new ContextSlot("before.memory", "before-memory", "BEFORE_HISTORY", "system", 0, 90, 1));
        bundle.add(new ContextSlot("inside.note", "inside-note", "IN_HISTORY", "assistant", 1, 80, 1));
        bundle.add(new ContextSlot("after.directive", "after-directive", "AFTER_HISTORY", "system", 0, 85, 1));

        List<PromptMessage> messages = bundle.renderMessages(
                List.of(
                        new ConversationSnippet("user", "history-user"),
                        new ConversationSnippet("assistant", "history-assistant")
                ),
                "current-user"
        );

        assertEquals("system", messages.get(0).role, "system slot should render first");
        assertEquals("system", messages.get(1).role, "before-history slot should keep its role");
        assertEquals("history-user", messages.get(2).content, "history should follow before-history slots");
        assertEquals("inside-note", messages.get(3).content, "depth=1 should insert before the latest history message");
        assertEquals("history-assistant", messages.get(4).content, "latest history should remain after depth=1 slot");
        assertEquals("after-directive", messages.get(5).content, "after-history slot should render before current user cue");
        assertEquals("current-user", messages.get(6).content, "current user cue should render last");
    }

    private static LlmRequest baseRequest(
            AgentProfile agent,
            String userMessage,
            String longTermSummary,
            String recalledTier,
            String recalledText,
            PendingRepairCue repairCue,
            String scenario
    ) {
        return baseRequest(agent, userMessage, longTermSummary, recalledTier, recalledText, repairCue, scenario, List.of());
    }

    private static LlmRequest baseRequest(
            AgentProfile agent,
            String userMessage,
            String longTermSummary,
            String recalledTier,
            String recalledText,
            PendingRepairCue repairCue,
            String scenario,
            List<WorldInfoActivation> worldInfoActivations
    ) {
        RelationshipState relationship = new RelationshipState();
        relationship.relationshipStage = "初识";
        relationship.affectionScore = 12;
        relationship.stageProgressHint = "stable";
        relationship.relationshipFeedback = "关系轻微升温";

        TimeContext time = new TimeContext();
        time.timezone = "Asia/Shanghai";
        time.localTime = "20:30";
        time.dayPart = "night";
        time.frame = "校园夜晚";

        WeatherContext weather = new WeatherContext();
        weather.city = "长沙";
        weather.summary = "小雨";
        weather.temperatureC = 18;
        weather.live = false;

        SceneState scene = new SceneState();
        scene.location = "图书馆";
        scene.subLocation = "靠窗座位";
        scene.interactionMode = "chat";
        scene.timeOfScene = "night";
        scene.weatherMood = "rainy";
        scene.sceneSummary = "两人坐在图书馆靠窗的位置，雨声很轻。";

        MemoryUsePlan memoryUsePlan = new MemoryUsePlan();
        memoryUsePlan.useMode = "light";
        memoryUsePlan.relevanceReason = "与当前情绪和地点有关";
        memoryUsePlan.selectedMemories.add(recalledText);
        memoryUsePlan.callbackCandidates.add("雨天图书馆");
        memoryUsePlan.mergedMemoryText = recalledText;

        EmotionState emotion = new EmotionState();
        emotion.warmth = 55;
        emotion.safety = 52;
        emotion.longing = 20;
        emotion.initiative = 35;
        emotion.vulnerability = 24;
        emotion.currentMood = "soft";

        IntentState intent = new IntentState();
        intent.primaryIntent = "repair".equals(scenario) ? "meta_repair" : "light_chat";
        intent.secondaryIntent = "memory_recall";
        intent.emotion = "soft";
        intent.clarity = "medium";
        intent.needsEmpathy = true;
        intent.rationale = "simulated prompt slot turn";

        ResponsePlan responsePlan = new ResponsePlan();
        responsePlan.firstMove = "answer_first";
        responsePlan.coreTask = "acknowledge_and_continue";
        responsePlan.initiativeLevel = "medium";
        responsePlan.responseLength = "short";
        responsePlan.dialogueMode = "gentle_expand";
        responsePlan.shouldReferenceMemory = true;
        responsePlan.allowFollowupQuestion = true;

        UncertaintyState uncertainty = new UncertaintyState();
        uncertainty.level = "low";
        uncertainty.reason = "simulation";

        InitiativeDecision initiative = new InitiativeDecision();
        initiative.allowed = true;
        initiative.action = "gentle_expand";
        initiative.level = "medium";
        initiative.reason = "simulation";

        RealityEnvelope reality = new RealityEnvelope();
        reality.timeTruth = "night";
        reality.weatherTruth = "rainy";
        reality.sceneTruth = "library";
        reality.interactionTruth = "same_chat";

        RelationalTensionState tension = new RelationalTensionState();
        tension.repairReadiness = 70;

        PlotGateDecision plotGate = new PlotGateDecision();
        plotGate.allowed = false;
        plotGate.blockedReason = "simulation holds plot";

        DialogueContinuityState continuity = new DialogueContinuityState();
        continuity.currentObjective = "继续当前聊天";
        continuity.acceptedPlan = "repair".equals(scenario) ? "按刚刚的计划继续" : "";
        continuity.nextBestMove = "先承接用户，再轻轻推进一句";
        continuity.confidence = 82;

        TurnContext turnContext = new TurnContext();
        turnContext.primaryIntent = intent.primaryIntent;
        turnContext.secondaryIntent = intent.secondaryIntent;
        turnContext.userEmotion = intent.emotion;
        turnContext.replySource = "user_turn";
        turnContext.sceneLocation = scene.location;
        turnContext.interactionMode = scene.interactionMode;
        turnContext.sceneMoveKind = "stay";
        turnContext.userReplyAct = "repair".equals(scenario) ? "accept_previous_plan" : "emotion_share";
        turnContext.userReplyActConfidence = "repair".equals(scenario) ? 86 : 70;
        turnContext.continuityObjective = continuity.currentObjective;
        turnContext.continuityAcceptedPlan = continuity.acceptedPlan;
        turnContext.continuityNextBestMove = continuity.nextBestMove;

        SessionMemoryPaneState memoryPane = new SessionMemoryPaneState();
        memoryPane.pinnedFacts.add("偏好：用户喜欢雨天图书馆");
        memoryPane.workingFacts.add("本轮用户输入：" + userMessage);
        memoryPane.pendingPlans.add("下一步承接：" + continuity.nextBestMove);
        memoryPane.sceneAnchors.add("地点：图书馆 / 靠窗座位");
        if (repairCue != null) {
            memoryPane.repairNotes.add("待自然修正：" + repairCue.instruction);
        }
        memoryPane.directorNote = "replySource=user_turn；plotAction=hold；plotPressure=2";
        memoryPane.updatedAt = IsoTimes.now();

        return new LlmRequest(
                agent,
                relationship,
                List.of(
                        new ConversationSnippet("user", "今天有点累。"),
                        new ConversationSnippet("assistant", "嗯，我听着。")
                ),
                longTermSummary,
                recalledTier,
                recalledText,
                "soft",
                "steady_flow",
                "先回答当前轮，再自然承接记忆。",
                null,
                userMessage,
                time,
                weather,
                scene.sceneSummary,
                scene,
                memoryUsePlan,
                emotion,
                "user_turn",
                null,
                "",
                intent,
                responsePlan,
                uncertainty,
                initiative,
                List.of(),
                reality,
                tension,
                plotGate,
                continuity,
                repairCue,
                turnContext,
                memoryPane,
                worldInfoActivations
        );
    }

    private static ContextSlot findSlot(LlmResponse response, String key) {
        for (ContextSlot slot : response.promptSlotsUsed) {
            if (slot != null && key.equals(slot.key)) {
                return slot;
            }
        }
        return null;
    }

    private static void assertIncluded(LlmResponse response, String key) {
        ContextSlot slot = findSlot(response, key);
        assertTrue(slot != null, "missing prompt slot: " + key);
        assertTrue(slot.included, "prompt slot should be included: " + key);
    }

    private static void assertExcluded(LlmResponse response, String key) {
        ContextSlot slot = findSlot(response, key);
        assertTrue(slot != null, "missing prompt slot: " + key);
        assertTrue(!slot.included, "prompt slot should be excluded by budget: " + key);
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
