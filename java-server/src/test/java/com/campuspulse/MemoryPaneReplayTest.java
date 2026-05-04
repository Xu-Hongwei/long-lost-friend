package com.campuspulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MemoryPaneReplayTest {
    private static final Path DEFAULT_CASES = Path.of("testdata", "memory-pane-replay", "cases.jsonl");
    private static final Path DEFAULT_REPORT = Path.of("build", "memory-pane-replay", "report.json");

    private MemoryPaneReplayTest() {
    }

    public static void main(String[] args) throws Exception {
        Path cases = args.length > 0 && args[0] != null && !args[0].isBlank()
                ? Path.of(args[0])
                : DEFAULT_CASES;
        ReplaySuiteResult result = runSuite(cases, DEFAULT_REPORT);
        printSummary(result, DEFAULT_REPORT);
        if (result.fail > 0) {
            throw new IllegalStateException("Memory pane replay failed: " + result.fail + " case(s)");
        }
    }

    static void run() throws Exception {
        ReplaySuiteResult result = runSuite(DEFAULT_CASES, DEFAULT_REPORT);
        if (result.fail > 0) {
            throw new IllegalStateException("Memory pane replay failed: " + result.fail + " case(s); report=" + DEFAULT_REPORT.toAbsolutePath());
        }
    }

    private static ReplaySuiteResult runSuite(Path casesPath, Path reportPath) throws Exception {
        List<Map<String, Object>> reports = new ArrayList<>();
        int pass = 0;
        int fail = 0;
        List<String> lines = Files.readAllLines(casesPath);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> item = Json.asObject(Json.parse(line));
            Map<String, Object> report = runCase(item, index + 1);
            reports.add(report);
            if ("pass".equals(report.get("status"))) {
                pass++;
            } else {
                fail++;
            }
        }
        ReplaySuiteResult result = new ReplaySuiteResult(lines.size(), pass, fail, reports);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("purpose", "memory_pane_prompt_stack_replay");
        payload.put("source", casesPath.toString());
        payload.put("total", result.total);
        payload.put("pass", result.pass);
        payload.put("fail", result.fail);
        payload.put("cases", result.reports);
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, Json.stringify(payload));
        return result;
    }

    private static Map<String, Object> runCase(Map<String, Object> item, int lineNumber) throws Exception {
        String id = blankTo(Json.asString(item.get("id")), "case-" + lineNumber);
        String agentId = blankTo(Json.asString(item.get("agentId")), "healing");
        Map<String, Object> expected = objectOrEmpty(item.get("expect"));
        List<String> failures = new ArrayList<>();

        ChatOrchestrator orchestrator = createOrchestrator(id);
        Map<String, Object> visitor = orchestrator.initVisitor("");
        Map<String, Object> session = orchestrator.startSession(Json.asString(visitor.get("visitorId")), agentId);
        String visitorId = Json.asString(visitor.get("visitorId"));
        String sessionId = Json.asString(session.get("sessionId"));

        Map<String, Object> latestReply = Map.of();
        for (Object turnValue : arrayOrEmpty(item.get("turns"))) {
            String message = Json.asString(turnValue);
            latestReply = orchestrator.sendMessage(Map.of(
                    "visitorId", visitorId,
                    "sessionId", sessionId,
                    "agentId", agentId,
                    "userMessage", message,
                    "quickJudgeMode", "off",
                    "plotPressureMode", "relaxed"
            ));
        }

        Map<String, Object> heartbeat = Map.of();
        int heartbeatAfterSeconds = Json.asInt(item.get("heartbeatAfterSeconds"), 0);
        if (heartbeatAfterSeconds > 0) {
            Map<String, Object> stateBeforeHeartbeat = orchestrator.getSessionState(sessionId);
            Map<String, Object> presence = objectOrEmpty(stateBeforeHeartbeat.get("presenceState"));
            Instant lastUser = parseInstant(Json.asString(presence.get("lastUserMessageAt")));
            heartbeat = orchestrator.updatePresence(Map.of(
                    "visitorId", visitorId,
                    "sessionId", sessionId,
                    "visible", true,
                    "focused", true,
                    "clientTime", lastUser.plusSeconds(heartbeatAfterSeconds).toString(),
                    "plotPressureMode", "relaxed"
            ));
        }

        Map<String, Object> state = orchestrator.getSessionState(sessionId);
        Map<String, Object> memoryPane = objectOrEmpty(state.get("memoryPaneState"));
        Map<String, Object> turnContext = objectOrEmpty(state.get("lastTurnContext"));
        Map<String, Object> continuity = objectOrEmpty(state.get("dialogueContinuityState"));
        Map<String, Object> sceneState = objectOrEmpty(state.get("sceneState"));
        List<Object> promptSlots = arrayOrEmpty(state.get("lastPromptSlotsUsed"));

        assertPromptSlots(expected, promptSlots, failures);
        assertContainsAll("memoryPane", flatten(memoryPane), arrayOrEmpty(expected.get("memoryPaneContains")), failures);
        assertContainsAll("continuity", flatten(continuity), arrayOrEmpty(expected.get("continuityContains")), failures);
        assertContainsAll("turnContext", flatten(turnContext), arrayOrEmpty(expected.get("turnContextContains")), failures);
        assertScene(expected, sceneState, failures);
        assertReply(latestReply, heartbeat, expected, failures);
        assertHeartbeat(heartbeat, expected, failures);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", id);
        report.put("description", Json.asString(item.get("description")));
        report.put("status", failures.isEmpty() ? "pass" : "fail");
        report.put("failures", failures);
        report.put("replyText", latestReply.get("reply_text"));
        report.put("heartbeatReply", heartbeat.get("proactive_message"));
        report.put("replySource", latestReply.get("reply_source"));
        report.put("heartbeatSource", heartbeat.get("reply_source"));
        report.put("scene", sceneState);
        report.put("turnContext", turnContext);
        report.put("dialogueContinuity", continuity);
        report.put("memoryPaneState", memoryPane);
        report.put("promptSlotSummary", state.get("lastPromptSlotSummary"));
        report.put("promptSlots", summarizeSlots(promptSlots));
        return report;
    }

    private static ChatOrchestrator createOrchestrator(String id) throws Exception {
        Path root = Files.createTempDirectory("campus-pulse-memory-pane-replay-" + id.replaceAll("[^a-zA-Z0-9_-]", "-"));
        AppConfig config = new AppConfig(
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
        return new ChatOrchestrator(
                new StateRepository(config.stateFile),
                new AgentConfigService(),
                new SocialMemoryService(config.memoryRetentionMs),
                new NarrativeRelationshipService(),
                new EventEngine(),
                new ExpressiveLlmClient(config),
                new AdaptiveSafetyService(),
                new AnalyticsService()
        );
    }

    private static void assertPromptSlots(Map<String, Object> expected, List<Object> promptSlots, List<String> failures) {
        List<Object> required = arrayOrEmpty(expected.get("promptSlotsIncluded"));
        for (Object value : required) {
            String key = Json.asString(value);
            boolean found = false;
            boolean included = false;
            for (Object slotValue : promptSlots) {
                Map<String, Object> slot = objectOrEmpty(slotValue);
                if (key.equals(Json.asString(slot.get("key")))) {
                    found = true;
                    included = Json.asBoolean(slot.get("included"));
                    break;
                }
            }
            if (!found) {
                failures.add("missing prompt slot: " + key);
            } else if (!included) {
                failures.add("prompt slot was trimmed: " + key);
            }
        }
    }

    private static void assertScene(Map<String, Object> expected, Map<String, Object> scene, List<String> failures) {
        String sceneText = flatten(scene);
        String contains = Json.asString(expected.get("sceneLocationContains"));
        if (!contains.isBlank() && !sceneText.contains(contains)) {
            failures.add("scene should contain " + contains + " but was " + sceneText);
        }
        String notContains = Json.asString(expected.get("sceneLocationNotContains"));
        if (!notContains.isBlank() && sceneText.contains(notContains)) {
            failures.add("scene should not contain " + notContains + " but was " + sceneText);
        }
    }

    private static void assertReply(
            Map<String, Object> latestReply,
            Map<String, Object> heartbeat,
            Map<String, Object> expected,
            List<String> failures
    ) {
        String replyText = visibleReplyText(latestReply) + " " + visibleHeartbeatText(heartbeat);
        for (Object value : arrayOrEmpty(expected.get("replyMustNotContain"))) {
            String keyword = Json.asString(value);
            if (!keyword.isBlank() && replyText.contains(keyword)) {
                failures.add("reply should not contain: " + keyword);
            }
        }
        String sourceContains = Json.asString(expected.get("replySourceContains"));
        if (!sourceContains.isBlank()) {
            String sourceText = Json.asString(latestReply.get("reply_source")) + " " + Json.asString(heartbeat.get("reply_source"));
            if (!sourceText.contains(sourceContains)) {
                failures.add("reply source should contain " + sourceContains + " but was " + sourceText);
            }
        }
    }

    private static String visibleReplyText(Map<String, Object> reply) {
        if (reply == null || reply.isEmpty()) {
            return "";
        }
        return Json.asString(reply.get("reply_text"))
                + " " + Json.asString(reply.get("speech_text"))
                + " " + Json.asString(reply.get("scene_text"));
    }

    private static String visibleHeartbeatText(Map<String, Object> heartbeat) {
        if (heartbeat == null || heartbeat.isEmpty()) {
            return "";
        }
        Object message = heartbeat.get("proactive_message");
        if (message instanceof Map<?, ?>) {
            Map<String, Object> map = Json.asObject(message);
            return Json.asString(map.get("text"))
                    + " " + Json.asString(map.get("speechText"))
                    + " " + Json.asString(map.get("sceneText"));
        }
        return Json.asString(message);
    }

    private static void assertHeartbeat(Map<String, Object> heartbeat, Map<String, Object> expected, List<String> failures) {
        if (!expected.containsKey("heartbeatTriggered")) {
            return;
        }
        boolean shouldTrigger = Json.asBoolean(expected.get("heartbeatTriggered"));
        boolean triggered = heartbeat.get("proactive_message") != null;
        if (shouldTrigger != triggered) {
            failures.add("heartbeatTriggered expected " + shouldTrigger + " but was " + triggered + "; blocked=" + heartbeat.get("blocked_reason"));
        }
    }

    private static void assertContainsAll(String label, String text, List<Object> required, List<String> failures) {
        for (Object value : required) {
            String keyword = Json.asString(value);
            if (!keyword.isBlank() && !text.contains(keyword)) {
                failures.add(label + " should contain " + keyword + " but was " + text);
            }
        }
    }

    private static List<Map<String, Object>> summarizeSlots(List<Object> promptSlots) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object slotValue : promptSlots) {
            Map<String, Object> slot = objectOrEmpty(slotValue);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", Json.asString(slot.get("key")));
            item.put("included", Json.asBoolean(slot.get("included")));
            item.put("priority", Json.asInt(slot.get("priority"), 0));
            item.put("tokenBudget", Json.asInt(slot.get("tokenBudget"), 0));
            result.add(item);
        }
        return result;
    }

    private static String flatten(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder();
            for (Object item : map.values()) {
                if (builder.length() > 0) builder.append(' ');
                builder.append(flatten(item));
            }
            return builder.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder();
            for (Object item : iterable) {
                if (builder.length() > 0) builder.append(' ');
                builder.append(flatten(item));
            }
            return builder.toString();
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> objectOrEmpty(Object value) {
        if (value instanceof Map<?, ?>) {
            return Json.asObject(value);
        }
        return Map.of();
    }

    private static List<Object> arrayOrEmpty(Object value) {
        if (value instanceof List<?>) {
            return Json.asArray(value);
        }
        return List.of();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        return Instant.parse(value);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void printSummary(ReplaySuiteResult result, Path reportPath) {
        System.out.println("Memory pane replay report written: " + reportPath.toAbsolutePath());
        System.out.println("total=" + result.total + " pass=" + result.pass + " fail=" + result.fail);
        for (Map<String, Object> report : result.reports) {
            System.out.println("- " + report.get("status") + " " + report.get("id"));
            for (Object failure : arrayOrEmpty(report.get("failures"))) {
                System.out.println("  * " + failure);
            }
        }
    }

    private record ReplaySuiteResult(int total, int pass, int fail, List<Map<String, Object>> reports) {
    }
}
