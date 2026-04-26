import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { api } from "../lib/api";
import type {
  AgentProfile,
  AnalyticsOverview,
  PresenceResponse,
  SessionRecord,
  VisitorContextUpdateResult,
  VisitorInitResult
} from "../types";

const VISITOR_ID_KEY = "campus-agent-visitor-id";
const SESSION_ID_KEY = "campus-agent-session-id";
const AGENT_ID_KEY = "campus-agent-agent-id";
const CITY_KEY = "campus-agent-preferred-city";
const QUICK_JUDGE_ENABLED_KEY = "campus-agent-quick-judge-enabled";
const QUICK_JUDGE_MODE_KEY = "campus-agent-quick-judge-mode";
const QUICK_JUDGE_WAIT_SECONDS_KEY = "campus-agent-quick-judge-wait-seconds";
const QUICK_JUDGE_MIN_SECONDS = 0.06;
const QUICK_JUDGE_MAX_SECONDS = 5;
const QUICK_JUDGE_DEFAULT_SECONDS = 0.3;
export type QuickJudgeMode = "off" | "smart" | "always";

function normalizeQuickJudgeWaitSeconds(value: unknown) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return QUICK_JUDGE_DEFAULT_SECONDS;
  }
  return Math.min(QUICK_JUDGE_MAX_SECONDS, Math.max(QUICK_JUDGE_MIN_SECONDS, numeric));
}

function initialQuickJudgeMode(): QuickJudgeMode {
  const savedMode = localStorage.getItem(QUICK_JUDGE_MODE_KEY);
  if (savedMode === "off" || savedMode === "smart" || savedMode === "always") {
    return savedMode;
  }
  return localStorage.getItem(QUICK_JUDGE_ENABLED_KEY) === "false" ? "off" : "smart";
}

