package com.campuspulse;

import java.io.IOException;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class EmotionState implements Serializable {
    int warmth;
    int safety;
    int longing;
    int initiative;
    int vulnerability;
    String currentMood;
    String updatedAt;
}

class PlotState implements Serializable {
    int beatIndex;
    String phase;
    String sceneFrame;
    List<String> openThreads = new ArrayList<>();
    int lastPlotTurn;
    int forcePlotAtTurn;
    String plotProgress;
    String nextBeatHint;
    String updatedAt;
}

class PresenceState implements Serializable {
    boolean visible;
    boolean focused;
    boolean online;
    boolean typing;
    int draftLength;
    String openedAt;
    String lastHeartbeatAt;
    String lastSeenAt;
    String lastUserMessageAt;
    String lastSilenceHeartbeatAt;
    String lastLongHeartbeatAt;
    String lastInputAt;
    String blockedReason;
    String triggerReason;
    String heartbeatExplain;
}

class MemoryUsePlan implements Serializable {
    String useMode;
    String relevanceReason;
    List<String> selectedMemories = new ArrayList<>();
    List<String> callbackCandidates = new ArrayList<>();
    List<String> assistantOwnedThreads = new ArrayList<>();
    String mergedMemoryText;
}

class TimeContext implements Serializable {
    String timezone;
    String localTime;
    String dayPart;
    String frame;
}

class WeatherContext implements Serializable {
    String city;
    String summary;
    Integer temperatureC;
    boolean live;
    String updatedAt;
}

class PlotDecision {
    final PlotState nextPlotState;
    final PlotArcState nextPlotArcState;
    final SceneState nextSceneState;
    final boolean advanced;
    final String replySource;
    final String sceneFrame;
    final String sceneText;

    PlotDecision(
            PlotState nextPlotState,
            PlotArcState nextPlotArcState,
            SceneState nextSceneState,
            boolean advanced,
            String replySource,
            String sceneFrame,
            String sceneText
    ) {
        this.nextPlotState = nextPlotState;
        this.nextPlotArcState = nextPlotArcState;
        this.nextSceneState = nextSceneState;
        this.advanced = advanced;
        this.replySource = replySource;
        this.sceneFrame = sceneFrame;
        this.sceneText = sceneText;
    }

    PlotDecision(PlotState nextPlotState, boolean advanced, String replySource, String sceneFrame) {
        this(nextPlotState, null, null, advanced, replySource, sceneFrame, "");
    }
}

class AffectionScoreResult {
    final TurnEvaluation turnEvaluation;
    final EmotionState nextEmotion;

    AffectionScoreResult(TurnEvaluation turnEvaluation, EmotionState nextEmotion) {
        this.turnEvaluation = turnEvaluation;
        this.nextEmotion = nextEmotion;
    }
}

class PresenceResult {
    final PresenceState nextState;
    final boolean shouldSend;
    final String replySource;
    final String triggerReason;
    final String blockedReason;
    final String heartbeatExplain;

    PresenceResult(
            PresenceState nextState,
            boolean shouldSend,
            String replySource,
            String triggerReason,
            String blockedReason,
            String heartbeatExplain
    ) {
        this.nextState = nextState;
        this.shouldSend = shouldSend;
        this.replySource = replySource;
        this.triggerReason = triggerReason;
        this.blockedReason = blockedReason;
        this.heartbeatExplain = heartbeatExplain;
    }

    PresenceResult(PresenceState nextState, boolean shouldSend, String replySource) {
        this(nextState, shouldSend, replySource, replySource, "", "");
    }
}

class SearchDecision {
    final boolean enabled;
    final String query;
    final String reason;

    SearchDecision(boolean enabled, String query, String reason) {
        this.enabled = enabled;
        this.query = query;
        this.reason = reason;
    }
}

class SocialMemoryService extends AdaptiveMemoryService {
    SocialMemoryService(long retentionMs) {
        super(retentionMs);
    }

    @Override
    MemorySummary normalizeSummary(MemorySummary summary, String nowIso) {
        MemorySummary next = super.normalizeSummary(summary, nowIso);
        if (next.callbackCandidates == null) {
            next.callbackCandidates = new ArrayList<>();
        }
        if (next.assistantOwnedThreads == null) {
            next.assistantOwnedThreads = new ArrayList<>();
        }
        if (next.lastMemoryUseMode == null || next.lastMemoryUseMode.isBlank()) {
            next.lastMemoryUseMode = "hold";
        }
        if (next.lastMemoryRelevanceReason == null || next.lastMemoryRelevanceReason.isBlank()) {
            next.lastMemoryRelevanceReason = "当前没有需要特意提起的记忆";
        }
        trimTo(next.callbackCandidates, 8);
        trimTo(next.assistantOwnedThreads, 8);
        StoryEvent event = null;
        if (!next.sharedMoments.isEmpty()) {
            pushUniqueLimited(next.assistantOwnedThreads, "我还惦记着：" + next.sharedMoments.get(0), 8);
        } else if (event != null) {
            pushUniqueLimited(next.assistantOwnedThreads, "我还在想着刚才那段“" + event.title + "”的气氛", 8);
        }
        return next;
    }

    @Override
    MemorySummary updateTieredSummary(MemorySummary summary, String userMessage, StoryEvent event, String relationshipStage, String nowIso) {
        MemorySummary next = super.updateTieredSummary(summary, userMessage, event, relationshipStage, nowIso);
        next = normalizeSummary(next, nowIso);

        String planCandidate = extractPlanCandidate(userMessage);
        if (!planCandidate.isBlank()) {
            pushUniqueLimited(next.promises, planCandidate, 5);
            pushUniqueLimited(next.callbackCandidates, planCandidate, 8);
        }

        String callbackTopic = extractCallbackTopic(userMessage);
        if (!callbackTopic.isBlank()) {
            pushUniqueLimited(next.callbackCandidates, callbackTopic, 8);
        }

        if (!next.promises.isEmpty()) {
            pushUniqueLimited(next.callbackCandidates, next.promises.get(0), 8);
        }
        if (!next.openLoops.isEmpty()) {
            pushUniqueLimited(next.callbackCandidates, next.openLoops.get(0), 8);
        }
        if (!next.sharedMoments.isEmpty()) {
            pushUniqueLimited(next.assistantOwnedThreads, "我还惦记着：" + next.sharedMoments.get(0), 8);
        } else if (event != null) {
            pushUniqueLimited(next.assistantOwnedThreads, "我还在想着刚才那段“" + event.title + "”的气氛", 8);
        }
        return next;
    }

    @Override
    MemoryUsePlan planMemoryUse(MemorySummary summary, String userMessage, String replySource, String sceneFrame) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        MemoryUsePlan plan = new MemoryUsePlan();

        String cue = (userMessage == null || userMessage.isBlank()) ? sceneFrame : userMessage;
        MemoryRecall recall = recallRelevantMemories(normalized, cue == null ? "" : cue, 3);
        plan.selectedMemories.addAll(recall.selectedMemories);
        plan.mergedMemoryText = recall.mergedText;

        if (normalized.callbackCandidates != null) {
            plan.callbackCandidates.addAll(normalized.callbackCandidates.stream().limit(3).toList());
        }
        if (normalized.assistantOwnedThreads != null) {
            plan.assistantOwnedThreads.addAll(normalized.assistantOwnedThreads.stream().limit(3).toList());
        }

