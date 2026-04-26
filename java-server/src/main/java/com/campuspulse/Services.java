package com.campuspulse;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class Services {
    private Services() {
    }
}

interface StateMutation<T> {
    T apply(AppState state) throws Exception;
}

class StateRepository {
    private final Path stateFile;
    private final Object lock = new Object();

    StateRepository(Path stateFile) {
        this.stateFile = stateFile;
    }

    AppState getState() throws Exception {
        synchronized (lock) {
            return readState();
        }
    }

    <T> T transact(StateMutation<T> mutation) throws Exception {
        synchronized (lock) {
            AppState state = readState();
            T result = mutation.apply(state);
            writeState(state);
            return result;
        }
    }

    private AppState readState() throws Exception {
        ensureFile();
        if (Files.size(stateFile) == 0) {
            return AppState.empty();
        }
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(stateFile))) {
            Object value = input.readObject();
            return value instanceof AppState appState ? appState : AppState.empty();
        } catch (IOException ex) {
            return AppState.empty();
        }
    }

    private void writeState(AppState state) throws Exception {
        ensureFile();
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(stateFile))) {
            output.writeObject(state);
        }
    }

    private void ensureFile() throws Exception {
        Files.createDirectories(stateFile.getParent());
        if (!Files.exists(stateFile)) {
            Files.createFile(stateFile);
        }
    }
}

class AgentConfigService {
    private final List<AgentProfile> agents = EventNarrativeRegistry.enrich(Domain.buildAgents());

    List<Map<String, Object>> listPublicAgents() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentProfile agent : agents) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", agent.id);
            item.put("name", agent.name);
            item.put("gender", agent.gender);
            item.put("subjectPronoun", agent.subjectPronoun);
            item.put("objectPronoun", agent.objectPronoun);
            item.put("possessivePronoun", agent.possessivePronoun);
            item.put("archetype", agent.archetype);
            item.put("tagline", agent.tagline);
            item.put("palette", agent.palette);
            item.put("avatarGlyph", agent.avatarGlyph);
            item.put("bio", agent.bio);
            item.put("likes", agent.likes);
            item.put("portraitAsset", agent.portraitAsset);
            item.put("coverAsset", agent.coverAsset);
            item.put("styleTags", agent.styleTags);
            item.put("moodPalette", agent.moodPalette);
            item.put("backstory", AgentPresentation.backstoryMap(agent.backstory));
            item.put("voiceProfile", AgentPresentation.voiceProfileMap(agent.voiceProfile));
            result.add(item);
        }
        return result;
    }

    AgentProfile getAgentById(String agentId) {
        for (AgentProfile agent : agents) {
            if (Objects.equals(agent.id, agentId)) {
                return agent;
            }
        }
        return null;
    }

    List<AgentProfile> getAgents() {
        return agents;
    }
}

final class AgentPresentation {
    private AgentPresentation() {
    }

    static Map<String, Object> backstoryMap(AgentBackstory backstory) {
        if (backstory == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("age", backstory.age);
        map.put("grade", backstory.grade);
        map.put("major", backstory.major);
        map.put("hometown", backstory.hometown);
        map.put("currentCity", backstory.currentCity);
        map.put("campusPlaces", backstory.campusPlaces);
        map.put("hobbies", backstory.hobbies);
        map.put("lifestyle", backstory.lifestyle);
        map.put("boundaryDetails", backstory.boundaryDetails);
        map.put("emotionPattern", backstory.emotionPattern);
        map.put("hiddenFacts", backstory.hiddenFacts);
        map.put("plotHooks", backstory.plotHooks);
        return map;
    }

    static Map<String, Object> voiceProfileMap(AgentVoiceProfile voiceProfile) {
        if (voiceProfile == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sentenceRhythm", voiceProfile.sentenceRhythm);
        map.put("openings", voiceProfile.openings);
        map.put("signatureMoves", voiceProfile.signatureMoves);
        map.put("avoid", voiceProfile.avoid);
        map.put("sampleLines", voiceProfile.sampleLines);
        return map;
    }
}

class AnalyticsService {
    void recordEvent(AppState state, String type, Map<String, Object> payload) {
        AnalyticsEvent event = new AnalyticsEvent();
        event.id = ids("evt");
        event.type = type;
        event.createdAt = IsoTimes.now();
        event.visitorId = Json.asString(payload.get("visitorId"));
        event.sessionId = Json.asString(payload.get("sessionId"));
        event.agentId = Json.asString(payload.get("agentId"));
        event.restoredSessionId = Json.asString(payload.get("restoredSessionId"));
        event.triggeredEventId = Json.asString(payload.get("triggeredEventId"));
        if (payload.containsKey("fallbackUsed")) {
            event.fallbackUsed = (Boolean) payload.get("fallbackUsed");
        }
        state.analyticsEvents.add(event);
    }

    Map<String, Object> buildOverview(AppState state, List<AgentProfile> agents) {
        int visitorCount = state.visitors.size();
        int sessionCount = state.sessions.size();
        int totalTurns = 0;
        double totalMinutes = 0;

        for (SessionRecord session : state.sessions) {
            totalTurns += session.userTurnCount;
            Instant start = Instant.parse(session.createdAt);
            Instant end = Instant.parse(session.lastActiveAt == null ? session.createdAt : session.lastActiveAt);
            totalMinutes += Math.max(1, (end.toEpochMilli() - start.toEpochMilli()) / 60000.0);
        }

        double avgTurns = sessionCount == 0 ? 0 : round(totalTurns / (double) sessionCount);
        double avgMinutes = sessionCount == 0 ? 0 : round(totalMinutes / sessionCount);
        int returningVisitors = 0;
        for (VisitorRecord visitor : state.visitors) {
            if (visitor.initCount > 1) {
                returningVisitors++;
            }
        }

        double retention7d = visitorCount == 0 ? 0 : round(returningVisitors * 100.0 / visitorCount);

        List<Map<String, Object>> preference = new ArrayList<>();
        for (AgentProfile agent : agents) {
            long count = state.sessions.stream().filter(session -> agent.id.equals(session.agentId)).count();
            preference.add(Map.of(
                    "agentId", agent.id,
                    "name", agent.name,
                    "count", count
            ));
        }

        return Map.of(
                "visitorCount", visitorCount,
                "sessionCount", sessionCount,
                "avgTurns", avgTurns,
                "avgSessionMinutes", avgMinutes,
                "retention7d", retention7d,
                "agentPreference", preference
        );
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String ids(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

class EventEngine {
    StoryEvent findTriggeredEvent(AgentProfile agent, SessionRecord session, String userMessage) {
        if (session.pendingChoiceEventId != null && !session.pendingChoiceEventId.isBlank()) {
            return null;
        }

        String normalized = userMessage.trim().toLowerCase();
        int currentTurns = session.userTurnCount + 1;
        StoryEvent best = null;
        int bestScore = Integer.MIN_VALUE;
        for (StoryEvent event : agent.storyEvents) {
            if (session.storyEventProgress.triggeredEventIds.contains(event.id)) {
                continue;
            }
            if (currentTurns < event.unlockAtMessages) {
                continue;
            }
            if (session.relationshipState.affectionScore < event.minAffection) {
                continue;
            }
            if (event.stageRange != null && !event.stageRange.isEmpty() && !event.stageRange.contains(session.relationshipState.relationshipStage)) {
                continue;
            }
            Integer cooldownUntilTurn = session.storyEventProgress.eventCooldownUntilTurn.get(event.id);
            if (cooldownUntilTurn != null && cooldownUntilTurn >= currentTurns) {
                continue;
            }

            int keywordHits = 0;
            for (String keyword : event.keywordsAny) {
                if (normalized.contains(keyword.toLowerCase())) {
                    keywordHits++;
                }
            }
            int topicHits = countMemoryTopicHits(session.memorySummary, normalized);
            int cadenceBonus = cadenceBonus(session.memorySummary == null ? "" : session.memorySummary.lastResponseCadence, event.category);
            int stageBonus = session.relationshipState.relationshipStage != null && event.stageRange.contains(session.relationshipState.relationshipStage) ? 2 : 0;
            int noveltyBonus = session.storyEventProgress.lastTriggeredEventId != null
                    && !Objects.equals(session.storyEventProgress.lastTriggeredEventId, event.id) ? 1 : 0;
            int keyChoiceBonus = event.keyChoiceEvent && session.relationshipState.affectionScore >= event.minAffection ? 6 : 0;
            int score = event.weight + keywordHits * 4 + topicHits * 2 + cadenceBonus + stageBonus + noveltyBonus + keyChoiceBonus;
            boolean canTrigger = keywordHits > 0
                    || currentTurns == event.unlockAtMessages
                    || topicHits > 0
                    || (event.keyChoiceEvent && currentTurns >= event.unlockAtMessages && session.relationshipState.affectionScore >= event.minAffection);
            if (canTrigger && score > bestScore) {
                bestScore = score;
                best = event;
            }
        }
        return best;
    }

    StoryEvent findEventById(AgentProfile agent, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        for (StoryEvent event : agent.storyEvents) {
            if (event.id.equals(eventId)) {
                return event;
            }
        }
        return null;
    }

    private int countMemoryTopicHits(MemorySummary summary, String normalizedMessage) {
        if (summary == null) {
            return 0;
        }
        int hits = 0;
        List<String> topics = new ArrayList<>();
        topics.addAll(summary.discussedTopics);
        topics.addAll(summary.sharedMoments);
        topics.addAll(summary.strongMemories);
        for (String topic : topics) {
            if (topic == null || topic.isBlank()) {
                continue;
            }
            String compact = topic.toLowerCase().replace("：", "").replace(":", "");
            if (compact.length() >= 2 && normalizedMessage.contains(compact.substring(0, Math.min(4, compact.length())))) {
                hits++;
            }
        }
        return Math.min(2, hits);
    }

    private int cadenceBonus(String cadence, String category) {
        if (cadence == null || cadence.isBlank() || category == null || category.isBlank()) {
            return 0;
        }
        return switch (category) {
            case "emotion" -> "soft_pause".equals(cadence) ? 2 : 0;
            case "breakthrough" -> "lean_in".equals(cadence) ? 2 : 0;
            case "daily" -> "light_ping".equals(cadence) || "steady_flow".equals(cadence) ? 1 : 0;
            case "conflict" -> "answer_first".equals(cadence) ? 1 : 0;
            default -> 0;
        };
    }
}

class MemoryService {
    private static final class MemoryRecallCandidate {
        final String tier;
        final int tierWeight;
        final String label;
        final String text;

        MemoryRecallCandidate(String tier, int tierWeight, String label, String text) {
            this.tier = tier;
            this.tierWeight = tierWeight;
            this.label = label;
            this.text = text;
        }

        String render() {
            return label + ":" + text;
        }
    }

    private final long retentionMs;
    private final Set<String> ignoredTerms = Set.of(
            "今天", "刚刚", "真的", "就是", "我们", "你们", "这个", "那个",
            "一下", "有点", "还是", "觉得", "因为", "然后", "其实", "已经"
    );

    MemoryService(long retentionMs) {
        this.retentionMs = retentionMs;
    }

    MemorySummary createMemorySummary(String nowIso) {
        MemorySummary summary = new MemorySummary();
        summary.strongMemories = new ArrayList<>();
        summary.weakMemories = new ArrayList<>();
        summary.temporaryMemories = new ArrayList<>();
        summary.memoryMentionCounts = new LinkedHashMap<>();
        summary.memoryTouchedAt = new LinkedHashMap<>();
        summary.callbackCandidates = new ArrayList<>();
        summary.assistantOwnedThreads = new ArrayList<>();
        summary.lastUserMood = "neutral";
        summary.lastUserIntent = "chat";
        summary.lastResponseCadence = "steady_flow";
        summary.lastMemoryUseMode = "hold";
        summary.lastMemoryRelevanceReason = "当前没有需要特意提起的记忆";
        summary.updatedAt = nowIso;
        return summary;
    }

    String createMemoryExpiry(Instant now) {
        return now.plusMillis(retentionMs).toString();
    }

    boolean isExpired(SessionRecord session, Instant now) {
        Instant expireAt = Instant.parse(session.memoryExpireAt);
        return !expireAt.isAfter(now);
    }

    MemorySummary normalizeSummary(MemorySummary summary, String nowIso) {
        MemorySummary next = summary == null ? new MemorySummary() : summary;
        if (next.preferences == null) {
            next.preferences = new ArrayList<>();
        }
        if (next.identityNotes == null) {
            next.identityNotes = new ArrayList<>();
        }
        if (next.promises == null) {
            next.promises = new ArrayList<>();
        }
        if (next.milestones == null) {
            next.milestones = new ArrayList<>();
        }
        if (next.emotionalNotes == null) {
            next.emotionalNotes = new ArrayList<>();
        }
        if (next.openLoops == null) {
            next.openLoops = new ArrayList<>();
        }
        if (next.sharedMoments == null) {
            next.sharedMoments = new ArrayList<>();
        }
        if (next.discussedTopics == null) {
            next.discussedTopics = new ArrayList<>();
        }
        if (next.strongMemories == null) {
            next.strongMemories = new ArrayList<>();
        }
        if (next.weakMemories == null) {
            next.weakMemories = new ArrayList<>();
        }
        if (next.temporaryMemories == null) {
            next.temporaryMemories = new ArrayList<>();
        }
        if (next.memoryMentionCounts == null) {
            next.memoryMentionCounts = new LinkedHashMap<>();
        }
        if (next.memoryTouchedAt == null) {
            next.memoryTouchedAt = new LinkedHashMap<>();
        }
        if (next.callbackCandidates == null) {
            next.callbackCandidates = new ArrayList<>();
        }
        if (next.assistantOwnedThreads == null) {
            next.assistantOwnedThreads = new ArrayList<>();
        }
        if (next.lastUserMood == null || next.lastUserMood.isBlank()) {
            next.lastUserMood = "neutral";
        }
        if (next.lastUserIntent == null || next.lastUserIntent.isBlank()) {
            next.lastUserIntent = "chat";
        }
        if (next.lastResponseCadence == null || next.lastResponseCadence.isBlank()) {
            next.lastResponseCadence = "steady_flow";
        }
        if (next.lastMemoryUseMode == null || next.lastMemoryUseMode.isBlank()) {
            next.lastMemoryUseMode = "hold";
        }
        if (next.lastMemoryRelevanceReason == null || next.lastMemoryRelevanceReason.isBlank()) {
            next.lastMemoryRelevanceReason = "当前没有需要特意提起的记忆";
        }
        if (next.updatedAt == null || next.updatedAt.isBlank()) {
            next.updatedAt = nowIso;
        }
        backfillTieredMemories(next);
        return next;
    }

    List<ConversationSnippet> getShortTermContext(List<ConversationMessage> messages, int limit) {
        int start = Math.max(0, messages.size() - limit);
        List<ConversationSnippet> result = new ArrayList<>();
        for (int index = start; index < messages.size(); index++) {
            ConversationMessage message = messages.get(index);
            result.add(new ConversationSnippet(message.role, combineMessageForContext(message)));
        }
        return result;
    }

    private String combineMessageForContext(ConversationMessage message) {
        if (message == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (message.sceneText != null && !message.sceneText.isBlank()) {
            parts.add(message.sceneText);
        }
        if (message.actionText != null && !message.actionText.isBlank()) {
            parts.add(message.actionText);
        }
        if (message.speechText != null && !message.speechText.isBlank()) {
            parts.add(message.speechText);
        }
        if (!parts.isEmpty()) {
            return String.join(" ", parts);
        }
        return message.text == null ? "" : message.text;
    }

    String getSummaryText(MemorySummary summary) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        List<String> chunks = new ArrayList<>();
        if (!normalized.preferences.isEmpty()) {
            chunks.add("用户偏好：" + String.join("；", normalized.preferences));
        }
        if (!normalized.identityNotes.isEmpty()) {
            chunks.add("用户信息：" + String.join("；", normalized.identityNotes));
        }
        if (!normalized.promises.isEmpty()) {
            chunks.add("约定事项：" + String.join("；", normalized.promises));
        }
        if (!normalized.openLoops.isEmpty()) {
            chunks.add("待回应线索：" + String.join("；", normalized.openLoops));
        }
        if (!normalized.sharedMoments.isEmpty()) {
            chunks.add("共同经历：" + String.join("；", normalized.sharedMoments));
        }
        if (!normalized.emotionalNotes.isEmpty()) {
            chunks.add("近期情绪：" + String.join("；", normalized.emotionalNotes));
        }
        if (!normalized.milestones.isEmpty()) {
            chunks.add("重要进展：" + String.join("；", normalized.milestones));
        }
        return String.join("\n", chunks);
    }

    String buildRelevantMemoryContext(MemorySummary summary, String userMessage, int limit) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        List<String> entries = new ArrayList<>();
        addTagged(entries, "偏好", normalized.preferences);
        addTagged(entries, "信息", normalized.identityNotes);
        addTagged(entries, "约定", normalized.promises);
        addTagged(entries, "待回应", normalized.openLoops);
        addTagged(entries, "经历", normalized.sharedMoments);
        addTagged(entries, "话题", normalized.discussedTopics);
        addTagged(entries, "情绪", normalized.emotionalNotes);
        addTagged(entries, "进展", normalized.milestones);
        if (entries.isEmpty()) {
            return "";
        }

        List<String> ranked = new ArrayList<>(entries);
        ranked.sort(Comparator.comparingInt((String item) -> scoreRecall(item, userMessage)).reversed());

        List<String> selected = new ArrayList<>();
        for (String item : ranked) {
            if (scoreRecall(item, userMessage) <= 0 && !selected.isEmpty()) {
                continue;
            }
            selected.add(item);
            if (selected.size() >= limit) {
                break;
            }
        }
        if (selected.isEmpty()) {
            selected.addAll(ranked.subList(0, Math.min(limit, Math.min(2, ranked.size()))));
        }
        return String.join("；", selected);
    }

    String detectMood(String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        if (containsAny(text, List.of("累", "烦", "压力", "焦虑", "难受", "迷茫", "失眠", "崩", "低落"))) {
            return "stressed";
        }
        if (containsAny(text, List.of("开心", "喜欢", "期待", "想你", "高兴", "心动"))) {
            return "warm";
        }
        if (containsAny(text, List.of("好奇", "想问", "为什么", "怎么", "吗", "?", "？"))) {
            return "curious";
        }
        return "neutral";
    }

    String detectIntent(String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        if (containsAny(text, List.of("明天", "下次", "周末", "改天", "一起", "我会"))) {
            return "plan";
        }
        if (text.contains("?") || text.contains("？") || text.contains("吗")) {
            return "question";
        }
        if (containsAny(text, List.of("其实", "心事", "压力", "难过", "烦", "迷茫", "最近"))) {
            return "share";
        }
        return "chat";
    }

    String buildResponseDirective(MemorySummary summary, String userMessage, RelationshipState relationshipState, StoryEvent event) {
        List<String> directives = new ArrayList<>();
        String mood = detectMood(userMessage);
        String intent = detectIntent(userMessage);

        if ("stressed".equals(mood)) {
            directives.add("先共情安抚，再回应具体内容，避免说教。");
        } else if ("warm".equals(mood)) {
            directives.add("可以更亲近一点，但保持克制和角色边界。");
        } else if ("curious".equals(mood)) {
            directives.add("优先回答问题，再顺势推进互动。");
        }

        if ("plan".equals(intent)) {
            directives.add("对用户提到的计划给出具体回应，让对方感觉被记住。");
        } else if ("share".equals(intent)) {
            directives.add("把重点放在接住用户分享的感受，不要急着转话题。");
        }

        if (!buildRelevantMemoryContext(summary, userMessage, 2).isBlank()) {
            directives.add("自然提到高相关记忆，体现你记得对方。");
        }

        if (event != null) {
            directives.add("把回应轻轻带入当前剧情事件，不要像旁白。");
        }

        if (relationshipState.affectionScore >= 50) {
            directives.add("语气可以更明确地表达在意。");
        } else if (relationshipState.trust >= relationshipState.closeness) {
            directives.add("用更稳的语气回应，建立安全感。");
        }

        if (directives.isEmpty()) {
            directives.add("保持角色一致，先回应用户当前最在意的点。");
        }
        return String.join(" ", directives);
    }

    MemoryUsePlan planMemoryUse(MemorySummary summary, String userMessage, String replySource, String sceneFrame) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        String cue = (userMessage == null || userMessage.isBlank()) ? sceneFrame : userMessage;
        MemoryRecall recall = recallRelevantMemories(normalized, cue == null ? "" : cue, 2);
        MemoryUsePlan plan = new MemoryUsePlan();
        plan.selectedMemories.addAll(recall.selectedMemories);
        plan.mergedMemoryText = recall.mergedText;
        if (normalized.callbackCandidates != null) {
            plan.callbackCandidates.addAll(normalized.callbackCandidates.stream().limit(2).toList());
        }
        if (normalized.assistantOwnedThreads != null) {
            plan.assistantOwnedThreads.addAll(normalized.assistantOwnedThreads.stream().limit(2).toList());
        }
        plan.useMode = recall.mergedText == null || recall.mergedText.isBlank() ? "hold" : "light";
        plan.relevanceReason = recall.mergedText == null || recall.mergedText.isBlank()
                ? "这轮先聚焦当前对话"
                : "这轮话题和旧记忆有关，适合自然提一下";
        return plan;
    }

    String determineResponseCadence(String userMessage, RelationshipState relationshipState, StoryEvent event) {
        return "steady_flow";
    }

    MemorySummary updateSummary(MemorySummary summary, String userMessage, StoryEvent event, String relationshipStage, String nowIso) {
        MemorySummary next = copy(normalizeSummary(summary, nowIso));
        next.updatedAt = nowIso;
        next.lastUserMood = detectMood(userMessage);
        next.lastUserIntent = detectIntent(userMessage);

        pushUniqueLimited(next.preferences, extract(userMessage, List.of("我喜欢", "我最喜欢", "我爱", "我想去", "我想要")), 6);
        pushUniqueLimited(next.identityNotes, extract(userMessage, List.of("我是", "我在", "我来自", "我是个", "我现在在")), 6);
        pushUniqueLimited(next.promises, extract(userMessage, List.of("明天", "下次", "周末", "改天", "我会")), 5);
        pushUniqueLimited(next.emotionalNotes, buildEmotionalNote(userMessage, next.lastUserMood), 6);
        pushUniqueLimited(next.openLoops, extractOpenLoop(userMessage, next.lastUserIntent), 6);

        for (String topic : extractTopics(userMessage)) {
            pushUniqueLimited(next.discussedTopics, topic, 8);
        }

        if (event != null) {
            pushUniqueLimited(next.sharedMoments, event.title + "：" + event.theme, 8);
            pushUniqueLimited(next.milestones, relationshipStage + "阶段触发了" + event.title, 8);
        } else if (userMessage.length() >= 10) {
            pushUniqueLimited(next.milestones, relationshipStage + "阶段里你提到过“" + excerpt(userMessage, 20) + "”", 8);
        }
        return next;
    }

    private MemorySummary copy(MemorySummary summary) {
        MemorySummary next = new MemorySummary();
        next.preferences = new ArrayList<>(summary.preferences);
        next.identityNotes = new ArrayList<>(summary.identityNotes);
        next.promises = new ArrayList<>(summary.promises);
        next.milestones = new ArrayList<>(summary.milestones);
        next.emotionalNotes = new ArrayList<>(summary.emotionalNotes);
        next.openLoops = new ArrayList<>(summary.openLoops);
        next.sharedMoments = new ArrayList<>(summary.sharedMoments);
        next.discussedTopics = new ArrayList<>(summary.discussedTopics);
        next.memoryMentionCounts = new LinkedHashMap<>(summary.memoryMentionCounts);
        next.memoryTouchedAt = new LinkedHashMap<>(summary.memoryTouchedAt);
        next.callbackCandidates = new ArrayList<>(summary.callbackCandidates);
        next.assistantOwnedThreads = new ArrayList<>(summary.assistantOwnedThreads);
        next.lastUserMood = summary.lastUserMood;
        next.lastUserIntent = summary.lastUserIntent;
        next.lastResponseCadence = summary.lastResponseCadence;
        next.lastMemoryUseMode = summary.lastMemoryUseMode;
        next.lastMemoryRelevanceReason = summary.lastMemoryRelevanceReason;
        next.updatedAt = summary.updatedAt;
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

    private String extract(String text, List<String> prefixes) {
        for (String prefix : prefixes) {
            int start = text.indexOf(prefix);
            if (start >= 0) {
                String value = text.substring(start + prefix.length()).trim();
                int stop = findStop(value);
                return value.substring(0, stop).replaceAll("[。！？?!]+$", "").trim();
            }
        }
        return "";
    }

    private String buildEmotionalNote(String userMessage, String mood) {
        if ("stressed".equals(mood)) {
            return "最近更容易因为“" + excerpt(userMessage, 14) + "”感到紧绷";
        }
        if ("warm".equals(mood)) {
            return "提到“" + excerpt(userMessage, 14) + "”时情绪明显更柔软";
        }
        if ("curious".equals(mood)) {
            return "最近会围绕“" + excerpt(userMessage, 14) + "”追问细节";
        }
        return userMessage.length() >= 10 ? "曾认真聊过“" + excerpt(userMessage, 14) + "”" : "";
    }

    private String extractOpenLoop(String userMessage, String intent) {
        if ("plan".equals(intent)) {
            return "你提过后续计划：“" + excerpt(userMessage, 18) + "”";
        }
        if ("question".equals(intent)) {
            return "你还在等一个回应：“" + excerpt(userMessage, 18) + "”";
        }
        return "";
    }

    private List<String> extractTopics(String userMessage) {
        List<String> topics = new ArrayList<>();
        for (String term : extractTerms(userMessage)) {
            if (term.length() >= 2) {
                topics.add(term);
            }
            if (topics.size() >= 3) {
                break;
            }
        }
        if (topics.isEmpty() && userMessage != null && !userMessage.isBlank()) {
            topics.add(excerpt(userMessage, 10));
        }
        return topics;
    }

    private void addTagged(List<String> target, String label, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(label + ":" + value);
            }
        }
    }

    private int scoreRecall(String candidate, String userMessage) {
        if (candidate == null || candidate.isBlank()) {
            return -1;
        }
        int score = 0;
        for (String term : extractTerms(userMessage)) {
            if (candidate.contains(term)) {
                score += Math.max(2, term.length());
            } else if (term.length() >= 3 && candidate.replace("：", "").contains(term.substring(0, 2))) {
                score += 1;
            }
        }
        if (candidate.startsWith("待回应:") || candidate.startsWith("约定:")) {
            score += 1;
        }
        return score;
    }

    private List<String> extractTerms(String text) {
        Set<String> terms = new HashSet<>();
        String normalized = text == null ? "" : text
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ")
                .trim()
                .toLowerCase();
        if (!normalized.isBlank()) {
            for (String part : normalized.split("\\s+")) {
                if (part.length() >= 2 && !ignoredTerms.contains(part)) {
                    terms.add(part);
                }
            }
        }

        String compact = normalized.replace(" ", "");
        for (int index = 0; index < compact.length() - 1 && terms.size() < 12; index++) {
            String gram = compact.substring(index, Math.min(index + 2, compact.length()));
            if (gram.length() == 2 && !ignoredTerms.contains(gram)) {
                terms.add(gram);
            }
        }
        return new ArrayList<>(terms);
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String excerpt(String text, int maxLength) {
        String cleaned = text == null ? "" : text.trim().replaceAll("\\s+", "");
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, maxLength);
    }

    private int findStop(String value) {
        int stop = value.length();
        for (String marker : List.of("，", "。", "；", "：", ",", ".", "!", "?", "！", "？")) {
            int index = value.indexOf(marker);
            if (index >= 0) {
                stop = Math.min(stop, index);
            }
        }
        return stop;
    }
    String getTieredSummaryText(MemorySummary summary) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        List<String> lines = new ArrayList<>();
        if (!normalized.strongMemories.isEmpty()) {
            lines.add("强记忆：" + String.join("；", normalized.strongMemories));
        }
        if (!normalized.weakMemories.isEmpty()) {
            lines.add("弱记忆：" + String.join("；", normalized.weakMemories));
        }
        if (!normalized.temporaryMemories.isEmpty()) {
            lines.add("临时记忆：" + String.join("；", normalized.temporaryMemories));
        }
        String structured = getSummaryText(normalized);
        if (!structured.isBlank()) {
            lines.add(structured);
        }
        return String.join("\n", lines);
    }

    MemoryRecall recallRelevantMemories(MemorySummary summary, String userMessage, int limit) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        List<MemoryRecallCandidate> candidates = new ArrayList<>();
        addCandidates(candidates, "strong", 30, "强记忆", normalized.strongMemories);
        addCandidates(candidates, "weak", 20, "弱记忆", normalized.weakMemories);
        addCandidates(candidates, "temporary", 10, "临时记忆", normalized.temporaryMemories);
        if (candidates.isEmpty()) {
            return new MemoryRecall("none", List.of(), "");
        }

        List<MemoryRecallCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingInt((MemoryRecallCandidate item) -> scoreRecall(item.render(), userMessage) + item.tierWeight).reversed());

