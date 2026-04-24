package com.campuspulse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RemoteTurnAnalysisResult {
    final SemanticRuntimeDecision semanticDecision;
    final AffectionScoreResult affectionScoreResult;
    final PlotDirectorAgentDecision plotDirectorDecision;

    RemoteTurnAnalysisResult(
            SemanticRuntimeDecision semanticDecision,
            AffectionScoreResult affectionScoreResult,
            PlotDirectorAgentDecision plotDirectorDecision
    ) {
        this.semanticDecision = semanticDecision;
        this.affectionScoreResult = affectionScoreResult;
        this.plotDirectorDecision = plotDirectorDecision;
    }
}

final class RemoteTurnAnalysisService {
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final SemanticRuntimeAgentService semanticRuntimeAgentService;
    private final RemoteAffectionJudgeService remoteAffectionJudgeService;
    private final PlotDirectorAgentService plotDirectorAgentService;
    private final LocalAffectionJudgeService localAffectionJudgeService = new LocalAffectionJudgeService();

    RemoteTurnAnalysisService(AppConfig config) {
        this.baseUrl = config == null ? "" : safe(config.plotLlmBaseUrl);
        this.apiKey = config == null ? "" : safe(config.plotLlmApiKey);
        this.model = config == null ? "" : safe(config.plotLlmModel);
        this.timeout = config == null || config.plotLlmTimeout == null ? Duration.ofMillis(12000) : config.plotLlmTimeout;
        this.semanticRuntimeAgentService = new SemanticRuntimeAgentService(config);
        this.remoteAffectionJudgeService = new RemoteAffectionJudgeService(config);
        this.plotDirectorAgentService = new PlotDirectorAgentService(config);
    }

    boolean remoteEnabled() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    RemoteTurnAnalysisResult analyzeUserTurn(
            String userMessage,
            List<ConversationSnippet> shortTerm,
            SceneState sceneState,
            RelationshipState relationshipState,
            EmotionState emotionState,
            RelationalTensionState tensionState,
            MemorySummary memorySummary,
            TimeContext timeContext,
            WeatherContext weatherContext,
            DialogueContinuityState dialogueContinuity,
            IntentState bootstrapIntentState,
            TurnContext bootstrapTurnContext,
            StoryEvent scoringEvent,
            RelationshipService relationshipService,
            String replySource,
            String nowIso,
            int currentTurn,
            int plotGap,
            int forcePlotAtTurn,
            int plotSignal
    ) throws IOException {
        SemanticRuntimeDecision semanticFallback = semanticRuntimeAgentService.localAnalyze(
                userMessage,
                sceneState,
                tensionState,
                timeContext,
                weatherContext,
                replySource,
                nowIso
        );
        AffectionScoreResult localAffectionReference = localAffectionJudgeService.evaluateTurn(
                userMessage,
                relationshipState,
                emotionState,
                scoringEvent,
                memorySummary,
                relationshipService,
                nowIso,
                bootstrapIntentState,
                sceneState,
                tensionState,
                dialogueContinuity,
                replySource
        );

        Map<String, Object> payload = buildPayload(
                userMessage,
                shortTerm,
                sceneState,
                relationshipState,
                emotionState,
                tensionState,
                memorySummary,
                timeContext,
                weatherContext,
                dialogueContinuity,
                bootstrapIntentState,
                bootstrapTurnContext,
                localAffectionReference,
                replySource,
                currentTurn,
                plotGap,
                forcePlotAtTurn,
                plotSignal,
                scoringEvent
        );
        String content = execute(payload);
        Map<String, Object> object = Json.asObject(Json.parse(extractJson(content)));
        Map<String, Object> semanticObject = Json.asObject(object.get("semantic"));
        Map<String, Object> affectionObject = Json.asObject(object.get("affection"));
        Map<String, Object> plotObject = Json.asObject(object.get("plot"));

        SemanticRuntimeDecision semanticDecision = semanticRuntimeAgentService.mergeWithFallback(
                semanticRuntimeAgentService.parseResponseContent(Json.stringify(semanticObject), nowIso),
                semanticFallback,
                nowIso
        );
        AffectionScoreResult affectionScoreResult = remoteAffectionJudgeService.parseResponseContent(
                Json.stringify(affectionObject),
                relationshipState,
                emotionState,
                localAffectionReference,
                nowIso,
                scoringEvent,
                bootstrapIntentState,
                tensionState,
                replySource
        );
        PlotDirectorAgentDecision plotDirectorDecision = plotDirectorAgentService.parseResponseContent(Json.stringify(plotObject));
        return new RemoteTurnAnalysisResult(semanticDecision, affectionScoreResult, plotDirectorDecision);
    }

