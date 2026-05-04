package com.campuspulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExportReplayTool {
    private ExportReplayTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException("Usage: ExportReplayTool <session-debug-export.json> [--remote]");
        }
        boolean remote = hasFlag(args, "--remote");
        long settleMs = longOption(args, "--settle-ms=", remote ? 4000L : 0L);
        Path exportPath = Path.of(args[0]).toAbsolutePath().normalize();
        Map<String, Object> original = Json.asObject(Json.parse(Files.readString(exportPath)));
        String originalSessionId = Json.asString(original.get("sessionId"));
        String agentId = blankTo(Json.asString(original.get("agentId")), "healing");
        List<String> userMessages = extractUserMessages(original);
        Instant originalStart = firstMessageInstant(original);
        Instant replayStart = Instant.now();
        Map<Integer, List<Map<String, Object>>> originalHeartbeatsByTurn = originalHeartbeatsByTurn(original);

        Path root = Files.createTempDirectory("campus-pulse-export-replay");
        AppConfig config = replayConfig(root, remote);
        ChatOrchestrator orchestrator = new ChatOrchestrator(
                new StateRepository(config.stateFile),
                new AgentConfigService(),
                new EnhancedSocialMemoryService(config.memoryRetentionMs),
                new NarrativeRelationshipService(),
                new EventEngine(),
                new ExpressiveLlmClient(config),
                new AdaptiveSafetyService(),
                new AnalyticsService(),
                new QuickJudgeService(config),
                new RelationshipCalibrationService(config),
                new PlotDirectorAgentService(config),
                new DynamicStoryEventService(config)
        );

        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession(Json.asString(visitor.get("visitorId")), agentId);
        String replaySessionId = Json.asString(session.get("sessionId"));
        List<Map<String, Object>> replayTurns = new ArrayList<>();
        for (int index = 0; index < userMessages.size(); index++) {
            String userMessage = userMessages.get(index);
            Map<String, Object> sendPayload = new LinkedHashMap<>();
            sendPayload.put("visitorId", visitor.get("visitorId"));
            sendPayload.put("sessionId", replaySessionId);
            sendPayload.put("agentId", agentId);
            sendPayload.put("userMessage", userMessage);
            sendPayload.put("plotPressureMode", replayPlotPressureMode(original));
            if (remote) {
                sendPayload.put("quickJudgeMode", "always");
                sendPayload.put("quickJudgeWaitSeconds", 1.5);
            }
            long sendStartedAtNanos = System.nanoTime();
            Map<String, Object> reply = orchestrator.sendMessage(sendPayload);
            long sendElapsedMs = elapsedMs(sendStartedAtNanos);
            if (settleMs > 0) {
                Thread.sleep(settleMs);
            }
            Map<String, Object> snapshot = orchestrator.exportSessionDebugData(replaySessionId);
            Map<String, Object> turnReport = turnReport(index + 1, userMessage, reply, snapshot, sendElapsedMs);
            List<Map<String, Object>> heartbeatReports = new ArrayList<>();
            for (Map<String, Object> originalHeartbeat : originalHeartbeatsByTurn.getOrDefault(index + 1, List.of())) {
                Instant originalHeartbeatAt = Instant.parse(Json.asString(originalHeartbeat.get("createdAt")));
                String simulatedClientTime = replayStart.plus(Duration.between(originalStart, originalHeartbeatAt)).toString();
                long heartbeatStartedAtNanos = System.nanoTime();
                Map<String, Object> heartbeat = orchestrator.updatePresence(Map.of(
                        "visitorId", visitor.get("visitorId"),
                        "sessionId", replaySessionId,
                        "visible", true,
                        "focused", true,
                        "clientTime", simulatedClientTime,
                        "plotPressureMode", replayPlotPressureMode(original)
                ));
                heartbeatReports.add(heartbeatReport(originalHeartbeat, heartbeat, elapsedMs(heartbeatStartedAtNanos)));
            }
            if (!heartbeatReports.isEmpty()) {
                turnReport.put("heartbeatReplays", heartbeatReports);
            }
            replayTurns.add(turnReport);
        }

        Map<String, Object> replaySnapshot = orchestrator.exportSessionDebugData(replaySessionId);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sourceFile", exportPath.toString());
        report.put("remoteEnabled", remote);
        report.put("settleMs", settleMs);
        report.put("llmModel", config.llmModel);
        report.put("plotLlmModel", config.plotLlmModel);
        report.put("plotLlmBaseUrl", config.plotLlmBaseUrl);
        report.put("originalSessionId", originalSessionId);
        report.put("replaySessionId", replaySessionId);
        report.put("agentId", agentId);
        report.put("userMessages", userMessages);
        report.put("originalSummary", original.get("summary"));
        report.put("originalMultiReplyTurns", originalMultiReplyTurns(original));
        report.put("replaySummary", replaySnapshot.get("summary"));
        report.put("replayTurns", replayTurns);
        report.put("timingSummary", timingSummary(replayTurns));

        Path outputDir = Path.of("build").resolve("debug-replay");
        Files.createDirectories(outputDir);
        String modeSuffix = remote ? "-remote" : "-local";
        Path output = outputDir.resolve(blankTo(originalSessionId, "session") + modeSuffix + "-replay-report.json");
        Files.writeString(output, Json.stringify(report));

        printSummary(report, output);
    }

    private static AppConfig replayConfig(Path root, boolean remote) {
        if (!remote) {
            return new AppConfig(
                    0,
                    root,
                    root.resolve("public"),
                    root.resolve("runtime").resolve("state.bin"),
                    7L * 24 * 60 * 60 * 1000,
                    "",
                    "",
                    "mock",
                    Duration.ofSeconds(5)
            );
        }
        AppConfig loaded = AppConfig.load();
        return new AppConfig(
                0,
                root,
                root.resolve("public"),
                root.resolve("runtime").resolve("state.bin"),
                loaded.memoryRetentionMs,
                loaded.llmBaseUrl,
                loaded.llmApiKey,
                loaded.llmModel,
                loaded.llmTimeout,
                loaded.plotLlmBaseUrl,
                loaded.plotLlmApiKey,
                loaded.plotLlmModel,
                loaded.plotLlmTimeout
        );
    }

    private static Instant firstMessageInstant(Map<String, Object> export) {
        Instant first = null;
        for (Object turnValue : Json.asArray(export.get("turnTimeline"))) {
            Map<String, Object> turn = Json.asObject(turnValue);
            Object userValue = mapOrEmpty(turn.get("userMessage")).get("createdAt");
            first = earlier(first, userValue);
            for (Object replyValue : Json.asArray(turn.get("assistantReplies"))) {
                first = earlier(first, Json.asObject(replyValue).get("createdAt"));
            }
        }
        return first == null ? Instant.now() : first;
    }

    private static Instant earlier(Instant current, Object isoValue) {
        String iso = Json.asString(isoValue);
        if (iso.isBlank()) {
            return current;
        }
        Instant parsed = Instant.parse(iso);
        return current == null || parsed.isBefore(current) ? parsed : current;
    }

    private static Map<Integer, List<Map<String, Object>>> originalHeartbeatsByTurn(Map<String, Object> export) {
        Map<Integer, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Object turnValue : Json.asArray(export.get("turnTimeline"))) {
            Map<String, Object> turn = Json.asObject(turnValue);
            int turnIndex = Json.asInt(turn.get("turnIndex"), -1);
            for (Object replyValue : Json.asArray(turn.get("assistantReplies"))) {
                Map<String, Object> reply = Json.asObject(replyValue);
                String source = Json.asString(reply.get("replySource"));
                if ("silence_heartbeat".equals(source) || "long_chat_heartbeat".equals(source)) {
                    result.computeIfAbsent(turnIndex, ignored -> new ArrayList<>()).add(reply);
                }
            }
        }
        return result;
    }

    private static String replayPlotPressureMode(Map<String, Object> export) {
        Map<String, Object> summary = mapOrEmpty(export.get("summary"));
        String mode = Json.asString(summary.get("plotPressureMode")).trim().toLowerCase();
        return "strict".equals(mode) ? "strict" : "relaxed";
    }

    private static List<String> extractUserMessages(Map<String, Object> export) {
        List<String> messages = new ArrayList<>();
        for (Object turnValue : Json.asArray(export.get("turnTimeline"))) {
            Map<String, Object> turn = Json.asObject(turnValue);
            Object userValue = turn.get("userMessage");
            if (!(userValue instanceof Map<?, ?>)) {
                continue;
            }
            String text = Json.asString(Json.asObject(userValue).get("text")).trim();
            if (!text.isBlank()) {
                messages.add(text);
            }
        }
        return messages;
    }

    private static Map<String, Object> turnReport(
            int turnIndex,
            String userMessage,
            Map<String, Object> reply,
            Map<String, Object> snapshot,
            long sendElapsedMs
    ) {
        Map<String, Object> latestSignals = Json.asObject(snapshot.get("latestSignals"));
        Map<String, Object> sessionPayload = mapOrEmpty(snapshot.get("session"));
        Map<String, Object> turnContext = mapOrEmpty(latestSignals.get("turnContext"));
        Map<String, Object> responseQuickJudge = mapOrEmpty(reply.get("quick_judge_status"));
        Map<String, Object> snapshotQuickJudge = mapOrEmpty(latestSignals.get("quickJudge"));
        Map<String, Object> scene = mapOrEmpty(latestSignals.get("scene"));
        Map<String, Object> plotArcState = mapOrEmpty(latestSignals.get("plotArcState"));
        Map<String, Object> latestAssistant = latestAssistantReply(snapshot);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("turnIndex", turnIndex);
        report.put("userMessage", userMessage);
        report.put("replyText", reply.get("reply_text"));
        report.put("sendElapsedMs", sendElapsedMs);
        report.put("replySource", reply.get("reply_source"));
        report.put("sceneText", reply.get("scene_text"));
        report.put("sceneLocation", scene.get("location"));
        report.put("sceneSummary", scene.get("sceneSummary"));
        report.put("plotAction", turnContext.get("plotDirectorAction"));
        report.put("plotSignal", turnContext.get("plotSignal"));
        report.put("plotGap", turnContext.get("plotGap"));
        report.put("plotDecisionSignal", turnContext.get("plotDecisionSignal"));
        report.put("plotDecisionGap", turnContext.get("plotDecisionGap"));
        report.put("plotDecisionPressure", turnContext.get("plotDecisionPressure"));
        report.put("plotPressureBuckets", Map.of(
                "relationship", turnContext.get("plotRelationshipPressure"),
                "scene", turnContext.get("plotScenePressure"),
                "openLoop", turnContext.get("plotOpenLoopPressure"),
                "event", turnContext.get("plotEventPressure"),
                "silence", turnContext.get("plotSilencePressure")
        ));
        report.put("plotDirectorDecision", reply.get("plot_director_decision"));
        report.put("plotDirectorInput", reply.get("plot_director_input"));
        report.put("plotProgress", plotArcState.get("plotProgress"));
        report.put("sceneMoveKind", turnContext.get("sceneMoveKind"));
        report.put("sceneMoveTarget", turnContext.get("sceneMoveTarget"));
        report.put("turnMission", turnContext.get("turnMission"));
        report.put("turnMissionReason", turnContext.get("turnMissionReason"));
        report.put("turnMissionPriority", turnContext.get("turnMissionPriority"));
        report.put("turnMissionCandidates", turnContext.get("turnMissionCandidates"));
        report.put("localGuards", turnContext.get("localGuards"));
        report.put("userReplyAct", turnContext.get("userReplyAct"));
        report.put("referentialFollowup", turnContext.get("referentialFollowup"));
        report.put("referentSource", turnContext.get("referentSource"));
        report.put("referentAnchor", turnContext.get("referentAnchor"));
        report.put("referentReason", turnContext.get("referentReason"));
        report.put("quickJudgeStatus", responseQuickJudge.get("status"));
        report.put("quickJudgeReason", responseQuickJudge.get("reason"));
        report.put("quickJudgeApplied", responseQuickJudge.get("applied"));
        report.put("quickJudgeConfidence", responseQuickJudge.get("confidence"));
        report.put("quickJudgeNextBestMove", responseQuickJudge.get("nextBestMove"));
        report.put("snapshotQuickJudgeStatus", snapshotQuickJudge.get("status"));
        report.put("snapshotQuickJudgeReason", snapshotQuickJudge.get("reason"));
        report.put("snapshotQuickJudgeApplied", snapshotQuickJudge.get("applied"));
        report.put("quickJudgeTriggerTier", mapOrEmpty(reply.get("agent_timing")).get("quickJudgeTriggerTier"));
        report.put("agentTiming", reply.get("agent_timing"));
        report.put("dynamicStoryEvents", sessionPayload.get("dynamicStoryEvents"));
        report.put("pendingQuickJudgeCorrection", snapshot.get("pendingQuickJudgeCorrection"));
        report.put("pendingQuickJudgeSourceTurn", snapshot.get("pendingQuickJudgeSourceTurn"));
        report.put("triggeredEvent", reply.get("triggered_event"));
        report.put("promptSlotSummary", latestAssistant.get("promptSlotSummary"));
        return report;
    }

    private static Map<String, Object> heartbeatReport(Map<String, Object> originalHeartbeat, Map<String, Object> replayHeartbeat, long heartbeatElapsedMs) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("originalCreatedAt", originalHeartbeat.get("createdAt"));
        report.put("originalReplySource", originalHeartbeat.get("replySource"));
        report.put("originalText", originalHeartbeat.get("text"));
        report.put("heartbeatElapsedMs", heartbeatElapsedMs);
        report.put("replayTriggered", replayHeartbeat.get("proactive_message") != null);
        report.put("replayReplySource", replayHeartbeat.get("reply_source"));
        report.put("replayText", replayHeartbeat.get("proactive_message"));
        report.put("heartbeatExplain", replayHeartbeat.get("heartbeat_explain"));
        report.put("blockedReason", replayHeartbeat.get("blocked_reason"));
        return report;
    }

    private static List<Map<String, Object>> originalMultiReplyTurns(Map<String, Object> export) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object turnValue : Json.asArray(export.get("turnTimeline"))) {
            Map<String, Object> turn = Json.asObject(turnValue);
            List<Object> replies = Json.asArray(turn.get("assistantReplies"));
            if (replies.size() <= 1) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("turnIndex", turn.get("turnIndex"));
            entry.put("userMessage", Json.asString(mapOrEmpty(turn.get("userMessage")).get("text")));
            List<Map<String, Object>> replyReports = new ArrayList<>();
            for (Object replyValue : replies) {
                Map<String, Object> reply = Json.asObject(replyValue);
                Map<String, Object> replyReport = new LinkedHashMap<>();
                replyReport.put("createdAt", reply.get("createdAt"));
                replyReport.put("replySource", reply.get("replySource"));
                replyReport.put("text", reply.get("text"));
                replyReports.add(replyReport);
            }
            entry.put("assistantReplies", replyReports);
            result.add(entry);
        }
        return result;
    }

    private static Map<String, Object> latestAssistantReply(Map<String, Object> snapshot) {
        List<Object> timeline = Json.asArray(snapshot.get("turnTimeline"));
        for (int turnIndex = timeline.size() - 1; turnIndex >= 0; turnIndex--) {
            Map<String, Object> turn = Json.asObject(timeline.get(turnIndex));
            List<Object> replies = Json.asArray(turn.get("assistantReplies"));
            if (!replies.isEmpty()) {
                return Json.asObject(replies.get(replies.size() - 1));
            }
        }
        return Map.of();
    }

    private static Map<String, Object> mapOrEmpty(Object value) {
        if (value instanceof Map<?, ?>) {
            return Json.asObject(value);
        }
        return Map.of();
    }

    private static void printSummary(Map<String, Object> report, Path output) {
        System.out.println("Export replay report written: " + output.toAbsolutePath());
        System.out.println("remoteEnabled=" + report.get("remoteEnabled"));
        System.out.println("settleMs=" + report.get("settleMs"));
        System.out.println("llmModel=" + report.get("llmModel"));
        System.out.println("plotLlmModel=" + report.get("plotLlmModel"));
        System.out.println("plotLlmBaseUrl=" + report.get("plotLlmBaseUrl"));
        System.out.println("agentId=" + report.get("agentId"));
        System.out.println("originalSummary=" + Json.stringify(report.get("originalSummary")));
        System.out.println("replaySummary=" + Json.stringify(report.get("replaySummary")));
        System.out.println("timingSummary=" + Json.stringify(report.get("timingSummary")));
        System.out.println("originalMultiReplyTurns=" + Json.stringify(report.get("originalMultiReplyTurns")));
        List<Object> turns = Json.asArray(report.get("replayTurns"));
        for (Object turnValue : turns) {
            Map<String, Object> turn = Json.asObject(turnValue);
            Map<String, Object> timing = mapOrEmpty(turn.get("agentTiming"));
            System.out.println("#" + turn.get("turnIndex")
                    + " user=" + turn.get("userMessage")
                    + " | sendMs=" + turn.get("sendElapsedMs")
                    + " | mainDoneMs=" + timing.get("mainReplyFinishedMs")
                    + " | reply=" + turn.get("replyText")
                    + " | plot=" + turn.get("plotAction") + "/" + turn.get("plotSignal")
                    + " | decision=" + turn.get("plotDecisionSignal") + "/" + turn.get("plotDecisionGap")
                    + " | mission=" + turn.get("turnMission")
                    + " | guards=" + turn.get("localGuards")
                    + " | sceneMove=" + turn.get("sceneMoveKind")
                    + " | qj=" + turn.get("quickJudgeStatus") + ":" + turn.get("quickJudgeReason")
                    + " | qjTier=" + turn.get("quickJudgeTriggerTier")
                    + " | dynamicEvents=" + dynamicEventCount(turn.get("dynamicStoryEvents")));
            if (turn.get("heartbeatReplays") != null) {
                System.out.println("   heartbeatReplays=" + Json.stringify(turn.get("heartbeatReplays")));
            }
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    private static int dynamicEventCount(Object value) {
        return value instanceof List<?> ? Json.asArray(value).size() : 0;
    }

    private static Map<String, Object> timingSummary(List<Map<String, Object>> turns) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Long> values = new ArrayList<>();
        for (Map<String, Object> turn : turns) {
            Object value = turn.get("sendElapsedMs");
            if (value instanceof Number number) {
                values.add(number.longValue());
            }
        }
        values.sort(Long::compareTo);
        long total = 0L;
        for (Long value : values) {
            total += value;
        }
        summary.put("turnCount", values.size());
        summary.put("totalSendMs", total);
        summary.put("avgSendMs", values.isEmpty() ? 0L : Math.round(total / (double) values.size()));
        summary.put("minSendMs", values.isEmpty() ? 0L : values.get(0));
        summary.put("p50SendMs", percentile(values, 0.50));
        summary.put("p90SendMs", percentile(values, 0.90));
        summary.put("maxSendMs", values.isEmpty() ? 0L : values.get(values.size() - 1));
        return summary;
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(values.size() * percentile) - 1;
        index = Math.max(0, Math.min(values.size() - 1, index));
        return values.get(index);
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.round((System.nanoTime() - startedAtNanos) / 1_000_000.0);
    }

    private static long longOption(String[] args, String prefix, long fallback) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                try {
                    return Long.parseLong(arg.substring(prefix.length()));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }


    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
