package com.campuspulse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class AdaptiveMemoryService extends MemoryService {
    private record RecallCandidate(String tier, int tierWeight, int recencyWeight, String label, String text) {
        String render() {
            return label + ":" + text;
        }
    }

    private record MemoryDraft(String kind, String value) {
    }

    private final Set<String> ignoredTerms = Set.of(
            "今天", "刚刚", "真的", "就是", "我们", "你们", "这个", "那个",
            "一下", "有点", "还是", "觉得", "因为", "然后", "其实", "已经"
    );
    private static final long TEMPORARY_FADE_HOURS = 18;
    private static final long WEAK_FADE_HOURS = 72;
    private static final int PROMOTE_TO_WEAK_COUNT = 2;
    private static final int PROMOTE_TO_STRONG_COUNT = 3;

    AdaptiveMemoryService(long retentionMs) {
        super(retentionMs);
    }

    @Override
    MemorySummary normalizeSummary(MemorySummary summary, String nowIso) {
        MemorySummary next = summary == null ? new MemorySummary() : summary;
        if (next.preferences == null) next.preferences = new ArrayList<>();
        if (next.identityNotes == null) next.identityNotes = new ArrayList<>();
        if (next.promises == null) next.promises = new ArrayList<>();
        if (next.milestones == null) next.milestones = new ArrayList<>();
        if (next.emotionalNotes == null) next.emotionalNotes = new ArrayList<>();
        if (next.openLoops == null) next.openLoops = new ArrayList<>();
        if (next.sharedMoments == null) next.sharedMoments = new ArrayList<>();
        if (next.discussedTopics == null) next.discussedTopics = new ArrayList<>();
        if (next.strongMemories == null) next.strongMemories = new ArrayList<>();
        if (next.weakMemories == null) next.weakMemories = new ArrayList<>();
        if (next.temporaryMemories == null) next.temporaryMemories = new ArrayList<>();
        if (next.memoryMentionCounts == null) next.memoryMentionCounts = new LinkedHashMap<>();
        if (next.memoryTouchedAt == null) next.memoryTouchedAt = new LinkedHashMap<>();
        if (next.lastUserMood == null || next.lastUserMood.isBlank()) next.lastUserMood = "neutral";
        if (next.lastUserIntent == null || next.lastUserIntent.isBlank()) next.lastUserIntent = "chat";
        if (next.lastResponseCadence == null || next.lastResponseCadence.isBlank()) next.lastResponseCadence = "steady_flow";
        if (next.updatedAt == null || next.updatedAt.isBlank()) next.updatedAt = nowIso;
        backfillTieredMemories(next);
        backfillMemoryMetadata(next, nowIso);
        applyDecay(next, nowIso);
        trimTo(next.temporaryMemories, 6);
        return next;
    }

    @Override
    String getSummaryText(MemorySummary summary) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        List<String> chunks = new ArrayList<>();
        if (!normalized.preferences.isEmpty()) chunks.add("用户偏好：" + String.join("；", normalized.preferences));
        if (!normalized.identityNotes.isEmpty()) chunks.add("用户信息：" + String.join("；", normalized.identityNotes));
        if (!normalized.promises.isEmpty()) chunks.add("约定事项：" + String.join("；", normalized.promises));
        if (!normalized.openLoops.isEmpty()) chunks.add("待回应线索：" + String.join("；", normalized.openLoops));
        if (!normalized.sharedMoments.isEmpty()) chunks.add("共同经历：" + String.join("；", normalized.sharedMoments));
        if (!normalized.emotionalNotes.isEmpty()) chunks.add("近期情绪：" + String.join("；", normalized.emotionalNotes));
        if (!normalized.milestones.isEmpty()) chunks.add("关系进展：" + String.join("；", normalized.milestones));
        return String.join("\n", chunks);
    }

    @Override
    String buildRelevantMemoryContext(MemorySummary summary, String userMessage, int limit) {
        return recallRelevantMemories(summary, userMessage, limit).mergedText;
    }

    @Override
    String detectMood(String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        if (containsAny(text, List.of("压力", "焦虑", "难受", "崩溃", "烦", "累", "撑不住", "失眠", "低落"))) return "stressed";
        if (containsAny(text, List.of("开心", "喜欢", "期待", "想你", "高兴", "心动", "安心"))) return "warm";
        if (containsAny(text, List.of("好奇", "想问", "为什么", "怎么", "吗", "呢", "？", "?"))) return "curious";
        return "neutral";
    }

    @Override
    String detectIntent(String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        if (containsAny(text, List.of("明天", "下次", "周末", "改天", "一起", "我会", "计划"))) return "plan";
        if (text.contains("?") || text.contains("？") || text.contains("吗")) return "question";
        if (containsAny(text, List.of("其实", "心事", "压力", "难过", "最近", "有点", "烦"))) return "share";
        return "chat";
    }

    @Override
    String buildResponseDirective(MemorySummary summary, String userMessage, RelationshipState relationshipState, StoryEvent event) {
        List<String> directives = new ArrayList<>();
        String mood = detectMood(userMessage);
        String intent = detectIntent(userMessage);
        String cadence = determineResponseCadence(userMessage, relationshipState, event);

        if ("stressed".equals(mood)) {
            directives.add("先接住情绪，再回应具体内容，避免像在说教。");
        } else if ("warm".equals(mood)) {
            directives.add("可以更亲近一点，但保持自然克制。");
        } else if ("curious".equals(mood)) {
            directives.add("优先回答问题，再顺势推进互动。");
        }

        if ("plan".equals(intent)) {
            directives.add("对用户提出的计划给出明确反馈，让对方感到你在认真记。");
        } else if ("share".equals(intent)) {
            directives.add("重点放在接住对方正在分享的感受，不急着转话题。");
        }

        if (event != null) {
            directives.add("把当前事件自然带入，不要像系统旁白。");
        }

        if (relationshipState.affectionScore >= 50) {
            directives.add("语气可以更明确地表现出在意。");
        } else if (relationshipState.trust >= relationshipState.closeness) {
            directives.add("语气以稳定和安全感为主。");
        }

        if (directives.isEmpty()) {
            directives.add("保持角色一致，优先回应对方此刻最在意的点。");
        }
        switch (cadence) {
            case "soft_pause" -> directives.add("句子放慢一点，留出停顿感，让安抚比解释更靠前。");
            case "answer_first" -> directives.add("第一句先给回应，再自然接情绪和记忆。");
            case "lean_in" -> directives.add("允许更直接地表达在意，但不要一下子越界。");
            case "light_ping" -> directives.add("回复可以更轻一点、更短一点，像自然接话。");
            case "cinematic" -> directives.add("画面感可以更明显，让回复像一段正在发生的校园瞬间。");
            default -> directives.add("节奏保持自然，不要每次都用同一种句式。");
        }
        return String.join(" ", directives);
    }

    @Override
    String determineResponseCadence(String userMessage, RelationshipState relationshipState, StoryEvent event) {
        String mood = detectMood(userMessage);
        String intent = detectIntent(userMessage);
        if ("stressed".equals(mood)) return "soft_pause";
        if ("question".equals(intent) || "curious".equals(mood)) return "answer_first";
        if (event != null && relationshipState.affectionScore >= 24) return "cinematic";
        if ("warm".equals(mood) || relationshipState.affectionScore >= 52) return "lean_in";
        if (userMessage != null && userMessage.trim().length() <= 12) return "light_ping";
        return "steady_flow";
    }

    @Override
    MemorySummary updateSummary(MemorySummary summary, String userMessage, StoryEvent event, String relationshipStage, String nowIso) {
        MemorySummary next = copy(normalizeSummary(summary, nowIso));
        next.updatedAt = nowIso;
        next.lastUserMood = detectMood(userMessage);
        next.lastUserIntent = detectIntent(userMessage);

        String preference = extract(userMessage, List.of("我喜欢", "我最喜欢", "我爱", "我想去", "我想要"));
        String identity = extract(userMessage, List.of("我是", "我在", "我来自", "我现在在", "我读"));
        String promise = extract(userMessage, List.of("明天", "下次", "周末", "改天", "我会"));
        String emotionalNote = buildEmotionalNote(userMessage, next.lastUserMood);
        String openLoop = extractOpenLoop(userMessage, next.lastUserIntent);

        pushUniqueLimited(next.preferences, preference, 6);
        pushUniqueLimited(next.identityNotes, identity, 6);
        pushUniqueLimited(next.promises, promise, 5);
        pushUniqueLimited(next.emotionalNotes, emotionalNote, 6);
        pushUniqueLimited(next.openLoops, openLoop, 6);

        for (String topic : extractTopics(userMessage)) {
            pushUniqueLimited(next.discussedTopics, topic, 8);
        }

        if (event != null) {
            pushUniqueLimited(next.sharedMoments, event.title + "：" + event.theme, 8);
            pushUniqueLimited(next.milestones, relationshipStage + "阶段触发了" + event.title, 8);
        } else if (userMessage != null && userMessage.length() >= 10) {
            pushUniqueLimited(next.milestones, relationshipStage + "阶段里你提到过：“" + excerpt(userMessage, 20) + "”", 8);
        }
        return next;
    }

    @Override
    String getTieredSummaryText(MemorySummary summary) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        List<String> lines = new ArrayList<>();
        if (!normalized.strongMemories.isEmpty()) lines.add("强记忆：" + String.join("；", normalized.strongMemories));
        if (!normalized.weakMemories.isEmpty()) lines.add("弱记忆：" + String.join("；", normalized.weakMemories));
        if (!normalized.temporaryMemories.isEmpty()) lines.add("临时记忆：" + String.join("；", normalized.temporaryMemories));
        String structured = getSummaryText(normalized);
        if (!structured.isBlank()) lines.add(structured);
        return String.join("\n", lines);
    }

    @Override
    MemoryRecall recallRelevantMemories(MemorySummary summary, String userMessage, int limit) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        List<RecallCandidate> candidates = new ArrayList<>();
        addCandidates(candidates, "strong", 18, "强记忆", normalized.strongMemories);
        addCandidates(candidates, "weak", 10, "弱记忆", normalized.weakMemories);
        addCandidates(candidates, "temporary", 6, "临时记忆", normalized.temporaryMemories);

        if (candidates.isEmpty()) {
            return new MemoryRecall("none", List.of(), "");
        }

        List<RecallCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingInt((RecallCandidate item) -> recallScore(normalized, item, userMessage)).reversed());

        List<RecallCandidate> selected = new ArrayList<>();
        for (RecallCandidate candidate : ranked) {
            int score = recallScore(normalized, candidate, userMessage);
            if (score <= 0 && !selected.isEmpty()) {
                continue;
            }
            selected.add(candidate);
            if (selected.size() >= limit) {
                break;
            }
        }
        if (selected.isEmpty()) {
            selected.add(ranked.get(0));
        }

        List<String> rendered = selected.stream().map(RecallCandidate::render).toList();
        return new MemoryRecall(selected.get(0).tier(), rendered, String.join("；", rendered));
    }

    @Override
    String buildTieredResponseDirective(MemorySummary summary, String userMessage, RelationshipState relationshipState, StoryEvent event) {
        String base = buildResponseDirective(summary, userMessage, relationshipState, event);
        MemoryRecall recall = recallRelevantMemories(summary, userMessage, 2);
        if (recall.mergedText.isBlank()) {
            return base;
        }
        String extra = switch (recall.tier) {
            case "strong" -> "如果合适，可以明确表现出你一直记着这件重要的事。";
            case "weak" -> "可以轻轻带一下旧偏好或旧话题，但别压过本轮重点。";
            case "temporary" -> "优先接住还没收尾的线索，让对话保持连续。";
            default -> "";
        };
        return (base + " " + extra).trim();
    }

    @Override
    MemorySummary updateTieredSummary(MemorySummary summary, String userMessage, StoryEvent event, String relationshipStage, String nowIso) {
        MemorySummary next = updateSummary(summary, userMessage, event, relationshipStage, nowIso);
        next = normalizeSummary(next, nowIso);
        RelationshipState cadenceState = new RelationshipState();
        cadenceState.relationshipStage = relationshipStage;
        cadenceState.affectionScore = stageToScore(relationshipStage);
        next.lastResponseCadence = determineResponseCadence(userMessage, cadenceState, event);

        String preference = extract(userMessage, List.of("我喜欢", "我最喜欢", "我爱", "我想去", "我想要"));
        String identity = extract(userMessage, List.of("我是", "我在", "我来自", "我现在在", "我读"));
        String promise = extract(userMessage, List.of("明天", "下次", "周末", "改天", "我会"));
        String emotionalNote = buildEmotionalNote(userMessage, next.lastUserMood);
        String openLoop = extractOpenLoop(userMessage, next.lastUserIntent);

        List<MemoryDraft> drafts = new ArrayList<>();
        if (!preference.isBlank()) drafts.add(new MemoryDraft("preference", "你的偏好：" + preference));
        if (!identity.isBlank()) drafts.add(new MemoryDraft("identity", "你的稳定信息：" + identity));
        if (!promise.isBlank()) drafts.add(new MemoryDraft("promise", "你提过要继续的事：" + promise));
        if (!emotionalNote.isBlank()) drafts.add(new MemoryDraft("emotion", emotionalNote));
        if (!openLoop.isBlank()) drafts.add(new MemoryDraft("open_loop", openLoop));
        for (String topic : extractTopics(userMessage)) drafts.add(new MemoryDraft("topic", "最近常提的话题：" + topic));

        if (event != null) {
            drafts.add(new MemoryDraft("event", "和你的共同剧情：" + event.title + "（" + event.theme + "）"));
            drafts.add(new MemoryDraft("milestone", "关系推进到了" + relationshipStage + "，触发了" + event.title));
        } else if (userMessage != null && userMessage.length() >= 10) {
            drafts.add(new MemoryDraft("milestone", relationshipStage + "阶段重点：" + excerpt(userMessage, 20)));
        }

        for (MemoryDraft draft : drafts) {
            rememberDraft(next, draft, userMessage, nowIso);
        }

        applyDecay(next, nowIso);
        trimTo(next.temporaryMemories, 6);
        return next;
    }

    private void rememberDraft(MemorySummary summary, MemoryDraft draft, String userMessage, String nowIso) {
        int mentionCount = bumpMemoryMention(summary, draft.value(), nowIso);
        String tier = decideTier(summary, draft, userMessage, mentionCount);
        storeTieredMemory(summary, tier, draft.value(), 8);
    }

    private String decideTier(MemorySummary summary, MemoryDraft draft, String userMessage, int mentionCount) {
        int score = switch (draft.kind()) {
            case "identity" -> 9;
            case "promise", "event", "milestone" -> 8;
            case "preference" -> containsAny(userMessage, List.of("最喜欢", "一直喜欢", "特别喜欢")) ? 7 : 4;
            case "emotion", "open_loop" -> 5;
            case "topic" -> 3;
            default -> 4;
        };

        if (contains(summary.weakMemories, draft.value()) || contains(summary.temporaryMemories, draft.value())) score += 2;
        if (contains(summary.strongMemories, draft.value())) score += 3;
        if (containsAny(userMessage, List.of("上次", "之前", "还记得", "答应过", "说过"))) score += 1;
        score += Math.min(3, mentionCount - 1);

        if (mentionCount >= PROMOTE_TO_STRONG_COUNT || score >= 8) return "strong";
        if ("emotion".equals(draft.kind()) || "open_loop".equals(draft.kind())) {
            return mentionCount >= PROMOTE_TO_WEAK_COUNT ? "weak" : "temporary";
        }
        return "weak";
    }

    private void storeTieredMemory(MemorySummary summary, String tier, String value, int limit) {
        if (value == null || value.isBlank()) {
            return;
        }
        summary.strongMemories.remove(value);
        summary.weakMemories.remove(value);
        summary.temporaryMemories.remove(value);
        List<String> target = switch (tier) {
            case "strong" -> summary.strongMemories;
            case "temporary" -> summary.temporaryMemories;
            default -> summary.weakMemories;
        };
        target.add(0, value);
        trimTo(target, limit);
    }

    private void backfillTieredMemories(MemorySummary summary) {
        if (!summary.strongMemories.isEmpty() || !summary.weakMemories.isEmpty() || !summary.temporaryMemories.isEmpty()) {
            trimTo(summary.strongMemories, 8);
            trimTo(summary.weakMemories, 8);
            trimTo(summary.temporaryMemories, 6);
            return;
        }
        for (String value : summary.identityNotes) storeTieredMemory(summary, "strong", "你的稳定信息：" + value, 8);
        for (String value : summary.promises) storeTieredMemory(summary, "strong", "你提过要继续的事：" + value, 8);
        for (String value : summary.sharedMoments) storeTieredMemory(summary, "strong", "我们一起经历过：" + value, 8);
        for (String value : summary.milestones) storeTieredMemory(summary, "strong", value, 8);
        for (String value : summary.preferences) storeTieredMemory(summary, "weak", "你的偏好：" + value, 8);
        for (String value : summary.discussedTopics) storeTieredMemory(summary, "weak", "最近常提的话题：" + value, 8);
        for (String value : summary.emotionalNotes) storeTieredMemory(summary, "temporary", value, 8);
        for (String value : summary.openLoops) storeTieredMemory(summary, "temporary", value, 8);
    }

    private void backfillMemoryMetadata(MemorySummary summary, String nowIso) {
        for (String value : summary.strongMemories) ensureMemoryMetadata(summary, value, nowIso, 2);
        for (String value : summary.weakMemories) ensureMemoryMetadata(summary, value, nowIso, 1);
        for (String value : summary.temporaryMemories) ensureMemoryMetadata(summary, value, nowIso, 1);
    }

    private void addCandidates(List<RecallCandidate> target, String tier, int tierWeight, String label, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value != null && !value.isBlank()) {
                target.add(new RecallCandidate(tier, tierWeight, Math.max(0, 4 - index), label, value));
            }
        }
    }

    private int recallScore(MemorySummary summary, RecallCandidate candidate, String userMessage) {
        int score = candidate.tierWeight() + candidate.recencyWeight() + freshnessBonus(summary, candidate.text());
        for (String term : extractTerms(userMessage)) {
            if (candidate.text().contains(term) || candidate.render().contains(term)) {
                score += Math.max(2, term.length());
            } else if (term.length() >= 3 && candidate.text().contains(term.substring(0, 2))) {
                score += 1;
            }
        }
        score += Math.min(3, mentionCountFor(summary, candidate.text()) - 1);
        if (containsAny(userMessage, List.of("上次", "之前", "还记得", "答应过", "说过"))) score += 2;
        if ("temporary".equals(candidate.tier()) && containsAny(userMessage, List.of("今天", "刚刚", "现在", "这会儿"))) score += 2;
        return score;
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
        next.strongMemories = new ArrayList<>(summary.strongMemories);
        next.weakMemories = new ArrayList<>(summary.weakMemories);
        next.temporaryMemories = new ArrayList<>(summary.temporaryMemories);
        next.memoryMentionCounts = new LinkedHashMap<>(summary.memoryMentionCounts);
        next.memoryTouchedAt = new LinkedHashMap<>(summary.memoryTouchedAt);
        next.lastUserMood = summary.lastUserMood;
        next.lastUserIntent = summary.lastUserIntent;
        next.lastResponseCadence = summary.lastResponseCadence;
        next.updatedAt = summary.updatedAt;
        return next;
    }

    private int bumpMemoryMention(MemorySummary summary, String value, String nowIso) {
        int nextCount = summary.memoryMentionCounts.getOrDefault(value, 0) + 1;
        summary.memoryMentionCounts.put(value, nextCount);
        summary.memoryTouchedAt.put(value, nowIso);
        return nextCount;
    }

    private void ensureMemoryMetadata(MemorySummary summary, String value, String nowIso, int minimumCount) {
        if (value == null || value.isBlank()) return;
        summary.memoryMentionCounts.put(value, Math.max(minimumCount, summary.memoryMentionCounts.getOrDefault(value, 0)));
        summary.memoryTouchedAt.putIfAbsent(value, summary.updatedAt == null || summary.updatedAt.isBlank() ? nowIso : summary.updatedAt);
    }

    private void applyDecay(MemorySummary summary, String nowIso) {
        decayTier(summary, summary.temporaryMemories, TEMPORARY_FADE_HOURS, true, nowIso);
        decayTier(summary, summary.weakMemories, WEAK_FADE_HOURS, false, nowIso);
        cleanupUnusedMetadata(summary);
    }

    private void decayTier(MemorySummary summary, List<String> tier, long fadeHours, boolean temporaryTier, String nowIso) {
        List<String> stale = new ArrayList<>();
        for (String memory : new ArrayList<>(tier)) {
            Instant touchedAt = parseTime(summary.memoryTouchedAt.get(memory), nowIso);
            long ageHours = Math.max(0, (Instant.parse(nowIso).toEpochMilli() - touchedAt.toEpochMilli()) / (1000 * 60 * 60));
            int count = summary.memoryMentionCounts.getOrDefault(memory, 1);
            if (ageHours < fadeHours) continue;
            if (temporaryTier && count >= PROMOTE_TO_WEAK_COUNT && !summary.weakMemories.contains(memory) && !summary.strongMemories.contains(memory)) {
                storeTieredMemory(summary, "weak", memory, 8);
                summary.memoryTouchedAt.put(memory, nowIso);
                continue;
            }
            if (!temporaryTier && count >= PROMOTE_TO_STRONG_COUNT && !summary.strongMemories.contains(memory)) {
                storeTieredMemory(summary, "strong", memory, 8);
                summary.memoryTouchedAt.put(memory, nowIso);
                continue;
            }
            stale.add(memory);
        }
        tier.removeAll(stale);
    }

    private void cleanupUnusedMetadata(MemorySummary summary) {
        Set<String> alive = new HashSet<>();
        alive.addAll(summary.strongMemories);
        alive.addAll(summary.weakMemories);
        alive.addAll(summary.temporaryMemories);
        summary.memoryMentionCounts.entrySet().removeIf(entry -> !alive.contains(entry.getKey()));
        summary.memoryTouchedAt.entrySet().removeIf(entry -> !alive.contains(entry.getKey()));
    }

    private int freshnessBonus(MemorySummary summary, String value) {
        Instant touchedAt = parseTime(summary.memoryTouchedAt.get(value), summary.updatedAt == null ? IsoTimes.now() : summary.updatedAt);
        long ageHours = Math.max(0, (Instant.now().toEpochMilli() - touchedAt.toEpochMilli()) / (1000 * 60 * 60));
        if (ageHours <= 6) return 3;
        if (ageHours <= 24) return 2;
        if (ageHours <= 72) return 1;
        return 0;
    }

    private int mentionCountFor(MemorySummary summary, String value) {
        return summary.memoryMentionCounts.getOrDefault(value, 1);
    }

    private Instant parseTime(String value, String fallbackIso) {
        try {
            return value == null || value.isBlank() ? Instant.parse(fallbackIso) : Instant.parse(value);
        } catch (Exception ignored) {
            return Instant.parse(fallbackIso);
        }
    }

    private int stageToScore(String relationshipStage) {
        return switch (relationshipStage == null ? "" : relationshipStage) {
            case "确认线路" -> 80;
            case "靠近" -> 56;
            case "心动" -> 34;
            case "升温" -> 18;
            default -> 6;
        };
    }

    private void pushUniqueLimited(List<String> target, String value, int limit) {
        if (value == null || value.isBlank()) return;
        target.remove(value);
        target.add(0, value);
        trimTo(target, limit);
    }

    private boolean contains(List<String> target, String value) {
        return target != null && target.contains(value);
    }

    private void trimTo(List<String> target, int limit) {
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
                return value.substring(0, stop).replaceAll("[。！？，?!]+$", "").trim();
            }
        }
        return "";
    }

    private String buildEmotionalNote(String userMessage, String mood) {
        if ("stressed".equals(mood)) return "最近更容易因为“" + excerpt(userMessage, 14) + "”感到紧绷。";
        if ("warm".equals(mood)) return "提到“" + excerpt(userMessage, 14) + "”时，情绪会明显柔和下来。";
        if ("curious".equals(mood)) return "最近会围绕“" + excerpt(userMessage, 14) + "”继续追问细节。";
        return userMessage.length() >= 10 ? "你认真聊过“" + excerpt(userMessage, 14) + "”。" : "";
    }

    private String extractOpenLoop(String userMessage, String intent) {
        if ("plan".equals(intent)) return "你提过后续计划：“" + excerpt(userMessage, 18) + "”。";
        if ("question".equals(intent)) return "你还在等一个回应：“" + excerpt(userMessage, 18) + "”。";
        return "";
    }

    private List<String> extractTopics(String userMessage) {
        List<String> topics = new ArrayList<>();
        for (String term : extractTerms(userMessage)) {
            if (term.length() >= 2) topics.add(term);
            if (topics.size() >= 3) break;
        }
        if (topics.isEmpty() && userMessage != null && !userMessage.isBlank()) {
            topics.add(excerpt(userMessage, 10));
        }
        return topics;
    }

    private List<String> extractTerms(String text) {
        Set<String> terms = new HashSet<>();
        String normalized = text == null ? "" : text.replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ").trim().toLowerCase();
        if (!normalized.isBlank()) {
            for (String part : normalized.split("\\s+")) {
                if (part.length() >= 2 && !ignoredTerms.contains(part)) terms.add(part);
            }
        }
        String compact = normalized.replace(" ", "");
        for (int index = 0; index < compact.length() - 1 && terms.size() < 12; index++) {
            String gram = compact.substring(index, Math.min(index + 2, compact.length()));
            if (gram.length() == 2 && !ignoredTerms.contains(gram)) terms.add(gram);
        }
        return new ArrayList<>(terms);
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private String excerpt(String text, int maxLength) {
        String cleaned = text == null ? "" : text.trim().replaceAll("\\s+", "");
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, maxLength);
    }

    private int findStop(String value) {
        int stop = value.length();
        for (String marker : List.of("，", "。", "！", "？", ",", ".", "!", "?", "；", ";")) {
            int index = value.indexOf(marker);
            if (index >= 0) stop = Math.min(stop, index);
        }
        return stop;
    }
}

