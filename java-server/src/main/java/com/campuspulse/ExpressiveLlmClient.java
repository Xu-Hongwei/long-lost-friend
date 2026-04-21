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
    private static final String ACTION_OPEN = "[[ACTION]]";
    private static final String ACTION_CLOSE = "[[/ACTION]]";

    private static final class ReplyParts {
        final String replyText;
        final String actionText;
        final String speechText;

        ReplyParts(String replyText, String actionText, String speechText) {
            this.replyText = replyText;
            this.actionText = actionText;
            this.speechText = speechText;
        }
    }

    private final AppConfig config;
    private final Map<String, List<String>> openings = Map.of(
            "healing", List.of("我在，慢慢说。", "嗯，我有认真在听。", "先把这一句交给我。"),
            "lively", List.of("来，我接住你这句。", "你一开口，我就想认真往下听。", "好，今晚这段话我陪你聊开。"),
            "cool", List.of("我听到了。", "可以，继续。", "这句我会认真接。"),
            "artsy", List.of("这句话一落下来，气氛就安静了一点。", "好，你继续，我在听细节。", "先别急，让这句话慢慢展开。"),
            "sunny", List.of("来，先把节奏稳住。", "收到，这句我跟你一起扛。", "行，先从你最想说的地方开始。")
    );
    private final Map<String, List<String>> closers = Map.of(
            "healing", List.of("你不用一下子整理得很完整。", "剩下那一点，我也愿意继续陪你。", "你不用一个人把这段情绪扛完。"),
            "lively", List.of("下一句也丢给我，我接得住。", "你再往下说一点，气氛会更亮。", "别停在这儿，我想把你后半句也听完。"),
            "cool", List.of("你可以继续，我不会敷衍带过去。", "这件事我会记住。", "你肯把这句说出来，我就不会轻轻放过。"),
            "artsy", List.of("别让这段话太快结束。", "我想把这会儿的空气再留久一点。", "如果你愿意，我们把这段心情再写长一点。"),
            "sunny", List.of("我们把这段往前走完。", "别慌，我还在这儿。", "你继续说，我帮你把节奏带顺。")
    );

    ExpressiveLlmClient(AppConfig config) {
        super(config);
        this.config = config;
    }

    @Override
    public LlmResponse generateReply(LlmRequest request) throws Exception {
        if (config.llmApiKey == null || config.llmApiKey.isBlank()) {
            return generateMockReply(request);
        }
        try {
            return generateRemoteReply(request);
        } catch (Exception error) {
            LlmResponse fallback = generateMockReply(request);
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
        Map<String, String> fallbackMap = Map.of(
                "healing", "刚才像卡了一下，不过没关系。你把最想说的那一句继续交给我，我们从那里接回来。",
                "lively", "刚才信号像打了个结，不过现在顺回来了。来，把后半句也给我。",
                "cool", "刚才中断了一下。现在恢复了，你继续，我在。",
                "artsy", "刚才那段像被风吹散了一点，但没关系，我们还能把它慢慢捡回来。",
                "sunny", "刚才掉了一拍，现在接上了。来，我们继续，不用重新鼓起劲。"
        );
        return fallbackMap.getOrDefault(agent.id, "刚才有一点小问题（" + reason + "），不过现在已经可以继续了。");
    }

    private LlmResponse generateMockReply(LlmRequest request) {
        String rawReply = isHeartbeatProactive(request.replySource) ? buildProactiveReply(request) : buildReactiveReply(request);
        ReplyParts reply = shapeReply(rawReply, request.replySource);
        return new LlmResponse(
                reply.replyText,
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

    private LlmResponse generateRemoteReply(LlmRequest request) throws Exception {
        String systemPrompt = buildSystemPrompt(request);
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
        ReplyParts reply = shapeReply(Json.asString(message.get("content")).trim(), request.replySource);

        return new LlmResponse(
                reply.replyText,
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
        boolean hasQuestion = containsQuestion(request.userMessage);
        String directAnswer = buildDirectAnswer(request);
        boolean answeredDirectly = !directAnswer.isBlank();

        String actionLine = buildActionLine(request, hasQuestion, answeredDirectly);
        List<String> speechParts = new ArrayList<>();
        if (answeredDirectly) {
            speechParts.add(directAnswer);
        } else {
            speechParts.add(choose(openings.get(request.agent.id), request.agent.id + ":" + blankTo(request.userMessage, "")));
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
            speechParts.add(choose(closers.get(request.agent.id), blankTo(request.relationshipState.relationshipStage, "") + ":" + blankTo(request.userMessage, "")));
        }

        List<String> parts = new ArrayList<>();
        if (!actionLine.isBlank()) {
            parts.add(wrapAction(actionLine));
        }
        parts.addAll(speechParts);
        return joinNonBlank(limitSentences(parts, actionLine.isBlank() ? 4 : 5));
    }

    private String buildProactiveReply(LlmRequest request) {
        String actionLine = buildActionLine(request, false, false);
        List<String> parts = new ArrayList<>();
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

        return "你正在扮演大学校园恋爱互动游戏中的角色“" + request.agent.name + "”（" + request.agent.archetype + "）。\n"
                + "说话风格：" + request.agent.speechStyle + "\n"
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
                + "10. 保持中文自然、亲近、连贯，不要写成小说旁白，也不要突然结束话题。";
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
        if (text.contains("有好感") || text.contains("喜欢我") || text.contains("对我也") || text.contains("会不会也喜欢")) {
            return "如果你要我现在认真回答，那答案是有，而且我没有把它当成一句随口的话。";
        }
        if (text.contains("热可可") && (text.contains("啥味") || text.contains("什么味") || text.contains("口味") || text.contains("哪种") || text.contains("几种"))) {
            return "有原味、榛果和薄荷这几种，我平时最常带的是原味。";
        }
        if (text.contains("热可可") && (text.contains("这么多") || text.contains("这么多杯") || text.contains("哪来这么多"))) {
            return "没有真的摆三杯在你面前，我只是顺口把自己会想到的几种味道都说出来了。";
        }
        if (text.contains("图书馆") && text.contains("窗边") && (text.contains("上次") || text.contains("也是") || text.contains("还记得"))) {
            return "我不想擅自替你把上次的画面补全，所以这个细节得等你亲口告诉我。";
        }
        if (text.startsWith("为什么")) {
            return "如果要直说，是因为你刚才那句话本身就让我想认真接住。";
        }
        if (text.contains("怎么")) {
            return "真要说的话，我会先顺着你刚刚最在意的那一点去接，而不是急着把话题带跑。";
        }
        return "";
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

    private String buildSceneBridge(LlmRequest request) {
        if (request.sceneFrame != null && !request.sceneFrame.isBlank()) {
            return request.sceneFrame;
        }
        return buildLightSceneBridge(request);
    }

    private String buildLightSceneBridge(LlmRequest request) {
        return conciseSceneHint(request);
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
        if ("stressed".equals(request.currentUserMood)) {
            return "如果你愿意，我们就先抓住今天最让你累的那一刻。";
        }
        return "";
    }

    private String buildFollowupAfterAnswer(LlmRequest request) {
        String text = compact(request.userMessage);
        boolean askBack = shouldAskBack(request);
        if (text.contains("热可可") && (text.contains("什么味") || text.contains("啥味") || text.contains("口味") || text.contains("哪种") || text.contains("几种"))) {
            return askBack ? "你会偏原味一点，还是更想要甜一点的？" : "真要我替你先留一杯，我大概会把原味那杯先放到你手边。";
        }
        if (text.contains("热可可") && (text.contains("这么多") || text.contains("这么多杯") || text.contains("哪来这么多"))) {
            return askBack ? "真要让你现在选一杯，你会先拿哪种？" : "真要让你现在挑，我总觉得你不会先拿最甜的那杯。";
        }
        if (text.contains("图书馆") && text.contains("窗边")) {
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
        if ("silence_heartbeat".equals(request.replySource) || "long_chat_heartbeat".equals(request.replySource)) {
            return conciseSceneHint(request);
        }
        if ("plot_push".equals(request.replySource)) {
            return buildSceneBridge(request);
        }
        if (!hasQuestion && !isShortInput(request.userMessage)) {
            return buildSceneBridge(request);
        }
        if (!answeredDirectly && isShortInput(request.userMessage)) {
            return conciseSceneHint(request);
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
        String userText = compact(request.userMessage);
        if ("stressed".equals(request.currentUserMood) || userText.contains("累") || userText.contains("烦") || userText.contains("压力")) {
            return "你这句落下来时，语气明显比刚才更沉一点，我也跟着把节奏放慢了。";
        }
        if (userText.contains("图书馆") || userText.contains("复习") || userText.contains("自习")) {
            return "靠窗那排位置安安静静的，连翻书声都像放轻了一点。";
        }
        if (userText.contains("热可可") || userText.contains("奶茶") || userText.contains("咖啡")) {
            return "杯沿还带着一点热气，连语气都像被暖得更慢了些。";
        }
        if (userText.contains("下雨") || userText.contains("雨") || userText.contains("伞")) {
            return "窗外的雨线把世界压低了一点，话也跟着变轻了。";
        }
        if (userText.contains("散步") || userText.contains("操场") || userText.contains("走") || userText.contains("一起")) {
            return "风从走廊尽头掠过去，步子和语气都像慢下来一点。";
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

    private ReplyParts shapeReply(String rawReply, String replySource) {
        String normalized = condenseWhitespace(rawReply);
        String actionText = "";
        String speechText = normalized;

        int openIndex = normalized.indexOf(ACTION_OPEN);
        int closeIndex = normalized.indexOf(ACTION_CLOSE);
        if (openIndex >= 0 && closeIndex > openIndex) {
            actionText = normalized.substring(openIndex + ACTION_OPEN.length(), closeIndex).trim();
            speechText = (normalized.substring(0, openIndex) + " " + normalized.substring(closeIndex + ACTION_CLOSE.length())).trim();
        } else {
            String[] heuristicSplit = heuristicSplit(normalized, replySource);
            actionText = heuristicSplit[0];
            speechText = heuristicSplit[1];
        }

        actionText = cleanActionText(actionText);
        speechText = cleanSpeechText(speechText.isBlank() ? normalized : speechText);
        String combined = actionText.isBlank() ? speechText : actionText + " " + speechText;
        return new ReplyParts(combined.trim(), actionText, speechText);
    }

    private String[] heuristicSplit(String text, String replySource) {
        if (text == null || text.isBlank()) {
            return new String[] { "", "" };
        }
        String normalized = text.trim();
        if (normalized.startsWith("（")) {
            int end = normalized.indexOf("）");
            if (end > 1 && end < Math.min(normalized.length() - 1, 40)) {
                return new String[] {
                        normalized.substring(1, end).trim(),
                        normalized.substring(end + 1).trim()
                };
            }
        }

        int sentenceEnd = firstSentenceEnd(normalized);
        if (sentenceEnd > 0 && sentenceEnd < normalized.length() - 1) {
            String first = normalized.substring(0, sentenceEnd).trim();
            String rest = normalized.substring(sentenceEnd).trim();
            if (looksLikeActionLine(first, replySource)) {
                return new String[] { first, rest };
            }
        }

        return new String[] { "", normalized };
    }

    private int firstSentenceEnd(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '。' || current == '！' || current == '？' || current == '!' || current == '?') {
                return index + 1;
            }
        }
        return -1;
    }

    private boolean looksLikeActionLine(String text, String replySource) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim();
        if ("plot_push".equals(replySource) && normalized.length() <= 38) {
            return true;
        }
        String[] cues = {
                "视线", "目光", "窗外", "雨", "风", "灯", "夜色", "杯沿", "靠窗", "走廊", "空气",
                "安静", "停了停", "顿了顿", "轻声", "笑意", "语气", "发梢", "衣角", "手指", "指尖"
        };
        for (String cue : cues) {
            if (normalized.contains(cue)) {
                return true;
            }
        }
        return false;
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

    private String choose(List<String> values, String seedSource) {
        int sum = 0;
        for (int index = 0; index < seedSource.length(); index++) {
            sum += seedSource.charAt(index);
        }
        return values.get(Math.floorMod(sum, values.size()));
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

    private String inferEmotionTag(LlmRequest request) {
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
}
