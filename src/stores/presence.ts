import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { useSessionStore } from "./session";

const OFFLINE_CHECK_INTERVAL = 15000;
const TYPE_COOLDOWN_MS = 1200;

export const usePresenceStore = defineStore("presence", () => {
  const sessionStore = useSessionStore();
  const isTyping = ref(false);
  const draftLength = ref(0);
  const lastInputAt = ref("");
  const triggerReason = ref("");
  const blockedReason = ref("");
  const heartbeatExplain = ref("");

let intervalId: number | null = null;
let typingTimer: number | null = null;
let focusHandler: (() => void) | null = null;
let blurHandler: (() => void) | null = null;
let visibilityHandler: (() => void) | null = null;

  const canPing = computed(() => Boolean(sessionStore.currentSession?.sessionId));

  async function ping() {
    if (!canPing.value) {
      return;
    }
    const result = await sessionStore.updatePresence({
      visible: document.visibilityState === "visible",
      focused: document.hasFocus(),
      isTyping: isTyping.value,
      draftLength: draftLength.value,
      lastInputAt: lastInputAt.value
    });

    triggerReason.value = result?.trigger_reason || "";
    blockedReason.value = result?.blocked_reason || "";
    heartbeatExplain.value = result?.heartbeat_explain || "";
  }

  function updateDraft(value: string) {
    draftLength.value = value.length;
    lastInputAt.value = value ? new Date().toISOString() : "";
    isTyping.value = value.length > 0;
    if (typingTimer) {
      window.clearTimeout(typingTimer);
      typingTimer = null;
    }
    typingTimer = window.setTimeout(() => {
      isTyping.value = false;
    }, TYPE_COOLDOWN_MS);
  }

  function clearDraftState() {
    isTyping.value = false;
    draftLength.value = 0;
    lastInputAt.value = "";
  }

  function bindPageEvents() {
    if (focusHandler || blurHandler || visibilityHandler) {
      return;
    }

    const handler = () => {
      void ping();
    };
    focusHandler = handler;
    blurHandler = handler;
    visibilityHandler = handler;

    window.addEventListener("focus", focusHandler);
    window.addEventListener("blur", blurHandler);
    document.addEventListener("visibilitychange", visibilityHandler);
  }

  function start() {
    stop();
    bindPageEvents();
    void ping();
    intervalId = window.setInterval(() => {
      void ping();
    }, OFFLINE_CHECK_INTERVAL);
  }

  function stop() {
    if (intervalId) {
      window.clearInterval(intervalId);
      intervalId = null;
    }
    if (focusHandler) {
      window.removeEventListener("focus", focusHandler);
      focusHandler = null;
    }
    if (blurHandler) {
      window.removeEventListener("blur", blurHandler);
      blurHandler = null;
    }
    if (visibilityHandler) {
      document.removeEventListener("visibilitychange", visibilityHandler);
      visibilityHandler = null;
    }
  }

  return {
    isTyping,
    draftLength,
    lastInputAt,
    triggerReason,
    blockedReason,
    heartbeatExplain,
    ping,
    updateDraft,
    clearDraftState,
    start,
    stop
  };
});