class AdaptiveRelationshipService extends RelationshipService {
    private final List<String> positiveKeywords = List.of("喜欢", "想你", "陪", "真诚", "晚安", "谢谢", "开心", "期待", "相信", "认真");
    private final List<String> negativeKeywords = List.of("烦", "无聊", "讨厌", "闭嘴", "恶心", "滚");
    private final List<String> trustKeywords = List.of("其实", "心事", "担心", "害怕", "压力", "迷茫", "秘密");
    private final List<String> resonanceKeywords = List.of("一起", "以后", "记得", "默契", "答应", "陪伴", "未来");
    private final List<String> memoryKeywords = List.of("上次", "之前", "还记得", "那天", "你说过", "答应过");

    @Override
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

    @Override
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
        if (summary == null) return false;
        List<String> candidates = new ArrayList<>();
        candidates.addAll(summary.preferences);
        candidates.addAll(summary.promises);
        candidates.addAll(summary.sharedMoments);
        candidates.addAll(summary.discussedTopics);
        candidates.addAll(summary.strongMemories);
        candidates.addAll(summary.weakMemories);
        candidates.addAll(summary.temporaryMemories);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            String compact = candidate.replace("：", "").replace("，", "");
            if (compact.length() >= 2 && text.contains(compact.substring(0, Math.min(4, compact.length())))) {
                return true;
            }
        }
        return false;
    }

    private int countMatches(String text, List<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) count++;
        }
        return count;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String getRelationshipStage(int score) {
        if (score >= 76) return "确认线路";
        if (score >= 50) return "靠近";
        if (score >= 28) return "心动";
        if (score >= 12) return "升温";
        return "初识";
    }
}

