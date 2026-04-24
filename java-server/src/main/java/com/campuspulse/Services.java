package com.campuspulse;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Services {
    private Services() {
    }
}

interface StateMutation<T> {
    T apply(AppState state) throws Exception;
}

@FunctionalInterface
interface CheckedSupplier<T> {
    T get() throws Exception;
}

final class TimedValue<T> {
    final T value;
    final long elapsedMs;

    TimedValue(T value, long elapsedMs) {
        this.value = value;
        this.elapsedMs = elapsedMs;
    }
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
        double feedbackRate = sessionCount == 0 ? 0 : round(state.feedback.size() * 100.0 / sessionCount);

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
                "feedbackCompletionRate", feedbackRate,
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
        return new TurnEvaluation(next, delta);
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

abstract class CompositeLlmClient implements LlmClient {
    CompositeLlmClient(AppConfig config) {
    }

    String buildFallbackReply(AgentProfile agent, String reason) {
        String name = agent == null ? "我" : agent.name;
        return name + "刚刚有点卡住了，不过现在可以继续。";
    }
}

class ChatOrchestrator {
    private static final ExecutorService asyncStageExecutor = Executors.newCachedThreadPool();
    private final StateRepository repository;
    private final AgentConfigService agentConfigService;
    private final MemoryService memoryService;
    private final RelationshipService relationshipService;
    private final EventEngine eventEngine;
    private final CompositeLlmClient llmClient;
    private final SafetyService safetyService;
    private final AnalyticsService analyticsService;
    private final AffectionJudgeService affectionJudgeService;
    private final EnhancedPlotDirectorService plotDirectorService;
    private final EnhancedPresenceHeartbeatService presenceHeartbeatService = new EnhancedPresenceHeartbeatService();
    private final RealityContextService realityContextService = new RealityContextService();
    private final SearchDecisionService searchDecisionService = new SearchDecisionService();
    private final SceneDirectorService sceneDirectorService = new SceneDirectorService();
    private final IntentInferenceService intentInferenceService = new IntentInferenceService();
    private final ResponsePlanningService responsePlanningService = new ResponsePlanningService();
    private final InitiativePolicyService initiativePolicyService = new InitiativePolicyService();
    private final BoundaryResponseService boundaryResponseService = new BoundaryResponseService();
    private final PlotGateService plotGateService = new PlotGateService();
    private final RealityGuardService realityGuardService = new RealityGuardService();
    private final HumanizationEvaluationService humanizationEvaluationService = new HumanizationEvaluationService();
    private final DialogueContinuityService dialogueContinuityService = new DialogueContinuityService();
    private final SemanticRuntimeAgentService semanticRuntimeAgentService;
    private final RemoteTurnAnalysisService remoteTurnAnalysisService;

    static MemoryService createMemoryService(long retentionMs) {
        return new EnhancedSocialMemoryService(retentionMs);
    }

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
            AppConfig config
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
                AgentRuntimeFactory.plotDirector(config),
                AgentRuntimeFactory.semanticRuntime(config),
                AgentRuntimeFactory.affectionJudge(config),
                config
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
                plotDirectorAgentService,
                new SemanticRuntimeAgentService(),
                new LocalAffectionJudgeService()
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
            PlotDirectorAgentService plotDirectorAgentService,
            SemanticRuntimeAgentService semanticRuntimeAgentService
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
                plotDirectorAgentService,
                semanticRuntimeAgentService,
                new LocalAffectionJudgeService(),
                null
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
            PlotDirectorAgentService plotDirectorAgentService,
            SemanticRuntimeAgentService semanticRuntimeAgentService,
            AffectionJudgeService affectionJudgeService
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
                plotDirectorAgentService,
                semanticRuntimeAgentService,
                affectionJudgeService,
                null
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
            PlotDirectorAgentService plotDirectorAgentService,
            SemanticRuntimeAgentService semanticRuntimeAgentService,
            AffectionJudgeService affectionJudgeService,
            AppConfig config
    ) {
        this.repository = repository;
        this.agentConfigService = agentConfigService;
        this.memoryService = memoryService;
        this.relationshipService = relationshipService;
        this.eventEngine = eventEngine;
        this.llmClient = llmClient;
        this.safetyService = safetyService;
        this.analyticsService = analyticsService;
        this.plotDirectorService = new EnhancedPlotDirectorService(plotDirectorAgentService);
        this.semanticRuntimeAgentService = semanticRuntimeAgentService == null ? new SemanticRuntimeAgentService() : semanticRuntimeAgentService;
        this.affectionJudgeService = affectionJudgeService == null ? new LocalAffectionJudgeService() : affectionJudgeService;
        this.remoteTurnAnalysisService = config == null ? null : new RemoteTurnAnalysisService(config);
    }

