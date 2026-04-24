package com.campuspulse;

class RemoteExpressiveLlmClient extends ExpressiveLlmClient {
    RemoteExpressiveLlmClient(AppConfig config) {
        super(config);
    }

    @Override
    public LlmResponse generateReply(LlmRequest request) throws Exception {
        try {
            return generateRemoteReply(request);
        } catch (Exception error) {
            AgentProfile agent = request == null ? null : request.agent;
            String fallback = buildFallbackReply(agent, error.getClass().getSimpleName());
            return new LlmResponse(
                    fallback,
                    "guarded",
                    "remote_error",
                    Math.max(20, fallback.length()),
                    error.getClass().getSimpleName(),
                    true,
                    "remote"
            );
        }
    }
}
