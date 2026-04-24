package com.campuspulse;

import java.util.List;

class RemoteSemanticRuntimeAgentService extends SemanticRuntimeAgentService {
    RemoteSemanticRuntimeAgentService(AppConfig config) {
        super(config);
    }

    @Override
    SemanticRuntimeDecision analyze(
            String userMessage,
            List<ConversationSnippet> recentContext,
            SceneState sceneState,
            RelationshipState relationshipState,
            RelationalTensionState tensionState,
            MemorySummary memorySummary,
            TimeContext timeContext,
            WeatherContext weatherContext,
            String replySource,
            String nowIso
    ) {
        SemanticRuntimeDecision localReference = localAnalyze(userMessage, sceneState, tensionState, timeContext, weatherContext, replySource, nowIso);
        if (userMessage == null || userMessage.isBlank()) {
            return localReference;
        }
        try {
            SemanticRuntimeDecision remote = callRemote(
                    userMessage,
                    recentContext,
                    sceneState,
                    relationshipState,
                    tensionState,
                    memorySummary,
                    timeContext,
                    weatherContext,
                    replySource,
                    nowIso
            );
            return mergeWithFallback(remote, localReference, nowIso);
        } catch (Exception ex) {
            localReference.source = "remote_semantic_error";
            localReference.reason = "remote_error:" + ex.getClass().getSimpleName();
            return localReference;
        }
    }
}
