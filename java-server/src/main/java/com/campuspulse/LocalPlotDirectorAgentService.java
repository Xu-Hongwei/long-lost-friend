package com.campuspulse;

class LocalPlotDirectorAgentService extends PlotDirectorAgentService {
    LocalPlotDirectorAgentService() {
        super();
    }

    @Override
    PlotDirectorAgentDecision decide(
            String userMessage,
            String replySource,
            int currentTurn,
            int gap,
            int forcePlotAtTurn,
            boolean explicitTransition,
            int signal,
            EmotionState emotionState,
            RelationshipState relationshipState,
            MemorySummary memorySummary,
            TurnContext turnContext
    ) {
        String text = userMessage == null ? "" : userMessage.trim();
        PlotDirectorAgentDecision guard = guardDecision(text, replySource, gap, explicitTransition);
        if (guard != null) {
            return guard;
        }
        return localDecision(text, replySource, currentTurn, gap, forcePlotAtTurn, signal);
    }
}
