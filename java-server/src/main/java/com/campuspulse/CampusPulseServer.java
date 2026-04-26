package com.campuspulse;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CampusPulseServer {
    private final AppConfig config;
    private final ChatOrchestrator chatOrchestrator;

    public CampusPulseServer(AppConfig config) {
        this.config = config;
        StateRepository repository = new StateRepository(config.stateFile);
        AgentConfigService agentConfigService = new AgentConfigService();
        MemoryService memoryService = new EnhancedSocialMemoryService(config.memoryRetentionMs);
        RelationshipService relationshipService = new NarrativeRelationshipService();
        EventEngine eventEngine = new EventEngine();
        SafetyService safetyService = new AdaptiveSafetyService();
        AnalyticsService analyticsService = new AnalyticsService();
        CompositeLlmClient llmClient = new ExpressiveLlmClient(config);
        this.chatOrchestrator = new ChatOrchestrator(
                repository,
                agentConfigService,
                memoryService,
                relationshipService,
                eventEngine,
                llmClient,
                safetyService,
                analyticsService,
                new QuickJudgeService(config),
                new RelationshipCalibrationService(config),
                new PlotDirectorAgentService(config)
        );
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        new CampusPulseServer(config).start();
    }

    public void start() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(config.port));
            System.out.println("Campus Pulse Java server is running at http://localhost:" + config.port);
            while (true) {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handleSocket(socket));
            }
        } finally {
            executor.shutdown();
        }
    }

    private void handleSocket(Socket socket) {
        try (socket; InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream()) {
            HttpRequestData request = readRequest(input);
            if (request == null) {
                return;
            }

            HttpResponseData response;
            try {
                if (request.path.startsWith("/api/")) {
                    response = handleApi(request);
                } else {
                    response = serveStatic(request.path);
                }
            } catch (ApiException error) {
                response = jsonResponse(error.statusCode, Map.of(
                        "ok", false,
                        "error", Map.of(
                                "code", error.code,
                                "message", error.getMessage()
                        )
                ));
            } catch (Exception error) {
                response = jsonResponse(500, Map.of(
                        "ok", false,
                        "error", Map.of(
                                "code", "INTERNAL_SERVER_ERROR",
                                "message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()
                        )
                ));
            }

            writeResponse(output, response);
        } catch (Exception ignored) {
            // Broken client connections can be ignored.
        }
    }

    private HttpResponseData handleApi(HttpRequestData request) throws Exception {
        if ("GET".equals(request.method) && "/api/health".equals(request.path)) {
            return jsonResponse(200, Map.of("ok", true, "data", Map.of("status", "up", "timestamp", Instant.now().toString())));
        }

        if ("GET".equals(request.method) && "/api/agents".equals(request.path)) {
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.listAgents()));
        }

        if ("POST".equals(request.method) && "/api/visitor/init".equals(request.path)) {
            Map<String, Object> body = readJsonBody(request);
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.initVisitor(Json.asString(body.get("visitor_id")))));
        }

        if ("POST".equals(request.method) && "/api/session/start".equals(request.path)) {
            Map<String, Object> body = readJsonBody(request);
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.startSession(
                    Json.asString(body.get("visitor_id")),
                    Json.asString(body.get("agent_id"))
            )));
        }

        if ("POST".equals(request.method) && "/api/visitor/context".equals(request.path)) {
            Map<String, Object> body = readJsonBody(request);
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.updateVisitorContext(Map.of(
                    "visitorId", Json.asString(body.get("visitor_id")),
                    "timezone", Json.asString(body.get("timezone")),
                    "preferredCity", Json.asString(body.get("preferred_city"))
            ))));
        }

        if ("POST".equals(request.method) && "/api/chat/send".equals(request.path)) {
            Map<String, Object> body = readJsonBody(request);
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.sendMessage(Map.of(
                    "visitorId", Json.asString(body.get("visitor_id")),
                    "sessionId", Json.asString(body.get("session_id")),
                    "agentId", Json.asString(body.get("agent_id")),
                    "userMessage", Json.asString(body.get("user_message")),
                    "quickJudgeMode", Json.asString(body.get("quick_judge_mode")),
                    "quickJudgeEnabled", body.containsKey("quick_judge_enabled") ? Json.asBoolean(body.get("quick_judge_enabled")) : true,
                    "quickJudgeForceAll", body.containsKey("quick_judge_force_all") && Json.asBoolean(body.get("quick_judge_force_all")),
                    "quickJudgeWaitSeconds", body.containsKey("quick_judge_wait_seconds") ? body.get("quick_judge_wait_seconds") : ""
            ))));
        }

        if ("POST".equals(request.method) && "/api/session/presence".equals(request.path)) {
            Map<String, Object> body = readJsonBody(request);
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.updatePresence(Map.of(
                    "visitorId", Json.asString(body.get("visitor_id")),
                    "sessionId", Json.asString(body.get("session_id")),
                    "visible", body.get("visible") instanceof Boolean bool && bool,
                    "focused", body.get("focused") instanceof Boolean bool && bool,
                    "isTyping", body.get("is_typing") instanceof Boolean bool && bool,
                    "draftLength", Json.asInt(body.get("draft_length"), 0),
                    "lastInputAt", Json.asString(body.get("last_input_at")),
                    "clientTime", Json.asString(body.get("client_time"))
            ))));
        }

        if ("POST".equals(request.method) && "/api/session/checkpoint".equals(request.path)) {
            Map<String, Object> body = readJsonBody(request);
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.continueCheckpoint(Map.of(
                    "visitorId", Json.asString(body.get("visitor_id")),
                    "sessionId", Json.asString(body.get("session_id"))
            ))));
        }

        if ("POST".equals(request.method) && "/api/session/settle".equals(request.path)) {
            Map<String, Object> body = readJsonBody(request);
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.settleCheckpoint(Map.of(
                    "visitorId", Json.asString(body.get("visitor_id")),
                    "sessionId", Json.asString(body.get("session_id"))
            ))));
        }

        if ("POST".equals(request.method) && "/api/event/choose".equals(request.path)) {
            Map<String, Object> body = readJsonBody(request);
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.chooseEvent(Map.of(
                    "visitorId", Json.asString(body.get("visitor_id")),
                    "sessionId", Json.asString(body.get("session_id")),
                    "choiceId", Json.asString(body.get("choice_id"))
            ))));
        }

        if ("GET".equals(request.method) && "/api/session/state".equals(request.path)) {
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.getSessionState(request.query.get("session_id"))));
        }

        if ("GET".equals(request.method) && "/api/session/export".equals(request.path)) {
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.exportSessionDebugData(request.query.get("session_id"))));
        }

        if ("GET".equals(request.method) && "/api/analytics/overview".equals(request.path)) {
            return jsonResponse(200, Map.of("ok", true, "data", chatOrchestrator.getAnalyticsOverview()));
        }

        throw ApiException.notFound("NOT_FOUND", "NOT_FOUND");
    }

    private HttpResponseData serveStatic(String requestPath) throws IOException {
        String normalized = (requestPath == null || "/".equals(requestPath)) ? "/index.html" : requestPath;
        if (normalized.contains("..")) {
            normalized = "/index.html";
        }

        Path file = config.publicDir.resolve(normalized.substring(1)).normalize();
        if (!file.startsWith(config.publicDir) || !Files.exists(file) || Files.isDirectory(file)) {
            file = config.publicDir.resolve("index.html");
        }

        return new HttpResponseData(200, contentType(file), Files.readAllBytes(file));
    }

    private Map<String, Object> readJsonBody(HttpRequestData request) {
        if (request.body == null || request.body.isBlank()) {
            return new HashMap<>();
        }
        return Json.asObject(Json.parse(request.body));
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> query = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return query;
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            query.put(key, value);
        }
        return query;
    }

    private HttpRequestData readRequest(InputStream rawInput) throws IOException {
        BufferedInputStream input = new BufferedInputStream(rawInput);
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.isBlank()) {
            return null;
        }

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new IOException("Invalid HTTP request line");
        }

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                headers.put(line.substring(0, separator).trim().toLowerCase(), line.substring(separator + 1).trim());
            }
        }

        int contentLength = 0;
        if (headers.containsKey("content-length")) {
            contentLength = Integer.parseInt(headers.get("content-length"));
        }
        byte[] bodyBytes = input.readNBytes(contentLength);

        URI uri = URI.create(parts[1]);
        HttpRequestData request = new HttpRequestData();
        request.method = parts[0];
        request.path = uri.getPath();
        request.query = parseQuery(uri);
        request.body = new String(bodyBytes, StandardCharsets.UTF_8);
        return request;
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int current;
        boolean carriage = false;
        while ((current = input.read()) != -1) {
            if (current == '\r') {
                carriage = true;
                continue;
            }
            if (current == '\n') {
                break;
            }
            if (carriage) {
                buffer.write('\r');
                carriage = false;
            }
            buffer.write(current);
        }
        if (current == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private HttpResponseData jsonResponse(int statusCode, Map<String, Object> payload) {
        return new HttpResponseData(statusCode, "application/json; charset=utf-8", Json.stringify(payload).getBytes(StandardCharsets.UTF_8));
    }

    private void writeResponse(OutputStream output, HttpResponseData response) throws IOException {
        String statusText = switch (response.statusCode) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 410 -> "Gone";
            case 500 -> "Internal Server Error";
            default -> "OK";
        };
        String headers = "HTTP/1.1 " + response.statusCode + " " + statusText + "\r\n"
                + "Content-Type: " + response.contentType + "\r\n"
                + "Content-Length: " + response.body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(response.body);
        output.flush();
    }

    private String contentType(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "text/plain; charset=utf-8";
    }

    private static final class HttpRequestData {
        String method;
        String path;
        Map<String, String> query;
        String body;
    }

    private static final class HttpResponseData {
        final int statusCode;
        final String contentType;
        final byte[] body;

        private HttpResponseData(int statusCode, String contentType, byte[] body) {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