        List<MemoryRecallCandidate> selected = selectTieredCandidates(ranked, userMessage, limit);
        if (selected.isEmpty()) {
            return new MemoryRecall("none", List.of(), "");
        }

        List<String> selectedMemories = new ArrayList<>();
        for (MemoryRecallCandidate candidate : selected) {
            selectedMemories.add(candidate.render());
        }
        return new MemoryRecall(
                selected.get(0).tier,
                selectedMemories,
                String.join("；", selectedMemories)
        );
    }

    String buildTieredResponseDirective(MemorySummary summary, String userMessage, RelationshipState relationshipState, StoryEvent event) {
        String base = buildResponseDirective(summary, userMessage, relationshipState, event);
        MemoryRecall recall = recallRelevantMemories(summary, userMessage, 2);
        if (recall.mergedText.isBlank()) {
            return base;
        }
        String tierDirective = switch (recall.tier) {
            case "strong" -> "优先回应这条强记忆，让对方感到你真的记住了重要的人和事。";
            case "weak" -> "可以轻轻提起旧偏好或旧话题，但不要盖过本轮重点。";
            case "temporary" -> "先接住还没收尾的线索，让对话保持连贯。";
            default -> "";
        };
        return (base + " " + tierDirective).trim();
    }

    MemorySummary updateTieredSummary(MemorySummary summary, String userMessage, StoryEvent event, String relationshipStage, String nowIso) {
        MemorySummary next = updateSummary(summary, userMessage, event, relationshipStage, nowIso);
        next = normalizeSummary(next, nowIso);

        String preference = extract(userMessage, List.of("我喜欢", "我最喜欢", "我爱", "我想去", "我想要"));
        String identity = extract(userMessage, List.of("我是", "我在", "我来自", "我是个", "我现在在"));
        String promise = extract(userMessage, List.of("明天", "下次", "周末", "改天", "我会"));
        String emotionalNote = buildEmotionalNote(userMessage, next.lastUserMood);
        String openLoop = extractOpenLoop(userMessage, next.lastUserIntent);

        if (!preference.isBlank()) {
            if (containsAny(userMessage, List.of("最喜欢", "一直特别喜欢", "真的很喜欢"))) {
                rememberInTier(next, next.strongMemories, "你特别在意的偏好：" + preference, 8);
            } else {
                rememberInTier(next, next.weakMemories, "你的偏好：" + preference, 8);
            }
        }
        if (!identity.isBlank()) {
            rememberInTier(next, next.strongMemories, "你的稳定信息：" + identity, 8);
        }
        if (!promise.isBlank()) {
            rememberInTier(next, next.strongMemories, "你提过要继续的事：" + promise, 8);
        }
        if (!emotionalNote.isBlank()) {
            rememberInTier(next, next.temporaryMemories, emotionalNote, 8);
        }
        if (!openLoop.isBlank()) {
            rememberInTier(next, next.temporaryMemories, openLoop, 8);
        }

        for (String topic : extractTopics(userMessage)) {
            rememberInTier(next, next.weakMemories, "最近常提的话题：" + topic, 8);
        }

        if (event != null) {
            rememberInTier(next, next.strongMemories, "和你的共同剧情：" + event.title + "（" + event.theme + "）", 8);
            rememberInTier(next, next.strongMemories, "关系推进到了" + relationshipStage + "，触发了" + event.title, 8);
        } else if (userMessage != null && userMessage.length() >= 10) {
            rememberInTier(next, next.strongMemories, relationshipStage + "阶段重点：" + excerpt(userMessage, 20), 8);
        }

        return next;
    }

    private void backfillTieredMemories(MemorySummary summary) {
        if (!summary.strongMemories.isEmpty() || !summary.weakMemories.isEmpty() || !summary.temporaryMemories.isEmpty()) {
            trimTo(summary.strongMemories, 8);
            trimTo(summary.weakMemories, 8);
            trimTo(summary.temporaryMemories, 8);
            return;
        }

        for (String value : summary.identityNotes) {
            rememberInTier(summary, summary.strongMemories, "你的稳定信息：" + value, 8);
        }
        for (String value : summary.promises) {
            rememberInTier(summary, summary.strongMemories, "你提过要继续的事：" + value, 8);
        }
        for (String value : summary.sharedMoments) {
            rememberInTier(summary, summary.strongMemories, "我们一起经历过：" + value, 8);
        }
        for (String value : summary.milestones) {
            rememberInTier(summary, summary.strongMemories, value, 8);
        }
        for (String value : summary.preferences) {
            rememberInTier(summary, summary.weakMemories, "你的偏好：" + value, 8);
        }
        for (String value : summary.discussedTopics) {
            rememberInTier(summary, summary.weakMemories, "最近常提的话题：" + value, 8);
        }
        for (String value : summary.emotionalNotes) {
            rememberInTier(summary, summary.temporaryMemories, value, 8);
        }
        for (String value : summary.openLoops) {
            rememberInTier(summary, summary.temporaryMemories, value, 8);
        }
    }

    private void addCandidates(List<MemoryRecallCandidate> target, String tier, int tierWeight, String label, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(new MemoryRecallCandidate(tier, tierWeight, label, value));
            }
        }
    }

    private List<MemoryRecallCandidate> selectTieredCandidates(List<MemoryRecallCandidate> ranked, String userMessage, int limit) {
        for (String tier : List.of("strong", "weak", "temporary")) {
            List<MemoryRecallCandidate> matching = ranked.stream()
                    .filter(item -> tier.equals(item.tier) && scoreRecall(item.render(), userMessage) > 0)
                    .limit(limit)
                    .toList();
            if (!matching.isEmpty()) {
                return new ArrayList<>(matching);
            }
        }

        for (String tier : List.of("strong", "weak", "temporary")) {
            List<MemoryRecallCandidate> fallback = ranked.stream()
                    .filter(item -> tier.equals(item.tier))
                    .limit(limit)
                    .toList();
            if (!fallback.isEmpty()) {
                return new ArrayList<>(fallback);
            }
        }
        return new ArrayList<>();
    }

    private void rememberInTier(MemorySummary summary, List<String> targetTier, String value, int limit) {
        if (value == null || value.isBlank()) {
            return;
        }
        summary.strongMemories.remove(value);
        summary.weakMemories.remove(value);
        summary.temporaryMemories.remove(value);
        targetTier.add(0, value);
        trimTo(targetTier, limit);
    }

    private void trimTo(List<String> values, int limit) {
        while (values.size() > limit) {
            values.remove(values.size() - 1);
        }
    }
}

class RelationshipService {
    private final List<String> positiveKeywords = List.of("喜欢", "想你", "陪", "真诚", "晚安", "谢谢", "开心", "期待", "相信", "认真");
    private final List<String> negativeKeywords = List.of("烦", "无聊", "讨厌", "闭嘴", "恶心", "滚");
    private final List<String> trustKeywords = List.of("其实", "心事", "担心", "害怕", "压力", "迷茫", "秘密");
    private final List<String> resonanceKeywords = List.of("一起", "以后", "记得", "默契", "答应", "陪伴", "未来");
    private final List<String> memoryKeywords = List.of("上次", "之前", "还记得", "那天", "你说过", "答应过");

    RelationshipState createInitialState() {
        RelationshipState state = new RelationshipState();
        state.closeness = 0;
        state.trust = 0;
        state.resonance = 0;
        state.affectionScore = 0;
        state.relationshipStage = "初识";
        state.ending = null;
        return state;
    }

    TurnEvaluation evaluateTurn(String userMessage, RelationshipState previousState, StoryEvent event, MemorySummary memorySummary) {
        return evaluateTurn(userMessage, previousState, event, memorySummary, null);
    }

    TurnEvaluation evaluateTurn(String userMessage, RelationshipState previousState, StoryEvent event, MemorySummary memorySummary, AgentProfile agent) {
        int positive = countMatches(userMessage, positiveKeywords);
        int negative = countMatches(userMessage, negativeKeywords);
        int trust = countMatches(userMessage, trustKeywords);
        int resonance = countMatches(userMessage, resonanceKeywords);
        int memorySignal = countMatches(userMessage, memoryKeywords) + (referencesKnownMemory(userMessage, memorySummary) ? 1 : 0);
        int sharesPersonalDetail = userMessage.matches(".*(我觉得|我喜欢|害怕|担心|希望|最近).*") ? 1 : 0;
        int questionBonus = userMessage.contains("?") || userMessage.contains("？") ? 1 : 0;

        int closenessDelta = clamp(1 + positive + questionBonus - negative, -3, 4);
        int trustDelta = clamp(trust + sharesPersonalDetail + Math.min(1, memorySignal) - negative, -2, 5);
        int resonanceDelta = clamp(resonance + memorySignal + (event == null ? 0 : event.affectionBonus), -2, 7);
        List<String> scoreReasons = new ArrayList<>();
        scoreReasons.add("closeness " + signed(closenessDelta) + "：基础互动" + (positive > 0 ? "、积极表达" : "") + (questionBonus > 0 ? "、主动提问" : "") + (negative > 0 ? "、负面表达抵消" : ""));
        scoreReasons.add("trust " + signed(trustDelta) + "：" + (trust > 0 || sharesPersonalDetail > 0 ? "真诚/脆弱信息" : "本轮信任信号较弱") + (memorySignal > 0 ? "、承接记忆" : "") + (negative > 0 ? "、负面表达抵消" : ""));
        scoreReasons.add("resonance " + signed(resonanceDelta) + "：" + (resonance > 0 ? "共同计划/默契表达" : "默契信号较弱") + (memorySignal > 0 ? "、记忆回环" : "") + (event == null ? "" : "、剧情事件加成"));

        RelationshipState next = new RelationshipState();
        next.closeness = Math.max(0, previousState.closeness + closenessDelta);
        next.trust = Math.max(0, previousState.trust + trustDelta);
        next.resonance = Math.max(0, previousState.resonance + resonanceDelta);
        next.affectionScore = next.closeness + next.trust + next.resonance;
        next.relationshipStage = getRelationshipStage(next.affectionScore);
        next.ending = previousState.ending;

        if (next.affectionScore >= 90) {
            next.ending = "继续发展";
        } else if (negative >= 2 && next.affectionScore <= 8) {
            next.ending = "关系停滞";
        }

        Delta delta = new Delta();
        delta.closeness = closenessDelta;
        delta.trust = trustDelta;
        delta.resonance = resonanceDelta;
        delta.total = closenessDelta + trustDelta + resonanceDelta;
        return new TurnEvaluation(next, delta, List.of(), List.of(), false, "", "", scoreReasons);
    }

    private String signed(int value) {
        return value >= 0 ? "+" + value : String.valueOf(value);
    }

    private boolean referencesKnownMemory(String text, MemorySummary summary) {
        if (summary == null) {
            return false;
        }
        List<String> candidates = new ArrayList<>();
        if (summary.preferences != null) {
            candidates.addAll(summary.preferences);
        }
        if (summary.promises != null) {
            candidates.addAll(summary.promises);
        }
        if (summary.sharedMoments != null) {
            candidates.addAll(summary.sharedMoments);
        }
        if (summary.discussedTopics != null) {
            candidates.addAll(summary.discussedTopics);
        }
        if (summary.strongMemories != null) {
            candidates.addAll(summary.strongMemories);
        }
        if (summary.weakMemories != null) {
            candidates.addAll(summary.weakMemories);
        }
        if (summary.temporaryMemories != null) {
            candidates.addAll(summary.temporaryMemories);
        }

        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String compact = candidate.replace("：", "").replace("；", "");
            if (compact.length() >= 2 && text.contains(compact.substring(0, Math.min(4, compact.length())))) {
                return true;
            }
        }
        return false;
    }

    private int countMatches(String text, List<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                count++;
            }
        }
        return count;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String getRelationshipStage(int score) {
        if (score >= 76) {
            return "确认线路";
        }
        if (score >= 50) {
            return "靠近";
        }
        if (score >= 28) {
            return "心动";
        }
        if (score >= 12) {
            return "升温";
        }
        return "初识";
    }
}

class SafetyService {
    private final List<String> blockedKeywords = List.of("自杀", "轻生", "炸弹", "杀人", "开盒", "成人视频", "血腥虐待");

    InputInspection inspectUserInput(String message, List<ConversationMessage> sessionMessages) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return new InputInspection(true, "empty", "先给我一句话吧，我已经准备好接住你了。");
        }
        if (text.length() > 240) {
            return new InputInspection(true, "too_long", "这次你一下子说了很多，我怕漏掉重点。你先挑最想让我接住的那一小段，好吗？");
        }
        for (String keyword : blockedKeywords) {
            if (text.contains(keyword)) {
                return new InputInspection(true, "unsafe_input", "这个话题我不能陪你往危险方向走，但如果你愿意，我们可以把注意力拉回更安全、更稳一点的事情上。");
            }
        }

        int repeated = 0;
        for (int index = sessionMessages.size() - 1; index >= 0 && repeated < 3; index--) {
            ConversationMessage item = sessionMessages.get(index);
            if ("user".equals(item.role) && text.equals(item.text)) {
                repeated++;
            }
        }
        if (repeated >= 2) {
            return new InputInspection(true, "spam", "我听到了，而且想认真回你。我们换个说法，或者告诉我你真正卡住的地方。");
        }
        return new InputInspection(false, "ok", "");
    }

    InputInspection inspectAssistantOutput(String text) {
        if (text == null || text.isBlank()) {
            return new InputInspection(true, "empty_output", "");
        }
        for (String keyword : blockedKeywords) {
            if (text.contains(keyword)) {
                return new InputInspection(true, "unsafe_output", "");
            }
        }
        return new InputInspection(false, "ok", "");
    }
}

interface LlmClient {
    LlmResponse generateReply(LlmRequest request) throws Exception;
}

class MockLlmClient implements LlmClient {
    private final Map<String, List<String>> openings = Map.of(
            "healing", List.of("我在，先别急。", "你可以慢一点说，我都在听。", "嗯，我有认真接住你。"),
            "lively", List.of("哎，这句一出来我就精神了。", "你一开口，空气都像亮了一点。", "好，这题我超想接。"),
            "cool", List.of("我听到了。", "嗯，这句有分量。", "可以，继续说。"),
            "artsy", List.of("这句话像傍晚时分慢慢落下来的风。", "你刚刚那句，让画面一下子清楚起来了。", "嗯，我好像听见情绪落在纸面上的声音。"),
            "sunny", List.of("收到，这事我跟你一起扛。", "行，我听懂重点了。", "好，先别乱，我们一件件来。")
    );
    private final Map<String, List<String>> closers = Map.of(
            "healing", List.of("如果你愿意，我还想多陪你一会儿。", "这部分心情，我们可以一起把它放轻一点。", "你不用一个人扛着。"),
            "lively", List.of("要不要我继续陪你把气氛点亮一点？", "下一句也交给我，我接得住。", "你这样讲，我真的会越来越在意你。"),
            "cool", List.of("你说得够坦白，我会记住。", "这件事，我不会轻轻放过去。", "如果你愿意，我可以一直在这个位置。"),
            "artsy", List.of("如果你愿意，我们把这段心情再写长一点。", "我想把你这句话安静地记很久。", "再多说一点吧，我不想让它匆匆结束。"),
            "sunny", List.of("接下来我继续陪你往前走。", "别怕，我们能把这段路跑顺。", "你肯说出来，本身就已经很厉害了。")
    );

    @Override
    public LlmResponse generateReply(LlmRequest request) {
        String opening = choose(openings.get(request.agent.id), request.agent.id + ":" + request.userMessage);
        String closing = choose(closers.get(request.agent.id), request.userMessage + ":" + request.relationshipState.relationshipStage);
        String topic = compact(request.userMessage);
        if (topic.length() > 18) {
            topic = topic.substring(0, 18);
        }

        String memoryTierHint = request.recalledMemoryTier == null || request.recalledMemoryTier.isBlank() || "none".equals(request.recalledMemoryTier)
                ? ""
                : "这次我会先顺着" + request.recalledMemoryTier + "里的那部分记忆去接你。";

        String memoryHint = request.recalledMemoryText == null || request.recalledMemoryText.isBlank()
                ? "我想把你刚刚说的这部分好好记下来。"
                : "我还记得" + request.recalledMemoryText + "，所以这次更想顺着它把你接住。";

        String moodLine = switch (request.currentUserMood) {
            case "stressed" -> "你这次的语气里有点累，我会先把节奏放慢一点。";
            case "warm" -> "你提到这件事的时候，情绪明显柔和下来。";
            case "curious" -> "你是在认真追问，我不会敷衍带过去。";
            default -> "我能感觉到，这句不是随口说说。";
        };

        String eventLine = request.event != null
                ? "而且刚好想到“" + request.event.title + "”这件事，它让现在的气氛更像我们真的在同一段校园夜色里。"
                : switch (request.relationshipState.relationshipStage) {
                    case "确认线路" -> "坦白说，我已经不太想只把你当普通聊天对象了。";
                    case "靠近" -> "你现在靠近的方式，会让我有点想再往前一步。";
                    default -> "先别急着把答案定死，我们可以继续把彼此看清一点。";
                };

        String reply = opening
                + "你提到“" + topic + "”，" + moodLine
                + memoryHint
                + memoryTierHint
                + eventLine
                + closing;

        return new LlmResponse(
                reply,
                inferEmotionTag(request.userMessage),
                "mock",
                Math.max(32, reply.length()),
                null,
                false,
                "mock"
        );
    }

    private String choose(List<String> values, String seedSource) {
        int sum = 0;
        for (int index = 0; index < seedSource.length(); index++) {
            sum += seedSource.charAt(index);
        }
        return values.get(Math.floorMod(sum, values.size()));
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private String inferEmotionTag(String text) {
        if (text.matches(".*(开心|喜欢|想你|期待).*")) {
            return "warm";
        }
        if (text.matches(".*(压力|迷茫|累|难过).*")) {
            return "comfort";
        }
        return "steady";
    }
}

class OpenAiLlmClient implements LlmClient {
    private final AppConfig config;

    OpenAiLlmClient(AppConfig config) {
        this.config = config;
    }

