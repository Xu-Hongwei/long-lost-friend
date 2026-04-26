package com.campuspulse;

import java.io.IOException;
import java.io.OutputStream;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

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
    int plotPressure;
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
    final String transitionLine;
    final boolean shouldAdvance;
    final int confidence;
    final String riskIfAdvance;
    final String requiredUserSignal;

    PlotDirectorAgentDecision(String action, String reason, String sceneCue, boolean shouldAdvance) {
        this(action, reason, reason, sceneCue, "", shouldAdvance, defaultConfidence(action, shouldAdvance), "", "");
    }

    PlotDirectorAgentDecision(String action, String reason, String sceneCue, String transitionLine, boolean shouldAdvance) {
        this(action, reason, reason, sceneCue, transitionLine, shouldAdvance, defaultConfidence(action, shouldAdvance), "", "");
    }

    PlotDirectorAgentDecision(String action, String reason, String whyNow, String sceneCue, String transitionLine, boolean shouldAdvance) {
        this(action, reason, whyNow, sceneCue, transitionLine, shouldAdvance, defaultConfidence(action, shouldAdvance), "", "");
    }

    PlotDirectorAgentDecision(
            String action,
            String reason,
            String whyNow,
            String sceneCue,
            String transitionLine,
            boolean shouldAdvance,
            int confidence,
            String riskIfAdvance,
            String requiredUserSignal
    ) {
        this.action = action;
        this.reason = reason;
        this.whyNow = whyNow == null || whyNow.isBlank() ? reason : whyNow;
        this.sceneCue = sceneCue;
        this.transitionLine = transitionLine == null ? "" : transitionLine;
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

class SceneMoveIntent {
    final String type;
    final String moveType;
    final String targetLocation;
    final String interactionMode;
    final boolean shouldMove;
    final boolean shouldCreateSceneText;
    final int confidence;
    final String reason;

    SceneMoveIntent(
            String type,
            String targetLocation,
            String interactionMode,
            boolean shouldMove,
            boolean shouldCreateSceneText,
            int confidence,
            String reason
    ) {
        this(type, deriveMoveType(type, shouldMove, targetLocation), targetLocation, interactionMode, shouldMove, shouldCreateSceneText, confidence, reason);
    }

    SceneMoveIntent(
            String type,
            String moveType,
            String targetLocation,
            String interactionMode,
            boolean shouldMove,
            boolean shouldCreateSceneText,
            int confidence,
            String reason
    ) {
        this.type = type == null || type.isBlank() ? "no_scene_change" : type;
        this.moveType = moveType == null || moveType.isBlank() ? deriveMoveType(this.type, shouldMove, targetLocation) : moveType;
        this.targetLocation = targetLocation == null ? "" : targetLocation;
        this.interactionMode = interactionMode == null ? "" : interactionMode;
        this.shouldMove = shouldMove;
        this.shouldCreateSceneText = shouldCreateSceneText;
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.reason = reason == null ? "" : reason;
    }

    private static String deriveMoveType(String type, boolean shouldMove, String targetLocation) {
        String safeType = type == null ? "" : type;
        if ("scene_topic".equals(safeType) || "ambient_reference".equals(safeType)) {
            return "topic_only";
        }
        if (shouldMove && targetLocation != null && !targetLocation.isBlank()) {
            return "move_to";
        }
        return "no_change";
    }

    boolean isExplicitMove() {
        return "explicit_move".equals(type);
    }

    boolean isImplicitMove() {
        return "implicit_move".equals(type);
    }

    boolean isSceneTopic() {
        return "scene_topic".equals(type) || "ambient_reference".equals(type);
    }

    boolean blocksPlotAdvance() {
        return List.of("stay", "cancel_move", "arrived", "topic_only").contains(moveType);
    }

    static SceneMoveIntent none(String reason) {
        return new SceneMoveIntent("no_scene_change", "", "", false, false, 70, reason);
    }
}

class SceneMoveIntentService {
    SceneMoveIntent classify(String userMessage, DialogueContinuityState continuityState, SceneState sceneState) {
        String text = userMessage == null ? "" : userMessage.trim();
        String compact = text.replaceAll("\\s+", "");
        if (compact.isBlank()) {
            return SceneMoveIntent.none("empty");
        }
        if (isArrived(compact)) {
            return new SceneMoveIntent("scene_status", "arrived", currentLocation(sceneState), currentInteraction(sceneState), false, false, 88, "user_says_already_arrived");
        }
        if (isStayOrCancel(compact)) {
            return new SceneMoveIntent("scene_status", isCancelMove(compact) ? "cancel_move" : "stay", currentLocation(sceneState), currentInteraction(sceneState), false, false, 90, "user_blocks_or_cancels_move");
        }
        String returnTarget = returnTarget(compact, sceneState);
        if (!returnTarget.isBlank()) {
            return new SceneMoveIntent("explicit_move", "return_to", returnTarget, interactionFor(returnTarget, compact), true, true, 88, "return_move_request");
        }
        if (isSceneTopicQuestion(compact)) {
            return new SceneMoveIntent("scene_topic", "topic_only", "", "", false, false, 88, "scene_topic_question");
        }
        if (isAmbientReference(compact)) {
            return new SceneMoveIntent("ambient_reference", "topic_only", "", "", false, false, 78, "ambient_reference");
        }
        String explicitTarget = explicitTarget(compact);
        if (!explicitTarget.isBlank()) {
            return new SceneMoveIntent("explicit_move", "move_to", explicitTarget, interactionFor(explicitTarget, compact), true, true, 92, "explicit_move_request");
        }
        if (hasMoveVerb(compact)) {
            String target = targetFromText(compact, sceneState);
            return new SceneMoveIntent("explicit_move", "move_to", target, interactionFor(target, compact), true, true, 84, "move_verb_request");
        }
        if (isImplicitMove(compact) && hasAcceptedPlan(continuityState)) {
            String target = targetFromObjective(continuityState.acceptedPlan, sceneState);
            return new SceneMoveIntent("implicit_move", "move_to", target, interactionFor(target, compact), true, true, 80, "accepted_plan_continuation");
        }
        return SceneMoveIntent.none("no_move_intent");
    }

    private boolean isArrived(String compact) {
        return containsAny(compact, List.of(
                "\u5df2\u7ecf\u5230\u4e86", "\u6211\u4eec\u5230\u4e86", "\u90fd\u5230\u4e86", "\u5230\u4e86",
                "\u5df2\u7ecf\u5728"
        ));
    }

    private boolean isStayOrCancel(String compact) {
        return containsAny(compact, List.of(
                "\u4e0d\u53bb\u4e86", "\u5148\u4e0d\u53bb", "\u522b\u53bb\u4e86", "\u4e0d\u7528\u53bb", "\u522b\u8d70",
                "\u5148\u522b\u8d70", "\u4e0d\u6362\u5730\u65b9", "\u522b\u6362\u5730\u65b9", "\u7559\u5728\u8fd9",
                "\u5c31\u5728\u8fd9", "\u5148\u5750\u4f1a", "\u5750\u4e0b\u5427"
        ));
    }

    private boolean isCancelMove(String compact) {
        return containsAny(compact, List.of(
                "\u4e0d\u53bb\u4e86", "\u5148\u4e0d\u53bb", "\u522b\u53bb\u4e86", "\u4e0d\u7528\u53bb", "\u7b97\u4e86"
        ));
    }

    private String returnTarget(String compact, SceneState sceneState) {
        if (!containsAny(compact, List.of("\u56de\u53bb", "\u56de\u5230", "\u56de\u56fe\u4e66\u9986", "\u56de\u98df\u5802", "\u56de\u5bbf\u820d", "\u56de\u64cd\u573a"))) {
            return "";
        }
        String explicit = explicitTarget(compact);
        if (!explicit.isBlank()) {
            return explicit;
        }
        if (containsAny(compact, List.of("\u56de\u56fe\u4e66\u9986"))) return "\u56fe\u4e66\u9986";
        if (containsAny(compact, List.of("\u56de\u98df\u5802"))) return "\u98df\u5802";
        if (containsAny(compact, List.of("\u56de\u5bbf\u820d"))) return "\u5bbf\u820d";
        if (containsAny(compact, List.of("\u56de\u64cd\u573a"))) return "\u64cd\u573a";
        return sceneState == null || sceneState.location == null || sceneState.location.isBlank() ? "\u539f\u6765\u7684\u5730\u65b9" : sceneState.location;
    }

    private String currentLocation(SceneState sceneState) {
        return sceneState == null || sceneState.location == null ? "" : sceneState.location;
    }

    private String currentInteraction(SceneState sceneState) {
        return sceneState == null || sceneState.interactionMode == null ? "" : sceneState.interactionMode;
    }

    private boolean isSceneTopicQuestion(String compact) {
        if (!containsAny(compact, List.of("吗", "?", "？", "喜欢", "讨厌", "觉得", "怎么样", "适合", "会不会", "有没有", "为什么"))) {
            return false;
        }
        return containsAny(compact, List.of(
                "淋雨", "雨天", "下雨", "天气", "晴天", "刮风", "风", "冷", "热",
                "奶茶", "咖啡", "热饮", "图书馆", "操场", "食堂", "宿舍", "电影", "歌"
        ));
    }

    private boolean isAmbientReference(String compact) {
        return containsAny(compact, List.of("风好大", "雨声", "下雨了", "今天好冷", "今天好热", "天气真好"))
                && !hasMoveVerb(compact);
    }

    private String explicitTarget(String compact) {
        if (containsAny(compact, List.of("去操场", "到操场", "操场走走", "操场散步"))) return "操场";
        if (containsAny(compact, List.of("去食堂", "到食堂", "去吃饭", "去打饭"))) return "食堂";
        if (containsAny(compact, List.of("去图书馆", "到图书馆", "去自习", "去复习"))) return "图书馆";
        if (containsAny(compact, List.of("回宿舍", "去宿舍", "送你回宿舍", "送她回宿舍", "送他回宿舍", "宿舍楼下"))) return "宿舍";
        if (containsAny(compact, List.of("一起走", "边走边说", "路上说", "路上聊", "送你回", "送她回", "送他回"))) return "回去的路上";
        if (containsAny(compact, List.of("去外面", "出去走", "换个地方", "出去看看", "去看看小雨", "看看小雨"))) return "外面";
        if (containsAny(compact, List.of("去市区", "出校", "校外"))) return "市区";
        return "";
    }

    private boolean hasMoveVerb(String compact) {
        return containsAny(compact, List.of("我们去", "一起去", "去看看", "过去", "走吧", "出发", "换个地方", "边走边说", "一起走"));
    }

    private boolean isImplicitMove(String compact) {
        return containsAny(compact, List.of("好啊", "好呀", "那就走", "走吧", "我跟你一起", "一起吧", "过去吧", "听你的", "就这样"));
    }

    private boolean hasAcceptedPlan(DialogueContinuityState continuityState) {
        return continuityState != null && continuityState.acceptedPlan != null && !continuityState.acceptedPlan.isBlank();
    }

    private String targetFromObjective(String objective, SceneState sceneState) {
        String compact = objective == null ? "" : objective.replaceAll("\\s+", "");
        String target = explicitTarget(compact);
        if (!target.isBlank()) {
            return target;
        }
        if (containsAny(compact, List.of("热饮", "奶茶", "咖啡"))) return "热饮摊附近";
        return sceneState == null || sceneState.location == null || sceneState.location.isBlank() ? "聊天现场" : sceneState.location;
    }

    private String targetFromText(String compact, SceneState sceneState) {
        String target = explicitTarget(compact);
        if (!target.isBlank()) {
            return target;
        }
        return sceneState == null || sceneState.location == null || sceneState.location.isBlank() ? "外面" : sceneState.location;
    }

    private String interactionFor(String targetLocation, String compact) {
        if (containsAny(compact, List.of("发消息", "回消息", "聊天框", "手机", "屏幕那头"))) return "online_chat";
        if (containsAny(compact, List.of("打电话", "通话", "电话里"))) return "phone_call";
        if ("回去的路上".equals(targetLocation) || containsAny(compact, List.of("一起走", "边走边说", "路上", "送你回", "送她回", "送他回"))) return "mixed_transition";
        return "face_to_face";
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

class TurnUnderstandingService {
    private final SceneMoveIntentService sceneMoveIntentService = new SceneMoveIntentService();

    TurnUnderstandingState understand(
            String userMessage,
            List<ConversationSnippet> recentContext,
            IntentState intentState,
            DialogueContinuityState continuityState,
            SceneState sceneState,
            String nowIso
    ) {
        String text = userMessage == null ? "" : userMessage.trim();
        String compact = compact(text);
        SceneMoveIntent moveIntent = sceneMoveIntentService.classify(text, continuityState, sceneState);
        AssistantObligation assistantObligation = detectAssistantObligation(recentContext);
        String obligationType = assistantObligation == null ? "none" : blank(assistantObligation.type);
        List<UserReplyActCandidate> candidates = new ArrayList<>();

        addCandidate(candidates, "clarify", repairScore(compact, intentState), List.of("user_correction_or_meta_repair"));
        addCandidate(candidates, "answer_question", answerQuestionScore(compact, obligationType), List.of("last_assistant_question"));
        addCandidate(candidates, "accept_plan", acceptPlanScore(compact, obligationType, continuityState), List.of("soft_acceptance_or_active_plan"));
        addCandidate(candidates, "reject", rejectScore(compact), List.of("reject_or_cancel_signal"));
        addCandidate(candidates, "defer", deferScore(compact), List.of("defer_or_later_signal"));
        addCandidate(candidates, "counter_offer", counterOfferScore(compact), List.of("alternative_offer_signal"));
        addCandidate(candidates, "scene_move", moveIntent.shouldMove ? 3 + moveIntent.confidence / 18 : 0, List.of("scene_move:" + moveIntent.reason));
        addCandidate(candidates, "scene_stay", stayScore(compact), List.of("stay_or_no_transition_signal"));
        addCandidate(candidates, "topic_only", moveIntent.isSceneTopic() ? 8 : 0, List.of("scene_topic_not_movement"));
        addCandidate(candidates, "emotion_share", "emotion_share".equals(intentState == null ? "" : blank(intentState.primaryIntent)) ? 7 : 0, List.of("local_intent_emotion_share"));
        addCandidate(candidates, "romantic_probe", "romantic_probe".equals(intentState == null ? "" : blank(intentState.primaryIntent)) ? 7 : 0, List.of("local_intent_romantic_probe"));
        addCandidate(candidates, "small_talk", ("small_talk".equals(intentState == null ? "" : blank(intentState.primaryIntent))
                || "light_chat".equals(intentState == null ? "" : blank(intentState.primaryIntent))) ? 4 : 0, List.of("local_intent_light_chat"));

        if (candidates.isEmpty()) {
            addCandidate(candidates, "small_talk", 3, List.of("fallback_no_strong_signal"));
        }
        candidates.sort((left, right) -> Integer.compare(right.score, left.score));

        UserReplyActCandidate top = candidates.get(0);
        int second = candidates.size() > 1 ? candidates.get(1).score : 0;
        List<LocalConflict> localConflicts = detectLocalConflicts(compact, obligationType, continuityState, sceneState, moveIntent, top, second);
        int confidence = confidence(top.score, second, localConflicts);
        String tier = recommendedTier(top.act, confidence, localConflicts, compact);

        TurnUnderstandingState state = new TurnUnderstandingState();
        state.primaryAct = top.act;
        state.confidence = confidence;
        state.candidates = candidates.size() > 4 ? new ArrayList<>(candidates.subList(0, 4)) : candidates;
        state.localConflicts = localConflicts;
        state.assistantObligation = assistantObligation;
        state.recommendedQuickJudgeTier = tier;
        state.shouldAskQuickJudge = !"skip".equals(tier);
        state.sceneMoveKind = moveIntent.moveType;
        state.sceneMoveTarget = moveIntent.targetLocation;
        state.sceneMoveReason = moveIntent.reason;
        state.sceneMoveConfidence = moveIntent.confidence;
        state.updatedAt = nowIso;
        return state;
    }

    private void addCandidate(List<UserReplyActCandidate> candidates, String act, int score, List<String> evidence) {
        if (score <= 0) {
            return;
        }
        UserReplyActCandidate candidate = new UserReplyActCandidate();
        candidate.act = act;
        candidate.score = score;
        candidate.confidence = Math.max(35, Math.min(96, 40 + score * 7));
        candidate.evidence = evidence == null ? new ArrayList<>() : new ArrayList<>(evidence);
        candidates.add(candidate);
    }

    private int repairScore(String compact, IntentState intentState) {
        int score = "meta_repair".equals(intentState == null ? "" : blank(intentState.primaryIntent)) ? 8 : 0;
        if (containsAny(compact, List.of(
                "\u4f60\u6ca1\u56de\u7b54\u6211", "\u7b54\u975e\u6240\u95ee", "\u6211\u95ee\u7684\u662f", "\u522b\u8f6c\u79fb\u8bdd\u9898",
                "\u4f60\u5728\u8bf4\u4ec0\u4e48", "\u4f60\u53c8\u91cd\u590d", "\u4e0d\u662f\u8fd9\u4e2a\u95ee\u9898", "\u4f60\u662f\u4e0d\u662f\u5fd8\u4e86",
                "\u4e0d\u662f\u8fd9\u4e2a\u610f\u601d", "\u4f60\u7406\u89e3\u9519", "\u4e0d\u5bf9", "\u6211\u4eec\u4e0d\u662f", "\u5df2\u7ecf\u5728"
        ))) {
            score += 8;
        }
        return score;
    }

    private int answerQuestionScore(String compact, String obligation) {
        if (!"answer_question".equals(obligation)) {
            return 0;
        }
        int score = compact.length() <= 16 ? 6 : 3;
        if (containsAny(compact, List.of("\u559c\u6b22", "\u4e0d\u559c\u6b22", "\u53ef\u4ee5", "\u4e0d\u884c", "\u6709", "\u6ca1\u6709", "\u662f", "\u4e0d\u662f", "\u597d", "\u4e0d\u597d"))) {
            score += 3;
        }
        return score;
    }

    private int acceptPlanScore(String compact, String obligation, DialogueContinuityState continuityState) {
        boolean activePlan = continuityState != null && (!blank(continuityState.acceptedPlan).isBlank()
                || !blank(continuityState.currentObjective).isBlank()
                || continuityState.sceneTransitionNeeded);
        if (!activePlan && !"accept_plan".equals(obligation)) {
            return 0;
        }
        if (containsAny(compact, List.of("\u53ef\u4ee5", "\u597d\u554a", "\u597d\u5440", "\u884c", "\u55ef", "\u542c\u4f60\u7684", "\u90a3\u5c31", "\u8d70\u5427"))) {
            return "accept_plan".equals(obligation) ? 8 : 5;
        }
        return 0;
    }

    private int rejectScore(String compact) {
        return containsAny(compact, List.of(
                "\u4e0d\u7528", "\u4e0d\u8981", "\u4e0d\u60f3", "\u7b97\u4e86", "\u522b\u8fd9\u6837", "\u522b\u53bb", "\u4e0d\u53bb\u4e86", "\u4e0d\u7528\u4e86"
        )) ? 8 : 0;
    }

    private int deferScore(String compact) {
        return containsAny(compact, List.of("\u7b49\u4e0b", "\u7b49\u4e00\u4e0b", "\u665a\u70b9", "\u5148\u4e0d", "\u8fc7\u4f1a\u513f", "\u4e0b\u6b21\u5427")) ? 7 : 0;
    }

    private int counterOfferScore(String compact) {
        return containsAny(compact, List.of("\u8981\u4e0d", "\u4e0d\u5982", "\u8fd8\u662f", "\u6362\u4e2a", "\u6211\u4eec\u53bb", "\u53bb\u522b\u7684")) ? 7 : 0;
    }

    private int stayScore(String compact) {
        return containsAny(compact, List.of("\u5c31\u5728\u8fd9", "\u522b\u8d70", "\u5148\u522b\u8d70", "\u4e0d\u7528\u6362\u5730\u65b9", "\u522b\u6362\u5730\u65b9", "\u7559\u5728\u8fd9", "\u5148\u5750\u4f1a", "\u5750\u4e0b\u5427")) ? 8 : 0;
    }

    private List<LocalConflict> detectLocalConflicts(
            String compact,
            String obligation,
            DialogueContinuityState continuityState,
            SceneState sceneState,
            SceneMoveIntent moveIntent,
            UserReplyActCandidate top,
            int secondScore
    ) {
        List<LocalConflict> conflicts = new ArrayList<>();
        boolean activePlan = continuityState != null && (!blank(continuityState.acceptedPlan).isBlank()
                || !blank(continuityState.currentObjective).isBlank()
                || continuityState.sceneTransitionNeeded);
        if (compact.length() <= 12 && top.score - secondScore <= 2 && secondScore > 0) {
            conflicts.add(localConflict(
                    "ambiguous_short_reply",
                    "medium",
                    "user_reply",
                    "candidate_margin",
                    "ask_quick_judge_or_use_last_obligation"
            ));
        }
        if ("answer_question".equals(obligation) && activePlan && (top.score - secondScore <= 3)) {
            conflicts.add(localConflict(
                    "question_vs_plan_ambiguous",
                    "high",
                    "assistant_obligation",
                    "active_plan",
                    "prefer_answer_question_before_scene"
            ));
        }
        if (("reject".equals(top.act) || "defer".equals(top.act) || "scene_stay".equals(top.act)) && activePlan) {
            conflicts.add(localConflict(
                    "user_cancels_active_objective",
                    "high",
                    "user_reply_act",
                    "dialogue_continuity",
                    "clear_or_hold_movement_goal"
            ));
        }
        if (moveIntent != null && moveIntent.shouldMove && sceneAlreadyAt(sceneState, moveIntent.targetLocation)) {
            conflicts.add(localConflict(
                    "scene_target_already_current",
                    "high",
                    "scene_move_intent",
                    "scene_state",
                    "clear_duplicate_scene_move"
            ));
        }
        if ("clarify".equals(top.act)) {
            conflicts.add(localConflict(
                    "user_self_rescue",
                    "urgent",
                    "user_reply_act",
                    "assistant_reply",
                    "repair_then_answer"
            ));
        }
        return conflicts;
    }

    private LocalConflict localConflict(String type, String severity, String sourceA, String sourceB, String recommendedAction) {
        LocalConflict conflict = new LocalConflict();
        conflict.type = type == null ? "" : type;
        conflict.severity = severity == null ? "medium" : severity;
        conflict.sourceA = sourceA == null ? "" : sourceA;
        conflict.sourceB = sourceB == null ? "" : sourceB;
        conflict.recommendedAction = recommendedAction == null ? "" : recommendedAction;
        return conflict;
    }

    private int confidence(int topScore, int secondScore, List<LocalConflict> conflicts) {
        int margin = Math.max(0, topScore - secondScore);
        int value = Math.min(96, 45 + topScore * 5 + margin * 5);
        if (conflicts != null && !conflicts.isEmpty()) {
            value -= Math.min(24, conflicts.size() * 8);
        }
        return Math.max(35, Math.min(96, value));
    }

    private String recommendedTier(String primaryAct, int confidence, List<LocalConflict> conflicts, String compact) {
        if ("clarify".equals(primaryAct)
                || hasConflict(conflicts, "user_self_rescue")
                || hasConflict(conflicts, "user_cancels_active_objective")
                || hasConflict(conflicts, "scene_target_already_current")) {
            return "urgent";
        }
        if (conflicts != null && !conflicts.isEmpty()) {
            return "opportunistic";
        }
        if (confidence < 68) {
            return compact.length() <= 120 ? "background" : "skip";
        }
        return "skip";
    }

    private boolean hasConflict(List<LocalConflict> conflicts, String type) {
        if (conflicts == null || type == null) {
            return false;
        }
        for (LocalConflict conflict : conflicts) {
            if (conflict != null && type.equals(conflict.type)) {
                return true;
            }
        }
        return false;
    }

    private AssistantObligation detectAssistantObligation(List<ConversationSnippet> recentContext) {
        String last = lastAssistantText(recentContext);
        String compact = compact(last);
        if (compact.isBlank()) {
            return assistantObligation("none", "", 0, List.of(), "no_recent_assistant");
        }
        if (containsAny(compact, List.of("?", "\uff1f", "\u5417", "\u5462", "\u8981\u4e0d\u8981", "\u6709\u6ca1\u6709", "\u559c\u6b22", "\u89c9\u5f97"))) {
            return assistantObligation(
                    "answer_question",
                    last,
                    80,
                    List.of("answer_question", "clarify"),
                    "last_assistant_question"
            );
        }
        if (containsAny(compact, List.of("\u6211\u4eec\u53bb", "\u4e00\u8d77\u53bb", "\u8981\u4e0d\u53bb", "\u53bb\u98df\u5802", "\u53bb\u64cd\u573a", "\u53bb\u56fe\u4e66\u9986", "\u9001\u4f60\u56de"))) {
            return assistantObligation(
                    "accept_plan",
                    last,
                    72,
                    List.of("accept_plan", "reject", "defer", "counter_offer"),
                    "last_assistant_plan_offer"
            );
        }
        if (containsAny(compact, List.of("\u5148", "\u63a8\u8350", "\u9009\u4e00\u4e2a", "\u8bd5\u8bd5"))) {
            return assistantObligation(
                    "follow_instruction",
                    last,
                    58,
                    List.of("answer_question", "clarify"),
                    "last_assistant_instruction"
            );
        }
        return assistantObligation("none", last, 0, List.of(), "no_explicit_obligation");
    }

    private AssistantObligation assistantObligation(
            String type,
            String source,
            int priority,
            List<String> expectedUserActs,
            String reason
    ) {
        AssistantObligation obligation = new AssistantObligation();
        obligation.type = type == null || type.isBlank() ? "none" : type;
        obligation.source = truncate(source, 120);
        obligation.priority = Math.max(0, Math.min(100, priority));
        obligation.expectedUserActs = expectedUserActs == null ? new ArrayList<>() : new ArrayList<>(expectedUserActs);
        obligation.reason = reason == null ? "" : reason;
        return obligation;
    }

    private String lastAssistantText(List<ConversationSnippet> recentContext) {
        if (recentContext == null) {
            return "";
        }
        for (int index = recentContext.size() - 1; index >= 0; index--) {
            ConversationSnippet snippet = recentContext.get(index);
            if (snippet != null && "assistant".equals(snippet.role)) {
                return blank(snippet.text);
            }
        }
        return "";
    }

    private boolean sceneAlreadyAt(SceneState sceneState, String target) {
        String anchor = compact((sceneState == null ? "" : blank(sceneState.location)) + (sceneState == null ? "" : blank(sceneState.subLocation)));
        String compactTarget = compact(target);
        return !anchor.isBlank() && !compactTarget.isBlank() && (anchor.contains(compactTarget) || compactTarget.contains(anchor));
    }

    private boolean containsAny(String text, List<String> keywords) {
        String safe = blank(text);
        for (String keyword : keywords) {
            if (safe.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String compact(String value) {
        return blank(value).replaceAll("\\s+", "");
    }

    private String truncate(String value, int maxLength) {
        String safe = blank(value);
        if (safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maxLength));
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }
}

class QuickJudgeDecision implements Serializable {
    final boolean used;
    final String primaryIntent;
    final String secondaryIntent;
    final String emotion;
    final String sharedObjective;
    final boolean sceneTransitionNeeded;
    final String nextBestMove;
    final String replyPriority;
    final int confidence;
    final String reason;

    QuickJudgeDecision(
            boolean used,
            String primaryIntent,
            String secondaryIntent,
            String emotion,
            String sharedObjective,
            boolean sceneTransitionNeeded,
            String nextBestMove,
            String replyPriority,
            int confidence,
            String reason
    ) {
        this.used = used;
        this.primaryIntent = primaryIntent == null ? "" : primaryIntent;
        this.secondaryIntent = secondaryIntent == null ? "" : secondaryIntent;
        this.emotion = emotion == null ? "" : emotion;
        this.sharedObjective = sharedObjective == null ? "" : sharedObjective;
        this.sceneTransitionNeeded = sceneTransitionNeeded;
        this.nextBestMove = nextBestMove == null ? "" : nextBestMove;
        this.replyPriority = replyPriority == null ? "" : replyPriority;
        this.confidence = confidence;
        this.reason = reason == null ? "" : reason;
    }

    static QuickJudgeDecision none(String reason) {
        return new QuickJudgeDecision(false, "", "", "", "", false, "", "", 0, reason);
    }

    boolean shouldApply() {
        return used
                && confidence >= 65
                && (hasMeaningfulValue(primaryIntent)
                || hasMeaningfulValue(secondaryIntent)
                || hasMeaningfulEmotion(emotion)
                || hasMeaningfulValue(sharedObjective)
                || sceneTransitionNeeded
                || hasMeaningfulValue(nextBestMove)
                || hasMeaningfulValue(replyPriority));
    }

    static boolean hasMeaningfulValue(String value) {
        String safe = value == null ? "" : value.trim();
        return !safe.isBlank() && !"none".equals(safe);
    }

    static boolean hasMeaningfulEmotion(String value) {
        String safe = value == null ? "" : value.trim();
        return hasMeaningfulValue(safe) && !"neutral".equals(safe);
    }
}

class QuickJudgeTask {
    final CompletableFuture<QuickJudgeDecision> future;
    final long startedAtNanos;
    final String triggerTier;
    final String triggerReason;
    final int triggerScore;
    final List<String> triggerReasons;
    final List<String> suppressedReasons;
    final AtomicLong completedAtNanos = new AtomicLong(0L);

    QuickJudgeTask(CompletableFuture<QuickJudgeDecision> future, long startedAtNanos) {
        this(future, startedAtNanos, "opportunistic", "", 0, List.of(), List.of());
    }

    QuickJudgeTask(CompletableFuture<QuickJudgeDecision> future, long startedAtNanos, String triggerTier, String triggerReason) {
        this(future, startedAtNanos, triggerTier, triggerReason, 0, List.of(), List.of());
    }

    QuickJudgeTask(
            CompletableFuture<QuickJudgeDecision> future,
            long startedAtNanos,
            String triggerTier,
            String triggerReason,
            int triggerScore,
            List<String> triggerReasons,
            List<String> suppressedReasons
    ) {
        this.future = future;
        this.startedAtNanos = startedAtNanos;
        this.triggerTier = triggerTier == null ? "" : triggerTier;
        this.triggerReason = triggerReason == null ? "" : triggerReason;
        this.triggerScore = triggerScore;
        this.triggerReasons = triggerReasons == null ? List.of() : List.copyOf(triggerReasons);
        this.suppressedReasons = suppressedReasons == null ? List.of() : List.copyOf(suppressedReasons);
    }
}

class QuickJudgeLocalCorrectionResult {
    final IntentState intentState;
    final DialogueContinuityState dialogueContinuity;
    final List<String> notes;

    QuickJudgeLocalCorrectionResult(IntentState intentState, DialogueContinuityState dialogueContinuity, List<String> notes) {
        this.intentState = intentState;
        this.dialogueContinuity = dialogueContinuity;
        this.notes = notes == null ? List.of() : notes;
    }
}

class QuickJudgeLocalCorrectionService {
    private final DialogueContinuityService dialogueContinuityService = new DialogueContinuityService();

    QuickJudgeLocalCorrectionResult correct(
            IntentState intentState,
            DialogueContinuityState continuity,
            SceneState sceneState,
            List<ConversationSnippet> recentContext,
            String userMessage,
            String nowIso
    ) {
        List<String> notes = new ArrayList<>();
        DialogueContinuityState nextContinuity = dialogueContinuityService.normalize(continuity, nowIso);
        nextContinuity = dialogueContinuityService.settleSceneTransitionIfArrived(nextContinuity, sceneState, nowIso);
        nextContinuity = clearArrivedMovementGoal(nextContinuity, sceneState, notes, nowIso);
        nextContinuity = clearUserReportedArrivedGoal(nextContinuity, sceneState, userMessage, notes, nowIso);
        nextContinuity = preventQuestionAcceptanceFromRevivingMovement(nextContinuity, sceneState, recentContext, userMessage, notes, nowIso);
        return new QuickJudgeLocalCorrectionResult(intentState, nextContinuity, notes);
    }

    private DialogueContinuityState clearArrivedMovementGoal(
            DialogueContinuityState continuity,
            SceneState sceneState,
            List<String> notes,
            String nowIso
    ) {
        if (continuity == null || sceneState == null) {
            return continuity;
        }
        String objective = firstNonBlank(continuity.currentObjective, continuity.acceptedPlan);
        String target = objectiveTargetLocation(objective);
        if (target.isBlank() || !sceneAlreadyAt(sceneState, target)) {
            return continuity;
        }
        DialogueContinuityState next = copyContinuity(continuity);
        next.currentObjective = "";
        next.acceptedPlan = "";
        next.pendingUserOffer = "";
        next.sceneTransitionNeeded = false;
        next.nextBestMove = "\u5df2\u7ecf\u5728" + target + "\uff0c\u4e0d\u8981\u518d\u628a\u5f53\u524d\u8f6e\u5199\u6210\u6b63\u5728\u53bb" + target + "\u3002";
        next.mustNotContradict = next.mustNotContradict == null ? new ArrayList<>() : new ArrayList<>(next.mustNotContradict);
        addUnique(next.mustNotContradict, "\u5f53\u524d\u5df2\u7ecf\u5728" + target + "\uff0c\u4e0d\u8981\u91cd\u590d\u8f6c\u573a\u5230" + target + "\u3002");
        next.confidence = Math.max(70, next.confidence);
        next.updatedAt = nowIso;
        notes.add("arrived_movement_completed:" + target);
        return next;
    }

    private DialogueContinuityState clearUserReportedArrivedGoal(
            DialogueContinuityState continuity,
            SceneState sceneState,
            String userMessage,
            List<String> notes,
            String nowIso
    ) {
        if (continuity == null) {
            return continuity;
        }
        String compact = compact(userMessage);
        if (compact.isBlank() || !looksLikeUserLocationCorrection(compact)) {
            return continuity;
        }
        String target = firstNonBlank(
                locationMentionedIn(compact),
                objectiveTargetLocation(firstNonBlank(continuity.currentObjective, continuity.acceptedPlan))
        );
        if (target.isBlank()) {
            return continuity;
        }
        DialogueContinuityState next = copyContinuity(continuity);
        next.currentObjective = "";
        next.acceptedPlan = "";
        next.pendingUserOffer = "";
        next.sceneTransitionNeeded = false;
        next.nextBestMove = "\u7528\u6237\u5df2\u7ecf\u7ea0\u6b63\u4e86\u4f4d\u7f6e\uff1a\u5f53\u524d\u5df2\u5728" + target + "\uff0c\u4e0d\u8981\u518d\u91cd\u590d\u5f80" + target + "\u8d70\u3002";
        next.mustNotContradict = next.mustNotContradict == null ? new ArrayList<>() : new ArrayList<>(next.mustNotContradict);
        addUnique(next.mustNotContradict, "\u7528\u6237\u7ea0\u6b63\uff1a\u5f53\u524d\u5df2\u5728" + target + "\uff0c\u4e0d\u8981\u518d\u5199\u6b63\u5728\u53bb" + target + "\u3002");
        if (sceneState != null) {
            addUnique(next.mustNotContradict, "\u5f53\u524d\u573a\u666f\uff1a" + firstNonBlank(sceneState.location, target) + "\u3002");
        }
        next.confidence = Math.max(82, next.confidence);
        next.updatedAt = nowIso;
        notes.add("user_reported_arrived:" + target);
        return next;
    }

    private boolean looksLikeUserLocationCorrection(String compact) {
        return containsAny(compact, List.of(
                "\u5df2\u7ecf\u5728", "\u4e0d\u662f\u5728", "\u4e0d\u662f\u5df2\u7ecf", "\u6211\u4eec\u4e0d\u662f\u5728", "\u6211\u4eec\u5df2\u7ecf\u5728", "\u90fd\u5230\u4e86"
        ));
    }

    private String locationMentionedIn(String compact) {
        if (compact.contains("\u98df\u5802")) return "\u98df\u5802";
        if (compact.contains("\u64cd\u573a")) return "\u64cd\u573a";
        if (compact.contains("\u56fe\u4e66\u9986")) return "\u56fe\u4e66\u9986";
        if (compact.contains("\u5bbf\u820d")) return "\u5bbf\u820d";
        if (compact.contains("\u5e02\u533a") || compact.contains("\u6821\u5916")) return "\u5e02\u533a";
        if (compact.contains("\u70ed\u996e") || compact.contains("\u5976\u8336") || compact.contains("\u5496\u5561")) return "\u70ed\u996e";
        return "";
    }

    private DialogueContinuityState preventQuestionAcceptanceFromRevivingMovement(
            DialogueContinuityState continuity,
            SceneState sceneState,
            List<ConversationSnippet> recentContext,
            String userMessage,
            List<String> notes,
            String nowIso
    ) {
        if (continuity == null || sceneState == null || recentContext == null || recentContext.isEmpty()) {
            return continuity;
        }
        String compact = compact(userMessage);
        if (!isSoftAcceptance(compact)) {
            return continuity;
        }
        String lastAssistant = lastText(recentContext, "assistant");
        if (!containsQuestion(lastAssistant)) {
            return continuity;
        }
        String target = objectiveTargetLocation(firstNonBlank(continuity.currentObjective, continuity.acceptedPlan));
        if (target.isBlank() || !sceneAlreadyAt(sceneState, target)) {
            return continuity;
        }
        DialogueContinuityState next = copyContinuity(continuity);
        next.currentObjective = "";
        next.acceptedPlan = "";
        next.sceneTransitionNeeded = false;
        next.nextBestMove = "\u7528\u6237\u662f\u5728\u63a5\u53d7\u4e0a\u4e00\u8f6e\u7684\u95ee\u9898\u6216\u8bdd\u9898\uff0c\u4e0d\u8981\u91cd\u542f\u5df2\u5b8c\u6210\u7684\u79fb\u52a8\u76ee\u6807\u3002";
        next.updatedAt = nowIso;
        notes.add("question_acceptance_not_movement:" + target);
        return next;
    }

    private DialogueContinuityState copyContinuity(DialogueContinuityState source) {
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

    private boolean sceneAlreadyAt(SceneState sceneState, String target) {
        String anchor = compact(blank(sceneState.location) + blank(sceneState.subLocation) + blank(sceneState.sceneSummary));
        String compactTarget = compact(target);
        return !anchor.isBlank() && !compactTarget.isBlank() && (anchor.contains(compactTarget) || compactTarget.contains(compact(blank(sceneState.location))));
    }

    private String objectiveTargetLocation(String objective) {
        String compact = compact(objective);
        if (compact.contains("\u98df\u5802")) return "\u98df\u5802";
        if (compact.contains("\u64cd\u573a")) return "\u64cd\u573a";
        if (compact.contains("\u56fe\u4e66\u9986")) return "\u56fe\u4e66\u9986";
        if (compact.contains("\u5bbf\u820d")) return "\u5bbf\u820d";
        if (compact.contains("\u5e02\u533a") || compact.contains("\u6821\u5916")) return "\u5e02\u533a";
        if (compact.contains("\u70ed\u996e") || compact.contains("\u5976\u8336") || compact.contains("\u5496\u5561")) return "\u70ed\u996e";
        return "";
    }

    private boolean isSoftAcceptance(String compact) {
        if (compact == null || compact.isBlank()) {
            return false;
        }
        return compact.length() <= 12 && containsAny(compact, List.of(
                "\u53ef\u4ee5", "\u597d\u554a", "\u597d\u5440", "\u884c", "\u55ef", "\u90a3\u4f60\u8bb2", "\u4f60\u8bb2", "\u542c\u4f60\u7684", "\u90a3\u5c31"
        ));
    }

    private String lastText(List<ConversationSnippet> context, String role) {
        if (context == null) {
            return "";
        }
        for (int index = context.size() - 1; index >= 0; index--) {
            ConversationSnippet snippet = context.get(index);
            if (snippet != null && role.equals(snippet.role)) {
                return blank(snippet.text);
            }
        }
        return "";
    }

    private boolean containsQuestion(String text) {
        return text != null && (text.contains("?") || text.contains("\uff1f") || text.contains("\u5417") || text.contains("\u5462"));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void addUnique(List<String> values, String value) {
        if (values != null && value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private boolean containsAny(String text, List<String> keywords) {
        String safe = blank(text);
        for (String keyword : keywords) {
            if (safe.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String compact(String value) {
        return blank(value).replaceAll("\\s+", "");
    }

    private String blank(String value) {
        return value == null ? "" : value;
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
    private static final List<String> META_REPAIR_KEYWORDS = List.of("不是", "怎么变成", "我说的是", "明明", "你刚刚", "我们不是", "别误会", "你理解错", "你是不是搞错");
    private static final List<String> ROMANTIC_PROBE_KEYWORDS = List.of("喜欢你", "喜欢我", "对我有好感", "是不是喜欢我", "有点吸引", "心动", "在意我", "会不会也喜欢", "对我也");
    private static final List<String> SCENE_PUSH_KEYWORDS = List.of("送你回宿舍", "回宿舍", "去食堂", "去操场", "一起走", "路上", "去图书馆", "夜跑", "散步", "去外面", "换个地方");
    private static final List<String> SCENE_LOCATION_KEYWORDS = List.of("操场", "食堂", "图书馆", "宿舍", "市区", "路上", "天台");
    private static final List<String> ADVICE_KEYWORDS = List.of("怎么办", "建议", "怎么选", "该不该", "要不要", "有没有办法", "怎么办才好");
    private static final List<String> EMOTION_SHARE_KEYWORDS = List.of("难受", "压力", "委屈", "低落", "有点累", "崩溃", "烦", "emo", "心里堵", "撑不住");
    private static final List<String> MEMORY_CALLBACK_KEYWORDS = List.of("上次", "之前", "说过", "还记得", "答应过", "后来");
    private static final List<String> ANGRY_KEYWORDS = List.of("烦", "滚", "讨厌", "闭嘴", "离谱", "别烦", "有病", "恶心");
    private static final List<String> SAD_KEYWORDS = List.of("难受", "委屈", "低落", "哭", "压力", "累", "崩溃", "失眠", "好难");
    private static final List<String> WARM_KEYWORDS = List.of("喜欢", "开心", "想你", "高兴", "安心", "好感", "在意", "期待");
    private static final List<String> BOUNDARY_KEYWORDS = List.of("别碰", "别问", "不要逼我", "烦", "滚", "讨厌", "离我远点");

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
        String compact = compact(text);
        IntentState state = new IntentState();
        state.primaryIntent = detectPrimary(text, compact, recentContext, relationshipState, sceneState);
        state.secondaryIntent = detectSecondary(text, compact, relationshipState, sceneState, state.primaryIntent);
        state.emotion = detectEmotion(text, tensionState);
        state.clarity = detectClarity(text, compact);
        state.needsEmpathy = "emotion_share".equals(state.primaryIntent)
                || "romantic_probe".equals(state.primaryIntent)
                || "sad".equals(state.emotion)
                || "angry".equals(state.emotion);
        state.needsStructure = "advice_seek".equals(state.primaryIntent);
        state.needsFollowup = "low".equals(state.clarity)
                || "scene_push".equals(state.primaryIntent)
                || "romantic_probe".equals(state.primaryIntent);
        state.isBoundarySensitive = containsAny(text, BOUNDARY_KEYWORDS)
                || tensionState != null && tensionState.guarded;
        state.rationale = buildRationale(text, recentContext, sceneState, memorySummary, state);
        state.updatedAt = nowIso;
        return state;
    }

    private String detectPrimary(
            String text,
            String compact,
            List<ConversationSnippet> recentContext,
            RelationshipState relationshipState,
            SceneState sceneState
    ) {
        if (compact.isBlank()) return "presence_followup";

        int metaRepairScore = keywordScore(text, META_REPAIR_KEYWORDS)
                + (startsWithAny(compact, List.of("不是", "我说的是", "别误会")) ? 2 : 0);
        int romanticProbeScore = keywordScore(text, ROMANTIC_PROBE_KEYWORDS)
                + (containsAny(text, List.of("喜欢", "心动", "有好感")) && containsQuestion(text) ? 1 : 0);
        int scenePushScore = keywordScore(text, SCENE_PUSH_KEYWORDS)
                + countMatches(text, SCENE_LOCATION_KEYWORDS);
        if (countMatches(text, SCENE_LOCATION_KEYWORDS) > 0 && containsAny(text, List.of("去", "走", "一起", "送", "陪"))) {
            scenePushScore += 2;
        }
        if (sceneState != null && sceneState.transitionPending && containsAny(text, List.of("好啊", "走吧", "可以", "行", "一起"))) {
            scenePushScore += 1;
        }
        int adviceSeekScore = keywordScore(text, ADVICE_KEYWORDS);
        int emotionShareScore = keywordScore(text, EMOTION_SHARE_KEYWORDS)
                + (countMatches(text, SAD_KEYWORDS) > 0 ? 1 : 0);
        int questionCheckScore = containsQuestion(text) ? 2 : 0;
        if (containsAny(text, List.of("吗", "呢", "是不是", "为什么", "怎么", "要不要", "会不会", "有没有"))) {
            questionCheckScore += 1;
        }
        if (recentContext != null && !recentContext.isEmpty() && containsAny(text, MEMORY_CALLBACK_KEYWORDS)) {
            questionCheckScore += 1;
        }

        if (metaRepairScore >= 2) {
            return "meta_repair";
        }
        if (romanticProbeScore >= 2 && romanticProbeScore >= questionCheckScore) {
            return "romantic_probe";
        }
        if (scenePushScore >= 3 && scenePushScore >= questionCheckScore) {
            return "scene_push";
        }
        if (adviceSeekScore >= 2 && adviceSeekScore >= questionCheckScore) {
            return "advice_seek";
        }
        if (emotionShareScore >= 2 && emotionShareScore + 1 >= questionCheckScore) {
            return "emotion_share";
        }
        if (questionCheckScore >= 2) {
            return "question_check";
        }
        if (compact.length() <= 4) {
            return "small_talk";
        }
        if (relationshipState != null && relationshipState.affectionScore < 18 && containsAny(text, List.of("在吗", "忙吗", "嗯嗯", "哈哈"))) {
            return "small_talk";
        }
        return "light_chat";
    }

    private String detectSecondary(String text, String compact, RelationshipState relationshipState, SceneState sceneState, String primaryIntent) {
        if (!"meta_repair".equals(primaryIntent) && containsAny(text, MEMORY_CALLBACK_KEYWORDS)) {
            return "memory_callback";
        }
        if (sceneState != null && sceneState.transitionPending && containsAny(text, List.of("好啊", "可以", "走吧", "那就", "一起"))) {
            return "transition_hold";
        }
        if (relationshipState != null && relationshipState.affectionScore < 25
                && ("romantic_probe".equals(primaryIntent) || containsAny(text, List.of("喜欢", "确认关系", "在一起", "亲一下")))) {
            return "pace_guard";
        }
        if (containsAny(compact, List.of("下次", "以后", "改天", "约好", "一起")) && countMatches(text, SCENE_LOCATION_KEYWORDS) > 0) {
            return "plan_anchor";
        }
        return "none";
    }

    private String detectEmotion(String text, RelationalTensionState tensionState) {
        int angryScore = keywordScore(text, ANGRY_KEYWORDS);
        int sadScore = keywordScore(text, SAD_KEYWORDS);
        int warmScore = keywordScore(text, WARM_KEYWORDS);
        if (text.contains("!") || text.contains("！")) {
            angryScore += 1;
        }
        if (angryScore > 0 && angryScore >= sadScore && angryScore >= warmScore) {
            return "angry";
        }
        if (sadScore > 0 && sadScore >= warmScore) {
            return "sad";
        }
        if (warmScore > 0) {
            return "warm";
        }
        if (tensionState != null && tensionState.guarded) {
            return "fragile";
        }
        return "neutral";
    }

    private String detectClarity(String text, String compact) {
        if (compact.isBlank()) return "low";
        if (compact.length() <= 4) return "low";
        if (compact.length() <= 8 && containsAny(text, List.of("这个", "那个", "算了", "随便", "就这样"))) return "low";
        if (containsQuestion(text) || countMatches(text, EMOTION_SHARE_KEYWORDS) > 0 || countMatches(text, SCENE_LOCATION_KEYWORDS) > 0) {
            return compact.length() >= 6 ? "high" : "medium";
        }
        if (compact.length() <= 10) return "medium";
        return "high";
    }

    private String buildRationale(
            String text,
            List<ConversationSnippet> recentContext,
            SceneState sceneState,
            MemorySummary memorySummary,
            IntentState state
    ) {
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
        if (state != null) {
            builder.append("; primary=").append(blank(state.primaryIntent));
            builder.append("; emotion=").append(blank(state.emotion));
            builder.append("; clarity=").append(blank(state.clarity));
        }
        return builder.toString();
    }

    private int keywordScore(String text, List<String> keywords) {
        return countMatches(text, keywords);
    }

    private int countMatches(String text, List<String> keywords) {
        int score = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    private boolean containsQuestion(String text) {
        return text != null && (text.contains("?") || text.contains("？"));
    }

    private boolean startsWithAny(String text, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private String blank(String text) {
        return text == null ? "" : text;
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
        String combinedContext = compact + lastAssistant + previousUser + blank(next.currentObjective);
        String offeredObjective = detectOfferedObjective(compact, combinedContext);
        String sceneObjective = inferSceneObjective(compact);
        boolean acceptedPrevious = isAcceptance(compact)
                && (looksLikeProposal(lastAssistant) || hasActiveObjective(next) || !sceneObjective.isBlank());

        next.updatedAt = nowIso;
        next.lastAssistantQuestion = containsQuestion(lastAssistant) ? lastAssistant : "";
        next.userAnsweredLastQuestion = !next.lastAssistantQuestion.isBlank() && !compact.isBlank() && !containsQuestion(compact);

        if (isObjectiveCompletion(compact, next.currentObjective, combinedContext)) {
            next.currentObjective = "";
            next.pendingUserOffer = "";
            next.acceptedPlan = "";
            next.sceneTransitionNeeded = false;
            next.nextBestMove = "承接已完成的动作，回到两人的即时感受。";
            next.mustNotContradict = baseGuards(sceneState);
            next.confidence = 70;
            return next;
        }

        if (!offeredObjective.isBlank()) {
            next.pendingUserOffer = describeObjective(offeredObjective);
            next.currentObjective = offeredObjective;
            next.acceptedPlan = "";
            next.sceneTransitionNeeded = requiresTransition(offeredObjective);
            next.nextBestMove = next.sceneTransitionNeeded
                    ? "顺着这个具体提议完成转场，再把对话自然接下去。"
                    : "回应这个具体提议，明确是一起去、等在原地，还是继续当前动作。";
            next.mustNotContradict = guardsForObjective(next.currentObjective, sceneState);
            next.confidence = 82;
            return next;
        }

        if (acceptedPrevious) {
            String objective = inferObjectiveFromContext(next, lastAssistant, previousUser, sceneObjective);
            next.acceptedPlan = objective;
            next.currentObjective = objective;
            next.pendingUserOffer = "";
            next.sceneTransitionNeeded = requiresTransition(objective);
            next.nextBestMove = "用户已经接受上一轮提议，下一句必须承接计划开始行动，而不是重新开话题。";
            next.mustNotContradict = guardsForObjective(objective, sceneState);
            next.confidence = 88;
            return next;
        }

        if (!sceneObjective.isBlank()) {
            next.currentObjective = sceneObjective;
            next.acceptedPlan = sceneObjective;
            next.pendingUserOffer = "";
            next.sceneTransitionNeeded = requiresTransition(sceneObjective);
            next.nextBestMove = "先完成场景过渡，再顺着新地点继续聊天。";
            next.mustNotContradict = guardsForObjective(sceneObjective, sceneState);
            next.confidence = 80;
            return next;
        }

        if (hasActiveObjective(next)) {
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

    DialogueContinuityState applyHeartbeatSelfContext(
            DialogueContinuityState state,
            String lastAssistantMessage,
            String nowIso
    ) {
        DialogueContinuityState next = normalize(state, nowIso);
        if (!assistantAlreadyCarriedPlan(lastAssistantMessage)) {
            return next;
        }
        boolean hasObjective = hasActiveObjective(next)
                || (next.acceptedPlan != null && !next.acceptedPlan.isBlank())
                || next.sceneTransitionNeeded;
        if (!hasObjective) {
            return next;
        }
        String carriedObjective = firstNonBlank(next.acceptedPlan, next.currentObjective, next.pendingUserOffer);
        next.currentObjective = "";
        next.pendingUserOffer = "";
        next.acceptedPlan = "";
        next.sceneTransitionNeeded = false;
        next.nextBestMove = "\u89d2\u8272\u4e0a\u4e00\u53e5\u5df2\u7ecf\u627f\u63a5\u5e76\u5f00\u59cb\u6267\u884c\u5f53\u524d\u8ba1\u5212\uff0c\u5fc3\u8df3\u53ea\u987a\u7740\u8fd9\u4e2a\u52a8\u4f5c\u8f7b\u8f7b\u7eed\u4e0a\uff0c\u4e0d\u8981\u91cd\u65b0\u8be2\u95ee\u662f\u5426\u8981\u53bb\u6216\u518d\u6b21\u53d1\u8d77\u540c\u4e00\u8ba1\u5212\u3002";
        next.mustNotContradict = next.mustNotContradict == null ? new ArrayList<>() : new ArrayList<>(next.mustNotContradict);
        if (!carriedObjective.isBlank()) {
            next.mustNotContradict.add("\u4e0a\u4e00\u6761\u52a9\u624b\u56de\u590d\u5df2\u627f\u63a5\u8ba1\u5212\uff1a" + carriedObjective);
        }
        next.confidence = Math.max(70, next.confidence);
        next.updatedAt = nowIso;
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

    private String inferObjectiveFromContext(DialogueContinuityState state, String lastAssistant, String previousUser, String sceneObjective) {
        if (hasActiveObjective(state)) {
            if (referencesDrinkContext(compact(lastAssistant + previousUser)) && !state.currentObjective.contains("热饮")) {
                return "一起去买热饮。";
            }
            return state.currentObjective;
        }
        String all = compact(lastAssistant + previousUser);
        if (!sceneObjective.isBlank()) return sceneObjective;
        if (referencesDrinkContext(all)) return "一起去买热饮。";
        if (all.contains("操场")) return "一起去操场。";
        if (all.contains("食堂")) return "一起去食堂。";
        if (all.contains("图书馆")) return "一起去图书馆。";
        if (all.contains("宿舍")) return "送对方回宿舍。";
        if (containsAny(all, List.of("散步", "一起走", "路上"))) return "一起往前走。";
        return "承接上一轮已经确认的共同计划。";
    }

    private String inferSceneObjective(String compact) {
        boolean movingIntent = containsAny(compact, List.of("去", "走吧", "一起走", "一起去", "送你", "送她", "送他", "带你", "陪你", "回", "出发", "路上"));
        if (containsAny(compact, List.of("热饮", "热可可", "奶茶", "咖啡")) && containsAny(compact, List.of("买", "去", "带", "拿"))) return "一起去买热饮。";
        if (movingIntent && compact.contains("操场")) return "一起去操场。";
        if (movingIntent && compact.contains("食堂")) return "一起去食堂。";
        if (movingIntent && compact.contains("图书馆")) return "一起去图书馆。";
        if (movingIntent && compact.contains("宿舍")) return "送对方回宿舍。";
        if (movingIntent && compact.contains("市区")) return "一起去市区。";
        if (compact.contains("走吧") || compact.contains("一起走") || compact.contains("一起去")) return "一起往前走。";
        return "";
    }

    private String detectOfferedObjective(String compact, String context) {
        if (containsAny(compact, List.of("我去给你买", "给你买", "帮你买", "去买一杯", "买一杯", "给你带一杯"))) {
            return referencesDrinkContext(context) ? "一起去买热饮。" : "承接用户提出的具体小行动。";
        }
        if (containsAny(compact, List.of("我陪你去", "带你去", "不如一起去", "那我们去", "要不要一起去"))) {
            String objective = inferSceneObjective(compact);
            if (!objective.isBlank()) {
                return objective;
            }
        }
        if (containsAny(compact, List.of("送你回", "送她回", "送他回"))) {
            return "送对方回宿舍。";
        }
        return "";
    }

    private boolean isObjectiveCompletion(String compact, String currentObjective, String context) {
        if (currentObjective == null || currentObjective.isBlank()) {
            return false;
        }
        if (currentObjective.contains("热饮")) {
            return containsAny(compact, List.of("买到了", "拿到了", "喝上了", "已经买", "买好了", "到热饮摊了"));
        }
        if (currentObjective.contains("食堂")) {
            return containsAny(compact, List.of("到了食堂", "打好饭了", "买好了", "坐下了"));
        }
        if (currentObjective.contains("图书馆")) {
            return containsAny(compact, List.of("到了图书馆", "找到位置了", "坐下了", "在窗边了"));
        }
        if (currentObjective.contains("操场")) {
            return containsAny(compact, List.of("到操场了", "跑起来了", "走到操场了"));
        }
        if (currentObjective.contains("宿舍")) {
            return containsAny(compact, List.of("到宿舍了", "送到楼下了", "送到了", "到楼下了"));
        }
        if (currentObjective.contains("往前走") || currentObjective.contains("路上")) {
            return containsAny(compact, List.of("快到了", "到了", "走到了", "还在路上"));
        }
        return containsAny(compact, List.of("已经好了", "办好了", "搞定了")) && context.length() >= 4;
    }

    private boolean referencesDrinkContext(String text) {
        return containsAny(text, List.of("热饮", "热可可", "奶茶", "咖啡", "买一杯", "喝的"));
    }

    private boolean hasActiveObjective(DialogueContinuityState state) {
        return state != null && state.currentObjective != null && !state.currentObjective.isBlank();
    }

    private boolean requiresTransition(String objective) {
        return objective != null && containsAny(objective, List.of("一起去", "送她回", "送他回", "送对方回", "往前走", "路上", "市区"));
    }

    DialogueContinuityState settleSceneTransitionIfArrived(DialogueContinuityState state, SceneState sceneState, String nowIso) {
        DialogueContinuityState next = cloneState(normalize(state, nowIso));
        if (!next.sceneTransitionNeeded || sceneState == null) {
            return next;
        }
        String target = objectiveTargetLocation(next.currentObjective);
        String location = sceneState.location == null ? "" : sceneState.location;
        String sceneAnchor = compact(location + blank(sceneState.subLocation) + blank(sceneState.sceneSummary));
        String compactTarget = compact(target);
        if (!target.isBlank() && !sceneAnchor.isBlank()
                && (sceneAnchor.contains(compactTarget) || (!location.isBlank() && compactTarget.contains(compact(location))))) {
            String reachedObjective = next.currentObjective;
            next.sceneTransitionNeeded = false;
            next.currentObjective = "";
            next.pendingUserOffer = "";
            next.acceptedPlan = "";
            next.nextBestMove = "\u5df2\u7ecf\u5230\u8fbe" + target + "\uff0c\u987a\u7740\u7528\u6237\u5f53\u524d\u8fd9\u53e5\u8bdd\u81ea\u7136\u63a5\u8bdd\uff0c\u4e0d\u8981\u518d\u5199\u5f80" + target + "\u8d70\u53bb\u7684\u8f6c\u573a\u3002";
            next.mustNotContradict = baseGuards(sceneState);
            next.mustNotContradict.add("\u5df2\u5b8c\u6210\u79fb\u52a8\u76ee\u6807\uff1a" + reachedObjective);
            next.mustNotContradict.add("\u5f53\u524d\u5df2\u7ecf\u5728" + target + "\uff0c\u4e0d\u8981\u518d\u5199\u6b63\u5728\u53bb" + target + "\u3001\u671d" + target + "\u8d70\u53bb\u6216\u91cd\u590d\u8fdb\u5165" + target + "\u3002");
            next.confidence = Math.max(65, next.confidence - 2);
            next.updatedAt = nowIso;
            return next;
        }
        if (target.isBlank() || location.isBlank()) {
            return next;
        }
        if (location.contains(target) || target.contains(location)) {
            next.sceneTransitionNeeded = false;
            next.nextBestMove = "已经到达" + target + "，顺着用户当前这句话自然接话，不要再次写转场。";
            next.mustNotContradict = guardsForObjective(next.currentObjective, sceneState);
            next.confidence = Math.max(65, next.confidence - 2);
            next.updatedAt = nowIso;
        }
        return next;
    }

    private String objectiveTargetLocation(String objective) {
        String compact = compact(objective);
        if (compact.contains("食堂")) return "食堂";
        if (compact.contains("操场")) return "操场";
        if (compact.contains("图书馆")) return "图书馆";
        if (compact.contains("宿舍")) return "宿舍";
        if (compact.contains("市区") || compact.contains("校外")) return "市区";
        if (compact.contains("热饮") || compact.contains("奶茶") || compact.contains("咖啡")) return "热饮";
        return "";
    }

    private String describeObjective(String objective) {
        if (objective == null || objective.isBlank()) {
            return "";
        }
        if (objective.contains("热饮")) {
            return "用户提出围绕买热饮继续互动。";
        }
        if (objective.contains("宿舍")) {
            return "用户提出把行动推进到送回宿舍这条线。";
        }
        if (objective.contains("图书馆") || objective.contains("食堂") || objective.contains("操场") || objective.contains("市区")) {
            return "用户提出把聊天带到更具体的地点里继续。";
        }
        return "用户提出替角色完成一个具体行动。";
    }

    private List<String> guardsForObjective(String objective, SceneState sceneState) {
        List<String> guards = baseGuards(sceneState);
        if (objective != null && !objective.isBlank()) {
            guards.add("不要违背当前共同目标：" + objective);
            guards.add("如果这个计划已经确认，下一句要顺着行动往前，不要重新发起同一个提议。");
        }
        if (objective != null && objective.contains("热饮")) {
            guards.add("不要把买热饮泛化成逛街、从哪里开始逛或重新开新路线。");
            guards.add("下一句应承接出发、路上互动、到热饮摊或选择口味。");
        } else if (objective != null && objective.contains("图书馆")) {
            guards.add("下一句应承接路上、找位置、坐下或安静继续聊，不要突然跳成别的地点。");
        } else if (objective != null && objective.contains("食堂")) {
            guards.add("下一句应承接去食堂、排队、打饭或坐下后的互动。");
        } else if (objective != null && objective.contains("宿舍")) {
            guards.add("下一句应承接送到楼下、路上慢慢聊或到宿舍附近的停顿。");
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
        if (text == null || text.isBlank()) {
            return false;
        }
        String compact = compact(text);
        if (compact.length() <= 6 && containsAny(compact, List.of("好啊", "可以", "行", "嗯", "好呀"))) {
            return true;
        }
        return containsAny(compact, List.of("那去吧", "走吧", "就这样", "听你的", "一起去", "那就这样", "那就去"));
    }

    private boolean looksLikeProposal(String text) {
        return containsAny(text, List.of("要不", "一起", "去吧", "走吧", "不如", "要不要", "陪你", "带你", "先去", "先一起"));
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

    private boolean assistantAlreadyCarriedPlan(String text) {
        String compact = compact(text);
        return containsAny(compact, List.of(
                "\u90a3\u6211\u4eec\u5c31", "\u6211\u4eec\u5c31", "\u90a3\u5c31", "\u8fc7\u53bb\u770b\u770b",
                "\u53bb\u770b\u770b", "\u8d70\u8fc7\u53bb", "\u5f80\u90a3\u8fb9", "\u8fb9\u8d70\u8fb9",
                "\u5148\u53bb", "\u8d70\u5230", "\u5230\u4e86", "\u5750\u4e0b", "\u6211\u4eec\u8fc7\u53bb",
                "\u4e00\u8d77\u8fc7\u53bb", "\u5c31\u8fc7\u53bb"
        ));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
        List<String> userSignals = extractSignals(userMessage);
        audit.feltHeard = intentState == null
                || !intentState.needsEmpathy
                || containsAny(speech, List.of("我听到了", "我明白", "我知道", "我能感觉到", "先别急", "慢慢说", "我在"))
                || referencesAnySignal(speech, userSignals);
        audit.answeredCoreQuestion = intentState == null
                || !"question_check".equals(intentState.primaryIntent)
                || (responsePlan != null && "answer_first".equals(responsePlan.firstMove) && hasSubstantiveAnswer(speech, userMessage));
        audit.usedMemoryNaturally = memoryUsePlan == null
                || "hold".equals(memoryUsePlan.useMode)
                || blank(memoryUsePlan.mergedMemoryText).isBlank()
                || looksLikeNaturalMemoryUse(speech, memoryUsePlan.mergedMemoryText);
        audit.initiativeAppropriate = responsePlan == null
                || !"low".equals(responsePlan.initiativeLevel)
                || questionPressure(speech) == 0;
        audit.sceneConsistent = realityAudit == null || (realityAudit.sceneConsistent && realityAudit.interactionConsistent);
        audit.emotionMatched = intentState == null
                || !intentState.needsEmpathy
                || containsAny(speech, List.of("抱一下", "我在", "别怕", "慢慢说", "我接得住", "先别急"))
                || referencesAnySignal(speech, userSignals);
        audit.overacted = speech.contains("！！！") || countQuestionMarks(speech) > 2 || countOccurrences(speech, "真的") >= 3;
        audit.tooMechanical = strippedLength(speech) < 6;
        if (!audit.feltHeard) audit.notes.add("needs_more_empathy");
        if (!audit.answeredCoreQuestion) audit.notes.add("missed_core_question");
        if (!audit.sceneConsistent) audit.notes.add("scene_conflict");
        if (!audit.usedMemoryNaturally) audit.notes.add("memory_too_forced");
        if (audit.overacted) audit.notes.add("overacted");
        if (audit.tooMechanical) audit.notes.add("too_short");
        return audit;
    }

    private boolean referencesAnySignal(String speech, List<String> signals) {
        for (String signal : signals) {
            if (speech.contains(signal)) {
                return true;
            }
            if (signal.length() >= 4 && speech.contains(signal.substring(0, 2))) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractSignals(String userMessage) {
        List<String> signals = new ArrayList<>();
        if (userMessage == null || userMessage.isBlank()) {
            return signals;
        }
        for (String segment : userMessage.split("[，。！？；,.!?]")) {
            String candidate = segment.trim();
            if (candidate.length() < 2 || candidate.length() > 12) {
                continue;
            }
            if (containsAny(candidate, List.of("这个", "那个", "就是", "一下", "真的", "其实", "我们", "你我", "然后"))) {
                continue;
            }
            if (!signals.contains(candidate)) {
                signals.add(candidate);
            }
            if (signals.size() >= 4) {
                break;
            }
        }
        return signals;
    }

    private boolean hasSubstantiveAnswer(String speech, String userMessage) {
        String compactSpeech = blank(speech).replaceAll("\\s+", "");
        if (compactSpeech.length() < 8) {
            return false;
        }
        if (questionPressure(compactSpeech) > 0 && !containsAny(compactSpeech, List.of("是", "不是", "会", "可以", "因为", "其实", "答案"))) {
            return false;
        }
        if (blank(userMessage).contains("为什么")) {
            return compactSpeech.contains("因为") || compactSpeech.length() >= 12;
        }
        return true;
    }

    private boolean looksLikeNaturalMemoryUse(String speech, String mergedMemoryText) {
        String compactSpeech = blank(speech).replaceAll("\\s+", "");
        if (compactSpeech.length() > 180) {
            return false;
        }
        if (countOccurrences(compactSpeech, "我还记得") > 1) {
            return false;
        }
        if (countOccurrences(compactSpeech, "；") >= 2) {
            return false;
        }
        return mergedMemoryText == null || mergedMemoryText.isBlank() || compactSpeech.contains("记得") || compactSpeech.length() < 140;
    }

    private int questionPressure(String speech) {
        int pressure = countQuestionMarks(speech);
        if (speech.endsWith("吗") || speech.endsWith("呢")) {
            pressure++;
        }
        return pressure;
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private int strippedLength(String text) {
        return blank(text).replaceAll("\\s+", "").length();
    }

    private String blank(String text) {
        return text == null ? "" : text;
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
            AgentProfile agent,
            String nowIso
    ) {
        TurnEvaluation evaluation = relationshipService.evaluateTurn(userMessage, relationshipState, event, memorySummary, agent);
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

class QuickJudgeService {
    private static final String DEFAULT_MODEL = "qwen-plus";
    private static final long DEFAULT_RESOLVE_BUDGET_MS = 120L;
    private static final long MIN_RESOLVE_BUDGET_MS = 60L;
    private static final long MAX_RESOLVE_BUDGET_MS = 5000L;
    private static final List<String> ALLOWED_PRIMARY_INTENTS = List.of(
            "meta_repair",
            "romantic_probe",
            "scene_push",
            "advice_seek",
            "emotion_share",
            "question_check",
            "small_talk",
            "light_chat"
    );
    private static final List<String> ALLOWED_SECONDARY_INTENTS = List.of(
            "memory_callback",
            "transition_hold",
            "pace_guard",
            "plan_anchor",
            "none"
    );
    private static final List<String> ALLOWED_EMOTIONS = List.of("angry", "sad", "warm", "fragile", "neutral");
    private static final List<String> ALLOWED_PRIORITIES = List.of(
            "answer_then_reassure",
            "accept_plan_then_reassure",
            "repair_then_answer",
            "comfort_then_answer",
            "hold_scene_then_answer",
            "answer_then_scene",
            "comfort_then_hold",
            "none"
    );
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "quick-judge");
        thread.setDaemon(true);
        return thread;
    });

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final boolean forceAll;
    private final long resolveBudgetMs;

    QuickJudgeService() {
        this.baseUrl = "";
        this.apiKey = "";
        this.model = DEFAULT_MODEL;
        this.forceAll = envFlag("QUICK_JUDGE_FORCE_ALL");
        this.resolveBudgetMs = envLong("QUICK_JUDGE_WAIT_MS", DEFAULT_RESOLVE_BUDGET_MS, MIN_RESOLVE_BUDGET_MS, MAX_RESOLVE_BUDGET_MS);
        this.timeout = Duration.ofMillis(deriveTimeoutMs(2000L, resolveBudgetMs));
    }

    QuickJudgeService(AppConfig config) {
        this.baseUrl = config == null ? "" : safe(config.plotLlmBaseUrl);
        this.apiKey = config == null ? "" : safe(config.plotLlmApiKey);
        this.model = config == null || safe(config.plotLlmModel).isBlank() ? DEFAULT_MODEL : safe(config.plotLlmModel);
        this.forceAll = envFlag("QUICK_JUDGE_FORCE_ALL");
        this.resolveBudgetMs = envLong("QUICK_JUDGE_WAIT_MS", DEFAULT_RESOLVE_BUDGET_MS, MIN_RESOLVE_BUDGET_MS, MAX_RESOLVE_BUDGET_MS);
        long configuredTimeout = config == null || config.plotLlmTimeout == null ? 2000L : config.plotLlmTimeout.toMillis();
        this.timeout = Duration.ofMillis(deriveTimeoutMs(configuredTimeout, resolveBudgetMs));
    }

    QuickJudgeTask start(
            String userMessage,
            List<ConversationSnippet> recentContext,
            RelationshipState relationshipState,
            SceneState sceneState,
            RelationalTensionState tensionState,
            MemorySummary memorySummary,
            IntentState localIntent,
            DialogueContinuityState localContinuity,
            TurnUnderstandingState turnUnderstanding,
            boolean runtimeEnabled,
            boolean runtimeForceAll,
            int currentTurn
    ) {
        long startedAtNanos = System.nanoTime();
        QuickJudgeTrigger trigger = decideTrigger(userMessage, localIntent, localContinuity, turnUnderstanding, runtimeEnabled, runtimeForceAll, currentTurn);
        if (!remoteEnabled() && trigger.shouldStart) {
            List<String> suppressed = new ArrayList<>(trigger.suppressedReasons);
            suppressed.add("remote_disabled");
            trigger = new QuickJudgeTrigger(false, "skip", "remote_disabled", trigger.score, trigger.reasons, suppressed);
        }
        if (!trigger.shouldStart) {
            QuickJudgeTask task = new QuickJudgeTask(
                    CompletableFuture.completedFuture(QuickJudgeDecision.none("skip:" + trigger.reason)),
                    startedAtNanos,
                    trigger.tier,
                    trigger.reason,
                    trigger.score,
                    trigger.reasons,
                    trigger.suppressedReasons
            );
            task.completedAtNanos.set(startedAtNanos);
            return task;
        }
        CompletableFuture<QuickJudgeDecision> future = CompletableFuture.supplyAsync(() -> {
            try {
                return callRemoteJudge(
                        userMessage,
                        recentContext,
                        relationshipState,
                        sceneState,
                        tensionState,
                        memorySummary,
                        localIntent,
                        localContinuity,
                        turnUnderstanding
                );
            } catch (Exception ex) {
                return QuickJudgeDecision.none("fallback:" + failureReason(ex));
            }
        }, EXECUTOR);
        QuickJudgeTask task = new QuickJudgeTask(
                future,
                startedAtNanos,
                trigger.tier,
                trigger.reason,
                trigger.score,
                trigger.reasons,
                trigger.suppressedReasons
        );
        future.whenComplete((decision, error) -> task.completedAtNanos.compareAndSet(0L, System.nanoTime()));
        return task;
    }

    QuickJudgeDecision resolve(QuickJudgeTask task, long maxWaitMs) {
        if (task == null || task.future == null) {
            return QuickJudgeDecision.none("missing_future");
        }
        if (maxWaitMs <= 0L) {
            return QuickJudgeDecision.none("background_deferred:" + blank(task.triggerReason));
        }
        long waitMs = Math.max(1L, Math.min(maxWaitMs, timeout.toMillis()));
        try {
            return task.future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return QuickJudgeDecision.none("timeout:" + blank(task.triggerTier) + ":" + blank(task.triggerReason));
        } catch (Exception ex) {
            return QuickJudgeDecision.none("resolve:" + ex.getClass().getSimpleName());
        }
    }

    long resolveBudgetMs(Long requestedBudgetMs) {
        if (requestedBudgetMs == null || requestedBudgetMs <= 0L) {
            return resolveBudgetMs;
        }
        return Math.max(MIN_RESOLVE_BUDGET_MS, Math.min(MAX_RESOLVE_BUDGET_MS, requestedBudgetMs));
    }

    long resolveBudgetMs(Long requestedBudgetMs, QuickJudgeTask task) {
        String tier = task == null ? "" : blank(task.triggerTier);
        if ("background".equals(tier)) {
            return 0L;
        }
        return resolveBudgetMs(requestedBudgetMs);
    }

    private long deriveTimeoutMs(long configuredTimeoutMs, long waitBudgetMs) {
        long baseline = Math.max(180L, waitBudgetMs + 250L);
        long configured = Math.max(180L, configuredTimeoutMs);
        return Math.max(baseline, configured);
    }

    private String failureReason(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = safe(ex.getMessage());
        if (!message.isBlank()) {
            return truncate(message.replaceAll("\\s+", " "), 180);
        }
        return ex.getClass().getSimpleName();
    }

    IntentState refineIntentState(IntentState local, QuickJudgeDecision decision, String nowIso) {
        if (local == null || decision == null || !decision.shouldApply()) {
            return local;
        }
        IntentState next = copyIntent(local);
        if (QuickJudgeDecision.hasMeaningfulValue(decision.primaryIntent)) {
            next.primaryIntent = decision.primaryIntent;
        }
        if (QuickJudgeDecision.hasMeaningfulValue(decision.secondaryIntent)) {
            next.secondaryIntent = decision.secondaryIntent;
        }
        if (QuickJudgeDecision.hasMeaningfulEmotion(decision.emotion)) {
            next.emotion = decision.emotion;
        }
        if ("accept_plan_then_reassure".equals(decision.replyPriority)
                || "comfort_then_answer".equals(decision.replyPriority)
                || "comfort_then_hold".equals(decision.replyPriority)
                || "answer_then_reassure".equals(decision.replyPriority)) {
            next.needsEmpathy = true;
        }
        if (QuickJudgeDecision.hasMeaningfulValue(decision.sharedObjective)) {
            next.needsFollowup = true;
        }
        next.rationale = appendRationale(local.rationale, decision);
        next.updatedAt = nowIso;
        return next;
    }

    DialogueContinuityState refineDialogueContinuity(DialogueContinuityState local, QuickJudgeDecision decision, String nowIso) {
        if (local == null || decision == null || !decision.shouldApply()) {
            return local;
        }
        DialogueContinuityState next = copyContinuity(local);
        if (decision.sceneTransitionNeeded) {
            next.sceneTransitionNeeded = true;
        }
        if (QuickJudgeDecision.hasMeaningfulValue(decision.sharedObjective)) {
            next.currentObjective = decision.sharedObjective;
            if (next.acceptedPlan == null || next.acceptedPlan.isBlank()
                    || "accept_plan_then_reassure".equals(decision.replyPriority)
                    || "answer_then_scene".equals(decision.replyPriority)) {
                next.acceptedPlan = decision.sharedObjective;
            }
            if (next.mustNotContradict == null) {
                next.mustNotContradict = new ArrayList<>();
            }
            String guard = "Follow shared objective: " + decision.sharedObjective;
            if (!next.mustNotContradict.contains(guard)) {
                next.mustNotContradict.add(guard);
            }
        }
        if (QuickJudgeDecision.hasMeaningfulValue(decision.nextBestMove)) {
            next.nextBestMove = decision.nextBestMove;
        }
        next.confidence = Math.max(next.confidence, decision.confidence);
        next.updatedAt = nowIso;
        return next;
    }

    ResponsePlan refineResponsePlan(ResponsePlan local, QuickJudgeDecision decision, IntentState intentState, String nowIso) {
        if (local == null || decision == null || !decision.shouldApply()) {
            return local;
        }
        ResponsePlan next = copyResponsePlan(local);
        switch (decision.replyPriority) {
            case "answer_then_reassure" -> {
                next.firstMove = "answer_first";
                next.dialogueMode = "emotional_hold";
            }
            case "accept_plan_then_reassure" -> {
                next.coreTask = "advance_scene";
                next.dialogueMode = "emotional_hold";
                next.shouldAdvanceScene = true;
            }
            case "repair_then_answer" -> next.firstMove = "align";
            case "comfort_then_answer" -> next.firstMove = "empathize_first";
            case "hold_scene_then_answer" -> {
                next.coreTask = "advance_scene";
                next.firstMove = "respond";
                next.shouldAdvanceScene = true;
            }
            case "answer_then_scene" -> {
                next.firstMove = "answer_first";
                next.coreTask = "advance_scene";
                next.shouldAdvanceScene = true;
            }
            case "comfort_then_hold" -> {
                next.firstMove = "empathize_first";
                next.dialogueMode = "emotional_hold";
            }
            default -> {
            }
        }
        if (!decision.sharedObjective.isBlank() && intentState != null && "scene_push".equals(intentState.primaryIntent)) {
            next.shouldAdvanceScene = true;
        }
        next.explanation = appendExplanation(local.explanation, decision);
        next.updatedAt = nowIso;
        return next;
    }

    private QuickJudgeDecision callRemoteJudge(
            String userMessage,
            List<ConversationSnippet> recentContext,
            RelationshipState relationshipState,
            SceneState sceneState,
            RelationalTensionState tensionState,
            MemorySummary memorySummary,
            IntentState localIntent,
            DialogueContinuityState localContinuity,
            TurnUnderstandingState turnUnderstanding
    ) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.1);
        payload.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content", "You are a hidden quick judge for a campus romance chat game. Return ONLY strict JSON. Do not write dialogue. Refine intent and continuity without inventing facts. Prefer keeping the local judgment unless the message is clearly a mixed intent."
                ),
                Map.of(
                        "role", "user",
                        "content", Json.stringify(buildJudgeInput(
                                userMessage,
                                recentContext,
                                relationshipState,
                                sceneState,
                                tensionState,
                                memorySummary,
                                localIntent,
                                localContinuity,
                                turnUnderstanding
                        ))
                )
        ));

        return callHttpJudge(payload);
    }

    private QuickJudgeDecision callHttpJudge(Map<String, Object> payload) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(trimTrailingSlash(baseUrl) + "/chat/completions").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout((int) timeout.toMillis());
        connection.setReadTimeout((int) timeout.toMillis());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(Json.stringify(payload).getBytes(StandardCharsets.UTF_8));
        }

        int status = connection.getResponseCode();
        String raw = new String(
                (status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()).readAllBytes(),
                StandardCharsets.UTF_8
        );
        if (status < 200 || status >= 300) {
            throw new IOException("quick_judge_http_" + status + ":" + raw);
        }

        Map<String, Object> parsed = Json.asObject(Json.parse(raw));
        List<Object> choices = Json.asArray(parsed.get("choices"));
        if (choices.isEmpty()) {
            throw new IOException("quick_judge_empty_choices");
        }
        Map<String, Object> choice = Json.asObject(choices.get(0));
        Map<String, Object> message = Json.asObject(choice.get("message"));
        return parseJudgeJson(Json.asString(message.get("content")));
    }

    private Map<String, Object> buildJudgeInput(
            String userMessage,
            List<ConversationSnippet> recentContext,
            RelationshipState relationshipState,
            SceneState sceneState,
            RelationalTensionState tensionState,
            MemorySummary memorySummary,
            IntentState localIntent,
            DialogueContinuityState localContinuity,
            TurnUnderstandingState turnUnderstanding
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("task", "Refine the local intent, continuity objective, and reply priority for this single user turn.");
        input.put("output_schema", Map.of(
                "primaryIntent", String.join("|", ALLOWED_PRIMARY_INTENTS),
                "secondaryIntent", String.join("|", ALLOWED_SECONDARY_INTENTS),
                "emotion", String.join("|", ALLOWED_EMOTIONS),
                "sharedObjective", "short string, empty if none",
                "sceneTransitionNeeded", "boolean",
                "nextBestMove", "short English instruction, empty if none",
                "replyPriority", String.join("|", ALLOWED_PRIORITIES),
                "confidence", "0-100 integer",
                "reason", "short snake_case reason"
        ));
        input.put("userMessage", safe(userMessage));
        input.put("recentContext", summarizeRecentContext(recentContext));
        input.put("localIntent", Map.of(
                "primaryIntent", localIntent == null ? "" : safe(localIntent.primaryIntent),
                "secondaryIntent", localIntent == null ? "" : safe(localIntent.secondaryIntent),
                "emotion", localIntent == null ? "" : safe(localIntent.emotion),
                "clarity", localIntent == null ? "" : safe(localIntent.clarity),
                "needsEmpathy", localIntent != null && localIntent.needsEmpathy,
                "needsFollowup", localIntent != null && localIntent.needsFollowup
        ));
        input.put("localContinuity", Map.of(
                "currentObjective", localContinuity == null ? "" : safe(localContinuity.currentObjective),
                "acceptedPlan", localContinuity == null ? "" : safe(localContinuity.acceptedPlan),
                "nextBestMove", localContinuity == null ? "" : safe(localContinuity.nextBestMove),
                "sceneTransitionNeeded", localContinuity != null && localContinuity.sceneTransitionNeeded,
                "confidence", localContinuity == null ? 0 : localContinuity.confidence
        ));
        input.put("turnUnderstanding", turnUnderstandingMap(turnUnderstanding));
        input.put("sceneState", Map.of(
                "location", sceneState == null ? "" : safe(sceneState.location),
                "interactionMode", sceneState == null ? "" : safe(sceneState.interactionMode),
                "transitionPending", sceneState != null && sceneState.transitionPending
        ));
        input.put("relationship", Map.of(
                "stage", relationshipState == null ? "" : safe(relationshipState.relationshipStage),
                "score", relationshipState == null ? 0 : relationshipState.affectionScore
        ));
        input.put("tension", Map.of(
                "guarded", tensionState != null && tensionState.guarded,
                "annoyance", tensionState == null ? 0 : tensionState.annoyance,
                "hurt", tensionState == null ? 0 : tensionState.hurt
        ));
        input.put("memory", Map.of(
                "openLoops", limitList(memorySummary == null ? null : memorySummary.openLoops, 3),
                "callbackCandidates", limitList(memorySummary == null ? null : memorySummary.callbackCandidates, 3)
        ));
        input.put("rules", List.of(
                "Prefer the local intent unless the message clearly mixes action and emotion.",
                "Do not invent a new location, objective, or memory.",
                "Only set sharedObjective when the user is accepting, offering, or refining a concrete joint action.",
                "replyPriority must be about order of response, not dialogue style.",
                "When uncertain, return the local interpretation and lower confidence."
        ));
        return input;
    }

    private Map<String, Object> turnUnderstandingMap(TurnUnderstandingState state) {
        if (state == null) {
            return Map.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (state.candidates != null) {
            for (UserReplyActCandidate candidate : state.candidates) {
                if (candidate == null) {
                    continue;
                }
                candidates.add(Map.of(
                        "act", safe(candidate.act),
                        "score", candidate.score,
                        "confidence", candidate.confidence,
                        "evidence", candidate.evidence == null ? List.of() : candidate.evidence
                ));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("primaryAct", safe(state.primaryAct));
        result.put("confidence", state.confidence);
        result.put("assistantObligation", assistantObligationMap(state.assistantObligation));
        result.put("recommendedQuickJudgeTier", safe(state.recommendedQuickJudgeTier));
        result.put("shouldAskQuickJudge", state.shouldAskQuickJudge);
        result.put("sceneMoveKind", safe(state.sceneMoveKind));
        result.put("sceneMoveTarget", safe(state.sceneMoveTarget));
        result.put("sceneMoveReason", safe(state.sceneMoveReason));
        result.put("sceneMoveConfidence", state.sceneMoveConfidence);
        result.put("localConflicts", localConflictMaps(state.localConflicts));
        result.put("candidates", candidates);
        return result;
    }

    private Map<String, Object> assistantObligationMap(AssistantObligation obligation) {
        if (obligation == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", safe(obligation.type));
        map.put("source", safe(obligation.source));
        map.put("priority", obligation.priority);
        map.put("expectedUserActs", obligation.expectedUserActs == null ? List.of() : obligation.expectedUserActs);
        map.put("reason", safe(obligation.reason));
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
            map.put("type", safe(conflict.type));
            map.put("severity", safe(conflict.severity));
            map.put("sourceA", safe(conflict.sourceA));
            map.put("sourceB", safe(conflict.sourceB));
            map.put("recommendedAction", safe(conflict.recommendedAction));
            result.add(map);
        }
        return result;
    }

    private QuickJudgeDecision parseJudgeJson(String content) throws IOException {
        String json = extractJson(content);
        Map<String, Object> object = Json.asObject(Json.parse(json));
        String primaryIntent = sanitizeValue(Json.asString(object.get("primaryIntent")), ALLOWED_PRIMARY_INTENTS);
        String secondaryIntent = sanitizeValue(Json.asString(object.get("secondaryIntent")), ALLOWED_SECONDARY_INTENTS);
        String emotion = sanitizeValue(Json.asString(object.get("emotion")), ALLOWED_EMOTIONS);
        String sharedObjective = truncate(safe(Json.asString(object.get("sharedObjective"))), 80);
        boolean sceneTransitionNeeded = Json.asBoolean(object.get("sceneTransitionNeeded"));
        String nextBestMove = truncate(safe(Json.asString(object.get("nextBestMove"))), 120);
        String replyPriority = sanitizeValue(Json.asString(object.get("replyPriority")), ALLOWED_PRIORITIES);
        int confidence = Math.max(0, Math.min(100, Json.asInt(object.get("confidence"), 0)));
        String reason = truncate(safe(Json.asString(object.get("reason"))), 60);
        secondaryIntent = noneToBlank(secondaryIntent);
        replyPriority = noneToBlank(replyPriority);
        if (!QuickJudgeDecision.hasMeaningfulEmotion(emotion)) {
            emotion = "";
        }
        if (primaryIntent.isBlank()
                && secondaryIntent.isBlank()
                && emotion.isBlank()
                && sharedObjective.isBlank()
                && !sceneTransitionNeeded
                && nextBestMove.isBlank()
                && replyPriority.isBlank()) {
            return QuickJudgeDecision.none("empty_remote");
        }
        return new QuickJudgeDecision(
                true,
                primaryIntent,
                secondaryIntent,
                emotion,
                sharedObjective,
                sceneTransitionNeeded,
                nextBestMove,
                replyPriority,
                confidence,
                reason.isBlank() ? "remote_quick_judge" : reason
        );
    }

    private String noneToBlank(String value) {
        String safe = safe(value);
        return "none".equals(safe) ? "" : safe;
    }

    private record QuickJudgeTrigger(
            boolean shouldStart,
            String tier,
            String reason,
            int score,
            List<String> reasons,
            List<String> suppressedReasons
    ) {
    }

    private QuickJudgeTrigger decideTrigger(
            String userMessage,
            IntentState localIntent,
            DialogueContinuityState localContinuity,
            TurnUnderstandingState turnUnderstanding,
            boolean runtimeEnabled,
            boolean runtimeForceAll,
        int currentTurn
    ) {
        if (!runtimeEnabled) {
            return new QuickJudgeTrigger(false, "skip", "disabled", 0, List.of(), List.of("runtime_disabled"));
        }
        if (forceAll || runtimeForceAll) {
            return new QuickJudgeTrigger(true, "always", "force_all", 100, List.of("force_all"), List.of());
        }
        String compact = compact(userMessage);
        if (compact.isBlank()) {
            return new QuickJudgeTrigger(false, "skip", "empty", 0, List.of(), List.of("empty_message"));
        }
        TriggerExplanation explanation = explainQuickJudgeTrigger(userMessage, localIntent, localContinuity);
        if (isUserSelfRescue(compact, localIntent, localContinuity)) {
            List<String> reasons = new ArrayList<>(explanation.reasons);
            reasons.add("urgent_user_self_rescue:+5");
            return new QuickJudgeTrigger(true, "urgent", "user_self_rescue", Math.max(5, explanation.score), reasons, explanation.suppressedReasons);
        }
        if (turnUnderstanding != null && turnUnderstanding.shouldAskQuickJudge) {
            List<String> reasons = new ArrayList<>(explanation.reasons);
            reasons.add("turn_understanding:" + blank(turnUnderstanding.primaryAct) + ":" + turnUnderstanding.confidence);
            if (turnUnderstanding.localConflicts != null) {
                for (LocalConflict conflict : turnUnderstanding.localConflicts) {
                    if (conflict != null && conflict.type != null && !conflict.type.isBlank()) {
                        reasons.add("understanding_conflict:" + blank(conflict.type) + ":" + blank(conflict.severity));
                    }
                }
            }
            String tier = blank(turnUnderstanding.recommendedQuickJudgeTier);
            int score = Math.max(explanation.score, "urgent".equals(tier) ? 5 : 3);
            if ("urgent".equals(tier)) {
                return new QuickJudgeTrigger(true, "urgent", "turn_understanding", score, reasons, explanation.suppressedReasons);
            }
            if ("opportunistic".equals(tier)) {
                return new QuickJudgeTrigger(true, "opportunistic", "turn_understanding", score, reasons, explanation.suppressedReasons);
            }
            if ("background".equals(tier) && shouldBackgroundReview(currentTurn, compact)) {
                return new QuickJudgeTrigger(true, "background", "turn_understanding", score, reasons, explanation.suppressedReasons);
            }
        }
        if (explanation.score >= 3) {
            return new QuickJudgeTrigger(true, "opportunistic", "rule_high_value", explanation.score, explanation.reasons, explanation.suppressedReasons);
        }
        if (shouldBackgroundReview(currentTurn, compact)) {
            List<String> reasons = new ArrayList<>(explanation.reasons);
            reasons.add("periodic_review_turn:" + currentTurn);
            return new QuickJudgeTrigger(true, "background", "periodic_review", explanation.score, reasons, explanation.suppressedReasons);
        }
        List<String> suppressed = new ArrayList<>(explanation.suppressedReasons);
        suppressed.add("score_below_threshold:" + explanation.score);
        return new QuickJudgeTrigger(false, "skip", "low_value", explanation.score, explanation.reasons, suppressed);
    }

    private record TriggerExplanation(int score, List<String> reasons, List<String> suppressedReasons) {
    }

    private TriggerExplanation explainQuickJudgeTrigger(String userMessage, IntentState localIntent, DialogueContinuityState localContinuity) {
        String compact = compact(userMessage);
        List<String> reasons = new ArrayList<>();
        List<String> suppressed = new ArrayList<>();
        if (compact.isBlank()) {
            suppressed.add("empty_message");
            return new TriggerExplanation(0, reasons, suppressed);
        }
        if (compact.length() > 90) {
            suppressed.add("message_too_long:" + compact.length());
            return new TriggerExplanation(0, reasons, suppressed);
        }
        int score = 0;
        if (localIntent == null) {
            reasons.add("missing_local_intent:+3");
            return new TriggerExplanation(3, reasons, suppressed);
        }
        boolean hasActiveContinuity = localContinuity != null
                && (!blank(localContinuity.currentObjective).isBlank()
                || !blank(localContinuity.acceptedPlan).isBlank()
                || localContinuity.sceneTransitionNeeded);
        if (("small_talk".equals(localIntent.primaryIntent) || "light_chat".equals(localIntent.primaryIntent))
                && !hasActiveContinuity
                && !localIntent.needsEmpathy
                && !"meta_repair".equals(localIntent.primaryIntent)) {
            suppressed.add("plain_light_chat");
            return new TriggerExplanation(0, reasons, suppressed);
        }
        if ("meta_repair".equals(localIntent.primaryIntent)) {
            score += 4;
            reasons.add("primary_meta_repair:+4");
        }
        if ("romantic_probe".equals(localIntent.primaryIntent)) {
            score += 3;
            reasons.add("primary_romantic_probe:+3");
        }
        if ("emotion_share".equals(localIntent.primaryIntent) || localIntent.needsEmpathy) {
            score += 3;
            reasons.add("emotion_or_empathy:+3");
        }
        if ("scene_push".equals(localIntent.primaryIntent) && hasActiveContinuity) {
            score += 3;
            reasons.add("scene_push_with_continuity:+3");
        }
        if ("low".equals(localIntent.clarity)) {
            score += 2;
            reasons.add("low_clarity:+2");
        } else if ("medium".equals(localIntent.clarity)) {
            score += 1;
            reasons.add("medium_clarity:+1");
        }
        if (localContinuity != null && localContinuity.sceneTransitionNeeded) {
            score += 3;
            reasons.add("scene_transition_needed:+3");
        }
        if (hasActiveContinuity && localContinuity.confidence < 70) {
            score += 1;
            reasons.add("continuity_low_confidence:+1");
        }
        if (compact.length() <= 12 && localContinuity != null
                && (hasActiveContinuity
                || (containsAny(compact, List.of(
                "\u53ef\u4ee5", "\u597d\u554a", "\u597d\u5440", "\u542c\u4f60\u7684", "\u90a3\u4f60\u8bb2", "\u4f60\u8bb2", "\u7b97\u4e86", "\u968f\u4fbf"
        ))
                && !"small_talk".equals(localIntent.primaryIntent)))) {
            score += 2;
            reasons.add("short_acceptance_or_followup:+2");
        }
        if ("question_check".equals(localIntent.primaryIntent) && localContinuity != null && localContinuity.sceneTransitionNeeded) {
            score += 4;
            reasons.add("question_during_transition:+4");
        }
        if (!"none".equals(blank(localIntent.secondaryIntent)) && !"".equals(blank(localIntent.secondaryIntent))) {
            score += 1;
            reasons.add("secondary_intent:" + blank(localIntent.secondaryIntent) + ":+1");
        }
        return new TriggerExplanation(score, reasons, suppressed);
    }

    private boolean isUserSelfRescue(String compact, IntentState localIntent, DialogueContinuityState localContinuity) {
        if ("meta_repair".equals(localIntent == null ? "" : blank(localIntent.primaryIntent))) {
            return true;
        }
        if (containsAny(compact, List.of(
                "你没懂", "没懂我", "不是这个意思", "我不是这个意思", "你在说什么",
                "你说什么", "你回复", "你咋回事", "怎么突然", "不对", "不是吧",
                "重复了", "又说这个", "我们不是", "已经在", "刚才不是", "什么意思"
        ))) {
            return true;
        }
        if (compact.length() <= 2 && containsAny(compact, List.of("？", "?"))) {
            return true;
        }
        return localContinuity != null
                && localContinuity.sceneTransitionNeeded
                && compact.length() <= 12
                && containsAny(compact, List.of("不是", "不对", "已经", "在哪", "哪里", "？", "?"));
    }

    private boolean shouldBackgroundReview(int currentTurn, String compact) {
        return currentTurn > 0
                && currentTurn % 4 == 0
                && compact.length() <= 120;
    }

    private boolean containsAny(String text, List<String> keywords) {
        String safe = blank(text);
        for (String keyword : keywords) {
            if (safe.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> summarizeRecentContext(List<ConversationSnippet> recentContext) {
        List<Map<String, Object>> summary = new ArrayList<>();
        if (recentContext == null) {
            return summary;
        }
        int start = Math.max(0, recentContext.size() - 4);
        for (int index = start; index < recentContext.size(); index++) {
            ConversationSnippet snippet = recentContext.get(index);
            if (snippet == null || snippet.text == null || snippet.text.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", safe(snippet.role));
            row.put("text", truncate(snippet.text, 120));
            summary.add(row);
        }
        return summary;
    }

    private IntentState copyIntent(IntentState source) {
        IntentState next = new IntentState();
        next.primaryIntent = source.primaryIntent;
        next.secondaryIntent = source.secondaryIntent;
        next.emotion = source.emotion;
        next.clarity = source.clarity;
        next.needsEmpathy = source.needsEmpathy;
        next.needsStructure = source.needsStructure;
        next.needsFollowup = source.needsFollowup;
        next.isBoundarySensitive = source.isBoundarySensitive;
        next.rationale = source.rationale;
        next.updatedAt = source.updatedAt;
        return next;
    }

    private DialogueContinuityState copyContinuity(DialogueContinuityState source) {
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

    private ResponsePlan copyResponsePlan(ResponsePlan source) {
        ResponsePlan next = new ResponsePlan();
        next.firstMove = source.firstMove;
        next.coreTask = source.coreTask;
        next.initiativeLevel = source.initiativeLevel;
        next.responseLength = source.responseLength;
        next.dialogueMode = source.dialogueMode;
        next.shouldReferenceMemory = source.shouldReferenceMemory;
        next.shouldAdvanceScene = source.shouldAdvanceScene;
        next.shouldAdvancePlot = source.shouldAdvancePlot;
        next.shouldUseUncertainty = source.shouldUseUncertainty;
        next.allowFollowupQuestion = source.allowFollowupQuestion;
        next.explanation = source.explanation;
        next.updatedAt = source.updatedAt;
        return next;
    }

    private String appendRationale(String localRationale, QuickJudgeDecision decision) {
        String base = safe(localRationale);
        String suffix = "quickJudge=" + safe(decision.reason) + ":" + safe(decision.replyPriority) + ":" + decision.confidence;
        return base.isBlank() ? suffix : base + "; " + suffix;
    }

    private String appendExplanation(String explanation, QuickJudgeDecision decision) {
        String base = safe(explanation);
        String suffix = "quickJudge=" + safe(decision.reason) + ",priority=" + safe(decision.replyPriority) + ",confidence=" + decision.confidence;
        return base.isBlank() ? suffix : base + ", " + suffix;
    }

    private String sanitizeValue(String value, List<String> allowed) {
        String safe = safe(value);
        return allowed.contains(safe) ? safe : "";
    }

    private boolean remoteEnabled() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
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

    private String extractJson(String content) throws IOException {
        String text = safe(content).trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("quick_judge_non_json");
        }
        return text.substring(start, end + 1);
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

    private String compact(String value) {
        return safe(value).replaceAll("\\s+", "");
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean envFlag(String key) {
        String value = System.getenv(key);
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
    }

    private long envLong(String key, long fallback, long min, long max) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

class RelationshipCalibrationTask {
    final CompletableFuture<RelationshipScoreCalibration> future;
    final int sourceTurn;

    RelationshipCalibrationTask(CompletableFuture<RelationshipScoreCalibration> future, int sourceTurn) {
        this.future = future;
        this.sourceTurn = sourceTurn;
    }
}

class RelationshipCalibrationService {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "relationship-calibration");
        thread.setDaemon(true);
        return thread;
    });

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    RelationshipCalibrationService() {
        this.baseUrl = "";
        this.apiKey = "";
        this.model = "qwen-plus";
        this.timeout = Duration.ofMillis(2000);
    }

    RelationshipCalibrationService(AppConfig config) {
        this.baseUrl = config == null ? "" : safe(config.plotLlmBaseUrl);
        this.apiKey = config == null ? "" : safe(config.plotLlmApiKey);
        this.model = config == null || safe(config.plotLlmModel).isBlank() ? "qwen-plus" : safe(config.plotLlmModel);
        long configuredTimeout = config == null || config.plotLlmTimeout == null ? 2000L : config.plotLlmTimeout.toMillis();
        this.timeout = Duration.ofMillis(Math.max(1000L, Math.min(4000L, configuredTimeout)));
    }

    boolean shouldStart(int currentTurn, String userMessage, TurnEvaluation localEvaluation) {
        if (!remoteEnabled()) {
            return false;
        }
        if (currentTurn <= 0 || currentTurn % 4 != 0) {
            return false;
        }
        String compact = safe(userMessage).replaceAll("\\s+", "");
        if (compact.isBlank() || compact.length() > 180) {
            return false;
        }
        return localEvaluation != null && localEvaluation.affectionDelta != null;
    }

    RelationshipCalibrationTask start(
            int currentTurn,
            String userMessage,
            List<ConversationSnippet> recentContext,
            AgentProfile agent,
            RelationshipState previousState,
            TurnEvaluation localEvaluation
    ) {
        if (!shouldStart(currentTurn, userMessage, localEvaluation)) {
            return new RelationshipCalibrationTask(
                    CompletableFuture.completedFuture(RelationshipScoreCalibration.none("skip")),
                    currentTurn
            );
        }
        CompletableFuture<RelationshipScoreCalibration> future = CompletableFuture.supplyAsync(() -> {
            try {
                RelationshipScoreCalibration calibration = callRemoteCalibration(
                        currentTurn,
                        userMessage,
                        recentContext,
                        agent,
                        previousState,
                        localEvaluation
                );
                calibration.sourceTurn = currentTurn;
                return calibration;
            } catch (Exception ex) {
                return RelationshipScoreCalibration.none("fallback:" + failureReason(ex));
            }
        }, EXECUTOR);
        return new RelationshipCalibrationTask(future, currentTurn);
    }

    private RelationshipScoreCalibration callRemoteCalibration(
            int currentTurn,
            String userMessage,
            List<ConversationSnippet> recentContext,
            AgentProfile agent,
            RelationshipState previousState,
            TurnEvaluation localEvaluation
    ) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.1);
        payload.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content", "You are a hidden relationship-score calibrator for a campus romance chat game. Return ONLY strict JSON. Do not write dialogue. Calibrate the local score conservatively."
                ),
                Map.of(
                        "role", "user",
                        "content", Json.stringify(buildInput(currentTurn, userMessage, recentContext, agent, previousState, localEvaluation))
                )
        ));
        return callHttp(payload);
    }

    private Map<String, Object> buildInput(
            int currentTurn,
            String userMessage,
            List<ConversationSnippet> recentContext,
            AgentProfile agent,
            RelationshipState previousState,
            TurnEvaluation localEvaluation
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("task", "Check whether the local relationship score needs a tiny next-turn calibration.");
        input.put("rules", List.of(
                "Return only JSON.",
                "Be conservative. If the local score is reasonable, return zero deltas.",
                "Each dimension delta must be between -2 and 2.",
                "Total absolute correction should usually be <= 2.",
                "Do not reward generic small talk unless it shows care, memory, boundary respect, or concrete shared action.",
                "Do not punish shy or short replies if they answer the current context."
        ));
        input.put("output_schema", Map.of(
                "closenessDelta", "-2..2 integer",
                "trustDelta", "-2..2 integer",
                "resonanceDelta", "-2..2 integer",
                "confidence", "0..100 integer",
                "reason", "short snake_case reason"
        ));
        input.put("turn", currentTurn);
        input.put("userMessage", safe(userMessage));
        input.put("agent", Map.of(
                "id", agent == null ? "" : safe(agent.id),
                "name", agent == null ? "" : safe(agent.name),
                "archetype", agent == null ? "" : safe(agent.archetype)
        ));
        input.put("previousRelationship", Map.of(
                "closeness", previousState == null ? 0 : previousState.closeness,
                "trust", previousState == null ? 0 : previousState.trust,
                "resonance", previousState == null ? 0 : previousState.resonance,
                "stage", previousState == null ? "" : safe(previousState.relationshipStage)
        ));
        Delta delta = localEvaluation == null || localEvaluation.affectionDelta == null ? new Delta() : localEvaluation.affectionDelta;
        input.put("localDelta", Map.of(
                "closeness", delta.closeness,
                "trust", delta.trust,
                "resonance", delta.resonance,
                "total", delta.total
        ));
        input.put("localReasons", localEvaluation == null || localEvaluation.scoreReasons == null ? List.of() : localEvaluation.scoreReasons);
        input.put("behaviorTags", localEvaluation == null || localEvaluation.behaviorTags == null ? List.of() : localEvaluation.behaviorTags);
        input.put("riskFlags", localEvaluation == null || localEvaluation.riskFlags == null ? List.of() : localEvaluation.riskFlags);
        input.put("recentContext", summarizeRecentContext(recentContext));
        return input;
    }

    private RelationshipScoreCalibration callHttp(Map<String, Object> payload) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(trimTrailingSlash(baseUrl) + "/chat/completions").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout((int) timeout.toMillis());
        connection.setReadTimeout((int) timeout.toMillis());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(Json.stringify(payload).getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        String raw = new String(
                (status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()).readAllBytes(),
                StandardCharsets.UTF_8
        );
        if (status < 200 || status >= 300) {
            throw new IOException("relationship_calibration_http_" + status + ":" + raw);
        }
        Map<String, Object> parsed = Json.asObject(Json.parse(raw));
        List<Object> choices = Json.asArray(parsed.get("choices"));
        if (choices.isEmpty()) {
            throw new IOException("relationship_calibration_empty_choices");
        }
        Map<String, Object> choice = Json.asObject(choices.get(0));
        Map<String, Object> message = Json.asObject(choice.get("message"));
        return parseCalibrationJson(Json.asString(message.get("content")));
    }

    private RelationshipScoreCalibration parseCalibrationJson(String content) throws IOException {
        Map<String, Object> object = Json.asObject(Json.parse(extractJson(content)));
        RelationshipScoreCalibration calibration = new RelationshipScoreCalibration();
        calibration.used = true;
        calibration.closenessDelta = clamp(Json.asInt(object.get("closenessDelta"), 0), -2, 2);
        calibration.trustDelta = clamp(Json.asInt(object.get("trustDelta"), 0), -2, 2);
        calibration.resonanceDelta = clamp(Json.asInt(object.get("resonanceDelta"), 0), -2, 2);
        calibration.confidence = clamp(Json.asInt(object.get("confidence"), 0), 0, 100);
        calibration.reason = truncate(safe(Json.asString(object.get("reason"))), 80);
        return normalizeTotal(calibration);
    }

    private RelationshipScoreCalibration normalizeTotal(RelationshipScoreCalibration calibration) {
        int total = calibration.closenessDelta + calibration.trustDelta + calibration.resonanceDelta;
        if (Math.abs(total) <= 2) {
            return calibration;
        }
        if (Math.abs(calibration.resonanceDelta) > 0) {
            calibration.resonanceDelta -= Integer.signum(total);
            total = calibration.closenessDelta + calibration.trustDelta + calibration.resonanceDelta;
        }
        if (Math.abs(total) > 2 && Math.abs(calibration.closenessDelta) > 0) {
            calibration.closenessDelta -= Integer.signum(total);
            total = calibration.closenessDelta + calibration.trustDelta + calibration.resonanceDelta;
        }
        if (Math.abs(total) > 2 && Math.abs(calibration.trustDelta) > 0) {
            calibration.trustDelta -= Integer.signum(total);
        }
        return calibration;
    }

    private List<Map<String, Object>> summarizeRecentContext(List<ConversationSnippet> recentContext) {
        List<Map<String, Object>> summary = new ArrayList<>();
        if (recentContext == null) {
            return summary;
        }
        int start = Math.max(0, recentContext.size() - 6);
        for (int index = start; index < recentContext.size(); index++) {
            ConversationSnippet snippet = recentContext.get(index);
            if (snippet == null || snippet.text == null || snippet.text.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", safe(snippet.role));
            row.put("text", truncate(snippet.text, 140));
            summary.add(row);
        }
        return summary;
    }

    private boolean remoteEnabled() {
        return !baseUrl.isBlank() && !apiKey.isBlank();
    }

    private String extractJson(String content) throws IOException {
        String text = safe(content).trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("relationship_calibration_non_json");
        }
        return text.substring(start, end + 1);
    }

    private String trimTrailingSlash(String value) {
        String text = safe(value);
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private String failureReason(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = safe(ex.getMessage());
        if (!message.isBlank()) {
            return truncate(message.replaceAll("\\s+", " "), 160);
        }
        return ex.getClass().getSimpleName();
    }

    private String truncate(String value, int maxLength) {
        String text = safe(value);
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

class PlotDirectorAgentService {
    private static final String DEFAULT_PLOT_MODEL = "qwen-plus";
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    PlotDirectorAgentService() {
        this.baseUrl = "";
        this.apiKey = "";
        this.model = DEFAULT_PLOT_MODEL;
        this.timeout = Duration.ofMillis(12000);
    }

    PlotDirectorAgentService(AppConfig config) {
        this.baseUrl = config == null ? "" : safe(config.plotLlmBaseUrl);
        this.apiKey = config == null ? "" : safe(config.plotLlmApiKey);
        this.model = config == null || safe(config.plotLlmModel).isBlank() ? DEFAULT_PLOT_MODEL : safe(config.plotLlmModel);
        this.timeout = config == null || config.plotLlmTimeout == null ? Duration.ofMillis(12000) : config.plotLlmTimeout;
    }

    PlotDirectorAgentDecision decide(
            String userMessage,
            String replySource,
            int currentTurn,
            int gap,
            int forcePlotAtTurn,
            boolean explicitTransition,
            int signal,
            int plotPressure,
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

        PlotDirectorAgentDecision local = localDecision(text, replySource, currentTurn, gap, forcePlotAtTurn, signal, plotPressure);
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
                    plotPressure,
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
                    local.reason,
                    local.sceneCue,
                    local.transitionLine,
                    local.shouldAdvance
            );
        }
    }

    private PlotDirectorAgentDecision guardDecision(String text, String replySource, int gap, boolean explicitTransition) {
        if (explicitTransition) {
            return new PlotDirectorAgentDecision(
                    "transition_only",
                    "user_requested_scene_transition",
                    "user_requested_scene_transition",
                    transitionCue(text),
                    transitionLine(text),
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
            int signal,
            int plotPressure
    ) {
        if (currentTurn >= forcePlotAtTurn && gap >= 5 && (signal >= 2 || plotPressure >= 4)) {
            return new PlotDirectorAgentDecision(
                    "advance_plot",
                    "force_window_with_context_signal",
                    "\u5267\u60c5\u53ea\u987a\u7740\u521a\u624d\u7684\u8bdd\u5f80\u524d\u534a\u6b65\uff0c\u4e0d\u8df3\u5f00\u5f53\u524d\u7528\u6237\u610f\u601d\u3002",
                    transitionLine(text),
                    true
            );
        }
        if (plotPressure >= 7 && gap >= 3) {
            return new PlotDirectorAgentDecision(
                    "advance_plot",
                    "accumulated_plot_pressure_ready",
                    "\u524d\u51e0\u8f6e\u7684\u6c14\u6c1b\u5df2\u7ecf\u84c4\u5230\u53ef\u4ee5\u81ea\u7136\u5f80\u524d\u8d70\u4e00\u62cd\u3002",
                    transitionLine(text),
                    true
            );
        }
        if (plotPressure >= 5 && gap >= 5) {
            return new PlotDirectorAgentDecision(
                    "advance_plot",
                    "steady_plot_pressure_ready",
                    "\u5267\u60c5\u84c4\u529b\u5df2\u7ecf\u8db3\u591f\uff0c\u53ef\u4ee5\u5728\u4e0d\u62a2\u8bdd\u7684\u60c5\u51b5\u4e0b\u63a8\u8fdb\u534a\u6b65\u3002",
                    transitionLine(text),
                    true
            );
        }
        if (signal >= 4 && gap >= 4) {
            return new PlotDirectorAgentDecision(
                    "advance_plot",
                    "strong_context_signal",
                    "\u628a\u5f53\u524d\u8bdd\u9898\u81ea\u7136\u53d8\u6210\u4e00\u4e2a\u5c0f\u8282\u62cd\uff0c\u800c\u4e0d\u662f\u5207\u8d70\u8bdd\u9898\u3002",
                    transitionLine(text),
                    true
            );
        }
        if ("long_chat_heartbeat".equals(replySource) && gap >= 6 && signal >= 2) {
            return new PlotDirectorAgentDecision(
                    "heartbeat_nudge",
                    "long_chat_soft_nudge",
                    "\u957f\u804a\u540e\u7684\u8f7b\u63a8\u52a8\uff0c\u53ea\u8865\u6c14\u6c1b\uff0c\u4e0d\u5f00\u5927\u5267\u60c5\u3002",
                    "",
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
            int plotPressure,
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
                                plotPressure,
                                emotionState,
                                relationshipState,
                                memorySummary,
                                turnContext
                        ))
                )
        ));

        return callHttpDirector(payload);
    }

    private PlotDirectorAgentDecision callHttpDirector(Map<String, Object> payload) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(trimTrailingSlash(baseUrl) + "/chat/completions").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout((int) timeout.toMillis());
        connection.setReadTimeout((int) timeout.toMillis());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(Json.stringify(payload).getBytes(StandardCharsets.UTF_8));
        }

        int status = connection.getResponseCode();
        String raw = new String(
                (status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()).readAllBytes(),
                StandardCharsets.UTF_8
        );
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
            int plotPressure,
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
                "transitionLine", "one short Chinese transition line for the user-facing scene text, empty when not needed",
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
        input.put("plotPressure", plotPressure);
        Map<String, Object> turnContextPayload = new LinkedHashMap<>();
        turnContextPayload.put("primaryIntent", turnContext == null ? "" : safe(turnContext.primaryIntent));
        turnContextPayload.put("secondaryIntent", turnContext == null ? "" : safe(turnContext.secondaryIntent));
        turnContextPayload.put("clarity", turnContext == null ? "" : safe(turnContext.clarity));
        turnContextPayload.put("userEmotion", turnContext == null ? "" : safe(turnContext.userEmotion));
        turnContextPayload.put("affectionDeltaTotal", turnContext == null ? 0 : turnContext.affectionDeltaTotal);
        turnContextPayload.put("behaviorTags", turnContext == null || turnContext.behaviorTags == null ? List.of() : turnContext.behaviorTags);
        turnContextPayload.put("riskFlags", turnContext == null || turnContext.riskFlags == null ? List.of() : turnContext.riskFlags);
        turnContextPayload.put("scoreReasons", turnContext == null || turnContext.scoreReasons == null ? List.of() : turnContext.scoreReasons);
        turnContextPayload.put("plotSignalBreakdown", Map.of(
                "total", turnContext == null ? 0 : turnContext.plotSignal,
                "pressure", turnContext == null ? 0 : turnContext.plotPressure,
                "scene", turnContext == null ? 0 : turnContext.plotSceneSignal,
                "relationship", turnContext == null ? 0 : turnContext.plotRelationshipSignal,
                "event", turnContext == null ? 0 : turnContext.plotEventSignal,
                "continuity", turnContext == null ? 0 : turnContext.plotContinuitySignal,
                "risk", turnContext == null ? 0 : turnContext.plotRiskSignal
        ));
        turnContextPayload.put("sceneLocation", turnContext == null ? "" : safe(turnContext.sceneLocation));
        turnContextPayload.put("interactionMode", turnContext == null ? "" : safe(turnContext.interactionMode));
        turnContextPayload.put("userReplyAct", turnContext == null ? "" : safe(turnContext.userReplyAct));
        turnContextPayload.put("userReplyActConfidence", turnContext == null ? 0 : turnContext.userReplyActConfidence);
        turnContextPayload.put("assistantObligation", turnContext == null ? Map.of() : assistantObligationMap(turnContext.assistantObligation));
        turnContextPayload.put("localConflicts", turnContext == null ? List.of() : localConflictMaps(turnContext.localConflicts));
        turnContextPayload.put("sceneMoveKind", turnContext == null ? "" : safe(turnContext.sceneMoveKind));
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
        String transitionLine = truncate(safe(Json.asString(object.get("transitionLine"))), 160);
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
        return new PlotDirectorAgentDecision(action, "remote:" + reason, whyNow, sceneCue, transitionLine, shouldAdvance, confidence, riskIfAdvance, requiredUserSignal);
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
                    remote.transitionLine,
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
                remote.transitionLine.isBlank() ? local.transitionLine : remote.transitionLine,
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

    private String transitionLine(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (compact.isBlank()) {
            return "";
        }
        if (containsAny(compact, List.of("\u56fe\u4e66\u9986", "\u81ea\u4e60"))) {
            return "\u6211\u4eec\u987a\u7740\u8fd9\u53e5\u8bdd\u5f80\u56fe\u4e66\u9986\u90a3\u8fb9\u8d70\uff0c\u8fde\u811a\u6b65\u90fd\u4e0d\u81ea\u89c9\u653e\u8f7b\u4e86\u4e00\u70b9\u3002";
        }
        if (containsAny(compact, List.of("\u98df\u5802", "\u6253\u996d", "\u5403\u996d"))) {
            return "\u6211\u4eec\u4ece\u539f\u5730\u6162\u6162\u8d70\u5411\u98df\u5802\u90a3\u8fb9\uff0c\u521a\u624d\u7684\u8bdd\u4e5f\u8ddf\u7740\u843d\u8fdb\u4e86\u66f4\u65e5\u5e38\u7684\u8282\u594f\u91cc\u3002";
        }
        if (containsAny(compact, List.of("\u64cd\u573a", "\u591c\u8dd1", "\u6563\u6b65"))) {
            return "\u6211\u4eec\u987a\u7740\u8fd9\u4e2a\u8bdd\u5934\u5f80\u5916\u8d70\uff0c\u98ce\u4ece\u7a7a\u65f7\u7684\u5730\u65b9\u62c2\u8fc7\u6765\uff0c\u521a\u624d\u7684\u6c14\u6c1b\u4e5f\u88ab\u4e00\u8d77\u5e26\u4e86\u51fa\u53bb\u3002";
        }
        if (containsAny(compact, List.of("\u5bbf\u820d", "\u56de\u5bbf\u820d"))) {
            return "\u6211\u4eec\u987a\u7740\u53bb\u5bbf\u820d\u7684\u65b9\u5411\u6162\u6162\u8d70\u8fc7\u53bb\uff0c\u8fde\u539f\u672c\u505c\u5728\u539f\u5730\u7684\u90a3\u70b9\u5fc3\u7eea\uff0c\u4e5f\u8ddf\u7740\u6709\u4e86\u7740\u843d\u3002";
        }
        if (containsAny(compact, List.of("\u8def\u4e0a", "\u4e00\u8d77\u8d70", "\u9001\u4f60\u56de", "\u9001\u5979\u56de"))) {
            return "\u6211\u4eec\u4e00\u8fb9\u5f80\u524d\u8d70\uff0c\u4e00\u8fb9\u628a\u521a\u624d\u8fd8\u6ca1\u8bf4\u5b8c\u7684\u90a3\u70b9\u8bdd\u6162\u6162\u63a5\u4e0b\u53bb\u3002";
        }
        return "";
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

    private Map<String, Object> assistantObligationMap(AssistantObligation obligation) {
        if (obligation == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", safe(obligation.type));
        map.put("source", safe(obligation.source));
        map.put("priority", obligation.priority);
        map.put("expectedUserActs", obligation.expectedUserActs == null ? List.of() : obligation.expectedUserActs);
        map.put("reason", safe(obligation.reason));
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
            map.put("type", safe(conflict.type));
            map.put("severity", safe(conflict.severity));
            map.put("sourceA", safe(conflict.sourceA));
            map.put("sourceB", safe(conflict.sourceB));
            map.put("recommendedAction", safe(conflict.recommendedAction));
            result.add(map);
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
    private final SceneMoveIntentService sceneMoveIntentService = new SceneMoveIntentService();

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
        SceneMoveIntent moveIntent = sceneMoveIntentService.classify(userMessage, session.dialogueContinuityState, session.sceneState);
        boolean explicitTransition = moveIntent.isExplicitMove();
        int signal = explicitTransition ? 0 : sceneSignal(userMessage, memorySummary, emotionState, weatherContext, timeContext, replySource, moveIntent);
        signal = adjustSignalWithTurnContext(signal, turnContext, currentTurn, current.forcePlotAtTurn);
        int plotPressure = evolvePlotPressure(current.plotPressure, signal, moveIntent, explicitTransition);
        enrichTurnContext(turnContext, moveIntent);
        enrichTurnContext(turnContext, gap, signal, plotPressure, replySource, nowIso);
        enrichPlotSignalBreakdown(
                turnContext,
                signal,
                userMessage,
                memorySummary,
                emotionState,
                replySource,
                moveIntent,
                currentTurn,
                current.forcePlotAtTurn
        );
        PlotDirectorAgentDecision directorDecision = plotDirectorAgentService.decide(
                userMessage,
                replySource,
                currentTurn,
                gap,
                current.forcePlotAtTurn,
                explicitTransition,
                signal,
                plotPressure,
                emotionState,
                relationshipState,
                memorySummary,
                turnContext
        );
        enrichTurnContext(turnContext, directorDecision);
        boolean advanced = directorDecision.shouldAdvance;
        String detail = directorDetail(directorDecision);
        String directorSceneText = directorSceneText(directorDecision);

        if (advanced) {
            next.beatIndex = current.beatIndex + 1;
            next.phase = phaseForBeat(next.beatIndex);
            next.lastPlotTurn = currentTurn;
            next.forcePlotAtTurn = currentTurn + 7;
            next.plotPressure = 0;
            next.sceneFrame = mergeSceneCue(buildSceneFrame(next.phase, userMessage, emotionState, timeContext, weatherContext), directorDecision.sceneCue);
            next.nextBeatHint = nextBeatHint(next.beatIndex);
            next.plotProgress = "第" + next.beatIndex + "/10拍 · " + next.phase;
            pushUniqueLimited(next.openThreads, openThread(userMessage, memorySummary, next.phase) + " / " + directorDecision.reason, 6);
            next.updatedAt = nowIso;
            consumePlotSignal(turnContext, nowIso);
            return new PlotDecision(next, null, null, true, "plot_push", next.sceneFrame, directorSceneText, detail);
        }

        next.sceneFrame = "transition_only".equals(directorDecision.action)
                ? mergeSceneCue(current.sceneFrame, directorDecision.sceneCue)
                : buildAmbientScene(current.sceneFrame, emotionState, timeContext, weatherContext);
        next.plotPressure = plotPressure;
        next.updatedAt = nowIso;
        return new PlotDecision(next, null, null, false, replySource, next.sceneFrame, directorSceneText, detail);
    }

    private void enrichTurnContext(TurnContext turnContext, int gap, int signal, int plotPressure, String replySource, String nowIso) {
        if (turnContext == null) {
            return;
        }
        turnContext.plotGap = gap;
        turnContext.plotSignal = signal;
        turnContext.plotPressure = plotPressure;
        turnContext.replySource = replySource;
        turnContext.updatedAt = nowIso;
    }

    private void enrichPlotSignalBreakdown(
            TurnContext turnContext,
            int totalSignal,
            String userMessage,
            MemorySummary memorySummary,
            EmotionState emotionState,
            String replySource,
            SceneMoveIntent moveIntent,
            int currentTurn,
            int forcePlotAtTurn
    ) {
        if (turnContext == null) {
            return;
        }
        String text = userMessage == null ? "" : userMessage;
        int risk = turnContext.riskFlags == null ? 0 : turnContext.riskFlags.size();
        int relationship = 0;
        if (turnContext.affectionDeltaTotal > 0) {
            relationship++;
        }
        if (turnContext.behaviorTags != null && !turnContext.behaviorTags.isEmpty()) {
            relationship++;
        }
        if ("romantic_probe".equals(turnContext.primaryIntent)) {
            relationship++;
        }

        int event = 0;
        if (memorySummary != null && memorySummary.openLoops != null && !memorySummary.openLoops.isEmpty()) {
            event++;
        }
        if (containsAny(text, List.of("上次", "之前", "还记得", "后来", "答应"))) {
            event++;
        }

        int continuity = 0;
        if (turnContext.continuityObjective != null && !turnContext.continuityObjective.isBlank()) {
            continuity++;
        }
        if (turnContext.continuityAcceptedPlan != null && !turnContext.continuityAcceptedPlan.isBlank()) {
            continuity++;
        }
        if (turnContext.sceneTransitionNeeded || (moveIntent != null && moveIntent.isImplicitMove())) {
            continuity++;
        }
        if (moveIntent != null && moveIntent.blocksPlotAdvance()) {
            continuity = Math.max(0, continuity - 1);
        }
        if ("long_chat_heartbeat".equals(replySource)) {
            continuity++;
        }

        int scene = Math.max(0, totalSignal - relationship - event - continuity + risk);
        if (emotionState != null && emotionState.longing >= 32) {
            relationship = Math.max(relationship, 1);
        }
        if (currentTurn >= forcePlotAtTurn) {
            event++;
        }

        turnContext.plotSceneSignal = Math.max(0, scene);
        turnContext.plotRelationshipSignal = Math.max(0, relationship);
        turnContext.plotEventSignal = Math.max(0, event);
        turnContext.plotContinuitySignal = Math.max(0, continuity);
        turnContext.plotRiskSignal = Math.max(0, risk);
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

    private void consumePlotSignal(TurnContext turnContext, String nowIso) {
        if (turnContext == null) {
            return;
        }
        turnContext.plotGap = 0;
        turnContext.plotSignal = 0;
        turnContext.plotPressure = 0;
        turnContext.plotSceneSignal = 0;
        turnContext.plotRelationshipSignal = 0;
        turnContext.plotEventSignal = 0;
        turnContext.plotContinuitySignal = 0;
        turnContext.plotRiskSignal = 0;
        turnContext.updatedAt = nowIso;
    }

    private void enrichTurnContext(TurnContext turnContext, SceneMoveIntent moveIntent) {
        if (turnContext == null || moveIntent == null) {
            return;
        }
        turnContext.sceneMoveKind = moveIntent.moveType;
        turnContext.sceneMoveTarget = moveIntent.targetLocation;
        turnContext.sceneMoveReason = moveIntent.reason;
        turnContext.sceneMoveConfidence = moveIntent.confidence;
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
        String replyAct = turnContext.userReplyAct == null ? "" : turnContext.userReplyAct;
        if (containsAny(replyAct, List.of("answer_question", "topic_only", "scene_stay", "reject", "defer", "clarify"))) {
            adjusted = Math.max(0, adjusted - 2);
        }
        if (turnContext.sceneMoveKind != null && List.of("stay", "cancel_move", "arrived", "topic_only").contains(turnContext.sceneMoveKind)) {
            adjusted = Math.max(0, adjusted - 2);
        }
        if (turnContext.localConflicts != null && !turnContext.localConflicts.isEmpty()) {
            adjusted = Math.max(0, adjusted - 1);
        }
        if (currentTurn >= forcePlotAtTurn) {
            adjusted++;
        }
        return Math.max(0, adjusted);
    }

    private int evolvePlotPressure(int previousPressure, int signal, SceneMoveIntent moveIntent, boolean explicitTransition) {
        int pressure = Math.max(0, previousPressure);
        if (explicitTransition || moveIntent != null && moveIntent.blocksPlotAdvance()) {
            return Math.max(0, pressure - 1);
        }
        if (signal <= 0) {
            return Math.max(0, pressure - 1);
        }
        int gain = Math.max(1, signal - 2);
        return Math.min(10, Math.max(signal, pressure + gain));
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

    private String directorSceneText(PlotDirectorAgentDecision decision) {
        if (decision == null || decision.transitionLine == null) {
            return "";
        }
        return decision.transitionLine.trim();
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

    private int sceneSignal(String userMessage, MemorySummary memorySummary, EmotionState emotionState, WeatherContext weatherContext, TimeContext timeContext, String replySource, SceneMoveIntent moveIntent) {
        int signal = 0;
        String text = userMessage == null ? "" : userMessage;
        if (moveIntent != null && moveIntent.isSceneTopic()) {
            return 0;
        }
        if (moveIntent != null && moveIntent.isImplicitMove()) {
            signal++;
        }
        if (isCasualPreferenceQuestion(text)) {
            return 0;
        }
        if (text.length() >= 8) signal++;
        if (containsAny(text, List.of("上次", "之前", "还记得", "后来"))) signal++;
        if (containsAny(text, List.of("一起", "下次", "以后", "认真", "靠近", "答应", "热饮", "奶茶", "咖啡", "喜欢我", "有好感", "在意我"))) signal++;
        if (memorySummary != null && memorySummary.openLoops != null && !memorySummary.openLoops.isEmpty()) signal++;
        if (emotionState != null && emotionState.longing >= 32) signal++;
        if (weatherContext != null && weatherContext.summary != null && !weatherContext.summary.isBlank()
                && containsAny(text, List.of("天气", "下雨", "晴", "冷", "热", "风"))) signal++;
        if (timeContext != null && timeContext.localTime != null && !timeContext.localTime.isBlank()
                && containsAny(text, List.of("今天", "今晚", "现在", "刚才", "明天", "下午", "晚上", "深夜"))) signal++;
        if ("long_chat_heartbeat".equals(replySource)) signal += 2;
        return signal;
    }

    private boolean isCasualPreferenceQuestion(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (compact.isBlank() || !containsAny(compact, List.of("吗", "?", "？", "喜欢", "讨厌", "想不想", "要不要"))) {
            return false;
        }
        if (containsAny(compact, List.of("喜欢我", "对我", "有好感", "在意我", "想我", "靠近我"))) {
            return false;
        }
        return containsAny(compact, List.of(
                "淋雨", "雨天", "下雨", "天气", "晴天", "刮风", "风", "冷", "热",
                "奶茶", "咖啡", "热饮", "图书馆", "操场", "食堂", "电影", "歌"
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
        next.plotPressure = current.plotPressure;
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
    private final SceneMoveIntentService sceneMoveIntentService = new SceneMoveIntentService();

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
        return evolve(sceneState, userMessage, null, timeContext, weatherContext, currentTurn, nowIso);
    }

    SceneState evolve(SceneState sceneState, String userMessage, DialogueContinuityState continuityState, TimeContext timeContext, WeatherContext weatherContext, int currentTurn, String nowIso) {
        SceneState next = cloneState(normalize(sceneState, nowIso));
        next.timeOfScene = timeContext == null ? "" : timeContext.dayPart;
        next.weatherMood = weatherContext == null ? "" : weatherContext.summary;
        String text = userMessage == null ? "" : userMessage;
        SceneMoveIntent moveIntent = sceneMoveIntentService.classify(userMessage, continuityState, next);
        String nextLocation = moveIntent.shouldMove && !moveIntent.targetLocation.isBlank()
                ? moveIntent.targetLocation
                : next.location;
        boolean changed = moveIntent.shouldMove && !nextLocation.equals(next.location);
        if (changed) {
            next.location = nextLocation;
            next.subLocation = detectSubLocation(text);
            next.transitionPending = true;
            next.transitionLockUntilTurn = currentTurn + 2;
            next.lastConfirmedSceneTurn = currentTurn;
        } else if (currentTurn >= next.transitionLockUntilTurn) {
            next.transitionPending = false;
        }
        next.interactionMode = moveIntent.shouldMove && !moveIntent.interactionMode.isBlank()
                ? moveIntent.interactionMode
                : detectInteractionMode(text, next.interactionMode);
        next.sceneSummary = buildSceneSummary(next, changed, text, moveIntent);
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
        return "";
    }
    private String detectSubLocation(String text) {
        String compact = compact(text);
        if (containsAny(compact, List.of("窗边", "靠窗"))) return "窗边";
        if (containsAny(compact, List.of("门口", "楼下"))) return "门口";
        if (compact.contains("走廊")) return "走廊";
        if (compact.contains("看台")) return "看台";
        if (compact.contains("摊位")) return "摊位";
        return "";
    }

    private String detectInteractionMode(String text, String fallback) {
        String compact = compact(text);
        if (containsAny(compact, List.of("打电话", "通话", "电话里"))) return "phone_call";
        if (containsAny(compact, List.of("发消息", "回消息", "聊天框", "手机", "屏幕那头"))) return "online_chat";
        if (containsAny(compact, List.of("送你回", "送她回", "送他回", "一起走", "路上", "并肩走"))) return "mixed_transition";
        return fallback == null || fallback.isBlank() ? "face_to_face" : fallback;
    }

    private String buildSceneSummary(SceneState state, boolean changed, String userMessage, SceneMoveIntent moveIntent) {
        if (changed) {
            String place = state.subLocation == null || state.subLocation.isBlank()
                    ? state.location
                    : state.location + state.subLocation;
            return "场景被轻轻带到了" + place + "这一侧，接下来的话也跟着换了气氛。";
        }
        String moveType = moveIntent == null ? "" : safe(moveIntent.moveType);
        boolean duplicateTargetMove = moveIntent != null
                && moveIntent.shouldMove
                && !safe(moveIntent.targetLocation).isBlank()
                && safe(moveIntent.targetLocation).equals(safe(state.location));
        if (("topic_only".equals(moveType)
                || "stay".equals(moveType)
                || "cancel_move".equals(moveType)
                || "arrived".equals(moveType)
                || "no_change".equals(moveType)
                || duplicateTargetMove)
                && !"online_chat".equals(state.interactionMode)
                && !"phone_call".equals(state.interactionMode)) {
            return state.sceneSummary == null || state.sceneSummary.isBlank() ? "\u4f60\u4eec\u8fd8\u5728\u540c\u4e00\u4e2a\u573a\u666f\u91cc\uff0c\u628a\u8bdd\u6162\u6162\u5f80\u4e0b\u63a5\u3002" : state.sceneSummary;
        }
        if ("online_chat".equals(state.interactionMode)) {
            return "话题暂时落回了线上，语气也更像隔着一层屏幕慢慢对上。";
        }
        if ("phone_call".equals(state.interactionMode)) {
            return "这会儿更像是隔着电话把呼吸和停顿都贴近了一点。";
        }
        if (containsAny(userMessage, List.of("送你回", "一起走", "路上", "并肩"))) {
            return "场景开始从原地移动，变成并肩走着继续聊天。";
        }
        if (containsAny(userMessage, List.of("图书馆", "自习", "复习"))) {
            return "你们还留在偏安静的场景里，话也自然放轻了一点。";
        }
        if (containsAny(userMessage, List.of("食堂", "打饭", "吃饭"))) {
            return "气氛更像落在日常陪伴里，连聊天都带着点烟火气。";
        }
        if (containsAny(userMessage, List.of("下雨", "雨"))) {
            return "外面的天色压低了一点，反而把这一小段安静衬得更近。";
        }
        return state.sceneSummary == null || state.sceneSummary.isBlank() ? "你们还在同一个场景里，把话慢慢往下接。" : state.sceneSummary;
    }

    private String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
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
        DialogueContinuityState sceneContinuity = continuityFromTurnContext(turnContext, session.dialogueContinuityState, nowIso);
        SceneState nextScene = sceneDirectorService.evolve(previousScene, userMessage, sceneContinuity, timeContext, weatherContext, currentTurn, nowIso);
        String sceneText = mergeSceneText(base.sceneText, sceneDirectorService.buildSceneText(previousScene, nextScene));
        enrichSceneTurnContext(turnContext, nextScene);

        PlotArcState nextArc = cloneArc(arc);
        nextArc.beatIndex = Math.max(arc.beatIndex, base.nextPlotState == null ? arc.beatIndex : base.nextPlotState.beatIndex);
        nextArc.arcIndex = Math.max(1, ((Math.max(1, nextArc.beatIndex) - 1) / 10) + 1);
        nextArc.phase = base.nextPlotState == null ? arc.phase : base.nextPlotState.phase;
        String stableSceneFrame = stableSceneFrame(nextScene);
        nextArc.sceneFrame = stableSceneFrame;
        nextArc.openThreads = new ArrayList<>(base.nextPlotState == null ? arc.openThreads : base.nextPlotState.openThreads);
        nextArc.lastPlotTurn = base.nextPlotState == null ? arc.lastPlotTurn : base.nextPlotState.lastPlotTurn;
        nextArc.forcePlotAtTurn = base.nextPlotState == null ? arc.forcePlotAtTurn : base.nextPlotState.forcePlotAtTurn;
        nextArc.plotPressure = base.nextPlotState == null ? arc.plotPressure : base.nextPlotState.plotPressure;
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
                stableSceneFrame,
                sceneText,
                base.plotDirectorReason
        );
    }

    private String stableSceneFrame(SceneState scene) {
        if (scene == null) {
            return "你们还在当前场景里自然接话。";
        }
        String location = scene.location == null || scene.location.isBlank() ? "当前场景" : scene.location;
        String subLocation = scene.subLocation == null ? "" : scene.subLocation;
        String place = !subLocation.isBlank() && !location.contains(subLocation) ? location + subLocation : location;
        String mode = switch (scene.interactionMode == null ? "" : scene.interactionMode) {
            case "online_chat" -> "隔着屏幕继续聊天";
            case "phone_call" -> "隔着电话继续聊天";
            case "mixed_transition" -> "边走边继续聊天";
            default -> "面对面继续聊天";
        };
        return place + "，" + mode + "。";
    }

    private DialogueContinuityState continuityFromTurnContext(TurnContext turnContext, DialogueContinuityState fallback, String nowIso) {
        DialogueContinuityState state = fallback == null ? new DialogueContinuityState() : fallback;
        if (turnContext == null) {
            return state;
        }
        DialogueContinuityState next = new DialogueContinuityState();
        next.currentObjective = turnContext.continuityObjective == null ? "" : turnContext.continuityObjective;
        next.acceptedPlan = turnContext.continuityAcceptedPlan == null ? "" : turnContext.continuityAcceptedPlan;
        next.nextBestMove = turnContext.continuityNextBestMove == null ? "" : turnContext.continuityNextBestMove;
        next.sceneTransitionNeeded = turnContext.sceneTransitionNeeded;
        next.mustNotContradict = turnContext.continuityGuards == null ? new ArrayList<>() : new ArrayList<>(turnContext.continuityGuards);
        next.confidence = fallback == null ? 0 : fallback.confidence;
        next.updatedAt = nowIso;
        return next;
    }

    private void enrichSceneTurnContext(TurnContext turnContext, SceneState nextScene) {
        if (turnContext == null || nextScene == null) {
            return;
        }
        turnContext.sceneLocation = nextScene.location == null ? "" : nextScene.location;
        turnContext.interactionMode = nextScene.interactionMode == null ? "" : nextScene.interactionMode;
    }

    private String mergeSceneText(String first, String second) {
        String left = first == null ? "" : first.trim();
        String right = second == null ? "" : second.trim();
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank() || left.contains(right)) {
            return left;
        }
        if (right.contains(left)) {
            return right;
        }
        return left + " " + right;
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
        next.plotPressure = current.plotPressure;
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
