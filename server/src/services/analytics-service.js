import { createId } from "../utils/ids.js";

export class AnalyticsService {
  recordEvent(state, type, payload = {}) {
    state.analyticsEvents.push({
      id: createId("evt"),
      type,
      createdAt: new Date().toISOString(),
      ...payload
    });
  }

  buildOverview(state, agents) {
    const visitorCount = state.visitors.length;
    const sessionCount = state.sessions.length;
    const totalTurns = state.sessions.reduce((sum, session) => sum + (session.userTurnCount || 0), 0);
    const avgTurns = sessionCount ? Number((totalTurns / sessionCount).toFixed(1)) : 0;
    const avgSessionMinutes = sessionCount
      ? Number(
          (
            state.sessions.reduce((sum, session) => {
              const start = Date.parse(session.createdAt);
              const end = Date.parse(session.lastActiveAt || session.createdAt);
              return sum + Math.max(1, (end - start) / 60000);
            }, 0) / sessionCount
          ).toFixed(1)
        )
      : 0;

    const agentPreference = agents.map((agent) => {
      const count = state.sessions.filter((session) => session.agentId === agent.id).length;
      return {
        agentId: agent.id,
        name: agent.name,
        count
      };
    });

    const returningVisitors = state.visitors.filter((visitor) => (visitor.initCount || 0) > 1).length;
    const retention7d = visitorCount ? Number(((returningVisitors / visitorCount) * 100).toFixed(1)) : 0;
    const feedbackCompletionRate = sessionCount
      ? Number(((state.feedback.length / sessionCount) * 100).toFixed(1))
      : 0;

    return {
      visitorCount,
      sessionCount,
      avgTurns,
      avgSessionMinutes,
      retention7d,
      feedbackCompletionRate,
      agentPreference
    };
  }
}
