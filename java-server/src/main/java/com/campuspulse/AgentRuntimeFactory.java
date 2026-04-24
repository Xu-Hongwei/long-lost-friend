package com.campuspulse;

final class AgentRuntimeFactory {
    private AgentRuntimeFactory() {
    }

    static CompositeLlmClient chatClient(AppConfig config) {
        String mode = mode(config);
        if ("local".equals(mode)) {
            return new LocalExpressiveLlmClient(config);
        }
        if ("remote".equals(mode)) {
            return new RemoteExpressiveLlmClient(config);
        }
        return new ExpressiveLlmClient(config);
    }

    static PlotDirectorAgentService plotDirector(AppConfig config) {
        String mode = mode(config);
        if ("local".equals(mode)) {
            return new LocalPlotDirectorAgentService();
        }
        if ("remote".equals(mode)) {
            return new RemotePlotDirectorAgentService(config);
        }
        return new PlotDirectorAgentService(config);
    }

    static SemanticRuntimeAgentService semanticRuntime(AppConfig config) {
        String mode = mode(config);
        if ("local".equals(mode)) {
            return new LocalSemanticRuntimeAgentService();
        }
        if ("remote".equals(mode)) {
            return new RemoteSemanticRuntimeAgentService(config);
        }
        return new SemanticRuntimeAgentService(config);
    }

    static AffectionJudgeService affectionJudge(AppConfig config) {
        String mode = mode(config);
        if ("local".equals(mode)) {
            return new LocalAffectionJudgeService();
        }
        if ("remote".equals(mode)) {
            return new RemoteAffectionJudgeService(config);
        }
        return new AffectionJudgeService();
    }

    private static String mode(AppConfig config) {
        return config == null || config.runMode == null || config.runMode.isBlank() ? "auto" : config.runMode;
    }
}
