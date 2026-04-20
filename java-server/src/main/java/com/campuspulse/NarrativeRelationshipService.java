package com.campuspulse;

import java.util.ArrayList;
import java.util.List;

class NarrativeRelationshipService extends RelationshipService {
    private final List<String> warmKeywords = List.of("谢谢", "晚安", "想你", "陪", "在吗", "记得", "辛苦了");
    private final List<String> honestShareKeywords = List.of("其实", "心事", "担心", "害怕", "压力", "迷茫", "秘密", "最近");
    private final List<String> initiativeKeywords = List.of("一起", "下次", "以后", "想和你", "约", "计划", "见面");
    private final List<String> boundaryKeywords = List.of("慢一点", "尊重", "不想勉强", "按你的节奏", "如果你愿意");
    private final List<String> dismissiveKeywords = List.of("随便", "算了", "无所谓", "爱回不回", "别烦");
    private final List<String> offenseKeywords = List.of("闭嘴", "恶心", "滚", "烦死了", "没意思");
    private final List<String> memoryKeywords = List.of("上次", "之前", "还记得", "那天", "你说过", "答应过");

    @Override
    RelationshipState createInitialState() {
        RelationshipState state = super.createInitialState();
        state.relationshipStage = "初识";
        state.stageProgressHint = "先从几轮自然聊天开始，让关系有第一个抬头。";
        state.routeTag = "日常升温";
        state.endingCandidate = "暧昧未满";
        state.relationshipFeedback = "先把节奏聊顺，比急着冲分更重要。";
        return state;
    }

    @Override
    TurnEvaluation evaluateTurn(String userMessage, RelationshipState previousState, StoryEvent event, MemorySummary memorySummary) {
        List<String> behaviorTags = new ArrayList<>();
        List<String> riskFlags = new ArrayList<>();

        int memorySignal = countMatches(userMessage, memoryKeywords) + (referencesKnownMemory(userMessage, memorySummary) ? 1 : 0);
        int honestShare = countMatches(userMessage, honestShareKeywords) + (userMessage.matches(".*(我觉得|我喜欢|我害怕|我担心|我希望).*") ? 1 : 0);
        int warmTouch = countMatches(userMessage, warmKeywords);
        int initiative = countMatches(userMessage, initiativeKeywords);
        int boundaryRespect = countMatches(userMessage, boundaryKeywords);
        int dismissive = countMatches(userMessage, dismissiveKeywords);
        int offensive = countMatches(userMessage, offenseKeywords);
        int questionBonus = userMessage.contains("?") || userMessage.contains("？") ? 1 : 0;

        if (initiative > 0) {
            behaviorTags.add("主动回应");
        }
        if (honestShare > 0) {
            behaviorTags.add("真诚分享");
        }
        if (warmTouch > 0 && honestShare > 0) {
            behaviorTags.add("接住情绪");
        }
        if (boundaryRespect > 0) {
            behaviorTags.add("尊重边界");
        }
        if (dismissive > 0) {
            behaviorTags.add("敷衍跳话题");
            riskFlags.add("low_quality_turn");
        }
        if (offensive > 0) {
            behaviorTags.add("冒犯");
            riskFlags.add("boundary_hit");
        }
        if (memorySignal > 0) {
            behaviorTags.add("记忆承接");
        }

        Delta delta = new Delta();
        delta.closeness = clamp(1 + warmTouch + initiative + questionBonus - dismissive - offensive, -3, 4);
        delta.trust = clamp(honestShare + boundaryRespect + Math.min(1, memorySignal) - dismissive - offensive * 2, -3, 5);
        delta.resonance = clamp(memorySignal + initiative + (event == null ? 0 : Math.max(1, event.affectionBonus - 1)) - dismissive, -2, 6);
        delta.total = delta.closeness + delta.trust + delta.resonance;

        RelationshipState next = new RelationshipState();
        next.closeness = Math.max(0, previousState.closeness + delta.closeness);
        next.trust = Math.max(0, previousState.trust + delta.trust);
        next.resonance = Math.max(0, previousState.resonance + delta.resonance);
        next.affectionScore = next.closeness + next.trust + next.resonance;
        next.relationshipStage = applyStageGate(next, previousState.relationshipStage == null ? "初识" : previousState.relationshipStage);
        next.stagnationLevel = Math.max(0, previousState.stagnationLevel + (delta.total <= 0 ? 1 : -1) + (riskFlags.isEmpty() ? 0 : 1));
        next.routeTag = event == null ? previousState.routeTag : event.successEffects.routeTag;
        next.stageProgressHint = buildAdaptiveStageHint(next);
        next.relationshipFeedback = buildAdaptiveFeedback(delta, behaviorTags, riskFlags);
        next.endingCandidate = buildAdaptiveEnding(next, riskFlags);
        next.ending = next.endingCandidate;

        boolean stageChanged = !next.relationshipStage.equals(previousState.relationshipStage);
        String stageProgress = stageChanged
                ? "关系从“" + previousState.relationshipStage + "”进入了“" + next.relationshipStage + "”。"
                : next.stageProgressHint;
        return new TurnEvaluation(next, delta, behaviorTags, riskFlags, stageChanged, stageProgress, next.relationshipFeedback);
    }