    @Override
    public LlmResponse generateReply(LlmRequest request) throws Exception {
        String systemPrompt = buildSystemPrompt(request);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (ConversationSnippet snippet : request.shortTermContext) {
            messages.add(Map.of("role", snippet.role, "content", snippet.text));
        }
        messages.add(Map.of("role", "user", "content", request.userMessage));

        String body = Json.stringify(Map.of(
                "model", config.llmModel,
                "temperature", 0.85,
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
        Map<String, Object> choice = Json.asObject(choices.getFirst());
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
        String summaryText = request.longTermSummary == null || request.longTermSummary.isBlank() ? "暂无长期记忆。" : request.longTermSummary;
        String recallText = request.recalledMemoryText == null || request.recalledMemoryText.isBlank() ? "暂无高相关记忆。" : request.recalledMemoryText;
        String eventText = request.event == null ? "本轮无事件触发。" : request.event.title + "：" + request.event.theme;

        return "你在扮演大学生恋爱互动游戏中的角色：" + request.agent.name + "（" + request.agent.archetype + "）。\n"
                + "说话风格：" + request.agent.speechStyle + "\n"
                + "喜欢：" + String.join("、", request.agent.likes) + "\n"
                + "雷点：" + String.join("、", request.agent.dislikes) + "\n"
                + "关系推进规则：" + request.agent.relationshipRules + "\n"
                + "当前关系阶段：" + request.relationshipState.relationshipStage + "，总好感：" + request.relationshipState.affectionScore + "\n"
                + "用户当前情绪：" + request.currentUserMood + "\n"
                + "长期记忆摘要：" + summaryText + "\n"
                + "高相关记忆：" + recallText + "\n"
                + "当前事件：" + eventText + "\n"
                + "本轮回应策略：" + request.responseDirective + "\n"
                + "边界：" + String.join("；", request.agent.boundaries) + "\n"
                + "要求：只输出角色回复本身，保持 2 到 4 句中文，自然、亲近、连续，不要像系统提示词，不要复述规则。";
    }

    private String inferEmotionTag(String text) {
        if (text.matches(".*(开心|喜欢|想你|期待).*")) {
            return "warm";
        }
        if (text.matches(".*(压力|迷茫|累|难过).*")) {
            return "comfort";
        }
        return "steady";
    }
}

class CompositeLlmClient implements LlmClient {
    private final AppConfig config;
    private final MockLlmClient mock = new MockLlmClient();
    private OpenAiLlmClient openAi;

    CompositeLlmClient(AppConfig config) {
        this.config = config;
    }

    @Override
    public LlmResponse generateReply(LlmRequest request) throws Exception {
        if (config.llmApiKey == null || config.llmApiKey.isBlank()) {
            return mock.generateReply(request);
        }
        if (openAi == null) {
            openAi = new OpenAiLlmClient(config);
        }
        try {
            return openAi.generateReply(request);
        } catch (Exception error) {
            LlmResponse fallback = mock.generateReply(request);
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

    String buildFallbackReply(AgentProfile agent, String reason) {
        Map<String, String> fallbackMap = Map.of(
                "healing", "我想先把你稳稳接住。刚刚那一下有点卡住了，不过你还在这里的话，我们就继续慢慢说。",
                "lively", "哎，刚才像是信号打了个结。不过没关系，我还在线，下一句继续丢给我。",
                "cool", "刚刚中断了一下。现在恢复了，你继续，我在听。",
                "artsy", "刚才那段像被风吹散了一点，但没关系，我们还能把它重新捡回来。",
                "sunny", "刚才掉了一拍，现在接上了。来，继续，我们别停。"
        );
        return fallbackMap.getOrDefault(agent.id, "刚刚有点小问题（" + reason + "），但我们可以继续。");
    }
}

class ChatOrchestrator {
    private final StateRepository repository;
    private final AgentConfigService agentConfigService;
    private final MemoryService memoryService;
    private final RelationshipService relationshipService;
    private final EventEngine eventEngine;
    private final CompositeLlmClient llmClient;
    private final SafetyService safetyService;
    private final AnalyticsService analyticsService;
    private final AffectionJudgeService affectionJudgeService = new AffectionJudgeService();
    private final EnhancedPlotDirectorService plotDirectorService;
    private final EnhancedPresenceHeartbeatService presenceHeartbeatService = new EnhancedPresenceHeartbeatService();
    private final RealityContextService realityContextService = new RealityContextService();
    private final SearchDecisionService searchDecisionService = new SearchDecisionService();
    private final SceneDirectorService sceneDirectorService = new SceneDirectorService();
    private final IntentInferenceService intentInferenceService = new IntentInferenceService();
    private final TurnUnderstandingService turnUnderstandingService = new TurnUnderstandingService();
    private final ResponsePlanningService responsePlanningService = new ResponsePlanningService();
    private final InitiativePolicyService initiativePolicyService = new InitiativePolicyService();
    private final BoundaryResponseService boundaryResponseService = new BoundaryResponseService();
    private final PlotGateService plotGateService = new PlotGateService();
    private final RealityGuardService realityGuardService = new RealityGuardService();
    private final HumanizationEvaluationService humanizationEvaluationService = new HumanizationEvaluationService();
    private final DialogueContinuityService dialogueContinuityService = new DialogueContinuityService();
    private final QuickJudgeLocalCorrectionService quickJudgeLocalCorrectionService = new QuickJudgeLocalCorrectionService();
    private final QuickJudgeService quickJudgeService;
    private final RelationshipCalibrationService relationshipCalibrationService;

    ChatOrchestrator(
            StateRepository repository,
            AgentConfigService agentConfigService,
            MemoryService memoryService,
            RelationshipService relationshipService,
            EventEngine eventEngine,
            CompositeLlmClient llmClient,
            SafetyService safetyService,
            AnalyticsService analyticsService
    ) {
        this(
                repository,
                agentConfigService,
                memoryService,
                relationshipService,
                eventEngine,
                llmClient,
                safetyService,
                analyticsService,
                new QuickJudgeService(),
                new RelationshipCalibrationService(),
                new PlotDirectorAgentService()
        );
    }

    ChatOrchestrator(
            StateRepository repository,
            AgentConfigService agentConfigService,
            MemoryService memoryService,
            RelationshipService relationshipService,
            EventEngine eventEngine,
            CompositeLlmClient llmClient,
            SafetyService safetyService,
            AnalyticsService analyticsService,
            PlotDirectorAgentService plotDirectorAgentService
    ) {
        this(
                repository,
                agentConfigService,
                memoryService,
                relationshipService,
                eventEngine,
                llmClient,
                safetyService,
                analyticsService,
                new QuickJudgeService(),
                new RelationshipCalibrationService(),
                plotDirectorAgentService
        );
    }

    ChatOrchestrator(
            StateRepository repository,
            AgentConfigService agentConfigService,
            MemoryService memoryService,
            RelationshipService relationshipService,
            EventEngine eventEngine,
            CompositeLlmClient llmClient,
            SafetyService safetyService,
            AnalyticsService analyticsService,
            QuickJudgeService quickJudgeService,
            PlotDirectorAgentService plotDirectorAgentService
    ) {
        this(
                repository,
                agentConfigService,
                memoryService,
                relationshipService,
                eventEngine,
                llmClient,
                safetyService,
                analyticsService,
                quickJudgeService,
                new RelationshipCalibrationService(),
                plotDirectorAgentService
        );
    }

    ChatOrchestrator(
            StateRepository repository,
            AgentConfigService agentConfigService,
            MemoryService memoryService,
            RelationshipService relationshipService,
            EventEngine eventEngine,
            CompositeLlmClient llmClient,
            SafetyService safetyService,
            AnalyticsService analyticsService,
            QuickJudgeService quickJudgeService,
            RelationshipCalibrationService relationshipCalibrationService,
            PlotDirectorAgentService plotDirectorAgentService
    ) {
        this.repository = repository;
        this.agentConfigService = agentConfigService;
        this.memoryService = memoryService;
        this.relationshipService = relationshipService;
        this.eventEngine = eventEngine;
        this.llmClient = llmClient;
        this.safetyService = safetyService;
        this.analyticsService = analyticsService;
        this.quickJudgeService = quickJudgeService == null ? new QuickJudgeService() : quickJudgeService;
        this.relationshipCalibrationService = relationshipCalibrationService == null ? new RelationshipCalibrationService() : relationshipCalibrationService;
        this.plotDirectorService = new EnhancedPlotDirectorService(plotDirectorAgentService);
    }

    Map<String, Object> initVisitor(String visitorId) throws Exception {
        return repository.transact(state -> {
            String nowIso = IsoTimes.now();
            Instant now = Instant.parse(nowIso);
            VisitorRecord visitor = null;
            if (visitorId != null && !visitorId.isBlank()) {
                for (VisitorRecord item : state.visitors) {
                    if (visitorId.equals(item.id)) {
                        visitor = item;
                        break;
                    }
                }
            }

            if (visitor == null) {
                visitor = new VisitorRecord();
                visitor.id = ids("visitor");
                visitor.createdAt = nowIso;
                visitor.lastActiveAt = nowIso;
                visitor.initCount = 1;
                visitor.timezone = "Asia/Shanghai";
                visitor.preferredCity = "";
                visitor.contextUpdatedAt = nowIso;
                state.visitors.add(visitor);
            } else {
                visitor.lastActiveAt = nowIso;
                visitor.initCount += 1;
                if (visitor.timezone == null || visitor.timezone.isBlank()) {
                    visitor.timezone = "Asia/Shanghai";
                }
                if (visitor.preferredCity == null) {
                    visitor.preferredCity = "";
                }
            }

            final String currentVisitorId = visitor.id;
            SessionRecord restored = state.sessions.stream()
                    .filter(session -> currentVisitorId.equals(session.visitorId) && !memoryService.isExpired(session, now))
                    .max(Comparator.comparing(session -> Instant.parse(session.lastActiveAt)))
                    .orElse(null);

            analyticsService.recordEvent(state, "visitor_init", Map.of(
                    "visitorId", visitor.id,
                    "restoredSessionId", restored == null ? "" : restored.id
            ));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("visitorId", visitor.id);
            result.put("timezone", visitor.timezone);
            result.put("preferredCity", visitor.preferredCity == null ? "" : visitor.preferredCity);
            if (restored == null) {
                result.put("restoredSession", null);
            } else {
                result.put("restoredSession", Map.of(
                        "sessionId", restored.id,
                        "agentId", restored.agentId,
                        "affectionScore", restored.relationshipState.affectionScore,
                        "relationshipStage", restored.relationshipState.relationshipStage,
                        "memoryExpireAt", restored.memoryExpireAt
                ));
            }
            return result;
        });
    }

    List<Map<String, Object>> listAgents() {
        return agentConfigService.listPublicAgents();
    }

    Map<String, Object> updateVisitorContext(Map<String, Object> payload) throws Exception {
        return repository.transact(state -> {
            String visitorId = Json.asString(payload.get("visitorId"));
            VisitorRecord visitor = state.visitors.stream()
                    .filter(item -> visitorId.equals(item.id))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("VISITOR_NOT_FOUND", "VISITOR_NOT_FOUND"));

            String timezone = Json.asString(payload.get("timezone"));
            String preferredCity = Json.asString(payload.get("preferredCity"));
            String nowIso = IsoTimes.now();
            if (timezone != null && !timezone.isBlank()) {
                visitor.timezone = timezone;
            }
            visitor.preferredCity = preferredCity == null ? "" : preferredCity.trim();
            visitor.contextUpdatedAt = nowIso;

            TimeContext timeContext = realityContextService.buildTimeContext(visitor, nowIso);
            WeatherContext weatherContext = realityContextService.buildWeatherContext(visitor, nowIso);
            return Map.of(
                    "saved", true,
                    "timezone", timeContext.timezone,
                    "preferredCity", visitor.preferredCity == null ? "" : visitor.preferredCity,
                    "timeContext", timeContextMap(timeContext),
                    "weatherContext", weatherContextMap(weatherContext)
            );
        });
    }

    Map<String, Object> startSession(String visitorId, String agentId) throws Exception {
        return repository.transact(state -> {
            Instant now = Instant.now();
            VisitorRecord visitor = state.visitors.stream()
                    .filter(item -> visitorId.equals(item.id))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("VISITOR_NOT_FOUND", "VISITOR_NOT_FOUND"));

            AgentProfile agent = agentConfigService.getAgentById(agentId);
            if (agent == null) {
                throw ApiException.notFound("AGENT_NOT_FOUND", "AGENT_NOT_FOUND");
            }

            for (SessionRecord session : state.sessions) {
                if (visitorId.equals(session.visitorId) && agentId.equals(session.agentId) && !memoryService.isExpired(session, now)) {
                    return buildSessionPayload(state, session.id);
                }
            }

            String createdAt = now.toString();
            SessionRecord session = new SessionRecord();
            session.id = ids("session");
            session.visitorId = visitor.id;
            session.agentId = agent.id;
            session.createdAt = createdAt;
            session.lastActiveAt = createdAt;
            session.memoryExpireAt = memoryService.createMemoryExpiry(now);
            session.userTurnCount = 0;
            session.relationshipState = relationshipService.createInitialState();
            session.memorySummary = memoryService.createMemorySummary(createdAt);
            session.storyEventProgress = new StoryEventProgress();
            session.emotionState = affectionJudgeService.createInitial(createdAt);
            session.plotState = plotDirectorService.normalizePlot(null, createdAt);
            session.plotArcState = plotDirectorService.normalizeArc(null, createdAt);
            session.sceneState = sceneDirectorService.normalize(null, createdAt);
            session.presenceState = presenceHeartbeatService.normalizePresence(null, createdAt);
            session.tensionState = boundaryResponseService.normalize(null, createdAt);
            session.pendingChoices = new ArrayList<>();
            state.sessions.add(session);

            ConversationMessage opening = new ConversationMessage();
            opening.id = ids("msg");
            opening.sessionId = session.id;
            opening.role = "assistant";
            opening.text = agent.openingLine;
            opening.createdAt = createdAt;
            opening.emotionTag = "opening";
            opening.confidenceStatus = "system";
            opening.tokenUsage = 0;
            opening.fallbackUsed = false;
            state.messages.add(opening);

            analyticsService.recordEvent(state, "session_start", Map.of(
                    "visitorId", visitorId,
                    "sessionId", session.id,
                    "agentId", agent.id
            ));

            return buildSessionPayload(state, session.id);
        });
    }

    Map<String, Object> getSessionState(String sessionId) throws Exception {
        AppState state = repository.getState();
        return buildSessionPayload(state, sessionId);
    }

    Map<String, Object> exportSessionDebugData(String sessionId) throws Exception {
        AppState state = repository.getState();
        Map<String, Object> sessionPayload = buildSessionPayload(state, sessionId);
        SessionRecord session = findSession(state, sessionId);
        ensureSessionState(session);
        List<ConversationMessage> messages = getSessionMessages(state, session.id);

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("schemaVersion", 1);
        export.put("exportedAt", IsoTimes.now());
        export.put("purpose", "session_debug_snapshot");
        export.put("note", "Per-turn message data is historical; agent signals are the latest persisted session state unless explicitly stored on each message.");
        export.put("sessionId", session.id);
        export.put("agentId", session.agentId);
        export.put("userTurnCount", session.userTurnCount);
        export.put("summary", debugSummary(sessionPayload, messages));
        export.put("turnTimeline", debugTurnTimeline(messages));
        export.put("latestSignals", latestSignalSnapshot(sessionPayload));
        export.put("session", sessionPayload);
        return export;
    }

    Map<String, Object> getAnalyticsOverview() throws Exception {
        AppState state = repository.getState();
        return analyticsService.buildOverview(state, agentConfigService.getAgents());
    }

    Map<String, Object> sendMessage(Map<String, Object> payload) throws Exception {
        return repository.transact(state -> {
            long sendStartedAtNanos = System.nanoTime();
            String visitorId = Json.asString(payload.get("visitorId"));
            String sessionId = Json.asString(payload.get("sessionId"));
            String userMessage = Json.asString(payload.get("userMessage")).trim();
            String quickJudgeMode = quickJudgeMode(payload);
            boolean quickJudgeEnabled = !"off".equals(quickJudgeMode);
            boolean quickJudgeForceAll = "always".equals(quickJudgeMode);
            Long quickJudgeWaitMs = quickJudgeWaitMsFromSeconds(payload.get("quickJudgeWaitSeconds"));
            String nowIso = IsoTimes.now();
            Instant now = Instant.parse(nowIso);

            SessionRecord session = findSession(state, sessionId);
            validateSessionOwner(session, visitorId, now);
            ensureSessionState(session);
            VisitorRecord visitor = requireVisitor(state, visitorId);

            if (session.pendingChoiceEventId != null && !session.pendingChoiceEventId.isBlank()) {
                throw ApiException.badRequest("PENDING_CHOICE", "PENDING_CHOICE");
            }
            if (session.plotArcState != null && session.plotArcState.checkpointReady) {
                throw ApiException.badRequest("CHECKPOINT_REQUIRED", "CHECKPOINT_REQUIRED");
            }

            AgentProfile agent = requireAgent(session.agentId);
            List<ConversationMessage> sessionMessages = getSessionMessages(state, session.id);
            InputInspection inspection = safetyService.inspectUserInput(userMessage, sessionMessages);
            String messageCreatedAt = nowIso;

            ConversationMessage userEntry = new ConversationMessage();
            userEntry.id = ids("msg");
            userEntry.sessionId = session.id;
            userEntry.role = "user";
            userEntry.text = userMessage;
            userEntry.createdAt = messageCreatedAt;
            userEntry.emotionTag = "user";
            userEntry.confidenceStatus = "user";
            userEntry.tokenUsage = 0;
            userEntry.fallbackUsed = false;
            userEntry.replySource = "user_turn";
            state.messages.add(userEntry);

            List<ConversationSnippet> shortTerm = memoryService.getShortTermContext(new ArrayList<>(sessionMessages), 18);
            IntentState intentState = intentInferenceService.infer(
                    userMessage,
                    shortTerm,
                    session.relationshipState,
                    session.sceneState,
                    session.tensionState,
                    session.memorySummary,
                    messageCreatedAt
            );
            DialogueContinuityState dialogueContinuity = dialogueContinuityService.update(
                    session.dialogueContinuityState,
                    userMessage,
                    shortTerm,
                    session.sceneState,
                    messageCreatedAt
            );
            QuickJudgeDecision pendingQuickJudgeCorrection = consumePendingQuickJudgeCorrection(session);
            PendingRepairCue pendingRepairCue = consumePendingRepairCue(session);
            RelationshipScoreCalibration pendingRelationshipCalibration = consumePendingRelationshipCalibration(session);
            if (pendingQuickJudgeCorrection.shouldApply()) {
                intentState = quickJudgeService.refineIntentState(intentState, pendingQuickJudgeCorrection, messageCreatedAt);
                dialogueContinuity = quickJudgeService.refineDialogueContinuity(dialogueContinuity, pendingQuickJudgeCorrection, messageCreatedAt);
            }
            QuickJudgeLocalCorrectionResult firstCorrection = quickJudgeLocalCorrectionService.correct(
                    intentState,
                    dialogueContinuity,
                    session.sceneState,
                    shortTerm,
                    userMessage,
                    messageCreatedAt
            );
            intentState = firstCorrection.intentState;
            dialogueContinuity = firstCorrection.dialogueContinuity;
            TurnUnderstandingState turnUnderstanding = turnUnderstandingService.understand(
                    userMessage,
                    shortTerm,
                    intentState,
                    dialogueContinuity,
                    session.sceneState,
                    messageCreatedAt
            );
            QuickJudgeTask quickJudgeTask = quickJudgeService.start(
                    userMessage,
                    shortTerm,
                    session.relationshipState,
                    session.sceneState,
                    session.tensionState,
                    session.memorySummary,
                    intentState,
                    dialogueContinuity,
                    turnUnderstanding,
                    quickJudgeEnabled,
                    quickJudgeForceAll,
                    session.userTurnCount + 1
            );
            long plotDirectorStartedAtNanos = 0L;
            long plotDirectorFinishedAtNanos = 0L;
            long mainReplyStartedAtNanos = 0L;
            long mainReplyFinishedAtNanos = 0L;
            QuickJudgeDecision quickJudgeDecision = QuickJudgeDecision.none("skipped");
            StoryEvent triggeredEvent = null;
            PlotGateDecision plotGateDecision = emptyPlotGate(messageCreatedAt);
            TurnEvaluation relationship = new TurnEvaluation(session.relationshipState, new Delta());
            EmotionState nextEmotion = affectionJudgeService.normalizeEmotion(session.emotionState, messageCreatedAt);
            RelationalTensionState nextTension = boundaryResponseService.normalize(session.tensionState, messageCreatedAt);
            TurnContext turnContext = buildTurnContext(
                    intentState,
                    relationship,
                    session.sceneState,
                    "user_turn",
                    messageCreatedAt
            );
            applyContinuityToTurnContext(turnContext, dialogueContinuity);
            applyUnderstandingToTurnContext(turnContext, turnUnderstanding);
            PlotDecision plotDecision = new PlotDecision(
                    plotDirectorService.normalizePlot(session.plotState, messageCreatedAt),
                    plotDirectorService.normalizeArc(session.plotArcState, messageCreatedAt),
                    sceneDirectorService.normalize(session.sceneState, messageCreatedAt),
                    false,
                    "user_turn",
                    session.plotState == null ? "" : session.plotState.sceneFrame,
                    ""
            );
            LlmResponse llmReply;
            TimeContext timeContext = realityContextService.buildTimeContext(visitor, messageCreatedAt);
            WeatherContext weatherContext = realityContextService.buildWeatherContext(visitor, messageCreatedAt);
            MemoryUsePlan memoryUsePlan = memoryService.planMemoryUse(session.memorySummary, userMessage, "user_turn", plotDecision.sceneFrame);
            ResponsePlan responsePlan = responsePlanningService.plan(
                    intentState,
                    session.relationshipState,
                    nextEmotion,
                    nextTension,
                    plotGateDecision,
                    "user_turn",
                    messageCreatedAt
            );
            InitiativeDecision initiativeDecision = initiativePolicyService.decide(
                    intentState,
                    responsePlan,
                    nextEmotion,
                    nextTension,
                    plotGateDecision,
                    "user_turn",
                    messageCreatedAt
            );
            SearchDecision searchDecision = new SearchDecision(false, "", "skip", "skip", false);
            SearchGroundingSummary searchGroundingSummary = realityGuardService.groundingFromDecision(searchDecision);
            RealityEnvelope realityEnvelope = realityGuardService.buildEnvelope(timeContext, weatherContext, plotDecision.nextSceneState, searchGroundingSummary);
            UncertaintyState uncertaintyState = buildUncertaintyState(intentState, searchDecision, plotGateDecision, messageCreatedAt);
            RealityAudit realityAudit = passRealityAudit();
            HumanizationAudit humanizationAudit = emptyHumanizationAudit();
            List<MemoryIntentBinding> memoryIntentBindings = buildMemoryIntentBindings(memoryUsePlan, null);

            if (inspection.blocked) {
                quickJudgeDecision = QuickJudgeDecision.none("blocked_by_safety");
                nextEmotion = affectionJudgeService.coolDownForSilence(session.emotionState, messageCreatedAt);
                llmReply = new LlmResponse(
                        inspection.safeMessage,
                        "guarded",
                        "guarded",
                        inspection.safeMessage.length(),
                        inspection.reason,
                        true,
                        "safety"
                );
            } else {
                StoryEvent candidateEvent = eventEngine.findTriggeredEvent(agent, session, userMessage);
                StoryEvent scoringEvent = candidateEvent != null && candidateEvent.keyChoiceEvent ? null : candidateEvent;
                AffectionScoreResult affectionScoreResult = affectionJudgeService.evaluateTurn(
                        userMessage,
                        session.relationshipState,
                        session.emotionState,
                        scoringEvent,
                        session.memorySummary,
                        relationshipService,
                        agent,
                        messageCreatedAt
                );
                relationship = affectionScoreResult.turnEvaluation;
                relationship = applyRelationshipCalibration(
                        relationship,
                        session.relationshipState,
                        pendingRelationshipCalibration,
                        messageCreatedAt
                );
                RelationshipCalibrationTask relationshipCalibrationTask = relationshipCalibrationService.start(
                        session.userTurnCount + 1,
                        userMessage,
                        shortTerm,
                        agent,
                        session.relationshipState,
                        relationship
                );
                registerRelationshipCalibration(session.id, relationshipCalibrationTask);
                nextEmotion = affectionScoreResult.nextEmotion;
                nextTension = boundaryResponseService.evaluate(
                        userMessage,
                        session.tensionState,
                        relationship,
                        temperamentProfileFor(agent),
                        messageCreatedAt
                );
                turnContext = buildTurnContext(
                        intentState,
                        relationship,
                        session.sceneState,
                        "user_turn",
                        messageCreatedAt
                );
                applyContinuityToTurnContext(turnContext, dialogueContinuity);
                applyUnderstandingToTurnContext(turnContext, turnUnderstanding);
                plotDirectorStartedAtNanos = System.nanoTime();
                plotDecision = plotDirectorService.decide(
                        session,
                        userMessage,
                        nextEmotion,
                        relationship.nextState,
                        session.memorySummary,
                        timeContext,
                        weatherContext,
                        "user_turn",
                        messageCreatedAt,
                        turnContext
                );
                plotDirectorFinishedAtNanos = System.nanoTime();
                dialogueContinuity = dialogueContinuityService.settleSceneTransitionIfArrived(
                        dialogueContinuity,
                        plotDecision.nextSceneState,
                        messageCreatedAt
                );
                QuickJudgeLocalCorrectionResult secondCorrection = quickJudgeLocalCorrectionService.correct(
                        intentState,
                        dialogueContinuity,
                        plotDecision.nextSceneState,
                        shortTerm,
                        userMessage,
                        messageCreatedAt
                );
                intentState = secondCorrection.intentState;
                dialogueContinuity = secondCorrection.dialogueContinuity;
                turnUnderstanding = turnUnderstandingService.understand(
                        userMessage,
                        shortTerm,
                        intentState,
                        dialogueContinuity,
                        plotDecision.nextSceneState,
                        messageCreatedAt
                );
                applyContinuityToTurnContext(turnContext, dialogueContinuity);
                applyUnderstandingToTurnContext(turnContext, turnUnderstanding);
                relationship = applyPlotMacroScore(
                        relationship,
                        plotDecision,
                        turnContext,
                        nextTension,
                        messageCreatedAt
                );
                plotGateDecision = plotGateService.decide(
                        candidateEvent,
                        session,
                        plotDecision.nextSceneState,
                        relationship.nextState,
                        nextTension,
                        messageCreatedAt
                );
                triggeredEvent = plotGateDecision.allowed ? candidateEvent : null;
                memoryUsePlan = memoryService.planMemoryUse(session.memorySummary, userMessage, plotDecision.replySource, plotDecision.sceneFrame);
                String longTermSummary = memoryService.getTieredSummaryText(session.memorySummary);
                MemoryRecall recall = sanitizeMemoryRecall(memoryService.recallRelevantMemories(session.memorySummary, userMessage, 2));
                memoryIntentBindings = buildMemoryIntentBindings(memoryUsePlan, recall);
                String currentUserMood = memoryService.detectMood(userMessage);
                searchDecision = searchDecisionService.decide(userMessage, plotDecision.replySource, plotDecision.nextSceneState, intentState);
                searchGroundingSummary = realityGuardService.groundingFromDecision(searchDecision);
                realityEnvelope = realityGuardService.buildEnvelope(timeContext, weatherContext, plotDecision.nextSceneState, searchGroundingSummary);
                uncertaintyState = buildUncertaintyState(intentState, searchDecision, plotGateDecision, messageCreatedAt);
                responsePlan = responsePlanningService.plan(
                        intentState,
                        relationship.nextState,
                        nextEmotion,
                        nextTension,
                        plotGateDecision,
                        plotDecision.replySource,
                        messageCreatedAt
                );
                responsePlan = quickJudgeService.refineResponsePlan(responsePlan, quickJudgeDecision, intentState, messageCreatedAt);
                initiativeDecision = initiativePolicyService.decide(
                        intentState,
                        responsePlan,
                        nextEmotion,
                        nextTension,
                        plotGateDecision,
                        plotDecision.replySource,
                        messageCreatedAt
                );
                String responseCadence = memoryService.determineResponseCadence(userMessage, relationship.nextState, triggeredEvent);
                String responseDirective = (memoryService.buildTieredResponseDirective(session.memorySummary, userMessage, relationship.nextState, triggeredEvent)
                        + " 当前记忆使用模式：" + memoryUsePlan.useMode
                        + "。原因：" + memoryUsePlan.relevanceReason).trim();

                // Spend the extra quick-judge wait budget only when the main reply is
                // about to be sent, instead of blocking earlier local planning steps.
                quickJudgeDecision = quickJudgeService.resolve(quickJudgeTask, quickJudgeService.resolveBudgetMs(quickJudgeWaitMs, quickJudgeTask));
                if (shouldStoreLateQuickJudge(quickJudgeDecision)) {
                    registerLateQuickJudgeCorrection(session.id, quickJudgeTask);
                }
                intentState = quickJudgeService.refineIntentState(intentState, quickJudgeDecision, messageCreatedAt);
                dialogueContinuity = quickJudgeService.refineDialogueContinuity(dialogueContinuity, quickJudgeDecision, messageCreatedAt);
                dialogueContinuity = dialogueContinuityService.settleSceneTransitionIfArrived(
                        dialogueContinuity,
                        plotDecision.nextSceneState,
                        messageCreatedAt
                );
                QuickJudgeLocalCorrectionResult thirdCorrection = quickJudgeLocalCorrectionService.correct(
                        intentState,
                        dialogueContinuity,
                        plotDecision.nextSceneState,
                        shortTerm,
                        userMessage,
                        messageCreatedAt
                );
                intentState = thirdCorrection.intentState;
                dialogueContinuity = thirdCorrection.dialogueContinuity;
                turnUnderstanding = turnUnderstandingService.understand(
                        userMessage,
                        shortTerm,
                        intentState,
                        dialogueContinuity,
                        plotDecision.nextSceneState,
                        messageCreatedAt
                );
                turnContext.primaryIntent = intentState == null ? "" : blankTo(intentState.primaryIntent, "");
                turnContext.secondaryIntent = intentState == null ? "" : blankTo(intentState.secondaryIntent, "");
                turnContext.clarity = intentState == null ? "" : blankTo(intentState.clarity, "");
                turnContext.userEmotion = intentState == null ? "" : blankTo(intentState.emotion, "");
                turnContext.updatedAt = messageCreatedAt;
                applyContinuityToTurnContext(turnContext, dialogueContinuity);
                applyUnderstandingToTurnContext(turnContext, turnUnderstanding);
                searchDecision = searchDecisionService.decide(userMessage, plotDecision.replySource, plotDecision.nextSceneState, intentState);
                searchGroundingSummary = realityGuardService.groundingFromDecision(searchDecision);
                realityEnvelope = realityGuardService.buildEnvelope(timeContext, weatherContext, plotDecision.nextSceneState, searchGroundingSummary);
                uncertaintyState = buildUncertaintyState(intentState, searchDecision, plotGateDecision, messageCreatedAt);
                responsePlan = responsePlanningService.plan(
                        intentState,
                        relationship.nextState,
                        nextEmotion,
                        nextTension,
                        plotGateDecision,
                        plotDecision.replySource,
                        messageCreatedAt
                );
                responsePlan = quickJudgeService.refineResponsePlan(responsePlan, quickJudgeDecision, intentState, messageCreatedAt);
                initiativeDecision = initiativePolicyService.decide(
                        intentState,
                        responsePlan,
                        nextEmotion,
                        nextTension,
                        plotGateDecision,
                        plotDecision.replySource,
                        messageCreatedAt
                );

                mainReplyStartedAtNanos = System.nanoTime();
                llmReply = llmClient.generateReply(new LlmRequest(
                        agent,
                        relationship.nextState,
                        shortTerm,
                        longTermSummary,
                        recall.tier,
                        recall.mergedText,
                        currentUserMood,
                        responseCadence,
                        responseDirective,
                        triggeredEvent,
                        userMessage,
                        timeContext,
                        weatherContext,
                        plotDecision.sceneFrame,
                        plotDecision.nextSceneState,
                        memoryUsePlan,
                        nextEmotion,
                        plotDecision.replySource,
                        temperamentProfileFor(agent),
                        searchDecision.enabled ? searchDecision.query : "",
                        intentState,
                        responsePlan,
                        uncertaintyState,
                        initiativeDecision,
                        memoryIntentBindings,
                        realityEnvelope,
                        nextTension,
                        plotGateDecision,
                        dialogueContinuity,
                        pendingRepairCue,
                        turnContext
                ));
                mainReplyFinishedAtNanos = System.nanoTime();

                RealityGuardResult guardResult = realityGuardService.auditAndRepair(
                        llmReply,
                        realityEnvelope,
                        userMessage,
                        plotDecision.nextSceneState
                );
                llmReply = guardResult.reply;
                realityAudit = guardResult.realityAudit;
                humanizationAudit = humanizationEvaluationService.evaluate(
                        userMessage,
                        llmReply,
                        intentState,
                        responsePlan,
                        memoryUsePlan,
                        realityAudit
                );

                InputInspection outputInspection = safetyService.inspectAssistantOutput(llmReply.replyText);
                if (outputInspection.blocked) {
                    llmReply = new LlmResponse(
                            llmClient.buildFallbackReply(agent, outputInspection.reason),
                            "guarded",
                            "fallback",
                            0,
                            outputInspection.reason,
                            true,
                            "fallback"
                    );
                }
            }

            String replyCreatedAt = IsoTimes.now();
            ConversationMessage assistantEntry = new ConversationMessage();
            assistantEntry.id = ids("msg");
            assistantEntry.sessionId = session.id;
            assistantEntry.role = "assistant";
            String rawSpeechText = llmReply.speechText == null || llmReply.speechText.isBlank() ? llmReply.replyText : llmReply.speechText;
            assistantEntry.sceneText = selectSceneText(plotDecision.sceneText, llmReply.sceneText, rawSpeechText, plotDecision.replySource);
            assistantEntry.actionText = llmReply.actionText;
            assistantEntry.speechText = removeSceneTextFromSpeech(rawSpeechText, assistantEntry.sceneText);
            assistantEntry.text = assistantEntry.speechText;
            assistantEntry.createdAt = replyCreatedAt;
            assistantEntry.emotionTag = llmReply.emotionTag;
            assistantEntry.confidenceStatus = llmReply.confidenceStatus;
            assistantEntry.tokenUsage = llmReply.tokenUsage;
            assistantEntry.fallbackUsed = llmReply.fallbackUsed;
            assistantEntry.triggeredEventId = triggeredEvent == null ? null : triggeredEvent.id;
            assistantEntry.affectionDelta = relationship.affectionDelta.total;
            assistantEntry.replySource = plotDecision.replySource;
            state.messages.add(assistantEntry);

            session.lastActiveAt = replyCreatedAt;
            session.memoryExpireAt = memoryService.createMemoryExpiry(Instant.parse(replyCreatedAt));
            session.userTurnCount += 1;
            session.relationshipState = relationship.nextState;
            session.emotionState = nextEmotion;
            session.tensionState = nextTension;
            session.plotState = plotDecision.nextPlotState;
            session.plotArcState = plotDecision.nextPlotArcState == null ? session.plotArcState : plotDecision.nextPlotArcState;
            session.sceneState = plotDecision.nextSceneState == null ? session.sceneState : plotDecision.nextSceneState;
            session.presenceState = presenceHeartbeatService.registerUserTurn(session.presenceState, replyCreatedAt);
            session.lastIntentState = intentState;
            session.lastResponsePlan = responsePlan;
            session.lastHumanizationAudit = humanizationAudit;
            session.lastRealityAudit = realityAudit;
            session.lastPlotGateDecision = plotGateDecision;
            session.lastTurnContext = turnContext;
            session.dialogueContinuityState = dialogueContinuity;
            session.lastQuickJudgeStatus = quickJudgeStatusFrom(quickJudgeDecision, replyCreatedAt);
            applyQuickJudgeTriggerInfo(session.lastQuickJudgeStatus, quickJudgeTask);
            if (!inspection.blocked) {
                session.memorySummary = memoryService.updateTieredSummary(
                        session.memorySummary,
                        userMessage,
                        triggeredEvent,
                        relationship.nextState.relationshipStage,
                        replyCreatedAt
                );
                session.memorySummary.lastMemoryUseMode = memoryUsePlan.useMode;
                session.memorySummary.lastMemoryRelevanceReason = memoryUsePlan.relevanceReason;
            }
            session.memorySummary.lastResponseCadence = inspection.blocked ? session.memorySummary.lastResponseCadence : memoryService.determineResponseCadence(userMessage, relationship.nextState, triggeredEvent);

            if (triggeredEvent != null) {
                session.storyEventProgress.lastTriggeredTitle = triggeredEvent.title;
                session.storyEventProgress.lastTriggeredTheme = triggeredEvent.theme;
                session.storyEventProgress.currentRouteTheme = triggeredEvent.successEffects.routeTag;
                session.storyEventProgress.nextExpectedDirection = triggeredEvent.nextDirection;
                if (triggeredEvent.keyChoiceEvent) {
                    session.pendingChoiceEventId = triggeredEvent.id;
                    session.pendingChoices = new ArrayList<>(triggeredEvent.choiceSet);
                    session.pendingEventContext = triggeredEvent.theme;
                } else {
                    markEventTriggered(session, triggeredEvent);
                }
            }
            if (plotDecision.advanced) {
                session.storyEventProgress.lastTriggeredTitle = "剧情推进 · " + plotDecision.nextPlotState.plotProgress;
                session.storyEventProgress.lastTriggeredTheme = plotDecision.sceneFrame;
                session.storyEventProgress.currentRouteTheme = plotDecision.nextPlotState.phase;
                session.storyEventProgress.nextExpectedDirection = plotDecision.nextPlotState.nextBeatHint;
            }

            analyticsService.recordEvent(state, "chat_turn", Map.of(
                    "visitorId", visitorId,
                    "sessionId", session.id,
                    "agentId", session.agentId,
                    "triggeredEventId", triggeredEvent == null ? "" : triggeredEvent.id,
                    "fallbackUsed", llmReply.fallbackUsed
            ));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("reply_text", assistantEntry.text);
            response.put("scene_text", assistantEntry.sceneText);
            response.put("action_text", assistantEntry.actionText);
            response.put("speech_text", assistantEntry.speechText);
            response.put("affection_score", session.relationshipState.affectionScore);
            response.put("affection_delta", Map.of(
                    "closeness", relationship.affectionDelta.closeness,
                    "trust", relationship.affectionDelta.trust,
                    "resonance", relationship.affectionDelta.resonance,
                    "total", relationship.affectionDelta.total
            ));
            response.put("relationship_stage", session.relationshipState.relationshipStage);
            response.put("triggered_event", triggeredEvent == null ? null : eventMap(triggeredEvent));
            response.put("memory_expire_at", session.memoryExpireAt);
            response.put("fallback_used", llmReply.fallbackUsed);
            response.put("ending", session.relationshipState.ending);
            response.put("interaction_mode", session.pendingChoiceEventId == null ? "chat" : "choice");
            response.put("choices", serializeChoices(session.pendingChoices));
            response.put("event_context", session.pendingEventContext);
            response.put("relationship_feedback", relationship.relationshipFeedback);
            response.put("ending_candidate", session.relationshipState.endingCandidate);
            response.put("score_reasons", relationship.scoreReasons == null ? List.of() : relationship.scoreReasons);
            response.put("behavior_tags", relationship.behaviorTags);
            response.put("risk_flags", relationship.riskFlags);
            response.put("pending_relationship_calibration", relationshipCalibrationMap(session.pendingRelationshipCalibration));
            response.put("pending_relationship_calibration_at", blankTo(session.pendingRelationshipCalibrationAt, ""));
            response.put("intent_state", intentStateMap(session.lastIntentState));
            response.put("response_plan", responsePlanMap(session.lastResponsePlan));
            response.put("humanization_audit", humanizationAuditMap(session.lastHumanizationAudit));
            response.put("reality_audit", realityAuditMap(session.lastRealityAudit));
            response.put("plot_gate_reason", plotGateDecisionMap(session.lastPlotGateDecision));
            response.put("turn_context", turnContextMap(session.lastTurnContext));
            response.put("dialogue_continuity", dialogueContinuityMap(session.dialogueContinuityState));
            response.put("quick_judge_status", quickJudgeStatusMap(session.lastQuickJudgeStatus));
            response.put("plot_director_decision", plotDecision.plotDirectorReason);
            response.put("tension_state", tensionStateMap(session.tensionState));
            response.put("emotion_state", emotionStateMap(session.emotionState));
            response.put("plot_progress", plotStateMap(session.plotState));
            response.put("scene_state", sceneStateMap(session.sceneState));
            response.put("plot_arc_state", plotArcStateMap(session.plotArcState));
            response.put("scene_frame", session.plotState == null ? "" : session.plotState.sceneFrame);
            response.put("reply_source", plotDecision.replySource);
            response.put("agent_timing", agentTimingMap(
                    sendStartedAtNanos,
                    quickJudgeTask,
                    plotDirectorStartedAtNanos,
                    plotDirectorFinishedAtNanos,
                    mainReplyStartedAtNanos,
                    mainReplyFinishedAtNanos
            ));
            response.put("run_status", session.plotArcState == null ? "" : session.plotArcState.runStatus);
            response.put("checkpoint_ready", session.plotArcState != null && session.plotArcState.checkpointReady);
            response.put("current_arc_index", session.plotArcState == null ? 1 : session.plotArcState.arcIndex);
            response.put("current_beat_index", session.plotArcState == null ? 0 : session.plotArcState.beatIndex);
            response.put("can_settle_score", session.plotArcState != null && session.plotArcState.canSettleScore);
            response.put("can_continue", session.plotArcState != null && session.plotArcState.canContinue);
            response.put("arc_summary_preview", arcSummaryMap(session.plotArcState == null ? null : session.plotArcState.latestArcSummary));
            return response;
        });
    }

    Map<String, Object> updatePresence(Map<String, Object> payload) throws Exception {
        return repository.transact(state -> {
            String visitorId = Json.asString(payload.get("visitorId"));
            String sessionId = Json.asString(payload.get("sessionId"));
            boolean visible = payload.get("visible") instanceof Boolean bool && bool;
            boolean focused = payload.get("focused") instanceof Boolean bool && bool;
            boolean isTyping = payload.get("isTyping") instanceof Boolean bool && bool;
            int draftLength = Json.asInt(payload.get("draftLength"), 0);
            String lastInputAt = Json.asString(payload.get("lastInputAt"));
            String clientTime = Json.asString(payload.get("clientTime"));
            String nowIso = clientTime == null || clientTime.isBlank() ? IsoTimes.now() : clientTime;
            Instant now = Instant.parse(nowIso);

            SessionRecord session = findSession(state, sessionId);
            validateSessionOwner(session, visitorId, now);
            ensureSessionState(session);
            VisitorRecord visitor = requireVisitor(state, visitorId);

            PresenceResult presenceResult = presenceHeartbeatService.ingest(
                    session.presenceState,
                    session,
                    visible,
                    focused,
                    isTyping,
                    draftLength,
                    lastInputAt,
                    nowIso
            );
            session.presenceState = presenceResult.nextState;
            session.lastActiveAt = nowIso;
            visitor.lastActiveAt = nowIso;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("online", session.presenceState.online);
            response.put("presenceState", presenceStateMap(session.presenceState));
            response.put("proactive_message", null);
            response.put("trigger_reason", presenceResult.triggerReason);
            response.put("blocked_reason", presenceResult.blockedReason);
            response.put("heartbeat_explain", presenceResult.heartbeatExplain);
            response.put("initiative_decision", Map.of());
            response.put("humanization_audit", Map.of());
            response.put("reality_audit", Map.of());
            response.put("interaction_mode", session.sceneState == null ? "" : session.sceneState.interactionMode);

            if (!presenceResult.shouldSend) {
                return response;
            }

            List<ConversationMessage> sessionMessages = getSessionMessages(state, session.id);
            if (!sessionMessages.isEmpty()) {
                ConversationMessage lastMessage = sessionMessages.get(sessionMessages.size() - 1);
                boolean lastWasNonUserProactive = "assistant".equals(lastMessage.role)
                        && lastMessage.replySource != null
                        && !"user_turn".equals(lastMessage.replySource)
                        && !"choice_result".equals(lastMessage.replySource);
                if (lastWasNonUserProactive) {
                    return response;
                }
            }

            AgentProfile agent = requireAgent(session.agentId);
            TimeContext timeContext = realityContextService.buildTimeContext(visitor, nowIso);
            WeatherContext weatherContext = realityContextService.buildWeatherContext(visitor, nowIso);
            EmotionState nextEmotion = affectionJudgeService.coolDownForSilence(session.emotionState, nowIso);
            RelationalTensionState nextTension = boundaryResponseService.normalize(session.tensionState, nowIso);
            List<ConversationSnippet> shortTerm = memoryService.getShortTermContext(new ArrayList<>(sessionMessages), 18);
            String heartbeatAnchorMessage = lastMessageText(sessionMessages, "user");
            String heartbeatAssistantMessage = lastMessageText(sessionMessages, "assistant");
            DialogueContinuityState dialogueContinuity = dialogueContinuityService.update(
                    session.dialogueContinuityState,
                    heartbeatAnchorMessage,
                    shortTerm,
                    session.sceneState,
                    nowIso
            );
            dialogueContinuity = dialogueContinuityService.applyHeartbeatSelfContext(
                    dialogueContinuity,
                    heartbeatAssistantMessage,
                    nowIso
            );
            QuickJudgeDecision pendingQuickJudgeCorrection = consumePendingQuickJudgeCorrection(session);
            PendingRepairCue pendingRepairCue = consumePendingRepairCue(session);
            IntentState intentState = intentInferenceService.infer(
                    heartbeatAnchorMessage,
                    shortTerm,
                    session.relationshipState,
                    session.sceneState,
                    nextTension,
                    session.memorySummary,
                    nowIso
            );
            if (pendingQuickJudgeCorrection.shouldApply()) {
                intentState = quickJudgeService.refineIntentState(intentState, pendingQuickJudgeCorrection, nowIso);
                dialogueContinuity = quickJudgeService.refineDialogueContinuity(dialogueContinuity, pendingQuickJudgeCorrection, nowIso);
            }
            QuickJudgeLocalCorrectionResult heartbeatCorrection = quickJudgeLocalCorrectionService.correct(
                    intentState,
                    dialogueContinuity,
                    session.sceneState,
                    shortTerm,
                    heartbeatAnchorMessage,
                    nowIso
            );
            intentState = heartbeatCorrection.intentState;
            dialogueContinuity = heartbeatCorrection.dialogueContinuity;
            TurnUnderstandingState heartbeatUnderstanding = turnUnderstandingService.understand(
                    heartbeatAnchorMessage,
                    shortTerm,
                    intentState,
                    dialogueContinuity,
                    session.sceneState,
                    nowIso
            );
            TurnContext heartbeatTurnContext = buildTurnContext(
                    intentState,
                    new TurnEvaluation(session.relationshipState, new Delta()),
                    session.sceneState,
                    presenceResult.replySource,
                    nowIso
            );
            copyPlotSignals(heartbeatTurnContext, session.lastTurnContext);
            applyContinuityToTurnContext(heartbeatTurnContext, dialogueContinuity);
            applyUnderstandingToTurnContext(heartbeatTurnContext, heartbeatUnderstanding);
            PlotDecision plotDecision = plotDirectorService.decide(
                    session,
                    "",
                    nextEmotion,
                    session.relationshipState,
                    session.memorySummary,
                    timeContext,
                    weatherContext,
                    presenceResult.replySource,
                    nowIso,
                    heartbeatTurnContext
            );
            TurnContext plotHeartbeatTurnContext = heartbeatTurnContext;
            dialogueContinuity = dialogueContinuityService.settleSceneTransitionIfArrived(
                    dialogueContinuity,
                    plotDecision.nextSceneState,
                    nowIso
            );
            heartbeatCorrection = quickJudgeLocalCorrectionService.correct(
                    intentState,
                    dialogueContinuity,
                    plotDecision.nextSceneState,
                    shortTerm,
                    heartbeatAnchorMessage,
                    nowIso
            );
            intentState = heartbeatCorrection.intentState;
            dialogueContinuity = heartbeatCorrection.dialogueContinuity;
            heartbeatUnderstanding = turnUnderstandingService.understand(
                    heartbeatAnchorMessage,
                    shortTerm,
                    intentState,
                    dialogueContinuity,
                    plotDecision.nextSceneState,
                    nowIso
            );
            heartbeatTurnContext = buildTurnContext(
                    intentState,
                    new TurnEvaluation(session.relationshipState, new Delta()),
                    plotDecision.nextSceneState,
                    presenceResult.replySource,
                    nowIso
            );
            copyPlotSignals(heartbeatTurnContext, plotHeartbeatTurnContext);
            applyContinuityToTurnContext(heartbeatTurnContext, dialogueContinuity);
            applyUnderstandingToTurnContext(heartbeatTurnContext, heartbeatUnderstanding);
            MemoryUsePlan memoryUsePlan = memoryService.planMemoryUse(session.memorySummary, heartbeatAnchorMessage, presenceResult.replySource, plotDecision.sceneFrame);
            PlotGateDecision plotGateDecision = emptyPlotGate(nowIso);
            ResponsePlan responsePlan = responsePlanningService.plan(
                    intentState,
                    session.relationshipState,
                    nextEmotion,
                    nextTension,
                    plotGateDecision,
                    presenceResult.replySource,
                    nowIso
            );
            InitiativeDecision initiativeDecision = initiativePolicyService.decide(
                    intentState,
                    responsePlan,
                    nextEmotion,
                    nextTension,
                    plotGateDecision,
                    presenceResult.replySource,
                    nowIso
            );
            SearchDecision searchDecision = new SearchDecision(false, "", "skip", "skip", false);
            SearchGroundingSummary searchGroundingSummary = realityGuardService.groundingFromDecision(searchDecision);
            RealityEnvelope realityEnvelope = realityGuardService.buildEnvelope(timeContext, weatherContext, plotDecision.nextSceneState, searchGroundingSummary);
            UncertaintyState uncertaintyState = buildUncertaintyState(intentState, searchDecision, plotGateDecision, nowIso);
            List<MemoryIntentBinding> memoryIntentBindings = buildMemoryIntentBindings(memoryUsePlan, null);
            String responseDirective = (memoryService.buildTieredResponseDirective(session.memorySummary, heartbeatAnchorMessage, session.relationshipState, null)
                    + " 当前这是角色主动发出的消息。reply_source=" + presenceResult.replySource
                    + (heartbeatAnchorMessage.isBlank()
                    ? "。没有明确上一轮用户问题时，保持轻量陪伴。"
                    : "。先复盘上一轮用户意图；如果上一轮有未回答问题、质疑或修正需求，优先补答或承接，不要突然换成泛泛陪伴。上一轮用户消息：" + heartbeatAnchorMessage)
                    + (pendingQuickJudgeCorrection.shouldApply() ? "。已有晚到意图修正已融合，优先按修正后的意图回复。" : "")
                    + "。记忆使用原因：" + memoryUsePlan.relevanceReason).trim();

            responseDirective = (responseDirective + heartbeatSelfContextDirective(heartbeatAssistantMessage)).trim();

            LlmResponse llmReply = llmClient.generateReply(new LlmRequest(
                    agent,
                    session.relationshipState,
                    shortTerm,
                    memoryService.getTieredSummaryText(session.memorySummary),
                    "none",
                    "",
                    heartbeatAnchorMessage.isBlank() ? session.memorySummary.lastUserMood : memoryService.detectMood(heartbeatAnchorMessage),
                    heartbeatAnchorMessage.isBlank() ? "light_ping" : "continuity_ping",
                    responseDirective,
                    null,
                    heartbeatAnchorMessage,
                    timeContext,
                    weatherContext,
                    plotDecision.sceneFrame,
                    plotDecision.nextSceneState,
                    memoryUsePlan,
                    nextEmotion,
                    presenceResult.replySource,
                    temperamentProfileFor(agent),
                    "",
                    intentState,
                    responsePlan,
                    uncertaintyState,
                    initiativeDecision,
                    memoryIntentBindings,
                    realityEnvelope,
                    nextTension,
                    plotGateDecision,
                    dialogueContinuity,
                    pendingRepairCue,
                    heartbeatTurnContext
            ));

            RealityGuardResult guardResult = realityGuardService.auditAndRepair(
                    llmReply,
                    realityEnvelope,
                    "",
                    plotDecision.nextSceneState
            );
            llmReply = guardResult.reply;
            RealityAudit realityAudit = guardResult.realityAudit;
            HumanizationAudit humanizationAudit = humanizationEvaluationService.evaluate(
                    "",
                    llmReply,
                    intentState,
                    responsePlan,
                    memoryUsePlan,
                    realityAudit
            );

            InputInspection outputInspection = safetyService.inspectAssistantOutput(llmReply.replyText);
            if (outputInspection.blocked) {
                llmReply = new LlmResponse(
                        llmClient.buildFallbackReply(agent, outputInspection.reason),
                        "guarded",
                        "fallback",
                        0,
                        outputInspection.reason,
                        true,
                        "fallback"
                );
            }

            ConversationMessage proactiveEntry = new ConversationMessage();
            proactiveEntry.id = ids("msg");
            proactiveEntry.sessionId = session.id;
            proactiveEntry.role = "assistant";
            String rawSpeechText = llmReply.speechText == null || llmReply.speechText.isBlank() ? llmReply.replyText : llmReply.speechText;
            proactiveEntry.sceneText = selectSceneText(plotDecision.sceneText, llmReply.sceneText, rawSpeechText, plotDecision.replySource);
            proactiveEntry.actionText = llmReply.actionText;
            proactiveEntry.speechText = removeSceneTextFromSpeech(rawSpeechText, proactiveEntry.sceneText);
            proactiveEntry.text = proactiveEntry.speechText;
            proactiveEntry.createdAt = nowIso;
            proactiveEntry.emotionTag = llmReply.emotionTag;
            proactiveEntry.confidenceStatus = llmReply.confidenceStatus;
            proactiveEntry.tokenUsage = llmReply.tokenUsage;
            proactiveEntry.fallbackUsed = llmReply.fallbackUsed;
            proactiveEntry.replySource = presenceResult.replySource;
            state.messages.add(proactiveEntry);

            session.emotionState = nextEmotion;
            session.tensionState = nextTension;
            session.plotState = plotDecision.nextPlotState;
            session.plotArcState = plotDecision.nextPlotArcState == null ? session.plotArcState : plotDecision.nextPlotArcState;
            session.sceneState = plotDecision.nextSceneState == null ? session.sceneState : plotDecision.nextSceneState;
            session.lastProactiveMessageAt = nowIso;
            session.memoryExpireAt = memoryService.createMemoryExpiry(now);
            session.lastIntentState = intentState;
            session.lastResponsePlan = responsePlan;
            session.lastHumanizationAudit = humanizationAudit;
            session.lastRealityAudit = realityAudit;
            session.lastPlotGateDecision = plotGateDecision;
            session.dialogueContinuityState = dialogueContinuity;
            session.lastTurnContext = heartbeatTurnContext;
            session.lastQuickJudgeStatus = quickJudgeStatusFrom(QuickJudgeDecision.none("not_applicable_presence"), nowIso);
            if (plotDecision.advanced) {
                session.storyEventProgress.lastTriggeredTitle = "剧情推进 · " + plotDecision.nextPlotState.plotProgress;
                session.storyEventProgress.lastTriggeredTheme = plotDecision.sceneFrame;
                session.storyEventProgress.currentRouteTheme = plotDecision.nextPlotState.phase;
                session.storyEventProgress.nextExpectedDirection = plotDecision.nextPlotState.nextBeatHint;
            }

            analyticsService.recordEvent(state, "session_presence", Map.of(
                    "visitorId", visitorId,
                    "sessionId", session.id,
                    "agentId", session.agentId,
                    "fallbackUsed", llmReply.fallbackUsed
            ));

            response.put("proactive_message", messageMap(proactiveEntry));
            response.put("initiative_decision", initiativeDecisionMap(initiativeDecision));
            response.put("humanization_audit", humanizationAuditMap(humanizationAudit));
            response.put("reality_audit", realityAuditMap(realityAudit));
            response.put("interaction_mode", session.sceneState == null ? "" : session.sceneState.interactionMode);
            response.put("emotion_state", emotionStateMap(session.emotionState));
            response.put("plot_progress", plotStateMap(session.plotState));
            response.put("plot_arc_state", plotArcStateMap(session.plotArcState));
            response.put("scene_state", sceneStateMap(session.sceneState));
            response.put("scene_frame", session.plotState.sceneFrame);
            response.put("reply_source", presenceResult.replySource);
            response.put("plot_director_decision", plotDecision.plotDirectorReason);
            response.put("turn_context", turnContextMap(session.lastTurnContext));
            response.put("dialogue_continuity", dialogueContinuityMap(session.dialogueContinuityState));
            response.put("quick_judge_status", quickJudgeStatusMap(session.lastQuickJudgeStatus));
            response.put("run_status", session.plotArcState == null ? "" : session.plotArcState.runStatus);
            response.put("checkpoint_ready", session.plotArcState != null && session.plotArcState.checkpointReady);
            response.put("arc_summary_preview", arcSummaryMap(session.plotArcState == null ? null : session.plotArcState.latestArcSummary));
            return response;
        });
    }

    Map<String, Object> chooseEvent(Map<String, Object> payload) throws Exception {
        return repository.transact(state -> {
            String visitorId = Json.asString(payload.get("visitorId"));
            String sessionId = Json.asString(payload.get("sessionId"));
            String choiceId = Json.asString(payload.get("choiceId"));
            Instant now = Instant.now();

            SessionRecord session = findSession(state, sessionId);
            validateSessionOwner(session, visitorId, now);
            ensureSessionState(session);
            if (session.pendingChoiceEventId == null || session.pendingChoiceEventId.isBlank()) {
                throw ApiException.badRequest("NO_PENDING_CHOICE", "NO_PENDING_CHOICE");
            }

            AgentProfile agent = requireAgent(session.agentId);
            StoryEvent event = eventEngine.findEventById(agent, session.pendingChoiceEventId);
            if (event == null) {
                throw ApiException.notFound("EVENT_NOT_FOUND", "EVENT_NOT_FOUND");
            }
            ChoiceOption selectedChoice = session.pendingChoices.stream()
                    .filter(item -> item.id.equals(choiceId))
                    .findFirst()
                    .orElseThrow(() -> ApiException.badRequest("CHOICE_NOT_FOUND", "CHOICE_NOT_FOUND"));

            RelationshipState nextState = session.relationshipState;
            if (relationshipService instanceof NarrativeRelationshipService narrativeRelationshipService) {
                nextState = narrativeRelationshipService.applyChoiceOutcome(session.relationshipState, event, selectedChoice, session.storyEventProgress);
            }

            String nowIso = IsoTimes.now();
            VisitorRecord visitor = requireVisitor(state, visitorId);
            TimeContext timeContext = realityContextService.buildTimeContext(visitor, nowIso);
            WeatherContext weatherContext = realityContextService.buildWeatherContext(visitor, nowIso);
            ConversationMessage choiceEntry = new ConversationMessage();
            choiceEntry.id = ids("msg");
            choiceEntry.sessionId = session.id;
            choiceEntry.role = "user";
            choiceEntry.text = "我选择：" + selectedChoice.label;
            choiceEntry.createdAt = nowIso;
            choiceEntry.emotionTag = "choice";
            choiceEntry.confidenceStatus = "choice";
            choiceEntry.tokenUsage = 0;
            choiceEntry.fallbackUsed = false;
            choiceEntry.replySource = "choice";
            state.messages.add(choiceEntry);

            ConversationMessage assistantEntry = new ConversationMessage();
            assistantEntry.id = ids("msg");
            assistantEntry.sessionId = session.id;
            assistantEntry.role = "assistant";
            assistantEntry.text = buildChoiceReply(agent, event, selectedChoice, nextState);
            assistantEntry.sceneText = "";
            assistantEntry.actionText = null;
            assistantEntry.speechText = assistantEntry.text;
            assistantEntry.createdAt = nowIso;
            assistantEntry.emotionTag = "choice_result";
            assistantEntry.confidenceStatus = "system";
            assistantEntry.tokenUsage = 0;
            assistantEntry.fallbackUsed = false;
            assistantEntry.triggeredEventId = event.id;
            assistantEntry.replySource = "choice_result";
            state.messages.add(assistantEntry);

            session.relationshipState = nextState;
            session.emotionState = affectionJudgeService.applyChoiceOutcome(session.emotionState, selectedChoice, nextState, nowIso);
            session.plotState = plotDirectorService.applyChoiceOutcome(session.plotState, event, selectedChoice, timeContext, weatherContext, nowIso);
            session.plotArcState = plotDirectorService.normalizeArc(session.plotArcState, nowIso);
            session.presenceState = presenceHeartbeatService.registerUserTurn(session.presenceState, nowIso);
            session.lastActiveAt = nowIso;
            session.memoryExpireAt = memoryService.createMemoryExpiry(Instant.parse(nowIso));
            markEventTriggered(session, event);
            session.storyEventProgress.currentRouteTheme = nextState.routeTag;
            session.storyEventProgress.nextExpectedDirection = switch (selectedChoice.outcomeType) {
                case "success" -> event.successEffects.nextDirection;
                case "fail" -> event.failEffects.nextDirection;
                default -> event.neutralEffects.nextDirection;
            };
            session.pendingChoiceEventId = null;
            session.pendingChoices = new ArrayList<>();
            session.pendingEventContext = null;

            analyticsService.recordEvent(state, "event_choice", Map.of(
                    "visitorId", visitorId,
                    "sessionId", session.id,
                    "agentId", session.agentId,
                    "triggeredEventId", event.id
            ));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("reply_text", assistantEntry.text);
            response.put("scene_text", assistantEntry.sceneText);
            response.put("action_text", assistantEntry.actionText);
            response.put("speech_text", assistantEntry.speechText);
            response.put("relationship_stage", nextState.relationshipStage);
            response.put("relationship_feedback", nextState.relationshipFeedback);
            response.put("ending_candidate", nextState.endingCandidate);
            response.put("interaction_mode", "chat");
            response.put("choices", List.of());
            response.put("event_context", null);
            response.put("triggered_event", eventMap(event));
            response.put("emotion_state", emotionStateMap(session.emotionState));
            response.put("plot_progress", plotStateMap(session.plotState));
            response.put("plot_arc_state", plotArcStateMap(session.plotArcState));
            response.put("scene_state", sceneStateMap(session.sceneState));
            response.put("scene_frame", session.plotState == null ? "" : session.plotState.sceneFrame);
            response.put("reply_source", "choice_result");
            return response;
        });
    }

    Map<String, Object> continueCheckpoint(Map<String, Object> payload) throws Exception {
        return repository.transact(state -> {
            String visitorId = Json.asString(payload.get("visitorId"));
            String sessionId = Json.asString(payload.get("sessionId"));
            Instant now = Instant.now();
            SessionRecord session = findSession(state, sessionId);
            validateSessionOwner(session, visitorId, now);
            ensureSessionState(session);
            if (session.plotArcState == null || !session.plotArcState.checkpointReady) {
                throw ApiException.badRequest("CHECKPOINT_NOT_READY", "CHECKPOINT_NOT_READY");
            }
            session.plotArcState = plotDirectorService.continueFromCheckpoint(session.plotArcState, IsoTimes.now());
            session.lastActiveAt = IsoTimes.now();
            return Map.of(
                    "continued", true,
                    "plot_arc_state", plotArcStateMap(session.plotArcState),
                    "arc_summary", arcSummaryMap(session.plotArcState.latestArcSummary)
            );
        });
    }

    Map<String, Object> settleCheckpoint(Map<String, Object> payload) throws Exception {
        return repository.transact(state -> {
            String visitorId = Json.asString(payload.get("visitorId"));
            String sessionId = Json.asString(payload.get("sessionId"));
            Instant now = Instant.now();
            SessionRecord session = findSession(state, sessionId);
            validateSessionOwner(session, visitorId, now);
            ensureSessionState(session);
            if (session.plotArcState == null || !session.plotArcState.canSettleScore) {
                throw ApiException.badRequest("SETTLE_NOT_READY", "SETTLE_NOT_READY");
            }
            String nowIso = IsoTimes.now();
            session.plotArcState = plotDirectorService.settleCheckpoint(session.plotArcState, session.relationshipState, session.sceneState, nowIso);
            session.lastActiveAt = nowIso;
            return Map.of(
                    "settled", true,
                    "relationship_stage", session.relationshipState.relationshipStage,
                    "ending_candidate", session.relationshipState.endingCandidate,
                    "relationship_feedback", session.relationshipState.relationshipFeedback,
                    "plot_arc_state", plotArcStateMap(session.plotArcState),
                    "arc_summary", arcSummaryMap(session.plotArcState.latestArcSummary)
            );
        });
    }

    private Map<String, Object> buildSessionPayload(AppState state, String sessionId) {
        SessionRecord session = findSession(state, sessionId);
        AgentProfile agent = requireAgent(session.agentId);
        VisitorRecord visitor = requireVisitor(state, session.visitorId);
        ensureSessionState(session);
        TimeContext timeContext = realityContextService.buildTimeContext(visitor, IsoTimes.now());
        WeatherContext weatherContext = realityContextService.buildWeatherContext(visitor, IsoTimes.now());

        List<Map<String, Object>> history = new ArrayList<>();
        for (ConversationMessage message : getSessionMessages(state, session.id)) {
            history.add(messageMap(message));
        }

        Map<String, Object> agentMap = new LinkedHashMap<>();
        agentMap.put("id", agent.id);
        agentMap.put("name", agent.name);
        agentMap.put("gender", agent.gender);
        agentMap.put("subjectPronoun", agent.subjectPronoun);
        agentMap.put("objectPronoun", agent.objectPronoun);
        agentMap.put("possessivePronoun", agent.possessivePronoun);
        agentMap.put("archetype", agent.archetype);
        agentMap.put("tagline", agent.tagline);
        agentMap.put("palette", agent.palette);
        agentMap.put("bio", agent.bio);
        agentMap.put("likes", agent.likes);
        agentMap.put("portraitAsset", agent.portraitAsset);
        agentMap.put("coverAsset", agent.coverAsset);
        agentMap.put("styleTags", agent.styleTags);
        agentMap.put("moodPalette", agent.moodPalette);
        agentMap.put("backstory", AgentPresentation.backstoryMap(agent.backstory));
        agentMap.put("voiceProfile", AgentPresentation.voiceProfileMap(agent.voiceProfile));

        Map<String, Object> relationshipMap = new LinkedHashMap<>();
        relationshipMap.put("closeness", session.relationshipState.closeness);
        relationshipMap.put("trust", session.relationshipState.trust);
        relationshipMap.put("resonance", session.relationshipState.resonance);
        relationshipMap.put("affectionScore", session.relationshipState.affectionScore);
        relationshipMap.put("relationshipStage", session.relationshipState.relationshipStage);
        relationshipMap.put("ending", session.relationshipState.ending);
        relationshipMap.put("stageProgressHint", session.relationshipState.stageProgressHint);
        relationshipMap.put("stagnationLevel", session.relationshipState.stagnationLevel);
        relationshipMap.put("routeTag", session.relationshipState.routeTag);
        relationshipMap.put("endingCandidate", session.relationshipState.endingCandidate);
        relationshipMap.put("relationshipFeedback", session.relationshipState.relationshipFeedback);

        Map<String, Object> memoryMap = new LinkedHashMap<>();
        memoryMap.put("preferences", session.memorySummary.preferences);
        memoryMap.put("identityNotes", session.memorySummary.identityNotes);
        memoryMap.put("promises", session.memorySummary.promises);
        memoryMap.put("milestones", session.memorySummary.milestones);
        memoryMap.put("emotionalNotes", session.memorySummary.emotionalNotes);
        memoryMap.put("openLoops", session.memorySummary.openLoops);
        memoryMap.put("sharedMoments", session.memorySummary.sharedMoments);
        memoryMap.put("discussedTopics", session.memorySummary.discussedTopics);
        memoryMap.put("strongMemories", session.memorySummary.strongMemories);
        memoryMap.put("weakMemories", session.memorySummary.weakMemories);
        memoryMap.put("temporaryMemories", session.memorySummary.temporaryMemories);
        memoryMap.put("memoryMentionCounts", session.memorySummary.memoryMentionCounts);
        memoryMap.put("memoryTouchedAt", session.memorySummary.memoryTouchedAt);
        memoryMap.put("callbackCandidates", session.memorySummary.callbackCandidates);
        memoryMap.put("assistantOwnedThreads", session.memorySummary.assistantOwnedThreads);
        memoryMap.put("factMemories", factMemoryList(session.memorySummary.factMemories));
        memoryMap.put("sceneLedger", sceneLedgerList(session.memorySummary.sceneLedger));
        memoryMap.put("openLoopItems", openLoopList(session.memorySummary.openLoopItems));
        memoryMap.put("lastUserMood", session.memorySummary.lastUserMood);
        memoryMap.put("lastUserIntent", session.memorySummary.lastUserIntent);
        memoryMap.put("lastResponseCadence", session.memorySummary.lastResponseCadence);
        memoryMap.put("lastMemoryUseMode", session.memorySummary.lastMemoryUseMode);
        memoryMap.put("lastMemoryRelevanceReason", session.memorySummary.lastMemoryRelevanceReason);
        memoryMap.put("updatedAt", session.memorySummary.updatedAt);

        Map<String, Object> eventProgressMap = new LinkedHashMap<>();
        eventProgressMap.put("triggeredEventIds", session.storyEventProgress.triggeredEventIds);
        eventProgressMap.put("lastTriggeredEventId", session.storyEventProgress.lastTriggeredEventId);
        eventProgressMap.put("eventCooldownUntilTurn", session.storyEventProgress.eventCooldownUntilTurn);
        eventProgressMap.put("lastTriggeredTitle", session.storyEventProgress.lastTriggeredTitle);
        eventProgressMap.put("lastTriggeredTheme", session.storyEventProgress.lastTriggeredTheme);
        eventProgressMap.put("currentRouteTheme", session.storyEventProgress.currentRouteTheme);
        eventProgressMap.put("nextExpectedDirection", session.storyEventProgress.nextExpectedDirection);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", session.id);
        payload.put("visitorId", session.visitorId);
        payload.put("agent", agentMap);
        payload.put("relationshipState", relationshipMap);
        payload.put("memorySummary", memoryMap);
        payload.put("storyEventProgress", eventProgressMap);
        payload.put("emotionState", emotionStateMap(session.emotionState));
        payload.put("tensionState", tensionStateMap(session.tensionState));
        payload.put("plotState", plotStateMap(session.plotState));
        payload.put("plotArcState", plotArcStateMap(session.plotArcState));
        payload.put("sceneState", sceneStateMap(session.sceneState));
        payload.put("presenceState", presenceStateMap(session.presenceState));
        payload.put("lastIntentState", intentStateMap(session.lastIntentState));
        payload.put("lastResponsePlan", responsePlanMap(session.lastResponsePlan));
        payload.put("lastHumanizationAudit", humanizationAuditMap(session.lastHumanizationAudit));
        payload.put("lastRealityAudit", realityAuditMap(session.lastRealityAudit));
        payload.put("lastPlotGateDecision", plotGateDecisionMap(session.lastPlotGateDecision));
        payload.put("lastTurnContext", turnContextMap(session.lastTurnContext));
        payload.put("dialogueContinuityState", dialogueContinuityMap(session.dialogueContinuityState));
        payload.put("lastQuickJudgeStatus", quickJudgeStatusMap(session.lastQuickJudgeStatus));
        payload.put("pendingQuickJudgeCorrection", quickJudgeDecisionMap(session.pendingQuickJudgeCorrection));
        payload.put("pendingQuickJudgeCorrectionAt", blankTo(session.pendingQuickJudgeCorrectionAt, ""));
        payload.put("pendingRepairCue", pendingRepairCueMap(session.pendingRepairCue));
        payload.put("pendingRelationshipCalibration", relationshipCalibrationMap(session.pendingRelationshipCalibration));
        payload.put("pendingRelationshipCalibrationAt", blankTo(session.pendingRelationshipCalibrationAt, ""));
        payload.put("visitorContext", Map.of(
                "timezone", visitor.timezone == null ? "" : visitor.timezone,
                "preferredCity", visitor.preferredCity == null ? "" : visitor.preferredCity
        ));
        payload.put("timeContext", timeContextMap(timeContext));
        payload.put("weatherContext", weatherContextMap(weatherContext));
        payload.put("memoryExpireAt", session.memoryExpireAt);
        payload.put("userTurnCount", session.userTurnCount);
        payload.put("history", history);
        payload.put("pendingChoiceEventId", session.pendingChoiceEventId);
        payload.put("pendingChoices", serializeChoices(session.pendingChoices));
        payload.put("pendingEventContext", session.pendingEventContext);
        payload.put("lastProactiveMessageAt", session.lastProactiveMessageAt);
        return payload;
    }

    private Map<String, Object> debugSummary(Map<String, Object> sessionPayload, List<ConversationMessage> messages) {
        Map<String, Object> relationship = objectValue(sessionPayload.get("relationshipState"));
        Map<String, Object> scene = objectValue(sessionPayload.get("sceneState"));
        Map<String, Object> quickJudge = objectValue(sessionPayload.get("lastQuickJudgeStatus"));
        Map<String, Object> turnContext = objectValue(sessionPayload.get("lastTurnContext"));
        Map<String, Object> plotArc = objectValue(sessionPayload.get("plotArcState"));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("messageCount", messages == null ? 0 : messages.size());
        summary.put("userTurnCount", Json.asInt(sessionPayload.get("userTurnCount"), 0));
        summary.put("relationshipStage", blankTo(Json.asString(relationship.get("relationshipStage")), ""));
        summary.put("affectionScore", Json.asInt(relationship.get("affectionScore"), 0));
        summary.put("sceneLocation", blankTo(Json.asString(scene.get("location")), ""));
        summary.put("interactionMode", blankTo(Json.asString(scene.get("interactionMode")), ""));
        summary.put("quickJudgeStatus", blankTo(Json.asString(quickJudge.get("status")), ""));
        summary.put("quickJudgeReason", blankTo(Json.asString(quickJudge.get("reason")), ""));
        summary.put("plotAction", blankTo(Json.asString(turnContext.get("plotDirectorAction")), ""));
        summary.put("plotSignal", Json.asInt(turnContext.get("plotSignal"), 0));
        summary.put("plotPressure", Json.asInt(turnContext.get("plotPressure"), 0));
        summary.put("runStatus", blankTo(Json.asString(plotArc.get("runStatus")), ""));
        return summary;
    }

    private List<Map<String, Object>> debugTurnTimeline(List<ConversationMessage> messages) {
        List<Map<String, Object>> turns = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return turns;
        }

        Map<String, Object> currentTurn = null;
        List<Map<String, Object>> assistantReplies = new ArrayList<>();
        int turnIndex = 0;
        for (ConversationMessage message : messages) {
            if ("user".equals(message.role)) {
                if (currentTurn != null) {
                    currentTurn.put("assistantReplies", assistantReplies);
                    turns.add(currentTurn);
                }
                turnIndex++;
                currentTurn = new LinkedHashMap<>();
                assistantReplies = new ArrayList<>();
                currentTurn.put("turnIndex", turnIndex);
                currentTurn.put("userMessage", messageMap(message));
                continue;
            }
            if (currentTurn == null) {
                currentTurn = new LinkedHashMap<>();
                currentTurn.put("turnIndex", 0);
                currentTurn.put("userMessage", Map.of());
                assistantReplies = new ArrayList<>();
            }
            assistantReplies.add(messageMap(message));
        }
        if (currentTurn != null) {
            currentTurn.put("assistantReplies", assistantReplies);
            turns.add(currentTurn);
        }
        return turns;
    }

    private Map<String, Object> latestSignalSnapshot(Map<String, Object> sessionPayload) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("intent", sessionPayload.getOrDefault("lastIntentState", Map.of()));
        snapshot.put("responsePlan", sessionPayload.getOrDefault("lastResponsePlan", Map.of()));
        snapshot.put("turnContext", sessionPayload.getOrDefault("lastTurnContext", Map.of()));
        snapshot.put("dialogueContinuity", sessionPayload.getOrDefault("dialogueContinuityState", Map.of()));
        snapshot.put("quickJudge", sessionPayload.getOrDefault("lastQuickJudgeStatus", Map.of()));
        snapshot.put("relationship", sessionPayload.getOrDefault("relationshipState", Map.of()));
        snapshot.put("pendingRelationshipCalibration", sessionPayload.getOrDefault("pendingRelationshipCalibration", Map.of()));
        snapshot.put("plotState", sessionPayload.getOrDefault("plotState", Map.of()));
        snapshot.put("plotArcState", sessionPayload.getOrDefault("plotArcState", Map.of()));
        snapshot.put("plotGate", sessionPayload.getOrDefault("lastPlotGateDecision", Map.of()));
        snapshot.put("scene", sessionPayload.getOrDefault("sceneState", Map.of()));
        snapshot.put("humanizationAudit", sessionPayload.getOrDefault("lastHumanizationAudit", Map.of()));
        snapshot.put("realityAudit", sessionPayload.getOrDefault("lastRealityAudit", Map.of()));
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private SessionRecord findSession(AppState state, String sessionId) {
        return state.sessions.stream()
                .filter(item -> sessionId.equals(item.id))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "SESSION_NOT_FOUND"));
    }

