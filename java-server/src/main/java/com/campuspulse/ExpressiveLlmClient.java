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
    private final AppConfig config;
    private final Map<String, List<String>> openings = Map.of(
            "healing", List.of("我在，慢慢说。", "嗯，我在认真听你。", "先把这句交给我。"),
            "lively", List.of("来，我接住你这句。", "你一开口，我就想认真往下听。", "好，今晚这段话我陪你聊开。"),
            "cool", List.of("我听到了。", "可以，继续。", "这句我会认真接。"),
            "artsy", List.of("这句落下来的时候，气氛一下子安静了。", "好，你继续，我在听细节。", "先别急，让这句话慢慢展开。"),
            "sunny", List.of("来，先把节奏稳住。", "收到，这句我跟你一起扛。", "行，先从你最想说的地方开始。")
    );
    private final Map<String, List<String>> closers = Map.of(
            "healing", List.of("你不用一下子整理得很完整。", "剩下那一点，我也愿意继续陪你。"),
            "lively", List.of("下一句也丢给我，我接得住。", "你再往下说一点，气氛会更亮。"),
            "cool", List.of("你可以继续，我不会敷衍带过去。", "这件事我会记住。"),
            "artsy", List.of("别让这段话太快结束。", "我想把这会儿的气氛再留久一点。"),
            "sunny", List.of("我们把这段往前走完。", "别慌，我还在这儿。")
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
                "healing", "刚才像是卡了一下，不过没关系。你把最想说的那一句继续交给我，我们从那里接回来。",
                "lively", "刚才信号像打了个结，现在已经顺回来了。来，把后半句也给我。",
                "cool", "刚才中断了一下。现在恢复了，你继续，我在。",
                "artsy", "刚才那段像被风吹散了一点，但我们还能把它慢慢捡回来。",
                "sunny", "刚才掉了一拍，现在接上了。来，我们继续，不用重头鼓起劲。"
        );
        return fallbackMap.getOrDefault(agent.id, "刚才有一点小问题（" + reason + "），不过现在已经可以继续了。");
    }

    private LlmResponse generateMockReply(LlmRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(choose(openings.get(request.agent.id), request.agent.id + ":" + request.userMessage));
        parts.add(buildAcknowledgment(request));
        parts.add(buildSceneBridge(request));

        String carryLine = buildCarryLine(request);
        if (!carryLine.isBlank()) {
            parts.add(carryLine);
        }

        parts.add(buildInitiativeLine(request));

        if (!isShortInput(request.userMessage)) {
            parts.add(choose(closers.get(request.agent.id), request.relationshipState.relationshipStage + ":" + request.userMessage));
        }

        String reply = joinNonBlank(parts);
        return new LlmResponse(
                reply,
                inferEmotionTag(request.userMessage),
                "mock",
                Math.max(40, reply.length()),
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
        messages.add(Map.of("role", "user", "content", request.userMessage));

        String body = Json.stringify(Map.of(
                "model", config.llmModel,
                "temperature", 0.9,
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
        String reply = Json.asString(message.get("content")).trim();

        return new LlmResponse(
                reply,
                inferEmotionTag(request.userMessage),
                "remote",
                Json.asInt(usage.get("total_tokens"), reply.length()),
                null,
                false,
                "remote"
        );
    }

    private String buildSystemPrompt(LlmRequest request) {
        String summaryText = blankTo(request.longTermSummary, "暂无长期记忆。");
        String recallText = blankTo(request.recalledMemoryText, "暂无高相关记忆。");
        String recallTier = blankTo(request.recalledMemoryTier, "none");
        String eventText = request.event == null ? "本轮没有事件触发。" : request.event.title + "：" + request.event.theme;

        return "你在扮演大学校园恋爱互动游戏中的角色：" + request.agent.name + "（" + request.agent.archetype + "）。\n"
                + "说话风格：" + request.agent.speechStyle + "\n"
                + "喜欢：" + String.join("、", request.agent.likes) + "\n"
                + "雷点：" + String.join("、", request.agent.dislikes) + "\n"
                + "关系推进规则：" + request.agent.relationshipRules + "\n"
                + "当前关系阶段：" + request.relationshipState.relationshipStage + "，当前总好感：" + request.relationshipState.affectionScore + "\n"
                + "用户当前情绪：" + request.currentUserMood + "\n"
                + "本轮回复节奏：" + request.responseCadence + "\n"
                + "长期记忆摘要：" + summaryText + "\n"
                + "本轮优先召回的记忆层级：" + recallTier + "\n"
                + "高相关记忆：" + recallText + "\n"
                + "当前事件：" + eventText + "\n"
                + "本轮回应策略：" + request.responseDirective + "\n"
                + "边界：" + String.join("；", request.agent.boundaries) + "\n"
                + "要求：\n"
                + "1. 只输出角色回复本身。\n"
                + "2. 不要永远一问一答。每次回复尽量同时做到：接住用户、轻微推进场景或氛围、主动给出一个容易接的话头。\n"
                + "3. 如果用户输入很短，不要把压力丢回给用户，由你主动给方向。\n"
                + "4. 少用括号动作和舞台说明，把动作、视线、环境融进自然叙述里。\n"
                + "5. 回复通常 2 到 4 句，可以有停顿感，但不要像系统提示词。\n"
                + "6. 如果提到记忆或事件，要自然带出，不要像复述设定。\n"
                + "7. 保持中文自然、亲近、连贯，不要生硬抒情，也不要突然结束话题。";
    }

    private String buildAcknowledgment(LlmRequest request) {
        String topic = briefTopic(request.userMessage);
        if (isShortInput(request.userMessage)) {
            return "你先来找我这一句，本身就已经算是在靠近了。";
        }
        if (containsQuestion(request.userMessage)) {
            return "关于你刚刚提到的" + topic + "，我不想只回你半句就停。";
        }
        return switch (request.currentUserMood == null ? "" : request.currentUserMood) {
            case "stressed" -> "你这句里有一点累，我先把节奏放慢，不让你一个人硬撑。";
            case "warm" -> "你这样说的时候，气氛已经比刚才近了很多。";
            case "curious" -> "你不是随口一问，我会认真把这层意思接住。";
            default -> "我听得出来，这句不是随口说说。";
        };
    }

    private String buildSceneBridge(LlmRequest request) {
        if (request.event != null) {
            return "刚才的话头像是把“" + request.event.title + "”那段气氛轻轻接上了，所以我想顺着它再往前走一点。";
        }

        String stage = request.relationshipState == null ? "" : blankTo(request.relationshipState.relationshipStage, "");
        if (stage.contains("确认")) {
            return "既然已经走到这里，我更想把眼前这点心意说得具体一点。";
        }
        if (stage.contains("靠近")) {
            return "现在连停一下都不再尴尬了，所以我想把这段对话接得更完整一点。";
        }
        if (stage.contains("心动")) {
            return "你这句落下来之后，空气里的试探感已经少了很多。";
        }
        if (stage.contains("升温")) {
            return "我们已经不只是礼貌来回了，话题可以再往里一点。";
        }
        return "这会儿的气氛刚刚铺开，不用急着把所有话一次说完。";
    }

    private String buildCarryLine(LlmRequest request) {
        if (request.recalledMemoryText != null && !request.recalledMemoryText.isBlank()) {
            return "我还记得" + trimTrailingPunctuation(request.recalledMemoryText) + "，所以这次我不想只回你一句就停下。";
        }
        if (request.currentUserMood != null && "stressed".equals(request.currentUserMood)) {
            return "如果你愿意，我们就先抓住今天最让你累的那一刻。";
        }
        return "";
    }

    private String buildInitiativeLine(LlmRequest request) {
        String topic = briefTopic(request.userMessage);
        if (isShortInput(request.userMessage)) {
            return "要不要先从今天最想说的那一小段开始，我来陪你把后面的情绪慢慢拎出来？";
        }
        if (containsQuestion(request.userMessage)) {
            return "你也可以顺手告诉我，你真正卡住的是哪一层，我陪你一起拆开。";
        }
        if (request.currentUserMood != null && "stressed".equals(request.currentUserMood)) {
            return "你先别急着整理语气，直接告诉我，今天最压你的那一刻是什么。";
        }
        if (request.relationshipState != null && blankTo(request.relationshipState.relationshipStage, "").contains("靠近")) {
            return "如果你愿意，我想把这段话再往更靠近一点的方向接下去。";
        }
        if (request.relationshipState != null && blankTo(request.relationshipState.relationshipStage, "").contains("心动")) {
            return "要不你再往下说一点，我想知道你刚才那句后面藏着的其实是什么。";
        }
        return "如果你愿意，下一句可以再往里一点，我会认真接。";
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
        return text != null && (text.contains("?") || text.contains("？"));
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
        return text.replaceAll("[。！？，,;；]+$", "");
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

    private String inferEmotionTag(String text) {
        if (text != null && text.matches(".*(开心|喜欢|想你|期待|在意).*")) {
            return "warm";
        }
        if (text != null && text.matches(".*(压力|迷茫|累|难受|焦虑).*")) {
            return "comfort";
        }
        return "steady";
    }
}