    RelationshipState applyChoiceOutcome(RelationshipState previousState, StoryEvent event, ChoiceOption choice, StoryEventProgress progress) {
        EventEffect effect = switch (choice.outcomeType) {
            case "success" -> event.successEffects;
            case "fail" -> event.failEffects;
            default -> event.neutralEffects;
        };

        RelationshipState next = new RelationshipState();
        next.closeness = Math.max(0, previousState.closeness + effect.closenessDelta);
        next.trust = Math.max(0, previousState.trust + effect.trustDelta);
        next.resonance = Math.max(0, previousState.resonance + effect.resonanceDelta);
        next.affectionScore = next.closeness + next.trust + next.resonance;
        next.relationshipStage = applyStageGate(next, previousState.relationshipStage == null ? "初识" : previousState.relationshipStage);
        next.stagnationLevel = Math.max(0, previousState.stagnationLevel + (effect.majorNegative ? 1 : -1));
        next.routeTag = effect.routeTag == null || effect.routeTag.isBlank() ? previousState.routeTag : effect.routeTag;
        next.stageProgressHint = buildAdaptiveStageHint(next);
        next.relationshipFeedback = effect.feedback;
        next.endingCandidate = buildAdaptiveEnding(next, effect.majorNegative ? List.of("major_negative_event") : List.of());
        if (progress != null && progress.triggeredEventIds != null && progress.triggeredEventIds.size() >= 2 && "确认关系".equals(next.relationshipStage)) {
            next.endingCandidate = "继续发展";
        }
        next.ending = next.endingCandidate;
        return next;
    }

    private String applyStageGate(RelationshipState state, String previousStage) {
        String target = baseStage(state);
        List<String> order = List.of("初识", "升温", "心动", "靠近", "确认关系");
        int previousIndex = Math.max(0, order.indexOf(previousStage));
        int targetIndex = Math.max(0, order.indexOf(target));
        if (targetIndex > previousIndex + 1) {
            target = order.get(previousIndex + 1);
        }
        return target;
    }

    private String baseStage(RelationshipState state) {
        if (state.closeness >= 24 && state.trust >= 24 && state.resonance >= 22 && state.affectionScore >= 78) {
            return "确认关系";
        }
        if (state.closeness >= 18 && state.trust >= 16 && state.resonance >= 16 && state.affectionScore >= 55) {
            return "靠近";
        }
        if (state.closeness >= 10 && state.trust >= 9 && state.resonance >= 9 && state.affectionScore >= 32) {
            return "心动";
        }
        if (state.closeness >= 5 && state.trust >= 3 && state.resonance >= 3 && state.affectionScore >= 12) {
            return "升温";
        }
        return "初识";
    }

    private String buildAdaptiveStageHint(RelationshipState state) {
        return switch (state.relationshipStage) {
            case "确认关系" -> "三维都够稳了，只差一次真正明确的回应。";
            case "靠近" -> state.trust < 20 ? "距离已经拉近，但还需要再建立一些信任。" : "关系已经很靠近了，可以期待一次更明确的双向表达。";
            case "心动" -> state.resonance < 12 ? "已经有心动感了，但默契还没完全长出来。" : "你们已经互相心动，下一步看谁先更认真一点。";
            case "升温" -> "聊天质量在往上走，只要别断节奏，关系会继续升温。";
            default -> "先把聊天聊顺，关系就会慢慢抬头。";
        };
    }

    private String buildAdaptiveEnding(RelationshipState state, List<String> riskFlags) {
        if (riskFlags.contains("major_negative_event") || riskFlags.contains("boundary_hit") || state.stagnationLevel >= 3) {
            return "关系停滞 / 错过";
        }
        if ("确认关系".equals(state.relationshipStage) && state.trust >= 22 && state.resonance >= 20) {
            return "继续发展";
        }
        if (state.affectionScore >= 18 || "心动".equals(state.relationshipStage) || "靠近".equals(state.relationshipStage)) {
            return "暧昧未满";
        }
        return "关系停滞 / 错过";
    }

    private String buildAdaptiveFeedback(Delta delta, List<String> behaviorTags, List<String> riskFlags) {
        if (riskFlags.contains("boundary_hit")) {
            return "这轮踩到了边界，剧情会先短暂停住。";
        }
        if (behaviorTags.contains("接住情绪")) {
            return "你这轮更像在认真接住对方，信任感升得很明显。";
        }
        if (behaviorTags.contains("记忆承接")) {
            return "你把之前的记忆接回来了，这会让默契涨得更自然。";
        }
        if (behaviorTags.contains("主动回应")) {
            return "这轮更主动一些，距离被明显拉近了。";
        }
        if (delta.total <= 0) {
            return "这轮没有真正接上节奏，关系会先停一停。";
        }
        return "气氛被维持住了，但还需要再给一点更明确的信号。";
    }

    private boolean referencesKnownMemory(String text, MemorySummary summary) {
        if (summary == null) {
            return false;
        }
        List<String> candidates = new ArrayList<>();
        candidates.addAll(summary.preferences);
        candidates.addAll(summary.promises);
        candidates.addAll(summary.sharedMoments);
        candidates.addAll(summary.discussedTopics);
        candidates.addAll(summary.strongMemories);
        candidates.addAll(summary.weakMemories);
        candidates.addAll(summary.temporaryMemories);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String compact = candidate.replace("：", "").replace(":", "");
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
}