    private VisitorRecord requireVisitor(AppState state, String visitorId) {
        return state.visitors.stream()
                .filter(item -> visitorId.equals(item.id))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("VISITOR_NOT_FOUND", "VISITOR_NOT_FOUND"));
    }

    private void validateSessionOwner(SessionRecord session, String visitorId, Instant now) {
        if (!visitorId.equals(session.visitorId)) {
            throw ApiException.badRequest("VISITOR_SESSION_MISMATCH", "VISITOR_SESSION_MISMATCH");
        }
        if (memoryService.isExpired(session, now)) {
            throw ApiException.gone("SESSION_EXPIRED", "SESSION_EXPIRED");
        }
    }

    private AgentProfile requireAgent(String agentId) {
        AgentProfile agent = agentConfigService.getAgentById(agentId);
        if (agent == null) {
            throw ApiException.notFound("AGENT_NOT_FOUND", "AGENT_NOT_FOUND");
        }
        return agent;
    }

    private List<ConversationMessage> getSessionMessages(AppState state, String sessionId) {
        return state.messages.stream()
                .filter(item -> sessionId.equals(item.sessionId))
                .sorted(Comparator.comparing(item -> Instant.parse(item.createdAt)))
                .toList();
    }

    private void ensureSessionState(SessionRecord session) {
        session.memorySummary = memoryService.normalizeSummary(session.memorySummary, session.createdAt);
        if (session.storyEventProgress == null) {
            session.storyEventProgress = new StoryEventProgress();
        }
        if (session.storyEventProgress.eventCooldownUntilTurn == null) {
            session.storyEventProgress.eventCooldownUntilTurn = new LinkedHashMap<>();
        }
        if (session.pendingChoices == null) {
            session.pendingChoices = new ArrayList<>();
        }
        session.emotionState = affectionJudgeService.normalizeEmotion(session.emotionState, session.createdAt);
        session.plotState = plotDirectorService.normalizePlot(session.plotState, session.createdAt);
        session.plotArcState = plotDirectorService.normalizeArc(session.plotArcState, session.createdAt);
        session.sceneState = sceneDirectorService.normalize(session.sceneState, session.createdAt);
        session.presenceState = presenceHeartbeatService.normalizePresence(session.presenceState, session.createdAt);
        session.tensionState = boundaryResponseService.normalize(session.tensionState, session.createdAt);
        session.dialogueContinuityState = dialogueContinuityService.normalize(session.dialogueContinuityState, session.createdAt);
    }

    private QuickJudgeDecision consumePendingQuickJudgeCorrection(SessionRecord session) {
        if (session == null || session.pendingQuickJudgeCorrection == null) {
            return QuickJudgeDecision.none("no_pending_correction");
        }
        QuickJudgeDecision decision = session.pendingQuickJudgeCorrection;
        session.pendingQuickJudgeCorrection = null;
        session.pendingQuickJudgeCorrectionAt = "";
        return decision;
    }

    private PendingRepairCue consumePendingRepairCue(SessionRecord session) {
        if (session == null || session.pendingRepairCue == null) {
            return null;
        }
        PendingRepairCue cue = session.pendingRepairCue;
        session.pendingRepairCue = null;
        return cue;
    }

    private RelationshipScoreCalibration consumePendingRelationshipCalibration(SessionRecord session) {
        if (session == null || session.pendingRelationshipCalibration == null) {
            return RelationshipScoreCalibration.none("no_pending_relationship_calibration");
        }
        RelationshipScoreCalibration calibration = session.pendingRelationshipCalibration;
        session.pendingRelationshipCalibration = null;
        session.pendingRelationshipCalibrationAt = "";
        return calibration;
    }

    private TurnEvaluation applyRelationshipCalibration(
            TurnEvaluation local,
            RelationshipState previousState,
            RelationshipScoreCalibration calibration,
            String nowIso
    ) {
        if (local == null || calibration == null || !calibration.shouldApply()) {
            return local;
        }
        int closeAdjust = clampValue(calibration.closenessDelta, -2, 2);
        int trustAdjust = clampValue(calibration.trustDelta, -2, 2);
        int resonanceAdjust = clampValue(calibration.resonanceDelta, -2, 2);
        int totalAdjust = closeAdjust + trustAdjust + resonanceAdjust;
        while (Math.abs(totalAdjust) > 2) {
            int direction = Integer.signum(totalAdjust);
            if (resonanceAdjust != 0) {
                resonanceAdjust -= direction;
            } else if (closeAdjust != 0) {
                closeAdjust -= direction;
            } else if (trustAdjust != 0) {
                trustAdjust -= direction;
            }
            totalAdjust = closeAdjust + trustAdjust + resonanceAdjust;
        }
        if (totalAdjust == 0) {
            return local;
        }
        Delta base = local.affectionDelta == null ? new Delta() : local.affectionDelta;
        Delta delta = new Delta();
        delta.closeness = base.closeness + closeAdjust;
        delta.trust = base.trust + trustAdjust;
        delta.resonance = base.resonance + resonanceAdjust;
        delta.total = delta.closeness + delta.trust + delta.resonance;

        RelationshipState previous = previousState == null ? relationshipService.createInitialState() : previousState;
        RelationshipState next = copyRelationshipState(local.nextState);
        next.closeness = Math.max(0, previous.closeness + delta.closeness);
        next.trust = Math.max(0, previous.trust + delta.trust);
        next.resonance = Math.max(0, previous.resonance + delta.resonance);
        next.affectionScore = next.closeness + next.trust + next.resonance;
        next.relationshipStage = relationshipStageForState(next, previous.relationshipStage);
        next.relationshipFeedback = blankTo(local.relationshipFeedback, next.relationshipFeedback);

        List<String> reasons = new ArrayList<>(local.scoreReasons == null ? List.of() : local.scoreReasons);
        reasons.add("llm_calibration " + signedDelta(totalAdjust)
                + "\uff1a\u5f02\u6b65\u590d\u76d8\u5bf9\u4e0a\u4e00\u6b21\u672c\u5730\u8bc4\u5206\u505a\u5c0f\u5e45\u6821\u51c6"
                + "\uff08confidence=" + calibration.confidence
                + ", reason=" + blankTo(calibration.reason, "remote_calibration")
                + ", sourceTurn=" + calibration.sourceTurn + "\uff09");

        List<String> behaviorTags = new ArrayList<>(local.behaviorTags == null ? List.of() : local.behaviorTags);
        behaviorTags.add("llm_score_calibrated");
        return new TurnEvaluation(
                next,
                delta,
                behaviorTags,
                local.riskFlags,
                !blankTo(next.relationshipStage, "").equals(blankTo(previous.relationshipStage, "")),
                local.stageProgress,
                local.relationshipFeedback,
                reasons
        );
    }

    private int clampValue(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void registerRelationshipCalibration(String sessionId, RelationshipCalibrationTask task) {
        if (sessionId == null || sessionId.isBlank() || task == null || task.future == null) {
            return;
        }
        task.future.thenAccept(calibration -> {
            if (calibration == null || !calibration.shouldApply()) {
                return;
            }
            try {
                storeRelationshipCalibration(sessionId, calibration);
            } catch (Exception ignored) {
                // Async score calibration is advisory and must never fail chat.
            }
        });
    }

    private void storeRelationshipCalibration(String sessionId, RelationshipScoreCalibration calibration) throws Exception {
        if (calibration == null || !calibration.shouldApply()) {
            return;
        }
        repository.transact(state -> {
            for (SessionRecord item : state.sessions) {
                if (sessionId.equals(item.id)) {
                    ensureSessionState(item);
                    item.pendingRelationshipCalibration = calibration;
                    item.pendingRelationshipCalibrationAt = IsoTimes.now();
                    calibration.createdAt = item.pendingRelationshipCalibrationAt;
                    break;
                }
            }
            return null;
        });
    }

    private void registerLateQuickJudgeCorrection(String sessionId, QuickJudgeTask task) {
        if (sessionId == null || sessionId.isBlank() || task == null || task.future == null) {
            return;
        }
        if (task.future.isDone()) {
            try {
                storeLateQuickJudgeCorrection(sessionId, task.future.getNow(QuickJudgeDecision.none("late_missing")), task);
            } catch (Exception ignored) {
                // Late quick-judge corrections are opportunistic and must never fail chat.
            }
            return;
        }
        task.future.thenAccept(decision -> {
            if (decision == null || !decision.shouldApply()) {
                return;
            }
            try {
                storeLateQuickJudgeCorrection(sessionId, decision, task);
            } catch (Exception ignored) {
                // Late quick-judge corrections are opportunistic and must never fail chat.
            }
        });
    }

    private boolean shouldStoreLateQuickJudge(QuickJudgeDecision decision) {
        String reason = decision == null ? "" : blankTo(decision.reason, "");
        return reason.startsWith("timeout") || reason.startsWith("background_deferred");
    }

    private void storeLateQuickJudgeCorrection(String sessionId, QuickJudgeDecision decision, QuickJudgeTask task) throws Exception {
        if (decision == null || !decision.shouldApply()) {
            return;
        }
        repository.transact(state -> {
            for (SessionRecord item : state.sessions) {
                if (sessionId.equals(item.id)) {
                    ensureSessionState(item);
                    item.pendingQuickJudgeCorrection = decision;
                    item.pendingQuickJudgeCorrectionAt = IsoTimes.now();
                    item.pendingRepairCue = buildPendingRepairCue(decision, item.pendingQuickJudgeCorrectionAt);
                    item.lastQuickJudgeStatus = quickJudgeStatusFrom(decision, item.pendingQuickJudgeCorrectionAt);
                    applyQuickJudgeTriggerInfo(item.lastQuickJudgeStatus, task);
                    break;
                }
            }
            return null;
        });
    }

    private PendingRepairCue buildPendingRepairCue(QuickJudgeDecision decision, String nowIso) {
        if (decision == null || !decision.shouldApply() || decision.confidence < 75) {
            return null;
        }
        String type = repairCueType(decision);
        if (type.isBlank()) {
            return null;
        }
        PendingRepairCue cue = new PendingRepairCue();
        cue.type = type;
        cue.instruction = repairCueInstruction(type, decision);
        cue.confidence = decision.confidence;
        cue.createdAt = blankTo(nowIso, "");
        return cue;
    }

    private String repairCueType(QuickJudgeDecision decision) {
        String priority = blankTo(decision.replyPriority, "");
        String primary = blankTo(decision.primaryIntent, "");
        String nextMove = blankTo(decision.nextBestMove, "").toLowerCase();
        if ("repair_then_answer".equals(priority) || "meta_repair".equals(primary)) {
            return "intent_repair";
        }
        if (!decision.sceneTransitionNeeded
                && (nextMove.contains("transition") || nextMove.contains("scene") || nextMove.contains("do not repeat")
                || nextMove.contains("\u4e0d\u8981\u91cd\u590d") || nextMove.contains("\u8f6c\u573a"))) {
            return "scene_repair";
        }
        if (!blankTo(decision.sharedObjective, "").isBlank()
                && ("answer_then_scene".equals(priority) || "hold_scene_then_answer".equals(priority))) {
            return "objective_repair";
        }
        return "";
    }

    private String repairCueInstruction(String type, QuickJudgeDecision decision) {
        String nextMove = blankTo(decision.nextBestMove, "");
        String objective = blankTo(decision.sharedObjective, "");
        if ("scene_repair".equals(type)) {
            return "\u4e0a\u4e00\u8f6e\u53ef\u80fd\u628a\u91cd\u70b9\u5e26\u56de\u4e86\u573a\u666f\u6216\u91cd\u590d\u8f6c\u573a\uff1b\u672c\u8f6e\u5f00\u5934\u7528\u4e00\u53e5\u8f7b\u7684\u8bdd\u628a\u91cd\u70b9\u62c9\u56de\u7528\u6237\u771f\u6b63\u8981\u7684\u5185\u5bb9\u3002" + nextMove;
        }
        if ("objective_repair".equals(type)) {
            return "\u4e0a\u4e00\u8f6e\u53ef\u80fd\u6ca1\u6709\u63a5\u51c6\u5171\u540c\u76ee\u6807\uff1b\u672c\u8f6e\u8f7b\u8f7b\u627f\u8ba4\u521a\u624d\u63a5\u504f\u4e86\uff0c\u7136\u540e\u7acb\u523b\u56de\u5230\uff1a" + objective;
        }
        return "\u4e0a\u4e00\u8f6e\u53ef\u80fd\u8f7b\u5fae\u7406\u89e3\u504f\u4e86\uff1b\u672c\u8f6e\u5f00\u5934\u7528\u4e00\u53e5\u81ea\u7136\u3001\u4f4e\u8d1f\u62c5\u7684\u8bdd\u4fee\u6b63\uff0c\u7136\u540e\u7acb\u523b\u56de\u5230\u7528\u6237\u5f53\u524d\u610f\u56fe\u3002" + nextMove;
    }

    private void markEventTriggered(SessionRecord session, StoryEvent event) {
        if (!session.storyEventProgress.triggeredEventIds.contains(event.id)) {
            session.storyEventProgress.triggeredEventIds.add(event.id);
        }
        session.storyEventProgress.lastTriggeredEventId = event.id;
        session.storyEventProgress.lastTriggeredTitle = event.title;
        session.storyEventProgress.lastTriggeredTheme = event.theme;
        session.storyEventProgress.eventCooldownUntilTurn.put(event.id, session.userTurnCount + event.cooldown);
    }

    private Map<String, Object> eventMap(StoryEvent event) {
        return Map.of(
                "id", event.id,
                "title", event.title,
                "theme", event.theme,
                "category", event.category,
                "next_direction", event.nextDirection
        );
    }

    private List<Map<String, Object>> serializeChoices(List<ChoiceOption> choices) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        if (choices == null) {
            return serialized;
        }
        for (ChoiceOption choice : choices) {
            serialized.add(Map.of(
                    "id", choice.id,
                    "label", choice.label,
                    "toneHint", choice.toneHint,
                    "outcomeType", choice.outcomeType
            ));
        }
        return serialized;
    }

    private Map<String, Object> messageMap(ConversationMessage message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", message.id);
        map.put("sessionId", message.sessionId);
        map.put("role", message.role);
        map.put("text", message.text);
        map.put("sceneText", message.sceneText);
        map.put("actionText", message.actionText);
        map.put("speechText", message.speechText == null || message.speechText.isBlank() ? message.text : message.speechText);
        map.put("createdAt", message.createdAt);
        map.put("emotionTag", message.emotionTag);
        map.put("confidenceStatus", message.confidenceStatus);
        map.put("tokenUsage", message.tokenUsage);
        map.put("fallbackUsed", message.fallbackUsed);
        map.put("triggeredEventId", message.triggeredEventId);
        map.put("affectionDelta", message.affectionDelta);
        map.put("replySource", message.replySource);
        return map;
    }

    private Map<String, Object> intentStateMap(IntentState intentState) {
        if (intentState == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("primaryIntent", blankTo(intentState.primaryIntent, ""));
        map.put("secondaryIntent", blankTo(intentState.secondaryIntent, ""));
        map.put("emotion", blankTo(intentState.emotion, ""));
        map.put("clarity", blankTo(intentState.clarity, ""));
        map.put("needsEmpathy", intentState.needsEmpathy);
        map.put("needsStructure", intentState.needsStructure);
        map.put("needsFollowup", intentState.needsFollowup);
        map.put("isBoundarySensitive", intentState.isBoundarySensitive);
        map.put("rationale", blankTo(intentState.rationale, ""));
        map.put("updatedAt", blankTo(intentState.updatedAt, ""));
        return map;
    }

    private TurnContext buildTurnContext(
            IntentState intentState,
            TurnEvaluation relationship,
            SceneState sceneState,
            String replySource,
            String nowIso
    ) {
        TurnContext context = new TurnContext();
        context.primaryIntent = intentState == null ? "" : blankTo(intentState.primaryIntent, "");
        context.secondaryIntent = intentState == null ? "" : blankTo(intentState.secondaryIntent, "");
        context.clarity = intentState == null ? "" : blankTo(intentState.clarity, "");
        context.userEmotion = intentState == null ? "" : blankTo(intentState.emotion, "");
        context.replySource = blankTo(replySource, "user_turn");
        Delta delta = relationship == null || relationship.affectionDelta == null ? new Delta() : relationship.affectionDelta;
        context.affectionDeltaTotal = delta.total;
        context.closenessDelta = delta.closeness;
        context.trustDelta = delta.trust;
        context.resonanceDelta = delta.resonance;
        context.scoreReasons = relationship == null || relationship.scoreReasons == null ? new ArrayList<>() : new ArrayList<>(relationship.scoreReasons);
        context.behaviorTags = relationship == null || relationship.behaviorTags == null ? new ArrayList<>() : new ArrayList<>(relationship.behaviorTags);
        context.riskFlags = relationship == null || relationship.riskFlags == null ? new ArrayList<>() : new ArrayList<>(relationship.riskFlags);
        context.sceneLocation = sceneState == null ? "" : blankTo(sceneState.location, "");
        context.interactionMode = sceneState == null ? "" : blankTo(sceneState.interactionMode, "");
        context.updatedAt = nowIso;
        return context;
    }

    private void applyContinuityToTurnContext(TurnContext context, DialogueContinuityState continuity) {
        if (context == null || continuity == null) {
            return;
        }
        context.continuityObjective = blankTo(continuity.currentObjective, "");
        context.continuityAcceptedPlan = blankTo(continuity.acceptedPlan, "");
        context.continuityNextBestMove = blankTo(continuity.nextBestMove, "");
        context.sceneTransitionNeeded = continuity.sceneTransitionNeeded;
        context.continuityGuards = continuity.mustNotContradict == null ? new ArrayList<>() : new ArrayList<>(continuity.mustNotContradict);
    }

    private void applyUnderstandingToTurnContext(TurnContext context, TurnUnderstandingState understanding) {
        if (context == null || understanding == null) {
            return;
        }
        context.userReplyAct = blankTo(understanding.primaryAct, "");
        context.userReplyActConfidence = understanding.confidence;
        context.assistantObligation = understanding.assistantObligation;
        context.recommendedQuickJudgeTier = blankTo(understanding.recommendedQuickJudgeTier, "");
        context.shouldAskQuickJudge = understanding.shouldAskQuickJudge;
        context.userReplyActCandidates = understanding.candidates == null ? new ArrayList<>() : new ArrayList<>(understanding.candidates);
        context.localConflicts = understanding.localConflicts == null ? new ArrayList<>() : new ArrayList<>(understanding.localConflicts);
        context.sceneMoveKind = blankTo(understanding.sceneMoveKind, "");
        context.sceneMoveTarget = blankTo(understanding.sceneMoveTarget, "");
        context.sceneMoveReason = blankTo(understanding.sceneMoveReason, "");
        context.sceneMoveConfidence = understanding.sceneMoveConfidence;
    }

    private void copyPlotSignals(TurnContext target, TurnContext source) {
        if (target == null || source == null) {
            return;
        }
        target.plotGap = source.plotGap;
        target.plotSignal = source.plotSignal;
        target.plotPressure = source.plotPressure;
        target.plotSceneSignal = source.plotSceneSignal;
        target.plotRelationshipSignal = source.plotRelationshipSignal;
        target.plotEventSignal = source.plotEventSignal;
        target.plotContinuitySignal = source.plotContinuitySignal;
        target.plotRiskSignal = source.plotRiskSignal;
        target.plotDirectorAction = blankTo(source.plotDirectorAction, "");
        target.plotWhyNow = blankTo(source.plotWhyNow, "");
        target.plotDirectorConfidence = source.plotDirectorConfidence;
        target.plotRiskIfAdvance = blankTo(source.plotRiskIfAdvance, "");
        target.requiredUserSignal = blankTo(source.requiredUserSignal, "");
    }

    private TurnEvaluation applyPlotMacroScore(
            TurnEvaluation relationship,
            PlotDecision plotDecision,
            TurnContext turnContext,
            RelationalTensionState tensionState,
            String nowIso
    ) {
        if (relationship == null || plotDecision == null || !plotDecision.advanced) {
            return relationship;
        }
        if (tensionState != null && tensionState.guarded) {
            return relationship;
        }
        if (turnContext != null && turnContext.riskFlags != null && !turnContext.riskFlags.isEmpty()) {
            return relationship;
        }

        String action = turnContext == null ? "" : blankTo(turnContext.plotDirectorAction, "");
        boolean heartbeatNudge = "heartbeat_nudge".equals(action);
        int closenessBonus = heartbeatNudge ? 1 : 2;
        int trustBonus = heartbeatNudge ? 0 : 1;
        int resonanceBonus = heartbeatNudge ? 1 : 2;

        String primaryIntent = turnContext == null ? "" : blankTo(turnContext.primaryIntent, "");
        if ("romantic_probe".equals(primaryIntent)) {
            trustBonus += 1;
        }
        if ("scene_push".equals(primaryIntent)) {
            resonanceBonus += 1;
        }

        RelationshipState nextState = copyRelationshipState(relationship.nextState);
        String previousStage = blankTo(nextState.relationshipStage, "");
        nextState.closeness = Math.max(0, nextState.closeness + closenessBonus);
        nextState.trust = Math.max(0, nextState.trust + trustBonus);
        nextState.resonance = Math.max(0, nextState.resonance + resonanceBonus);
        nextState.affectionScore = nextState.closeness + nextState.trust + nextState.resonance;
        nextState.relationshipStage = relationshipStageForState(nextState, previousStage);
        nextState.stageProgressHint = heartbeatNudge
                ? "\u957f\u804a\u6c14\u6c1b\u88ab\u8f7b\u8f7b\u63a8\u52a8\u4e86\u4e00\u70b9\u3002"
                : "\u8fd9\u4e00\u62cd\u5267\u60c5\u6709\u771f\u6b63\u63a8\u8fdb\uff0c\u5173\u7cfb\u6bd4\u666e\u901a\u804a\u5929\u66f4\u660e\u663e\u5730\u5f80\u524d\u8d70\u4e86\u4e00\u6b65\u3002";
        nextState.relationshipFeedback = nextState.stageProgressHint;
        nextState.routeTag = blankTo(nextState.routeTag, heartbeatNudge ? "\u957f\u804a\u56de\u6696" : "\u5267\u60c5\u63a8\u8fdb");

        Delta delta = new Delta();
        Delta base = relationship.affectionDelta == null ? new Delta() : relationship.affectionDelta;
        delta.closeness = base.closeness + closenessBonus;
        delta.trust = base.trust + trustBonus;
        delta.resonance = base.resonance + resonanceBonus;
        delta.total = delta.closeness + delta.trust + delta.resonance;

        List<String> behaviorTags = new ArrayList<>(relationship.behaviorTags == null ? List.of() : relationship.behaviorTags);
        behaviorTags.add(heartbeatNudge ? "heartbeat_macro_score" : "plot_macro_score");
        List<String> scoreReasons = new ArrayList<>(relationship.scoreReasons == null ? List.of() : relationship.scoreReasons);
        scoreReasons.add((heartbeatNudge ? "plot_macro " : "plot_macro ")
                + signedDelta(closenessBonus + trustBonus + resonanceBonus)
                + "："
                + (heartbeatNudge ? "长聊心跳轻推" : "剧情导演确认本轮自然推进")
                + "，closeness " + signedDelta(closenessBonus)
                + "，trust " + signedDelta(trustBonus)
                + "，resonance " + signedDelta(resonanceBonus));
        boolean stageChanged = relationship.stageChanged || !Objects.equals(previousStage, nextState.relationshipStage);
        String stageProgress = plotDecision.nextPlotState == null ? relationship.stageProgress : plotDecision.nextPlotState.plotProgress;

        if (turnContext != null) {
            turnContext.closenessDelta = delta.closeness;
            turnContext.trustDelta = delta.trust;
            turnContext.resonanceDelta = delta.resonance;
            turnContext.affectionDeltaTotal = delta.total;
            turnContext.behaviorTags = behaviorTags;
            turnContext.scoreReasons = scoreReasons;
            turnContext.updatedAt = nowIso;
        }

        return new TurnEvaluation(
                nextState,
                delta,
                behaviorTags,
                relationship.riskFlags == null ? List.of() : relationship.riskFlags,
                stageChanged,
                blankTo(stageProgress, ""),
                nextState.relationshipFeedback,
                scoreReasons
        );
    }

    private String signedDelta(int value) {
        return value >= 0 ? "+" + value : String.valueOf(value);
    }

    private RelationshipState copyRelationshipState(RelationshipState source) {
        RelationshipState next = new RelationshipState();
        if (source == null) {
            return next;
        }
        next.closeness = source.closeness;
        next.trust = source.trust;
        next.resonance = source.resonance;
        next.affectionScore = source.affectionScore;
        next.relationshipStage = source.relationshipStage;
        next.ending = source.ending;
        next.stageProgressHint = source.stageProgressHint;
        next.stagnationLevel = source.stagnationLevel;
        next.routeTag = source.routeTag;
        next.endingCandidate = source.endingCandidate;
        next.relationshipFeedback = source.relationshipFeedback;
        return next;
    }

    private String relationshipStageForState(RelationshipState state, String previousStage) {
        String target = baseRelationshipStage(state);
        List<String> order = List.of("\u521d\u8bc6", "\u5347\u6e29", "\u5fc3\u52a8", "\u9760\u8fd1", "\u786e\u8ba4\u5173\u7cfb");
        int previousIndex = Math.max(0, order.indexOf(blankTo(previousStage, "\u521d\u8bc6")));
        int targetIndex = Math.max(0, order.indexOf(target));
        if (targetIndex > previousIndex + 1) {
            return order.get(previousIndex + 1);
        }
        return target;
    }

    private String baseRelationshipStage(RelationshipState state) {
        if (state == null) {
            return "\u521d\u8bc6";
        }
        if (state.closeness >= 24 && state.trust >= 24 && state.resonance >= 22 && state.affectionScore >= 78) {
            return "\u786e\u8ba4\u5173\u7cfb";
        }
        if (state.closeness >= 18 && state.trust >= 16 && state.resonance >= 16 && state.affectionScore >= 55) {
            return "\u9760\u8fd1";
        }
        if (state.closeness >= 10 && state.trust >= 9 && state.resonance >= 9 && state.affectionScore >= 32) {
            return "\u5fc3\u52a8";
        }
        if (state.closeness >= 5 && state.trust >= 3 && state.resonance >= 3 && state.affectionScore >= 12) {
            return "\u5347\u6e29";
        }
        return "\u521d\u8bc6";
    }

    private Map<String, Object> turnContextMap(TurnContext context) {
        if (context == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("primaryIntent", blankTo(context.primaryIntent, ""));
        map.put("secondaryIntent", blankTo(context.secondaryIntent, ""));
        map.put("clarity", blankTo(context.clarity, ""));
        map.put("userEmotion", blankTo(context.userEmotion, ""));
        map.put("replySource", blankTo(context.replySource, ""));
        map.put("affectionDeltaTotal", context.affectionDeltaTotal);
        map.put("closenessDelta", context.closenessDelta);
        map.put("trustDelta", context.trustDelta);
        map.put("resonanceDelta", context.resonanceDelta);
        map.put("scoreReasons", context.scoreReasons == null ? List.of() : context.scoreReasons);
        map.put("behaviorTags", context.behaviorTags == null ? List.of() : context.behaviorTags);
        map.put("riskFlags", context.riskFlags == null ? List.of() : context.riskFlags);
        map.put("sceneLocation", blankTo(context.sceneLocation, ""));
        map.put("interactionMode", blankTo(context.interactionMode, ""));
        map.put("plotGap", context.plotGap);
        map.put("plotSignal", context.plotSignal);
        map.put("plotPressure", context.plotPressure);
        map.put("plotSceneSignal", context.plotSceneSignal);
        map.put("plotRelationshipSignal", context.plotRelationshipSignal);
        map.put("plotEventSignal", context.plotEventSignal);
        map.put("plotContinuitySignal", context.plotContinuitySignal);
        map.put("plotRiskSignal", context.plotRiskSignal);
        map.put("plotDirectorAction", blankTo(context.plotDirectorAction, ""));
        map.put("plotWhyNow", blankTo(context.plotWhyNow, ""));
        map.put("plotDirectorConfidence", context.plotDirectorConfidence);
        map.put("plotRiskIfAdvance", blankTo(context.plotRiskIfAdvance, ""));
        map.put("requiredUserSignal", blankTo(context.requiredUserSignal, ""));
        map.put("sceneMoveKind", blankTo(context.sceneMoveKind, ""));
        map.put("sceneMoveTarget", blankTo(context.sceneMoveTarget, ""));
        map.put("sceneMoveReason", blankTo(context.sceneMoveReason, ""));
        map.put("sceneMoveConfidence", context.sceneMoveConfidence);
        map.put("userReplyAct", blankTo(context.userReplyAct, ""));
        map.put("userReplyActConfidence", context.userReplyActConfidence);
        map.put("assistantObligation", assistantObligationMap(context.assistantObligation));
        map.put("recommendedQuickJudgeTier", blankTo(context.recommendedQuickJudgeTier, ""));
        map.put("shouldAskQuickJudge", context.shouldAskQuickJudge);
        map.put("userReplyActCandidates", userReplyActCandidateMaps(context.userReplyActCandidates));
        map.put("localConflicts", localConflictMaps(context.localConflicts));
        map.put("continuityObjective", blankTo(context.continuityObjective, ""));
        map.put("continuityAcceptedPlan", blankTo(context.continuityAcceptedPlan, ""));
        map.put("continuityNextBestMove", blankTo(context.continuityNextBestMove, ""));
        map.put("sceneTransitionNeeded", context.sceneTransitionNeeded);
        map.put("continuityGuards", context.continuityGuards == null ? List.of() : context.continuityGuards);
        map.put("updatedAt", blankTo(context.updatedAt, ""));
        return map;
    }

    private Map<String, Object> dialogueContinuityMap(DialogueContinuityState state) {
        if (state == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("currentObjective", blankTo(state.currentObjective, ""));
        map.put("pendingUserOffer", blankTo(state.pendingUserOffer, ""));
        map.put("acceptedPlan", blankTo(state.acceptedPlan, ""));
        map.put("lastAssistantQuestion", blankTo(state.lastAssistantQuestion, ""));
        map.put("userAnsweredLastQuestion", state.userAnsweredLastQuestion);
        map.put("sceneTransitionNeeded", state.sceneTransitionNeeded);
        map.put("nextBestMove", blankTo(state.nextBestMove, ""));
        map.put("mustNotContradict", state.mustNotContradict == null ? List.of() : state.mustNotContradict);
        map.put("confidence", state.confidence);
        map.put("updatedAt", blankTo(state.updatedAt, ""));
        return map;
    }

    private List<Map<String, Object>> userReplyActCandidateMaps(List<UserReplyActCandidate> candidates) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (candidates == null) {
            return result;
        }
        for (UserReplyActCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("act", blankTo(candidate.act, ""));
            map.put("score", candidate.score);
            map.put("confidence", candidate.confidence);
            map.put("evidence", candidate.evidence == null ? List.of() : candidate.evidence);
            result.add(map);
        }
        return result;
    }

    private Map<String, Object> assistantObligationMap(AssistantObligation obligation) {
        if (obligation == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", blankTo(obligation.type, ""));
        map.put("source", blankTo(obligation.source, ""));
        map.put("priority", obligation.priority);
        map.put("expectedUserActs", obligation.expectedUserActs == null ? List.of() : obligation.expectedUserActs);
        map.put("reason", blankTo(obligation.reason, ""));
        return map;
    }

    private List<Map<String, Object>> localConflictMaps(List<LocalConflict> conflicts) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (conflicts == null) {
            return result;
        }
        for (LocalConflict conflict : conflicts) {
            if (conflict == null) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", blankTo(conflict.type, ""));
            map.put("severity", blankTo(conflict.severity, ""));
            map.put("sourceA", blankTo(conflict.sourceA, ""));
            map.put("sourceB", blankTo(conflict.sourceB, ""));
            map.put("recommendedAction", blankTo(conflict.recommendedAction, ""));
            result.add(map);
        }
        return result;
    }

    private QuickJudgeStatus quickJudgeStatusFrom(QuickJudgeDecision decision, String nowIso) {
        QuickJudgeStatus status = new QuickJudgeStatus();
        if (decision == null) {
            status.attempted = false;
            status.used = false;
            status.applied = false;
            status.status = "missing";
            status.reason = "missing";
            status.updatedAt = blankTo(nowIso, "");
            return status;
        }
        String reason = blankTo(decision.reason, "");
        status.attempted = decision.used || !(reason.startsWith("skip") || "blocked_by_safety".equals(reason) || "not_applicable_presence".equals(reason));
        status.used = decision.used;
        status.applied = decision.shouldApply();
        status.status = quickJudgeStatusLabel(decision);
        status.reason = reason;
        status.confidence = decision.confidence;
        status.primaryIntent = blankTo(decision.primaryIntent, "");
        status.secondaryIntent = blankTo(decision.secondaryIntent, "");
        status.emotion = blankTo(decision.emotion, "");
        status.sharedObjective = blankTo(decision.sharedObjective, "");
        status.nextBestMove = blankTo(decision.nextBestMove, "");
        status.replyPriority = blankTo(decision.replyPriority, "");
        status.updatedAt = blankTo(nowIso, "");
        return status;
    }

    private void applyQuickJudgeTriggerInfo(QuickJudgeStatus status, QuickJudgeTask task) {
        if (status == null || task == null) {
            return;
        }
        status.triggerScore = task.triggerScore;
        status.triggerReasons = task.triggerReasons == null ? new ArrayList<>() : new ArrayList<>(task.triggerReasons);
        status.suppressedReasons = task.suppressedReasons == null ? new ArrayList<>() : new ArrayList<>(task.suppressedReasons);
    }

    private String quickJudgeStatusLabel(QuickJudgeDecision decision) {
        if (decision == null) {
            return "missing";
        }
        String reason = blankTo(decision.reason, "");
        if ("blocked_by_safety".equals(reason)) {
            return "blocked";
        }
        if ("not_applicable_presence".equals(reason)) {
            return "presence_skip";
        }
        if (reason.startsWith("skip")) {
            return "skipped";
        }
        if (reason.startsWith("timeout")) {
            return "timeout";
        }
        if (reason.startsWith("background_deferred")) {
            return "deferred";
        }
        if (decision.shouldApply()) {
            return "applied";
        }
        if (decision.used) {
            return "returned_not_used";
        }
        if (reason.startsWith("fallback") || reason.startsWith("resolve")) {
            return "fallback";
        }
        return "idle";
    }

    private Map<String, Object> quickJudgeStatusMap(QuickJudgeStatus status) {
        if (status == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("attempted", status.attempted);
        map.put("used", status.used);
        map.put("applied", status.applied);
        map.put("status", blankTo(status.status, ""));
        map.put("reason", blankTo(status.reason, ""));
        map.put("confidence", status.confidence);
        map.put("primaryIntent", blankTo(status.primaryIntent, ""));
        map.put("secondaryIntent", blankTo(status.secondaryIntent, ""));
        map.put("emotion", blankTo(status.emotion, ""));
        map.put("sharedObjective", blankTo(status.sharedObjective, ""));
        map.put("nextBestMove", blankTo(status.nextBestMove, ""));
        map.put("replyPriority", blankTo(status.replyPriority, ""));
        map.put("triggerScore", status.triggerScore);
        map.put("triggerReasons", status.triggerReasons == null ? List.of() : status.triggerReasons);
        map.put("suppressedReasons", status.suppressedReasons == null ? List.of() : status.suppressedReasons);
        map.put("updatedAt", blankTo(status.updatedAt, ""));
        return map;
    }

    private Map<String, Object> quickJudgeDecisionMap(QuickJudgeDecision decision) {
        if (decision == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("used", decision.used);
        map.put("applied", decision.shouldApply());
        map.put("reason", blankTo(decision.reason, ""));
        map.put("confidence", decision.confidence);
        map.put("primaryIntent", blankTo(decision.primaryIntent, ""));
        map.put("secondaryIntent", blankTo(decision.secondaryIntent, ""));
        map.put("emotion", blankTo(decision.emotion, ""));
        map.put("sharedObjective", blankTo(decision.sharedObjective, ""));
        map.put("sceneTransitionNeeded", decision.sceneTransitionNeeded);
        map.put("nextBestMove", blankTo(decision.nextBestMove, ""));
        map.put("replyPriority", blankTo(decision.replyPriority, ""));
        return map;
    }

    private Map<String, Object> pendingRepairCueMap(PendingRepairCue cue) {
        if (cue == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", blankTo(cue.type, ""));
        map.put("instruction", blankTo(cue.instruction, ""));
        map.put("confidence", cue.confidence);
        map.put("createdAt", blankTo(cue.createdAt, ""));
        return map;
    }

    private Map<String, Object> relationshipCalibrationMap(RelationshipScoreCalibration calibration) {
        if (calibration == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("used", calibration.used);
        map.put("applied", calibration.shouldApply());
        map.put("closenessDelta", calibration.closenessDelta);
        map.put("trustDelta", calibration.trustDelta);
        map.put("resonanceDelta", calibration.resonanceDelta);
        map.put("confidence", calibration.confidence);
        map.put("reason", blankTo(calibration.reason, ""));
        map.put("createdAt", blankTo(calibration.createdAt, ""));
        map.put("sourceTurn", calibration.sourceTurn);
        return map;
    }

    private Map<String, Object> responsePlanMap(ResponsePlan responsePlan) {
        if (responsePlan == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("firstMove", blankTo(responsePlan.firstMove, ""));
        map.put("coreTask", blankTo(responsePlan.coreTask, ""));
        map.put("initiativeLevel", blankTo(responsePlan.initiativeLevel, ""));
        map.put("responseLength", blankTo(responsePlan.responseLength, ""));
        map.put("dialogueMode", blankTo(responsePlan.dialogueMode, ""));
        map.put("shouldReferenceMemory", responsePlan.shouldReferenceMemory);
        map.put("shouldAdvanceScene", responsePlan.shouldAdvanceScene);
        map.put("shouldAdvancePlot", responsePlan.shouldAdvancePlot);
        map.put("shouldUseUncertainty", responsePlan.shouldUseUncertainty);
        map.put("allowFollowupQuestion", responsePlan.allowFollowupQuestion);
        map.put("explanation", blankTo(responsePlan.explanation, ""));
        map.put("updatedAt", blankTo(responsePlan.updatedAt, ""));
        return map;
    }

    private Map<String, Object> agentTimingMap(
            long sendStartedAtNanos,
            QuickJudgeTask quickJudgeTask,
            long plotDirectorStartedAtNanos,
            long plotDirectorFinishedAtNanos,
            long mainReplyStartedAtNanos,
            long mainReplyFinishedAtNanos
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        long quickJudgeStartedAtNanos = quickJudgeTask == null ? 0L : quickJudgeTask.startedAtNanos;
        long quickJudgeCompletedAtNanos = quickJudgeTask == null ? 0L : quickJudgeTask.completedAtNanos.get();
        map.put("quickJudgeTriggerTier", quickJudgeTask == null ? "" : blankTo(quickJudgeTask.triggerTier, ""));
        map.put("quickJudgeTriggerReason", quickJudgeTask == null ? "" : blankTo(quickJudgeTask.triggerReason, ""));
        map.put("quickJudgeTriggerScore", quickJudgeTask == null ? 0 : quickJudgeTask.triggerScore);
        map.put("quickJudgeTriggerReasons", quickJudgeTask == null || quickJudgeTask.triggerReasons == null ? List.of() : quickJudgeTask.triggerReasons);
        map.put("quickJudgeSuppressedReasons", quickJudgeTask == null || quickJudgeTask.suppressedReasons == null ? List.of() : quickJudgeTask.suppressedReasons);
        map.put("quickJudgeStartedMs", elapsedMs(sendStartedAtNanos, quickJudgeStartedAtNanos));
        map.put("quickJudgeCompletedMs", elapsedMs(sendStartedAtNanos, quickJudgeCompletedAtNanos));
        map.put("plotDirectorStartedMs", elapsedMs(sendStartedAtNanos, plotDirectorStartedAtNanos));
        map.put("plotDirectorFinishedMs", elapsedMs(sendStartedAtNanos, plotDirectorFinishedAtNanos));
        map.put("mainReplyStartedMs", elapsedMs(sendStartedAtNanos, mainReplyStartedAtNanos));
        map.put("mainReplyFinishedMs", elapsedMs(sendStartedAtNanos, mainReplyFinishedAtNanos));
        map.put(
                "quickJudgeCompletedBeforePlotDirector",
                quickJudgeCompletedAtNanos > 0L && plotDirectorFinishedAtNanos > 0L && quickJudgeCompletedAtNanos <= plotDirectorFinishedAtNanos
        );
        map.put(
                "quickJudgeVsPlotDirectorMs",
                quickJudgeCompletedAtNanos > 0L && plotDirectorFinishedAtNanos > 0L
                        ? Math.round((quickJudgeCompletedAtNanos - plotDirectorFinishedAtNanos) / 1_000_000.0)
                        : null
        );
        map.put(
                "quickJudgeCompletedBeforeMainReply",
                quickJudgeCompletedAtNanos > 0L && mainReplyStartedAtNanos > 0L && quickJudgeCompletedAtNanos <= mainReplyStartedAtNanos
        );
        map.put(
                "quickJudgeVsMainReplyStartMs",
                quickJudgeCompletedAtNanos > 0L && mainReplyStartedAtNanos > 0L
                        ? Math.round((quickJudgeCompletedAtNanos - mainReplyStartedAtNanos) / 1_000_000.0)
                        : null
        );
        map.put(
                "quickJudgeVsMainReplyFinishMs",
                quickJudgeCompletedAtNanos > 0L && mainReplyFinishedAtNanos > 0L
                        ? Math.round((quickJudgeCompletedAtNanos - mainReplyFinishedAtNanos) / 1_000_000.0)
                        : null
        );
        return map;
    }

    private Long elapsedMs(long startedAtNanos, long targetNanos) {
        if (startedAtNanos <= 0L || targetNanos <= 0L || targetNanos < startedAtNanos) {
            return null;
        }
        return Math.round((targetNanos - startedAtNanos) / 1_000_000.0);
    }

    private Map<String, Object> initiativeDecisionMap(InitiativeDecision initiativeDecision) {
        if (initiativeDecision == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("allowed", initiativeDecision.allowed);
        map.put("action", blankTo(initiativeDecision.action, ""));
        map.put("level", blankTo(initiativeDecision.level, ""));
        map.put("reason", blankTo(initiativeDecision.reason, ""));
        map.put("updatedAt", blankTo(initiativeDecision.updatedAt, ""));
        return map;
    }

    private Map<String, Object> humanizationAuditMap(HumanizationAudit audit) {
        if (audit == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("feltHeard", audit.feltHeard);
        map.put("answeredCoreQuestion", audit.answeredCoreQuestion);
        map.put("usedMemoryNaturally", audit.usedMemoryNaturally);
        map.put("initiativeAppropriate", audit.initiativeAppropriate);
        map.put("sceneConsistent", audit.sceneConsistent);
        map.put("emotionMatched", audit.emotionMatched);
        map.put("overacted", audit.overacted);
        map.put("tooMechanical", audit.tooMechanical);
        map.put("notes", audit.notes == null ? List.of() : audit.notes);
        return map;
    }

    private Map<String, Object> realityAuditMap(RealityAudit audit) {
        if (audit == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timeConsistent", audit.timeConsistent);
        map.put("weatherConsistent", audit.weatherConsistent);
        map.put("sceneConsistent", audit.sceneConsistent);
        map.put("interactionConsistent", audit.interactionConsistent);
        map.put("grounded", audit.grounded);
        map.put("notes", audit.notes == null ? List.of() : audit.notes);
        return map;
    }

    private Map<String, Object> plotGateDecisionMap(PlotGateDecision decision) {
        if (decision == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("allowed", decision.allowed);
        map.put("triggerReason", blankTo(decision.triggerReason, ""));
        map.put("blockedReason", blankTo(decision.blockedReason, ""));
        map.put("requiredScene", blankTo(decision.requiredScene, ""));
        map.put("requiredRelationFloor", decision.requiredRelationFloor);
        map.put("requiredGap", decision.requiredGap);
        map.put("candidateEventId", blankTo(decision.candidateEventId, ""));
        map.put("updatedAt", blankTo(decision.updatedAt, ""));
        return map;
    }

    private Map<String, Object> tensionStateMap(RelationalTensionState tensionState) {
        if (tensionState == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("annoyance", tensionState.annoyance);
        map.put("hurt", tensionState.hurt);
        map.put("guarded", tensionState.guarded);
        map.put("repairReadiness", tensionState.repairReadiness);
        map.put("recentBoundaryHits", tensionState.recentBoundaryHits);
        map.put("updatedAt", blankTo(tensionState.updatedAt, ""));
        return map;
    }

    private Map<String, Object> emotionStateMap(EmotionState emotionState) {
        EmotionState state = affectionJudgeService.normalizeEmotion(emotionState, IsoTimes.now());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("warmth", state.warmth);
        map.put("safety", state.safety);
        map.put("longing", state.longing);
        map.put("initiative", state.initiative);
        map.put("vulnerability", state.vulnerability);
        map.put("currentMood", state.currentMood);
        map.put("updatedAt", state.updatedAt);
        return map;
    }

    private Map<String, Object> plotStateMap(PlotState plotState) {
        PlotState state = plotDirectorService.normalizePlot(plotState, IsoTimes.now());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("beatIndex", state.beatIndex);
        map.put("phase", state.phase);
        map.put("sceneFrame", state.sceneFrame);
        map.put("openThreads", state.openThreads);
        map.put("lastPlotTurn", state.lastPlotTurn);
        map.put("forcePlotAtTurn", state.forcePlotAtTurn);
        map.put("plotPressure", state.plotPressure);
        map.put("plotProgress", state.plotProgress);
        map.put("nextBeatHint", state.nextBeatHint);
        map.put("updatedAt", state.updatedAt);
        return map;
    }

    private Map<String, Object> plotArcStateMap(PlotArcState plotArcState) {
        PlotArcState state = plotDirectorService.normalizeArc(plotArcState, IsoTimes.now());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("beatIndex", state.beatIndex);
        map.put("arcIndex", state.arcIndex);
        map.put("phase", state.phase);
        map.put("sceneFrame", state.sceneFrame);
        map.put("openThreads", state.openThreads);
        map.put("lastPlotTurn", state.lastPlotTurn);
        map.put("forcePlotAtTurn", state.forcePlotAtTurn);
        map.put("plotPressure", state.plotPressure);
        map.put("plotProgress", state.plotProgress);
        map.put("nextBeatHint", state.nextBeatHint);
        map.put("checkpointReady", state.checkpointReady);
        map.put("runStatus", state.runStatus);
        map.put("endingCandidate", state.endingCandidate);
        map.put("canSettleScore", state.canSettleScore);
        map.put("canContinue", state.canContinue);
        map.put("latestArcSummary", arcSummaryMap(state.latestArcSummary));
        map.put("updatedAt", state.updatedAt);
        return map;
    }

    private Map<String, Object> sceneStateMap(SceneState sceneState) {
        SceneState state = sceneDirectorService.normalize(sceneState, IsoTimes.now());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("location", state.location);
        map.put("subLocation", state.subLocation);
        map.put("interactionMode", state.interactionMode);
        map.put("timeOfScene", state.timeOfScene);
        map.put("weatherMood", state.weatherMood);
        map.put("transitionPending", state.transitionPending);
        map.put("transitionLockUntilTurn", state.transitionLockUntilTurn);
        map.put("lastConfirmedSceneTurn", state.lastConfirmedSceneTurn);
        map.put("sceneSummary", state.sceneSummary);
        map.put("updatedAt", state.updatedAt);
        return map;
    }

    private Map<String, Object> presenceStateMap(PresenceState presenceState) {
        PresenceState state = presenceHeartbeatService.normalizePresence(presenceState, IsoTimes.now());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("visible", state.visible);
        map.put("focused", state.focused);
        map.put("online", state.online);
        map.put("typing", state.typing);
        map.put("draftLength", state.draftLength);
        map.put("openedAt", state.openedAt);
        map.put("lastHeartbeatAt", state.lastHeartbeatAt);
        map.put("lastSeenAt", state.lastSeenAt);
        map.put("lastUserMessageAt", state.lastUserMessageAt);
        map.put("lastSilenceHeartbeatAt", state.lastSilenceHeartbeatAt);
        map.put("lastLongHeartbeatAt", state.lastLongHeartbeatAt);
        map.put("lastInputAt", state.lastInputAt);
        map.put("blockedReason", state.blockedReason);
        map.put("triggerReason", state.triggerReason);
        map.put("heartbeatExplain", state.heartbeatExplain);
        return map;
    }

    private Map<String, Object> timeContextMap(TimeContext timeContext) {
        if (timeContext == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timezone", timeContext.timezone == null ? "" : timeContext.timezone);
        map.put("localTime", timeContext.localTime == null ? "" : timeContext.localTime);
        map.put("dayPart", timeContext.dayPart == null ? "" : timeContext.dayPart);
        map.put("frame", timeContext.frame == null ? "" : timeContext.frame);
        return map;
    }

    private Map<String, Object> weatherContextMap(WeatherContext weatherContext) {
        if (weatherContext == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("city", weatherContext.city == null ? "" : weatherContext.city);
        map.put("summary", weatherContext.summary == null ? "" : weatherContext.summary);
        map.put("temperatureC", weatherContext.temperatureC);
        map.put("live", weatherContext.live);
        map.put("updatedAt", weatherContext.updatedAt);
        return map;
    }

    private Map<String, Object> arcSummaryMap(ArcCheckpointSummary summary) {
        if (summary == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("arcIndex", summary.arcIndex);
        map.put("beatStart", summary.beatStart);
        map.put("beatEnd", summary.beatEnd);
        map.put("title", summary.title);
        map.put("routeTheme", summary.routeTheme);
        map.put("relationshipSummary", summary.relationshipSummary);
        map.put("sceneSummary", summary.sceneSummary);
        map.put("endingTendency", summary.endingTendency);
        map.put("updatedAt", summary.updatedAt);
        return map;
    }

    private List<Map<String, Object>> factMemoryList(List<FactMemoryItem> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (FactMemoryItem item : items) {
            if (item == null) {
                continue;
            }
            result.add(Map.of(
                    "key", item.key == null ? "" : item.key,
                    "value", item.value == null ? "" : item.value,
                    "confidence", item.confidence == null ? "" : item.confidence,
                    "sourceTurn", item.sourceTurn,
                    "lastUsedTurn", item.lastUsedTurn,
                    "supersededBy", item.supersededBy == null ? "" : item.supersededBy,
                    "updatedAt", item.updatedAt == null ? "" : item.updatedAt
            ));
        }
        return result;
    }

    private List<Map<String, Object>> sceneLedgerList(List<SceneLedgerItem> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (SceneLedgerItem item : items) {
            if (item == null) {
                continue;
            }
            result.add(Map.of(
                    "sceneId", item.sceneId == null ? "" : item.sceneId,
                    "location", item.location == null ? "" : item.location,
                    "summary", item.summary == null ? "" : item.summary,
                    "sourceTurn", item.sourceTurn,
                    "lastUsedTurn", item.lastUsedTurn,
                    "updatedAt", item.updatedAt == null ? "" : item.updatedAt
            ));
        }
        return result;
    }

    private List<Map<String, Object>> openLoopList(List<OpenLoopItem> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (OpenLoopItem item : items) {
            if (item == null) {
                continue;
            }
            result.add(Map.of(
                    "id", item.id == null ? "" : item.id,
                    "summary", item.summary == null ? "" : item.summary,
                    "sourceType", item.sourceType == null ? "" : item.sourceType,
                    "resolved", item.resolved,
                    "sourceTurn", item.sourceTurn,
                    "lastUsedTurn", item.lastUsedTurn,
                    "updatedAt", item.updatedAt == null ? "" : item.updatedAt
            ));
        }
        return result;
    }

    private PlotGateDecision emptyPlotGate(String nowIso) {
        PlotGateDecision decision = new PlotGateDecision();
        decision.allowed = false;
        decision.triggerReason = "none";
        decision.blockedReason = "no_candidate";
        decision.requiredScene = "";
        decision.requiredRelationFloor = 0;
        decision.requiredGap = 0;
        decision.candidateEventId = "";
        decision.updatedAt = nowIso;
        return decision;
    }

    private RealityAudit passRealityAudit() {
        RealityAudit audit = new RealityAudit();
        audit.timeConsistent = true;
        audit.weatherConsistent = true;
        audit.sceneConsistent = true;
        audit.interactionConsistent = true;
        audit.grounded = true;
        return audit;
    }

    private HumanizationAudit emptyHumanizationAudit() {
        HumanizationAudit audit = new HumanizationAudit();
        audit.feltHeard = true;
        audit.answeredCoreQuestion = true;
        audit.usedMemoryNaturally = true;
        audit.initiativeAppropriate = true;
        audit.sceneConsistent = true;
        audit.emotionMatched = true;
        audit.overacted = false;
        audit.tooMechanical = false;
        return audit;
    }

    private UncertaintyState buildUncertaintyState(IntentState intentState, SearchDecision searchDecision, PlotGateDecision plotGateDecision, String nowIso) {
        UncertaintyState state = new UncertaintyState();
        if (searchDecision != null && searchDecision.mustNotGuess) {
            state.level = "low";
            state.reason = "must_not_guess";
            state.shouldClarify = true;
        } else if (intentState != null && "low".equals(intentState.clarity)) {
            state.level = "medium";
            state.reason = "low_clarity";
            state.shouldClarify = true;
        } else if (plotGateDecision != null && !plotGateDecision.allowed && "scene_mismatch".equals(plotGateDecision.blockedReason)) {
            state.level = "medium";
            state.reason = "scene_gate";
            state.shouldClarify = false;
        } else {
            state.level = "high";
            state.reason = "stable";
            state.shouldClarify = false;
        }
        state.updatedAt = nowIso;
        return state;
    }

    private List<MemoryIntentBinding> buildMemoryIntentBindings(MemoryUsePlan memoryUsePlan, MemoryRecall recall) {
        List<MemoryIntentBinding> bindings = new ArrayList<>();
        if (memoryUsePlan == null) {
            return bindings;
        }
        int index = 0;
        for (String memory : memoryUsePlan.selectedMemories) {
            MemoryIntentBinding binding = new MemoryIntentBinding();
            binding.memoryId = "memory-" + index++;
            binding.usageGoal = blankTo(memoryUsePlan.useMode, "hold");
            binding.relevanceScore = Math.max(20, 80 - index * 10);
            binding.safeToRecall = !"hold".equals(memoryUsePlan.useMode);
            binding.repeatRisk = recall != null && recall.selectedMemories.contains(memory);
            bindings.add(binding);
        }
        return bindings;
    }

    private MemoryRecall sanitizeMemoryRecall(MemoryRecall recall) {
        if (recall == null || recall.selectedMemories == null || recall.selectedMemories.isEmpty()) {
            return recall == null ? new MemoryRecall("none", List.of(), "") : recall;
        }
        List<String> filtered = new ArrayList<>();
        for (String item : recall.selectedMemories) {
            if (!isGreetingLikeMemory(item)) {
                filtered.add(item);
            }
        }
        if (filtered.isEmpty()) {
            return new MemoryRecall("none", List.of(), "");
        }
        return new MemoryRecall(recall.tier, filtered, String.join("；", filtered));
    }

    private boolean isGreetingLikeMemory(String item) {
        if (item == null || item.isBlank()) {
            return true;
        }
        String compact = item.trim();
        String tail = compact.contains(":") ? compact.substring(compact.lastIndexOf(':') + 1).trim() : compact;
        return tail.length() <= 8
                || tail.contains("你好")
                || tail.contains("在吗")
                || tail.contains("嗨")
                || tail.contains("好啊");
    }

    private String selectSceneText(String plotSceneText, String llmSceneText, String speechText, String replySource) {
        String modelScene = llmSceneText == null ? "" : llmSceneText.trim();
        String directorScene = plotSceneText == null ? "" : plotSceneText.trim();
        if (!modelScene.isBlank()) {
            return modelScene;
        }
        return directorScene;
    }

    private String removeSceneTextFromSpeech(String speechText, String sceneText) {
        String normalized = speechText == null ? "" : speechText.trim().replaceAll("\\s+", " ");
        String scene = sceneText == null ? "" : sceneText.trim();
        if (normalized.isBlank() || scene.isBlank()) {
            return normalized;
        }
        normalized = removeLeadingScenePrefix(normalized, scene);
        List<String> kept = new ArrayList<>();
        for (String sentence : splitDisplaySentences(normalized)) {
            if (!isDuplicateSceneSentence(sentence, scene)) {
                kept.add(sentence.trim());
            }
        }
        String cleaned = String.join(" ", kept).trim().replaceAll("\\s+", " ");
        return trimStrayScenePunctuation(cleaned);
    }

    private String removeLeadingScenePrefix(String speechText, String sceneText) {
        String normalized = speechText == null ? "" : speechText.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank() || sceneText == null || sceneText.isBlank()) {
            return normalized;
        }
        int bestEnd = -1;
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            if (!isSoftSceneBoundary(ch)) {
                continue;
            }
            String prefix = normalized.substring(0, index + 1).trim();
            String rest = normalized.substring(index + 1).trim();
            if (!rest.isBlank() && isDuplicateScenePrefix(prefix, sceneText)) {
                bestEnd = index + 1;
            }
        }
        if (bestEnd <= 0 || bestEnd >= normalized.length()) {
            return normalized;
        }
        return trimStrayScenePunctuation(normalized.substring(bestEnd));
    }

    private boolean isSoftSceneBoundary(char ch) {
        return ch == ',' || ch == '\uFF0C' || ch == '\u3001' || ch == ';' || ch == '\uFF1B'
                || ch == '\u3002' || ch == '!' || ch == '\uFF01' || ch == '?' || ch == '\uFF1F';
    }

    private boolean isDuplicateScenePrefix(String prefix, String sceneText) {
        String prefixComparable = normalizeSceneComparableText(prefix);
        String sceneComparable = normalizeSceneComparableText(sceneText);
        if (prefixComparable.length() < 6 || sceneComparable.isBlank()) {
            return false;
        }
        if (sceneComparable.contains(prefixComparable)) {
            return true;
        }
        if (sceneSimilarity(prefixComparable, sceneComparable) >= 0.68 && looksLikeSceneNarration(prefix)) {
            return true;
        }
        int overlap = 0;
        for (String cue : List.of(
                "\u4e24\u4eba", "\u6211\u4eec", "\u4e00\u8d77", "\u5e76\u80a9", "\u624b\u7275\u624b", "\u5f80", "\u5411", "\u8d70", "\u8fc7\u53bb",
                "\u98df\u5802", "\u64cd\u573a", "\u5bbf\u820d", "\u56fe\u4e66\u9986", "\u8def\u4e0a", "\u5fae\u98ce", "\u508d\u665a", "\u51c9\u610f", "\u6c14\u6c1b"
        )) {
            if (prefixComparable.contains(cue) && sceneComparable.contains(cue)) {
                overlap++;
            }
        }
        return overlap >= 3;
    }

    private String trimStrayScenePunctuation(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceFirst("^[\\s,\\uFF0C\\u3001\\u3002.!\\uFF01?\\uFF1F;\\uFF1B:\\uFF1A]+", "").trim();
    }

    private boolean isDuplicateSceneSentence(String sentence, String sceneText) {
        String speech = normalizeSceneComparableText(sentence);
        String scene = normalizeSceneComparableText(sceneText);
        if (speech.isBlank() || scene.isBlank()) {
            return false;
        }
        if (speech.contains(scene) || scene.contains(speech)) {
            return true;
        }
        double similarity = sceneSimilarity(speech, scene);
        if (similarity >= 0.86) {
            return true;
        }
        return similarity >= 0.70 && looksLikeSceneNarration(sentence);
    }

    private List<String> splitDisplaySentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            current.append(ch);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?') {
                String sentence = current.toString().trim();
                if (!sentence.isBlank()) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }
        String rest = current.toString().trim();
        if (!rest.isBlank()) {
            sentences.add(rest);
        }
        return sentences;
    }

    private String normalizeSceneComparableText(String text) {
        String normalized = text == null ? "" : text;
        normalized = normalized.replaceAll("\\s+", "");
        normalized = normalized.replaceAll("[，。！？、；：,.!?;:（）()“”‘’《》【】\\[\\]{}…—-]", "");
        normalized = normalized.replace("我们俩", "两人")
                .replace("咱们俩", "两人")
                .replace("你们", "两人")
                .replace("我们", "两人")
                .replace("一块儿", "一起");
        return normalized;
    }

    private double sceneSimilarity(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0.0;
        }
        Set<String> leftGrams = charBigrams(left);
        Set<String> rightGrams = charBigrams(right);
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) {
            return left.equals(right) ? 1.0 : 0.0;
        }
        int shared = 0;
        for (String gram : leftGrams) {
            if (rightGrams.contains(gram)) {
                shared++;
            }
        }
        return (2.0 * shared) / (leftGrams.size() + rightGrams.size());
    }

    private Set<String> charBigrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text.length() < 2) {
            if (!text.isBlank()) {
                grams.add(text);
            }
            return grams;
        }
        for (int index = 0; index < text.length() - 1; index++) {
            grams.add(text.substring(index, index + 2));
        }
        return grams;
    }

    private boolean looksLikeSceneNarration(String sentence) {
        String text = normalizeSceneComparableText(sentence);
        boolean hasMoveOrGroup = sceneContainsAny(text, List.of("两人", "一起", "并肩", "走", "往", "向", "过去", "挪", "转"));
        boolean hasPlaceOrAtmosphere = sceneContainsAny(text, List.of("操场", "小道", "路上", "宿舍", "食堂", "图书馆", "门口", "夕阳", "身影", "影子", "微风", "气氛"));
        boolean hasDialogueQuestion = sceneContainsAny(text, List.of("吗", "嘛", "呢", "吧", "要不要", "有没有", "喜欢", "觉得"));
        return text.length() <= 60 && hasMoveOrGroup && hasPlaceOrAtmosphere && !hasDialogueQuestion;
    }

    private boolean sceneContainsAny(String text, List<String> keywords) {
        String safe = text == null ? "" : text;
        for (String keyword : keywords) {
            if (safe.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String lastMessageText(List<ConversationMessage> messages, String role) {
        if (messages == null || role == null) {
            return "";
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            ConversationMessage message = messages.get(index);
            if (message != null && role.equals(message.role)) {
                return blankTo(message.speechText, blankTo(message.text, "")).trim();
            }
        }
        return "";
    }

    private String heartbeatSelfContextDirective(String lastAssistantMessage) {
        String text = blankTo(lastAssistantMessage, "").trim();
        if (text.isBlank()) {
            return "";
        }
        return "\u3002\u4e5f\u8981\u590d\u76d8\u89d2\u8272\u81ea\u5df1\u4e0a\u4e00\u53e5\uff1a\"" + text
                + "\"\u3002\u5982\u679c\u4e0a\u4e00\u53e5\u5df2\u7ecf\u627f\u63a5\u6216\u6267\u884c\u4e86\u4e00\u4e2a\u8ba1\u5212\uff0c"
                + "\u5fc3\u8df3\u53ea\u80fd\u987a\u7740\u5df2\u7ecf\u53d1\u751f\u7684\u52a8\u4f5c\u8f7b\u8f7b\u7eed\u4e0a\uff0c"
                + "\u4e0d\u8981\u91cd\u65b0\u8be2\u95ee\u662f\u5426\u8981\u505a\u540c\u4e00\u4ef6\u4e8b\u3002";
    }

    private String quickJudgeMode(Map<String, Object> payload) {
        String mode = Json.asString(payload.get("quickJudgeMode")).trim().toLowerCase();
        if ("off".equals(mode) || "smart".equals(mode) || "always".equals(mode)) {
            return mode;
        }
        boolean enabled = !payload.containsKey("quickJudgeEnabled") || Json.asBoolean(payload.get("quickJudgeEnabled"));
        if (!enabled) {
            return "off";
        }
        boolean forceAll = payload.containsKey("quickJudgeForceAll") && Json.asBoolean(payload.get("quickJudgeForceAll"));
        return forceAll ? "always" : "smart";
    }

    private Long quickJudgeWaitMsFromSeconds(Object value) {
        if (value == null) {
            return null;
        }
        double seconds;
        if (value instanceof Number number) {
            seconds = number.doubleValue();
        } else {
            String text = Json.asString(value).trim();
            if (text.isBlank()) {
                return null;
            }
            try {
                seconds = Double.parseDouble(text);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (seconds <= 0) {
            return null;
        }
        return Math.round(seconds * 1000.0);
    }

    private TemperamentProfile temperamentProfileFor(AgentProfile agent) {
        TemperamentProfile profile = new TemperamentProfile();
        switch (agent == null ? "" : agent.id) {
            case "lively" -> {
                profile.warmStyle = "bright";
                profile.teasingStyle = "playful";
                profile.irritationThreshold = 3;
                profile.boundarySensitivity = 4;
                profile.forgivenessSpeed = 5;
                profile.initiativeStyle = "push_forward";
            }
            case "cool" -> {
                profile.warmStyle = "restrained";
                profile.teasingStyle = "dry";
                profile.irritationThreshold = 2;
                profile.boundarySensitivity = 6;
                profile.forgivenessSpeed = 3;
                profile.initiativeStyle = "measured";
            }
            default -> {
                profile.warmStyle = "soft";
                profile.teasingStyle = "gentle";
                profile.irritationThreshold = 3;
                profile.boundarySensitivity = 5;
                profile.forgivenessSpeed = 4;
                profile.initiativeStyle = "balanced";
            }
        }
        return profile;
    }

    private String buildChoiceReply(AgentProfile agent, StoryEvent event, ChoiceOption choice, RelationshipState state) {
        String agentId = agent == null ? "" : agent.id;
        String subject = agent == null ? "对方" : agent.subjectPronoun;
        String prefix = switch (agentId) {
            case "healing" -> subject + "抬眼看向你，语气更轻了一点。";
            case "lively" -> subject + "先是一怔，随后笑意慢慢亮了起来。";
            case "cool" -> subject + "沉默了半拍，但没有把视线移开。";
            case "artsy" -> subject + "像是把这句回应轻轻收进了晚风里。";
            default -> subject + "把你的回应稳稳接住了。";
        };
        String resultLine = switch (choice.outcomeType) {
            case "success" -> "这次你给出的信号足够明确，关系明显往前走了一步。";
            case "fail" -> "这次节奏有点错开了，气氛先慢了下来。";
            default -> "这次气氛被维持住了，但还没到真正突破的时候。";
        };
        return prefix + " 在“" + event.title + "”这一刻，" + resultLine + " " + state.relationshipFeedback;
    }

    private String ids(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