    private Map<String, Object> buildPayload(
            String userMessage,
            List<ConversationSnippet> shortTerm,
            SceneState sceneState,
            RelationshipState relationshipState,
            EmotionState emotionState,
            RelationalTensionState tensionState,
            MemorySummary memorySummary,
            TimeContext timeContext,
            WeatherContext weatherContext,
            DialogueContinuityState dialogueContinuity,
            IntentState bootstrapIntentState,
            TurnContext bootstrapTurnContext,
            AffectionScoreResult localAffectionReference,
            String replySource,
            int currentTurn,
            int plotGap,
            int forcePlotAtTurn,
            int plotSignal,
            StoryEvent scoringEvent
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.1);
        payload.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content",
                        "You are a unified hidden analysis agent for a campus romance chat game. "
                                + "Return ONLY strict JSON with three top-level objects: semantic, affection, plot. "
                                + "Reuse the shared context once instead of analyzing each task independently. "
                                + "Do not write dialogue. Keep outputs concise, grounded, and internally consistent."
                ),
                Map.of("role", "user", "content", Json.stringify(buildSharedInput(
                        userMessage,
                        shortTerm,
                        sceneState,
                        relationshipState,
                        emotionState,
                        tensionState,
                        memorySummary,
                        timeContext,
                        weatherContext,
                        dialogueContinuity,
                        bootstrapIntentState,
                        bootstrapTurnContext,
                        localAffectionReference,
                        replySource,
                        currentTurn,
                        plotGap,
                        forcePlotAtTurn,
                        plotSignal,
                        scoringEvent
                )))
        ));
        return payload;
    }

    private Map<String, Object> buildSharedInput(
            String userMessage,
            List<ConversationSnippet> shortTerm,
            SceneState sceneState,
            RelationshipState relationshipState,
            EmotionState emotionState,
            RelationalTensionState tensionState,
            MemorySummary memorySummary,
            TimeContext timeContext,
            WeatherContext weatherContext,
            DialogueContinuityState dialogueContinuity,
            IntentState bootstrapIntentState,
            TurnContext bootstrapTurnContext,
            AffectionScoreResult localAffectionReference,
            String replySource,
            int currentTurn,
            int plotGap,
            int forcePlotAtTurn,
            int plotSignal,
            StoryEvent scoringEvent
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("output_schema", Map.of(
                "semantic", "semantic runtime JSON",
                "affection", "affection judge JSON",
                "plot", "plot director JSON"
        ));
        input.put("userMessage", safe(userMessage));
        input.put("replySource", safe(replySource));
        input.put("recentContext", shortTerm == null ? List.of() : shortTerm.stream()
                .limit(6)
                .map(item -> Map.of("role", safe(item.role), "text", safe(item.text)))
                .toList());
        input.put("scene", Map.of(
                "location", sceneState == null ? "" : safe(sceneState.location),
                "subLocation", sceneState == null ? "" : safe(sceneState.subLocation),
                "interactionMode", sceneState == null ? "" : safe(sceneState.interactionMode),
                "summary", sceneState == null ? "" : safe(sceneState.sceneSummary)
        ));
        input.put("relationship", Map.of(
                "stage", relationshipState == null ? "" : safe(relationshipState.relationshipStage),
                "score", relationshipState == null ? 0 : relationshipState.affectionScore,
                "closeness", relationshipState == null ? 0 : relationshipState.closeness,
                "trust", relationshipState == null ? 0 : relationshipState.trust,
                "resonance", relationshipState == null ? 0 : relationshipState.resonance
        ));
        input.put("emotion", Map.of(
                "warmth", emotionState == null ? 0 : emotionState.warmth,
                "safety", emotionState == null ? 0 : emotionState.safety,
                "longing", emotionState == null ? 0 : emotionState.longing,
                "initiative", emotionState == null ? 0 : emotionState.initiative,
                "vulnerability", emotionState == null ? 0 : emotionState.vulnerability,
                "mood", emotionState == null ? "" : safe(emotionState.currentMood)
        ));
        input.put("tension", Map.of(
                "annoyance", tensionState == null ? 0 : tensionState.annoyance,
                "hurt", tensionState == null ? 0 : tensionState.hurt,
                "guarded", tensionState != null && tensionState.guarded,
                "repairReadiness", tensionState == null ? 0 : tensionState.repairReadiness,
                "recentBoundaryHits", tensionState == null ? 0 : tensionState.recentBoundaryHits
        ));
        input.put("memory", Map.of(
                "facts", memorySummary == null || memorySummary.factMemories == null ? List.of() : memorySummary.factMemories.stream().limit(4).map(item -> safe(item.value)).toList(),
                "openLoops", memorySummary == null || memorySummary.openLoops == null ? List.of() : memorySummary.openLoops.stream().limit(4).toList(),
                "sharedMoments", memorySummary == null || memorySummary.sharedMoments == null ? List.of() : memorySummary.sharedMoments.stream().limit(4).toList(),
                "callbackCandidates", memorySummary == null || memorySummary.callbackCandidates == null ? List.of() : memorySummary.callbackCandidates.stream().limit(3).toList()
        ));
        input.put("time", Map.of(
                "dayPart", timeContext == null ? "" : safe(timeContext.dayPart),
                "localTime", timeContext == null ? "" : safe(timeContext.localTime)
        ));
        input.put("weather", Map.of(
                "city", weatherContext == null ? "" : safe(weatherContext.city),
                "summary", weatherContext == null ? "" : safe(weatherContext.summary),
                "live", weatherContext != null && weatherContext.live
        ));
        input.put("continuity", dialogueContinuity == null ? Map.of() : Map.of(
                "currentObjective", safe(dialogueContinuity.currentObjective),
                "acceptedPlan", safe(dialogueContinuity.acceptedPlan),
                "nextBestMove", safe(dialogueContinuity.nextBestMove),
                "mustNotContradict", dialogueContinuity.mustNotContradict == null ? List.of() : dialogueContinuity.mustNotContradict
        ));
        input.put("bootstrapIntent", bootstrapIntentState == null ? Map.of() : Map.of(
                "primaryIntent", safe(bootstrapIntentState.primaryIntent),
                "secondaryIntent", safe(bootstrapIntentState.secondaryIntent),
                "emotion", safe(bootstrapIntentState.emotion),
                "clarity", safe(bootstrapIntentState.clarity),
                "needsEmpathy", bootstrapIntentState.needsEmpathy,
                "needsStructure", bootstrapIntentState.needsStructure,
                "needsFollowup", bootstrapIntentState.needsFollowup,
                "boundarySensitive", bootstrapIntentState.isBoundarySensitive
        ));
        input.put("bootstrapTurnContext", bootstrapTurnContext == null ? Map.of() : Map.of(
                "primaryIntent", safe(bootstrapTurnContext.primaryIntent),
                "secondaryIntent", safe(bootstrapTurnContext.secondaryIntent),
                "clarity", safe(bootstrapTurnContext.clarity),
                "userEmotion", safe(bootstrapTurnContext.userEmotion),
                "sceneLocation", safe(bootstrapTurnContext.sceneLocation),
                "interactionMode", safe(bootstrapTurnContext.interactionMode),
                "continuityObjective", safe(bootstrapTurnContext.continuityObjective),
                "continuityAcceptedPlan", safe(bootstrapTurnContext.continuityAcceptedPlan),
                "continuityNextBestMove", safe(bootstrapTurnContext.continuityNextBestMove),
                "continuityGuards", bootstrapTurnContext.continuityGuards == null ? List.of() : bootstrapTurnContext.continuityGuards
        ));
        TurnEvaluation localTurn = localAffectionReference == null ? null : localAffectionReference.turnEvaluation;
        input.put("localAffectionReference", localTurn == null ? Map.of() : Map.of(
                "closenessDelta", localTurn.affectionDelta == null ? 0 : localTurn.affectionDelta.closeness,
                "trustDelta", localTurn.affectionDelta == null ? 0 : localTurn.affectionDelta.trust,
                "resonanceDelta", localTurn.affectionDelta == null ? 0 : localTurn.affectionDelta.resonance,
                "behaviorTags", localTurn.behaviorTags == null ? List.of() : localTurn.behaviorTags,
                "riskFlags", localTurn.riskFlags == null ? List.of() : localTurn.riskFlags,
                "relationshipFeedback", safe(localTurn.relationshipFeedback)
        ));
        input.put("plotMeta", Map.of(
                "currentTurn", currentTurn,
                "gapSinceLastPlot", plotGap,
                "forcePlotAtTurn", forcePlotAtTurn,
                "contextSignal", plotSignal
        ));
        input.put("event", scoringEvent == null ? Map.of() : Map.of(
                "id", safe(scoringEvent.id),
                "title", safe(scoringEvent.title),
                "theme", safe(scoringEvent.theme),
                "category", safe(scoringEvent.category)
        ));
        input.put("rules", List.of(
                "Semantic must classify the turn, scene continuity, search need, and direct-answer policy.",
                "Affection must score bounded deltas and emotion updates without rushing romance.",
                "Plot must decide hold_plot, advance_plot, or heartbeat_nudge without hijacking the current topic.",
                "Keep semantic, affection, and plot outputs mutually consistent.",
                "Return one JSON object only."
        ));
        return input;
    }

    private String execute(Map<String, Object> payload) throws IOException {
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
            throw new IOException("combined_turn_analysis_interrupted", ex);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("combined_turn_analysis_http_" + response.statusCode() + ":" + response.body());
        }
        Map<String, Object> parsed = Json.asObject(Json.parse(response.body()));
        List<Object> choices = Json.asArray(parsed.get("choices"));
        if (choices.isEmpty()) {
            throw new IOException("combined_turn_analysis_empty_choices");
        }
        Map<String, Object> choice = Json.asObject(choices.get(0));
        Map<String, Object> message = Json.asObject(choice.get("message"));
        return Json.asString(message.get("content"));
    }

    private String extractJson(String content) throws IOException {
        String text = safe(content);
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("combined_turn_analysis_non_json");
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

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
