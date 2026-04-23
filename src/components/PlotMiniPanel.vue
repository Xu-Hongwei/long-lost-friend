<script setup lang="ts">
import { computed } from "vue";
import type { PlotArcState, PlotGateDecision, PlotState, PresenceState, StoryEventProgress, TurnContext } from "../types";

const props = defineProps<{
  plotState: PlotState | null;
  plotArcState: PlotArcState | null;
  storyEventProgress: StoryEventProgress | null;
  presenceState: PresenceState | null;
  plotGate: PlotGateDecision | null;
  turnContext: TurnContext | null;
  plotDirectorDecision: string;
}>();

const text = {
  title: "\u5267\u60c5\u4e0e\u6c1b\u56f4",
  fallbackPhase: "\u6c14\u6c1b\u521a\u521a\u94fa\u5f00",
  fallbackScene: "\u573a\u666f\u4f1a\u6839\u636e\u804a\u5929\u548c\u5267\u60c5\u81ea\u7136\u63a8\u8fdb\u3002",
  beat: "\u62cd\u6570",
  arc: "\u9636\u6bb5",
  status: "\u72b6\u6001",
  action: "\u5267\u60c5\u52a8\u4f5c",
  confidence: "\u7f6e\u4fe1\u5ea6",
  risk: "\u98ce\u9669\uff1a",
  needSignal: "\u8fd8\u7f3a\u7684\u7528\u6237\u4fe1\u53f7\uff1a",
  heartbeat: "\u5fc3\u8df3\u72b6\u6001\uff1a"
};

const actionLabel = computed(() => {
  const action = props.turnContext?.plotDirectorAction || props.plotDirectorDecision || "hold_plot";
  if (action === "advance_plot") return "\u5267\u60c5\u63a8\u8fdb";
  if (action === "transition_only") return "\u53ea\u8f6c\u573a";
  if (action === "heartbeat_nudge") return "\u8f7b\u63a8\u6c14\u6c1b";
  return "\u6682\u7f13\u63a8\u8fdb";
});

const plotExplain = computed(() => {
  if (props.turnContext?.plotDirectorAction === "advance_plot") {
    return props.turnContext.plotWhyNow || props.plotState?.nextBeatHint || "\u5267\u60c5\u5df2\u7ecf\u987a\u7740\u5f53\u524d\u804a\u5929\u5411\u524d\u63a8\u8fdb\u4e86\u4e00\u62cd\u3002";
  }
  if (props.turnContext?.plotDirectorAction === "transition_only") {
    return props.turnContext.plotWhyNow || "\u5f53\u524d\u5148\u5b8c\u6210\u573a\u666f\u79fb\u52a8\uff0c\u5267\u60c5\u4e0d\u4f1a\u62a2\u5728\u8f6c\u573a\u524d\u786c\u63a8\u8fdb\u3002";
  }
  if (props.plotGate?.blockedReason) {
    return `\u5173\u952e\u5267\u60c5\u6682\u7f13\uff1a${props.plotGate.blockedReason}`;
  }
  if (props.turnContext?.requiredUserSignal) {
    return `\u8fd8\u5dee\u4e00\u70b9\u7528\u6237\u4fe1\u53f7\uff1a${props.turnContext.requiredUserSignal}`;
  }
  if (props.turnContext?.plotRiskIfAdvance) {
    return `\u6682\u7f13\u539f\u56e0\uff1a${props.turnContext.plotRiskIfAdvance}`;
  }
  return props.storyEventProgress?.nextExpectedDirection || props.plotState?.nextBeatHint || "\u4e0b\u4e00\u6b65\u4f1a\u987a\u7740\u5f53\u524d\u573a\u666f\u7ee7\u7eed\u63a8\u8fdb\u3002";
});
</script>

<template>
  <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
    <p class="tracking-[0.28em] text-[0.68rem] uppercase text-white/45">{{ text.title }}</p>
    <h3 class="mt-2 text-lg font-semibold text-white">
      {{ plotArcState?.phase || plotState?.phase || text.fallbackPhase }}
    </h3>
    <p class="mt-3 text-sm leading-6 text-white/68">
      {{ plotState?.sceneFrame || storyEventProgress?.lastTriggeredTheme || text.fallbackScene }}
    </p>

    <div class="mt-5 grid grid-cols-3 gap-3">
      <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">
        <div class="text-[11px] tracking-[0.22em] text-white/42">{{ text.beat }}</div>
        <div class="mt-2 text-lg font-semibold text-white">{{ plotArcState?.beatIndex || plotState?.beatIndex || 0 }}</div>
      </div>
      <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">
        <div class="text-[11px] tracking-[0.22em] text-white/42">{{ text.arc }}</div>
        <div class="mt-2 text-lg font-semibold text-white">{{ plotArcState?.arcIndex || 1 }}</div>
      </div>
      <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">
        <div class="text-[11px] tracking-[0.22em] text-white/42">{{ text.status }}</div>
        <div class="mt-2 text-lg font-semibold text-white">{{ plotArcState?.runStatus || "in_progress" }}</div>
      </div>
    </div>

    <div class="mt-4 grid grid-cols-2 gap-3" v-if="turnContext">
      <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">
        <div class="text-[11px] tracking-[0.22em] text-white/42">{{ text.action }}</div>
        <div class="mt-2 text-sm font-semibold text-white">{{ actionLabel }}</div>
      </div>
      <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">
        <div class="text-[11px] tracking-[0.22em] text-white/42">{{ text.confidence }}</div>
        <div class="mt-2 text-sm font-semibold text-white">{{ turnContext.plotDirectorConfidence ?? 0 }}</div>
      </div>
    </div>

    <p class="mt-4 text-sm leading-6 text-white/60">
      {{ plotExplain }}
    </p>

    <p class="mt-3 text-xs leading-6 text-white/46" v-if="turnContext?.plotRiskIfAdvance">
      {{ text.risk }}{{ turnContext.plotRiskIfAdvance }}
    </p>

    <p class="mt-2 text-xs leading-6 text-white/46" v-if="turnContext?.requiredUserSignal">
      {{ text.needSignal }}{{ turnContext.requiredUserSignal }}
    </p>

    <p class="mt-2 text-xs leading-6 text-white/42" v-if="presenceState?.heartbeatExplain">
      {{ text.heartbeat }}{{ presenceState.heartbeatExplain }}
    </p>
  </section>
</template>