export const useSessionStore = defineStore("session", () => {
  const booting = ref(true);
  const busy = ref(false);
  const syncingContext = ref(false);
  const visitorId = ref("");
  const agents = ref<AgentProfile[]>([]);
  const currentAgentId = ref("");
  const currentSession = ref<SessionRecord | null>(null);
  const analytics = ref<AnalyticsOverview | null>(null);
  const errorMessage = ref("");
  const quickJudgeMode = ref<QuickJudgeMode>(initialQuickJudgeMode());
  const quickJudgeWaitSeconds = ref(normalizeQuickJudgeWaitSeconds(localStorage.getItem(QUICK_JUDGE_WAIT_SECONDS_KEY)));
  const quickJudgeEnabled = computed(() => quickJudgeMode.value !== "off");

  const selectedAgent = computed(() => {
    return agents.value.find((agent) => agent.id === currentAgentId.value) || agents.value[0] || null;
  });

  const preferredCity = computed(() => currentSession.value?.visitorContext?.preferredCity || localStorage.getItem(CITY_KEY) || "");
  const pendingChoices = computed(() => currentSession.value?.pendingChoices || []);
  const checkpointReady = computed(() => Boolean(currentSession.value?.plotArcState?.checkpointReady));

  async function boot() {
    booting.value = true;
    errorMessage.value = "";
    try {
      const knownVisitorId = localStorage.getItem(VISITOR_ID_KEY) || undefined;
      const visitor = await api<VisitorInitResult>("/api/visitor/init", {
        method: "POST",
        body: JSON.stringify({
          visitor_id: knownVisitorId
        })
      });
      visitorId.value = visitor.visitorId;
      localStorage.setItem(VISITOR_ID_KEY, visitor.visitorId);
      if (visitor.preferredCity) {
        localStorage.setItem(CITY_KEY, visitor.preferredCity);
      }

      agents.value = await api<AgentProfile[]>("/api/agents");

      const rememberedAgentId = localStorage.getItem(AGENT_ID_KEY);
      currentAgentId.value = visitor.restoredSession?.agentId || rememberedAgentId || agents.value[0]?.id || "";

      if (visitor.restoredSession?.sessionId) {
        localStorage.setItem(SESSION_ID_KEY, visitor.restoredSession.sessionId);
        await hydrateSession(visitor.restoredSession.sessionId);
      } else if (currentAgentId.value) {
        localStorage.setItem(AGENT_ID_KEY, currentAgentId.value);
      }

      await loadAnalytics();
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : "初始化失败";
    } finally {
      booting.value = false;
    }
  }

  async function loadAnalytics() {
    analytics.value = await api<AnalyticsOverview>("/api/analytics/overview");
  }

  async function hydrateSession(sessionId: string) {
    busy.value = true;
    try {
      const session = await api<SessionRecord>(`/api/session/state?session_id=${encodeURIComponent(sessionId)}`);
      currentSession.value = session;
      currentAgentId.value = session.agent.id;
      localStorage.setItem(SESSION_ID_KEY, session.sessionId);
      localStorage.setItem(AGENT_ID_KEY, session.agent.id);
      if (session.visitorContext?.preferredCity) {
        localStorage.setItem(CITY_KEY, session.visitorContext.preferredCity);
      }
    } finally {
      busy.value = false;
    }
  }

  async function startSession(agentId: string) {
    if (!visitorId.value) {
      return;
    }
    busy.value = true;
    errorMessage.value = "";
    try {
      const session = await api<SessionRecord>("/api/session/start", {
        method: "POST",
        body: JSON.stringify({
          visitor_id: visitorId.value,
          agent_id: agentId
        })
      });
      currentSession.value = session;
      currentAgentId.value = agentId;
      localStorage.setItem(AGENT_ID_KEY, agentId);
      localStorage.setItem(SESSION_ID_KEY, session.sessionId);
      await loadAnalytics();
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : "开启会话失败";
    } finally {
      busy.value = false;
    }
  }

  function selectAgent(agentId: string) {
    currentAgentId.value = agentId;
    localStorage.setItem(AGENT_ID_KEY, agentId);
  }

  async function saveVisitorContext(city: string) {
    if (!visitorId.value) {
      return;
    }
    syncingContext.value = true;
    const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Shanghai";
    try {
      const result = await api<VisitorContextUpdateResult>("/api/visitor/context", {
        method: "POST",
        body: JSON.stringify({
          visitor_id: visitorId.value,
          timezone,
          preferred_city: city
        })
      });
      localStorage.setItem(CITY_KEY, result.preferredCity || city);
      if (currentSession.value) {
        currentSession.value.visitorContext = {
          ...(currentSession.value.visitorContext || {}),
          timezone: result.timezone,
          preferredCity: result.preferredCity
        };
        if (result.timeContext) {
          currentSession.value.timeContext = result.timeContext;
        }
        if (result.weatherContext) {
          currentSession.value.weatherContext = result.weatherContext;
        }
      }
    } finally {
      syncingContext.value = false;
    }
  }

  async function sendMessage(message: string) {
    if (!currentSession.value || !message.trim()) {
      return;
    }
    busy.value = true;
    try {
      await api("/api/chat/send", {
        method: "POST",
        body: JSON.stringify({
          visitor_id: visitorId.value,
          session_id: currentSession.value.sessionId,
          agent_id: currentSession.value.agent.id,
          user_message: message,
          quick_judge_mode: quickJudgeMode.value,
          quick_judge_wait_seconds: quickJudgeWaitSeconds.value
        })
      });
      await hydrateSession(currentSession.value.sessionId);
      await loadAnalytics();
    } finally {
      busy.value = false;
    }
  }

  function setQuickJudgeMode(mode: QuickJudgeMode) {
    quickJudgeMode.value = mode;
    localStorage.setItem(QUICK_JUDGE_MODE_KEY, mode);
    localStorage.setItem(QUICK_JUDGE_ENABLED_KEY, String(mode !== "off"));
  }

  function setQuickJudgeWaitSeconds(value: number) {
    quickJudgeWaitSeconds.value = normalizeQuickJudgeWaitSeconds(value);
    localStorage.setItem(QUICK_JUDGE_WAIT_SECONDS_KEY, String(quickJudgeWaitSeconds.value));
  }

  async function submitChoice(choiceId: string) {
    if (!currentSession.value) {
      return;
    }
    busy.value = true;
    try {
      await api("/api/event/choose", {
        method: "POST",
        body: JSON.stringify({
          visitor_id: visitorId.value,
          session_id: currentSession.value.sessionId,
          choice_id: choiceId
        })
      });
      await hydrateSession(currentSession.value.sessionId);
    } finally {
      busy.value = false;
    }
  }

  async function continueCheckpoint() {
    if (!currentSession.value) {
      return;
    }
    busy.value = true;
    try {
      await api("/api/session/checkpoint", {
        method: "POST",
        body: JSON.stringify({
          visitor_id: visitorId.value,
          session_id: currentSession.value.sessionId
        })
      });
      await hydrateSession(currentSession.value.sessionId);
    } finally {
      busy.value = false;
    }
  }

  async function settleCheckpoint() {
    if (!currentSession.value) {
      return;
    }
    busy.value = true;
    try {
      await api("/api/session/settle", {
        method: "POST",
        body: JSON.stringify({
          visitor_id: visitorId.value,
          session_id: currentSession.value.sessionId
        })
      });
      await hydrateSession(currentSession.value.sessionId);
    } finally {
      busy.value = false;
    }
  }

  async function updatePresence(payload: {
    visible: boolean;
    focused: boolean;
    isTyping: boolean;
    draftLength: number;
    lastInputAt: string;
  }) {
    if (!currentSession.value) {
      return null;
    }

    const result = await api<PresenceResponse>("/api/session/presence", {
      method: "POST",
      body: JSON.stringify({
        visitor_id: visitorId.value,
        session_id: currentSession.value.sessionId,
        visible: payload.visible,
        focused: payload.focused,
        is_typing: payload.isTyping,
        draft_length: payload.draftLength,
        last_input_at: payload.lastInputAt,
        client_time: new Date().toISOString()
      })
    });

    if (currentSession.value) {
      currentSession.value.presenceState = {
        ...(result.presenceState || {}),
        triggerReason: result.trigger_reason,
        blockedReason: result.blocked_reason,
        heartbeatExplain: result.heartbeat_explain || result.presenceState?.heartbeatExplain
      };
      if (result.emotion_state) {
        currentSession.value.emotionState = result.emotion_state;
      }
      if (result.plot_progress) {
        currentSession.value.plotState = result.plot_progress;
      }
      if (result.plot_arc_state) {
        currentSession.value.plotArcState = result.plot_arc_state;
      }
      if (result.scene_state) {
        currentSession.value.sceneState = result.scene_state;
      }
      if (result.dialogue_continuity) {
        currentSession.value.dialogueContinuityState = result.dialogue_continuity;
      }
    }

    if (result.proactive_message && currentSession.value) {
      await hydrateSession(currentSession.value.sessionId);
    }

    return result;
  }

  return {
    booting,
    busy,
    syncingContext,
    visitorId,
    agents,
    currentAgentId,
    currentSession,
    analytics,
    errorMessage,
    quickJudgeMode,
    quickJudgeEnabled,
    quickJudgeWaitSeconds,
    selectedAgent,
    preferredCity,
    pendingChoices,
    checkpointReady,
    boot,
    loadAnalytics,
    hydrateSession,
    startSession,
    selectAgent,
    saveVisitorContext,
    sendMessage,
    setQuickJudgeMode,
    setQuickJudgeWaitSeconds,
    submitChoice,
    continueCheckpoint,
    settleCheckpoint,
    updatePresence
  };
});
