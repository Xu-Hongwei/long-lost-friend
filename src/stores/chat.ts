import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { usePresenceStore } from "./presence";
import { useSessionStore } from "./session";

export const useChatStore = defineStore("chat", () => {
  const sessionStore = useSessionStore();
  const presenceStore = usePresenceStore();
  const draft = ref("");
  const sending = ref(false);
  const sendingElapsedMs = ref(0);
  let sendingTimer: number | null = null;
  let sendingStartedAt = 0;

  const disabled = computed(() => {
    return sending.value || sessionStore.busy || sessionStore.checkpointReady || Boolean(sessionStore.pendingChoices.length);
  });

  function setDraft(value: string) {
    draft.value = value;
    presenceStore.updateDraft(value);
  }

  function stopSendingTimer() {
    if (sendingTimer !== null) {
      window.clearInterval(sendingTimer);
      sendingTimer = null;
    }
    sendingElapsedMs.value = 0;
    sendingStartedAt = 0;
  }

  function startSendingTimer() {
    stopSendingTimer();
    sendingStartedAt = performance.now();
    sendingElapsedMs.value = 0;
    sendingTimer = window.setInterval(() => {
      sendingElapsedMs.value = Math.round(performance.now() - sendingStartedAt);
    }, 100);
  }

  async function send() {
    const payload = draft.value.trim();
    if (!payload || !sessionStore.currentSession) {
      return;
    }

    sending.value = true;
    startSendingTimer();
    try {
      await sessionStore.sendMessage(payload);
      draft.value = "";
      presenceStore.clearDraftState();
    } finally {
      sending.value = false;
      stopSendingTimer();
    }
  }

  return {
    draft,
    sending,
    sendingElapsedMs,
    disabled,
    setDraft,
    send
  };
});
