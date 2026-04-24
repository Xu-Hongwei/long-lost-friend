package com.campuspulse;

class RemotePlotDirectorAgentService extends PlotDirectorAgentService {
    RemotePlotDirectorAgentService(AppConfig config) {
        super(config);
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
        PlotDirectorAgentDecision localReference = localDecision(text, replySource, currentTurn, gap, forcePlotAtTurn, signal);
        try {
            PlotDirectorAgentDecision remote = callRemoteDirector(
                    text,
                    replySource,
                    currentTurn,
                    gap,
                    forcePlotAtTurn,
                    signal,
                    emotionState,
                    relationshipState,
                    memorySummary,
                    turnContext
            );
            return sanitizeRemoteDecision(remote, localReference, replySource, gap, signal);
        } catch (Exception ex) {
            return new PlotDirectorAgentDecision(
                    "hold_plot",
                    "remote_error:" + ex.getClass().getSimpleName(),
                    "",
                    false
            );
        }
    }
}
