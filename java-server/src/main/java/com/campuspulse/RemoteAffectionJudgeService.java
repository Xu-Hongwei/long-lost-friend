package com.campuspulse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RemoteAffectionJudgeService extends AffectionJudgeService {
    private static final String DEFAULT_MODEL = "qwen-plus";
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    RemoteAffectionJudgeService(AppConfig config) {
        this.baseUrl = config == null ? "" : safe(config.plotLlmBaseUrl);
        this.apiKey = config == null ? "" : safe(config.plotLlmApiKey);
        this.model = config == null || safe(config.plotLlmModel).isBlank() ? DEFAULT_MODEL : safe(config.plotLlmModel);
        this.timeout = config == null || config.plotLlmTimeout == null ? Duration.ofMillis(12000) : config.plotLlmTimeout;
    }

    @Override
    AffectionScoreResult evaluateTurn(
            String userMessage,
            RelationshipState relationshipState,
            EmotionState emotionState,
            StoryEvent event,
            MemorySummary memorySummary,
            RelationshipService relationshipService,
            String nowIso
    ) {
        return evaluateTurn(
                userMessage,
                relationshipState,
                emotionState,
                event,
                memorySummary,
                relationshipService,
                nowIso,
                null,
                null,
                null,
                null,
                "user_turn"
        );
    }

    @Override
    AffectionScoreResult evaluateTurn(
            String userMessage,
            RelationshipState relationshipState,
            EmotionState emotionState,
            StoryEvent event,
            MemorySummary memorySummary,
            RelationshipService relationshipService,
            String nowIso,
            IntentState intentState,
            SceneState sceneState,
            RelationalTensionState tensionState,
            DialogueContinuityState continuityState,
            String replySource
    ) {
        AffectionScoreResult localReference = super.evaluateTurn(
                userMessage,
                relationshipState,
                emotionState,
                event,
                memorySummary,
                relationshipService,
                nowIso
        );
        if (!remoteEnabled() || userMessage == null || userMessage.isBlank()) {
            return localReference;
        }
        try {
            return scoreRemotely(
                    userMessage,
                    relationshipState,
                    emotionState,
                    event,
                    memorySummary,
                    localReference,
                    nowIso,
                    intentState,
                    sceneState,
                    tensionState,
                    continuityState,
                    replySource
            );
        } catch (Exception ex) {
            List<String> tags = new ArrayList<>(localReference.turnEvaluation.behaviorTags);
            tags.add("remote_score_error:" + ex.getClass().getSimpleName());
            TurnEvaluation fallbackEvaluation = new TurnEvaluation(
                    localReference.turnEvaluation.nextState,
                    localReference.turnEvaluation.affectionDelta,
                    tags,
                    localReference.turnEvaluation.riskFlags,
                    localReference.turnEvaluation.stageChanged,
                    localReference.turnEvaluation.stageProgress,
                    localReference.turnEvaluation.relationshipFeedback
            );
            return new AffectionScoreResult(fallbackEvaluation, localReference.nextEmotion);
        }
    }

    private AffectionScoreResult scoreRemotely(
            String userMessage,
            RelationshipState previousState,
            EmotionState previousEmotion,
            StoryEvent event,
            MemorySummary memorySummary,
            AffectionScoreResult localReference,
            String nowIso,
            IntentState intentState,
            SceneState sceneState,
            RelationalTensionState tensionState,
            DialogueContinuityState continuityState,
            String replySource
    ) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.1);
        payload.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content",
                        "You are a hidden relationship scoring judge for a campus romance chat game. "
                                + "Return ONLY strict JSON. Do not write dialogue. "
                                + "Judge the user's latest message by intent, scene continuity, relationship tension, and current stage. "
                                + "Ordinary chat should be small deltas. Do not reward every friendly sentence as romance. "
                                + "Conflict, apology, repair, memory callback, and plot beats may have stronger but still bounded effects. "
                                + "Always include confidence, impactLevel, repairSignal, offenseLevel, behaviorTags, riskFlags, and a concise reason."
                ),
                Map.of("role", "user", "content", Json.stringify(buildInput(
                        userMessage,
                        previousState,
                        previousEmotion,
                        event,
                        memorySummary,
                        localReference,
                        intentState,
                        sceneState,
                        tensionState,
                        continuityState,
                        replySource
                )))
        ));
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
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
            throw new IOException("affection_judge_interrupted", ex);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("affection_judge_http_" + response.statusCode());
        }
        Map<String, Object> parsed = Json.asObject(Json.parse(response.body()));
        List<Object> choices = Json.asArray(parsed.get("choices"));
        if (choices.isEmpty()) {
            throw new IOException("affection_judge_empty_choices");
        }
        Map<String, Object> choice = Json.asObject(choices.get(0));
        Map<String, Object> message = Json.asObject(choice.get("message"));
        return parseRemoteScore(
                Json.asString(message.get("content")),
                previousState,
                previousEmotion,
                localReference,
                nowIso,
                event,
                intentState,
                tensionState,
                replySource
        );
    }

    private Map<String, Object> buildInput(
            String userMessage,
            RelationshipState previousState,
            EmotionState previousEmotion,
            StoryEvent event,
            MemorySummary memorySummary,
            AffectionScoreResult localReference,
            IntentState intentState,
            SceneState sceneState,
            RelationalTensionState tensionState,
            DialogueContinuityState continuityState,
            String replySource
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("output_schema", Map.ofEntries(
                Map.entry("closenessDelta", "-3..4 integer"),
                Map.entry("trustDelta", "-3..5 integer"),
                Map.entry("resonanceDelta", "-2..6 integer"),
                Map.entry("behaviorTags", "array of Chinese tags"),
                Map.entry("riskFlags", "array of snake_case flags"),
                Map.entry("warmthDelta", "-6..6 integer"),
                Map.entry("safetyDelta", "-6..6 integer"),
                Map.entry("longingDelta", "-6..6 integer"),
                Map.entry("initiativeDelta", "-6..6 integer"),
                Map.entry("vulnerabilityDelta", "-6..6 integer"),
                Map.entry("currentMood", "calm|warm|teasing|protective|uneasy"),
                Map.entry("confidence", "0..100 integer, confidence in this scoring judgment"),
                Map.entry("impactLevel", "minor|medium|major, ordinary chat is usually minor"),
                Map.entry("repairSignal", "boolean, true if user is sincerely repairing conflict"),
                Map.entry("offenseLevel", "none|light|medium|severe"),
                Map.entry("userSignalSummary", "short Chinese summary of what the user did emotionally"),
                Map.entry("relationshipFeedback", "short Chinese explanation"),
                Map.entry("reason", "short snake_case reason")
        ));
        input.put("userMessage", userMessage);
        input.put("scoringContext", Map.of(
                "mode", event == null ? "micro_chat" : "plot_beat",
                "replySource", safe(replySource),
                "earlyRelationship", previousState == null || previousState.affectionScore < 18,
                "hasPlotEvent", event != null,
                "guarded", tensionState != null && tensionState.guarded
        ));
        input.put("intent", intentState == null ? Map.of() : Map.of(
                "primaryIntent", safe(intentState.primaryIntent),
                "secondaryIntent", safe(intentState.secondaryIntent),
                "emotion", safe(intentState.emotion),
                "clarity", safe(intentState.clarity),
                "needsEmpathy", intentState.needsEmpathy,
                "needsFollowup", intentState.needsFollowup,
                "boundarySensitive", intentState.isBoundarySensitive
        ));
        input.put("scene", sceneState == null ? Map.of() : Map.of(
                "location", safe(sceneState.location),
                "subLocation", safe(sceneState.subLocation),
                "interactionMode", safe(sceneState.interactionMode),
                "summary", safe(sceneState.sceneSummary),
                "transitionPending", sceneState.transitionPending
        ));
        input.put("tension", tensionState == null ? Map.of() : Map.of(
                "annoyance", tensionState.annoyance,
                "hurt", tensionState.hurt,
                "guarded", tensionState.guarded,
                "repairReadiness", tensionState.repairReadiness,
                "recentBoundaryHits", tensionState.recentBoundaryHits
        ));
        input.put("continuity", continuityState == null ? Map.of() : Map.of(
                "currentObjective", safe(continuityState.currentObjective),
                "acceptedPlan", safe(continuityState.acceptedPlan),
                "nextBestMove", safe(continuityState.nextBestMove),
                "mustNotContradict", continuityState.mustNotContradict == null ? List.of() : continuityState.mustNotContradict
        ));
        input.put("relationship", Map.of(
                "stage", previousState == null ? "" : safe(previousState.relationshipStage),
                "closeness", previousState == null ? 0 : previousState.closeness,
                "trust", previousState == null ? 0 : previousState.trust,
                "resonance", previousState == null ? 0 : previousState.resonance,
                "score", previousState == null ? 0 : previousState.affectionScore
        ));
        input.put("emotion", Map.of(
                "warmth", previousEmotion == null ? 0 : previousEmotion.warmth,
                "safety", previousEmotion == null ? 0 : previousEmotion.safety,
                "longing", previousEmotion == null ? 0 : previousEmotion.longing,
                "initiative", previousEmotion == null ? 0 : previousEmotion.initiative,
                "vulnerability", previousEmotion == null ? 0 : previousEmotion.vulnerability,
                "mood", previousEmotion == null ? "" : safe(previousEmotion.currentMood)
        ));
        input.put("event", event == null ? Map.of() : Map.of(
                "id", safe(event.id),
                "title", safe(event.title),
                "theme", safe(event.theme),
                "category", safe(event.category)
        ));
        input.put("memory", Map.of(
                "openLoops", memorySummary == null || memorySummary.openLoops == null ? List.of() : memorySummary.openLoops.stream().limit(4).toList(),
                "sharedMoments", memorySummary == null || memorySummary.sharedMoments == null ? List.of() : memorySummary.sharedMoments.stream().limit(4).toList()
        ));
        TurnEvaluation local = localReference.turnEvaluation;
        input.put("localReference", Map.of(
                "closenessDelta", local.affectionDelta.closeness,
                "trustDelta", local.affectionDelta.trust,
                "resonanceDelta", local.affectionDelta.resonance,
                "behaviorTags", local.behaviorTags,
                "riskFlags", local.riskFlags
        ));
        input.put("rules", List.of(
                "Do not rush romance in early relationship stages.",
                "Respect boundaries and penalize offensive or dismissive messages.",
                "Reward sincere sharing, emotional receiving, memory callbacks, and respectful initiative.",
                "If the user gives a short reaction like laughing or acknowledging, usually give small or neutral deltas.",
                "If the scene or continuity suggests the user is answering the previous assistant line, do not treat it as a new unrelated topic.",
                "When tension is guarded, positive deltas should be small unless there is a clear apology or repair signal.",
                "Keep deltas bounded. Do not jump relationship stage directly; backend will gate stages.",
                "Use confidence below 60 when the user's intent is unclear or the message depends heavily on missing context."
        ));
        return input;
    }

    private AffectionScoreResult parseRemoteScore(
            String content,
            RelationshipState previousState,
            EmotionState previousEmotion,
            AffectionScoreResult localReference,
            String nowIso,
            StoryEvent event,
            IntentState intentState,
            RelationalTensionState tensionState,
            String replySource
    ) throws IOException {
        Map<String, Object> object = Json.asObject(Json.parse(extractJson(content)));
        Delta delta = new Delta();
        delta.closeness = clamp(Json.asInt(object.get("closenessDelta"), localReference.turnEvaluation.affectionDelta.closeness), -3, 4);
        delta.trust = clamp(Json.asInt(object.get("trustDelta"), localReference.turnEvaluation.affectionDelta.trust), -3, 5);
        delta.resonance = clamp(Json.asInt(object.get("resonanceDelta"), localReference.turnEvaluation.affectionDelta.resonance), -2, 6);
        delta.total = delta.closeness + delta.trust + delta.resonance;

        int confidence = clamp(Json.asInt(object.get("confidence"), 70), 0, 100);
        String impactLevel = normalizeImpact(Json.asString(object.get("impactLevel")), event);
        boolean repairSignal = Json.asBoolean(object.get("repairSignal"));
        String offenseLevel = normalizeOffense(Json.asString(object.get("offenseLevel")));
        delta = calibrateDelta(
                delta,
                localReference.turnEvaluation.affectionDelta,
                previousState,
                confidence,
                impactLevel,
                repairSignal,
                offenseLevel,
                tensionState,
                intentState,
                replySource,
                event != null
        );

        RelationshipState next = applyRemoteDelta(previousState, delta, localReference.turnEvaluation, object);
        EmotionState nextEmotion = applyRemoteEmotion(previousEmotion, object, localReference.nextEmotion, nowIso);
        List<String> behaviorTags = sanitizeTags(asArrayOrEmpty(object.get("behaviorTags")), localReference.turnEvaluation.behaviorTags);
        behaviorTags.add("remote_affection_score");
        behaviorTags.add("remote_confidence_" + confidence);
        behaviorTags.add("remote_impact_" + impactLevel);
        List<String> riskFlags = sanitizeTags(asArrayOrEmpty(object.get("riskFlags")), localReference.turnEvaluation.riskFlags);
        if (!"none".equals(offenseLevel) && !riskFlags.contains("offense_" + offenseLevel)) {
            riskFlags.add("offense_" + offenseLevel);
        }
        boolean stageChanged = !safe(next.relationshipStage).equals(safe(previousState == null ? "" : previousState.relationshipStage));
        String feedback = firstNonBlank(Json.asString(object.get("relationshipFeedback")), localReference.turnEvaluation.relationshipFeedback);
        next.relationshipFeedback = feedback;
        TurnEvaluation evaluation = new TurnEvaluation(
                next,
                delta,
                behaviorTags,
                riskFlags,
                stageChanged,
                stageChanged ? "关系阶段出现了变化：" + next.relationshipStage : next.stageProgressHint,
                feedback
        );
        return new AffectionScoreResult(evaluation, nextEmotion);
    }

    AffectionScoreResult parseResponseContent(
            String content,
            RelationshipState previousState,
            EmotionState previousEmotion,
            AffectionScoreResult localReference,
            String nowIso,
            StoryEvent event,
            IntentState intentState,
            RelationalTensionState tensionState,
            String replySource
    ) throws IOException {
        return parseRemoteScore(
                content,
                previousState,
                previousEmotion,
                localReference,
                nowIso,
                event,
                intentState,
                tensionState,
                replySource
        );
    }

    private Delta calibrateDelta(
            Delta remote,
            Delta local,
            RelationshipState previousState,
            int confidence,
            String impactLevel,
            boolean repairSignal,
            String offenseLevel,
            RelationalTensionState tensionState,
            IntentState intentState,
            String replySource,
            boolean hasPlotEvent
    ) {
        Delta result = copyDelta(remote);
        Delta localDelta = copyDelta(local);

        if (confidence < 45) {
            result = localDelta;
        } else if (confidence < 70) {
            result.closeness = roundedAverage(result.closeness, localDelta.closeness);
            result.trust = roundedAverage(result.trust, localDelta.trust);
            result.resonance = roundedAverage(result.resonance, localDelta.resonance);
        }

        if ("medium".equals(offenseLevel) || "severe".equals(offenseLevel)) {
            result.closeness = Math.min(result.closeness, -1);
            result.trust = Math.min(result.trust, "severe".equals(offenseLevel) ? -3 : -2);
            result.resonance = Math.min(result.resonance, 0);
        } else if ("light".equals(offenseLevel)) {
            result.trust = Math.min(result.trust, 0);
            result.resonance = Math.min(result.resonance, 1);
        }

        int positiveCap = positiveCap(previousState, impactLevel, replySource, hasPlotEvent);
        if (tensionState != null && tensionState.guarded && !repairSignal) {
            positiveCap = Math.min(positiveCap, 1);
        }
        if (intentState != null && "avoidance".equals(safe(intentState.primaryIntent))) {
            positiveCap = Math.min(positiveCap, 2);
        }
        result = capPositiveTotal(result, positiveCap);
        result.closeness = clamp(result.closeness, -3, hasPlotEvent ? 4 : 3);
        result.trust = clamp(result.trust, -3, hasPlotEvent ? 5 : 3);
        result.resonance = clamp(result.resonance, -2, hasPlotEvent ? 6 : 3);
        result.total = result.closeness + result.trust + result.resonance;
        return result;
    }

    private int positiveCap(RelationshipState previousState, String impactLevel, String replySource, boolean hasPlotEvent) {
        int cap = switch (impactLevel) {
            case "major" -> hasPlotEvent ? 7 : 4;
            case "medium" -> hasPlotEvent ? 5 : 3;
            default -> hasPlotEvent ? 4 : 2;
        };
        if (!hasPlotEvent && !"plot_push".equals(safe(replySource)) && !"choice_result".equals(safe(replySource))) {
            cap = Math.min(cap, 3);
        }
        if (previousState == null || previousState.affectionScore < 18) {
            cap = Math.min(cap, 4);
        }
        return Math.max(0, cap);
    }

    private Delta capPositiveTotal(Delta delta, int cap) {
        Delta result = copyDelta(delta);
        while (positiveTotal(result) > cap) {
            if (result.resonance >= result.closeness && result.resonance >= result.trust && result.resonance > 0) {
                result.resonance -= 1;
            } else if (result.closeness >= result.trust && result.closeness > 0) {
                result.closeness -= 1;
            } else if (result.trust > 0) {
                result.trust -= 1;
            } else {
                break;
            }
        }
        result.total = result.closeness + result.trust + result.resonance;
        return result;
    }

    private int positiveTotal(Delta delta) {
        if (delta == null) {
            return 0;
        }
        return Math.max(0, delta.closeness) + Math.max(0, delta.trust) + Math.max(0, delta.resonance);
    }

    private Delta copyDelta(Delta value) {
        Delta next = new Delta();
        if (value != null) {
            next.closeness = value.closeness;
            next.trust = value.trust;
            next.resonance = value.resonance;
        }
        next.total = next.closeness + next.trust + next.resonance;
        return next;
    }

    private int roundedAverage(int first, int second) {
        return Math.round((first + second) / 2.0f);
    }

    private String normalizeImpact(String raw, StoryEvent event) {
        String text = safe(raw).toLowerCase();
        if ("major".equals(text) || "medium".equals(text) || "minor".equals(text)) {
            return text;
        }
        return event == null ? "minor" : "medium";
    }

    private String normalizeOffense(String raw) {
        String text = safe(raw).toLowerCase();
        return switch (text) {
            case "light", "medium", "severe" -> text;
            default -> "none";
        };
    }

    private RelationshipState applyRemoteDelta(RelationshipState previousState, Delta delta, TurnEvaluation localReference, Map<String, Object> object) {
        RelationshipState previous = previousState == null ? new RelationshipState() : previousState;
        RelationshipState next = new RelationshipState();
        next.closeness = Math.max(0, previous.closeness + delta.closeness);
        next.trust = Math.max(0, previous.trust + delta.trust);
        next.resonance = Math.max(0, previous.resonance + delta.resonance);
        next.affectionScore = next.closeness + next.trust + next.resonance;
        next.relationshipStage = applyStageGate(next, safe(previous.relationshipStage).isBlank() ? "初识" : previous.relationshipStage);
        next.stagnationLevel = Math.max(0, previous.stagnationLevel + (delta.total <= 0 ? 1 : -1));
        next.routeTag = firstNonBlank(previous.routeTag, "日常升温");
        next.stageProgressHint = firstNonBlank(localReference.relationshipFeedback, "关系变化由远程评分智能体评估。");
        next.endingCandidate = buildEnding(next, sanitizeTags(asArrayOrEmpty(object.get("riskFlags")), localReference.riskFlags));
        next.ending = next.endingCandidate;
        return next;
    }

    private EmotionState applyRemoteEmotion(EmotionState previousEmotion, Map<String, Object> object, EmotionState localEmotion, String nowIso) {
        EmotionState base = normalizeEmotion(previousEmotion, nowIso);
        EmotionState next = new EmotionState();
        next.warmth = clamp(base.warmth + clamp(Json.asInt(object.get("warmthDelta"), 0), -6, 6), 0, 100);
        next.safety = clamp(base.safety + clamp(Json.asInt(object.get("safetyDelta"), 0), -6, 6), 0, 100);
        next.longing = clamp(base.longing + clamp(Json.asInt(object.get("longingDelta"), 0), -6, 6), 0, 100);
        next.initiative = clamp(base.initiative + clamp(Json.asInt(object.get("initiativeDelta"), 0), -6, 6), 0, 100);
        next.vulnerability = clamp(base.vulnerability + clamp(Json.asInt(object.get("vulnerabilityDelta"), 0), -6, 6), 0, 100);
        String mood = safe(Json.asString(object.get("currentMood")));
        next.currentMood = List.of("calm", "warm", "teasing", "protective", "uneasy").contains(mood)
                ? mood
                : safe(localEmotion.currentMood).isBlank() ? "calm" : localEmotion.currentMood;
        next.updatedAt = nowIso;
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
        if (state.closeness >= 24 && state.trust >= 24 && state.resonance >= 22 && state.affectionScore >= 78) return "确认关系";
        if (state.closeness >= 18 && state.trust >= 16 && state.resonance >= 16 && state.affectionScore >= 55) return "靠近";
        if (state.closeness >= 10 && state.trust >= 9 && state.resonance >= 9 && state.affectionScore >= 32) return "心动";
        if (state.closeness >= 5 && state.trust >= 3 && state.resonance >= 3 && state.affectionScore >= 12) return "升温";
        return "初识";
    }

    private String buildEnding(RelationshipState state, List<String> riskFlags) {
        if (riskFlags.contains("boundary_hit") || state.stagnationLevel >= 3) return "关系停滞 / 错过";
        if ("确认关系".equals(state.relationshipStage) && state.trust >= 22 && state.resonance >= 20) return "继续发展";
        if (state.affectionScore >= 18 || "心动".equals(state.relationshipStage) || "靠近".equals(state.relationshipStage)) return "暧昧未满";
        return "关系停滞 / 错过";
    }

    private List<String> sanitizeTags(List<Object> raw, List<String> fallback) {
        List<String> result = new ArrayList<>();
        if (raw != null) {
            for (Object item : raw) {
                String value = safe(String.valueOf(item));
                if (!value.isBlank() && result.size() < 8) {
                    result.add(value);
                }
            }
        }
        if (result.isEmpty() && fallback != null) {
            result.addAll(fallback);
        }
        return result;
    }

    private List<Object> asArrayOrEmpty(Object raw) {
        if (raw instanceof List<?>) {
            return Json.asArray(raw);
        }
        return List.of();
    }

    private boolean remoteEnabled() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    private String extractJson(String content) throws IOException {
        String text = safe(content);
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("affection_judge_non_json");
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

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