        if ("silence_heartbeat".equals(replySource) || "long_chat_heartbeat".equals(replySource)) {
            if (!plan.callbackCandidates.isEmpty()) {
                plan.useMode = "light";
                plan.relevanceReason = "适合主动回调还没聊完的话题，让主动消息更像记得你";
                if (plan.mergedMemoryText == null || plan.mergedMemoryText.isBlank()) {
                    plan.mergedMemoryText = String.join("；", plan.callbackCandidates);
                }
            } else if (!plan.assistantOwnedThreads.isEmpty()) {
                plan.useMode = "light";
                plan.relevanceReason = "适合用角色自己惦记的事发起一句自然问候";
                if (plan.mergedMemoryText == null || plan.mergedMemoryText.isBlank()) {
                    plan.mergedMemoryText = String.join("；", plan.assistantOwnedThreads);
                }
            } else {
                plan.useMode = "hold";
                plan.relevanceReason = "主动消息以当前气氛为主，不强行提旧记忆";
            }
        } else if (plan.mergedMemoryText != null && !plan.mergedMemoryText.isBlank()) {
            plan.useMode = plan.selectedMemories.size() >= 2 ? "deep" : "light";
            plan.relevanceReason = "这轮话题与旧记忆有明显关联，适合自然带回";
        } else {
            plan.useMode = "hold";
            plan.relevanceReason = "这轮先接住当前内容，不需要强行提记忆";
        }

        if ("silence_heartbeat".equals(replySource) || "long_chat_heartbeat".equals(replySource)) {
            if (!plan.callbackCandidates.isEmpty()) {
                plan.relevanceReason = "适合主动回调还没聊完的话题，让主动消息更像记得你";
                if (plan.mergedMemoryText == null || plan.mergedMemoryText.isBlank()) {
                    plan.mergedMemoryText = String.join("；", plan.callbackCandidates);
                }
            } else if (!plan.assistantOwnedThreads.isEmpty()) {
                plan.relevanceReason = "适合用角色自己惦记的事发起一句自然问候";
                if (plan.mergedMemoryText == null || plan.mergedMemoryText.isBlank()) {
                    plan.mergedMemoryText = String.join("；", plan.assistantOwnedThreads);
                }
            } else {
                plan.relevanceReason = "主动消息以当前气氛为主，不强行提旧记忆";
            }
        } else if (plan.mergedMemoryText != null && !plan.mergedMemoryText.isBlank()) {
            plan.relevanceReason = "这轮话题与旧记忆有明显关联，适合自然带回";
        } else {
            plan.relevanceReason = "这轮先接住当前内容，不需要强行提记忆";
        }

        normalized.lastMemoryUseMode = plan.useMode;
        normalized.lastMemoryRelevanceReason = plan.relevanceReason;
        return plan;
    }

    private String extractPlanCandidate(String userMessage) {
        String segment = firstSegmentWith(userMessage, List.of("下次", "明天", "周末", "改天", "以后", "一起", "约好"));
        if (segment.isBlank()) {
            return "";
        }
        if (!segment.contains("图书馆") && userMessage != null && userMessage.contains("图书馆")) {
            return segment + "，去图书馆";
        }
        if (!segment.contains("散步") && userMessage != null && userMessage.contains("散步")) {
            return segment + "，一起散步";
        }
        return segment;
    }

    private String extractCallbackTopic(String userMessage) {
        return firstSegmentWith(userMessage, List.of("图书馆", "下雨", "雨天", "热可可", "夜市", "天台", "晚风"));
    }

    private String firstSegmentWith(String userMessage, List<String> markers) {
        if (userMessage == null || userMessage.isBlank()) {
            return "";
        }
        String normalized = userMessage.trim().replace('；', '，').replace(';', '，');
        String[] segments = normalized.split("[，。！？?!]");
        for (String segment : segments) {
            String candidate = segment.trim();
            if (candidate.isBlank()) {
                continue;
            }
            for (String marker : markers) {
                if (candidate.contains(marker)) {
                    return candidate;
                }
            }
        }
        return "";
    }

    private void trimTo(List<String> values, int limit) {
        while (values.size() > limit) {
            values.remove(values.size() - 1);
        }
    }

    private void pushUniqueLimited(List<String> target, String value, int limit) {
        if (value == null || value.isBlank()) {
            return;
        }
        target.remove(value);
        target.add(0, value);
        trimTo(target, limit);
    }
}

class EnhancedSocialMemoryService extends SocialMemoryService {
    EnhancedSocialMemoryService(long retentionMs) {
        super(retentionMs);
    }

    @Override
    MemorySummary normalizeSummary(MemorySummary summary, String nowIso) {
        MemorySummary next = super.normalizeSummary(summary, nowIso);
        if (next.factMemories == null) {
            next.factMemories = new ArrayList<>();
        }
        if (next.sceneLedger == null) {
            next.sceneLedger = new ArrayList<>();
        }
        if (next.openLoopItems == null) {
            next.openLoopItems = new ArrayList<>();
        }
        return next;
    }

