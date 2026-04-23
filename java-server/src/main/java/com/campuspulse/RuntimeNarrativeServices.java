package com.campuspulse;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    final String plotDirectorReason;

    PlotDecision(
            PlotState nextPlotState,
            PlotArcState nextPlotArcState,
            SceneState nextSceneState,
            boolean advanced,
            String replySource,
            String sceneFrame,
            String sceneText,
            String plotDirectorReason
    ) {
        this.nextPlotState = nextPlotState;
        this.nextPlotArcState = nextPlotArcState;
        this.nextSceneState = nextSceneState;
        this.advanced = advanced;
        this.replySource = replySource;
        this.sceneFrame = sceneFrame;
        this.sceneText = sceneText;
        this.plotDirectorReason = plotDirectorReason;
    }

    PlotDecision(
            PlotState nextPlotState,
            PlotArcState nextPlotArcState,
            SceneState nextSceneState,
            boolean advanced,
            String replySource,
            String sceneFrame,
            String sceneText
    ) {
        this(nextPlotState, nextPlotArcState, nextSceneState, advanced, replySource, sceneFrame, sceneText, "");
    }

    PlotDecision(PlotState nextPlotState, boolean advanced, String replySource, String sceneFrame) {
        this(nextPlotState, null, null, advanced, replySource, sceneFrame, "");
    }
}

class PlotDirectorAgentDecision {
    final String action;
    final String reason;
    final String whyNow;
    final String sceneCue;
    final boolean shouldAdvance;
    final int confidence;
    final String riskIfAdvance;
    final String requiredUserSignal;

    PlotDirectorAgentDecision(String action, String reason, String sceneCue, boolean shouldAdvance) {
        this(action, reason, reason, sceneCue, shouldAdvance, defaultConfidence(action, shouldAdvance), "", "");
    }

    PlotDirectorAgentDecision(
            String action,
            String reason,
            String whyNow,
            String sceneCue,
            boolean shouldAdvance,
            int confidence,
            String riskIfAdvance,
            String requiredUserSignal
    ) {
        this.action = action;
        this.reason = reason;
        this.whyNow = whyNow == null || whyNow.isBlank() ? reason : whyNow;
        this.sceneCue = sceneCue;
        this.shouldAdvance = shouldAdvance;
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.riskIfAdvance = riskIfAdvance == null ? "" : riskIfAdvance;
        this.requiredUserSignal = requiredUserSignal == null ? "" : requiredUserSignal;
    }

    private static int defaultConfidence(String action, boolean shouldAdvance) {
        if ("transition_only".equals(action)) {
            return 95;
        }
        if ("hold_plot".equals(action)) {
            return 82;
        }
        return shouldAdvance ? 70 : 55;
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
    final String mode;
    final boolean mustNotGuess;

    SearchDecision(boolean enabled, String query, String reason, String mode, boolean mustNotGuess) {
        this.enabled = enabled;
        this.query = query;
        this.reason = reason;
        this.mode = mode;
        this.mustNotGuess = mustNotGuess;
    }
}

class RealityGuardResult {
    final LlmResponse reply;
    final RealityAudit realityAudit;
    final SceneConsistencyAudit sceneConsistencyAudit;

    RealityGuardResult(LlmResponse reply, RealityAudit realityAudit, SceneConsistencyAudit sceneConsistencyAudit) {
        this.reply = reply;
        this.realityAudit = realityAudit;
        this.sceneConsistencyAudit = sceneConsistencyAudit;
    }
}

class IntentInferenceService {
    IntentState infer(
            String userMessage,
            List<ConversationSnippet> recentContext,
            RelationshipState relationshipState,
            SceneState sceneState,
            RelationalTensionState tensionState,
            MemorySummary memorySummary,
            String nowIso
    ) {
        String text = userMessage == null ? "" : userMessage.trim();
        IntentState state = new IntentState();
        state.primaryIntent = detectPrimary(text);
        state.secondaryIntent = detectSecondary(text, relationshipState, sceneState);
        state.emotion = detectEmotion(text, tensionState);
        state.clarity = detectClarity(text);
        state.needsEmpathy = "emotion_share".equals(state.primaryIntent)
                || "romantic_probe".equals(state.primaryIntent)
                || "sad".equals(state.emotion)
                || "angry".equals(state.emotion);
        state.needsStructure = "advice_seek".equals(state.primaryIntent);
        state.needsFollowup = "low".equals(state.clarity)
                || "scene_push".equals(state.primaryIntent)
                || "romantic_probe".equals(state.primaryIntent);
        state.isBoundarySensitive = containsAny(text, List.of("别碰", "别问", "不要逼我", "烦", "滚", "讨厌"))
                || tensionState != null && tensionState.guarded;
        state.rationale = buildRationale(text, recentContext, sceneState, memorySummary);
        state.updatedAt = nowIso;
        return state;
    }

    private String detectPrimary(String text) {
        if (text.isBlank()) return "presence_followup";
        if (containsAny(text, List.of("不是", "怎么变成", "我说的是", "明明", "你刚刚", "我们不是", "别误会"))) {
            return "meta_repair";
        }
        if (containsAny(text, List.of("喜欢你", "喜欢我", "对我有好感", "是不是喜欢我", "有点吸引", "心动", "在意我"))) {
            return "romantic_probe";
        }
        if (containsAny(text, List.of("送你回宿舍", "回宿舍", "去食堂", "去操场", "一起走", "路上", "去图书馆", "夜跑"))) {
            return "scene_push";
        }
        if (containsAny(text, List.of("怎么办", "建议", "怎么选", "该不该", "要不要"))) {
            return "advice_seek";
        }
        if (containsAny(text, List.of("难受", "压力", "委屈", "低落", "有点累", "崩溃", "烦"))) {
            return "emotion_share";
        }
        if (text.contains("?") || text.contains("？") || containsAny(text, List.of("吗", "呢", "是不是", "为什么", "怎么", "要不要"))) {
            return "question_check";
        }
        if (text.length() <= 4) {
            return "small_talk";
        }
        return "light_chat";
    }

    private String detectSecondary(String text, RelationshipState relationshipState, SceneState sceneState) {
        if (containsAny(text, List.of("上次", "之前", "说过", "还记得", "答应过", "后来"))) {
            return "memory_callback";
        }
        if (sceneState != null && sceneState.transitionPending) {
            return "transition_hold";
        }
        if (relationshipState != null && relationshipState.affectionScore < 25
                && containsAny(text, List.of("喜欢", "确认关系", "在一起", "亲一下"))) {
            return "pace_guard";
        }
        return "none";
    }

    private String detectEmotion(String text, RelationalTensionState tensionState) {
        if (containsAny(text, List.of("烦", "滚", "讨厌", "闭嘴", "离谱", "别烦"))) {
            return "angry";
        }
        if (containsAny(text, List.of("难受", "委屈", "低落", "哭", "压力", "累", "崩溃"))) {
            return "sad";
        }
        if (containsAny(text, List.of("喜欢", "开心", "想你", "高兴", "安心", "好感"))) {
            return "warm";
        }
        if (tensionState != null && tensionState.guarded) {
            return "fragile";
        }
        return "neutral";
    }

    private String detectClarity(String text) {
        if (text.isBlank()) return "low";
        if (text.length() <= 4) return "low";
        if (text.length() <= 10) return "medium";
        return "high";
    }