    private <T> CompletableFuture<T> runAsyncStage(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, asyncStageExecutor);
    }

    private <T> T awaitStage(CompletableFuture<T> future) throws Exception {
        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw ex;
        }
    }

    private long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private void recordStageTiming(String scope, Map<String, Long> timings, String stageName, long elapsedMs) {
        timings.put(stageName, elapsedMs);
        System.out.println("[timing][" + scope + "] " + stageName + "=" + elapsedMs + "ms");
    }

    private <T> TimedValue<T> runTimedStage(String scope, Map<String, Long> timings, String stageName, CheckedSupplier<T> supplier) throws Exception {
        long startedAt = System.nanoTime();
        try {
            T value = supplier.get();
            long elapsedMs = elapsedMillis(startedAt);
            recordStageTiming(scope, timings, stageName, elapsedMs);
            return new TimedValue<>(value, elapsedMs);
        } catch (Exception ex) {
            long elapsedMs = elapsedMillis(startedAt);
            recordStageTiming(scope, timings, stageName + "_error", elapsedMs);
            throw ex;
        }
    }

    private <T> CompletableFuture<TimedValue<T>> runTimedAsyncStage(String scope, Map<String, Long> timings, String stageName, CheckedSupplier<T> supplier) {
        return runAsyncStage(() -> runTimedStage(scope, timings, stageName, supplier));
    }

    private boolean shouldUseCombinedRemoteTurnAnalysis() {
        return remoteTurnAnalysisService != null
                && remoteTurnAnalysisService.remoteEnabled()
                && semanticRuntimeAgentService instanceof RemoteSemanticRuntimeAgentService
                && affectionJudgeService instanceof RemoteAffectionJudgeService;
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

    List<Map<String, Object>> getSessionHistory(String sessionId) throws Exception {
        AppState state = repository.getState();
        state.sessions.stream()
                .filter(session -> sessionId.equals(session.id))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "SESSION_NOT_FOUND"));

        List<Map<String, Object>> history = new ArrayList<>();
        for (ConversationMessage message : state.messages.stream()
                .filter(item -> sessionId.equals(item.sessionId))
                .sorted(Comparator.comparing(item -> Instant.parse(item.createdAt)))
                .toList()) {
            history.add(messageMap(message));
        }
        return history;
    }

    Map<String, Object> submitFeedback(Map<String, Object> payload) throws Exception {
        return repository.transact(state -> {
            String sessionId = Json.asString(payload.get("sessionId"));
            SessionRecord session = state.sessions.stream()
                    .filter(item -> sessionId.equals(item.id))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "SESSION_NOT_FOUND"));

            UserFeedback feedback = new UserFeedback();
            feedback.id = ids("feedback");
            feedback.visitorId = Json.asString(payload.get("visitorId"));
            feedback.sessionId = session.id;
            feedback.agentId = Json.asString(payload.get("agentId"));
            feedback.rating = Json.asInt(payload.get("rating"), 4);
            feedback.likedPoint = Json.asString(payload.get("likedPoint"));
            feedback.improvementPoint = Json.asString(payload.get("improvementPoint"));
            feedback.continueIntent = payload.get("continueIntent") instanceof Boolean bool && bool;
            feedback.createdAt = IsoTimes.now();
            state.feedback.add(feedback);

            analyticsService.recordEvent(state, "feedback_submit", Map.of(
                    "visitorId", feedback.visitorId,
                    "sessionId", feedback.sessionId,
                    "agentId", feedback.agentId
            ));

            return Map.of("saved", true);
        });
    }

    Map<String, Object> getAnalyticsOverview() throws Exception {
        AppState state = repository.getState();
        return analyticsService.buildOverview(state, agentConfigService.getAgents());
    }

    Map<String, Object> sendMessage(Map<String, Object> payload) throws Exception {
        return repository.transact(state -> {
            String visitorId = Json.asString(payload.get("visitorId"));
            String sessionId = Json.asString(payload.get("sessionId"));
            String userMessage = Json.asString(payload.get("userMessage")).trim();
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

            Map<String, Long> stageTimings = new ConcurrentHashMap<>();
            long sendStartedAt = System.nanoTime();
            List<ConversationSnippet> shortTerm = memoryService.getShortTermContext(new ArrayList<>(sessionMessages), 18);
            TimeContext timeContext = realityContextService.buildTimeContext(visitor, messageCreatedAt);
            WeatherContext weatherContext = realityContextService.buildWeatherContext(visitor, messageCreatedAt);
            long dialogueStartedAt = System.nanoTime();
            DialogueContinuityState dialogueContinuity = dialogueContinuityService.update(
                    session.dialogueContinuityState,
                    userMessage,
                    shortTerm,
                    session.sceneState,
                    messageCreatedAt
            );
            recordStageTiming("chat_send", stageTimings, "dialogue_continuity", elapsedMillis(dialogueStartedAt));
            long bootstrapIntentStartedAt = System.nanoTime();
            IntentState bootstrapIntentState = intentInferenceService.infer(
                    userMessage,
                    shortTerm,
                    session.relationshipState,
                    session.sceneState,
                    session.tensionState,
                    session.memorySummary,
                    messageCreatedAt,
                    null
            );
            recordStageTiming("chat_send", stageTimings, "intent_bootstrap", elapsedMillis(bootstrapIntentStartedAt));
            boolean useCombinedRemoteTurnAnalysis = !inspection.blocked && shouldUseCombinedRemoteTurnAnalysis();
            CompletableFuture<TimedValue<SemanticRuntimeDecision>> semanticFuture = inspection.blocked || useCombinedRemoteTurnAnalysis
                    ? null
                    : runTimedAsyncStage("chat_send", stageTimings, "semantic_runtime", () -> semanticRuntimeAgentService.analyze(
                            userMessage,
                            shortTerm,
                            session.sceneState,
                            session.relationshipState,
                            session.tensionState,
                            session.memorySummary,
                            timeContext,
                            weatherContext,
                            "user_turn",
                            messageCreatedAt
                    ));
            SemanticRuntimeDecision semanticDecision = null;
            IntentState intentState = bootstrapIntentState;
            StoryEvent triggeredEvent = null;
            PlotGateDecision plotGateDecision = emptyPlotGate(messageCreatedAt);
            TurnEvaluation relationship = new TurnEvaluation(session.relationshipState, new Delta());
            EmotionState nextEmotion = affectionJudgeService.normalizeEmotion(session.emotionState, messageCreatedAt);
            RelationalTensionState nextTension = boundaryResponseService.normalize(session.tensionState, messageCreatedAt);
            long bootstrapTurnContextStartedAt = System.nanoTime();
            TurnContext turnContext = buildSharedTurnContext(
                    intentState,
                    relationship,
                    session.sceneState,
                    "user_turn",
                    messageCreatedAt,
                    dialogueContinuity
            );
            recordStageTiming("chat_send", stageTimings, "turn_context_bootstrap", elapsedMillis(bootstrapTurnContextStartedAt));
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
            RealityEnvelope realityEnvelope = realityGuardService.buildEnvelope(timeContext, weatherContext, plotDecision.nextSceneState, searchGroundingSummary, semanticDecision);
            UncertaintyState uncertaintyState = buildUncertaintyState(intentState, searchDecision, plotGateDecision, messageCreatedAt);
            RealityAudit realityAudit = passRealityAudit();
            HumanizationAudit humanizationAudit = emptyHumanizationAudit();
            List<MemoryIntentBinding> memoryIntentBindings = buildMemoryIntentBindings(memoryUsePlan, null);

            if (inspection.blocked) {
                nextEmotion = affectionJudgeService.coolDownForSilence(session.emotionState, messageCreatedAt);
                recordStageTiming("chat_send", stageTimings, "input_safety_block", 0L);
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
                RemoteTurnAnalysisResult combinedTurnAnalysis = null;
                if (useCombinedRemoteTurnAnalysis) {
                    try {
                        TurnContext analysisTurnContext = turnContext;
                        PlotState analysisPlotState = plotDirectorService.normalizePlot(session.plotState, messageCreatedAt);
                        int analysisCurrentTurn = session.userTurnCount + 1;
                        int analysisPlotGap = Math.max(0, analysisCurrentTurn - analysisPlotState.lastPlotTurn);
                        int analysisPlotSignal = plotDirectorService.estimateSignal(
                                userMessage,
                                session.memorySummary,
                                nextEmotion,
                                weatherContext,
                                timeContext,
                                "user_turn",
                                turnContext,
                                analysisCurrentTurn,
                                analysisPlotState.forcePlotAtTurn
                        );
                        combinedTurnAnalysis = runTimedStage("chat_send", stageTimings, "combined_remote_analysis", () -> remoteTurnAnalysisService.analyzeUserTurn(
                                userMessage,
                                shortTerm,
                                session.sceneState,
                                session.relationshipState,
                                session.emotionState,
                                session.tensionState,
                                session.memorySummary,
                                timeContext,
                                weatherContext,
                                dialogueContinuity,
                                bootstrapIntentState,
                                analysisTurnContext,
                                scoringEvent,
                                relationshipService,
                                "user_turn",
                                messageCreatedAt,
                                analysisCurrentTurn,
                                analysisPlotGap,
                                analysisPlotState.forcePlotAtTurn,
                                analysisPlotSignal
                        )).value;
                    } catch (Exception ex) {
                        combinedTurnAnalysis = null;
                        if (semanticFuture == null) {
                            semanticFuture = runTimedAsyncStage("chat_send", stageTimings, "semantic_runtime", () -> semanticRuntimeAgentService.analyze(
                                    userMessage,
                                    shortTerm,
                                    session.sceneState,
                                    session.relationshipState,
                                    session.tensionState,
                                    session.memorySummary,
                                    timeContext,
                                    weatherContext,
                                    "user_turn",
                                    messageCreatedAt
                            ));
                        }
                    }
                }

                AffectionScoreResult affectionScoreResult;
                if (combinedTurnAnalysis != null) {
                    semanticDecision = combinedTurnAnalysis.semanticDecision;
                    long intentStartedAt = System.nanoTime();
                    intentState = intentInferenceService.infer(
                            userMessage,
                            shortTerm,
                            session.relationshipState,
                            session.sceneState,
                            session.tensionState,
                            session.memorySummary,
                            messageCreatedAt,
                            semanticDecision
                    );
                    recordStageTiming("chat_send", stageTimings, "intent_inference", elapsedMillis(intentStartedAt));
                    affectionScoreResult = combinedTurnAnalysis.affectionScoreResult;
                    relationship = affectionScoreResult.turnEvaluation;
                    nextEmotion = affectionScoreResult.nextEmotion;
                    long boundaryStartedAt = System.nanoTime();
                    nextTension = boundaryResponseService.evaluate(
                            userMessage,
                            session.tensionState,
                            relationship,
                            temperamentProfileFor(agent),
                            messageCreatedAt
                    );
                    recordStageTiming("chat_send", stageTimings, "boundary_response", elapsedMillis(boundaryStartedAt));
                    long finalTurnContextStartedAt = System.nanoTime();
                    turnContext = buildSharedTurnContext(
                            intentState,
                            relationship,
                            session.sceneState,
                            "user_turn",
                            messageCreatedAt,
                            dialogueContinuity
                    );
                    recordStageTiming("chat_send", stageTimings, "turn_context_final", elapsedMillis(finalTurnContextStartedAt));
                    EmotionState plotEmotion = nextEmotion;
                    RelationshipState plotRelationshipState = relationship.nextState;
                    TurnContext plotTurnContext = turnContext;
                    long plotDecisionStartedAt = System.nanoTime();
                    plotDecision = plotDirectorService.decide(
                            session,
                            userMessage,
                            plotEmotion,
                            plotRelationshipState,
                            session.memorySummary,
                            timeContext,
                            weatherContext,
                            "user_turn",
                            messageCreatedAt,
                            plotTurnContext,
                            combinedTurnAnalysis.plotDirectorDecision
                    );
                    recordStageTiming("chat_send", stageTimings, "plot_director", elapsedMillis(plotDecisionStartedAt));
                } else {
                    CompletableFuture<TimedValue<AffectionScoreResult>> affectionFuture = runTimedAsyncStage("chat_send", stageTimings, "affection_judge", () -> affectionJudgeService.evaluateTurn(
                            userMessage,
                            session.relationshipState,
                            session.emotionState,
                            scoringEvent,
                            session.memorySummary,
                            relationshipService,
                            messageCreatedAt,
                            bootstrapIntentState,
                            session.sceneState,
                            session.tensionState,
                            dialogueContinuity,
                            "user_turn"
                    ));
                    semanticDecision = awaitStage(semanticFuture).value;
                    long intentStartedAt = System.nanoTime();
                    intentState = intentInferenceService.infer(
                            userMessage,
                            shortTerm,
                            session.relationshipState,
                            session.sceneState,
                            session.tensionState,
                            session.memorySummary,
                            messageCreatedAt,
                            semanticDecision
                    );
                    recordStageTiming("chat_send", stageTimings, "intent_inference", elapsedMillis(intentStartedAt));
                    affectionScoreResult = awaitStage(affectionFuture).value;
                    relationship = affectionScoreResult.turnEvaluation;
                    nextEmotion = affectionScoreResult.nextEmotion;
                    long boundaryStartedAt = System.nanoTime();
                    nextTension = boundaryResponseService.evaluate(
                            userMessage,
                            session.tensionState,
                            relationship,
                            temperamentProfileFor(agent),
                            messageCreatedAt
                    );
                    recordStageTiming("chat_send", stageTimings, "boundary_response", elapsedMillis(boundaryStartedAt));
                    long finalTurnContextStartedAt = System.nanoTime();
                    turnContext = buildSharedTurnContext(
                            intentState,
                            relationship,
                            session.sceneState,
                            "user_turn",
                            messageCreatedAt,
                            dialogueContinuity
                    );
                    recordStageTiming("chat_send", stageTimings, "turn_context_final", elapsedMillis(finalTurnContextStartedAt));
                    EmotionState plotEmotion = nextEmotion;
                    RelationshipState plotRelationshipState = relationship.nextState;
                    TurnContext plotTurnContext = turnContext;
                    CompletableFuture<TimedValue<PlotDecision>> plotDecisionFuture = runTimedAsyncStage("chat_send", stageTimings, "plot_director", () -> plotDirectorService.decide(
                            session,
                            userMessage,
                            plotEmotion,
                            plotRelationshipState,
                            session.memorySummary,
                            timeContext,
                            weatherContext,
                            "user_turn",
                            messageCreatedAt,
                            plotTurnContext
                    ));
                    plotDecision = awaitStage(plotDecisionFuture).value;
                }
                long longTermSummaryStartedAt = System.nanoTime();
                String longTermSummary = memoryService.getTieredSummaryText(session.memorySummary);
                recordStageTiming("chat_send", stageTimings, "long_term_summary", elapsedMillis(longTermSummaryStartedAt));
                long recallStartedAt = System.nanoTime();
                MemoryRecall recall = sanitizeMemoryRecall(memoryService.recallRelevantMemories(session.memorySummary, userMessage, 2));
                recordStageTiming("chat_send", stageTimings, "memory_recall", elapsedMillis(recallStartedAt));
                long userMoodStartedAt = System.nanoTime();
                String currentUserMood = memoryService.detectMood(userMessage);
                recordStageTiming("chat_send", stageTimings, "user_mood", elapsedMillis(userMoodStartedAt));
                long semanticSceneStartedAt = System.nanoTime();
                plotDecision = applySemanticSceneDecision(
                        session.sceneState,
                        plotDecision,
                        semanticDecision,
                        userMessage,
                        timeContext,
                        weatherContext,
                        session.userTurnCount,
                        messageCreatedAt
                );
                recordStageTiming("chat_send", stageTimings, "semantic_scene_merge", elapsedMillis(semanticSceneStartedAt));
                long plotMacroStartedAt = System.nanoTime();
                relationship = applyPlotMacroScore(
                        relationship,
                        plotDecision,
                        turnContext,
                        nextTension,
                        messageCreatedAt
                );
                recordStageTiming("chat_send", stageTimings, "plot_macro_score", elapsedMillis(plotMacroStartedAt));
                long plotGateStartedAt = System.nanoTime();
                plotGateDecision = plotGateService.decide(
                        candidateEvent,
                        session,
                        plotDecision.nextSceneState,
                        relationship.nextState,
                        nextTension,
                        messageCreatedAt
                );
                recordStageTiming("chat_send", stageTimings, "plot_gate", elapsedMillis(plotGateStartedAt));
                triggeredEvent = plotGateDecision.allowed ? candidateEvent : null;
                long memoryUsePlanStartedAt = System.nanoTime();
                memoryUsePlan = memoryService.planMemoryUse(session.memorySummary, userMessage, plotDecision.replySource, plotDecision.sceneFrame);
                recordStageTiming("chat_send", stageTimings, "memory_use_plan", elapsedMillis(memoryUsePlanStartedAt));
                memoryIntentBindings = buildMemoryIntentBindings(memoryUsePlan, recall);
                long searchDecisionStartedAt = System.nanoTime();
                searchDecision = searchDecisionService.decide(userMessage, plotDecision.replySource, plotDecision.nextSceneState, intentState, semanticDecision);
                recordStageTiming("chat_send", stageTimings, "search_decision", elapsedMillis(searchDecisionStartedAt));
                long searchGroundingStartedAt = System.nanoTime();
                searchGroundingSummary = realityGuardService.groundingFromDecision(searchDecision);
                recordStageTiming("chat_send", stageTimings, "search_grounding", elapsedMillis(searchGroundingStartedAt));
                long realityEnvelopeStartedAt = System.nanoTime();
                realityEnvelope = realityGuardService.buildEnvelope(timeContext, weatherContext, plotDecision.nextSceneState, searchGroundingSummary, semanticDecision);
                recordStageTiming("chat_send", stageTimings, "reality_envelope", elapsedMillis(realityEnvelopeStartedAt));
                long uncertaintyStartedAt = System.nanoTime();
                uncertaintyState = buildUncertaintyState(intentState, searchDecision, plotGateDecision, messageCreatedAt);
                recordStageTiming("chat_send", stageTimings, "uncertainty_state", elapsedMillis(uncertaintyStartedAt));
                long responsePlanStartedAt = System.nanoTime();
                responsePlan = responsePlanningService.plan(
                        intentState,
                        relationship.nextState,
                        nextEmotion,
                        nextTension,
                        plotGateDecision,
                        plotDecision.replySource,
                        messageCreatedAt
                );
                recordStageTiming("chat_send", stageTimings, "response_plan", elapsedMillis(responsePlanStartedAt));
                long initiativeStartedAt = System.nanoTime();
                initiativeDecision = initiativePolicyService.decide(
                        intentState,
                        responsePlan,
                        nextEmotion,
                        nextTension,
                        plotGateDecision,
                        plotDecision.replySource,
                        messageCreatedAt
                );
                recordStageTiming("chat_send", stageTimings, "initiative_policy", elapsedMillis(initiativeStartedAt));
                long responseCadenceStartedAt = System.nanoTime();
                String responseCadence = memoryService.determineResponseCadence(userMessage, relationship.nextState, triggeredEvent);
                recordStageTiming("chat_send", stageTimings, "response_cadence", elapsedMillis(responseCadenceStartedAt));
                String responseDirective = (memoryService.buildTieredResponseDirective(session.memorySummary, userMessage, relationship.nextState, triggeredEvent)
                        + " 当前记忆使用模式：" + memoryUsePlan.useMode
                        + "。原因：" + memoryUsePlan.relevanceReason).trim();

                long llmReplyStartedAt = System.nanoTime();
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
                        semanticDecision
                ));
                recordStageTiming("chat_send", stageTimings, "llm_reply", elapsedMillis(llmReplyStartedAt));

                long realityGuardStartedAt = System.nanoTime();
                RealityGuardResult guardResult = realityGuardService.auditAndRepair(
                        llmReply,
                        realityEnvelope,
                        userMessage,
                        plotDecision.nextSceneState
                );
                recordStageTiming("chat_send", stageTimings, "reality_guard", elapsedMillis(realityGuardStartedAt));
                llmReply = guardResult.reply;
                realityAudit = guardResult.realityAudit;
                long humanizationStartedAt = System.nanoTime();
                humanizationAudit = humanizationEvaluationService.evaluate(
                        userMessage,
                        llmReply,
                        intentState,
                        responsePlan,
                        memoryUsePlan,
                        realityAudit
                );
                recordStageTiming("chat_send", stageTimings, "humanization_audit", elapsedMillis(humanizationStartedAt));

                long outputSafetyStartedAt = System.nanoTime();
                InputInspection outputInspection = safetyService.inspectAssistantOutput(llmReply.replyText);
                recordStageTiming("chat_send", stageTimings, "assistant_output_safety", elapsedMillis(outputSafetyStartedAt));
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
            recordStageTiming("chat_send", stageTimings, "total", elapsedMillis(sendStartedAt));

            String replyCreatedAt = IsoTimes.now();
            ConversationMessage assistantEntry = new ConversationMessage();
            assistantEntry.id = ids("msg");
            assistantEntry.sessionId = session.id;
            assistantEntry.role = "assistant";
            assistantEntry.text = llmReply.speechText == null || llmReply.speechText.isBlank() ? llmReply.replyText : llmReply.speechText;
            assistantEntry.sceneText = llmReply.sceneText == null || llmReply.sceneText.isBlank() ? plotDecision.sceneText : llmReply.sceneText;
            assistantEntry.actionText = llmReply.actionText;
            assistantEntry.speechText = llmReply.speechText == null || llmReply.speechText.isBlank() ? llmReply.replyText : llmReply.speechText;
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
            session.lastSemanticRuntimeDecision = semanticDecision;
            session.dialogueContinuityState = dialogueContinuity;
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
            response.put("behavior_tags", relationship.behaviorTags);
            response.put("risk_flags", relationship.riskFlags);
            response.put("intent_state", intentStateMap(session.lastIntentState));
            response.put("response_plan", responsePlanMap(session.lastResponsePlan));
            response.put("humanization_audit", humanizationAuditMap(session.lastHumanizationAudit));
            response.put("reality_audit", realityAuditMap(session.lastRealityAudit));
            response.put("plot_gate_reason", plotGateDecisionMap(session.lastPlotGateDecision));
            response.put("turn_context", turnContextMap(session.lastTurnContext));
            response.put("semantic_runtime", semanticRuntimeMap(session.lastSemanticRuntimeDecision));
            response.put("dialogue_continuity", dialogueContinuityMap(session.dialogueContinuityState));
            response.put("plot_director_decision", plotDecision.plotDirectorReason);
            response.put("tension_state", tensionStateMap(session.tensionState));
            response.put("emotion_state", emotionStateMap(session.emotionState));
            response.put("plot_progress", plotStateMap(session.plotState));
            response.put("scene_state", sceneStateMap(session.sceneState));
            response.put("plot_arc_state", plotArcStateMap(session.plotArcState));
            response.put("scene_frame", session.plotState == null ? "" : session.plotState.sceneFrame);
            response.put("reply_source", plotDecision.replySource);
            response.put("run_status", session.plotArcState == null ? "" : session.plotArcState.runStatus);
            response.put("checkpoint_ready", session.plotArcState != null && session.plotArcState.checkpointReady);
            response.put("current_arc_index", session.plotArcState == null ? 1 : session.plotArcState.arcIndex);
            response.put("current_beat_index", session.plotArcState == null ? 0 : session.plotArcState.beatIndex);
            response.put("can_settle_score", session.plotArcState != null && session.plotArcState.canSettleScore);
            response.put("can_continue", session.plotArcState != null && session.plotArcState.canContinue);
            response.put("arc_summary_preview", arcSummaryMap(session.plotArcState == null ? null : session.plotArcState.latestArcSummary));
            response.put("stage_timings_ms", new TreeMap<>(stageTimings));
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
            Map<String, Long> stageTimings = new ConcurrentHashMap<>();
            long presenceStartedAt = System.nanoTime();
            TimeContext timeContext = realityContextService.buildTimeContext(visitor, nowIso);
            WeatherContext weatherContext = realityContextService.buildWeatherContext(visitor, nowIso);
            EmotionState nextEmotion = affectionJudgeService.coolDownForSilence(session.emotionState, nowIso);
            RelationalTensionState nextTension = boundaryResponseService.normalize(session.tensionState, nowIso);
            List<ConversationSnippet> shortTerm = memoryService.getShortTermContext(new ArrayList<>(sessionMessages), 18);
            long presenceDialogueStartedAt = System.nanoTime();
            DialogueContinuityState dialogueContinuity = dialogueContinuityService.normalize(session.dialogueContinuityState, nowIso);
            recordStageTiming("presence", stageTimings, "dialogue_continuity", elapsedMillis(presenceDialogueStartedAt));
            CompletableFuture<TimedValue<PlotDecision>> plotDecisionFuture = runTimedAsyncStage("presence", stageTimings, "plot_director", () -> plotDirectorService.decide(
                    session,
                    "",
                    nextEmotion,
                    session.relationshipState,
                    session.memorySummary,
                    timeContext,
                    weatherContext,
                    presenceResult.replySource,
                    nowIso
            ));
            PlotDecision plotDecision = awaitStage(plotDecisionFuture).value;
            long presenceMemoryUseStartedAt = System.nanoTime();
            MemoryUsePlan memoryUsePlan = memoryService.planMemoryUse(session.memorySummary, "", presenceResult.replySource, plotDecision.sceneFrame);
            recordStageTiming("presence", stageTimings, "memory_use_plan", elapsedMillis(presenceMemoryUseStartedAt));
            long presenceSemanticStartedAt = System.nanoTime();
            SemanticRuntimeDecision semanticDecision = semanticRuntimeAgentService.analyze(
                    "",
                    shortTerm,
                    plotDecision.nextSceneState,
                    session.relationshipState,
                    nextTension,
                    session.memorySummary,
                    timeContext,
                    weatherContext,
                    presenceResult.replySource,
                    nowIso
            );
            recordStageTiming("presence", stageTimings, "semantic_runtime", elapsedMillis(presenceSemanticStartedAt));
            long presenceIntentStartedAt = System.nanoTime();
            IntentState intentState = intentInferenceService.infer(
                    "",
                    shortTerm,
                    session.relationshipState,
                    plotDecision.nextSceneState,
                    nextTension,
                    session.memorySummary,
                    nowIso,
                    semanticDecision
            );
            recordStageTiming("presence", stageTimings, "intent_inference", elapsedMillis(presenceIntentStartedAt));
            PlotGateDecision plotGateDecision = emptyPlotGate(nowIso);
            long presenceResponsePlanStartedAt = System.nanoTime();
            ResponsePlan responsePlan = responsePlanningService.plan(
                    intentState,
                    session.relationshipState,
                    nextEmotion,
                    nextTension,
                    plotGateDecision,
                    presenceResult.replySource,
                    nowIso
            );
            recordStageTiming("presence", stageTimings, "response_plan", elapsedMillis(presenceResponsePlanStartedAt));
            long presenceInitiativeStartedAt = System.nanoTime();
            InitiativeDecision initiativeDecision = initiativePolicyService.decide(
                    intentState,
                    responsePlan,
                    nextEmotion,
                    nextTension,
                    plotGateDecision,
                    presenceResult.replySource,
                    nowIso
            );
            recordStageTiming("presence", stageTimings, "initiative_policy", elapsedMillis(presenceInitiativeStartedAt));
            SearchDecision searchDecision = new SearchDecision(false, "", "skip", "skip", false);
            long presenceGroundingStartedAt = System.nanoTime();
            SearchGroundingSummary searchGroundingSummary = realityGuardService.groundingFromDecision(searchDecision);
            recordStageTiming("presence", stageTimings, "search_grounding", elapsedMillis(presenceGroundingStartedAt));
            long presenceEnvelopeStartedAt = System.nanoTime();
            RealityEnvelope realityEnvelope = realityGuardService.buildEnvelope(timeContext, weatherContext, plotDecision.nextSceneState, searchGroundingSummary, semanticDecision);
            recordStageTiming("presence", stageTimings, "reality_envelope", elapsedMillis(presenceEnvelopeStartedAt));
            long presenceUncertaintyStartedAt = System.nanoTime();
            UncertaintyState uncertaintyState = buildUncertaintyState(intentState, searchDecision, plotGateDecision, nowIso);
            recordStageTiming("presence", stageTimings, "uncertainty_state", elapsedMillis(presenceUncertaintyStartedAt));
            List<MemoryIntentBinding> memoryIntentBindings = buildMemoryIntentBindings(memoryUsePlan, null);
            String responseDirective = (memoryService.buildTieredResponseDirective(session.memorySummary, "", session.relationshipState, null)
                    + " 当前这是角色主动发出的消息。reply_source=" + presenceResult.replySource
                    + "。记忆使用原因：" + memoryUsePlan.relevanceReason).trim();

            long presenceSummaryStartedAt = System.nanoTime();
            String longTermSummary = memoryService.getTieredSummaryText(session.memorySummary);
            recordStageTiming("presence", stageTimings, "long_term_summary", elapsedMillis(presenceSummaryStartedAt));
            long presenceLlmStartedAt = System.nanoTime();
            LlmResponse llmReply = llmClient.generateReply(new LlmRequest(
                    agent,
                    session.relationshipState,
                    shortTerm,
                    longTermSummary,
                    "none",
                    "",
                    session.memorySummary.lastUserMood,
                    "light_ping",
                    responseDirective,
                    null,
                    "",
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
                    semanticDecision
            ));
            recordStageTiming("presence", stageTimings, "llm_reply", elapsedMillis(presenceLlmStartedAt));

            long presenceGuardStartedAt = System.nanoTime();
            RealityGuardResult guardResult = realityGuardService.auditAndRepair(
                    llmReply,
                    realityEnvelope,
                    "",
                    plotDecision.nextSceneState
            );
            recordStageTiming("presence", stageTimings, "reality_guard", elapsedMillis(presenceGuardStartedAt));
            llmReply = guardResult.reply;
            RealityAudit realityAudit = guardResult.realityAudit;
            long presenceHumanizationStartedAt = System.nanoTime();
            HumanizationAudit humanizationAudit = humanizationEvaluationService.evaluate(
                    "",
                    llmReply,
                    intentState,
                    responsePlan,
                    memoryUsePlan,
                    realityAudit
            );
            recordStageTiming("presence", stageTimings, "humanization_audit", elapsedMillis(presenceHumanizationStartedAt));

            long presenceOutputSafetyStartedAt = System.nanoTime();
            InputInspection outputInspection = safetyService.inspectAssistantOutput(llmReply.replyText);
            recordStageTiming("presence", stageTimings, "assistant_output_safety", elapsedMillis(presenceOutputSafetyStartedAt));
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
            recordStageTiming("presence", stageTimings, "total", elapsedMillis(presenceStartedAt));

            ConversationMessage proactiveEntry = new ConversationMessage();
            proactiveEntry.id = ids("msg");
            proactiveEntry.sessionId = session.id;
            proactiveEntry.role = "assistant";
            proactiveEntry.text = llmReply.speechText == null || llmReply.speechText.isBlank() ? llmReply.replyText : llmReply.speechText;
            proactiveEntry.sceneText = llmReply.sceneText == null || llmReply.sceneText.isBlank() ? plotDecision.sceneText : llmReply.sceneText;
            proactiveEntry.actionText = llmReply.actionText;
            proactiveEntry.speechText = llmReply.speechText == null || llmReply.speechText.isBlank() ? llmReply.replyText : llmReply.speechText;
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
            session.lastSemanticRuntimeDecision = semanticDecision;
            session.dialogueContinuityState = dialogueContinuity;
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
            response.put("semantic_runtime", semanticRuntimeMap(session.lastSemanticRuntimeDecision));
            response.put("dialogue_continuity", dialogueContinuityMap(session.dialogueContinuityState));
            response.put("run_status", session.plotArcState == null ? "" : session.plotArcState.runStatus);
            response.put("checkpoint_ready", session.plotArcState != null && session.plotArcState.checkpointReady);
            response.put("arc_summary_preview", arcSummaryMap(session.plotArcState == null ? null : session.plotArcState.latestArcSummary));
            response.put("stage_timings_ms", new TreeMap<>(stageTimings));
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
        payload.put("lastSemanticRuntimeDecision", semanticRuntimeMap(session.lastSemanticRuntimeDecision));
        payload.put("dialogueContinuityState", dialogueContinuityMap(session.dialogueContinuityState));
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
        context.behaviorTags = relationship == null || relationship.behaviorTags == null ? new ArrayList<>() : new ArrayList<>(relationship.behaviorTags);
        context.riskFlags = relationship == null || relationship.riskFlags == null ? new ArrayList<>() : new ArrayList<>(relationship.riskFlags);
        context.sceneLocation = sceneState == null ? "" : blankTo(sceneState.location, "");
        context.interactionMode = sceneState == null ? "" : blankTo(sceneState.interactionMode, "");
        context.updatedAt = nowIso;
        return context;
    }

    private TurnContext buildSharedTurnContext(
            IntentState intentState,
            TurnEvaluation relationship,
            SceneState sceneState,
            String replySource,
            String nowIso,
            DialogueContinuityState continuity
    ) {
        TurnContext context = buildTurnContext(intentState, relationship, sceneState, replySource, nowIso);
        applyContinuityToTurnContext(context, continuity);
        return context;
    }

    private void applyContinuityToTurnContext(TurnContext context, DialogueContinuityState continuity) {
        if (context == null || continuity == null) {
            return;
        }
        context.continuityObjective = blankTo(continuity.currentObjective, "");
        context.continuityAcceptedPlan = blankTo(continuity.acceptedPlan, "");
        context.continuityNextBestMove = blankTo(continuity.nextBestMove, "");
        context.continuityGuards = continuity.mustNotContradict == null ? new ArrayList<>() : new ArrayList<>(continuity.mustNotContradict);
    }

    private PlotDecision applySemanticSceneDecision(
            SceneState previousScene,
            PlotDecision plotDecision,
            SemanticRuntimeDecision semanticDecision,
            String userMessage,
            TimeContext timeContext,
            WeatherContext weatherContext,
            int currentTurn,
            String nowIso
    ) {
        if (plotDecision == null || semanticDecision == null) {
            return plotDecision;
        }
        boolean hasSemanticScene = semanticDecision.sceneTransition
                || semanticDecision.sceneLocation != null && !semanticDecision.sceneLocation.isBlank()
                || semanticDecision.interactionMode != null && !semanticDecision.interactionMode.isBlank();
        if (!hasSemanticScene) {
            return plotDecision;
        }
        SceneState nextScene = sceneDirectorService.evolve(
                previousScene,
                userMessage,
                timeContext,
                weatherContext,
                currentTurn,
                nowIso,
                semanticDecision
        );
        String sceneText = plotDecision.sceneText;
        if (semanticDecision.sceneTransition) {
            sceneText = sceneDirectorService.buildSceneText(previousScene, nextScene);
        }
        return new PlotDecision(
                plotDecision.nextPlotState,
                plotDecision.nextPlotArcState,
                nextScene,
                plotDecision.advanced,
                plotDecision.replySource,
                plotDecision.sceneFrame,
                sceneText,
                plotDecision.plotDirectorReason
        );
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
        nextState.relationshipStage = relationshipStageForScore(nextState.affectionScore);
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
        boolean stageChanged = relationship.stageChanged || !Objects.equals(previousStage, nextState.relationshipStage);
        String stageProgress = plotDecision.nextPlotState == null ? relationship.stageProgress : plotDecision.nextPlotState.plotProgress;

        if (turnContext != null) {
            turnContext.closenessDelta = delta.closeness;
            turnContext.trustDelta = delta.trust;
            turnContext.resonanceDelta = delta.resonance;
            turnContext.affectionDeltaTotal = delta.total;
            turnContext.behaviorTags = behaviorTags;
            turnContext.updatedAt = nowIso;
        }

        return new TurnEvaluation(
                nextState,
                delta,
                behaviorTags,
                relationship.riskFlags == null ? List.of() : relationship.riskFlags,
                stageChanged,
                blankTo(stageProgress, ""),
                nextState.relationshipFeedback
        );
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

    private String relationshipStageForScore(int score) {
        if (score >= 76) return "\u786e\u8ba4\u5173\u7cfb";
        if (score >= 50) return "\u9760\u8fd1";
        if (score >= 28) return "\u5fc3\u52a8";
        if (score >= 12) return "\u5347\u6e29";
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
        map.put("behaviorTags", context.behaviorTags == null ? List.of() : context.behaviorTags);
        map.put("riskFlags", context.riskFlags == null ? List.of() : context.riskFlags);
        map.put("sceneLocation", blankTo(context.sceneLocation, ""));
        map.put("interactionMode", blankTo(context.interactionMode, ""));
        map.put("plotGap", context.plotGap);
        map.put("plotSignal", context.plotSignal);
        map.put("plotDirectorAction", blankTo(context.plotDirectorAction, ""));
        map.put("plotWhyNow", blankTo(context.plotWhyNow, ""));
        map.put("plotDirectorConfidence", context.plotDirectorConfidence);
        map.put("plotRiskIfAdvance", blankTo(context.plotRiskIfAdvance, ""));
        map.put("requiredUserSignal", blankTo(context.requiredUserSignal, ""));
        map.put("continuityObjective", blankTo(context.continuityObjective, ""));
        map.put("continuityAcceptedPlan", blankTo(context.continuityAcceptedPlan, ""));
        map.put("continuityNextBestMove", blankTo(context.continuityNextBestMove, ""));
        map.put("continuityGuards", context.continuityGuards == null ? List.of() : context.continuityGuards);
        map.put("updatedAt", blankTo(context.updatedAt, ""));
        return map;
    }

    private Map<String, Object> semanticRuntimeMap(SemanticRuntimeDecision decision) {
        if (decision == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("source", blankTo(decision.source, ""));
        map.put("remoteUsed", decision.remoteUsed);
        map.put("reason", blankTo(decision.reason, ""));
        map.put("primaryIntent", blankTo(decision.primaryIntent, ""));
        map.put("secondaryIntent", blankTo(decision.secondaryIntent, ""));
        map.put("emotion", blankTo(decision.emotion, ""));
        map.put("clarity", blankTo(decision.clarity, ""));
        map.put("sceneLocation", blankTo(decision.sceneLocation, ""));
        map.put("sceneSubLocation", blankTo(decision.sceneSubLocation, ""));
        map.put("interactionMode", blankTo(decision.interactionMode, ""));
        map.put("sceneTransition", decision.sceneTransition);
        map.put("sceneSummary", blankTo(decision.sceneSummary, ""));
        map.put("searchMode", blankTo(decision.searchMode, ""));
        map.put("searchReason", blankTo(decision.searchReason, ""));
        map.put("mustNotGuess", decision.mustNotGuess);
        map.put("directAnswerPolicy", blankTo(decision.directAnswerPolicy, ""));
        map.put("sceneAtmosphere", blankTo(decision.sceneAtmosphere, ""));
        map.put("updatedAt", blankTo(decision.updatedAt, ""));
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

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
        String prefix = switch (agentId) {
            case "healing" -> "她抬眼看向你，语气更轻了一点。";
            case "lively" -> "她先是一怔，随后笑意慢慢亮起来。";
            case "cool" -> "他沉默了半拍，但没有把视线移开。";
            case "artsy" -> "她像是把这句回应轻轻收进了晚风里。";
            default -> "他把你的回应稳稳接住了。";
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
