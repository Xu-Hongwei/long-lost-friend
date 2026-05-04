package com.campuspulse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class WorldInfoService {
    private static final int MAX_ACTIVATIONS = 4;
    private static final int MAX_DYNAMIC_EVENTS = 6;

    private final Path configFile;

    WorldInfoService() {
        this(Path.of("data").resolve("world-info.json"));
    }

    WorldInfoService(AppConfig config) {
        this(config == null ? Path.of("data").resolve("world-info.json") : config.rootDir.resolve("data").resolve("world-info.json"));
    }

    WorldInfoService(Path configFile) {
        this.configFile = configFile;
    }

    List<WorldInfoActivation> activate(
            AgentProfile agent,
            SessionRecord session,
            String userMessage,
            List<ConversationSnippet> recentContext,
            TurnContext turnContext
    ) {
        List<WorldInfoActivation> activations = new ArrayList<>();
        String scanText = buildScanText(userMessage, recentContext, 6);
        List<String> signalTags = signalTags(session, turnContext);
        for (WorldInfoEntry entry : loadEntries()) {
            if (entry == null || !entry.enabled || blankTo(entry.content, "").isBlank()) {
                continue;
            }
            if (!agentAllowed(entry, agent) || !stageAllowed(entry, session) || !affectionAllowed(entry, session)) {
                continue;
            }
            ActivationScore score = score(entry, scanText, userMessage, signalTags, session, turnContext);
            if (score.value <= 0) {
                continue;
            }
            WorldInfoActivation activation = new WorldInfoActivation();
            activation.id = blankTo(entry.id, "world_info_" + Math.abs(entry.content.hashCode()));
            activation.title = blankTo(entry.title, "世界信息");
            activation.content = entry.content;
            activation.source = score.reason;
            activation.matchedKeywords = score.keywords;
            activation.tags = entry.tags == null ? new ArrayList<>() : new ArrayList<>(entry.tags);
            activation.score = score.value;
            activation.priority = entry.priority;
            activation.depth = entry.depth;
            activation.role = blankTo(entry.role, "system");
            activation.eventCandidate = entry.eventCandidate != null;
            activations.add(activation);
        }
        activations.sort(Comparator
                .comparingInt((WorldInfoActivation item) -> item.score)
                .thenComparingInt(item -> item.priority)
                .reversed());
        return new ArrayList<>(activations.subList(0, Math.min(MAX_ACTIVATIONS, activations.size())));
    }

    void refreshStoryCandidates(AgentProfile agent, SessionRecord session, List<WorldInfoActivation> activations) {
        if (session == null || activations == null || activations.isEmpty()) {
            return;
        }
        if (session.dynamicStoryEvents == null) {
            session.dynamicStoryEvents = new ArrayList<>();
        }
        Map<String, WorldInfoEntry> entries = new LinkedHashMap<>();
        for (WorldInfoEntry entry : loadEntries()) {
            if (entry != null && entry.id != null) {
                entries.put(entry.id, entry);
            }
        }
        for (WorldInfoActivation activation : activations) {
            if (activation == null || !activation.eventCandidate) {
                continue;
            }
            WorldInfoEntry entry = entries.get(activation.id);
            if (entry == null || entry.eventCandidate == null) {
                continue;
            }
            StoryEvent event = toStoryEvent(agent, session, entry, activation);
            if (event != null) {
                addOrReplaceEvent(session, event);
            }
        }
    }

    private StoryEvent toStoryEvent(AgentProfile agent, SessionRecord session, WorldInfoEntry entry, WorldInfoActivation activation) {
        WorldInfoEventSpec spec = entry.eventCandidate;
        if (spec == null) {
            return null;
        }
        String agentId = agent == null ? "agent" : blankTo(agent.id, "agent");
        int currentTurn = session == null ? 0 : session.userTurnCount + 1;
        int currentScore = session == null || session.relationshipState == null ? 0 : session.relationshipState.affectionScore;
        int bonus = clamp(spec.affectionBonus <= 0 ? 2 : spec.affectionBonus, 1, 4);
        int minAffection = Math.max(0, spec.minAffectionOffset == 0 ? entry.minAffection : currentScore + spec.minAffectionOffset);
        String category = normalizeCategory(spec.category);
        List<String> keywords = entry.keywordsAny == null || entry.keywordsAny.isEmpty()
                ? activation.matchedKeywords
                : new ArrayList<>(entry.keywordsAny);
        if (keywords == null || keywords.isEmpty()) {
            keywords = List.of(blankTo(entry.title, "世界信息"));
        }
        String id = "world_" + sanitizeId(agentId + "_" + blankTo(entry.id, String.valueOf(entry.content.hashCode())));
        String title = blankTo(spec.title, blankTo(entry.title, "世界信息候选"));
        String theme = blankTo(spec.theme, entry.content);
        String nextDirection = "把这个世界信息只当作未来可自然提起的候选，不要压过用户当前问题。";
        EventEffect success = new EventEffect(
                Math.max(1, bonus / 2),
                Math.max(0, bonus / 2),
                bonus,
                "世界信息候选",
                "这条候选来自可配置 WorldInfo，不是硬剧情命令。",
                nextDirection,
                false
        );
        EventEffect neutral = new EventEffect(
                0,
                0,
                Math.max(1, bonus - 1),
                "世界信息候选",
                "先把背景线索留住，等用户给出更明确的共同方向。",
                nextDirection,
                false
        );
        EventEffect fail = new EventEffect(
                0,
                -1,
                -1,
                "世界信息候选",
                "这条背景被使用得太用力，需要退回当前话题。",
                "先回答用户当前真正问的内容。",
                false
        );
        return new StoryEvent(
                id,
                title,
                currentTurn + Math.max(1, spec.unlockAfterTurns),
                minAffection,
                theme,
                keywords,
                bonus,
                category,
                entry.stageRange == null || entry.stageRange.isEmpty() ? allStages() : new ArrayList<>(entry.stageRange),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                Math.max(4, entry.priority / 10),
                Math.max(2, spec.cooldown),
                List.of(),
                success,
                neutral,
                fail,
                List.of(),
                false,
                nextDirection
        );
    }

    private void addOrReplaceEvent(SessionRecord session, StoryEvent event) {
        for (int index = 0; index < session.dynamicStoryEvents.size(); index++) {
            StoryEvent existing = session.dynamicStoryEvents.get(index);
            if (existing != null && event.id.equals(existing.id)) {
                session.dynamicStoryEvents.set(index, event);
                return;
            }
        }
        session.dynamicStoryEvents.add(0, event);
        while (session.dynamicStoryEvents.size() > MAX_DYNAMIC_EVENTS) {
            session.dynamicStoryEvents.remove(session.dynamicStoryEvents.size() - 1);
        }
    }

    private ActivationScore score(
            WorldInfoEntry entry,
            String scanText,
            String userMessage,
            List<String> signalTags,
            SessionRecord session,
            TurnContext turnContext
    ) {
        List<String> matched = new ArrayList<>();
        String lowerScan = blankTo(scanText, "").toLowerCase();
        String lowerUser = blankTo(userMessage, "").toLowerCase();
        for (String keyword : entry.keywordsAny == null ? List.<String>of() : entry.keywordsAny) {
            String safe = blankTo(keyword, "").trim().toLowerCase();
            if (safe.length() < 2) {
                continue;
            }
            if (lowerScan.contains(safe)) {
                matched.add(keyword);
            }
        }
        int tagHits = 0;
        for (String tag : entry.tags == null ? List.<String>of() : entry.tags) {
            if (signalTags.contains(tag)) {
                tagHits++;
            }
        }
        if (matched.isEmpty() && tagHits == 0) {
            return ActivationScore.none();
        }
        boolean globalEntry = entry.agentIds == null || entry.agentIds.isEmpty() || entry.agentIds.contains("*");
        if (matched.isEmpty() && (globalEntry || entry.priority < 70)) {
            return ActivationScore.none();
        }
        if (globalEntry) {
            boolean currentUserHit = false;
            for (String keyword : matched) {
                if (lowerUser.contains(keyword.toLowerCase())) {
                    currentUserHit = true;
                    break;
                }
            }
            if (!currentUserHit) {
                return ActivationScore.none();
            }
        }

        int value = Math.max(1, entry.priority) + matched.size() * 12 + tagHits * 6;
        for (String keyword : matched) {
            if (lowerUser.contains(keyword.toLowerCase())) {
                value += 8;
            }
        }
        String sceneText = session == null || session.sceneState == null
                ? ""
                : (blankTo(session.sceneState.location, "") + " " + blankTo(session.sceneState.sceneSummary, "")).toLowerCase();
        for (String keyword : matched) {
            if (!sceneText.isBlank() && sceneText.contains(keyword.toLowerCase())) {
                value += 4;
            }
        }
        String moveTarget = turnContext == null ? "" : blankTo(turnContext.sceneMoveTarget, "").toLowerCase();
        for (String keyword : matched) {
            if (!moveTarget.isBlank() && moveTarget.contains(keyword.toLowerCase())) {
                value += 6;
            }
        }
        String reason = (matched.isEmpty() ? "tag_signal:" + String.join(",", signalTags) : "keyword_signal:" + String.join(",", matched))
                + "; world_info_is_candidate_not_order";
        return new ActivationScore(value, matched, reason);
    }

    private List<String> signalTags(SessionRecord session, TurnContext turnContext) {
        List<String> tags = new ArrayList<>();
        if (turnContext != null) {
            addTag(tags, turnContext.primaryIntent);
            addTag(tags, turnContext.secondaryIntent);
            addTag(tags, turnContext.turnMission);
            addTag(tags, turnContext.sceneMoveKind);
            addTag(tags, turnContext.userReplyAct);
            if (turnContext.localGuards != null && !turnContext.localGuards.isEmpty()) {
                addTag(tags, "guarded");
            }
        }
        if (session != null && session.sceneState != null) {
            addTag(tags, session.sceneState.location);
            addTag(tags, session.sceneState.interactionMode);
        }
        if (session != null && session.relationshipState != null) {
            addTag(tags, session.relationshipState.relationshipStage);
        }
        return tags;
    }

    private void addTag(List<String> tags, String value) {
        String safe = blankTo(value, "").trim();
        if (!safe.isBlank() && !tags.contains(safe)) {
            tags.add(safe);
        }
    }

    private String buildScanText(String userMessage, List<ConversationSnippet> recentContext, int maxContext) {
        StringBuilder builder = new StringBuilder(blankTo(userMessage, ""));
        int count = 0;
        if (recentContext != null) {
            for (int index = recentContext.size() - 1; index >= 0 && count < maxContext; index--) {
                ConversationSnippet snippet = recentContext.get(index);
                if (snippet == null || blankTo(snippet.text, "").isBlank()) {
                    continue;
                }
                if (!"user".equals(snippet.role)) {
                    continue;
                }
                builder.append('\n').append(snippet.role).append(": ").append(snippet.text);
                count++;
            }
        }
        return builder.toString();
    }

    private List<WorldInfoEntry> loadEntries() {
        if (configFile == null || !Files.exists(configFile)) {
            return defaultEntries();
        }
        try {
            String raw = Files.readString(configFile, StandardCharsets.UTF_8);
            Map<String, Object> root = Json.asObject(Json.parse(raw));
            Object entriesValue = root.get("entries");
            List<Object> rawEntries = entriesValue instanceof List<?> ? Json.asArray(entriesValue) : List.of();
            List<WorldInfoEntry> entries = new ArrayList<>();
            for (Object rawEntry : rawEntries) {
                WorldInfoEntry entry = parseEntry(Json.asObject(rawEntry));
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return entries.isEmpty() ? defaultEntries() : entries;
        } catch (Exception ignored) {
            return defaultEntries();
        }
    }

    private WorldInfoEntry parseEntry(Map<String, Object> map) {
        WorldInfoEntry entry = new WorldInfoEntry();
        entry.id = blankTo(Json.asString(map.get("id")), "");
        entry.title = blankTo(Json.asString(map.get("title")), "");
        entry.content = blankTo(Json.asString(map.get("content")), "");
        entry.enabled = !map.containsKey("enabled") || Json.asBoolean(map.get("enabled"));
        entry.agentIds = stringList(map.get("agentIds"));
        entry.keywordsAny = stringList(map.get("keywordsAny"));
        entry.tags = stringList(map.get("tags"));
        entry.stageRange = stringList(map.get("stageRange"));
        entry.minAffection = Json.asInt(map.get("minAffection"), 0);
        entry.priority = Json.asInt(map.get("priority"), 60);
        entry.depth = Json.asInt(map.get("depth"), 3);
        entry.scanRecentTurns = Json.asInt(map.get("scanRecentTurns"), 4);
        entry.role = blankTo(Json.asString(map.get("role")), "system");
        if (map.get("eventCandidate") instanceof Map<?, ?>) {
            entry.eventCandidate = parseEventSpec(Json.asObject(map.get("eventCandidate")));
        }
        return entry.content.isBlank() ? null : entry;
    }

    private WorldInfoEventSpec parseEventSpec(Map<String, Object> map) {
        WorldInfoEventSpec spec = new WorldInfoEventSpec();
        spec.title = blankTo(Json.asString(map.get("title")), "");
        spec.theme = blankTo(Json.asString(map.get("theme")), "");
        spec.category = normalizeCategory(Json.asString(map.get("category")));
        spec.affectionBonus = Json.asInt(map.get("affectionBonus"), 2);
        spec.minAffectionOffset = Json.asInt(map.get("minAffectionOffset"), 0);
        spec.unlockAfterTurns = Json.asInt(map.get("unlockAfterTurns"), 1);
        spec.cooldown = Json.asInt(map.get("cooldown"), 4);
        return spec;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?>)) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Object item : Json.asArray(value)) {
            String text = blankTo(Json.asString(item), "").trim();
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private List<WorldInfoEntry> defaultEntries() {
        List<WorldInfoEntry> entries = new ArrayList<>();
        entries.add(entry(
                "healing.library-window",
                "林晚栀的图书馆窗边",
                "林晚栀在图书馆窗边会更放松。她喜欢低声聊天、热可可和慢慢被接住的安静，不喜欢被迫立刻说明全部心事。",
                List.of("healing"),
                List.of("图书馆", "窗边", "热可可", "安静", "书"),
                List.of("scene_push", "stay", "topic_only"),
                72,
                event("窗边热可可", "如果用户也愿意停在图书馆，后续可以把热可可和窗边座位变成轻量共同事件。", "daily", 2)
        ));
        entries.add(entry(
                "lively.club-night",
                "许朝暮的社团夜场",
                "许朝暮的精力常被社团、夜市和热闹摊位点亮。她会把尴尬转成玩笑，但认真时会突然把话说得很直。",
                List.of("lively"),
                List.of("社团", "夜市", "摊", "活动", "热闹"),
                List.of("scene_push", "light_chat"),
                70,
                event("热闹里的认真", "如果用户顺着热闹氛围继续接话，后续可以让她在玩笑里露出一点认真。", "emotion", 3)
        ));
        entries.add(entry(
                "cool.lab-corridor",
                "沈砚的实验楼走廊",
                "沈砚常把情绪藏在实验楼、走廊和黑咖啡后面。他不轻易表态，但会用实际安排回应在意。",
                List.of("cool"),
                List.of("实验楼", "走廊", "咖啡", "黑咖啡", "程序", "代码"),
                List.of("question_check", "meta_repair", "stay"),
                74,
                event("黑咖啡旁的停顿", "如果用户持续追问他真实想法，后续可以让沈砚用一次具体行动替代表态。", "emotion", 3)
        ));
        entries.add(entry(
                "artsy.lakeside-camera",
                "顾遥的湖边镜头",
                "顾遥习惯用镜头和湖边黄昏消化情绪。他会把亲近说得很轻，但会认真记住对方注意过的小细节。",
                List.of("artsy"),
                List.of("湖边", "相机", "镜头", "黄昏", "照片"),
                List.of("emotion_share", "romantic_probe"),
                73,
                event("黄昏里的底片", "如果用户主动聊记忆或照片，后续可以把一张未洗出的照片作为温柔线索。", "daily", 2)
        ));
        entries.add(entry(
                "sunny.playground-wind",
                "周燃的操场风",
                "周燃在操场和球场最自然。他常用轻松玩笑降低压力，但真正关心时会直接把陪伴落到行动上。",
                List.of("sunny"),
                List.of("操场", "球场", "篮球", "跑步", "风"),
                List.of("scene_push", "light_chat"),
                70,
                event("并肩慢跑", "如果用户愿意一起去操场，后续可以让并肩慢跑成为轻松拉近的事件。", "daily", 2)
        ));
        entries.add(entry(
                "campus.slow-night",
                "校园夜聊的慢靠近",
                "这个项目的夜聊关系适合慢慢靠近：真实问题优先回答，世界信息只做底色，不应该替用户决定剧情方向。",
                List.of("*"),
                List.of("今晚", "校园", "靠近", "一起"),
                List.of("light_chat", "emotion_share", "continue_chat"),
                60,
                null
        ));
        return entries;
    }

    private WorldInfoEntry entry(
            String id,
            String title,
            String content,
            List<String> agentIds,
            List<String> keywords,
            List<String> tags,
            int priority,
            WorldInfoEventSpec eventSpec
    ) {
        WorldInfoEntry entry = new WorldInfoEntry();
        entry.id = id;
        entry.title = title;
        entry.content = content;
        entry.enabled = true;
        entry.agentIds = new ArrayList<>(agentIds);
        entry.keywordsAny = new ArrayList<>(keywords);
        entry.tags = new ArrayList<>(tags);
        entry.stageRange = allStages();
        entry.minAffection = 0;
        entry.priority = priority;
        entry.depth = 3;
        entry.scanRecentTurns = 4;
        entry.role = "system";
        entry.eventCandidate = eventSpec;
        return entry;
    }

    private WorldInfoEventSpec event(String title, String theme, String category, int bonus) {
        WorldInfoEventSpec spec = new WorldInfoEventSpec();
        spec.title = title;
        spec.theme = theme;
        spec.category = category;
        spec.affectionBonus = bonus;
        spec.minAffectionOffset = -2;
        spec.unlockAfterTurns = 1;
        spec.cooldown = 4;
        return spec;
    }

    private boolean agentAllowed(WorldInfoEntry entry, AgentProfile agent) {
        if (entry.agentIds == null || entry.agentIds.isEmpty() || entry.agentIds.contains("*")) {
            return true;
        }
        String id = agent == null ? "" : blankTo(agent.id, "");
        return entry.agentIds.contains(id);
    }

    private boolean stageAllowed(WorldInfoEntry entry, SessionRecord session) {
        if (entry.stageRange == null || entry.stageRange.isEmpty()) {
            return true;
        }
        String stage = session == null || session.relationshipState == null ? "" : blankTo(session.relationshipState.relationshipStage, "");
        return stage.isBlank() || entry.stageRange.contains(stage);
    }

    private boolean affectionAllowed(WorldInfoEntry entry, SessionRecord session) {
        int score = session == null || session.relationshipState == null ? 0 : session.relationshipState.affectionScore;
        return score >= entry.minAffection;
    }

    private List<String> allStages() {
        return List.of("初识", "升温", "心动", "靠近", "确认关系");
    }

    private String normalizeCategory(String category) {
        String safe = blankTo(category, "daily").toLowerCase();
        return switch (safe) {
            case "emotion", "breakthrough", "conflict" -> safe;
            default -> "daily";
        };
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String sanitizeId(String value) {
        return blankTo(value, "entry").replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ActivationScore(int value, List<String> keywords, String reason) {
        static ActivationScore none() {
            return new ActivationScore(0, List.of(), "");
        }
    }
}