    private String buildRationale(String text, List<ConversationSnippet> recentContext, SceneState sceneState, MemorySummary memorySummary) {
        StringBuilder builder = new StringBuilder();
        builder.append("message=").append(text.isBlank() ? "<empty>" : text);
        if (sceneState != null && sceneState.location != null) {
            builder.append("; scene=").append(sceneState.location);
        }
        if (memorySummary != null && memorySummary.lastUserIntent != null) {
            builder.append("; lastIntent=").append(memorySummary.lastUserIntent);
        }
        if (recentContext != null && !recentContext.isEmpty()) {
            builder.append("; recentTurns=").append(Math.min(5, recentContext.size()));
        }
        return builder.toString();
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

class DialogueContinuityService {
    DialogueContinuityState normalize(DialogueContinuityState state, String nowIso) {
        DialogueContinuityState next = state == null ? new DialogueContinuityState() : cloneState(state);
        if (next.mustNotContradict == null) {
            next.mustNotContradict = new ArrayList<>();
        }
        if (next.updatedAt == null || next.updatedAt.isBlank()) {
            next.updatedAt = nowIso;
        }
        return next;
    }

    DialogueContinuityState update(
            DialogueContinuityState previous,
            String userMessage,
            List<ConversationSnippet> recentContext,
            SceneState sceneState,
            String nowIso
    ) {
        DialogueContinuityState next = normalize(previous, nowIso);
        String text = userMessage == null ? "" : userMessage.trim();
        String compact = compact(text);
        String lastAssistant = lastText(recentContext, "assistant");
        String previousUser = lastText(recentContext, "user");
        boolean acceptedPrevious = isAcceptance(compact)
                && (looksLikeProposal(lastAssistant) || next.currentObjective != null && !next.currentObjective.isBlank());
        boolean hotDrinkContext = containsAny(compact + lastAssistant + previousUser + blank(next.currentObjective), List.of("热饮", "热可可", "奶茶", "咖啡", "买一杯", "喝的"));
        boolean scenePush = containsAny(compact, List.of("去", "走吧", "一起走", "一起去", "送你", "回宿舍", "操场", "食堂", "图书馆", "市区"));

        next.updatedAt = nowIso;
        next.lastAssistantQuestion = containsQuestion(lastAssistant) ? lastAssistant : "";
        next.userAnsweredLastQuestion = !next.lastAssistantQuestion.isBlank() && !compact.isBlank() && !containsQuestion(compact);

        if (containsAny(compact, List.of("买到了", "拿到了", "喝上了", "已经买", "到了"))) {
            next.currentObjective = "";
            next.pendingUserOffer = "";
            next.acceptedPlan = "";
            next.sceneTransitionNeeded = false;
            next.nextBestMove = "承接已完成的动作，回到两人的即时感受。";
            next.mustNotContradict = baseGuards(sceneState);
            next.confidence = 70;
            return next;
        }

        if (containsAny(compact, List.of("我去给你买", "给你买", "帮你买", "去买一杯", "买一杯"))) {
            next.pendingUserOffer = hotDrinkContext ? "用户提出去给角色买一杯热饮。" : "用户提出替角色完成一个小行动。";
            next.currentObjective = hotDrinkContext ? "围绕买热饮这件事继续互动。" : next.pendingUserOffer;
            next.acceptedPlan = "";
            next.sceneTransitionNeeded = false;
            next.nextBestMove = "回应这个具体提议，明确是一起去、等在原地，还是轻轻改成并肩行动。";
            next.mustNotContradict = guardsForObjective(next.currentObjective, sceneState);
            next.confidence = 82;
            return next;
        }

        if (acceptedPrevious) {
            String objective = inferObjectiveFromContext(next, lastAssistant, previousUser, hotDrinkContext);
            next.acceptedPlan = objective;
            next.currentObjective = objective;
            next.pendingUserOffer = "";
            next.sceneTransitionNeeded = true;
            next.nextBestMove = "用户已经接受上一轮提议，下一句必须承接计划开始行动，而不是重新开话题。";
            next.mustNotContradict = guardsForObjective(objective, sceneState);
            next.confidence = 88;
            return next;
        }

        if (scenePush) {
            String objective = inferSceneObjective(compact);
            if (!objective.isBlank()) {
                next.currentObjective = objective;
                next.acceptedPlan = objective;
                next.pendingUserOffer = "";
                next.sceneTransitionNeeded = true;
                next.nextBestMove = "先完成场景过渡，再顺着新地点继续聊天。";
                next.mustNotContradict = guardsForObjective(objective, sceneState);
                next.confidence = 80;
                return next;
            }
        }

        if (next.currentObjective != null && !next.currentObjective.isBlank()) {
            next.nextBestMove = "继续承接当前目标：" + next.currentObjective;
            next.mustNotContradict = guardsForObjective(next.currentObjective, sceneState);
            next.confidence = Math.max(55, next.confidence - 4);
            return next;
        }

        next.nextBestMove = "";
        next.mustNotContradict = baseGuards(sceneState);
        next.confidence = 35;
        return next;
    }

    private DialogueContinuityState cloneState(DialogueContinuityState source) {
        DialogueContinuityState next = new DialogueContinuityState();
        next.currentObjective = source.currentObjective;
        next.pendingUserOffer = source.pendingUserOffer;
        next.acceptedPlan = source.acceptedPlan;
        next.lastAssistantQuestion = source.lastAssistantQuestion;
        next.userAnsweredLastQuestion = source.userAnsweredLastQuestion;
        next.sceneTransitionNeeded = source.sceneTransitionNeeded;
        next.nextBestMove = source.nextBestMove;
        next.mustNotContradict = source.mustNotContradict == null ? new ArrayList<>() : new ArrayList<>(source.mustNotContradict);
        next.confidence = source.confidence;
        next.updatedAt = source.updatedAt;
        return next;
    }

    private String inferObjectiveFromContext(DialogueContinuityState state, String lastAssistant, String previousUser, boolean hotDrinkContext) {
        if (state.currentObjective != null && !state.currentObjective.isBlank()) {
            if (hotDrinkContext && !state.currentObjective.contains("热饮")) {
                return "一起去买热饮。";
            }
            return state.currentObjective;
        }
        String all = compact(lastAssistant + previousUser);
        if (hotDrinkContext) return "一起去买热饮。";
        if (all.contains("操场")) return "一起去操场。";
        if (all.contains("食堂")) return "一起去食堂。";
        if (all.contains("图书馆")) return "一起去图书馆。";
        if (all.contains("宿舍")) return "送她回宿舍。";
        return "承接上一轮已经确认的共同计划。";
    }

    private String inferSceneObjective(String compact) {
        if (compact.contains("操场")) return "一起去操场。";
        if (compact.contains("食堂")) return "一起去食堂。";
        if (compact.contains("图书馆")) return "一起去图书馆。";
        if (compact.contains("宿舍")) return "送她回宿舍。";
        if (compact.contains("市区")) return "一起去市区。";
        if (compact.contains("热饮") || compact.contains("热可可") || compact.contains("奶茶") || compact.contains("咖啡")) return "一起去买热饮。";
        if (compact.contains("走吧") || compact.contains("一起走") || compact.contains("一起去")) return "一起往前走。";
        return "";
    }

    private List<String> guardsForObjective(String objective, SceneState sceneState) {
        List<String> guards = baseGuards(sceneState);
        if (objective != null && !objective.isBlank()) {
            guards.add("不要违背当前共同目标：" + objective);
        }
        if (objective != null && objective.contains("热饮")) {
            guards.add("不要把买热饮泛化成逛街、从哪里开始逛或重新开新路线。");
            guards.add("下一句应承接出发、路上互动、到热饮摊或选择口味。");
        }
        return guards;
    }

    private List<String> baseGuards(SceneState sceneState) {
        List<String> guards = new ArrayList<>();
        if (sceneState != null && "face_to_face".equals(sceneState.interactionMode)) {
            guards.add("当前是面对面互动，不要写成发消息、打字、看到回复。");
        }
        if (sceneState != null && sceneState.location != null && !sceneState.location.isBlank()) {
            guards.add("不要无故跳离当前地点：" + sceneState.location);
        }
        return guards;
    }

    private boolean isAcceptance(String text) {
        return containsAny(text, List.of("那去吧", "走吧", "好啊", "可以", "行", "嗯", "好呀", "就这样", "听你的", "一起去"));
    }

    private boolean looksLikeProposal(String text) {
        return containsAny(text, List.of("要不", "一起", "去吧", "走吧", "可以", "不如", "我们"));
    }

    private boolean containsQuestion(String text) {
        return text != null && (text.contains("?") || text.contains("？") || text.contains("吗") || text.contains("呢"));
    }

    private String lastText(List<ConversationSnippet> context, String role) {
        if (context == null) {
            return "";
        }
        for (int index = context.size() - 1; index >= 0; index--) {
            ConversationSnippet snippet = context.get(index);
            if (snippet != null && role.equals(snippet.role)) {
                return snippet.text == null ? "" : snippet.text;
            }
        }
        return "";
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private String blank(String text) {
        return text == null ? "" : text;
    }

    private boolean containsAny(String text, List<String> keywords) {
        String safe = text == null ? "" : text;
        for (String keyword : keywords) {
            if (safe.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

class ResponsePlanningService {
    ResponsePlan plan(
            IntentState intentState,
            RelationshipState relationshipState,
            EmotionState emotionState,
            RelationalTensionState tensionState,
            PlotGateDecision plotGateDecision,
            String replySource,
            String nowIso
    ) {
        ResponsePlan plan = new ResponsePlan();
        plan.firstMove = firstMove(intentState, tensionState);
        plan.coreTask = coreTask(intentState, tensionState);
        plan.initiativeLevel = initiativeLevel(intentState, relationshipState, tensionState, replySource);
        plan.responseLength = responseLength(intentState, replySource, tensionState, plotGateDecision);
        plan.dialogueMode = dialogueMode(intentState, relationshipState, tensionState, plotGateDecision);
        plan.shouldReferenceMemory = !"meta_repair".equals(intentState.primaryIntent) && !"low".equals(intentState.clarity);
        plan.shouldAdvanceScene = "scene_push".equals(intentState.primaryIntent);
        plan.shouldAdvancePlot = plotGateDecision != null && plotGateDecision.allowed;
        plan.shouldUseUncertainty = "low".equals(intentState.clarity) || "meta_repair".equals(intentState.primaryIntent);
        plan.allowFollowupQuestion = !("meta_repair".equals(intentState.primaryIntent) || tensionState != null && tensionState.guarded);
        plan.explanation = "firstMove=" + plan.firstMove + ", task=" + plan.coreTask + ", mode=" + plan.dialogueMode;
        plan.updatedAt = nowIso;
        return plan;
    }

    private String firstMove(IntentState intentState, RelationalTensionState tensionState) {
        if ("meta_repair".equals(intentState.primaryIntent)) return "align";
        if (tensionState != null && tensionState.guarded) return "deescalate";
        if ("question_check".equals(intentState.primaryIntent) || "advice_seek".equals(intentState.primaryIntent)) return "answer_first";
        if (intentState.needsEmpathy) return "empathize_first";
        if ("low".equals(intentState.clarity)) return "soft_catch";
        return "respond";
    }

    private String coreTask(IntentState intentState, RelationalTensionState tensionState) {
        if ("meta_repair".equals(intentState.primaryIntent)) return "repair_context";
        if (tensionState != null && tensionState.guarded) return "repair_safety";
        if ("scene_push".equals(intentState.primaryIntent)) return "advance_scene";
        if ("romantic_probe".equals(intentState.primaryIntent)) return "hold_tension";
        if ("question_check".equals(intentState.primaryIntent)) return "answer";
        if ("emotion_share".equals(intentState.primaryIntent)) return "comfort";
        return "gentle_expand";
    }

    private String initiativeLevel(IntentState intentState, RelationshipState relationshipState, RelationalTensionState tensionState, String replySource) {
        if ("silence_heartbeat".equals(replySource) || "long_chat_heartbeat".equals(replySource)) {
            return "low";
        }
        if ("meta_repair".equals(intentState.primaryIntent) || tensionState != null && tensionState.guarded) {
            return "low";
        }
        if (relationshipState != null && relationshipState.affectionScore < 25) {
            return "low";
        }
        if ("scene_push".equals(intentState.primaryIntent) || "romantic_probe".equals(intentState.primaryIntent)) {
            return "medium";
        }
        return "medium";
    }

    private String responseLength(IntentState intentState, String replySource, RelationalTensionState tensionState, PlotGateDecision plotGateDecision) {
        if ("silence_heartbeat".equals(replySource) || "long_chat_heartbeat".equals(replySource)) {
            return "short";
        }
        if (tensionState != null && tensionState.guarded) {
            return "short";
        }
        if ("emotion_share".equals(intentState.primaryIntent) || "scene_push".equals(intentState.primaryIntent)) {
            return "long";
        }
        if (plotGateDecision != null && plotGateDecision.allowed) {
            return "long";
        }
        return "medium";
    }

    private String dialogueMode(IntentState intentState, RelationshipState relationshipState, RelationalTensionState tensionState, PlotGateDecision plotGateDecision) {
        if ("meta_repair".equals(intentState.primaryIntent)) return "repair";
        if (tensionState != null && tensionState.guarded) return "guarded";
        if ("question_check".equals(intentState.primaryIntent)) return "direct_answer";
        if ("romantic_probe".equals(intentState.primaryIntent) && relationshipState != null && relationshipState.affectionScore < 25) {
            return "slow_burn";
        }
        if (plotGateDecision != null && plotGateDecision.allowed) return "scene_push";
        return intentState.needsEmpathy ? "emotional_hold" : "gentle_expand";
    }
}

class InitiativePolicyService {
    InitiativeDecision decide(
            IntentState intentState,
            ResponsePlan responsePlan,
            EmotionState emotionState,
            RelationalTensionState tensionState,
            PlotGateDecision plotGateDecision,
            String replySource,
            String nowIso
    ) {
        InitiativeDecision decision = new InitiativeDecision();
        decision.allowed = responsePlan != null && responsePlan.allowFollowupQuestion;
        decision.level = responsePlan == null ? "low" : responsePlan.initiativeLevel;
        decision.action = decision.allowed ? defaultAction(intentState, plotGateDecision, replySource) : "hold";
        if (tensionState != null && tensionState.guarded) {
            decision.allowed = false;
            decision.level = "low";
            decision.action = "hold";
            decision.reason = "guarded";
        } else if (emotionState != null && emotionState.initiative < 20) {
            decision.allowed = false;
            decision.action = "hold";
            decision.reason = "low_initiative";
        } else {
            decision.reason = decision.allowed ? "natural" : "plan_blocked";
        }
        decision.updatedAt = nowIso;
        return decision;
    }

    private String defaultAction(IntentState intentState, PlotGateDecision plotGateDecision, String replySource) {
        if ("silence_heartbeat".equals(replySource) || "long_chat_heartbeat".equals(replySource)) {
            return "soft_ping";
        }
        if (plotGateDecision != null && plotGateDecision.allowed) {
            return "plot_prompt";
        }
        if ("scene_push".equals(intentState.primaryIntent)) {
            return "scene_followup";
        }
        if ("question_check".equals(intentState.primaryIntent)) {
            return "answer_then_followup";
        }
        return "light_followup";
    }
}

class BoundaryResponseService {
    private final List<String> dismissiveKeywords = List.of("哦", "呵", "随便", "算了", "行吧", "无所谓");
    private final List<String> offenseKeywords = List.of("滚", "烦", "讨厌", "有病", "恶心", "闭嘴", "离谱");
    private final List<String> apologyKeywords = List.of("对不起", "抱歉", "不好意思", "我错了", "别生气", "不是故意的");

    RelationalTensionState normalize(RelationalTensionState tensionState, String nowIso) {
        RelationalTensionState next = tensionState == null ? new RelationalTensionState() : tensionState;
        if (next.repairReadiness <= 0) {
            next.repairReadiness = 48;
        }
        if (next.updatedAt == null || next.updatedAt.isBlank()) {
            next.updatedAt = nowIso;
        }
        return next;
    }

    RelationalTensionState evaluate(
            String userMessage,
            RelationalTensionState tensionState,
            TurnEvaluation turnEvaluation,
            TemperamentProfile temperamentProfile,
            String nowIso
    ) {
        RelationalTensionState next = cloneState(normalize(tensionState, nowIso));
        String text = userMessage == null ? "" : userMessage;
        int dismissive = countMatches(text, dismissiveKeywords);
        int offense = countMatches(text, offenseKeywords);
        int apology = countMatches(text, apologyKeywords);
        int threshold = temperamentProfile == null ? 30 : Math.max(18, temperamentProfile.irritationThreshold * 10);

        next.annoyance = clamp(next.annoyance + dismissive * 12 + offense * 22 - apology * 14, 0, 100);
        next.hurt = clamp(next.hurt + offense * 15 + dismissive * 6 - apology * 10, 0, 100);
        next.recentBoundaryHits = clamp(next.recentBoundaryHits
                + (turnEvaluation != null && turnEvaluation.riskFlags.contains("boundary_hit") ? 1 : 0)
                + offense, 0, 6);
        next.repairReadiness = clamp(56 - next.annoyance / 2 + apology * 8, 0, 100);
        next.guarded = next.annoyance >= threshold || next.recentBoundaryHits >= 2;

        if (apology > 0 && next.annoyance <= threshold - 8) {
            next.guarded = false;
            next.recentBoundaryHits = Math.max(0, next.recentBoundaryHits - 1);
        }
        next.updatedAt = nowIso;
        return next;
    }

    private RelationalTensionState cloneState(RelationalTensionState current) {
        RelationalTensionState next = new RelationalTensionState();
        next.annoyance = current.annoyance;
        next.hurt = current.hurt;
        next.guarded = current.guarded;
        next.repairReadiness = current.repairReadiness;
        next.recentBoundaryHits = current.recentBoundaryHits;
        next.updatedAt = current.updatedAt;
        return next;
    }

    private int countMatches(String text, List<String> keywords) {
        int matches = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                matches++;
            }
        }
        return matches;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

class PlotGateService {
    PlotGateDecision decide(
            StoryEvent candidate,
            SessionRecord session,
            SceneState nextSceneState,
            RelationshipState relationshipState,
            RelationalTensionState tensionState,
            String nowIso
    ) {
        PlotGateDecision decision = new PlotGateDecision();
        decision.allowed = false;
        decision.requiredGap = 4;
        decision.requiredRelationFloor = candidate == null ? 0 : candidate.minAffection;
        decision.candidateEventId = candidate == null ? "" : candidate.id;
        decision.requiredScene = inferRequiredScene(candidate);
        if (candidate == null) {
            decision.blockedReason = "no_candidate";
            decision.triggerReason = "none";
            decision.updatedAt = nowIso;
            return decision;
        }
        if (tensionState != null && tensionState.guarded && candidate.keyChoiceEvent) {
            decision.blockedReason = "guarded";
            decision.triggerReason = "blocked_by_tension";
            decision.updatedAt = nowIso;
            return decision;
        }
        if (relationshipState != null && relationshipState.affectionScore < candidate.minAffection) {
            decision.blockedReason = "affection_floor";
            decision.triggerReason = "below_relation_floor";
            decision.updatedAt = nowIso;
            return decision;
        }
        int currentTurn = session == null ? 0 : session.userTurnCount + 1;
        int lastPlotTurn = session != null && session.plotArcState != null ? session.plotArcState.lastPlotTurn : 0;
        if (lastPlotTurn > 0 && currentTurn - lastPlotTurn < decision.requiredGap) {
            decision.blockedReason = "gap_too_short";
            decision.triggerReason = "cooldown_gap";
            decision.updatedAt = nowIso;
            return decision;
        }
        if (!sceneMatches(decision.requiredScene, nextSceneState)) {
            decision.blockedReason = "scene_mismatch";
            decision.triggerReason = "scene_not_ready";
            decision.updatedAt = nowIso;
            return decision;
        }
        if (session != null && session.plotArcState != null && session.plotArcState.checkpointReady) {
            decision.blockedReason = "checkpoint_ready";
            decision.triggerReason = "checkpoint_holds_plot";
            decision.updatedAt = nowIso;
            return decision;
        }
        decision.allowed = true;
        decision.blockedReason = "";
        decision.triggerReason = candidate.keyChoiceEvent ? "key_choice_window" : "natural_progression";
        decision.updatedAt = nowIso;
        return decision;
    }

    private String inferRequiredScene(StoryEvent event) {
        if (event == null) return "";
        String text = ((event.title == null ? "" : event.title) + " " + (event.theme == null ? "" : event.theme)).toLowerCase();
        if (text.contains("操场") || text.contains("夜跑")) return "操场";
        if (text.contains("图书馆")) return "图书馆";
        if (text.contains("食堂")) return "食堂";
        if (text.contains("宿舍")) return "宿舍";
        if (text.contains("市区")) return "市区";
        return "";
    }

    private boolean sceneMatches(String requiredScene, SceneState sceneState) {
        if (requiredScene == null || requiredScene.isBlank()) {
            return true;
        }
        if (sceneState == null || sceneState.location == null) {
            return false;
        }
        return sceneState.location.contains(requiredScene)
                || requiredScene.contains(sceneState.location)
                || sceneState.sceneSummary != null && sceneState.sceneSummary.contains(requiredScene);
    }
}

class RealityGuardService {
    RealityEnvelope buildEnvelope(TimeContext timeContext, WeatherContext weatherContext, SceneState sceneState, SearchGroundingSummary grounding) {
        RealityEnvelope envelope = new RealityEnvelope();
        envelope.timeTruth = timeContext == null ? "" : blank(timeContext.dayPart) + " " + blank(timeContext.localTime);
        envelope.weatherTruth = weatherContext == null ? "" : blank(weatherContext.city) + " " + blank(weatherContext.summary);
        envelope.sceneTruth = sceneState == null ? "" : blank(sceneState.location) + " " + blank(sceneState.sceneSummary);
        envelope.interactionTruth = sceneState == null ? "" : blank(sceneState.interactionMode);
        envelope.searchGrounding = grounding;
        return envelope;
    }

    SearchGroundingSummary groundingFromDecision(SearchDecision decision) {
        SearchGroundingSummary grounding = new SearchGroundingSummary();
        grounding.mode = decision == null ? "skip" : decision.mode;
        grounding.query = decision == null ? "" : decision.query;
        grounding.confidence = decision == null ? "none" : ("must_search".equals(decision.mode) ? "guarded" : "medium");
        grounding.canQuote = false;
        grounding.mustDeclineIfMissing = decision != null && decision.mustNotGuess;
        return grounding;
    }

    RealityGuardResult auditAndRepair(LlmResponse reply, RealityEnvelope envelope, String userMessage, SceneState sceneState) {
        if (reply == null) {
            RealityAudit emptyAudit = new RealityAudit();
            emptyAudit.timeConsistent = true;
            emptyAudit.weatherConsistent = true;
            emptyAudit.sceneConsistent = true;
            emptyAudit.interactionConsistent = true;
            emptyAudit.grounded = true;
            SceneConsistencyAudit sceneAudit = new SceneConsistencyAudit();
            sceneAudit.consistent = true;
            sceneAudit.fixed = false;
            sceneAudit.reason = "empty";
            return new RealityGuardResult(reply, emptyAudit, sceneAudit);
        }

        String speech = safe(reply.speechText);
        String action = safe(reply.actionText);
        String scene = safe(reply.sceneText);
        String full = speech + "\\n" + action + "\\n" + scene;
        RealityAudit audit = new RealityAudit();
        SceneConsistencyAudit sceneAudit = new SceneConsistencyAudit();
        audit.timeConsistent = true;
        audit.weatherConsistent = true;
        audit.sceneConsistent = true;
        audit.interactionConsistent = true;
        audit.grounded = envelope == null || envelope.searchGrounding == null || !envelope.searchGrounding.mustDeclineIfMissing;
        sceneAudit.consistent = true;
        sceneAudit.fixed = false;
        sceneAudit.reason = "ok";

        String interactionMode = sceneState == null ? "" : safe(sceneState.interactionMode);
        if ("face_to_face".equals(interactionMode) || "mixed_transition".equals(interactionMode)) {
            String repairedAction = replaceMessagingLanguage(action);
            String repairedSpeech = replaceMessagingLanguage(speech);
            String repairedScene = replaceMessagingLanguage(scene);
            if (!repairedAction.equals(action) || !repairedSpeech.equals(speech) || !repairedScene.equals(scene)) {
                audit.interactionConsistent = false;
                audit.notes.add("interaction_repaired");
                sceneAudit.consistent = false;
                sceneAudit.fixed = true;
                sceneAudit.reason = "removed_online_chat_language";
                action = repairedAction;
                speech = repairedSpeech;
                scene = repairedScene;
            }
        }

        if (envelope != null && envelope.weatherTruth != null && !containsRain(envelope.weatherTruth)) {
            String repairedSpeech = replaceNonRainWeatherClaims(speech);
            String repairedAction = replaceNonRainWeatherClaims(action);
            String repairedScene = replaceNonRainWeatherClaims(scene);
            if (!repairedSpeech.equals(speech) || !repairedAction.equals(action) || !repairedScene.equals(scene)) {
                audit.weatherConsistent = false;
                audit.notes.add("weather_repaired");
                speech = repairedSpeech;
                action = repairedAction;
                scene = repairedScene;
            }
        }

        if (isAfternoon(envelope == null ? "" : envelope.timeTruth)) {
            String repairedSpeech = replaceAfternoonSunsetClaims(speech);
            String repairedScene = replaceAfternoonSunsetClaims(scene);
            if (!repairedSpeech.equals(speech) || !repairedScene.equals(scene)) {
                audit.timeConsistent = false;
                audit.notes.add("time_repaired");
                speech = repairedSpeech;
                scene = repairedScene;
            }
        }

        if (sceneState != null && sceneState.location != null && !sceneState.location.isBlank()) {
            String normalizedLocation = sceneState.location;
            if (mentionsConflictingLocation(full, normalizedLocation)) {
                audit.sceneConsistent = false;
                audit.notes.add("scene_repaired");
                sceneAudit.consistent = false;
                sceneAudit.fixed = true;
                sceneAudit.reason = "scene_conflict";
                if (scene.isBlank()) {
                    scene = safe(sceneState.sceneSummary);
                } else {
                    scene = keepCurrentScene(scene, normalizedLocation);
                }
                speech = removeForeignLocations(speech, normalizedLocation);
                action = removeForeignLocations(action, normalizedLocation);
            }
        }

        LlmResponse repaired = new LlmResponse(
                speech.isBlank() ? reply.replyText : speech,
                scene,
                action,
                speech.isBlank() ? reply.speechText : speech,
                reply.emotionTag,
                reply.confidenceStatus,
                reply.tokenUsage,
                reply.errorCode,
                reply.fallbackUsed,
                reply.provider
        );
        return new RealityGuardResult(repaired, audit, sceneAudit);
    }

    private String replaceMessagingLanguage(String text) {
        return safe(text)
                .replace("给你发了条消息", "把话轻轻接了回来")
                .replace("给你发消息", "把话轻轻接了回来")
                .replace("看到你回复", "迎上你的目光")
                .replace("看着你回复", "看着你的反应")
                .replace("屏幕亮了一下", "目光轻轻晃了晃")
                .replace("手指在键盘上敲", "指尖轻轻收紧又松开")
                .replace("打字", "斟酌着开口")
                .replace("聊天框", "气氛");
    }

    private String replaceNonRainWeatherClaims(String text) {
        return safe(text)
                .replace("下雨", "风有点凉")
                .replace("雨幕", "天色")
                .replace("雨丝", "晚风")
                .replace("带伞", "路上注意别着凉");
    }

    private String replaceAfternoonSunsetClaims(String text) {
        return safe(text)
                .replace("日落", "这会儿的天色")
                .replace("夕阳", "光线")
                .replace("黄昏", "下午");
    }

    private boolean containsRain(String text) {
        return text.contains("雨") || text.toLowerCase().contains("rain");
    }

    private boolean mentionsConflictingLocation(String text, String currentLocation) {
        for (String candidate : List.of("图书馆", "操场", "食堂", "宿舍", "市区")) {
            if (!candidate.equals(currentLocation) && text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String keepCurrentScene(String sceneText, String currentLocation) {
        return removeForeignLocations(sceneText, currentLocation);
    }

    private String removeForeignLocations(String text, String currentLocation) {
        String next = safe(text);
        for (String candidate : List.of("图书馆", "操场", "食堂", "宿舍", "市区")) {
            if (!candidate.equals(currentLocation)) {
                next = next.replace(candidate, "这里");
            }
        }
        return next;
    }

    private boolean isAfternoon(String timeTruth) {
        String text = safe(timeTruth);
        int hour = extractHour(text);
        return hour >= 12 && hour < 17;
    }

    private int extractHour(String text) {
        int colon = text.indexOf(':');
        if (colon >= 2) {
            try {
                return Integer.parseInt(text.substring(colon - 2, colon));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }
}

class HumanizationEvaluationService {
    HumanizationAudit evaluate(
            String userMessage,
            LlmResponse reply,
            IntentState intentState,
            ResponsePlan responsePlan,
            MemoryUsePlan memoryUsePlan,
            RealityAudit realityAudit
    ) {
        HumanizationAudit audit = new HumanizationAudit();
        String speech = reply == null ? "" : (reply.speechText == null || reply.speechText.isBlank() ? reply.replyText : reply.speechText);
        audit.feltHeard = intentState == null || !intentState.needsEmpathy || containsAny(speech, List.of("我听到了", "我明白", "我知道", "我能感觉到", "先别急"));
        audit.answeredCoreQuestion = intentState == null
                || !"question_check".equals(intentState.primaryIntent)
                || (responsePlan != null && "answer_first".equals(responsePlan.firstMove) && !speech.isBlank());
        audit.usedMemoryNaturally = memoryUsePlan == null
                || "hold".equals(memoryUsePlan.useMode)
                || speech.length() < 120;
        audit.initiativeAppropriate = responsePlan == null
                || !"low".equals(responsePlan.initiativeLevel)
                || countQuestionMarks(speech) == 0;
        audit.sceneConsistent = realityAudit == null || (realityAudit.sceneConsistent && realityAudit.interactionConsistent);
        audit.emotionMatched = intentState == null || !intentState.needsEmpathy || containsAny(speech, List.of("抱一下", "我在", "别怕", "慢慢说", "我接得住"));
        audit.overacted = speech.contains("！！！") || countQuestionMarks(speech) > 2;
        audit.tooMechanical = speech.length() < 4;
        if (!audit.feltHeard) audit.notes.add("needs_more_empathy");
        if (!audit.answeredCoreQuestion) audit.notes.add("missed_core_question");
        if (!audit.sceneConsistent) audit.notes.add("scene_conflict");
        if (audit.overacted) audit.notes.add("overacted");
        if (audit.tooMechanical) audit.notes.add("too_short");
        return audit;
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int countQuestionMarks(String text) {
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c == '?' || c == '？') {
                count++;
            }
        }
        return count;
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
            next.lastMemoryRelevanceReason = "本轮先以当前对话为主。";
        }
        trimTo(next.callbackCandidates, 8);
        trimTo(next.assistantOwnedThreads, 8);
        if (!next.sharedMoments.isEmpty()) {
            pushUniqueLimited(next.assistantOwnedThreads, "我还记得：" + next.sharedMoments.get(0), 8);
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

        if (!next.openLoops.isEmpty()) {
            pushUniqueLimited(next.callbackCandidates, next.openLoops.get(0), 8);
        }
        if (!next.sharedMoments.isEmpty()) {
            pushUniqueLimited(next.assistantOwnedThreads, "我还记得：" + next.sharedMoments.get(0), 8);
        } else if (event != null && event.title != null && !event.title.isBlank()) {
            pushUniqueLimited(next.assistantOwnedThreads, "我还在想着刚才那段关于" + event.title + "的气氛。", 8);
        }
        return next;
    }

    @Override
    MemoryUsePlan planMemoryUse(MemorySummary summary, String userMessage, String replySource, String sceneFrame) {
        MemorySummary normalized = normalizeSummary(summary, IsoTimes.now());
        MemoryUsePlan plan = new MemoryUsePlan();

        String cue = (userMessage == null || userMessage.isBlank()) ? sceneFrame : userMessage;
        MemoryRecall recall = recallRelevantMemories(normalized, cue == null ? "" : cue, 3);
        List<String> filteredMemories = new ArrayList<>();
        for (String item : recall.selectedMemories) {
            if (!isWeakGreetingMemory(item)) {
                filteredMemories.add(item);
            }
        }
        plan.selectedMemories.addAll(filteredMemories);
        plan.mergedMemoryText = filteredMemories.isEmpty() ? "" : String.join("；", filteredMemories);

        if (normalized.callbackCandidates != null) {
            plan.callbackCandidates.addAll(normalized.callbackCandidates.stream().limit(3).toList());
        }
        if (normalized.assistantOwnedThreads != null) {
            plan.assistantOwnedThreads.addAll(normalized.assistantOwnedThreads.stream().limit(3).toList());
        }

        if ("silence_heartbeat".equals(replySource) || "long_chat_heartbeat".equals(replySource)) {
            if (!plan.callbackCandidates.isEmpty()) {
                plan.useMode = "light";
                plan.relevanceReason = "适合轻轻回调还没聊完的话题。";
                if (plan.mergedMemoryText == null || plan.mergedMemoryText.isBlank()) {
                    plan.mergedMemoryText = String.join("；", plan.callbackCandidates);
                }
            } else if (!plan.assistantOwnedThreads.isEmpty()) {
                plan.useMode = "light";
                plan.relevanceReason = "适合把角色自己挂念的事轻轻带回来。";
                if (plan.mergedMemoryText == null || plan.mergedMemoryText.isBlank()) {
                    plan.mergedMemoryText = String.join("；", plan.assistantOwnedThreads);
                }
            } else {
                plan.useMode = "hold";
                plan.relevanceReason = "先接当前场景，不主动翻旧账。";
            }
        } else if (plan.mergedMemoryText != null && !plan.mergedMemoryText.isBlank()) {
            plan.useMode = plan.selectedMemories.size() >= 2 ? "deep" : "light";
            plan.relevanceReason = "这轮话题和旧记忆有关，适合自然承接。";
        } else {
            plan.useMode = "hold";
            plan.relevanceReason = "本轮先围绕当前输入继续。";
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
            return segment + "，去图书馆。";
        }
        if (!segment.contains("散步") && userMessage != null && userMessage.contains("散步")) {
            return segment + "，一起散步。";
        }
        return segment;
    }

    private String extractCallbackTopic(String userMessage) {
        return firstSegmentWith(userMessage, List.of("图书馆", "下雨", "雨天", "热可可", "夜市", "天台", "晚风", "操场", "宿舍"));
    }

    private String firstSegmentWith(String userMessage, List<String> markers) {
        if (userMessage == null || userMessage.isBlank()) {
            return "";
        }
        String[] segments = userMessage.trim().split("[，。！？；,.!?]");
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

    private boolean isWeakGreetingMemory(String item) {
        if (item == null || item.isBlank()) {
            return true;
        }
        String compact = item.replace(" ", "").trim();
        String tail = compact.contains("：") ? compact.substring(compact.lastIndexOf("：") + 1).trim() : compact;
        return tail.length() <= 8
                || tail.contains("你好")
                || tail.contains("在吗")
                || tail.contains("嗨")
                || tail.contains("好啊")
                || tail.contains("你好啊");
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
            lines.add("明确事实：" + String.join("；", facts));
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
            lines.add("未完成话题：" + String.join("；", openLoops));
        }
        return String.join("\\n", lines);
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
            for (String item : sceneCandidates) {
                if (!plan.selectedMemories.contains(item)) {
                    plan.selectedMemories.add(item);
                }
            }
            plan.mergedMemoryText = String.join("；", sceneCandidates);
        }
        return plan;
    }

    private void rememberFacts(MemorySummary summary, String userMessage, String nowIso) {
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }
        rememberFact(summary, "city_area", extractFact(userMessage, List.of("我之前在", "我在市区", "我平时在")), "confirmed", nowIso);
        rememberFact(summary, "drink_preference", extractFact(userMessage, List.of("我喜欢", "我最喜欢", "我更喜欢")), "confirmed", nowIso);
        rememberFact(summary, "school_place", extractFact(userMessage, List.of("我一般在", "我常在")), "likely", nowIso);
    }

    private void rememberScene(MemorySummary summary, String userMessage, String nowIso) {
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }
        String location = "";
        String scene = "";
        if (userMessage.contains("宿舍")) {
            location = "宿舍";
            scene = "场景已经转到宿舍附近，气氛更像并肩慢慢走着继续聊。";
        } else if (userMessage.contains("图书馆")) {
            location = "图书馆";
            scene = "你们把话题带到了图书馆相关的安静场景里。";
        } else if (userMessage.contains("食堂")) {
            location = "食堂";
            scene = "场景被带到了食堂这一侧，更像日常陪伴。";
        } else if (userMessage.contains("路上") || userMessage.contains("送你回") || userMessage.contains("一起走")) {
            location = "路上";
            scene = "场景开始从原地移动，变成并肩走着继续聊天。";
        }
        if (scene.isBlank()) {
            return;
        }
        for (SceneLedgerItem item : summary.sceneLedger) {
            if (item != null && scene.equals(item.summary)) {
                item.location = location;
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
        if (containsAny(userMessage, List.of("下次", "改天", "以后", "再说", "约好"))) {
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
                int stop = findStop(value);
                return value.substring(0, stop).trim();
            }
        }
        return "";
    }

    private int findStop(String value) {
        int stop = value.length();
        for (String token : List.of("，", "。", "！", "？", ";", "；", ",", ".", "!", "?")) {
            int index = value.indexOf(token);
            if (index >= 0) {
                stop = Math.min(stop, index);
            }
        }
        return stop;
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\s+", "");
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
        nextEmotion.safety = clamp(nextEmotion.safety + openness + (evaluation.behaviorTags.contains("灏婇噸杈圭晫") ? 2 : 0) - (strained ? 3 : 0), 0, 100);
        nextEmotion.longing = clamp(nextEmotion.longing + closeness + (evaluation.stageChanged ? 3 : 0) - (strained ? 2 : 0), 0, 100);
        nextEmotion.initiative = clamp(nextEmotion.initiative + (evaluation.affectionDelta.total > 0 ? 2 : -2) + (evaluation.behaviorTags.contains("涓诲姩鍥炲簲") ? 1 : 0), 0, 100);
        nextEmotion.vulnerability = clamp(nextEmotion.vulnerability + openness + (evaluation.behaviorTags.contains("鎺ヤ綇鎯呯华") ? 2 : 0) - (strained ? 1 : 0), 0, 100);
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
        next.currentMood = "缁х画鍙戝睍".equals(nextState.endingCandidate) ? "warm" : deriveMood(next, new TurnEvaluation(nextState, new Delta()));
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
        if (emotion.vulnerability >= 40 && evaluation.behaviorTags.contains("鐪熻瘹鍒嗕韩")) {
            return "protective";
        }
        return "calm";
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

class PlotDirectorAgentService {
    private static final String DEFAULT_PLOT_MODEL = "qwen-plus";
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final Path nodeBridgeScript;

    PlotDirectorAgentService() {
        this.baseUrl = "";
        this.apiKey = "";
        this.model = DEFAULT_PLOT_MODEL;
        this.timeout = Duration.ofMillis(12000);
        this.nodeBridgeScript = null;
    }

    PlotDirectorAgentService(AppConfig config) {
        this.baseUrl = config == null ? "" : safe(config.plotLlmBaseUrl);
        this.apiKey = config == null ? "" : safe(config.plotLlmApiKey);
        this.model = config == null || safe(config.plotLlmModel).isBlank() ? DEFAULT_PLOT_MODEL : safe(config.plotLlmModel);
        this.timeout = config == null || config.plotLlmTimeout == null ? Duration.ofMillis(12000) : config.plotLlmTimeout;
        this.nodeBridgeScript = config == null ? null : config.rootDir.resolve("scripts").resolve("plot-director-agent.mjs");
    }

    PlotDirectorAgentDecision decide(
            String userMessage,
            String replySource,
            int currentTurn,
            int gap,
            int forcePlotAtTurn,
            boolean explicitTransition,
            int signal,
            EmotionState emotionState,
            RelationshipState relationshipState,
            MemorySummary memorySummary,
            TurnContext turnContext
    ) {
        String text = userMessage == null ? "" : userMessage.trim();
        PlotDirectorAgentDecision guard = guardDecision(text, replySource, gap, explicitTransition);
        if (guard != null) {
            return guard;
        }

        PlotDirectorAgentDecision local = localDecision(text, replySource, currentTurn, gap, forcePlotAtTurn, signal);
        if (!remoteEnabled()) {
            return local;
        }

        try {
            PlotDirectorAgentDecision remote = callRemoteDirector(
                    text,
                    replySource,
                    currentTurn,
                    gap,
                    forcePlotAtTurn,
                    signal,
                    emotionState,
                    relationshipState,
                    memorySummary,
                    turnContext
            );
            return sanitizeRemoteDecision(remote, local, replySource, gap, signal);
        } catch (Exception ex) {
            return new PlotDirectorAgentDecision(
                    local.action,
                    "local_fallback:" + ex.getClass().getSimpleName(),
                    local.sceneCue,
                    local.shouldAdvance
            );
        }
    }

    private PlotDirectorAgentDecision guardDecision(String text, String replySource, int gap, boolean explicitTransition) {
        if (explicitTransition) {
            return new PlotDirectorAgentDecision(
                    "transition_only",
                    "user_requested_scene_transition",
                    transitionCue(text),
                    false
            );
        }
        if ("user_turn".equals(replySource) && isShortReaction(text)) {
            return new PlotDirectorAgentDecision(
                    "hold_plot",
                    "short_reaction_should_answer_context_first",
                    "",
                    false
            );
        }
        if (!"user_turn".equals(replySource) && !"long_chat_heartbeat".equals(replySource)) {
            return new PlotDirectorAgentDecision("hold_plot", "not_user_or_long_chat_window", "", false);
        }
        if (gap < 4) {
            return new PlotDirectorAgentDecision("hold_plot", "plot_gap_too_short", "", false);
        }
        return null;
    }

    private PlotDirectorAgentDecision localDecision(
            String text,
            String replySource,
            int currentTurn,
            int gap,
            int forcePlotAtTurn,
            int signal
    ) {
        if (currentTurn >= forcePlotAtTurn && gap >= 5 && signal >= 2) {
            return new PlotDirectorAgentDecision(
                    "advance_plot",
                    "force_window_with_context_signal",
                    "\u5267\u60c5\u53ea\u987a\u7740\u521a\u624d\u7684\u8bdd\u5f80\u524d\u534a\u6b65\uff0c\u4e0d\u8df3\u5f00\u5f53\u524d\u7528\u6237\u610f\u601d\u3002",
                    true
            );
        }
        if (signal >= 4 && gap >= 4) {
            return new PlotDirectorAgentDecision(
                    "advance_plot",
                    "strong_context_signal",
                    "\u628a\u5f53\u524d\u8bdd\u9898\u81ea\u7136\u53d8\u6210\u4e00\u4e2a\u5c0f\u8282\u62cd\uff0c\u800c\u4e0d\u662f\u5207\u8d70\u8bdd\u9898\u3002",
                    true
            );
        }
        if ("long_chat_heartbeat".equals(replySource) && gap >= 6 && signal >= 2) {
            return new PlotDirectorAgentDecision(
                    "heartbeat_nudge",
                    "long_chat_soft_nudge",
                    "\u957f\u804a\u540e\u7684\u8f7b\u63a8\u52a8\uff0c\u53ea\u8865\u6c14\u6c1b\uff0c\u4e0d\u5f00\u5927\u5267\u60c5\u3002",
                    true
            );
        }
        return new PlotDirectorAgentDecision("hold_plot", "director_prefers_current_conversation", "", false);
    }

    private PlotDirectorAgentDecision callRemoteDirector(
            String userMessage,
            String replySource,
            int currentTurn,
            int gap,
            int forcePlotAtTurn,
            int signal,
            EmotionState emotionState,
            RelationshipState relationshipState,
            MemorySummary memorySummary,
            TurnContext turnContext
    ) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.2);
        payload.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content", "You are the hidden plot director for a campus romance chat game. Return ONLY strict JSON. Do not write dialogue. Do not force romance. Keep continuity, avoid sudden scene jumps, and only advance the plot when the current chat naturally supports it."
                ),
                Map.of(
                        "role", "user",
                        "content", Json.stringify(buildDirectorInput(
                                userMessage,
                                replySource,
                                currentTurn,
                                gap,
                                forcePlotAtTurn,
                                signal,
                                emotionState,
                                relationshipState,
                                memorySummary,
                                turnContext
                        ))
                )
        ));

        if (nodeBridgeScript != null && Files.exists(nodeBridgeScript)) {
            return callNodeDirector(payload);
        }
        return callHttpDirector(payload);
    }

    private PlotDirectorAgentDecision callNodeDirector(Map<String, Object> payload) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("node", nodeBridgeScript.toString());
        Map<String, String> environment = builder.environment();
        environment.put("PLOT_LLM_BASE_URL", baseUrl);
        environment.put("PLOT_LLM_API_KEY", apiKey);
        environment.put("PLOT_LLM_MODEL", model);

        Process process = builder.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(Json.stringify(payload).getBytes(StandardCharsets.UTF_8));
        }

        boolean finished;
        try {
            finished = process.waitFor(Math.max(1000, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("plot_node_interrupted", ex);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("plot_node_timeout");
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IOException("plot_node_exit_" + process.exitValue() + ":" + truncate(stderr, 180));
        }
        return parseDirectorJson(stdout);
    }

    private PlotDirectorAgentDecision callHttpDirector(Map<String, Object> payload) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("plot_llm_interrupted", ex);
        }

        int status = response.statusCode();
        String raw = response.body() == null ? "" : response.body();
        if (status < 200 || status >= 300) {
            throw new IOException("plot_llm_http_" + status + ":" + raw);
        }

        Map<String, Object> parsed = Json.asObject(Json.parse(raw));
        List<Object> choices = Json.asArray(parsed.get("choices"));
        if (choices.isEmpty()) {
            throw new IOException("plot_llm_empty_choices");
        }
        Map<String, Object> choice = Json.asObject(choices.get(0));
        Map<String, Object> message = Json.asObject(choice.get("message"));
        return parseDirectorJson(Json.asString(message.get("content")));
    }

    private Map<String, Object> buildDirectorInput(
            String userMessage,
            String replySource,
            int currentTurn,
            int gap,
            int forcePlotAtTurn,
            int signal,
            EmotionState emotionState,
            RelationshipState relationshipState,
            MemorySummary memorySummary,
            TurnContext turnContext
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("task", "Choose one action: hold_plot, advance_plot, heartbeat_nudge.");
        input.put("output_schema", Map.of(
                "action", "hold_plot|advance_plot|heartbeat_nudge",
                "reason", "short snake_case reason",
                "sceneCue", "one short Chinese cue for the chat agent, empty when holding",
                "shouldAdvance", "boolean",
                "confidence", "0-100 integer",
                "whyNow", "brief explanation of why this timing is appropriate",
                "riskIfAdvance", "short reason why advancing may hurt continuity",
                "requiredUserSignal", "what user signal is still needed before advancing"
        ));
        input.put("userMessage", userMessage);
        input.put("replySource", replySource);
        input.put("currentTurn", currentTurn);
        input.put("gapSinceLastPlot", gap);
        input.put("forcePlotAtTurn", forcePlotAtTurn);
        input.put("contextSignal", signal);
        Map<String, Object> turnContextPayload = new LinkedHashMap<>();
        turnContextPayload.put("primaryIntent", turnContext == null ? "" : safe(turnContext.primaryIntent));
        turnContextPayload.put("secondaryIntent", turnContext == null ? "" : safe(turnContext.secondaryIntent));
        turnContextPayload.put("clarity", turnContext == null ? "" : safe(turnContext.clarity));
        turnContextPayload.put("userEmotion", turnContext == null ? "" : safe(turnContext.userEmotion));
        turnContextPayload.put("affectionDeltaTotal", turnContext == null ? 0 : turnContext.affectionDeltaTotal);
        turnContextPayload.put("behaviorTags", turnContext == null || turnContext.behaviorTags == null ? List.of() : turnContext.behaviorTags);
        turnContextPayload.put("riskFlags", turnContext == null || turnContext.riskFlags == null ? List.of() : turnContext.riskFlags);
        turnContextPayload.put("sceneLocation", turnContext == null ? "" : safe(turnContext.sceneLocation));
        turnContextPayload.put("interactionMode", turnContext == null ? "" : safe(turnContext.interactionMode));
        turnContextPayload.put("continuityObjective", turnContext == null ? "" : safe(turnContext.continuityObjective));
        turnContextPayload.put("continuityAcceptedPlan", turnContext == null ? "" : safe(turnContext.continuityAcceptedPlan));
        turnContextPayload.put("continuityNextBestMove", turnContext == null ? "" : safe(turnContext.continuityNextBestMove));
        turnContextPayload.put("continuityGuards", turnContext == null || turnContext.continuityGuards == null ? List.of() : turnContext.continuityGuards);
        input.put("turnContext", turnContextPayload);
        input.put("emotion", Map.of(
                "warmth", emotionState == null ? 0 : emotionState.warmth,
                "safety", emotionState == null ? 0 : emotionState.safety,
                "longing", emotionState == null ? 0 : emotionState.longing,
                "initiative", emotionState == null ? 0 : emotionState.initiative,
                "mood", emotionState == null ? "" : safe(emotionState.currentMood)
        ));
        input.put("relationship", Map.of(
                "stage", relationshipState == null ? "" : safe(relationshipState.relationshipStage),
                "score", relationshipState == null ? 0 : relationshipState.affectionScore,
                "closeness", relationshipState == null ? 0 : relationshipState.closeness,
                "trust", relationshipState == null ? 0 : relationshipState.trust,
                "resonance", relationshipState == null ? 0 : relationshipState.resonance
        ));
        input.put("memory", Map.of(
                "openLoops", limitList(memorySummary == null ? null : memorySummary.openLoops, 4),
                "sharedMoments", limitList(memorySummary == null ? null : memorySummary.sharedMoments, 4),
                "callbackCandidates", limitList(memorySummary == null ? null : memorySummary.callbackCandidates, 3)
        ));
        input.put("rules", List.of(
                "If the user is only reacting briefly, do not advance plot.",
                "If contextSignal is weak, hold plot.",
                "A plot beat must continue the current chat, not replace it.",
                "Do not invent a night run, rain, library, phone chat, or confession unless the context already supports it.",
                "Use advance_plot only when it improves continuity and there has been enough spacing."
        ));
        return input;
    }

    private PlotDirectorAgentDecision parseDirectorJson(String content) throws IOException {
        String json = extractJson(content);
        Map<String, Object> object = Json.asObject(Json.parse(json));
        String action = safe(Json.asString(object.get("action")));
        String reason = safe(Json.asString(object.get("reason")));
        String sceneCue = truncate(safe(Json.asString(object.get("sceneCue"))), 140);
        boolean shouldAdvance = Json.asBoolean(object.get("shouldAdvance"));
        int confidence = Json.asInt(object.get("confidence"), defaultRemoteConfidence(action, shouldAdvance));
        String whyNow = truncate(safe(Json.asString(object.get("whyNow"))), 120);
        String riskIfAdvance = truncate(safe(Json.asString(object.get("riskIfAdvance"))), 120);
        String requiredUserSignal = truncate(safe(Json.asString(object.get("requiredUserSignal"))), 120);
        if (!List.of("hold_plot", "advance_plot", "heartbeat_nudge").contains(action)) {
            throw new IOException("plot_llm_bad_action");
        }
        if (reason.isBlank()) {
            reason = "remote_director";
        }
        return new PlotDirectorAgentDecision(action, "remote:" + reason, whyNow, sceneCue, shouldAdvance, confidence, riskIfAdvance, requiredUserSignal);
    }

    private PlotDirectorAgentDecision sanitizeRemoteDecision(
            PlotDirectorAgentDecision remote,
            PlotDirectorAgentDecision local,
            String replySource,
            int gap,
            int signal
    ) {
        if (remote == null) {
            return local;
        }
        if ("advance_plot".equals(remote.action) && (gap < 4 || signal < 2)) {
            return new PlotDirectorAgentDecision("hold_plot", "remote_blocked_by_gap_or_signal", "", false);
        }
        if ("advance_plot".equals(remote.action) && remote.confidence < 60) {
            return new PlotDirectorAgentDecision(
                    "hold_plot",
                    "remote_low_confidence:" + remote.reason,
                    remote.whyNow,
                    "",
                    false,
                    remote.confidence,
                    remote.riskIfAdvance,
                    remote.requiredUserSignal
            );
        }
        if ("heartbeat_nudge".equals(remote.action) && (!"long_chat_heartbeat".equals(replySource) || gap < 6)) {
            return new PlotDirectorAgentDecision("hold_plot", "remote_heartbeat_blocked", "", false);
        }
        boolean shouldAdvance = "advance_plot".equals(remote.action) || "heartbeat_nudge".equals(remote.action);
        return new PlotDirectorAgentDecision(
                remote.action,
                remote.reason,
                remote.whyNow,
                remote.sceneCue,
                shouldAdvance && remote.shouldAdvance,
                remote.confidence,
                remote.riskIfAdvance,
                remote.requiredUserSignal
        );
    }

    private int defaultRemoteConfidence(String action, boolean shouldAdvance) {
        if ("hold_plot".equals(action)) {
            return 76;
        }
        if ("heartbeat_nudge".equals(action)) {
            return 64;
        }
        return shouldAdvance ? 62 : 55;
    }

    private boolean remoteEnabled() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    private boolean isShortReaction(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        return compact.length() <= 6
                || compact.contains("\u54c8\u54c8")
                || compact.contains("\u563f\u563f")
                || compact.contains("\u55ef\u55ef")
                || compact.contains("\u597d\u554a")
                || compact.contains("\u662f\u7684")
                || compact.contains("\u7136\u540e\u5462");
    }

    private String transitionCue(String text) {
        if (text.contains("\u64cd\u573a")) {
            return "\u573a\u666f\u5148\u987a\u7740\u4f60\u7684\u63d0\u8bae\u8f6c\u5230\u64cd\u573a\u8fb9\uff0c\u5267\u60c5\u6682\u65f6\u4e0d\u62a2\u8dd1\u3002";
        }
        if (text.contains("\u5bbf\u820d")) {
            return "\u573a\u666f\u5148\u8f6c\u5230\u56de\u5bbf\u820d\u7684\u8def\u4e0a\uff0c\u8ba9\u79fb\u52a8\u8fc7\u7a0b\u81ea\u7136\u63a5\u4e0a\u3002";
        }
        if (text.contains("\u98df\u5802")) {
            return "\u573a\u666f\u5148\u8f6c\u5230\u53bb\u98df\u5802\u7684\u8def\u4e0a\uff0c\u8bdd\u9898\u8ddf\u7740\u6362\u6210\u66f4\u65e5\u5e38\u7684\u8282\u594f\u3002";
        }
        if (text.contains("\u56fe\u4e66\u9986")) {
            return "\u573a\u666f\u5148\u8f6c\u56de\u56fe\u4e66\u9986\u9644\u8fd1\uff0c\u628a\u5b89\u9759\u7684\u6c1b\u56f4\u63a5\u4f4f\u3002";
        }
        return "\u573a\u666f\u5148\u6309\u7528\u6237\u63d0\u51fa\u7684\u65b9\u5411\u79fb\u52a8\uff0c\u5267\u60c5\u4fdd\u6301\u7b49\u5f85\u3002";
    }

    private String extractJson(String content) throws IOException {
        String text = safe(content).trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("plot_llm_non_json");
        }
        return text.substring(start, end + 1);
    }

    private List<String> limitList(List<String> values, int limit) {
        List<String> result = new ArrayList<>();
        if (values == null || limit <= 0) {
            return result;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private String trimTrailingSlash(String value) {
        String text = safe(value);
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private String truncate(String value, int maxLength) {
        String text = safe(value);
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

class PlotDirectorService {
    private final PlotDirectorAgentService plotDirectorAgentService;

    PlotDirectorService() {
        this(new PlotDirectorAgentService());
    }

    PlotDirectorService(PlotDirectorAgentService plotDirectorAgentService) {
        this.plotDirectorAgentService = plotDirectorAgentService == null ? new PlotDirectorAgentService() : plotDirectorAgentService;
    }

    PlotState normalizePlot(PlotState plotState, String nowIso) {
        PlotState next = plotState == null ? new PlotState() : plotState;
        if (next.phase == null || next.phase.isBlank()) {
            next.phase = "相识";
        }
        if (next.sceneFrame == null || next.sceneFrame.isBlank()) {
            next.sceneFrame = "你们还在慢慢把今晚的气氛铺开。";
        }
        if (next.openThreads == null) {
            next.openThreads = new ArrayList<>();
        }
        if (next.plotProgress == null || next.plotProgress.isBlank()) {
            next.plotProgress = "第0/10拍·相识";
        }
        if (next.nextBeatHint == null || next.nextBeatHint.isBlank()) {
            next.nextBeatHint = "先把日常节奏聊顺。";
        }
        if (next.forcePlotAtTurn <= 0) {
            next.forcePlotAtTurn = 7;
        }
        if (next.updatedAt == null || next.updatedAt.isBlank()) {
            next.updatedAt = nowIso;
        }
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
            next.sceneFrame = "你们还在慢慢把今晚的气氛铺开。";
        }
        if (next.openThreads == null) {
            next.openThreads = new ArrayList<>();
        }
        if (next.plotProgress == null || next.plotProgress.isBlank()) {
            next.plotProgress = "第0/10拍·相识";
        }
        if (next.nextBeatHint == null || next.nextBeatHint.isBlank()) {
            next.nextBeatHint = "先把日常节奏聊顺。";
        }
        if (next.runStatus == null || next.runStatus.isBlank()) {
            next.runStatus = "in_progress";
        }
        next.canContinue = true;
        next.canSettleScore = next.beatIndex > 0 && next.beatIndex % 10 == 0;
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
        return decide(session, userMessage, emotionState, relationshipState, memorySummary, timeContext, weatherContext, replySource, nowIso, null);
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
            String nowIso,
            TurnContext turnContext
    ) {
        PlotState current = normalizePlot(session.plotState, nowIso);
        PlotState next = clonePlot(current);
        int currentTurn = "user_turn".equals(replySource) ? session.userTurnCount + 1 : session.userTurnCount;
        int gap = Math.max(0, currentTurn - current.lastPlotTurn);
        boolean explicitTransition = isExplicitSceneTransition(userMessage);
        int signal = explicitTransition ? 0 : sceneSignal(userMessage, memorySummary, emotionState, weatherContext, timeContext, replySource);
        signal = adjustSignalWithTurnContext(signal, turnContext, currentTurn, current.forcePlotAtTurn);
        enrichTurnContext(turnContext, gap, signal, replySource, nowIso);
        PlotDirectorAgentDecision directorDecision = plotDirectorAgentService.decide(
                userMessage,
                replySource,
                currentTurn,
                gap,
                current.forcePlotAtTurn,
                explicitTransition,
                signal,
                emotionState,
                relationshipState,
                memorySummary,
                turnContext
        );
        enrichTurnContext(turnContext, directorDecision);
        boolean advanced = directorDecision.shouldAdvance;
        String detail = directorDetail(directorDecision);

        if (advanced) {
            next.beatIndex = current.beatIndex + 1;
            next.phase = phaseForBeat(next.beatIndex);
            next.lastPlotTurn = currentTurn;
            next.forcePlotAtTurn = currentTurn + 7;
            next.sceneFrame = mergeSceneCue(buildSceneFrame(next.phase, userMessage, emotionState, timeContext, weatherContext), directorDecision.sceneCue);
            next.nextBeatHint = nextBeatHint(next.beatIndex);
            next.plotProgress = "第" + next.beatIndex + "/10拍 · " + next.phase;
            pushUniqueLimited(next.openThreads, openThread(userMessage, memorySummary, next.phase) + " / " + directorDecision.reason, 6);
            next.updatedAt = nowIso;
            return new PlotDecision(next, null, null, true, "plot_push", next.sceneFrame, "", detail);
        }

        next.sceneFrame = "transition_only".equals(directorDecision.action)
                ? mergeSceneCue(current.sceneFrame, directorDecision.sceneCue)
                : buildAmbientScene(current.sceneFrame, emotionState, timeContext, weatherContext);
        next.updatedAt = nowIso;
        return new PlotDecision(next, null, null, false, replySource, next.sceneFrame, "", detail);
    }

    private void enrichTurnContext(TurnContext turnContext, int gap, int signal, String replySource, String nowIso) {
        if (turnContext == null) {
            return;
        }
        turnContext.plotGap = gap;
        turnContext.plotSignal = signal;
        turnContext.replySource = replySource;
        turnContext.updatedAt = nowIso;
    }

    private void enrichTurnContext(TurnContext turnContext, PlotDirectorAgentDecision directorDecision) {
        if (turnContext == null || directorDecision == null) {
            return;
        }
        turnContext.plotDirectorAction = directorDecision.action;
        turnContext.plotWhyNow = directorDecision.whyNow;
        turnContext.plotDirectorConfidence = directorDecision.confidence;
        turnContext.plotRiskIfAdvance = directorDecision.riskIfAdvance;
        turnContext.requiredUserSignal = directorDecision.requiredUserSignal;
    }

    private int adjustSignalWithTurnContext(int signal, TurnContext turnContext, int currentTurn, int forcePlotAtTurn) {
        if (turnContext == null) {
            return signal;
        }
        int adjusted = signal;
        if (turnContext.affectionDeltaTotal > 0) {
            adjusted++;
        }
        if (turnContext.behaviorTags != null && !turnContext.behaviorTags.isEmpty()) {
            adjusted++;
        }
        if ("romantic_probe".equals(turnContext.primaryIntent) || "scene_push".equals(turnContext.primaryIntent)) {
            adjusted++;
        }
        if ("meta_repair".equals(turnContext.primaryIntent) || (turnContext.riskFlags != null && !turnContext.riskFlags.isEmpty())) {
            adjusted = Math.max(0, adjusted - 1);
        }
        if (currentTurn >= forcePlotAtTurn) {
            adjusted++;
        }
        return Math.max(0, adjusted);
    }

    private String directorDetail(PlotDirectorAgentDecision decision) {
        if (decision == null) {
            return "";
        }
        return decision.action
                + ":" + decision.reason
                + "|confidence=" + decision.confidence
                + (decision.whyNow.isBlank() ? "" : "|why=" + decision.whyNow)
                + (decision.riskIfAdvance.isBlank() ? "" : "|risk=" + decision.riskIfAdvance)
                + (decision.requiredUserSignal.isBlank() ? "" : "|need=" + decision.requiredUserSignal);
    }

    private String mergeSceneCue(String base, String cue) {
        String safeBase = base == null ? "" : base.trim();
        String safeCue = cue == null ? "" : cue.trim();
        if (safeCue.isBlank()) {
            return safeBase;
        }
        if (safeBase.isBlank() || safeBase.contains(safeCue)) {
            return safeCue;
        }
        return safeBase + " " + safeCue;
    }
    PlotState applyChoiceOutcome(PlotState plotState, StoryEvent event, ChoiceOption choice, TimeContext timeContext, WeatherContext weatherContext, String nowIso) {
        PlotState next = normalizePlot(plotState, nowIso);
        next.beatIndex = next.beatIndex + 1;
        next.phase = phaseForBeat(next.beatIndex);
        String outcome = choice == null ? "平缓" : switch (choice.outcomeType) {
            case "success" -> "更靠近";
            case "fail" -> "有点失手";
            default -> "还在试探";
        };
        String title = event == null || event.title == null ? "这个节点" : event.title;
        next.sceneFrame = "围绕“" + title + "”的这一步，让气氛变得" + outcome + "。";
        next.nextBeatHint = nextBeatHint(next.beatIndex);
        next.plotProgress = "第" + next.beatIndex + "/10拍·" + next.phase;
        pushUniqueLimited(next.openThreads, "顺着“" + title + "”之后的变化继续往前走", 6);
        next.updatedAt = nowIso;
        return next;
    }

    private int sceneSignal(String userMessage, MemorySummary memorySummary, EmotionState emotionState, WeatherContext weatherContext, TimeContext timeContext, String replySource) {
        int signal = 0;
        String text = userMessage == null ? "" : userMessage;
        if (text.length() >= 8) signal++;
        if (containsAny(text, List.of("上次", "之前", "还记得", "后来"))) signal++;
        if (containsAny(text, List.of("一起", "下次", "以后", "认真", "靠近", "喜欢"))) signal++;
        if (containsAny(text, List.of("涓婃", "涔嬪墠", "杩樿寰?", "鍚庢潵"))) signal++;
        if (containsAny(text, List.of("涓€璧?", "涓嬫", "浠ュ悗"))) signal++;
        if (memorySummary != null && memorySummary.openLoops != null && !memorySummary.openLoops.isEmpty()) signal++;
        if (emotionState != null && emotionState.longing >= 32) signal++;
        if (weatherContext != null && weatherContext.summary != null && !weatherContext.summary.isBlank()
                && containsAny(text, List.of("天气", "下雨", "晴", "冷", "热", "风"))) signal++;
        if (timeContext != null && timeContext.localTime != null && !timeContext.localTime.isBlank()
                && containsAny(text, List.of("今天", "今晚", "现在", "刚才", "明天", "下午", "晚上", "深夜"))) signal++;
        if ("long_chat_heartbeat".equals(replySource)) signal += 2;
        return signal;
    }

    private boolean isExplicitSceneTransition(String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        return containsAny(text, List.of(
                "去操场", "去食堂", "去图书馆", "回宿舍", "送你回宿舍", "送她回宿舍",
                "一起走", "路上", "出去走", "换个地方", "去外面", "到操场", "去市区"
        ));
    }
    private String phaseForBeat(int beatIndex) {
        if (beatIndex >= 9) return "收束";
        if (beatIndex >= 7) return "波动";
        if (beatIndex >= 5) return "靠近";
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
            case "升温" -> "你们的对话开始不只停在表面，语气也比刚才更认真。";
            case "靠近" -> "距离像被悄悄收短了一截，说话时已经不太需要绕开。";
            case "波动" -> "彼此都开始在意了，所以连一点停顿都显得有分量。";
            case "收束" -> "话已经走到该给方向的时候，谁都很难再装作只是随便聊聊。";
            default -> "你们还在把今晚的氛围慢慢铺开。";
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
        String moodScene = emotionScene(emotionState);
        String merged = (topicScene + " " + moodScene).trim();
        return merged.isBlank() ? "气氛还在缓慢铺开。" : merged;
    }

    private String topicalScene(String userMessage, TimeContext timeContext, WeatherContext weatherContext) {
        String text = userMessage == null ? "" : userMessage;
        if (text.contains("图书馆") || text.contains("复习") || text.contains("自习")) {
            return "靠窗那排位置安安静静的，连翻页声都像放轻了一点。";
        }
        if (text.contains("热可可") || text.contains("奶茶") || text.contains("咖啡")) {
            return "杯壁还留着一点热气，连说话声都像被暖得慢了些。";
        }
        if (text.contains("下雨") || text.contains("雨") || text.contains("伞")) {
            return "外面的天色像压低了一点，反而把这一小段安静衬得更近。";
        }
        if (text.contains("操场") || text.contains("散步") || text.contains("夜跑")) {
            return "风从走廊尽头掠过去，步子和语气都像慢下来一点。";
        }
        if (timeContext != null && timeContext.localTime != null && timeContext.localTime.startsWith("23")) {
            return "夜已经深了，很多白天收着的话在这时候反而更容易落下来。";
        }
        if (weatherContext != null && weatherContext.summary != null && weatherContext.summary.contains("雨")) {
            return "外面的雨声没有断，倒把这一小段安静衬得更近。";
        }
        return "";
    }

    private String emotionScene(EmotionState emotionState) {
        if (emotionState == null || emotionState.currentMood == null || emotionState.currentMood.isBlank()) {
            return "";
        }
        return switch (emotionState.currentMood) {
            case "warm" -> "这一刻的语气明显更软，也更像在等对方把后半句说完。";
            case "teasing" -> "那点想靠近的心思没藏得太严，语气里已经有了点逗留感。";
            case "protective" -> "连停顿里都带着一点想把人稳稳接住的耐心。";
            case "uneasy" -> "明明还想靠近，语气里却先多留了一层小心。";
            default -> "";
        };
    }

    private String openThread(String userMessage, MemorySummary memorySummary, String phase) {
        if (userMessage != null && !userMessage.isBlank()) {
            return "顺着“" + excerpt(userMessage, 16) + "”继续推进到" + phase;
        }
        if (memorySummary != null && memorySummary.openLoops != null && !memorySummary.openLoops.isEmpty()) {
            return memorySummary.openLoops.get(0);
        }
        return phase + "阶段继续推进";
    }

    private String excerpt(String text, int limit) {
        String compact = text == null ? "" : text.trim().replaceAll("\s+", "");
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

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

class PresenceHeartbeatService {
    private static final long OFFLINE_SECONDS = 45;
    private static final long SILENCE_MIN_SECONDS = 90;
    private static final long SILENCE_MAX_SECONDS = 150;
    private static final long LONG_CHAT_SECONDS = 6 * 60;
    private static final long LONG_CHAT_MIN_SILENCE_SECONDS = 75;

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
                && silenceSeconds >= LONG_CHAT_MIN_SILENCE_SECONDS
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
            next.location = "聊天现场";
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
            return "场景顺着这句话慢慢挪到" + next.location + "这边，接下来的话也更适合在这里继续。";
        }
        if (next.transitionPending) {
            return next.sceneSummary;
        }
        return "";
    }
    private String detectLocation(String text, String fallback) {
        if (text.contains("操场")) return "操场";
        if (text.contains("宿舍")) return "宿舍";
        if (text.contains("食堂")) return "食堂";
        if (text.contains("图书馆")) return "图书馆";
        if (text.contains("市区")) return "市区";
        if (text.contains("路上") || text.contains("送你回") || text.contains("送她回") || text.contains("一起走")) return "回去的路上";
        if (text.contains("手机") || text.contains("发消息") || text.contains("回消息")) return "线上聊天";
        if (text.contains("操场")) return "操场";
        if (text.contains("宿舍")) return "宿舍";
        if (text.contains("食堂")) return "食堂";
        if (text.contains("图书馆")) return "图书馆";
        if (text.contains("路上") || text.contains("送你回") || text.contains("一起走")) return "回去的路上";
        if (text.contains("手机") || text.contains("发消息")) return "线上聊天";
        return fallback == null || fallback.isBlank() ? "聊天现场" : fallback;
    }

    private String detectSubLocation(String text) {
        if (text.contains("窗边")) return "窗边";
        if (text.contains("门口")) return "门口";
        if (text.contains("走廊")) return "走廊";
        if (text.contains("看台")) return "看台";
        if (text.contains("窗边")) return "窗边";
        if (text.contains("门口")) return "门口";
        if (text.contains("走廊")) return "走廊";
        return "";
    }

    private String detectInteractionMode(String text, String fallback) {
        if (text.contains("打电话") || text.contains("电话")) return "phone_call";
        if (text.contains("发消息") || text.contains("回消息") || text.contains("聊天框") || text.contains("手机")) return "online_chat";
        if (text.contains("送你回") || text.contains("送她回") || text.contains("一起走") || text.contains("路上")) return "mixed_transition";
        if (text.contains("打电话") || text.contains("电话")) return "phone_call";
        if (text.contains("发消息") || text.contains("回消息") || text.contains("聊天框") || text.contains("手机")) return "online_chat";
        if (text.contains("送你回") || text.contains("一起走") || text.contains("路上")) return "mixed_transition";
        return fallback == null || fallback.isBlank() ? "face_to_face" : fallback;
    }

    private String buildSceneSummary(SceneState state, boolean changed, String userMessage) {
        if (changed) {
            return "场景被轻轻带到了" + state.location + "这一侧，接下来的话也跟着换了气氛。";
        }
        if (userMessage.contains("送你回") || userMessage.contains("一起走") || userMessage.contains("路上")) {
            return "场景开始从原地移动，变成并肩走着继续聊天。";
        }
        if (userMessage.contains("下雨") || userMessage.contains("雨")) {
            return "外面的天色压低了一点，反而把这一小段安静衬得更近。";
        }
        return state.sceneSummary == null || state.sceneSummary.isBlank() ? "你们还在同一个场景里，把话慢慢往下接。" : state.sceneSummary;
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
    SearchDecision decide(String userMessage, String replySource, SceneState sceneState, IntentState intentState) {
        if (userMessage == null || userMessage.isBlank()) {
            return new SearchDecision(false, "", "empty", "skip", false);
        }
        String text = userMessage.trim();
        boolean lyrics = containsAny(text, List.of("歌词", "台词", "原句", "完整句子"));
        boolean realtime = containsAny(text, List.of("天气", "今天", "现在", "新闻", "热搜", "最近"));
        boolean factual = containsAny(text, List.of("是什么", "资料", "介绍", "百科", "历史", "含义"));
        boolean proactiveAnchor = ("plot_push".equals(replySource) || "long_chat_heartbeat".equals(replySource)) && sceneState != null;
        if (lyrics) {
            return new SearchDecision(true, text, "lyrics", "must_search", true);
        }
        if (realtime) {
            return new SearchDecision(true, text, "realtime", "must_search", true);
        }
        if (factual && text.length() >= 6) {
            return new SearchDecision(true, text, "factual", "should_search", false);
        }
        if (proactiveAnchor) {
            return new SearchDecision(true, text, "anchor", "should_search", false);
        }
        if (intentState != null && "advice_seek".equals(intentState.primaryIntent) && text.length() >= 10) {
            return new SearchDecision(true, text, "contextual", "should_search", false);
        }
        return new SearchDecision(false, "", "skip", "skip", false);
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

class EnhancedPlotDirectorService extends PlotDirectorService {
    private final SceneDirectorService sceneDirectorService = new SceneDirectorService();

    EnhancedPlotDirectorService() {
        super();
    }

    EnhancedPlotDirectorService(PlotDirectorAgentService plotDirectorAgentService) {
        super(plotDirectorAgentService);
    }

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
        return decide(session, userMessage, emotionState, relationshipState, memorySummary, timeContext, weatherContext, replySource, nowIso, null);
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
            String nowIso,
            TurnContext turnContext
    ) {
        PlotDecision base = super.decide(session, userMessage, emotionState, relationshipState, memorySummary, timeContext, weatherContext, replySource, nowIso, turnContext);
        PlotArcState arc = normalizeArc(session.plotArcState, nowIso);
        SceneState previousScene = sceneDirectorService.normalize(session.sceneState, nowIso);
        int currentTurn = "user_turn".equals(replySource) ? session.userTurnCount + 1 : session.userTurnCount;
        SceneState nextScene = sceneDirectorService.evolve(previousScene, userMessage, timeContext, weatherContext, currentTurn, nowIso);
        enrichSceneTurnContext(turnContext, nextScene);

        PlotArcState nextArc = cloneArc(arc);
        nextArc.beatIndex = Math.max(arc.beatIndex, base.nextPlotState == null ? arc.beatIndex : base.nextPlotState.beatIndex);
        nextArc.arcIndex = Math.max(1, ((Math.max(1, nextArc.beatIndex) - 1) / 10) + 1);
        nextArc.phase = base.nextPlotState == null ? arc.phase : base.nextPlotState.phase;
        nextArc.sceneFrame = nextScene.sceneSummary;
        nextArc.openThreads = new ArrayList<>(base.nextPlotState == null ? arc.openThreads : base.nextPlotState.openThreads);
        nextArc.lastPlotTurn = base.nextPlotState == null ? arc.lastPlotTurn : base.nextPlotState.lastPlotTurn;
        nextArc.forcePlotAtTurn = base.nextPlotState == null ? arc.forcePlotAtTurn : base.nextPlotState.forcePlotAtTurn;
        nextArc.plotProgress = "第" + nextArc.beatIndex + "拍 / 第" + nextArc.arcIndex + "段";
        nextArc.nextBeatHint = base.nextPlotState == null ? arc.nextBeatHint : base.nextPlotState.nextBeatHint;
        nextArc.checkpointReady = base.advanced && nextArc.beatIndex > 0 && nextArc.beatIndex % 10 == 0;
        nextArc.runStatus = nextArc.checkpointReady ? "checkpoint_ready" : "in_progress";
        nextArc.endingCandidate = relationshipState == null ? "" : relationshipState.endingCandidate;
        nextArc.canSettleScore = nextArc.checkpointReady;
        nextArc.canContinue = nextArc.checkpointReady;
        if (nextArc.latestArcSummary == null || nextArc.checkpointReady) {
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
                sceneDirectorService.buildSceneText(previousScene, nextScene),
                base.plotDirectorReason
        );
    }

    private void enrichSceneTurnContext(TurnContext turnContext, SceneState nextScene) {
        if (turnContext == null || nextScene == null) {
            return;
        }
        turnContext.sceneLocation = nextScene.location == null ? "" : nextScene.location;
        turnContext.interactionMode = nextScene.interactionMode == null ? "" : nextScene.interactionMode;
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
            next.plotProgress = "第0拍 / 第1段";
        }
        if (next.nextBeatHint == null || next.nextBeatHint.isBlank()) {
            next.nextBeatHint = "先把当前气氛聊顺。";
        }
        if (next.runStatus == null || next.runStatus.isBlank()) {
            next.runStatus = "in_progress";
        }
        if (next.latestArcSummary == null) {
            next.latestArcSummary = buildCheckpointSummary(next, null, null, nowIso);
        }
        if (next.updatedAt == null || next.updatedAt.isBlank()) {
            next.updatedAt = nowIso;
        }
        return next;
    }

    private ArcCheckpointSummary buildCheckpointSummary(PlotArcState plotArcState, SceneState sceneState, RelationshipState relationshipState, String nowIso) {
        ArcCheckpointSummary summary = new ArcCheckpointSummary();
        int beatIndex = plotArcState == null ? 0 : plotArcState.beatIndex;
        int arcIndex = plotArcState == null ? 1 : Math.max(1, plotArcState.arcIndex);
        summary.arcIndex = arcIndex;
        summary.beatStart = Math.max(1, ((arcIndex - 1) * 10) + 1);
        summary.beatEnd = Math.max(summary.beatStart, beatIndex);
        summary.title = "第" + arcIndex + "段阶段总结";
        summary.routeTheme = plotArcState == null || plotArcState.phase == null ? "相识" : plotArcState.phase;
        summary.relationshipSummary = relationshipState == null
                ? "关系还在慢慢铺开。"
                : "当前更接近“" + relationshipState.endingCandidate + "”，阶段停在“" + relationshipState.relationshipStage + "”。";
        summary.sceneSummary = sceneState == null || sceneState.sceneSummary == null || sceneState.sceneSummary.isBlank()
                ? "这一段主要还是围绕当下的聊天场景慢慢展开。"
                : sceneState.sceneSummary;
        summary.endingTendency = relationshipState == null ? "继续发展" : relationshipState.endingCandidate;
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
            next.heartbeatExplain = "当前正在等待你决定是否继续这段剧情。";
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
            case "深夜" -> "现在已经很晚了，很多真心话会在这时候更容易落下来。";
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
            // fall back to time-only context
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
