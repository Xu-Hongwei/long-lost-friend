import { computed } from "vue";
import { defineStore } from "pinia";
import { useSessionStore } from "./session";
import { useUiStore } from "./ui";

export const useCheckpointStore = defineStore("checkpoint", () => {
  const sessionStore = useSessionStore();
  const uiStore = useUiStore();

  const summary = computed(() => sessionStore.currentSession?.plotArcState?.latestArcSummary || null);
  const canSettle = computed(() => Boolean(sessionStore.currentSession?.plotArcState?.canSettleScore));
  const canContinue = computed(() => Boolean(sessionStore.currentSession?.plotArcState?.canContinue));
  const open = computed(() => uiStore.checkpointSheetOpen);

  function syncWithSession() {
    if (sessionStore.currentSession?.plotArcState?.checkpointReady) {
      uiStore.openCheckpoint();
    } else {
      uiStore.closeCheckpoint();
    }
  }

  return {
    summary,
    canSettle,
    canContinue,
    open,
    syncWithSession
  };
});