    @Override
    String getSummaryText(MemorySummary summary) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        List<String> lines = new ArrayList<>();
        String base = super.getSummaryText(normalized);
        if (!base.isBlank()) {
            lines.add(base);
        }
        List<String> facts = normalized.factMemories.stream()
                .filter(item -> item != null && item.value != null && !item.value.isBlank() && item.supersededBy == null)
                .limit(6)
                .map(item -> item.value)
                .toList();
        if (!facts.isEmpty()) {
            lines.add("已确认事实：" + String.join("；", facts));
        }
        List<String> scenes = normalized.sceneLedger.stream()
                .filter(item -> item != null && item.summary != null && !item.summary.isBlank())
                .limit(6)
                .map(item -> item.summary)
                .toList();
        if (!scenes.isEmpty()) {
            lines.add("共同场景：" + String.join("；", scenes));
        }
        List<String> openLoops = normalized.openLoopItems.stream()
                .filter(item -> item != null && item.summary != null && !item.summary.isBlank() && !item.resolved)
                .limit(6)
                .map(item -> item.summary)
                .toList();
        if (!openLoops.isEmpty()) {
            lines.add("未完成线索：" + String.join("；", openLoops));
        }
        return String.join("\n", lines);
    }

    @Override
    MemorySummary updateTieredSummary(MemorySummary summary, String userMessage, StoryEvent event, String relationshipStage, String nowIso) {
        MemorySummary next = super.updateTieredSummary(summary, userMessage, event, relationshipStage, nowIso);
        next = normalizeSummary(next, nowIso);
        rememberFacts(next, userMessage, nowIso);
        rememberScene(next, userMessage, nowIso);
        rememberOpenLoops(next, userMessage, nowIso);
        return next;
    }

    @Override
    MemoryUsePlan planMemoryUse(MemorySummary summary, String userMessage, String replySource, String sceneFrame) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        MemoryUsePlan plan = super.planMemoryUse(normalized, userMessage, replySource, sceneFrame);
        List<String> sceneCandidates = normalized.sceneLedger.stream()
                .filter(item -> item != null && item.summary != null && !item.summary.isBlank())
                .filter(item -> sceneFrame == null || sceneFrame.isBlank() || item.location == null || sceneFrame.contains(item.location))
                .limit(2)
                .map(item -> item.summary)
                .toList();
        if (("silence_heartbeat".equals(replySource) || "long_chat_heartbeat".equals(replySource)) && !sceneCandidates.isEmpty()) {
            plan.useMode = "light";
            plan.relevanceReason = "优先承接当前场景，避免主动消息跳回旧地点。";
            plan.selectedMemories.addAll(sceneCandidates);
            plan.mergedMemoryText = String.join("；", sceneCandidates);
        }
        return plan;
    }

    private void rememberFacts(MemorySummary summary, String userMessage, String nowIso) {
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }
        rememberFact(summary, "city_area", extractFact(userMessage, List.of("我之前在", "我在", "我之前一直在")), "confirmed", nowIso);
        rememberFact(summary, "drink_preference", extractFact(userMessage, List.of("我喜欢", "我最喜欢", "我更喜欢")), "confirmed", nowIso);
        rememberFact(summary, "school_place", extractFact(userMessage, List.of("我一般在", "我常在")), "likely", nowIso);
    }

    private void rememberScene(MemorySummary summary, String userMessage, String nowIso) {
        String scene = "";
        String location = "";
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }
        if (userMessage.contains("宿舍")) {
            location = "宿舍";
            scene = "你们的话题已经走到宿舍附近这一段了。";
        } else if (userMessage.contains("图书馆")) {
            location = "图书馆";
            scene = "你们在图书馆相关的场景里继续把话说下去。";
        } else if (userMessage.contains("食堂")) {
            location = "食堂";
            scene = "场景被带到了食堂这一侧，更像日常陪伴。";
        } else if (userMessage.contains("路上") || userMessage.contains("送她回")) {
            location = "路上";
            scene = "场景开始从原地移动，变成并肩走着继续聊天。";
        }
        if (scene.isBlank()) {
            return;
        }
        for (SceneLedgerItem item : summary.sceneLedger) {
            if (item != null && scene.equals(item.summary)) {
                item.updatedAt = nowIso;
                return;
            }
        }
        SceneLedgerItem item = new SceneLedgerItem();
        item.sceneId = "scene-" + Math.abs(scene.hashCode());
        item.location = location;
        item.summary = scene;
        item.updatedAt = nowIso;
        summary.sceneLedger.add(0, item);
        while (summary.sceneLedger.size() > 12) {
            summary.sceneLedger.remove(summary.sceneLedger.size() - 1);
        }
    }

    private void rememberOpenLoops(MemorySummary summary, String userMessage, String nowIso) {
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }
        if (userMessage.contains("下次") || userMessage.contains("以后") || userMessage.contains("改天")) {
            upsertOpenLoop(summary, "plan:" + compact(userMessage), "你提过后续还想继续这件事。", "plan", nowIso);
        }
        if (userMessage.contains("?") || userMessage.contains("？")) {
            upsertOpenLoop(summary, "question:" + compact(userMessage), "你刚刚还有一个问题没有完全收尾。", "question", nowIso);
        }
    }

    private void rememberFact(MemorySummary summary, String key, String value, String confidence, String nowIso) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (FactMemoryItem item : summary.factMemories) {
            if (item != null && key.equals(item.key) && item.supersededBy == null && !value.equals(item.value)) {
                item.supersededBy = value;
            }
        }
        for (FactMemoryItem item : summary.factMemories) {
            if (item != null && key.equals(item.key) && value.equals(item.value) && item.supersededBy == null) {
                item.confidence = confidence;
                item.updatedAt = nowIso;
                return;
            }
        }
        FactMemoryItem item = new FactMemoryItem();
        item.key = key;
        item.value = value;
        item.confidence = confidence;
        item.updatedAt = nowIso;
        summary.factMemories.add(0, item);
        while (summary.factMemories.size() > 12) {
            summary.factMemories.remove(summary.factMemories.size() - 1);
        }
    }

    private void upsertOpenLoop(MemorySummary summary, String id, String text, String sourceType, String nowIso) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (OpenLoopItem item : summary.openLoopItems) {
            if (item != null && id.equals(item.id)) {
                item.summary = text;
                item.sourceType = sourceType;
                item.resolved = false;
                item.updatedAt = nowIso;
                return;
            }
        }
        OpenLoopItem item = new OpenLoopItem();
        item.id = id;
        item.summary = text;
        item.sourceType = sourceType;
        item.resolved = false;
        item.updatedAt = nowIso;
        summary.openLoopItems.add(0, item);
        while (summary.openLoopItems.size() > 10) {
            summary.openLoopItems.remove(summary.openLoopItems.size() - 1);
        }
    }

    private String extractFact(String userMessage, List<String> markers) {
        if (userMessage == null || userMessage.isBlank()) {
            return "";
        }
        for (String marker : markers) {
            int start = userMessage.indexOf(marker);
            if (start >= 0) {
                String value = userMessage.substring(start + marker.length()).trim();
                int stop = value.length();
                for (String token : List.of("，", "。", "！", "？", ",", ".", "!", "?", "；", ";")) {
                    int index = value.indexOf(token);
                    if (index >= 0) {
                        stop = Math.min(stop, index);
                    }
                }
                return value.substring(0, stop).trim();
            }
        }
        return "";
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}

class AffectionJudgeService {
    AffectionScoreResult evaluateTurn(
            String userMessage,
            RelationshipState relationshipState,
            EmotionState emotionState,
            StoryEvent event,
            MemorySummary memorySummary,
            RelationshipService relationshipService,
            String nowIso
    ) {
        TurnEvaluation evaluation = relationshipService.evaluateTurn(userMessage, relationshipState, event, memorySummary);
        EmotionState nextEmotion = normalizeEmotion(emotionState, nowIso);

        int openness = Math.max(0, evaluation.affectionDelta.trust);
        int closeness = Math.max(0, evaluation.affectionDelta.closeness);
        int resonance = Math.max(0, evaluation.affectionDelta.resonance);
        boolean strained = evaluation.riskFlags.contains("boundary_hit") || evaluation.riskFlags.contains("low_quality_turn");

        nextEmotion.warmth = clamp(nextEmotion.warmth + closeness + resonance / 2 - (strained ? 2 : 0), 0, 100);
        nextEmotion.safety = clamp(nextEmotion.safety + openness + (evaluation.behaviorTags.contains("尊重边界") ? 2 : 0) - (strained ? 3 : 0), 0, 100);
        nextEmotion.longing = clamp(nextEmotion.longing + closeness + (evaluation.stageChanged ? 3 : 0) - (strained ? 2 : 0), 0, 100);
        nextEmotion.initiative = clamp(nextEmotion.initiative + (evaluation.affectionDelta.total > 0 ? 2 : -2) + (evaluation.behaviorTags.contains("主动回应") ? 1 : 0), 0, 100);
        nextEmotion.vulnerability = clamp(nextEmotion.vulnerability + openness + (evaluation.behaviorTags.contains("接住情绪") ? 2 : 0) - (strained ? 1 : 0), 0, 100);
        nextEmotion.currentMood = deriveMood(nextEmotion, evaluation);
        nextEmotion.updatedAt = nowIso;

        return new AffectionScoreResult(evaluation, nextEmotion);
    }

