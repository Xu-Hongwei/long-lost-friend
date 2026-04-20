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
            result.add(Map.of(
                    "id", agent.id,
                    "name", agent.name,
                    "archetype", agent.archetype,
                    "tagline", agent.tagline,
                    "palette", agent.palette,
                    "avatarGlyph", agent.avatarGlyph,
                    "bio", agent.bio,
                    "likes", agent.likes
            ));
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
        summary.lastUserMood = "neutral";
        summary.lastUserIntent = "chat";
        summary.lastResponseCadence = "steady_flow";
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
        if (next.lastUserMood == null || next.lastUserMood.isBlank()) {
            next.lastUserMood = "neutral";
        }
        if (next.lastUserIntent == null || next.lastUserIntent.isBlank()) {
            next.lastUserIntent = "chat";
        }
        if (next.lastResponseCadence == null || next.lastResponseCadence.isBlank()) {
            next.lastResponseCadence = "steady_flow";
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
            result.add(new ConversationSnippet(message.role, message.text));
        }
        return result;
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
        next.lastUserMood = summary.lastUserMood;
        next.lastUserIntent = summary.lastUserIntent;
        next.lastResponseCadence = summary.lastResponseCadence;
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
        this.repository = repository;
        this.agentConfigService = agentConfigService;
        this.memoryService = memoryService;
        this.relationshipService = relationshipService;
        this.eventEngine = eventEngine;
        this.llmClient = llmClient;
        this.safetyService = safetyService;
        this.analyticsService = analyticsService;
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
                state.visitors.add(visitor);
            } else {
                visitor.lastActiveAt = nowIso;
                visitor.initCount += 1;
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
            Instant now = Instant.now();

            SessionRecord session = findSession(state, sessionId);
            validateSessionOwner(session, visitorId, now);
            ensureSessionState(session);

            if (session.pendingChoiceEventId != null && !session.pendingChoiceEventId.isBlank()) {
                throw ApiException.badRequest("PENDING_CHOICE", "PENDING_CHOICE");
            }

            AgentProfile agent = requireAgent(session.agentId);
            List<ConversationMessage> sessionMessages = getSessionMessages(state, session.id);
            InputInspection inspection = safetyService.inspectUserInput(userMessage, sessionMessages);
            String messageCreatedAt = IsoTimes.now();

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
            state.messages.add(userEntry);

            StoryEvent triggeredEvent = null;
            TurnEvaluation relationship = new TurnEvaluation(session.relationshipState, new Delta());
            LlmResponse llmReply;

            if (inspection.blocked) {
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
                triggeredEvent = eventEngine.findTriggeredEvent(agent, session, userMessage);
                StoryEvent scoringEvent = triggeredEvent != null && triggeredEvent.keyChoiceEvent ? null : triggeredEvent;
                relationship = relationshipService.evaluateTurn(userMessage, session.relationshipState, scoringEvent, session.memorySummary);

                List<ConversationSnippet> shortTerm = memoryService.getShortTermContext(new ArrayList<>(sessionMessages), 18);
                String longTermSummary = memoryService.getTieredSummaryText(session.memorySummary);
                MemoryRecall recall = memoryService.recallRelevantMemories(session.memorySummary, userMessage, 2);
                String currentUserMood = memoryService.detectMood(userMessage);
                String responseCadence = memoryService.determineResponseCadence(userMessage, relationship.nextState, triggeredEvent);
                String responseDirective = memoryService.buildTieredResponseDirective(session.memorySummary, userMessage, relationship.nextState, triggeredEvent);

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
                        userMessage
                ));

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
            assistantEntry.text = llmReply.replyText;
            assistantEntry.createdAt = replyCreatedAt;
            assistantEntry.emotionTag = llmReply.emotionTag;
            assistantEntry.confidenceStatus = llmReply.confidenceStatus;
            assistantEntry.tokenUsage = llmReply.tokenUsage;
            assistantEntry.fallbackUsed = llmReply.fallbackUsed;
            assistantEntry.triggeredEventId = triggeredEvent == null ? null : triggeredEvent.id;
            assistantEntry.affectionDelta = relationship.affectionDelta.total;
            state.messages.add(assistantEntry);

            session.lastActiveAt = replyCreatedAt;
            session.memoryExpireAt = memoryService.createMemoryExpiry(Instant.parse(replyCreatedAt));
            session.userTurnCount += 1;
            session.relationshipState = relationship.nextState;
            if (!inspection.blocked) {
                session.memorySummary = memoryService.updateTieredSummary(
                        session.memorySummary,
                        userMessage,
                        triggeredEvent,
                        relationship.nextState.relationshipStage,
                        replyCreatedAt
                );
            }

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

            analyticsService.recordEvent(state, "chat_turn", Map.of(
                    "visitorId", visitorId,
                    "sessionId", session.id,
                    "agentId", session.agentId,
                    "triggeredEventId", triggeredEvent == null ? "" : triggeredEvent.id,
                    "fallbackUsed", llmReply.fallbackUsed
            ));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("reply_text", llmReply.replyText);
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
            state.messages.add(choiceEntry);

            ConversationMessage assistantEntry = new ConversationMessage();
            assistantEntry.id = ids("msg");
            assistantEntry.sessionId = session.id;
            assistantEntry.role = "assistant";
            assistantEntry.text = buildChoiceReply(agent, event, selectedChoice, nextState);
            assistantEntry.createdAt = nowIso;
            assistantEntry.emotionTag = "choice_result";
            assistantEntry.confidenceStatus = "system";
            assistantEntry.tokenUsage = 0;
            assistantEntry.fallbackUsed = false;
            assistantEntry.triggeredEventId = event.id;
            state.messages.add(assistantEntry);

            session.relationshipState = nextState;
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
            response.put("relationship_stage", nextState.relationshipStage);
            response.put("relationship_feedback", nextState.relationshipFeedback);
            response.put("ending_candidate", nextState.endingCandidate);
            response.put("interaction_mode", "chat");
            response.put("choices", List.of());
            response.put("event_context", null);
            response.put("triggered_event", eventMap(event));
            return response;
        });
    }

    private Map<String, Object> buildSessionPayload(AppState state, String sessionId) {
        SessionRecord session = findSession(state, sessionId);
        AgentProfile agent = requireAgent(session.agentId);
        ensureSessionState(session);

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
        memoryMap.put("lastUserMood", session.memorySummary.lastUserMood);
        memoryMap.put("lastUserIntent", session.memorySummary.lastUserIntent);
        memoryMap.put("lastResponseCadence", session.memorySummary.lastResponseCadence);
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
        payload.put("memoryExpireAt", session.memoryExpireAt);
        payload.put("userTurnCount", session.userTurnCount);
        payload.put("history", history);
        payload.put("pendingChoiceEventId", session.pendingChoiceEventId);
        payload.put("pendingChoices", serializeChoices(session.pendingChoices));
        payload.put("pendingEventContext", session.pendingEventContext);
        return payload;
    }

    private SessionRecord findSession(AppState state, String sessionId) {
        return state.sessions.stream()
                .filter(item -> sessionId.equals(item.id))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "SESSION_NOT_FOUND"));
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
        map.put("createdAt", message.createdAt);
        map.put("emotionTag", message.emotionTag);
        map.put("confidenceStatus", message.confidenceStatus);
        map.put("tokenUsage", message.tokenUsage);
        map.put("fallbackUsed", message.fallbackUsed);
        map.put("triggeredEventId", message.triggeredEventId);
        map.put("affectionDelta", message.affectionDelta);
        return map;
    }

    private String buildChoiceReply(AgentProfile agent, StoryEvent event, ChoiceOption choice, RelationshipState state) {
        String prefix = switch (agent.id) {
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
