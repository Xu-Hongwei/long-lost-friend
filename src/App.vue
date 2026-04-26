<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import HeroSection from "./components/HeroSection.vue";
import AgentRail from "./components/AgentRail.vue";
import ChatStage from "./components/ChatStage.vue";
import InsightDrawer from "./components/InsightDrawer.vue";
import CheckpointSheet from "./components/CheckpointSheet.vue";
import { useSessionStore } from "./stores/session";
import { useUiStore } from "./stores/ui";
import { usePresenceStore } from "./stores/presence";
import { useChatStore } from "./stores/chat";
import { useCheckpointStore } from "./stores/checkpoint";

const sessionStore = useSessionStore();
const uiStore = useUiStore();
const presenceStore = usePresenceStore();
const chatStore = useChatStore();
const checkpointStore = useCheckpointStore();
const cityDraft = ref("");
const chatStageAnchor = ref<HTMLElement | null>(null);

const activeDrawerTitle = computed(() => {
  const mapping = {
    relationship: "关系观察",
    memory: "记忆线索",
    plot: "剧情进度",
    analytics: "试玩数据",
    none: ""
  };
  return mapping[uiStore.activeDrawer];
});

const sceneMoodStyle = computed(() => {
  const palette = sessionStore.selectedAgent?.palette || ["#f7c6b4", "#d0dcff", "#41273a"];
  return {
    "--tone-a": palette[0],
    "--tone-b": palette[1],
    "--tone-c": palette[2] || "#241829"
  };
});

async function startFromHero() {
  const targetId = sessionStore.selectedAgent?.id || sessionStore.currentSession?.agent.id;
  if (targetId) {
    await sessionStore.startSession(targetId);
    await scrollToChatStage();
  }
}

async function handleSelectAgent(agentId: string) {
  sessionStore.selectAgent(agentId);
  await sessionStore.startSession(agentId);
}

async function handleStart(agentId: string) {
  await sessionStore.startSession(agentId);
  await scrollToChatStage();
}

async function saveCity() {
  await sessionStore.saveVisitorContext(cityDraft.value.trim());
}

async function scrollToChatStage() {
  await nextTick();
  chatStageAnchor.value?.scrollIntoView({
    behavior: "smooth",
    block: "start"
  });
}

watch(
  () => sessionStore.currentSession?.sessionId,
  (sessionId) => {
    if (sessionId) {
      presenceStore.start();
    } else {
      presenceStore.stop();
    }
  },
  { immediate: true }
);

watch(
  () => sessionStore.currentSession?.plotArcState?.checkpointReady,
  () => checkpointStore.syncWithSession(),
  { immediate: true }
);

watch(
  () => sessionStore.preferredCity,
  (city) => {
    cityDraft.value = city || "";
  },
  { immediate: true }
);

onMounted(async () => {
  await sessionStore.boot();
});
</script>