    EmotionState applyChoiceOutcome(EmotionState previous, ChoiceOption choice, RelationshipState nextState, String nowIso) {
        EmotionState next = normalizeEmotion(previous, nowIso);
        switch (choice.outcomeType) {
            case "success" -> {
                next.warmth = clamp(next.warmth + 6, 0, 100);
                next.safety = clamp(next.safety + 6, 0, 100);
                next.longing = clamp(next.longing + 7, 0, 100);
                next.initiative = clamp(next.initiative + 5, 0, 100);
                next.vulnerability = clamp(next.vulnerability + 5, 0, 100);
            }
            case "fail" -> {
                next.warmth = clamp(next.warmth - 3, 0, 100);
                next.safety = clamp(next.safety - 6, 0, 100);
                next.longing = clamp(next.longing - 4, 0, 100);
                next.initiative = clamp(next.initiative - 5, 0, 100);
                next.vulnerability = clamp(next.vulnerability - 4, 0, 100);
            }
            default -> {
                next.warmth = clamp(next.warmth + 2, 0, 100);
                next.longing = clamp(next.longing + 2, 0, 100);
            }
        }
        next.currentMood = "继续发展".equals(nextState.endingCandidate) ? "warm" : deriveMood(next, new TurnEvaluation(nextState, new Delta()));
        next.updatedAt = nowIso;
        return next;
    }

    EmotionState coolDownForSilence(EmotionState previous, String nowIso) {
        EmotionState next = normalizeEmotion(previous, nowIso);
        next.initiative = clamp(next.initiative - 2, 0, 100);
        if (next.initiative < 25 && next.currentMood != null && !next.currentMood.equals("uneasy")) {
            next.currentMood = "calm";
        }
        next.updatedAt = nowIso;
        return next;
    }

    EmotionState createInitial(String nowIso) {
        EmotionState emotion = new EmotionState();
        emotion.warmth = 18;
        emotion.safety = 20;
        emotion.longing = 12;
        emotion.initiative = 18;
        emotion.vulnerability = 10;
        emotion.currentMood = "calm";
        emotion.updatedAt = nowIso;
        return emotion;
    }

    EmotionState normalizeEmotion(EmotionState emotion, String nowIso) {
        if (emotion == null) {
            return createInitial(nowIso);
        }
        if (emotion.currentMood == null || emotion.currentMood.isBlank()) {
            emotion.currentMood = "calm";
        }
        if (emotion.updatedAt == null || emotion.updatedAt.isBlank()) {
            emotion.updatedAt = nowIso;
        }
        return emotion;
    }

