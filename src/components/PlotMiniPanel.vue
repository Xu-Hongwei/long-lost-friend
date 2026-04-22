<script setup lang="ts">
import type { PlotArcState, PlotState, PresenceState, StoryEventProgress } from "../types";

defineProps<{
  plotState: PlotState | null;
  plotArcState: PlotArcState | null;
  storyEventProgress: StoryEventProgress | null;
  presenceState: PresenceState | null;
}>();
</script>

<template>
  <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
    <p class="tracking-[0.28em] text-[0.68rem] uppercase text-white/45">Plot & Atmosphere</p>
    <h3 class="mt-2 text-lg font-semibold text-white">
      {{ plotArcState?.phase || plotState?.phase || "气氛刚刚铺开" }}
    </h3>
    <p class="mt-3 text-sm leading-6 text-white/68">
      {{ plotState?.sceneFrame || storyEventProgress?.lastTriggeredTheme || "场景会根据聊天、心跳和剧情自然推进。" }}
    </p>

    <div class="mt-5 grid grid-cols-3 gap-3">
      <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">
        <div class="text-[11px] uppercase tracking-[0.22em] text-white/42">Beat</div>
        <div class="mt-2 text-lg font-semibold text-white">{{ plotArcState?.beatIndex || plotState?.beatIndex || 0 }}</div>
      </div>
      <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">
        <div class="text-[11px] uppercase tracking-[0.22em] text-white/42">Arc</div>
        <div class="mt-2 text-lg font-semibold text-white">{{ plotArcState?.arcIndex || 1 }}</div>
      </div>
      <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">
        <div class="text-[11px] uppercase tracking-[0.22em] text-white/42">状态</div>
        <div class="mt-2 text-lg font-semibold text-white">{{ plotArcState?.runStatus || "进行中" }}</div>
      </div>
    </div>

    <p class="mt-4 text-sm leading-6 text-white/60">
      {{ presenceState?.heartbeatExplain || storyEventProgress?.nextExpectedDirection || plotState?.nextBeatHint || "下一步会顺着当前场景继续推进。" }}
    </p>
  </section>
</template>
