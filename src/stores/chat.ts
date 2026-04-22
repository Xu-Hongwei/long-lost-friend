import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { usePresenceStore } from "./presence";
import { useSessionStore } from "./session";

export const useChatStore = defineStore("chat", () => {
  const sessionStore = useSessionStore();
  const presenceStore = usePresenceStore();
  const draft = ref("");
  const sending = ref(false);

  const disabled = computed(() => {
    return sending.value || sessionStore.busy || sessionStore.checkpointReady || Boolean(sessionStore.pendingChoices.length);
  });

  function setDraft(value: string) {
    draft.value = value;
    presenceStore.updateDraft(value);
  }

  async function send() {
    const payload = draft.value.trim();
    if (!payload || !sessionStore.currentSession) {
      return;
    }

    sending.value = true;
    try {
      await sessionStore.sendMessage(payload);
      draft.value = "";
      presenceStore.clearDraftState();
    } finally {
      sending.value = false;
    }
  }

  return {
    draft,
    sending,
    disabled,
    setDraft,
    send
  };
});