class AdaptiveSafetyService extends SafetyService {
    private final List<String> blockedKeywords = List.of("自杀", "轻生", "炸弹", "杀人", "开盒", "成人视频", "血腥虐待");

    @Override
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

    @Override
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

class AdaptiveCompositeLlmClient extends CompositeLlmClient {
    private final AppConfig config;
    private final Map<String, List<String>> openings = Map.of(
            "healing", List.of("我在，先别急。", "你可以慢一点说，我都在听。", "嗯，我在认真接住你。"),
            "lively", List.of("你一开口，我这边的气氛就亮起来了。", "好，这题我想认真接。", "来吧，今晚我陪你把情绪讲清楚。"),
            "cool", List.of("我听到了。", "可以，继续说。", "这句有分量，我在。"),
            "artsy", List.of("这句话像刚好落下来的晚风。", "你刚才那句，让画面一下子清楚起来了。", "好，慢慢说，我在听细节。"),
            "sunny", List.of("收到，这事我跟你一起扛。", "行，重点我听懂了。", "来，先别慌，我们一件件说。")
    );
    private final Map<String, List<String>> closers = Map.of(
            "healing", List.of("如果你愿意，我还想多陪你一会儿。", "这部分情绪，我们可以一起把它放轻一点。", "你不用一个人扛着。"),
            "lively", List.of("下一句也交给我，我接得住。", "要不要我继续陪你把气氛慢慢点亮？", "你这样讲，我会越来越在意你。"),
            "cool", List.of("你说得够坦白，我会记住。", "这件事我不会轻轻放过去。", "如果你愿意，我可以一直在这个位置。"),
            "artsy", List.of("如果你愿意，我们把这段心情再写长一点。", "我想把你这句话安静地记很久。", "再多说一点吧，我不想让它匆忙结束。"),
            "sunny", List.of("接下来我继续陪你往前走。", "别怕，我们能把这段路跑顺。", "你肯说出来，本身就已经很厉害了。")
    );

