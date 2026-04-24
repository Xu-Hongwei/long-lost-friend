package com.campuspulse;

import java.util.List;

class LocalSemanticRuntimeAgentService extends SemanticRuntimeAgentService {
    LocalSemanticRuntimeAgentService() {
        super();
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
        return localAnalyze(userMessage, sceneState, tensionState, timeContext, weatherContext, replySource, nowIso);
    }
}