    private String deriveMood(EmotionState emotion, TurnEvaluation evaluation) {
        if (evaluation.riskFlags.contains("boundary_hit")) {
            return "uneasy";
        }
        if (emotion.safety >= 45 && emotion.warmth >= 45) {
            return "warm";
        }
        if (emotion.initiative >= 55 && emotion.longing >= 40) {
            return "teasing";
        }
        if (emotion.vulnerability >= 40 && evaluation.behaviorTags.contains("真诚分享")) {
            return "protective";
        }
        return "calm";
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

class PlotDirectorService {
    PlotState normalizePlot(PlotState plotState, String nowIso) {
        PlotState next = plotState == null ? new PlotState() : plotState;
        if (next.openThreads == null) {
            next.openThreads = new ArrayList<>();
        }
        if (next.phase == null || next.phase.isBlank()) {
            next.phase = "相识";
        }
        if (next.sceneFrame == null || next.sceneFrame.isBlank()) {
            next.sceneFrame = "夜色刚刚落稳，这段聊天也才把气氛慢慢热起来。";
        }
        if (next.plotProgress == null || next.plotProgress.isBlank()) {
            next.plotProgress = "第 0/10 拍 · 相识";
        }
        if (next.nextBeatHint == null || next.nextBeatHint.isBlank()) {
            next.nextBeatHint = "先把日常节奏聊顺。";
        }
        if (next.forcePlotAtTurn <= 0) {
            next.forcePlotAtTurn = 4;
        }
        if (next.updatedAt == null || next.updatedAt.isBlank()) {
            next.updatedAt = nowIso;
        }
        return next;
    }

    PlotDecision decide(
            SessionRecord session,
            String userMessage,
            EmotionState emotionState,
            RelationshipState relationshipState,
            MemorySummary memorySummary,
            TimeContext timeContext,
            WeatherContext weatherContext,
            String replySource,
            String nowIso
    ) {
        PlotState current = normalizePlot(session.plotState, nowIso);
        PlotState next = clonePlot(current);
        int currentTurn = "user_turn".equals(replySource) ? session.userTurnCount + 1 : session.userTurnCount;
        int gap = Math.max(0, currentTurn - current.lastPlotTurn);
        boolean canAdvance = current.beatIndex < 10;

        int signal = sceneSignal(userMessage, memorySummary, emotionState, weatherContext, timeContext, replySource);
        boolean forced = canAdvance && currentTurn >= current.forcePlotAtTurn;
        boolean naturalAdvance = canAdvance && gap >= 2 && signal >= 2;
        boolean heartbeatAdvance = canAdvance && "long_chat_heartbeat".equals(replySource) && gap >= 2;
        boolean advanced = forced || naturalAdvance || heartbeatAdvance;

        if (advanced) {
            next.beatIndex = Math.min(10, current.beatIndex + 1);
            next.phase = phaseForBeat(next.beatIndex);
            next.lastPlotTurn = currentTurn;
            next.forcePlotAtTurn = currentTurn + 5;
            next.sceneFrame = buildSceneFrame(next.phase, userMessage, emotionState, timeContext, weatherContext);
            next.nextBeatHint = nextBeatHint(next.beatIndex);
            next.plotProgress = "第 " + next.beatIndex + "/10 拍 · " + next.phase;
            pushUniqueLimited(next.openThreads, openThread(userMessage, memorySummary, next.phase), 6);
            next.updatedAt = nowIso;
            return new PlotDecision(next, true, "plot_push", next.sceneFrame);
        }

        next.sceneFrame = buildAmbientScene(current.sceneFrame, emotionState, timeContext, weatherContext);
        next.updatedAt = nowIso;
        return new PlotDecision(next, false, replySource, next.sceneFrame);
    }

    PlotState applyChoiceOutcome(PlotState plotState, StoryEvent event, ChoiceOption choice, TimeContext timeContext, WeatherContext weatherContext, String nowIso) {
        PlotState next = normalizePlot(plotState, nowIso);
        if (next.beatIndex < 10) {
            next.beatIndex = Math.min(10, next.beatIndex + 1);
        }
        next.phase = phaseForBeat(next.beatIndex);
        next.sceneFrame = "“" + event.title + "”之后，气氛一下子变得更" + switch (choice.outcomeType) {
            case "success" -> "靠近";
            case "fail" -> "克制";
            default -> "暧昧";
        } + "了。";
        next.nextBeatHint = nextBeatHint(next.beatIndex);
        next.plotProgress = "第 " + next.beatIndex + "/10 拍 · " + next.phase;
        pushUniqueLimited(next.openThreads, "顺着“" + event.title + "”之后的变化继续往前走", 6);
        next.updatedAt = nowIso;
        return next;
    }

    private int sceneSignal(String userMessage, MemorySummary memorySummary, EmotionState emotionState, WeatherContext weatherContext, TimeContext timeContext, String replySource) {
        int signal = 0;
        String text = userMessage == null ? "" : userMessage;
        if (text.length() >= 10) signal++;
        if (text.contains("上次") || text.contains("之前") || text.contains("还记得")) signal++;
        if (text.contains("一起") || text.contains("下次") || text.contains("以后")) signal++;
        if (memorySummary != null && !memorySummary.openLoops.isEmpty()) signal++;
        if (emotionState != null && emotionState.longing >= 32) signal++;
        if (weatherContext != null && weatherContext.summary != null && !weatherContext.summary.isBlank()) signal++;
        if (timeContext != null && ("深夜".equals(timeContext.dayPart) || "傍晚".equals(timeContext.dayPart))) signal++;
        if ("long_chat_heartbeat".equals(replySource)) signal += 2;
        return signal;
    }

    private String phaseForBeat(int beatIndex) {
        if (beatIndex >= 9) return "收束";
        if (beatIndex >= 7) return "波动";
        if (beatIndex >= 5) return "拉近";
        if (beatIndex >= 3) return "升温";
        return "相识";
    }

    private String nextBeatHint(int beatIndex) {
        if (beatIndex >= 9) return "可以开始收拢这段关系的走向。";
        if (beatIndex >= 7) return "接下来适合放一点试探或误差。";
        if (beatIndex >= 5) return "可以把情绪承接得更近一些。";
        if (beatIndex >= 3) return "适合让共同节奏更明显。";
        return "先把日常相识的氛围铺开。";
    }

    private String buildSceneFrame(String phase, String userMessage, EmotionState emotionState, TimeContext timeContext, WeatherContext weatherContext) {
        List<String> parts = new ArrayList<>();
        parts.add(switch (phase) {
            case "升温" -> "话题没有停在表面，连语气都比刚才认真了一点。";
            case "拉近" -> "距离像被悄悄收短了一截，说话时已经不太需要绕弯。";
            case "波动" -> "彼此都开始在意了，所以连一小下停顿都像有分量。";
            case "收束" -> "话已经走到该给方向的时候，谁都很难再装作只是随便聊聊。";
            default -> "你们的话才刚热起来，周围还留着一点彼此试探的安静。";
        });

        String topicScene = topicalScene(userMessage, timeContext, weatherContext);
        if (!topicScene.isBlank()) {
            parts.add(topicScene);
        }

        String emotionScene = emotionScene(emotionState);
        if (!emotionScene.isBlank()) {
            parts.add(emotionScene);
        }

        return String.join(" ", parts.stream().filter(part -> part != null && !part.isBlank()).limit(3).toList()).trim();
    }

    private String buildAmbientScene(String currentScene, EmotionState emotionState, TimeContext timeContext, WeatherContext weatherContext) {
        if (currentScene != null && !currentScene.isBlank()) {
            return currentScene;
        }
        String topicScene = topicalScene("", timeContext, weatherContext);
        if (!topicScene.isBlank()) {
            return topicScene;
        }
        String emotionScene = emotionScene(emotionState);
        if (!emotionScene.isBlank()) {
            return emotionScene;
        }
        return "气氛还在缓慢铺开。";
    }

    private String topicalScene(String userMessage, TimeContext timeContext, WeatherContext weatherContext) {
        String text = userMessage == null ? "" : userMessage;
        if (text.contains("图书馆") || text.contains("复习") || text.contains("自习")) {
            return "靠窗那排位置安安静静的，连翻书声都像放轻了一点。";
        }
        if (text.contains("热可可") || text.contains("奶茶") || text.contains("咖啡")) {
            return "杯沿还留着一点热气，连说话声都像被暖得慢了些。";
        }
        if (text.contains("下雨") || text.contains("雨") || text.contains("伞")) {
            return "窗外的雨线把世界压低了一点，话也跟着变轻了。";
        }
        if (text.contains("散步") || text.contains("操场") || text.contains("一起走") || text.contains("走走")) {
            return "风从走廊尽头掠过去，步子和语气都像慢下来一点。";
        }
        if (text.contains("晚安") || text.contains("睡不着") || text.contains("失眠")) {
            return "夜已经深了，很多白天收着的话在这时候反而更容易落下来。";
        }
        if (weatherContext != null && weatherContext.summary != null && weatherContext.summary.contains("雨")) {
            return "外面的雨声没有断，反倒把这一小段安静衬得更近。";
        }
        if (timeContext != null && timeContext.frame != null && !timeContext.frame.isBlank()) {
            return trimSceneText(timeContext.frame);
        }
        return "";
    }

    private String emotionScene(EmotionState emotionState) {
        if (emotionState == null || emotionState.currentMood == null) {
            return "";
        }
        return switch (emotionState.currentMood) {
            case "warm" -> "这一刻的语气明显更软，也更像在等对方把后半句说完。";
            case "teasing" -> "那点想靠近的心思没有藏得太严，语气里已经有了点逗留感。";
            case "protective" -> "连停顿里都带着一点想把人稳稳接住的耐心。";
            case "uneasy" -> "明明还想靠近，语气里却先多留了一层小心。";
            default -> "";
        };
    }

    private String trimSceneText(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.endsWith("。") || normalized.endsWith("！") || normalized.endsWith("？")) {
            return normalized;
        }
        return normalized + "。";
    }

    private String openThread(String userMessage, MemorySummary memorySummary, String phase) {
        if (userMessage != null && !userMessage.isBlank()) {
            return "顺着“" + excerpt(userMessage, 16) + "”继续推进到" + phase;
        }
        if (memorySummary != null && !memorySummary.openLoops.isEmpty()) {
            return memorySummary.openLoops.get(0);
        }
        return "把当前这段气氛再往前推半步";
    }

    private String excerpt(String text, int limit) {
        String compact = text.replaceAll("\\s+", "");
        return compact.length() <= limit ? compact : compact.substring(0, limit) + "…";
    }

    private PlotState clonePlot(PlotState current) {
        PlotState next = new PlotState();
        next.beatIndex = current.beatIndex;
        next.phase = current.phase;
        next.sceneFrame = current.sceneFrame;
        next.openThreads = new ArrayList<>(current.openThreads);
        next.lastPlotTurn = current.lastPlotTurn;
        next.forcePlotAtTurn = current.forcePlotAtTurn;
        next.plotProgress = current.plotProgress;
        next.nextBeatHint = current.nextBeatHint;
        next.updatedAt = current.updatedAt;
        return next;
    }

    private void pushUniqueLimited(List<String> target, String value, int limit) {
        if (value == null || value.isBlank()) {
            return;
        }
        target.remove(value);
        target.add(0, value);
        while (target.size() > limit) {
            target.remove(target.size() - 1);
        }
    }
}

class PresenceHeartbeatService {
    private static final long OFFLINE_SECONDS = 45;
    private static final long SILENCE_MIN_SECONDS = 90;
    private static final long SILENCE_MAX_SECONDS = 150;
    private static final long LONG_CHAT_SECONDS = 6 * 60;

    PresenceState normalizePresence(PresenceState presenceState, String nowIso) {
        PresenceState next = presenceState == null ? new PresenceState() : presenceState;
        if (next.openedAt == null || next.openedAt.isBlank()) {
            next.openedAt = nowIso;
        }
        if (next.lastHeartbeatAt == null || next.lastHeartbeatAt.isBlank()) {
            next.lastHeartbeatAt = nowIso;
        }
        if (next.lastSeenAt == null || next.lastSeenAt.isBlank()) {
            next.lastSeenAt = nowIso;
        }
        return next;
    }

    PresenceState registerUserTurn(PresenceState presenceState, String nowIso) {
        PresenceState next = normalizePresence(presenceState, nowIso);
        next.lastHeartbeatAt = nowIso;
        next.lastSeenAt = nowIso;
        next.lastUserMessageAt = nowIso;
        next.online = true;
        return next;
    }

