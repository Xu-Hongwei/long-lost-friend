import { agentProfiles, getAgentPublicProfile } from "../data/agents.js";

export class AgentConfigService {
  constructor() {
    this.agents = agentProfiles;
  }

  listPublicAgents() {
    return this.agents.map(getAgentPublicProfile);
  }

  getAgentById(agentId) {
    return this.agents.find((agent) => agent.id === agentId) || null;
  }
}