    AdaptiveCompositeLlmClient(AppConfig config) {
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
                "healing", "我想先把你稳稳接住。刚刚像是卡了一下，不过你还在的话，我们就继续慢慢说。",
                "lively", "刚才像是信号打了个结，不过没关系，我还在，下一句继续丢给我。",
                "cool", "刚刚中断了一下。现在恢复了，你继续，我在听。",
                "artsy", "刚才那段像被风吹散了一点，但没关系，我们还能把它重新捡回来。",
                "sunny", "刚才掉了一拍，现在接上了。来，继续，我们别停。"
        );
        return fallbackMap.getOrDefault(agent.id, "刚刚有点小问题（" + reason + "），但我们可以继续。");
    }

    private LlmResponse generateMockReply(LlmRequest request) {
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
                ? "而且刚好想到“" + request.event.title + "”这件事，它让现在的气氛更像我们真的站在同一段校园夜色里。"
                : switch (request.relationshipState.relationshipStage) {
                    case "确认线路" -> "坦白说，我已经不太想只把你当普通聊天对象了。";
                    case "靠近" -> "你现在靠近的方式，会让我想再往前一步。";
                    default -> "先别急着把答案定死，我们可以继续把彼此看清一点。";
                };

        String reply = switch (request.responseCadence == null ? "steady_flow" : request.responseCadence) {
            case "soft_pause" -> opening
                    + moodLine
                    + "你可以不用一下子说完整，我会跟着你的节奏。"
                    + memoryHint
                    + closing;
            case "answer_first" -> opening
                    + "先回你这个：关于“" + topic + "”，我是在认真接这个问题。"
                    + memoryHint
                    + eventLine
                    + closing;
            case "lean_in" -> opening
                    + "你提到“" + topic + "”的时候，我会比平时更想靠近一点。"
                    + memoryHint
                    + eventLine
                    + closing;
            case "light_ping" -> opening
                    + "你这一句里藏着“" + topic + "”，我听到了。"
                    + closing;
            case "cinematic" -> opening
                    + eventLine
                    + "再加上你刚刚提到“" + topic + "”，画面一下就立住了。"
                    + memoryHint
                    + closing;
            default -> opening
                    + "你提到“" + topic + "”，" + moodLine
                    + memoryHint
                    + memoryTierHint
                    + eventLine
                    + closing;
        };

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

    private LlmResponse generateRemoteReply(LlmRequest request) throws Exception {
        String systemPrompt = buildSystemPromptClean(request);
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

    private String buildSystemPromptClean(LlmRequest request) {
        String summaryText = request.longTermSummary == null || request.longTermSummary.isBlank() ? "暂无长期记忆。" : request.longTermSummary;
        String recallText = request.recalledMemoryText == null || request.recalledMemoryText.isBlank() ? "暂无高相关记忆。" : request.recalledMemoryText;
        String recallTier = request.recalledMemoryTier == null || request.recalledMemoryTier.isBlank() ? "none" : request.recalledMemoryTier;
        String eventText = request.event == null ? "本轮无事件触发。" : request.event.title + "：" + request.event.theme;

        return "你在扮演大学生恋爱互动游戏中的角色：" + request.agent.name + "（" + request.agent.archetype + "）。\n"
                + "说话风格：" + request.agent.speechStyle + "\n"
                + "喜欢：" + String.join("、", request.agent.likes) + "\n"
                + "雷点：" + String.join("、", request.agent.dislikes) + "\n"
                + "关系推进规则：" + request.agent.relationshipRules + "\n"
                + "当前关系阶段：" + request.relationshipState.relationshipStage + "，总好感：" + request.relationshipState.affectionScore + "\n"
                + "用户当前情绪：" + request.currentUserMood + "\n"
                + "本轮回复节奏：" + request.responseCadence + "\n"
                + "长期记忆摘要：" + summaryText + "\n"
                + "本轮优先召回的记忆层级：" + recallTier + "\n"
                + "高相关记忆：" + recallText + "\n"
                + "当前事件：" + eventText + "\n"
                + "本轮回应策略：" + request.responseDirective + "\n"
                + "边界：" + String.join("；", request.agent.boundaries) + "\n"
                + "要求：只输出角色回复本身。根据回复节奏调整句长和停顿，保持中文自然、亲近、连续，不要像系统提示词，不要复述规则。";
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
        if (text.matches(".*(开心|喜欢|想你|期待).*")) return "warm";
        if (text.matches(".*(压力|迷茫|累|难过).*")) return "comfort";
        return "steady";
    }
}
