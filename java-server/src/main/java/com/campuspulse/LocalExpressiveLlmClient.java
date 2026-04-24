package com.campuspulse;

class LocalExpressiveLlmClient extends ExpressiveLlmClient {
    LocalExpressiveLlmClient(AppConfig config) {
        super(config);
    }

    @Override
    public LlmResponse generateReply(LlmRequest request) {
        return generateLocalReply(request);
    }
}