    PresenceResult ingest(PresenceState presenceState, SessionRecord session, boolean visible, boolean focused, String observedIso) {
        PresenceState next = normalizePresence(presenceState, observedIso);
        next.visible = visible;
        next.focused = focused;
        next.lastHeartbeatAt = observedIso;
        next.lastSeenAt = observedIso;
        next.online = visible && focused;

        if (!next.online || session.pendingChoiceEventId != null && !session.pendingChoiceEventId.isBlank()) {
            return new PresenceResult(next, false, "presence");
        }

        Instant now = Instant.parse(observedIso);
        Instant lastUser = parse(next.lastUserMessageAt, observedIso);
        Instant lastSilence = parse(next.lastSilenceHeartbeatAt, "1970-01-01T00:00:00Z");
        Instant lastLong = parse(next.lastLongHeartbeatAt, "1970-01-01T00:00:00Z");
        Instant opened = parse(next.openedAt, observedIso);
        Instant lastProactive = parse(session.lastProactiveMessageAt, "1970-01-01T00:00:00Z");

        long silenceSeconds = Duration.between(lastUser, now).getSeconds();
        long chatSeconds = Duration.between(opened, now).getSeconds();
        long sinceLastProactive = Duration.between(lastProactive, now).getSeconds();

        if (silenceSeconds >= SILENCE_MIN_SECONDS
                && silenceSeconds <= SILENCE_MAX_SECONDS
                && Duration.between(lastSilence, now).getSeconds() >= SILENCE_MAX_SECONDS
                && sinceLastProactive >= SILENCE_MIN_SECONDS) {
            next.lastSilenceHeartbeatAt = observedIso;
            return new PresenceResult(next, true, "silence_heartbeat");
        }

        if (chatSeconds >= LONG_CHAT_SECONDS
                && Duration.between(lastLong, now).getSeconds() >= LONG_CHAT_SECONDS
                && silenceSeconds >= 30
                && sinceLastProactive >= 120) {
            next.lastLongHeartbeatAt = observedIso;
            return new PresenceResult(next, true, "long_chat_heartbeat");
        }

        if (Duration.between(parse(next.lastHeartbeatAt, observedIso), now).getSeconds() > OFFLINE_SECONDS) {
            next.online = false;
        }
        return new PresenceResult(next, false, "presence");
    }

    private Instant parse(String iso, String fallback) {
        String target = (iso == null || iso.isBlank()) ? fallback : iso;
        return Instant.parse(target);
    }
}

class SceneDirectorService {
    SceneState normalize(SceneState sceneState, String nowIso) {
        SceneState next = sceneState == null ? new SceneState() : sceneState;
        if (next.location == null || next.location.isBlank()) {
            next.location = "图书馆";
        }
        if (next.subLocation == null) {
            next.subLocation = "";
        }
        if (next.interactionMode == null || next.interactionMode.isBlank()) {
            next.interactionMode = "face_to_face";
        }
        if (next.sceneSummary == null || next.sceneSummary.isBlank()) {
            next.sceneSummary = "你们还在同一个场景里，把话慢慢往下接。";
        }
        if (next.updatedAt == null || next.updatedAt.isBlank()) {
            next.updatedAt = nowIso;
        }
        return next;
    }

    SceneState evolve(SceneState sceneState, String userMessage, TimeContext timeContext, WeatherContext weatherContext, int currentTurn, String nowIso) {
        SceneState next = cloneState(normalize(sceneState, nowIso));
        next.timeOfScene = timeContext == null ? "" : timeContext.dayPart;
        next.weatherMood = weatherContext == null ? "" : weatherContext.summary;
        String text = userMessage == null ? "" : userMessage;
        String nextLocation = detectLocation(text, next.location);
        boolean changed = !nextLocation.equals(next.location);
        if (changed) {
            next.location = nextLocation;
            next.subLocation = detectSubLocation(text);
            next.transitionPending = true;
            next.transitionLockUntilTurn = currentTurn + 2;
            next.lastConfirmedSceneTurn = currentTurn;
        } else if (currentTurn >= next.transitionLockUntilTurn) {
            next.transitionPending = false;
        }
        next.interactionMode = detectInteractionMode(text, next.interactionMode);
        next.sceneSummary = buildSceneSummary(next, changed, text);
        next.updatedAt = nowIso;
        return next;
    }

    String buildSceneText(SceneState previous, SceneState next) {
        if (next == null) {
            return "";
        }
        if (previous == null || !safe(previous.location).equals(safe(next.location))) {
            return "场景顺着你这句话往“" + next.location + "”那边移了过去，接下来的话也更适合在这个位置继续。";
        }
        if (next.transitionPending) {
            return next.sceneSummary;
        }
        return "";
    }

    private String detectLocation(String text, String fallback) {
        if (text.contains("宿舍")) return "宿舍";
        if (text.contains("食堂")) return "食堂";
        if (text.contains("操场")) return "操场";
        if (text.contains("路上") || text.contains("送她回") || text.contains("送你回")) return "回去的路上";
        if (text.contains("窗边")) return "窗边";
        if (text.contains("手机") || text.contains("发消息")) return "线上聊天";
        if (text.contains("图书馆")) return "图书馆";
        return fallback == null || fallback.isBlank() ? "图书馆" : fallback;
    }

    private String detectSubLocation(String text) {
        if (text.contains("窗边")) return "窗边";
        if (text.contains("门口")) return "门口";
        if (text.contains("走廊")) return "走廊";
        return "";
    }

    private String detectInteractionMode(String text, String fallback) {
        if (text.contains("发消息") || text.contains("回消息")) return "phone_chat";
        if (text.contains("送她回") || text.contains("一起走")) return "mixed_transition";
        return fallback == null || fallback.isBlank() ? "face_to_face" : fallback;
    }

    private String buildSceneSummary(SceneState state, boolean changed, String userMessage) {
        if (changed) {
            return "场景已经转到" + state.location + "，你们的话题也该顺着新的位置继续。";
        }
        if (userMessage.contains("下雨")) {
            return "雨声把气氛压低了一点，话也更容易贴近。";
        }
        if (userMessage.contains("送")) {
            return "脚步和话题都在往前走，气氛比原地聊天更近一点。";
        }
        return state.sceneSummary == null || state.sceneSummary.isBlank() ? "你们还在同一个场景里慢慢说话。" : state.sceneSummary;
    }

