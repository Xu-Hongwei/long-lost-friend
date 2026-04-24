package com.campuspulse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class ExpressiveLlmClient extends CompositeLlmClient {
    private static final String SCENE_OPEN = "[[SCENE]]";
    private static final String SCENE_CLOSE = "[[/SCENE]]";
    private static final String ACTION_OPEN = "[[ACTION]]";
    private static final String ACTION_CLOSE = "[[/ACTION]]";

    private static final class ReplyParts {
        final String replyText;
        final String sceneText;
        final String actionText;
        final String speechText;

        ReplyParts(String replyText, String sceneText, String actionText, String speechText) {
            this.replyText = replyText;
            this.sceneText = sceneText;
            this.actionText = actionText;
            this.speechText = speechText;
        }
    }

    private final AppConfig config;
    ExpressiveLlmClient(AppConfig config) {
        super(config);
        this.config = config;
    }

    @Override
    public LlmResponse generateReply(LlmRequest request) throws Exception {
        if (config.llmApiKey == null || config.llmApiKey.isBlank()) {
            return generateLocalReply(request);
        }
        try {
            return generateRemoteReply(request);
        } catch (Exception error) {
            LlmResponse fallback = generateLocalReply(request);
            return new LlmResponse(
                    fallback.replyText,
                    fallback.emotionTag,
                    "fallback",
                    fallback.tokenUsage,
                    error.getClass().getSimpleName(),
                    true,
                    "mock"
            );
        }
    }

    @Override
    String buildFallbackReply(AgentProfile agent, String reason) {
        if (agent == null) {
            return "刚才有一点小问题（" + reason + "），不过现在已经可以继续了。";
        }
        Map<String, String> fallbackMap = Map.of(
                "healing", "刚才像卡了一下，不过没关系。你把最想说的那一句继续交给我，我们从那里接回来。",
                "lively", "刚才信号像打了个结，不过现在顺回来了。来，把后半句也给我。",
                "cool", "刚才中断了一下。现在恢复了，你继续，我在。",
                "artsy", "刚才那段像被风吹散了一点，但没关系，我们还能把它慢慢捡回来。",
                "sunny", "刚才掉了一拍，现在接上了。来，我们继续，不用重新鼓起劲。"
        );
        return fallbackMap.getOrDefault(agent.id, "刚才有一点小问题（" + reason + "），不过现在已经可以继续了。");
    }

    LlmResponse generateLocalReply(LlmRequest request) {
        String rawReply = isHeartbeatProactive(request.replySource) ? buildProactiveReply(request) : buildReactiveReply(request);
        ReplyParts reply = shapeStructuredReply(rawReply);
        return new LlmResponse(
                reply.replyText,
                reply.sceneText,
                reply.actionText,
                reply.speechText,
                inferEmotionTag(request),
                "mock",
                Math.max(40, reply.replyText.length()),
                null,
                false,
                "mock"
        );
    }

    LlmResponse generateRemoteReply(LlmRequest request) throws Exception {
        String systemPrompt = buildSystemPrompt(request);
        if (request.searchContext != null && !request.searchContext.isBlank()) {
            return generateRemoteReplyWithSearch(request, systemPrompt);
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (ConversationSnippet snippet : request.shortTermContext) {
            messages.add(Map.of("role", snippet.role, "content", snippet.text));
        }
        messages.add(Map.of("role", "user", "content", buildUserCue(request)));

        String body = Json.stringify(Map.of(
                "model", config.llmModel,
                "temperature", 0.88,
                "messages", messages
        ));

        HttpURLConnection connection = (HttpURLConnection) URI.create(config.llmBaseUrl + "/chat/completions").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout((int) config.llmTimeout.toMillis());
        connection.setReadTimeout((int) config.llmTimeout.toMillis());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + config.llmApiKey);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("LLM request failed: " + statusCode);
        }

        String responseBody = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> payload = Json.asObject(Json.parse(responseBody));
        List<Object> choices = Json.asArray(payload.get("choices"));
        Map<String, Object> choice = Json.asObject(choices.get(0));
        Map<String, Object> message = Json.asObject(choice.get("message"));
        Map<String, Object> usage = payload.get("usage") == null ? Map.of() : Json.asObject(payload.get("usage"));
        ReplyParts reply = shapeStructuredReply(Json.asString(message.get("content")).trim());

        return new LlmResponse(
                reply.replyText,
                reply.sceneText,
                reply.actionText,
                reply.speechText,
                inferEmotionTag(request),
                "remote",
                Json.asInt(usage.get("total_tokens"), reply.replyText.length()),
                null,
                false,
                "remote"
        );
    }

    LlmResponse generateRemoteReplyWithSearch(LlmRequest request, String systemPrompt) throws Exception {
        List<Object> input = new ArrayList<>();
        input.add(Map.of(
                "role", "system",
                "content", List.of(Map.of("type", "input_text", "text", systemPrompt))
        ));
        input.add(Map.of(
                "role", "user",
                "content", List.of(Map.of(
                        "type", "input_text",
                        "text", buildUserCue(request) + "\n联网补充上下文：" + request.searchContext
                ))
        ));

        String body = Json.stringify(Map.of(
                "model", config.llmModel,
                "input", input,
                "tools", List.of(Map.of("type", "web_search"))
        ));

        HttpURLConnection connection = (HttpURLConnection) URI.create(config.llmBaseUrl + "/responses").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout((int) config.llmTimeout.toMillis());
        connection.setReadTimeout((int) config.llmTimeout.toMillis());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + config.llmApiKey);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("LLM responses request failed: " + statusCode);
        }

        String responseBody = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> payload = Json.asObject(Json.parse(responseBody));
        Map<String, Object> usage = payload.get("usage") == null ? Map.of() : Json.asObject(payload.get("usage"));
        ReplyParts reply = shapeStructuredReply(extractResponsesText(payload).trim());

        return new LlmResponse(
                reply.replyText,
                reply.sceneText,
                reply.actionText,
                reply.speechText,
                inferEmotionTag(request),
                "remote",
                Json.asInt(usage.get("total_tokens"), reply.replyText.length()),
                null,
                false,
                "remote"
        );
    }

    private String buildReactiveReply(LlmRequest request) {
        String policySpeech = buildPolicySpeech(request);
        boolean hasQuestion = containsQuestion(request.userMessage);
        String directAnswer = buildDirectAnswer(request);
        boolean answeredDirectly = !directAnswer.isBlank();

        String sceneLine = buildSceneLine(request, hasQuestion, answeredDirectly);
        String actionLine = buildActionLine(request, hasQuestion, answeredDirectly);
        if (!policySpeech.isBlank()) {
            List<String> guardedParts = new ArrayList<>();
            if (!sceneLine.isBlank()) {
                guardedParts.add(wrapScene(sceneLine));
            }
            if (!actionLine.isBlank()) {
                guardedParts.add(wrapAction(actionLine));
            }
            guardedParts.add(policySpeech);
            return joinNonBlank(limitSentences(guardedParts, actionLine.isBlank() && sceneLine.isBlank() ? 3 : 5));
        }
        List<String> speechParts = new ArrayList<>();
        if (answeredDirectly) {
            speechParts.add(directAnswer);
        } else {
            speechParts.add(buildOpeningLine(request));
        }

        String acknowledgment = buildAcknowledgment(request, answeredDirectly);
        if (!acknowledgment.isBlank()) {
            speechParts.add(acknowledgment);
        }

        if (!answeredDirectly || shouldCarryAfterQuestion(request)) {
            String carryLine = buildCarryLine(request);
            if (!carryLine.isBlank()) {
                speechParts.add(carryLine);
            }
        }

        String followup = answeredDirectly ? buildFollowupAfterAnswer(request) : buildInitiativeLine(request);
        if (!followup.isBlank()) {
            speechParts.add(followup);
        }

        if (!isShortInput(request.userMessage) && !answeredDirectly && !hasQuestion) {
            speechParts.add(buildClosingLine(request));
        }

        List<String> parts = new ArrayList<>();
        if (!sceneLine.isBlank()) {
            parts.add(wrapScene(sceneLine));
        }
        if (!actionLine.isBlank()) {
            parts.add(wrapAction(actionLine));
        }
        parts.addAll(speechParts);
        return joinNonBlank(limitSentences(parts, actionLine.isBlank() && sceneLine.isBlank() ? 4 : 6));
    }

    private String buildProactiveReply(LlmRequest request) {
        String sceneLine = buildSceneLine(request, false, false);
        String actionLine = buildActionLine(request, false, false);
        List<String> parts = new ArrayList<>();
        if (!sceneLine.isBlank()) {
            parts.add(wrapScene(sceneLine));
        }
        if (!actionLine.isBlank()) {
            parts.add(wrapAction(actionLine));
        }
        parts.add(buildPresenceLead(request));

        if (request.memoryUsePlan != null && request.memoryUsePlan.mergedMemoryText != null && !request.memoryUsePlan.mergedMemoryText.isBlank()) {
            String memoryLine = softenMemoryText(request.memoryUsePlan.mergedMemoryText);
            if (!memoryLine.isBlank()) {
                parts.add("刚刚又想起" + memoryLine + "。");
            }
        }

        String followup = buildProactiveFollowup(request);
        if (!followup.isBlank()) {
            parts.add(followup);
        } else {
            parts.add("你不用急着马上接话，我只是想把这一下轻轻递到你手边。");
        }

        return joinNonBlank(limitSentences(parts, 3));
    }

    private String buildSystemPrompt(LlmRequest request) {
        String summaryText = blankTo(request.longTermSummary, "暂无长期记忆。");
        String recallText = blankTo(request.recalledMemoryText, "暂无高相关记忆。");
        String recallTier = blankTo(request.recalledMemoryTier, "none");
        String eventText = request.event == null ? "本轮没有关键事件触发。" : request.event.title + "：" + request.event.theme;
        String timeText = request.timeContext == null
                ? "暂无时间上下文。"
                : request.timeContext.dayPart + "，当地时间 " + request.timeContext.localTime + "。" + blankTo(request.timeContext.frame, "");
        String weatherText = request.weatherContext == null || request.weatherContext.city == null || request.weatherContext.city.isBlank()
                ? "暂无天气上下文。"
                : request.weatherContext.city + " 当前天气：" + blankTo(request.weatherContext.summary, "未获取到天气描述")
                + (request.weatherContext.temperatureC == null ? "" : "，" + request.weatherContext.temperatureC + "°C")
                + (request.weatherContext.live ? "（实时）" : "（缓存或回退）");
        String sceneText = blankTo(request.sceneFrame, "当前场景还在自然铺开。");
        String emotionText = request.emotionState == null
                ? "暂无情感状态。"
                : "warmth=" + request.emotionState.warmth
                + ", safety=" + request.emotionState.safety
                + ", longing=" + request.emotionState.longing
                + ", initiative=" + request.emotionState.initiative
                + ", vulnerability=" + request.emotionState.vulnerability
                + ", mood=" + blankTo(request.emotionState.currentMood, "calm");
        String memoryPlanText = request.memoryUsePlan == null
                ? "暂无记忆使用计划。"
                : "模式=" + blankTo(request.memoryUsePlan.useMode, "hold")
                + "；原因=" + blankTo(request.memoryUsePlan.relevanceReason, "无")
                + "；候选记忆=" + (request.memoryUsePlan.selectedMemories.isEmpty() ? "无" : String.join(" | ", request.memoryUsePlan.selectedMemories));

        String continuityText = buildContinuityText(request.dialogueContinuityState);
        String backstoryText = buildBackstoryText(request.agent);
        String semanticText = buildSemanticRuntimeText(request.semanticDecision);

        return "你正在扮演大学校园恋爱互动游戏中的角色“" + request.agent.name + "”（" + request.agent.archetype + "）。\n"
                + "说话风格：" + request.agent.speechStyle + "\n"
                + "角色具体背景：" + backstoryText + "\n"
                + "喜欢：" + String.join("、", request.agent.likes) + "\n"
                + "雷点：" + String.join("、", request.agent.dislikes) + "\n"
                + "关系推进规则：" + request.agent.relationshipRules + "\n"
                + "当前关系阶段：" + request.relationshipState.relationshipStage + "，当前总好感：" + request.relationshipState.affectionScore + "\n"
                + "当前回复来源：" + blankTo(request.replySource, "user_turn") + "\n"
                + "用户当前情绪：" + blankTo(request.currentUserMood, "neutral") + "\n"
                + "本轮回复节奏：" + blankTo(request.responseCadence, "steady_flow") + "\n"
                + "时间上下文：" + timeText + "\n"
                + "天气上下文：" + weatherText + "\n"
                + "当前场景：" + sceneText + "\n"
                + "角色当前情感状态：" + emotionText + "\n"
                + "长期记忆摘要：" + summaryText + "\n"
                + "优先召回的记忆层级：" + recallTier + "\n"
                + "高相关记忆：" + recallText + "\n"
                + "记忆使用计划：" + memoryPlanText + "\n"
                + "上下文智能层：" + continuityText + "\n"
                + "语义运行时判断：" + semanticText + "\n"
                + "当前事件：" + eventText + "\n"
                + "本轮回应策略：" + blankTo(request.responseDirective, "保持角色一致，顺着当前聊天自然展开。") + "\n"
                + "边界：" + String.join("；", request.agent.boundaries) + "\n"
                + "要求：\n"
                + "1. 只输出角色回复本身。\n"
                + "2. 如果用户这轮问了明确问题，第一句必须先直接回答问题，不能先演动作、先回忆剧情、先抒情。\n"
                + "3. 场景推进和记忆带回只能放在回答当前问题之后，而且要轻，不要抢走当下这句话的重心。\n"
                + "4. 不要永远一问一答，但也不要为了推进剧情而忽略用户刚刚抛来的球。\n"
                + "5. 尽量不用括号动作，不要以“（……）”开头；把动作、视线、环境融进自然口语里。\n"
                + "6. 普通回复通常 2 到 4 句；静默心跳或长聊心跳这类主动消息通常 1 到 2 句，更轻、更像临时想起对方。\n"
                + "7. 如果 reply_source 是 plot_push，表示当前对话里顺势往前走半步，不是切到聊天外发消息，不要写成“给你发消息”“看到你回复”“屏幕那头”这种异步联系口吻。\n"
                + "8. 如果 reply_source 是 silence_heartbeat 或 long_chat_heartbeat，才说明这是角色主动发出的轻消息；要像顺手接话，不像系统提醒。\n"
                + "9. 如果用户输入很短，不要把压力丢回给用户，由你主动给一个容易接的话头。\n"
                + "10. 如果上下文智能层给出当前共同目标、已确认计划、下一句必须承接或禁止违背事实，必须优先遵守；不要重新问已经确认的计划，不要把具体行动泛化成无关闲逛。\n"
                + "11. 必须区分事实归属：用户用“我/我打算/我想/我的计划”表达的内容，默认只属于用户；角色只能回应、追问或记住，不能吸收成自己的设定。\n"
                + "12. 回答角色自己的未来、专业、经历、兴趣时，以角色具体背景和未来规划为准；剧情推进也不能覆盖角色背景事实。\n"
                + "13. 角色背景只作为说话习惯、兴趣、边界和情绪反应的底色，不要像简历一样主动报年龄、专业、出生地。\n"
                + "14. 隐藏经历只能在关系推进、用户主动问起或剧情自然触发时轻轻露出，不要开局全盘托出。\n"
                + "15. 保持中文自然、亲近、连贯，不要写成小说旁白，也不要突然结束话题。";
    }

    private String buildSemanticRuntimeText(SemanticRuntimeDecision decision) {
        if (decision == null) {
            return "暂无语义运行时判断。";
        }
        return "来源=" + blankTo(decision.source, "unknown")
                + "；意图=" + blankTo(decision.primaryIntent, "unknown")
                + "；情绪=" + blankTo(decision.emotion, "neutral")
                + "；场景=" + blankTo(decision.sceneLocation, "")
                + "；互动模式=" + blankTo(decision.interactionMode, "")
                + "；搜索=" + blankTo(decision.searchMode, "skip")
                + "；直答策略=" + blankTo(decision.directAnswerPolicy, "none")
                + "；氛围=" + blankTo(decision.sceneAtmosphere, "")
                + "；原因=" + blankTo(decision.reason, "");
    }

    private String buildBackstoryText(AgentProfile agent) {
        if (agent == null || agent.backstory == null) {
            return "暂无更具体背景。";
        }
        AgentBackstory backstory = agent.backstory;
        List<String> parts = new ArrayList<>();
        parts.add("年龄=" + backstory.age);
        parts.add("年级=" + blankTo(backstory.grade, "未设定"));
        parts.add("专业=" + blankTo(backstory.major, "未设定"));
        parts.add("出生地=" + blankTo(backstory.hometown, "未设定"));
        parts.add("当前城市=" + blankTo(backstory.currentCity, "未设定"));
        parts.add("常去地点=" + joinOrEmpty(backstory.campusPlaces));
        parts.add("爱好=" + joinOrEmpty(backstory.hobbies));
        parts.add("生活节奏=" + blankTo(backstory.lifestyle, "未设定"));
        parts.add("边界细节=" + blankTo(backstory.boundaryDetails, "未设定"));
        parts.add("情绪模式=" + blankTo(backstory.emotionPattern, "未设定"));
        parts.add("未来规划=" + blankTo(backstory.futurePlan, "未设定"));
        parts.add("隐藏经历=" + joinOrEmpty(backstory.hiddenFacts));
        parts.add("剧情钩子=" + joinOrEmpty(backstory.plotHooks));
        return String.join("；", parts);
    }

    private String buildContinuityText(DialogueContinuityState state) {
        if (state == null) {
            return "暂无明确行动链。";
        }
        List<String> parts = new ArrayList<>();
        parts.add("当前共同目标=" + blankTo(state.currentObjective, "无"));
        parts.add("待回应提议=" + blankTo(state.pendingUserOffer, "无"));
        parts.add("已确认计划=" + blankTo(state.acceptedPlan, "无"));
        parts.add("上一轮问题=" + blankTo(state.lastAssistantQuestion, "无"));
        parts.add("用户是否回答上一问=" + (state.userAnsweredLastQuestion ? "是" : "否"));
        parts.add("是否需要场景过渡=" + (state.sceneTransitionNeeded ? "是" : "否"));
        parts.add("下一句最好承接=" + blankTo(state.nextBestMove, "无"));
        parts.add("禁止违背=" + (state.mustNotContradict == null || state.mustNotContradict.isEmpty() ? "无" : String.join("；", state.mustNotContradict)));
        parts.add("置信度=" + state.confidence);
        return String.join("；", parts);
    }

    private String joinOrEmpty(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "无";
        }
        return String.join("、", values);
    }

    private String buildUserCue(LlmRequest request) {
        if (isHeartbeatProactive(request.replySource)) {
            return "请你主动发一条轻一点、像顺手发来的消息。结合当前场景、时间、天气、情绪状态和记忆使用计划，不要像系统提醒。";
        }
        return blankTo(request.userMessage, "继续顺着当前气氛自然回复。");
    }

    private String buildDirectAnswer(LlmRequest request) {
        String text = compact(request.userMessage);
        if (!containsQuestion(text)) {
            return "";
        }
        String semanticAnswer = buildSemanticDirectAnswer(request, text);
        if (!semanticAnswer.isBlank()) {
            return semanticAnswer;
        }
        String ownershipCorrection = correctUserPlanMisattribution(request, text);
        if (!ownershipCorrection.isBlank()) {
            return ownershipCorrection;
        }
        if (!hasRemoteSemanticDecision(request) && isAskingAgentFuturePlan(text)) {
            return futurePlanAnswer(request);
        }
        if (text.startsWith("为什么")) {
            return "如果要直说，是因为你刚才那句话本身就让我想认真接住。";
        }
        if (text.contains("怎么")) {
            return "真要说的话，我会先顺着你刚刚最在意的那一点去接，而不是急着把话题带跑。";
        }
        return "";
    }

    private String buildSemanticDirectAnswer(LlmRequest request, String text) {
        SemanticRuntimeDecision decision = request == null ? null : request.semanticDecision;
        String policy = decision == null ? "" : blankTo(decision.directAnswerPolicy, "");
        String hint = decision == null ? "" : blankTo(decision.directAnswerHint, "");
        if ("decline_ungrounded_quote".equals(policy) || mustDeclineUngroundedQuote(request, text)) {
            return hint.isBlank() ? "这类需要逐字准确的内容，我现在不能确认准确版本，所以不想乱编。" : hint;
        }
        if ("answer_relationship_feeling".equals(policy)) {
            return hint.isBlank() ? "如果你要我认真回答，那我会说：我确实在意这段靠近。" : hint;
        }
        if ("clarify_unconfirmed_detail".equals(policy)) {
            return hint.isBlank() ? "这个细节我不想替你乱补，还是想等你亲口确认。" : hint;
        }
        if ("repair_misattribution".equals(policy)) {
            return hint.isBlank() ? "我刚才把你自己的计划和我的想法混在一起了，这里先收回来。" : hint;
        }
        if ("answer_agent_future_plan".equals(policy) || hasRemoteSemanticDecision(request) && isAskingAgentFuturePlan(text)) {
            return hint.isBlank() ? futurePlanAnswer(request) : hint;
        }
        if (!hasRemoteSemanticDecision(request)) {
            if (isRelationshipConfirmationQuestion(text)) {
                return "如果你要我现在认真回答，那答案是有，而且我没有把它当成一句随口的话。";
            }
            if (isHotDrinkFlavorQuestion(text)) {
                return "有原味、榛果和薄荷这几种，我平时最常带的是原味。";
            }
            if (isHotDrinkQuantityQuestion(text)) {
                return "没有真的摆三杯在你面前，我只是顺口把自己会想到的几种味道都说出来了。";
            }
            if (isUnconfirmedLibraryWindowQuestion(text)) {
                return "我不想擅自替你把上次的画面补全，所以这个细节得等你亲口告诉我。";
            }
        }
        return "";
    }

    private String buildOpeningLine(LlmRequest request) {
        String mood = blankTo(request.currentUserMood, "neutral");
        if ("stressed".equals(mood)) {
            return "我在，先把节奏放慢一点。";
        }
        if ("curious".equals(mood)) {
            return "你这个问题我会认真接。";
        }
        if ("warm".equals(mood)) {
            return "你这样说的时候，气氛像是靠近了一点。";
        }
        if (request.agent != null && request.agent.openingLine != null && !request.agent.openingLine.isBlank()) {
            return firstSentence(request.agent.openingLine);
        }
        return "我在，慢慢说。";
    }

    private String buildClosingLine(LlmRequest request) {
        String stage = request.relationshipState == null ? "" : blankTo(request.relationshipState.relationshipStage, "");
        if (stage.contains("靠近") || stage.contains("心动")) {
            return "如果你愿意，我们可以把这句话再往里聊一点。";
        }
        if ("stressed".equals(blankTo(request.currentUserMood, ""))) {
            return "你不用一次说完，我会先陪你把最重的那段接住。";
        }
        return "你可以继续往下说，我会顺着这一句接住。";
    }

    private boolean mustDeclineUngroundedQuote(LlmRequest request, String text) {
        return (text.contains("歌词") || text.contains("台词"))
                && request.realityEnvelope != null
                && request.realityEnvelope.searchGrounding != null
                && request.realityEnvelope.searchGrounding.mustDeclineIfMissing;
    }

    private boolean hasRemoteSemanticDecision(LlmRequest request) {
        return request != null
                && request.semanticDecision != null
                && request.semanticDecision.remoteUsed;
    }

    private boolean isRelationshipConfirmationQuestion(String text) {
        return text.contains("有好感")
                || text.contains("喜欢我")
                || text.contains("对我也")
                || text.contains("会不会也喜欢");
    }

    private boolean isHotDrinkFlavorQuestion(String text) {
        return text.contains("热可可")
                && containsAny(text, List.of("啥味", "什么味", "口味", "哪种", "几种"));
    }

    private boolean isHotDrinkQuantityQuestion(String text) {
        return text.contains("热可可")
                && containsAny(text, List.of("这么多", "这么多杯", "哪来这么多"));
    }

    private boolean isLibraryWindowTopic(String text) {
        return text.contains("图书馆") && text.contains("窗边");
    }

    private boolean isUnconfirmedLibraryWindowQuestion(String text) {
        return isLibraryWindowTopic(text)
                && containsAny(text, List.of("上次", "也是", "还记得"));
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildAcknowledgment(LlmRequest request, boolean answeredDirectly) {
        String topic = briefTopic(request.userMessage);
        String text = compact(request.userMessage);
        if (isShortInput(request.userMessage) && !answeredDirectly) {
            return "你先来找我这一下，本身就已经算是在靠近了。";
        }
        if (!answeredDirectly && (text.contains("吸引我") || text.contains("喜欢你") || text.contains("好感"))) {
            return "你把这句话认真说出来的时候，我其实也跟着安静了一下。";
        }
        if (!answeredDirectly
                && request.semanticDecision != null
                && request.semanticDecision.sceneLocation != null
                && !request.semanticDecision.sceneLocation.isBlank()
                && !"聊天现场".equals(request.semanticDecision.sceneLocation)) {
            return "你把" + request.semanticDecision.sceneLocation + "带回这句话里，我会顺着这里慢慢接。";
        }
        if (answeredDirectly) {
            return switch (blankTo(request.currentUserMood, "neutral")) {
                case "stressed" -> "你这句背后有点累，所以我不想绕着说。";
                case "warm" -> "你这样问的时候，语气其实已经挺靠近了。";
                default -> "你刚刚这句问得很准，所以我不想把话题带偏。";
            };
        }
        if (containsQuestion(request.userMessage)) {
            return "关于你刚刚提到的" + topic + "，我不想只回你半句就停。";
        }
        return switch (blankTo(request.currentUserMood, "neutral")) {
            case "stressed" -> "你这句里有一点累，我先把节奏放慢，不让你一个人硬撑。";
            case "warm" -> "你这样说的时候，空气已经比刚才更近了一点。";
            case "curious" -> "你不是随口一问，我会认真把这层意思接住。";
            default -> "我听得出来，这句不是随口说说。";
        };
    }

    private String buildPresenceLead(LlmRequest request) {
        if ("silence_heartbeat".equals(request.replySource)) {
            return "刚刚安静了一会儿，我忽然想把这句先递给你。";
        }
        if ("long_chat_heartbeat".equals(request.replySource)) {
            return "聊到这会儿，气氛其实还稳稳地挂着。";
        }
        return "这一刻正好适合轻轻续上刚才那点心思。";
    }

    private String buildSceneLine(LlmRequest request, boolean hasQuestion, boolean answeredDirectly) {
        if (request == null) {
            return "";
        }
        if (request.dialogueContinuityState != null
                && request.dialogueContinuityState.sceneTransitionNeeded
                && request.dialogueContinuityState.currentObjective != null
                && request.dialogueContinuityState.currentObjective.contains("热饮")) {
            return "你们顺着刚刚说好的方向往热饮那边走，话题没有散开。";
        }
        if (request.sceneState != null && request.sceneState.transitionPending) {
            return blankTo(request.sceneFrame, "");
        }
        if ("plot_push".equals(request.replySource) || isHeartbeatProactive(request.replySource)) {
            return blankTo(request.sceneFrame, "");
        }
        if (!hasQuestion && !answeredDirectly) {
            return conciseSceneHint(request);
        }
        return "";
    }

    private boolean shouldCarryAfterQuestion(LlmRequest request) {
        String text = compact(request.userMessage);
        return text.contains("上次") || text.contains("记得") || text.contains("之前") || text.contains("答应");
    }

    private String buildCarryLine(LlmRequest request) {
        if (request.memoryUsePlan != null && request.memoryUsePlan.mergedMemoryText != null && !request.memoryUsePlan.mergedMemoryText.isBlank()) {
            return "我还记得" + softenMemoryText(request.memoryUsePlan.mergedMemoryText) + "。";
        }
        if (request.recalledMemoryText != null && !request.recalledMemoryText.isBlank()) {
            return "我还记得" + softenMemoryText(request.recalledMemoryText) + "。";
        }
        if (request.memoryUsePlan != null && request.memoryUsePlan.callbackCandidates != null && !request.memoryUsePlan.callbackCandidates.isEmpty()) {
            return "我还记得" + softenMemoryText(request.memoryUsePlan.callbackCandidates.get(0)) + "。";
        }
        if (request.longTermSummary != null && !request.longTermSummary.isBlank()) {
            String memoryLine = softenMemoryText(request.longTermSummary);
            if (!memoryLine.isBlank()) {
                return "我还记得" + memoryLine + "。";
            }
        }
        if ("stressed".equals(request.currentUserMood)) {
            return "如果你愿意，我们就先抓住今天最让你累的那一刻。";
        }
        return "";
    }

    private String buildFollowupAfterAnswer(LlmRequest request) {
        String text = compact(request.userMessage);
        boolean askBack = shouldAskBack(request);
        if (isHotDrinkFlavorQuestion(text)) {
            return askBack ? "你会偏原味一点，还是更想要甜一点的？" : "真要我替你先留一杯，我大概会把原味那杯先放到你手边。";
        }
        if (isHotDrinkQuantityQuestion(text)) {
            return askBack ? "真要让你现在选一杯，你会先拿哪种？" : "真要让你现在挑，我总觉得你不会先拿最甜的那杯。";
        }
        if (isLibraryWindowTopic(text)) {
            return askBack ? "如果你愿意告诉我，我会把这个细节好好记住。" : "等你哪天想说了，我会把这个细节好好收住。";
        }
        if ("stressed".equals(request.currentUserMood)) {
            return askBack ? "你也可以顺手告诉我，今天最压你的那一刻到底落在哪儿。" : "你不用一下子把今天都说完，我会先把最重的那一段接住。";
        }
        if (containsQuestion(request.userMessage)) {
            return askBack ? "你真正想继续往下聊的，是这个答案本身，还是它背后的那层意思？" : "你在意的其实不只答案本身，我听得出来。";
        }
        return "";
    }

    private String buildInitiativeLine(LlmRequest request) {
        boolean askBack = shouldAskBack(request);
        if (request.dialogueContinuityState != null
                && request.dialogueContinuityState.acceptedPlan != null
                && request.dialogueContinuityState.acceptedPlan.contains("热饮")) {
            return "那我们就先去买热饮，路上你不用重新找话题，我会接着刚才那句慢慢聊。";
        }
        if (isShortInput(request.userMessage)) {
            return askBack ? "要不要先从今天最想说的那一小段开始，我来陪你把后面的情绪慢慢拽出来？" : "你先把最想说的那一小段交给我，后面的情绪我们慢慢往外带。";
        }
        if (containsQuestion(request.userMessage)) {
            return askBack ? "你也可以顺手告诉我，你真正卡住的是哪一层，我陪你一起拆开。" : "你这样问的时候，其实已经把心里那层意思递出来一点了。";
        }
        if ("stressed".equals(request.currentUserMood)) {
            return askBack ? "你先别急着整理语气，直接告诉我，今天最压你的那一刻是什么。" : "你先不用把语气理顺，哪怕只把最累的那一小截丢给我也行。";
        }
        if (request.relationshipState != null && blankTo(request.relationshipState.relationshipStage, "").contains("靠近")) {
            return askBack ? "如果你愿意，我想把这段话再往更靠近一点的方向接下去。" : "我其实有点想把这段话继续往更靠近的地方接。";
        }
        if (request.relationshipState != null && blankTo(request.relationshipState.relationshipStage, "").contains("心动")) {
            return askBack ? "要不你再往下说一点，我想知道你刚才那句后面藏着的其实是什么。" : "你刚才那句后面其实还藏着东西，我听得出来。";
        }
        return askBack ? "如果你愿意，下一句可以再往里一点，我会认真接。" : "你不用急着推进，我会把你刚才那层意思稳稳接住。";
    }

    private String buildActionLine(LlmRequest request, boolean hasQuestion, boolean answeredDirectly) {
        if (request == null) {
            return "";
        }
        String userText = compact(request.userMessage);
        if ("silence_heartbeat".equals(request.replySource) || "long_chat_heartbeat".equals(request.replySource)) {
            return request.emotionState != null && request.emotionState.longing >= 55
                    ? "她把视线轻轻停在你这边，像是真的还挂念着你刚才那句话"
                    : "她顺着刚才的安静停了一下，像是在确认你还在不在这一侧";
        }
        if ("plot_push".equals(request.replySource)) {
            return userText.contains("送") || userText.contains("一起走")
                    ? "她和你并肩往前走了几步，语气也跟着更贴近了一点"
                    : "她看着你，像是准备顺着这段气氛再往前靠半步";
        }
        if (!hasQuestion && !isShortInput(request.userMessage)) {
            return "她没有把目光移开，像是认真等你把后半句也放下来";
        }
        if (!answeredDirectly && isShortInput(request.userMessage)) {
            return "她先把注意力轻轻落回你身上，像是在给你一个更容易接的话头";
        }
        return "";
    }

    private String buildProactiveFollowup(LlmRequest request) {
        if (request.memoryUsePlan != null && request.memoryUsePlan.callbackCandidates != null && !request.memoryUsePlan.callbackCandidates.isEmpty()) {
            return "上次那点没聊完的尾巴，我其实一直还惦记着。";
        }
        if ("stressed".equals(request.currentUserMood)) {
            return "你要是刚好也想说一点，我会先把节奏替你放慢。";
        }
        if (request.emotionState != null && request.emotionState.longing >= 58) {
            return "我只是想让你知道，这会儿我确实在朝你这边靠。";
        }
        return shouldAskBack(request) ? "你如果刚好在线，就把你现在那点心情分我一点。" : "你不用立刻回，我只是想把这点陪伴先递过来。";
    }

    private boolean shouldAskBack(LlmRequest request) {
        if (request == null) {
            return false;
        }
        if ("plot_push".equals(request.replySource)) {
            return false;
        }
        if (lastAssistantAskedQuestion(request)) {
            return false;
        }
        if (request.emotionState != null && "uneasy".equals(blankTo(request.emotionState.currentMood, ""))) {
            return false;
        }
        return true;
    }

    private boolean lastAssistantAskedQuestion(LlmRequest request) {
        if (request.shortTermContext == null) {
            return false;
        }
        for (int index = request.shortTermContext.size() - 1; index >= 0; index--) {
            ConversationSnippet snippet = request.shortTermContext.get(index);
            if ("assistant".equals(snippet.role)) {
                return containsQuestion(snippet.text);
            }
        }
        return false;
    }

    private String conciseSceneHint(LlmRequest request) {
        if (request == null) {
            return "";
        }
        if (request.semanticDecision != null && request.semanticDecision.sceneAtmosphere != null && !request.semanticDecision.sceneAtmosphere.isBlank()) {
            return trimTrailingPunctuation(request.semanticDecision.sceneAtmosphere) + "。";
        }
        if (request.sceneState != null && request.sceneState.sceneSummary != null && !request.sceneState.sceneSummary.isBlank()) {
            return trimTrailingPunctuation(request.sceneState.sceneSummary) + "。";
        }
        if (request.timeContext != null && request.timeContext.frame != null && !request.timeContext.frame.isBlank()) {
            return trimTrailingPunctuation(request.timeContext.frame) + "。";
        }
        return "";
    }

    private String softenMemoryText(String text) {
        String normalized = trimTrailingPunctuation(text);
        if (normalized.isBlank()) {
            return "";
        }

        String[] rawSegments = normalized.split("[；;|]");
        for (String rawSegment : rawSegments) {
            String segment = rawSegment.trim();
            segment = segment.replaceFirst("^(强记忆|弱记忆|临时记忆)[:：]", "");
            segment = segment.replaceFirst("^和你的我们剧情[:：]", "");
            segment = segment.replaceFirst("^(初识|升温|拉近|波动|收束)阶段重点[:：]", "");
            segment = segment.replaceFirst("^关系推进到了[^，,]+[，,]?", "");
            segment = trimTrailingPunctuation(segment);
            if (segment.isBlank()) {
                continue;
            }
            if (segment.contains("今天有点累")) {
                return "你刚才说今天有点累这件事";
            }
            if (segment.contains("热可可")) {
                return "刚才聊到热可可时你那点认真";
            }
            if (segment.contains("图书馆")) {
                return "你提过图书馆靠窗那种安静";
            }
            if (segment.contains("下雨") || segment.contains("雨天") || segment.contains("雨")) {
                return "你对雨天那点偏爱";
            }
            if (segment.contains("一起")) {
                return "你前面提过想和我一起做的那件事";
            }
            if (segment.contains("触发") || segment.contains("阶段") || segment.contains("剧情")) {
                continue;
            }
            return segment.replace("用户", "你").replace("共同", "我们").replace("约定", "说好的那件事");
        }
        return "";
    }

    private String wrapAction(String actionText) {
        return ACTION_OPEN + trimTrailingPunctuation(actionText) + "。" + ACTION_CLOSE;
    }

    private String condenseWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String cleanActionText(String text) {
        String normalized = condenseWhitespace(text);
        normalized = trimTrailingPunctuation(normalized);
        return normalized.isBlank() ? "" : normalized + "。";
    }

    private String cleanSpeechText(String text) {
        String normalized = condenseWhitespace(text);
        normalized = normalized.replaceAll("^(（[^）]{0,40}）\\s*)+", "");
        normalized = normalized.replaceAll("([。！？])\\s*（[^）]{0,24}）", "$1");
        normalized = normalized.replaceAll("^“([^”]{1,30})”$", "$1");
        return normalized.trim();
    }

    private List<String> limitSentences(List<String> parts, int limit) {
        List<String> trimmed = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            trimmed.add(part);
            if (trimmed.size() >= limit) {
                break;
            }
        }
        return trimmed;
    }

    private boolean isShortInput(String text) {
        return compact(text).length() <= 8;
    }

    private boolean containsQuestion(String text) {
        return text != null && (text.contains("?")
                || text.contains("？")
                || text.contains("吗")
                || text.contains("怎么")
                || text.contains("为什么")
                || text.contains("是不是")
                || text.contains("会不会")
                || text.contains("有没有")
                || text.contains("难道"));
    }

    private boolean isHeartbeatProactive(String replySource) {
        return "silence_heartbeat".equals(replySource)
                || "long_chat_heartbeat".equals(replySource);
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private String briefTopic(String text) {
        String compact = compact(text);
        if (compact.isBlank()) {
            return "这件事";
        }
        if (compact.length() <= 8) {
            return "这句话";
        }
        return "“" + compact.substring(0, Math.min(10, compact.length())) + (compact.length() > 10 ? "……”" : "”");
    }

    private String trimTrailingPunctuation(String text) {
        return text.replaceAll("[。！？；，,;、]+$", "");
    }

    private String firstSentence(String text) {
        String normalized = trimTrailingPunctuation(blankTo(text, ""));
        if (normalized.isBlank()) {
            return "";
        }
        String[] parts = normalized.split("[。！？!?]");
        return parts.length == 0 || parts[0].isBlank() ? normalized + "。" : parts[0].trim() + "。";
    }

    private String joinNonBlank(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" ");
            }
            builder.append(part.trim());
        }
        return builder.toString().trim();
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String wrapScene(String sceneText) {
        return SCENE_OPEN + trimTrailingPunctuation(sceneText) + "。" + SCENE_CLOSE;
    }

    private ReplyParts shapeStructuredReply(String rawReply) {
        String normalized = condenseWhitespace(rawReply);
        String sceneText = cleanSceneText(extractSegment(normalized, SCENE_OPEN, SCENE_CLOSE));
        String actionText = cleanActionText(extractSegment(normalized, ACTION_OPEN, ACTION_CLOSE));
        String speechText = normalized
                .replace(sceneText.isBlank() ? "" : SCENE_OPEN + trimTrailingPunctuation(sceneText) + "。" + SCENE_CLOSE, "")
                .replace(actionText.isBlank() ? "" : ACTION_OPEN + trimTrailingPunctuation(actionText) + "。" + ACTION_CLOSE, "")
                .replace(SCENE_OPEN, "")
                .replace(SCENE_CLOSE, "")
                .replace(ACTION_OPEN, "")
                .replace(ACTION_CLOSE, "")
                .trim();
        speechText = cleanSpeechText(speechText.isBlank() ? normalized : speechText);
        String combined = joinNonBlank(List.of(sceneText, actionText, speechText));
        return new ReplyParts(combined, sceneText, actionText, speechText);
    }

    private String cleanSceneText(String text) {
        String normalized = condenseWhitespace(text);
        normalized = trimTrailingPunctuation(normalized);
        return normalized.isBlank() ? "" : normalized + "。";
    }

    private String extractSegment(String text, String openToken, String closeToken) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int openIndex = text.indexOf(openToken);
        int closeIndex = text.indexOf(closeToken);
        if (openIndex < 0 || closeIndex <= openIndex) {
            return "";
        }
        return text.substring(openIndex + openToken.length(), closeIndex).trim();
    }

    private String extractResponsesText(Map<String, Object> payload) {
        List<Object> output = Json.asArray(payload.get("output"));
        StringBuilder builder = new StringBuilder();
        for (Object item : output) {
            Map<String, Object> outputItem = Json.asObject(item);
            if (!"message".equals(Json.asString(outputItem.get("type")))) {
                continue;
            }
            for (Object contentItem : Json.asArray(outputItem.get("content"))) {
                Map<String, Object> content = Json.asObject(contentItem);
                String text = Json.asString(content.get("text"));
                if (text != null && !text.isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append(" ");
                    }
                    builder.append(text.trim());
                }
            }
        }
        return builder.toString().trim();
    }

    private String inferEmotionTag(LlmRequest request) {
        if (request.tensionState != null && request.tensionState.guarded) {
            return "guarded";
        }
        if (request.emotionState != null && request.emotionState.currentMood != null && !request.emotionState.currentMood.isBlank()) {
            return request.emotionState.currentMood;
        }
        String text = request.userMessage == null ? "" : request.userMessage;
        if (text.matches(".*(开心|喜欢|想你|期待|在意).*")) {
            return "warm";
        }
        if (text.matches(".*(压力|迷茫|累|难受|焦虑).*")) {
            return "comfort";
        }
        return "steady";
    }

    private String buildPolicySpeech(LlmRequest request) {
        if (request == null) {
            return "";
        }
        String text = compact(request.userMessage);
        if (request.intentState != null && "meta_repair".equals(request.intentState.primaryIntent)) {
            String location = request.sceneState == null ? "这里" : blankTo(request.sceneState.location, "这里");
            return "你说得对，我先把前面的错位收回来。我们现在还是在" + location + "这边顺着聊，不是突然变成隔着屏幕发消息。";
        }
        if (isLaughter(text) && lastAssistantMentionedSong(request)) {
            return "你笑成这样，我就先当作刚才那句歌没有跑调太严重。别急着换新话题，我还挺想知道，你是觉得歌词好笑，还是我唱出来这件事本身比较好笑？";
        }
        if (("decline_ungrounded_quote".equals(request.semanticDecision == null ? "" : request.semanticDecision.directAnswerPolicy)
                || (text.contains("歌词") || text.contains("台词"))
                && request.realityEnvelope != null
                && request.realityEnvelope.searchGrounding != null
                && request.realityEnvelope.searchGrounding.mustDeclineIfMissing)) {
            return "这类需要逐字准确的内容，我现在不能确认准确版本，所以不想乱编。你要是愿意，我可以只聊它给人的感觉，或者等有可靠依据时再回答。";
        }
        if (request.tensionState != null && request.tensionState.guarded) {
            if (text.contains("对不起") || text.contains("抱歉")) {
                return "我听到了你的道歉。现在我还没有一下子完全放松下来，但我愿意先把语气放缓一点，看看我们能不能慢慢接回来。";
            }
            return "你刚才那句话让我有点不舒服，所以我现在不会顺着甜下去。要继续聊可以，但得先把这份不舒服放在桌面上。";
        }
        if (request.intentState != null
                && "romantic_probe".equals(request.intentState.primaryIntent)
                && request.relationshipState != null
                && "初识".equals(request.relationshipState.relationshipStage)) {
            return "我会在意你，也确实会被你吸引，但现在的我更想把这份靠近走稳一点，不想一上来就把话说满。";
        }
        return "";
    }

    private boolean isAskingAgentFuturePlan(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return (text.contains("以后") || text.contains("未来") || text.contains("毕业") || text.contains("打算") || text.contains("规划"))
                && (text.contains("你") || text.contains("你呢") || text.contains("那你"));
    }

    private String futurePlanAnswer(LlmRequest request) {
        String plan = request.agent == null || request.agent.backstory == null ? "" : request.agent.backstory.futurePlan;
        if (plan.isBlank()) {
            return "我还在慢慢想，但应该会顺着自己真正长期在意的方向走，不想只被眼前的热闹推着跑。";
        }
        return "我自己的方向是：" + trimTrailingPunctuation(plan) + "。";
    }

    private String correctUserPlanMisattribution(LlmRequest request, String text) {
        String userPlan = latestUserOwnedPlan(request);
        if (userPlan.isBlank() || request.agent == null || request.agent.backstory == null) {
            return "";
        }
        String agentPlan = request.agent.backstory.futurePlan;
        if (!containsUserOwnedPlanSignal(text, userPlan, agentPlan)) {
            return "";
        }
        return "不是把你的计划搬到我身上啦。你刚才说的那条路，我会认真记着；我自己的方向是："
                + trimTrailingPunctuation(agentPlan) + "。";
    }

    private String latestUserOwnedPlan(LlmRequest request) {
        if (request == null || request.shortTermContext == null) {
            return "";
        }
        for (int index = request.shortTermContext.size() - 1; index >= 0; index--) {
            ConversationSnippet snippet = request.shortTermContext.get(index);
            if (snippet == null || !"user".equals(snippet.role)) {
                continue;
            }
            String text = compact(snippet.text);
            if (text.isBlank()) {
                continue;
            }
            boolean firstPersonPlan = (text.contains("我打算") || text.contains("我想") || text.contains("我的计划")
                    || text.contains("我准备") || text.contains("我以后") || text.contains("我未来"))
                    && (text.contains("以后") || text.contains("未来") || text.contains("打算") || text.contains("计划")
                    || text.contains("想要") || text.contains("想做") || text.contains("准备"));
            if (firstPersonPlan) {
                return text;
            }
        }
        return "";
    }

    private boolean containsUserOwnedPlanSignal(String questionText, String userPlan, String agentPlan) {
        String compactQuestion = compact(questionText);
        String compactUserPlan = compact(userPlan);
        String compactAgentPlan = compact(agentPlan);
        if (compactQuestion.isBlank() || compactUserPlan.isBlank()) {
            return false;
        }
        int hits = 0;
        for (String token : planSignalTokens(compactUserPlan)) {
            if (compactQuestion.contains(token) && !compactAgentPlan.contains(token)) {
                hits++;
            }
        }
        boolean asksAboutAgent = compactQuestion.contains("你")
                || compactQuestion.contains("当")
                || compactQuestion.contains("做")
                || compactQuestion.contains("以后")
                || compactQuestion.contains("未来")
                || compactQuestion.contains("打算");
        return asksAboutAgent && hits > 0;
    }

    private List<String> planSignalTokens(String text) {
        List<String> tokens = new ArrayList<>();
        String cleaned = compact(text)
                .replace("我打算", "")
                .replace("我想要", "")
                .replace("我想", "")
                .replace("我的计划", "")
                .replace("我准备", "")
                .replace("然后", "，")
                .replace("以后", "，")
                .replace("未来", "，");
        for (String part : cleaned.split("[，。！？、,.!?\\s]+")) {
            String segment = part.trim();
            if (segment.length() >= 2 && segment.length() <= 12 && !isLowValuePlanToken(segment)) {
                tokens.add(segment);
            }
            if (segment.length() > 4) {
                for (int index = 0; index + 2 <= segment.length(); index++) {
                    for (int length = 2; length <= 3 && index + length <= segment.length(); length++) {
                        String token = segment.substring(index, index + length);
                        if (!isLowValuePlanToken(token)) {
                            tokens.add(token);
                        }
                    }
                }
            }
        }
        return tokens;
    }

    private boolean isLowValuePlanToken(String token) {
        return token == null || token.isBlank()
                || token.contains("几年")
                || token.contains("一些")
                || token.contains("自己")
                || token.contains("事情")
                || token.contains("完成")
                || token.contains("梦想");
    }

    private boolean isLaughter(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        return compact.contains("哈哈")
                || compact.contains("嘿嘿")
                || compact.contains("笑死")
                || compact.equalsIgnoreCase("lol");
    }

    private boolean lastAssistantMentionedSong(LlmRequest request) {
        if (request == null || request.shortTermContext == null) {
            return false;
        }
        for (int index = request.shortTermContext.size() - 1; index >= 0; index--) {
            ConversationSnippet snippet = request.shortTermContext.get(index);
            if (!"assistant".equals(snippet.role)) {
                continue;
            }
            String text = snippet.text == null ? "" : snippet.text;
            return text.contains("唱")
                    || text.contains("歌")
                    || text.contains("歌词")
                    || text.contains("哼")
                    || text.contains("旋律")
                    || text.contains("song")
                    || text.contains("lyric");
        }
        return false;
    }
}
