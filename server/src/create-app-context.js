import { config } from "./config.js";
import { StateRepository } from "./repositories/state-repository.js";
import { AgentConfigService } from "./services/agent-config-service.js";
import { AnalyticsService } from "./services/analytics-service.js";
import { EventEngine } from "./services/event-engine.js";
import { MemoryService } from "./services/memory-service.js";
import { RelationshipService } from "./services/relationship-service.js";
import { SafetyService } from "./services/safety-service.js";
import { LLMService } from "./services/llm-service.js";
import { ChatOrchestrator } from "./services/chat-orchestrator.js";

export function createAppContext(customConfig = config) {
  const repository = new StateRepository(customConfig.stateFile);
  const agentConfigService = new AgentConfigService();
  const memoryService = new MemoryService(customConfig.memoryRetentionMs);
  const relationshipService = new RelationshipService();
  const eventEngine = new EventEngine();
  const safetyService = new SafetyService();
  const analyticsService = new AnalyticsService();
  const llmService = new LLMService(customConfig.llm);

  const chatOrchestrator = new ChatOrchestrator({
    repository,
    agentConfigService,
    memoryService,
    relationshipService,
    eventEngine,
    llmService,
    safetyService,
    analyticsService
  });

  return {
    config: customConfig,
    repository,
    agentConfigService,
    chatOrchestrator
  };
}
