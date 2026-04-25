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
    final String plotLlmBaseUrl;
    final String plotLlmApiKey;
    final String plotLlmModel;
    final Duration plotLlmTimeout;

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
        this(
                port,
                rootDir,
                publicDir,
                stateFile,
                memoryRetentionMs,
                llmBaseUrl,
                llmApiKey,
                llmModel,
                llmTimeout,
                llmBaseUrl,
                llmApiKey,
                llmModel,
                llmTimeout
        );
    }

    AppConfig(
            int port,
            Path rootDir,
            Path publicDir,
            Path stateFile,
            long memoryRetentionMs,
            String llmBaseUrl,
            String llmApiKey,
            String llmModel,
            Duration llmTimeout,
            String plotLlmBaseUrl,
            String plotLlmApiKey,
            String plotLlmModel,
            Duration plotLlmTimeout
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
        this.plotLlmBaseUrl = plotLlmBaseUrl;
        this.plotLlmApiKey = plotLlmApiKey;
        this.plotLlmModel = plotLlmModel;
        this.plotLlmTimeout = plotLlmTimeout;
    }

    static AppConfig load() {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path distDir = root.resolve("dist");
        Path staticDir = Files.exists(distDir.resolve("index.html")) ? distDir : root.resolve("public");
        String portValue = getenvOrDefault("PORT", "3000");
        boolean preferDashScopeMain = hasAny(
                System.getenv("DASHSCOPE_API_KEY"),
                System.getenv("DASHSCOPE_BASE_URL"),
                System.getenv("DASHSCOPE_BASE"),
                System.getenv("DASHSCOPE_MODEL")
        );
        String timeoutValue = firstNonBlank(
                preferDashScopeMain ? System.getenv("DASHSCOPE_TIMEOUT_MS") : null,
                System.getenv("ARK_TIMEOUT_MS"),
                System.getenv("DASHSCOPE_TIMEOUT_MS"),
                System.getenv("OPENAI_TIMEOUT_MS"),
                "12000"
        );
        String llmBaseUrl = firstNonBlank(
                preferDashScopeMain ? System.getenv("DASHSCOPE_BASE_URL") : null,
                preferDashScopeMain ? System.getenv("DASHSCOPE_BASE") : null,
                System.getenv("ARK_BASE_URL"),
                System.getenv("DASHSCOPE_BASE_URL"),
                System.getenv("DASHSCOPE_BASE"),
                System.getenv("OPENAI_API_BASE"),
                System.getenv("OPENAI_BASE_URL"),
                System.getenv("OPENAI_BASE"),
                preferDashScopeMain
                        ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                        : "https://ark.cn-beijing.volces.com/api/v3"
        );
        String llmApiKey = firstNonBlank(
                preferDashScopeMain ? System.getenv("DASHSCOPE_API_KEY") : null,
                System.getenv("ARK_API_KEY"),
                System.getenv("DASHSCOPE_API_KEY"),
                System.getenv("OPENAI_API_KEY"),
                ""
        );
        String llmModel = firstNonBlank(
                preferDashScopeMain ? System.getenv("DASHSCOPE_MODEL") : null,
                preferDashScopeMain ? "qwen-plus-character" : null,
                System.getenv("ARK_MODEL"),
                System.getenv("DASHSCOPE_MODEL"),
                System.getenv("OPENAI_MODEL"),
                preferDashScopeMain ? "qwen-plus-character" : "ep-20260418203515-nw4jb"
        );
        Duration llmTimeout = Duration.ofMillis(Long.parseLong(timeoutValue));
        String plotTimeoutValue = firstNonBlank(
                System.getenv("PLOT_LLM_TIMEOUT_MS"),
                System.getenv("ARK_TIMEOUT_MS"),
                System.getenv("DASHSCOPE_TIMEOUT_MS"),
                System.getenv("OPENAI_TIMEOUT_MS"),
                timeoutValue
        );

        return new AppConfig(
                Integer.parseInt(portValue),
                root,
                staticDir,
                root.resolve("data").resolve("runtime").resolve("state.bin"),
                7L * 24 * 60 * 60 * 1000,
                llmBaseUrl,
                llmApiKey,
                llmModel,
                llmTimeout,
                firstNonBlank(
                        System.getenv("PLOT_LLM_BASE_URL"),
                        System.getenv("ARK_BASE_URL"),
                        System.getenv("DASHSCOPE_BASE_URL"),
                        System.getenv("DASHSCOPE_BASE"),
                        System.getenv("OPENAI_API_BASE"),
                        System.getenv("OPENAI_BASE_URL"),
                        System.getenv("OPENAI_BASE"),
                        llmBaseUrl
                ),
                firstNonBlank(
                        System.getenv("PLOT_LLM_API_KEY"),
                        System.getenv("ARK_API_KEY"),
                        System.getenv("DASHSCOPE_API_KEY"),
                        System.getenv("OPENAI_API_KEY"),
                        llmApiKey
                ),
                firstNonBlank(
                        System.getenv("PLOT_LLM_MODEL"),
                        System.getenv("ARK_MODEL"),
                        System.getenv("DASHSCOPE_MODEL"),
                        System.getenv("OPENAI_MODEL"),
                        llmModel
                ),
                Duration.ofMillis(Long.parseLong(plotTimeoutValue))
        );
    }

    private static String getenvOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return normalize(value);
            }
        }
        return "";
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

    private static boolean hasAny(String... values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
