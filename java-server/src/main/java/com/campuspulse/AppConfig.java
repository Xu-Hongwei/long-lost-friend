package com.campuspulse;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;

class AppConfig {
    final int port;
    final Path rootDir;
    final Path publicDir;
    final Path stateFile;
    final long memoryRetentionMs;
    final String llmBaseUrl;
    final String llmApiKey;
    final String llmModel;
    final Duration llmTimeout;

    AppConfig(
            int port,
            Path rootDir,
            Path publicDir,
            Path stateFile,
            long memoryRetentionMs,
            String llmBaseUrl,
            String llmApiKey,
            String llmModel,
            Duration llmTimeout
    ) {
        this.port = port;
        this.rootDir = rootDir;
        this.publicDir = publicDir;
        this.stateFile = stateFile;
        this.memoryRetentionMs = memoryRetentionMs;
        this.llmBaseUrl = llmBaseUrl;
        this.llmApiKey = llmApiKey;
        this.llmModel = llmModel;
        this.llmTimeout = llmTimeout;
    }

    static AppConfig load() {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path distDir = root.resolve("dist");
        Path staticDir = Files.exists(distDir.resolve("index.html")) ? distDir : root.resolve("public");
        String portValue = getenvOrDefault("PORT", "3000");
        String timeoutValue = firstNonBlank(
                System.getenv("ARK_TIMEOUT_MS"),
                System.getenv("OPENAI_TIMEOUT_MS"),
                "12000"
        );

        return new AppConfig(
                Integer.parseInt(portValue),
                root,
                staticDir,
                root.resolve("data").resolve("runtime").resolve("state.bin"),
                7L * 24 * 60 * 60 * 1000,
                firstNonBlank(
                        System.getenv("ARK_BASE_URL"),
                        System.getenv("OPENAI_BASE_URL"),
                        "https://ark.cn-beijing.volces.com/api/v3"
                ),
                firstNonBlank(
                        System.getenv("ARK_API_KEY"),
                        System.getenv("OPENAI_API_KEY"),
                        ""
                ),
                firstNonBlank(
                        System.getenv("ARK_MODEL"),
                        System.getenv("OPENAI_MODEL"),
                        "ep-20260418203515-nw4jb"
                ),
                Duration.ofMillis(Long.parseLong(timeoutValue))
        );
    }

    private static String getenvOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return normalize(first);
        }
        if (second != null && !second.isBlank()) {
            return normalize(second);
        }
        return normalize(fallback);
    }

    private static String normalize(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2) {
            boolean wrappedByDoubleQuotes = trimmed.startsWith("\"") && trimmed.endsWith("\"");
            boolean wrappedBySingleQuotes = trimmed.startsWith("'") && trimmed.endsWith("'");
            if (wrappedByDoubleQuotes || wrappedBySingleQuotes) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }
}