    private SceneState cloneState(SceneState current) {
        SceneState next = new SceneState();
        next.location = current.location;
        next.subLocation = current.subLocation;
        next.interactionMode = current.interactionMode;
        next.timeOfScene = current.timeOfScene;
        next.weatherMood = current.weatherMood;
        next.transitionPending = current.transitionPending;
        next.transitionLockUntilTurn = current.transitionLockUntilTurn;
        next.lastConfirmedSceneTurn = current.lastConfirmedSceneTurn;
        next.sceneSummary = current.sceneSummary;
        next.updatedAt = current.updatedAt;
        return next;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

class SearchDecisionService {
    SearchDecision decide(String userMessage, String replySource, SceneState sceneState) {
        if (userMessage == null || userMessage.isBlank()) {
            return new SearchDecision(false, "", "empty");
        }
        String text = userMessage.trim();
        boolean realtime = text.contains("天气") || text.contains("新闻") || text.contains("热点") || text.contains("今天");
        boolean factual = text.contains("电影") || text.contains("城市") || text.contains("专业") || text.contains("学校");
        boolean proactiveAnchor = ("plot_push".equals(replySource) || "long_chat_heartbeat".equals(replySource))
                && sceneState != null
                && "线上聊天".equals(sceneState.location);
        if (realtime) {
            return new SearchDecision(true, text, "realtime");
        }
        if (factual && text.length() >= 8) {
            return new SearchDecision(true, text, "factual");
        }
        if (proactiveAnchor) {
            return new SearchDecision(true, text, "anchor");
        }
        return new SearchDecision(false, "", "skip");
    }
}

class EnhancedPlotDirectorService extends PlotDirectorService {
    private final SceneDirectorService sceneDirectorService = new SceneDirectorService();

    @Override
    PlotDecision decide(
            SessionRecord session,
            String userMessage,
            EmotionState emotionState,
            RelationshipState relationshipState,
            MemorySummary memorySummary,
            TimeContext timeContext,
            WeatherContext weatherContext,
            String replySource,
            String nowIso
    ) {
        PlotDecision base = super.decide(session, userMessage, emotionState, relationshipState, memorySummary, timeContext, weatherContext, replySource, nowIso);
        PlotArcState arc = normalizeArc(session.plotArcState, nowIso);
        SceneState previousScene = sceneDirectorService.normalize(session.sceneState, nowIso);
        int currentTurn = "user_turn".equals(replySource) ? session.userTurnCount + 1 : session.userTurnCount;
        SceneState nextScene = sceneDirectorService.evolve(previousScene, userMessage, timeContext, weatherContext, currentTurn, nowIso);

        PlotArcState nextArc = cloneArc(arc);
        nextArc.beatIndex = Math.max(arc.beatIndex, base.nextPlotState == null ? arc.beatIndex : base.nextPlotState.beatIndex);
        nextArc.arcIndex = Math.max(1, ((Math.max(1, nextArc.beatIndex) - 1) / 10) + 1);
        nextArc.phase = base.nextPlotState == null ? arc.phase : base.nextPlotState.phase;
        nextArc.sceneFrame = nextScene.sceneSummary;
        nextArc.openThreads = new ArrayList<>(base.nextPlotState == null ? arc.openThreads : base.nextPlotState.openThreads);
        nextArc.lastPlotTurn = base.nextPlotState == null ? arc.lastPlotTurn : base.nextPlotState.lastPlotTurn;
        nextArc.forcePlotAtTurn = base.nextPlotState == null ? arc.forcePlotAtTurn : base.nextPlotState.forcePlotAtTurn;
        nextArc.plotProgress = "第 " + nextArc.beatIndex + " 拍 / 第 " + nextArc.arcIndex + " 章";
        nextArc.nextBeatHint = base.nextPlotState == null ? arc.nextBeatHint : base.nextPlotState.nextBeatHint;
        nextArc.checkpointReady = base.advanced && nextArc.beatIndex > 0 && nextArc.beatIndex % 10 == 0;
        nextArc.runStatus = nextArc.checkpointReady ? "checkpoint_ready" : "in_progress";
        nextArc.endingCandidate = relationshipState == null ? "" : relationshipState.endingCandidate;
        nextArc.canSettleScore = nextArc.checkpointReady;
        nextArc.canContinue = nextArc.checkpointReady;
        if (nextArc.checkpointReady) {
            nextArc.latestArcSummary = buildCheckpointSummary(nextArc, nextScene, relationshipState, nowIso);
        } else if (nextArc.latestArcSummary == null) {
            nextArc.latestArcSummary = buildCheckpointSummary(nextArc, nextScene, relationshipState, nowIso);
        }
        nextArc.updatedAt = nowIso;
        return new PlotDecision(
                base.nextPlotState,
                nextArc,
                nextScene,
                base.advanced,
                base.replySource,
                nextScene.sceneSummary,
                sceneDirectorService.buildSceneText(previousScene, nextScene)
        );
    }

    PlotArcState continueFromCheckpoint(PlotArcState plotArcState, String nowIso) {
        PlotArcState next = normalizeArc(plotArcState, nowIso);
        next.checkpointReady = false;
        next.runStatus = "in_progress";
        next.canSettleScore = false;
        next.canContinue = false;
        next.updatedAt = nowIso;
        return next;
    }

    PlotArcState settleCheckpoint(PlotArcState plotArcState, RelationshipState relationshipState, SceneState sceneState, String nowIso) {
        PlotArcState next = normalizeArc(plotArcState, nowIso);
        next.checkpointReady = false;
        next.runStatus = "settled";
        next.canSettleScore = false;
        next.canContinue = true;
        next.latestArcSummary = buildCheckpointSummary(next, sceneState, relationshipState, nowIso);
        next.updatedAt = nowIso;
        return next;
    }

    PlotArcState normalizeArc(PlotArcState plotArcState, String nowIso) {
        PlotArcState next = plotArcState == null ? new PlotArcState() : plotArcState;
        if (next.arcIndex <= 0) {
            next.arcIndex = 1;
        }
        if (next.phase == null || next.phase.isBlank()) {
            next.phase = "相识";
        }
        if (next.sceneFrame == null || next.sceneFrame.isBlank()) {
            next.sceneFrame = "剧情刚刚铺开。";
        }
        if (next.openThreads == null) {
            next.openThreads = new ArrayList<>();
        }
        if (next.plotProgress == null || next.plotProgress.isBlank()) {
            next.plotProgress = "第 0 拍 / 第 1 章";
        }
        if (next.nextBeatHint == null || next.nextBeatHint.isBlank()) {
            next.nextBeatHint = "先把当前气氛聊顺。";
        }
        if (next.runStatus == null || next.runStatus.isBlank()) {
            next.runStatus = "in_progress";
        }
        if (next.updatedAt == null || next.updatedAt.isBlank()) {
            next.updatedAt = nowIso;
        }
        if (next.latestArcSummary == null) {
            next.latestArcSummary = buildCheckpointSummary(next, null, null, nowIso);
        }
        return next;
    }

    private ArcCheckpointSummary buildCheckpointSummary(PlotArcState state, SceneState sceneState, RelationshipState relationshipState, String nowIso) {
        ArcCheckpointSummary summary = new ArcCheckpointSummary();
        summary.arcIndex = Math.max(1, state.arcIndex);
        summary.beatEnd = state.beatIndex;
        summary.beatStart = Math.max(1, summary.beatEnd - 9);
        summary.title = "第 " + summary.arcIndex + " 段关系总结";
        summary.routeTheme = state.phase == null ? "相识" : state.phase;
        summary.relationshipSummary = relationshipState == null
                ? "关系还在慢慢铺开。"
                : "当前更接近“" + relationshipState.endingCandidate + "”，阶段停在“" + relationshipState.relationshipStage + "”。";
        summary.sceneSummary = sceneState == null || sceneState.sceneSummary == null || sceneState.sceneSummary.isBlank()
                ? state.sceneFrame
                : sceneState.sceneSummary;
        summary.endingTendency = relationshipState == null ? "" : relationshipState.endingCandidate;
        summary.updatedAt = nowIso;
        return summary;
    }

    private PlotArcState cloneArc(PlotArcState current) {
        PlotArcState next = new PlotArcState();
        next.beatIndex = current.beatIndex;
        next.arcIndex = current.arcIndex;
        next.phase = current.phase;
        next.sceneFrame = current.sceneFrame;
        next.openThreads = new ArrayList<>(current.openThreads);
        next.lastPlotTurn = current.lastPlotTurn;
        next.forcePlotAtTurn = current.forcePlotAtTurn;
        next.plotProgress = current.plotProgress;
        next.nextBeatHint = current.nextBeatHint;
        next.checkpointReady = current.checkpointReady;
        next.runStatus = current.runStatus;
        next.endingCandidate = current.endingCandidate;
        next.canSettleScore = current.canSettleScore;
        next.canContinue = current.canContinue;
        next.latestArcSummary = current.latestArcSummary;
        next.updatedAt = current.updatedAt;
        return next;
    }
}

class EnhancedPresenceHeartbeatService extends PresenceHeartbeatService {
    PresenceResult ingest(
            PresenceState presenceState,
            SessionRecord session,
            boolean visible,
            boolean focused,
            boolean typing,
            int draftLength,
            String lastInputAt,
            String observedIso
    ) {
        PresenceState next = normalizePresence(presenceState, observedIso);
        next.visible = visible;
        next.focused = focused;
        next.typing = typing || draftLength > 0;
        next.draftLength = draftLength;
        next.lastInputAt = lastInputAt == null || lastInputAt.isBlank() ? next.lastInputAt : lastInputAt;
        next.lastHeartbeatAt = observedIso;
        next.lastSeenAt = observedIso;
        next.online = visible && focused;

        if (!next.online) {
            next.blockedReason = "offline";
            next.heartbeatExplain = "页面当前不在线，所以不会主动插话。";
            return new PresenceResult(next, false, "presence", "presence", "offline", next.heartbeatExplain);
        }
        if (session.pendingChoiceEventId != null && !session.pendingChoiceEventId.isBlank()) {
            next.blockedReason = "pending_choice";
            next.heartbeatExplain = "当前有待选剧情，主动消息先暂停。";
            return new PresenceResult(next, false, "presence", "presence", "pending_choice", next.heartbeatExplain);
        }
        if (session.plotArcState != null && session.plotArcState.checkpointReady) {
            next.blockedReason = "checkpoint_ready";
            next.heartbeatExplain = "当前正在等待你决定是否继续这一段剧情。";
            return new PresenceResult(next, false, "presence", "presence", "checkpoint_ready", next.heartbeatExplain);
        }
        if (next.typing) {
            next.blockedReason = "typing";
            next.heartbeatExplain = "你正在输入，系统会先等你把这句话写完。";
            return new PresenceResult(next, false, "presence", "presence", "typing", next.heartbeatExplain);
        }
        if (next.lastInputAt != null && !next.lastInputAt.isBlank()) {
            Instant lastInput = Instant.parse(next.lastInputAt);
            if (Duration.between(lastInput, Instant.parse(observedIso)).getSeconds() < 12) {
                next.blockedReason = "input_cooldown";
                next.heartbeatExplain = "你刚停下输入不久，先不打断这段节奏。";
                return new PresenceResult(next, false, "presence", "presence", "input_cooldown", next.heartbeatExplain);
            }
        }
        PresenceResult base = super.ingest(next, session, visible, focused, observedIso);
        base.nextState.triggerReason = base.shouldSend ? base.replySource : "presence";
        base.nextState.blockedReason = base.shouldSend ? "" : base.blockedReason;
        base.nextState.heartbeatExplain = base.shouldSend
                ? ("silence_heartbeat".equals(base.replySource)
                        ? "你安静了一会儿，角色顺着当前场景轻轻接了一句。"
                        : "你们已经在线聊了一段时间，角色顺势补了一句更自然的推进。")
                : (base.heartbeatExplain == null || base.heartbeatExplain.isBlank() ? "当前没有命中心跳窗口。" : base.heartbeatExplain);
        return new PresenceResult(
                base.nextState,
                base.shouldSend,
                base.replySource,
                base.nextState.triggerReason,
                base.nextState.blockedReason,
                base.nextState.heartbeatExplain
        );
    }
}

class RealityContextService {
    private static final class CachedWeather {
        final WeatherContext weather;
        final Instant expiresAt;

        CachedWeather(WeatherContext weather, Instant expiresAt) {
            this.weather = weather;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, CachedWeather> cache = new LinkedHashMap<>();

    TimeContext buildTimeContext(VisitorRecord visitor, String nowIso) {
        String timezone = visitor == null || visitor.timezone == null || visitor.timezone.isBlank()
                ? ZoneId.systemDefault().getId()
                : visitor.timezone;
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (Exception ignored) {
            zoneId = ZoneId.systemDefault();
            timezone = zoneId.getId();
        }

        ZonedDateTime time = Instant.parse(nowIso).atZone(zoneId);
        int hour = time.getHour();

        TimeContext context = new TimeContext();
        context.timezone = timezone;
        context.localTime = time.format(DateTimeFormatter.ofPattern("HH:mm"));
        context.dayPart = dayPart(hour);
        context.frame = switch (context.dayPart) {
            case "清晨" -> "现在是清晨，气氛更适合轻声问候和慢慢醒过来。";
            case "上午" -> "现在是上午，像是还带着一点要开始一天的认真。";
            case "下午" -> "现在是下午，节奏比白天前半段更松一点。";
            case "傍晚" -> "现在是傍晚，最适合让气氛慢慢变柔和。";
            case "深夜" -> "现在已经很晚了，很多真心话会在这个时间更容易落下来。";
            default -> "现在是晚上，聊天很容易带一点陪伴感。";
        };
        return context;
    }

    WeatherContext buildWeatherContext(VisitorRecord visitor, String nowIso) {
        if (visitor == null || visitor.preferredCity == null || visitor.preferredCity.isBlank()) {
            WeatherContext weather = new WeatherContext();
            weather.city = "";
            weather.summary = "";
            weather.live = false;
            weather.updatedAt = nowIso;
            return weather;
        }

        String city = visitor.preferredCity.trim();
        CachedWeather cached = cache.get(city);
        Instant now = Instant.parse(nowIso);
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached.weather;
        }

        try {
            String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
            HttpURLConnection connection = (HttpURLConnection) URI.create("https://wttr.in/" + encoded + "?format=j1").toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                String response = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> payload = Json.asObject(Json.parse(response));
                List<Object> currentConditions = Json.asArray(payload.get("current_condition"));
                if (!currentConditions.isEmpty()) {
                    Map<String, Object> current = Json.asObject(currentConditions.get(0));
                    List<Object> descList = Json.asArray(current.get("weatherDesc"));
                    String description = "";
                    if (!descList.isEmpty()) {
                        description = Json.asString(Json.asObject(descList.get(0)).get("value"));
                    }

                    WeatherContext weather = new WeatherContext();
                    weather.city = city;
                    weather.summary = description;
                    weather.temperatureC = Json.asInt(current.get("temp_C"), 0);
                    weather.live = true;
                    weather.updatedAt = nowIso;
                    cache.put(city, new CachedWeather(weather, now.plusSeconds(600)));
                    return weather;
                }
            }
        } catch (Exception ignored) {
            // Fall through to time-only context.
        }

        WeatherContext fallback = new WeatherContext();
        fallback.city = city;
        fallback.summary = "";
        fallback.temperatureC = null;
        fallback.live = false;
        fallback.updatedAt = nowIso;
        cache.put(city, new CachedWeather(fallback, now.plusSeconds(300)));
        return fallback;
    }

    private String dayPart(int hour) {
        if (hour >= 5 && hour < 9) return "清晨";
        if (hour >= 9 && hour < 12) return "上午";
        if (hour >= 12 && hour < 17) return "下午";
        if (hour >= 17 && hour < 20) return "傍晚";
        if (hour >= 23 || hour < 2) return "深夜";
        return "晚上";
    }
}
