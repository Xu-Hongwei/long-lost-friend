import { createId } from "../utils/ids.js";

export class ChatOrchestrator {
  constructor(services) {
    this.repository = services.repository;
    this.agentConfigService = services.agentConfigService;
    this.memoryService = services.memoryService;
    this.relationshipService = services.relationshipService;
    this.eventEngine = services.eventEngine;
    this.llmService = services.llmService;
    this.safetyService = services.safetyService;
    this.analyticsService = services.analyticsService;
  }

  async initVisitor(visitorId) {
    return this.repository.transact(async (state) => {
      const now = new Date();
      let visitor = visitorId ? state.visitors.find((item) => item.id === visitorId) : null;

      if (!visitor) {
        visitor = {
          id: createId("visitor"),
          createdAt: now.toISOString(),
          lastActiveAt: now.toISOString(),
          initCount: 1
        };
        state.visitors.push(visitor);
      } else {
        visitor.lastActiveAt = now.toISOString();
        visitor.initCount = (visitor.initCount || 0) + 1;
      }

      const activeSessions = state.sessions
        .filter((session) => session.visitorId === visitor.id && !this.memoryService.isExpired(session, now))
        .sort((a, b) => Date.parse(b.lastActiveAt) - Date.parse(a.lastActiveAt));

      const restoredSession = activeSessions[0] || null;
      this.analyticsService.recordEvent(state, "visitor_init", {
        visitorId: visitor.id,
        restoredSessionId: restoredSession?.id || null
      });

      return {
        visitorId: visitor.id,
        restoredSession: restoredSession
          ? {
              sessionId: restoredSession.id,
              agentId: restoredSession.agentId,
              affectionScore: restoredSession.relationshipState.affectionScore,
              relationshipStage: restoredSession.relationshipState.relationshipStage,
              memoryExpireAt: restoredSession.memoryExpireAt
            }
          : null
      };
    });
  }

  async listAgents() {
    return this.agentConfigService.listPublicAgents();
  }

  async startSession(visitorId, agentId) {
    return this.repository.transact(async (state) => {
      const now = new Date();
      const visitor = state.visitors.find((item) => item.id === visitorId);
      const agent = this.agentConfigService.getAgentById(agentId);

      if (!visitor) {
        throw new Error("VISITOR_NOT_FOUND");
      }
      if (!agent) {
        throw new Error("AGENT_NOT_FOUND");
      }

      const existing = state.sessions.find(
        (session) =>
          session.visitorId === visitorId &&
          session.agentId === agentId &&
          !this.memoryService.isExpired(session, now)
      );

      if (existing) {
        return this.buildSessionPayload(state, existing.id);
      }

      const sessionId = createId("session");
      const createdAt = now.toISOString();
      const session = {
        id: sessionId,
        visitorId,
        agentId,
        createdAt,
        lastActiveAt: createdAt,
        memoryExpireAt: this.memoryService.createSessionMemoryExpiry(now),
        userTurnCount: 0,
        relationshipState: this.relationshipService.createInitialState(),
        memorySummary: this.memoryService.createMemorySummary(createdAt),
        storyEventProgress: {
          triggeredEventIds: [],
          lastTriggeredEventId: null
        }
      };

      state.sessions.push(session);
      state.messages.push({
        id: createId("msg"),
        sessionId,
        role: "assistant",
        text: agent.openingLine,
        createdAt,
        emotionTag: "opening",
        confidenceStatus: "system",
        tokenUsage: 0,
        fallbackUsed: false
      });

      this.analyticsService.recordEvent(state, "session_start", {
        visitorId,
        sessionId,
        agentId
      });

      return this.buildSessionPayload(state, sessionId);
    });
  }

  async getSessionState(sessionId) {
    const state = await this.repository.getState();
    return this.buildSessionPayload(state, sessionId);
  }

