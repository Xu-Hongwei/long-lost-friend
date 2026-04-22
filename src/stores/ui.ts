import { computed, ref, watch } from "vue";
import { defineStore } from "pinia";

type UiMode = "immersive" | "inspector";
type DrawerKey = "none" | "relationship" | "memory" | "plot" | "analytics";

const UI_MODE_KEY = "campus-agent-ui-mode";

export const useUiStore = defineStore("ui", () => {
  const initialMode = window.innerWidth <= 980
    ? "immersive"
    : ((localStorage.getItem(UI_MODE_KEY) as UiMode | null) || "immersive");

  const uiMode = ref<UiMode>(initialMode);
  const activeDrawer = ref<DrawerKey>("none");
  const checkpointSheetOpen = ref(false);

  watch(uiMode, (value) => {
    localStorage.setItem(UI_MODE_KEY, value);
    if (value === "inspector") {
      activeDrawer.value = "none";
    }
  });

  const isImmersive = computed(() => uiMode.value === "immersive");

  function setMode(mode: UiMode) {
    uiMode.value = mode;
  }

  function toggleDrawer(drawer: DrawerKey) {
    activeDrawer.value = activeDrawer.value === drawer ? "none" : drawer;
  }

  function openCheckpoint() {
    checkpointSheetOpen.value = true;
  }

  function closeCheckpoint() {
    checkpointSheetOpen.value = false;
  }

  return {
    uiMode,
    activeDrawer,
    checkpointSheetOpen,
    isImmersive,
    setMode,
    toggleDrawer,
    openCheckpoint,
    closeCheckpoint
  };
});