<template>
  <div class="min-h-screen bg-[linear-gradient(145deg,#0c0d19_0%,#171224_48%,#1d2333_100%)] text-white" :style="sceneMoodStyle">
    <div class="pointer-events-none fixed inset-0 opacity-80">
      <div class="absolute -left-24 top-[-8rem] h-[30rem] w-[30rem] rounded-full bg-[radial-gradient(circle,var(--tone-a),transparent_62%)] blur-3xl"></div>
      <div class="absolute bottom-[-10rem] right-[-8rem] h-[30rem] w-[30rem] rounded-full bg-[radial-gradient(circle,var(--tone-b),transparent_62%)] blur-3xl"></div>
      <div class="absolute inset-0 bg-[linear-gradient(rgba(255,255,255,0.03)_1px,transparent_1px),linear-gradient(90deg,rgba(255,255,255,0.03)_1px,transparent_1px)] bg-[size:4px_4px] opacity-[0.05]"></div>
    </div>

    <div class="relative mx-auto w-[min(100%,1480px)] px-4 py-6 sm:px-6 xl:px-8">
      <div v-if="sessionStore.errorMessage" class="mb-5 rounded-[1.4rem] border border-rose-300/20 bg-rose-200/8 px-4 py-3 text-sm text-rose-100/90">
        {{ sessionStore.errorMessage }}
      </div>

      <div class="space-y-6">
        <HeroSection
          :agent="sessionStore.selectedAgent"
          :session="sessionStore.currentSession"
          @start="startFromHero"
        />

        <AgentRail
          :agents="sessionStore.agents"
          :selected-agent-id="sessionStore.currentAgentId"
          :active-session-agent-id="sessionStore.currentSession?.agent.id"
          :active-session-user-turn-count="sessionStore.currentSession?.userTurnCount || 0"
          @select="handleSelectAgent"
          @start="handleStart"
        />

        <div ref="chatStageAnchor" class="scroll-mt-6">
          <ChatStage
            :session="sessionStore.currentSession"
            :agent="sessionStore.currentSession?.agent || sessionStore.selectedAgent"
            :analytics="sessionStore.analytics"
            :ui-mode="uiStore.uiMode"
            :draft="chatStore.draft"
            :sending="chatStore.sending"
            :disabled="chatStore.disabled"
            :quick-judge-mode="sessionStore.quickJudgeMode"
            :quick-judge-enabled="sessionStore.quickJudgeEnabled"
            :quick-judge-wait-seconds="sessionStore.quickJudgeWaitSeconds"
            @update:draft="chatStore.setDraft"
            @send="chatStore.send"
            @choose="sessionStore.submitChoice"
            @set-quick-judge-mode="sessionStore.setQuickJudgeMode"
            @set-quick-judge-wait-seconds="sessionStore.setQuickJudgeWaitSeconds"
            @export-debug-data="sessionStore.exportDebugData"
            @toggleDrawer="uiStore.toggleDrawer"
          />
        </div>
      </div>

      <div class="mt-6 flex flex-wrap items-center gap-3">
        <button
          type="button"
          class="rounded-full border border-white/10 px-4 py-2 text-sm text-white/70 transition hover:bg-white/6"
          :class="uiStore.uiMode === 'immersive' ? 'bg-white/8' : ''"
          @click="uiStore.setMode('immersive')"
        >
          沉浸聊天
        </button>
        <button
          type="button"
          class="rounded-full border border-white/10 px-4 py-2 text-sm text-white/70 transition hover:bg-white/6"
          :class="uiStore.uiMode === 'inspector' ? 'bg-white/8' : ''"
          @click="uiStore.setMode('inspector')"
        >
          调试观察
        </button>
        <form class="ml-auto flex items-center gap-2" @submit.prevent="saveCity">
          <input
            v-model="cityDraft"
            type="text"
            placeholder="输入天气城市，比如杭州"
            class="w-56 rounded-full border border-white/10 bg-white/6 px-4 py-2 text-sm text-white outline-none placeholder:text-white/34"
            @blur="saveCity"
          />
          <button
            type="submit"
            class="rounded-full border border-white/10 bg-white/8 px-4 py-2 text-sm text-white/72 transition hover:bg-white/12 disabled:opacity-45"
            :disabled="sessionStore.syncingContext"
          >
            {{ sessionStore.syncingContext ? "保存中" : "保存城市" }}
          </button>
        </form>
      </div>
    </div>

    <InsightDrawer
      :open="uiStore.activeDrawer !== 'none' && uiStore.uiMode === 'immersive'"
      :title="activeDrawerTitle"
      :relationship="sessionStore.currentSession?.relationshipState || null"
      :memory="sessionStore.currentSession?.memorySummary || null"
      :plot="sessionStore.currentSession?.storyEventProgress || null"
      :plot-state="sessionStore.currentSession?.plotState || null"
      :plot-arc-state="sessionStore.currentSession?.plotArcState || null"
      :analytics="sessionStore.analytics"
      :presence="sessionStore.currentSession?.presenceState || null"
      :scene-state="sessionStore.currentSession?.sceneState || null"
      :time-context="sessionStore.currentSession?.timeContext || null"
      :weather-context="sessionStore.currentSession?.weatherContext || null"
      :emotion-state="sessionStore.currentSession?.emotionState || null"
      :intent-state="sessionStore.currentSession?.lastIntentState || null"
      :response-plan="sessionStore.currentSession?.lastResponsePlan || null"
      :humanization-audit="sessionStore.currentSession?.lastHumanizationAudit || null"
      :reality-audit="sessionStore.currentSession?.lastRealityAudit || null"
      :plot-gate="sessionStore.currentSession?.lastPlotGateDecision || null"
      :turn-context="sessionStore.currentSession?.lastTurnContext || null"
      :dialogue-continuity="sessionStore.currentSession?.dialogueContinuityState || null"
      :tension-state="sessionStore.currentSession?.tensionState || null"
      @close="uiStore.toggleDrawer('none')"
    />

    <CheckpointSheet
      :open="checkpointStore.open"
      :beat-index="sessionStore.currentSession?.plotArcState?.beatIndex || 10"
      :arc-index="sessionStore.currentSession?.plotArcState?.arcIndex || 1"
      :summary="checkpointStore.summary"
      :can-continue="checkpointStore.canContinue"
      :can-settle="checkpointStore.canSettle"
      @close="uiStore.closeCheckpoint"
      @continue="sessionStore.continueCheckpoint"
      @settle="sessionStore.settleCheckpoint"
    />
  </div>
</template>
