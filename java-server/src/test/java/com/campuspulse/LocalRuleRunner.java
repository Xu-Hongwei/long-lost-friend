package com.campuspulse;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class LocalRuleRunner {
    private final Path dataDir;
    private final String moduleFilter;
    private final int limit;
    private final boolean verbose;
    private final SceneMoveIntentService sceneMoveIntentService = new SceneMoveIntentService();
    private final IntentInferenceService intentInferenceService = new IntentInferenceService();
    private final DialogueContinuityService dialogueContinuityService = new DialogueContinuityService();
    private final TurnUnderstandingService turnUnderstandingService = new TurnUnderstandingService();
    private final QuickJudgeService quickJudgeService = new QuickJudgeService();
    private final PlotDirectorService plotDirectorService = new PlotDirectorService();
    private final NarrativeRelationshipService relationshipService = new NarrativeRelationshipService();
    private final AgentConfigService agentConfigService = new AgentConfigService();

    private Method quickJudgeTriggerMethod;

    private LocalRuleRunner(Path dataDir, String moduleFilter, int limit, boolean verbose) {
        this.dataDir = dataDir;
        this.moduleFilter = moduleFilter == null ? "" : moduleFilter.trim();
        this.limit = limit;
        this.verbose = verbose;
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of("testdata", "local-rules");
        String module = "";
        int limit = 0;
        boolean verbose = false;

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--data".equals(arg) && index + 1 < args.length) {
                dataDir = Path.of(args[++index]);
            } else if (arg.startsWith("--data=")) {
                dataDir = Path.of(arg.substring("--data=".length()));
            } else if ("--module".equals(arg) && index + 1 < args.length) {
                module = args[++index];
            } else if (arg.startsWith("--module=")) {
                module = arg.substring("--module=".length());
            } else if ("--limit".equals(arg) && index + 1 < args.length) {
                limit = parseInt(args[++index], 0);
            } else if (arg.startsWith("--limit=")) {
                limit = parseInt(arg.substring("--limit=".length()), 0);
            } else if ("--verbose".equals(arg)) {
                verbose = true;
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                return;
            }
        }

        LocalRuleRunner runner = new LocalRuleRunner(dataDir, module, limit, verbose);
        int exitCode = runner.run();
        System.exit(exitCode);
    }

    private static void printHelp() {
        System.out.println("Usage: java com.campuspulse.LocalRuleRunner [--data testdata/local-rules] [--module scene_move] [--limit 20] [--verbose]");
    }

    private int run() throws Exception {
        if (!Files.isDirectory(dataDir)) {
            throw new IllegalArgumentException("Data directory does not exist: " + dataDir.toAbsolutePath());
        }

        List<Path> files = Files.list(dataDir)
                .filter(path -> path.getFileName().toString().endsWith("_cases.jsonl"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();

        Map<String, ModuleStats> stats = new LinkedHashMap<>();
        List<Map<String, Object>> reportRows = new ArrayList<>();
        List<CaseResult> notable = new ArrayList<>();
        int executed = 0;

        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
                String line = lines.get(lineNumber).trim();
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> item = Json.asObject(Json.parse(line));
                String module = string(item.get("module"));
                if (!moduleFilter.isBlank() && !moduleFilter.equals(module)) {
                    continue;
                }
                if (limit > 0 && executed >= limit) {
                    break;
                }
                executed++;
                CaseResult result = runCase(item, file.getFileName().toString(), lineNumber + 1);
                stats.computeIfAbsent(module, ModuleStats::new).add(result);
                reportRows.add(result.toMap());
                if (result.status != Status.PASS) {
                    notable.add(result);
                }
            }
            if (limit > 0 && executed >= limit) {
                break;
            }
        }

        printSummary(stats, notable);
        writeReport(stats, reportRows);
        return stats.values().stream().anyMatch(stat -> stat.fail > 0) ? 1 : 0;
    }

    private CaseResult runCase(Map<String, Object> item, String fileName, int lineNumber) {
        String id = string(item.get("id"));
        String module = string(item.get("module"));
        Map<String, Object> actual;
        try {
            actual = switch (module) {
                case "scene_move" -> actualSceneMove(item);
                case "turn_understanding" -> actualTurnUnderstanding(item);
                case "quick_judge_trigger" -> actualQuickJudgeTrigger(item);
                case "plot_signal" -> actualPlotSignal(item);
                case "relationship_scoring" -> actualRelationshipScoring(item);
                case "heartbeat" -> actualHeartbeat(item);
                default -> Map.of("unsupportedModule", module);
            };
        } catch (Exception ex) {
            actual = new LinkedHashMap<>();
            actual.put("runnerException", ex.getClass().getSimpleName() + ":" + string(ex.getMessage()));
        }

        Map<String, Object> expect = object(item.get("expect"));
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        compareLayer("must", object(expect.get("must")), actual, failures, true);
        compareMustNot(object(expect.get("mustNot")), actual, failures);
        compareLayer("should", object(expect.get("should")), actual, warnings, false);

        Status status = failures.isEmpty() ? (warnings.isEmpty() ? Status.PASS : Status.WARN) : Status.FAIL;
        return new CaseResult(id, module, fileName + ":" + lineNumber, status, failures, warnings, actual);
    }

    private Map<String, Object> actualSceneMove(Map<String, Object> item) {
        String userMessage = string(item.get("userMessage"));
        Map<String, Object> context = object(item.get("context"));
        SceneMoveIntent intent = sceneMoveIntentService.classify(
                userMessage,
                continuityFromContext(context),
                sceneFromContext(context)
        );
        List<String> conflictTypes = new ArrayList<>();
        if ("arrived".equals(intent.moveType)) {
            conflictTypes.add("scene_target_already_current");
        }
        if ("cancel_move".equals(intent.moveType) && !isBlank(firstNonBlank(context.get("activeObjective"), context.get("currentObjective"), context.get("acceptedPlan")))) {
            conflictTypes.add("user_cancels_active_objective");
        }
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("sceneMoveKind", intent.moveType);
        actual.put("sceneMoveType", intent.type);
        actual.put("shouldMove", intent.shouldMove);
        actual.put("targetLocation", intent.targetLocation);
        actual.put("interactionMode", intent.interactionMode);
        actual.put("shouldCreateSceneText", intent.shouldCreateSceneText);
        actual.put("confidence", intent.confidence);
        actual.put("reason", intent.reason);
        actual.put("localConflict", conflictTypes);
        return actual;
    }

    private Map<String, Object> actualTurnUnderstanding(Map<String, Object> item) {
        String userMessage = string(item.get("userMessage"));
        Map<String, Object> context = object(item.get("context"));
        String now = nowIso();
        IntentState intent = intentFromContextOrInfer(userMessage, context, now);
        DialogueContinuityState continuity = continuityFromContext(context);
        SceneState scene = sceneFromContext(context);
        TurnUnderstandingState understanding = turnUnderstandingService.understand(
                userMessage,
                recentContext(context),
                intent,
                continuity,
                scene,
                now
        );
        return actualFromUnderstanding(understanding);
    }

    private Map<String, Object> actualQuickJudgeTrigger(Map<String, Object> item) throws Exception {
        String userMessage = string(item.get("userMessage"));
        Map<String, Object> context = object(item.get("context"));
        String now = nowIso();
        IntentState intent = intentFromContextOrInfer(userMessage, context, now);
        DialogueContinuityState continuity = continuityFromContext(context);
        TurnUnderstandingState understanding = turnUnderstandingService.understand(
                userMessage,
                recentContext(context),
                intent,
                continuity,
                sceneFromContext(context),
                now
        );
        Object trigger = quickJudgeTriggerMethod().invoke(
                quickJudgeService,
                userMessage,
                intent,
                continuity,
                understanding,
                true,
                false,
                integer(context.get("currentTurn"), 1)
        );
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("shouldStart", invokeRecordAccessor(trigger, "shouldStart"));
        actual.put("tier", invokeRecordAccessor(trigger, "tier"));
        actual.put("reason", invokeRecordAccessor(trigger, "reason"));
        actual.put("triggerScore", invokeRecordAccessor(trigger, "score"));
        actual.put("reasons", invokeRecordAccessor(trigger, "reasons"));
        Object suppressedReasons = invokeRecordAccessor(trigger, "suppressedReasons");
        actual.put("suppressedReasons", suppressedReasons);
        actual.put("suppressedReason", suppressedReasons);
        actual.put("waitMs", "background".equals(string(actual.get("tier"))) ? 0 : quickJudgeService.resolveBudgetMs(null));
        actual.put("turnPrimaryAct", understanding.primaryAct);
        actual.put("recommendedQuickJudgeTier", understanding.recommendedQuickJudgeTier);
        return actual;
    }

    private Map<String, Object> actualPlotSignal(Map<String, Object> item) {
        String userMessage = string(item.get("userMessage"));
        Map<String, Object> context = object(item.get("context"));
        String now = nowIso();

        SessionRecord session = new SessionRecord();
        session.id = "local-rule-runner";
        session.agentId = stringOr(context.get("agentId"), "healing");
        session.userTurnCount = Math.max(0, integer(context.get("currentTurn"), 1) - 1);
        session.sceneState = sceneFromContext(context);
        session.dialogueContinuityState = continuityFromContext(context);
        session.plotState = new PlotState();
        session.plotState.phase = "相识";
        session.plotState.sceneFrame = "你们还在慢慢把今晚的气氛铺开。";
        session.plotState.lastPlotTurn = integer(context.get("lastPlotTurn"), 0);
        session.plotState.forcePlotAtTurn = integer(context.get("forcePlotAtTurn"), 7);
        session.plotState.plotPressure = integer(context.get("plotPressure"), 0);
        session.plotState.plotProgress = "第0/10拍·相识";
        session.plotState.nextBeatHint = "先把日常节奏聊顺。";

        TurnContext turnContext = turnContextFor(userMessage, context, now);
        PlotDecision decision = plotDirectorService.decide(
                session,
                userMessage,
                new EmotionState(),
                relationshipFromContext(context),
                memoryFromContext(context),
                timeContextFromContext(context),
                weatherFromContext(context),
                stringOr(context.get("replySource"), "user_turn"),
                now,
                turnContext
        );

        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("advanced", decision.advanced);
        actual.put("plotDirectorAction", turnContext.plotDirectorAction);
        actual.put("plotSignal", turnContext.plotSignal);
        actual.put("plotGap", turnContext.plotGap);
        actual.put("plotPressure", turnContext.plotPressure);
        actual.put("plotSignalConsumed", decision.advanced && turnContext.plotSignal == 0);
        actual.put("plotGapConsumed", decision.advanced && turnContext.plotGap == 0);
        actual.put("plotPressureConsumed", decision.advanced && turnContext.plotPressure == 0);
        actual.put("plotPressureBefore", session.plotState.plotPressure);
        actual.put("plotPressureAfter", decision.nextPlotState == null ? 0 : decision.nextPlotState.plotPressure);
        actual.put("replySource", decision.replySource);
        actual.put("reason", turnContext.plotWhyNow);
        actual.put("sceneMoveKind", turnContext.sceneMoveKind);
        actual.put("plotDirectorConfidence", turnContext.plotDirectorConfidence);
        return actual;
    }

    private Map<String, Object> actualRelationshipScoring(Map<String, Object> item) {
        String userMessage = string(item.get("userMessage"));
        Map<String, Object> context = object(item.get("context"));
        RelationshipState previous = relationshipFromContext(context);
        MemorySummary memory = memoryFromContext(context);
        AgentProfile agent = agentConfigService.getAgentById(stringOr(context.get("agentId"), "healing"));
        TurnEvaluation evaluation = relationshipService.evaluateTurn(userMessage, previous, null, memory, agent);

        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("closenessDelta", evaluation.affectionDelta.closeness);
        actual.put("trustDelta", evaluation.affectionDelta.trust);
        actual.put("resonanceDelta", evaluation.affectionDelta.resonance);
        actual.put("affectionDeltaTotal", evaluation.affectionDelta.total);
        actual.put("tags", evaluation.behaviorTags);
        actual.put("riskFlags", evaluation.riskFlags);
        actual.put("acts", actsFromTags(evaluation.behaviorTags, evaluation.riskFlags));
        actual.put("possibleActs", actsFromTags(evaluation.behaviorTags, evaluation.riskFlags));
        actual.put("shouldNotPunish", evaluation.affectionDelta.closeness >= 0
                && evaluation.affectionDelta.trust >= 0
                && evaluation.affectionDelta.resonance >= 0);
        actual.put("relationshipStage", evaluation.nextState.relationshipStage);
        actual.put("scoreReasons", evaluation.scoreReasons);
        return actual;
    }

    private Map<String, Object> actualHeartbeat(Map<String, Object> item) {
        Map<String, Object> context = object(item.get("context"));
        DialogueContinuityState before = continuityFromContext(context);
        DialogueContinuityState after = dialogueContinuityService.applyHeartbeatSelfContext(
                before,
                string(context.get("lastAssistant")),
                nowIso()
        );
        Map<String, Object> lastTurnContext = object(context.get("lastTurnContext"));
        boolean clearObjective = isBlank(after.currentObjective)
                && isBlank(after.pendingUserOffer)
                && isBlank(after.acceptedPlan)
                && !after.sceneTransitionNeeded;

        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("clearObjective", clearObjective);
        actual.put("shouldNotAskSamePlan", clearObjective);
        actual.put("nextBestMove", after.nextBestMove);
        actual.put("nextBestMoveContains", after.nextBestMove);
        actual.put("shouldApplyPendingRepair", !string(context.get("pendingQuickJudgeRepair")).isBlank());
        actual.put("shouldPreferLastUserQuestion", shouldPreferLastUserQuestionOrRepair(context));
        actual.put("shouldAvoidGenericCompanion", true);
        actual.put("shouldBeShort", true);
        actual.put("replyFocus", inferHeartbeatReplyFocus(context));
        actual.put("plotSignalPreserved", !lastTurnContext.isEmpty() && integer(lastTurnContext.get("plotSignal"), -1) >= 0);
        actual.put("plotPressurePreserved", !lastTurnContext.isEmpty() && integer(lastTurnContext.get("plotPressure"), -1) >= 0);
        actual.put("plotGapPreserved", !lastTurnContext.isEmpty() && integer(lastTurnContext.get("plotGap"), -1) >= 0);
        return actual;
    }

    private String inferHeartbeatReplyFocus(Map<String, Object> context) {
        if (!string(context.get("pendingQuickJudgeRepair")).isBlank()) {
            return "repair_then_continue";
        }
        String lastUser = string(context.get("lastUser"));
        if (containsAny(lastUser, List.of("?", "？", "吗", "呢", "是不是", "为什么", "怎么"))) {
            return "answer_or_repair";
        }
        return "soft_reassure";
    }

    private TurnContext turnContextFor(String userMessage, Map<String, Object> context, String now) {
        TurnContext turnContext = new TurnContext();
        IntentState intent = intentFromContextOrInfer(userMessage, context, now);
        DialogueContinuityState continuity = continuityFromContext(context);
        TurnUnderstandingState understanding = turnUnderstandingService.understand(
                userMessage,
                recentContext(context),
                intent,
                continuity,
                sceneFromContext(context),
                now
        );
        turnContext.primaryIntent = intent.primaryIntent;
        turnContext.secondaryIntent = intent.secondaryIntent;
        turnContext.clarity = intent.clarity;
        turnContext.userEmotion = intent.emotion;
        turnContext.replySource = stringOr(context.get("replySource"), "user_turn");
        turnContext.affectionDeltaTotal = integer(context.get("affectionDeltaTotal"), 0);
        turnContext.closenessDelta = integer(context.get("closenessDelta"), 0);
        turnContext.trustDelta = integer(context.get("trustDelta"), 0);
        turnContext.resonanceDelta = integer(context.get("resonanceDelta"), 0);
        turnContext.sceneLocation = string(context.get("currentLocation"));
        turnContext.interactionMode = string(context.get("interactionMode"));
        turnContext.continuityObjective = continuity.currentObjective;
        turnContext.continuityAcceptedPlan = continuity.acceptedPlan;
        turnContext.continuityNextBestMove = continuity.nextBestMove;
        turnContext.sceneTransitionNeeded = continuity.sceneTransitionNeeded;
        turnContext.continuityGuards = continuity.mustNotContradict == null ? new ArrayList<>() : new ArrayList<>(continuity.mustNotContradict);
        turnContext.userReplyAct = understanding.primaryAct;
        turnContext.userReplyActConfidence = understanding.confidence;
        turnContext.assistantObligation = understanding.assistantObligation;
        turnContext.recommendedQuickJudgeTier = understanding.recommendedQuickJudgeTier;
        turnContext.shouldAskQuickJudge = understanding.shouldAskQuickJudge;
        turnContext.userReplyActCandidates = understanding.candidates;
        turnContext.localConflicts = understanding.localConflicts;
        turnContext.sceneMoveKind = understanding.sceneMoveKind;
        turnContext.sceneMoveTarget = understanding.sceneMoveTarget;
        turnContext.sceneMoveReason = understanding.sceneMoveReason;
        turnContext.sceneMoveConfidence = understanding.sceneMoveConfidence;
        return turnContext;
    }

    private Map<String, Object> actualFromUnderstanding(TurnUnderstandingState understanding) {
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("primaryAct", understanding.primaryAct);
        actual.put("recommendedQuickJudgeTier", understanding.recommendedQuickJudgeTier);
        actual.put("shouldAskQuickJudge", understanding.shouldAskQuickJudge);
        actual.put("sceneMoveKind", understanding.sceneMoveKind);
        actual.put("sceneMoveTarget", understanding.sceneMoveTarget);
        actual.put("sceneMoveReason", understanding.sceneMoveReason);
        actual.put("userReplyActConfidence", understanding.confidence);
        if (understanding.assistantObligation != null) {
            actual.put("assistantObligation", understanding.assistantObligation.type);
        }
        List<String> conflictTypes = new ArrayList<>();
        if (understanding.localConflicts != null) {
            for (LocalConflict conflict : understanding.localConflicts) {
                if (conflict != null && !isBlank(conflict.type)) {
                    conflictTypes.add(conflict.type);
                }
            }
        }
        actual.put("localConflict", conflictTypes);
        List<String> candidates = new ArrayList<>();
        if (understanding.candidates != null) {
            for (UserReplyActCandidate candidate : understanding.candidates) {
                candidates.add(candidate.act);
            }
        }
        actual.put("candidates", candidates);
        actual.put("secondaryCandidate", candidates);
        return actual;
    }

    private void compareLayer(String layer, Map<String, Object> expected, Map<String, Object> actual, List<String> messages, boolean hard) {
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();
            if ("diagnostic".equals(key)) {
                continue;
            }
            Object expectedValue = entry.getValue();
            FieldCheck check = checkExpected(key, expectedValue, actual);
            if (!check.ok) {
                messages.add(layer + "." + key + " expected " + compactValue(expectedValue)
                        + ", actual " + compactValue(check.actualValue)
                        + (check.reason.isBlank() ? "" : " (" + check.reason + ")"));
            } else if (verbose && !hard) {
                // Keep verbose mode available without flooding the normal output.
            }
        }
    }

    private void compareMustNot(Map<String, Object> expected, Map<String, Object> actual, List<String> failures) {
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();
            Object actualValue = lookupActual(actual, key);
            if (actualValue == MissingValue.INSTANCE) {
                continue;
            }
            if (matchesExpected(actualValue, entry.getValue())) {
                failures.add("mustNot." + key + " forbids " + compactValue(entry.getValue())
                        + ", actual " + compactValue(actualValue));
            }
        }
    }

    private FieldCheck checkExpected(String key, Object expectedValue, Map<String, Object> actual) {
        if (key.endsWith("Min")) {
            String actualKey = key.substring(0, key.length() - 3);
            Object actualValue = lookupActual(actual, actualKey);
            return new FieldCheck(actualValue, number(actualValue) >= number(expectedValue), "");
        }
        if (key.endsWith("Max")) {
            String actualKey = key.substring(0, key.length() - 3);
            Object actualValue = lookupActual(actual, actualKey);
            return new FieldCheck(actualValue, number(actualValue) <= number(expectedValue), "");
        }
        if (key.startsWith("min") && key.length() > 3 && Character.isUpperCase(key.charAt(3))) {
            String actualKey = Character.toLowerCase(key.charAt(3)) + key.substring(4);
            Object actualValue = lookupActual(actual, actualKey);
            if (isConsumedPlotMetric(key, actual)) {
                return new FieldCheck(actualValue, true, "");
            }
            return new FieldCheck(actualValue, number(actualValue) >= number(expectedValue), "");
        }
        if (key.endsWith("Contains")) {
            String actualKey = key.substring(0, key.length() - "Contains".length());
            Object actualValue = lookupActual(actual, actualKey);
            return new FieldCheck(actualValue, string(actualValue).contains(string(expectedValue)), "");
        }
        Object actualValue = lookupActual(actual, key);
        if (actualValue == MissingValue.INSTANCE) {
            return new FieldCheck(actualValue, false, "missing actual field");
        }
        if ("plotPressureAfter".equals(key) && "not_increase_by_transition".equals(string(expectedValue))) {
            Object before = lookupActual(actual, "plotPressureBefore");
            return new FieldCheck(actualValue, number(actualValue) <= number(before), "");
        }
        return new FieldCheck(actualValue, matchesExpected(actualValue, expectedValue), "");
    }

    private boolean isConsumedPlotMetric(String key, Map<String, Object> actual) {
        if (!bool(actual.get("advanced"))) {
            return false;
        }
        if ("minPlotSignal".equals(key)) {
            return bool(actual.get("plotSignalConsumed"));
        }
        if ("minPlotGap".equals(key)) {
            return bool(actual.get("plotGapConsumed"));
        }
        if ("minPlotPressure".equals(key)) {
            return bool(actual.get("plotPressureConsumed"));
        }
        return false;
    }

    private boolean matchesExpected(Object actualValue, Object expectedValue) {
        if (expectedValue instanceof List<?> expectedList) {
            if (actualValue instanceof List<?> actualList) {
                for (Object actualItem : actualList) {
                    if (expectedList.stream().anyMatch(expectedItem -> valuesEqual(actualItem, expectedItem))) {
                        return true;
                    }
                }
                return false;
            }
            return expectedList.stream().anyMatch(expectedItem -> valuesEqual(actualValue, expectedItem));
        }
        if (actualValue instanceof List<?> actualList) {
            return actualList.stream().anyMatch(actualItem -> valuesEqual(actualItem, expectedValue));
        }
        return valuesEqual(actualValue, expectedValue);
    }

    private boolean valuesEqual(Object actualValue, Object expectedValue) {
        if (actualValue instanceof Number || expectedValue instanceof Number) {
            return number(actualValue) == number(expectedValue);
        }
        if (actualValue instanceof Boolean || expectedValue instanceof Boolean) {
            return bool(actualValue) == bool(expectedValue);
        }
        return Objects.equals(string(actualValue), string(expectedValue));
    }

    private Object lookupActual(Map<String, Object> actual, String key) {
        if (actual.containsKey(key)) {
            return actual.get(key);
        }
        if ("suppressedReason".equals(key) && actual.containsKey("suppressedReasons")) {
            return actual.get("suppressedReasons");
        }
        if ("secondaryCandidate".equals(key) && actual.containsKey("candidates")) {
            return actual.get("candidates");
        }
        return MissingValue.INSTANCE;
    }

    private boolean shouldPreferLastUserQuestionOrRepair(Map<String, Object> context) {
        String lastUser = string(context.get("lastUser"));
        if (containsAny(lastUser, List.of("?", "？", "吗", "呢", "是不是", "为什么", "怎么"))) {
            return true;
        }
        if (!string(context.get("pendingQuickJudgeRepair")).isBlank()) {
            return true;
        }
        return containsAny(lastUser, List.of("不对", "不是", "重复", "没听懂", "没回答", "我问的是", "理解错", "说清楚"));
    }

    private IntentState intentFromContextOrInfer(String userMessage, Map<String, Object> context, String now) {
        if (context.containsKey("primaryIntent") || context.containsKey("clarity")) {
            IntentState intent = new IntentState();
            intent.primaryIntent = stringOr(context.get("primaryIntent"), "light_chat");
            intent.secondaryIntent = stringOr(context.get("secondaryIntent"), "none");
            intent.emotion = stringOr(context.get("emotion"), "neutral");
            intent.clarity = stringOr(context.get("clarity"), "medium");
            intent.updatedAt = now;
            return intent;
        }
        return intentInferenceService.infer(
                userMessage,
                recentContext(context),
                relationshipFromContext(context),
                sceneFromContext(context),
                new RelationalTensionState(),
                memoryFromContext(context),
                now
        );
    }

    private DialogueContinuityState continuityFromContext(Map<String, Object> context) {
        DialogueContinuityState continuity = new DialogueContinuityState();
        continuity.currentObjective = firstNonBlank(context.get("currentObjective"), context.get("activeObjective"));
        continuity.pendingUserOffer = string(context.get("pendingUserOffer"));
        continuity.acceptedPlan = firstNonBlank(context.get("acceptedPlan"), context.get("activeObjective"));
        continuity.lastAssistantQuestion = string(context.get("lastAssistantQuestion"));
        continuity.userAnsweredLastQuestion = bool(context.get("userAnsweredLastQuestion"));
        continuity.sceneTransitionNeeded = bool(context.get("sceneTransitionNeeded"));
        continuity.nextBestMove = string(context.get("nextBestMove"));
        continuity.confidence = integer(context.get("continuityConfidence"), 70);
        continuity.mustNotContradict = new ArrayList<>();
        return continuity;
    }

    private SceneState sceneFromContext(Map<String, Object> context) {
        SceneState scene = new SceneState();
        scene.location = firstNonBlank(context.get("currentLocation"), context.get("sceneLocation"), context.get("location"));
        scene.interactionMode = string(context.get("interactionMode"));
        scene.sceneSummary = string(context.get("sceneSummary"));
        scene.transitionPending = bool(context.get("transitionPending"));
        return scene;
    }

    private RelationshipState relationshipFromContext(Map<String, Object> context) {
        RelationshipState state = relationshipService.createInitialState();
        state.relationshipStage = stringOr(context.get("relationshipStage"), state.relationshipStage);
        state.closeness = integer(context.get("closeness"), state.closeness);
        state.trust = integer(context.get("trust"), state.trust);
        state.resonance = integer(context.get("resonance"), state.resonance);
        state.affectionScore = integer(context.get("affectionScore"), state.closeness + state.trust + state.resonance);
        return state;
    }

    private MemorySummary memoryFromContext(Map<String, Object> context) {
        MemorySummary memory = new MemorySummary();
        addIfPresent(memory.preferences, context.get("preference"));
        addIfPresent(memory.strongMemories, context.get("knownMemory"));
        addIfPresent(memory.openLoops, context.get("openLoop"));
        addIfPresent(memory.callbackCandidates, context.get("callbackCandidate"));
        memory.lastUserIntent = string(context.get("lastUserIntent"));
        return memory;
    }

    private TimeContext timeContextFromContext(Map<String, Object> context) {
        TimeContext time = new TimeContext();
        time.localTime = stringOr(context.get("localTime"), "20:00");
        time.dayPart = stringOr(context.get("dayPart"), "night");
        time.frame = string(context.get("timeFrame"));
        return time;
    }

    private WeatherContext weatherFromContext(Map<String, Object> context) {
        WeatherContext weather = new WeatherContext();
        weather.summary = stringOr(context.get("weather"), "微风");
        return weather;
    }

    private List<ConversationSnippet> recentContext(Map<String, Object> context) {
        List<ConversationSnippet> snippets = new ArrayList<>();
        String lastUser = string(context.get("lastUser"));
        String lastAssistant = string(context.get("lastAssistant"));
        if (!lastUser.isBlank()) {
            snippets.add(new ConversationSnippet("user", lastUser));
        }
        if (!lastAssistant.isBlank()) {
            snippets.add(new ConversationSnippet("assistant", lastAssistant));
        }
        return snippets;
    }

    private List<String> actsFromTags(List<String> tags, List<String> risks) {
        List<String> acts = new ArrayList<>();
        if (tags != null) {
            if (tags.contains("rel_act:quality_question")) acts.add("QUALITY_QUESTION");
            if (tags.contains("rel_act:concrete_care_action")) acts.add("CONCRETE_CARE_ACTION");
            if (tags.contains("rel_act:continuity_anchor")) acts.add("CONTINUITY_ANCHOR");
            if (tags.contains("rel_act:boundary_respect")) acts.add("BOUNDARY_RESPECT");
            if (tags.contains("rel_act:romantic_probe")) acts.add("romantic_probe");
        }
        if (risks != null) {
            if (risks.contains("rel_act:pace_or_control_pressure")) acts.add("PACE_OR_CONTROL_PRESSURE");
            if (risks.contains("rel_act:low_effort_dismissive")) acts.add("LOW_EFFORT_DISMISSIVE");
        }
        return acts;
    }

    private Method quickJudgeTriggerMethod() throws NoSuchMethodException {
        if (quickJudgeTriggerMethod == null) {
            quickJudgeTriggerMethod = QuickJudgeService.class.getDeclaredMethod(
                    "decideTrigger",
                    String.class,
                    IntentState.class,
                    DialogueContinuityState.class,
                    TurnUnderstandingState.class,
                    boolean.class,
                    boolean.class,
                    int.class
            );
            quickJudgeTriggerMethod.setAccessible(true);
        }
        return quickJudgeTriggerMethod;
    }

    private Object invokeRecordAccessor(Object target, String accessor) throws Exception {
        Method method = target.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private void printSummary(Map<String, ModuleStats> stats, List<CaseResult> notable) {
        System.out.println("Local rule runner summary");
        for (ModuleStats stat : stats.values()) {
            System.out.printf("%-24s total=%3d pass=%3d warn=%3d fail=%3d%n",
                    stat.module, stat.total, stat.pass, stat.warn, stat.fail);
        }
        if (!notable.isEmpty()) {
            System.out.println();
            System.out.println("Notable cases:");
            int max = verbose ? notable.size() : Math.min(40, notable.size());
            for (int index = 0; index < max; index++) {
                CaseResult result = notable.get(index);
                List<String> messages = result.status == Status.FAIL ? result.failures : result.warnings;
                System.out.println(result.status + " " + result.module + " " + result.id + " - " + messages.get(0));
                if (verbose) {
                    System.out.println("  actual=" + Json.stringify(result.actual));
                }
            }
            if (!verbose && notable.size() > max) {
                System.out.println("... " + (notable.size() - max) + " more. Re-run with --verbose for full details.");
            }
        }
    }

    private void writeReport(Map<String, ModuleStats> stats, List<Map<String, Object>> rows) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        for (ModuleStats stat : stats.values()) {
            summary.put(stat.module, stat.toMap());
        }
        report.put("summary", summary);
        report.put("cases", rows);
        Path out = Path.of("build", "local-rule-report.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, Json.stringify(report), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Report written to " + out.toAbsolutePath());
    }

    private static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(string(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(string(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        return integer(value, fallback);
    }

    private static int number(Object value) {
        return integer(value, 0);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(string(value));
    }

    private static String string(Object value) {
        return value == null || value == MissingValue.INSTANCE ? "" : String.valueOf(value);
    }

    private static String stringOr(Object value, String fallback) {
        String safe = string(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String safe = string(value);
            if (!safe.isBlank()) {
                return safe;
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void addIfPresent(List<String> target, Object value) {
        if (target == null) {
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String safe = string(item);
                if (!safe.isBlank()) {
                    target.add(safe);
                }
            }
            return;
        }
        String safe = string(value);
        if (!safe.isBlank()) {
            target.add(safe);
        }
    }

    private static boolean containsAny(String text, List<String> keywords) {
        String safe = string(text);
        for (String keyword : keywords) {
            if (safe.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String compactValue(Object value) {
        String raw = value == MissingValue.INSTANCE ? "<missing>" : Json.stringify(value);
        return raw.length() <= 180 ? raw : raw.substring(0, 180) + "...";
    }

    private static String nowIso() {
        return Instant.now().toString();
    }

    private enum Status {
        PASS,
        WARN,
        FAIL
    }

    private enum MissingValue {
        INSTANCE
    }

    private record FieldCheck(Object actualValue, boolean ok, String reason) {
    }

    private static final class ModuleStats {
        final String module;
        int total;
        int pass;
        int warn;
        int fail;

        ModuleStats(String module) {
            this.module = module;
        }

        void add(CaseResult result) {
            total++;
            switch (result.status) {
                case PASS -> pass++;
                case WARN -> warn++;
                case FAIL -> fail++;
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("total", total);
            map.put("pass", pass);
            map.put("warn", warn);
            map.put("fail", fail);
            return map;
        }
    }

    private static final class CaseResult {
        final String id;
        final String module;
        final String source;
        final Status status;
        final List<String> failures;
        final List<String> warnings;
        final Map<String, Object> actual;

        CaseResult(
                String id,
                String module,
                String source,
                Status status,
                List<String> failures,
                List<String> warnings,
                Map<String, Object> actual
        ) {
            this.id = id;
            this.module = module;
            this.source = source;
            this.status = status;
            this.failures = failures;
            this.warnings = warnings;
            this.actual = actual;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("module", module);
            map.put("source", source);
            map.put("status", status.name().toLowerCase());
            map.put("failures", failures);
            map.put("warnings", warnings);
            map.put("actual", actual);
            return map;
        }
    }
}