  async getSessionHistory(sessionId) {
    const state = await this.repository.getState();
    const session = state.sessions.find((item) => item.id === sessionId);
    if (!session) {
      throw new Error("SESSION_NOT_FOUND");
    }

    return state.messages
      .filter((message) => message.sessionId === sessionId)
      .sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));
  }

  async submitFeedback(payload) {
    return this.repository.transact(async (state) => {
      const session = state.sessions.find((item) => item.id === payload.sessionId);
      if (!session) {
        throw new Error("SESSION_NOT_FOUND");
      }

      state.feedback.push({
        id: createId("feedback"),
        ...payload,
        createdAt: new Date().toISOString()
      });

      this.analyticsService.recordEvent(state, "feedback_submit", {
        visitorId: payload.visitorId,
        sessionId: payload.sessionId,
        agentId: payload.agentId
      });

      return {
        saved: true
      };
    });
  }

  async getAnalyticsOverview() {
    const state = await this.repository.getState();
    return this.analyticsService.buildOverview(state, this.agentConfigService.agents);
  }

  async sendMessage(payload) {
    return this.repository.transact(async (state) => {
      const now = new Date();
      const session = state.sessions.find((item) => item.id === payload.sessionId);

      if (!session) {
        throw new Error("SESSION_NOT_FOUND");
      }

      if (session.visitorId !== payload.visitorId) {
        throw new Error("VISITOR_SESSION_MISMATCH");
      }

      if (this.memoryService.isExpired(session, now)) {
        throw new Error("SESSION_EXPIRED");
      }

      const agent = this.agentConfigService.getAgentById(payload.agentId || session.agentId);
      if (!agent || agent.id !== session.agentId) {
        throw new Error("AGENT_NOT_FOUND");
      }

      const sessionMessages = state.messages
        .filter((message) => message.sessionId === session.id)
        .sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));

      const inspection = this.safetyService.inspectUserInput(payload.userMessage, sessionMessages);
      const messageCreatedAt = now.toISOString();

      state.messages.push({
        id: createId("msg"),
        sessionId: session.id,
        role: "user",
        text: payload.userMessage.trim(),
        createdAt: messageCreatedAt,
        emotionTag: "user",
        confidenceStatus: "user",
        tokenUsage: 0,
        fallbackUsed: false
      });

      let triggeredEvent = null;
      let relationshipResult = {
        nextState: session.relationshipState,
        affectionDelta: {
          closeness: 0,
          trust: 0,
          resonance: 0,
          total: 0
        }
      };
      let llmReply;

      if (inspection.blocked) {
        llmReply = {
          replyText: inspection.safeMessage,
          emotionTag: "guarded",
          confidenceStatus: "guarded",
          tokenUsage: inspection.safeMessage.length,
          errorCode: inspection.reason,
          fallbackUsed: true,
          source: "safety"
        };
      } else {
        triggeredEvent = this.eventEngine.findTriggeredEvent(agent, session, payload.userMessage);
        relationshipResult = this.relationshipService.evaluateTurn(payload.userMessage, session.relationshipState, triggeredEvent);
        const shortTermContext = this.memoryService.getShortTermContext([...sessionMessages, { role: "user", text: payload.userMessage }]);
        const longTermSummary = this.memoryService.getSummaryText(session.memorySummary);

        llmReply = await this.llmService.generateReply({
          agent,
          relationshipState: relationshipResult.nextState,
          shortTermContext,
          longTermSummary,
          event: triggeredEvent,
          userMessage: payload.userMessage
        });

        const outputInspection = this.safetyService.inspectAssistantOutput(llmReply.replyText);
        if (outputInspection.blocked) {
          llmReply = {
            replyText: this.llmService.buildFallbackReply(agent, outputInspection.reason),
            emotionTag: "guarded",
            confidenceStatus: "fallback",
            tokenUsage: 0,
            errorCode: outputInspection.reason,
            fallbackUsed: true,
            source: "fallback"
          };
        }
      }

      const replyCreatedAt = new Date().toISOString();
      state.messages.push({
        id: createId("msg"),
        sessionId: session.id,
        role: "assistant",
        text: llmReply.replyText,
        createdAt: replyCreatedAt,
        emotionTag: llmReply.emotionTag,
        confidenceStatus: llmReply.confidenceStatus,
        tokenUsage: llmReply.tokenUsage,
        fallbackUsed: llmReply.fallbackUsed,
        triggeredEventId: triggeredEvent?.id || null,
        affectionDelta: relationshipResult.affectionDelta.total
      });

      session.lastActiveAt = replyCreatedAt;
      session.memoryExpireAt = this.memoryService.createSessionMemoryExpiry(new Date(replyCreatedAt));
      session.userTurnCount += 1;
      session.relationshipState = relationshipResult.nextState;
      session.memorySummary = this.memoryService.updateSummary(
        session.memorySummary,
        payload.userMessage,
        triggeredEvent,
        relationshipResult.nextState.relationshipStage,
        replyCreatedAt
      );

      if (triggeredEvent) {
        session.storyEventProgress.triggeredEventIds.push(triggeredEvent.id);
        session.storyEventProgress.lastTriggeredEventId = triggeredEvent.id;
      }

      this.analyticsService.recordEvent(state, "chat_turn", {
        visitorId: payload.visitorId,
        sessionId: session.id,
        agentId: session.agentId,
        triggeredEventId: triggeredEvent?.id || null,
        fallbackUsed: llmReply.fallbackUsed
      });

      return {
        reply_text: llmReply.replyText,
        affection_score: session.relationshipState.affectionScore,
        affection_delta: relationshipResult.affectionDelta,
        relationship_stage: session.relationshipState.relationshipStage,
        triggered_event: triggeredEvent
          ? {
              id: triggeredEvent.id,
              title: triggeredEvent.title,
              theme: triggeredEvent.theme
            }
          : null,
        memory_expire_at: session.memoryExpireAt,
        fallback_used: llmReply.fallbackUsed,
        ending: session.relationshipState.ending
      };
    });
  }

  buildSessionPayload(state, sessionId) {
    const session = state.sessions.find((item) => item.id === sessionId);
    if (!session) {
      throw new Error("SESSION_NOT_FOUND");
    }

    const agent = this.agentConfigService.getAgentById(session.agentId);
    const history = state.messages
      .filter((message) => message.sessionId === session.id)
      .sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));

    return {
      sessionId: session.id,
      visitorId: session.visitorId,
      agent: {
        id: agent.id,
        name: agent.name,
        archetype: agent.archetype,
        tagline: agent.tagline,
        palette: agent.palette
      },
      relationshipState: session.relationshipState,
      memorySummary: session.memorySummary,
      storyEventProgress: session.storyEventProgress,
      memoryExpireAt: session.memoryExpireAt,
      userTurnCount: session.userTurnCount,
      history
    };
  }
}
