<script setup lang="ts">
import { computed } from "vue";
import type { RelationshipState } from "../types";
import { getEndingCandidateLabel } from "../lib/labels";

const props = defineProps<{
  relationship: RelationshipState | null;
}>();

const metrics = computed(() => {
  const source = props.relationship;
  if (!source) {
    return [];
  }
  return [
    { label: "亲近", value: source.closeness || 0 },
    { label: "信任", value: source.trust || 0 },
    { label: "默契", value: source.resonance || 0 }
  ];
});
</script>

<template>
  <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
    <div class="flex items-start justify-between gap-4">
      <div>
        <p class="tracking-[0.28em] text-[0.68rem] uppercase text-white/45">关系温度</p>
        <h3 class="mt-2 text-lg font-semibold text-white">{{ relationship?.relationshipStage || "尚未开始" }}</h3>
      </div>
      <div class="rounded-full border border-white/10 bg-black/14 px-3 py-1 text-xs text-white/66">
        {{ getEndingCandidateLabel(relationship?.endingCandidate) }}
      </div>
    </div>

    <div class="mt-5 space-y-4">
      <div v-for="metric in metrics" :key="metric.label" class="space-y-2">
        <div class="flex items-center justify-between text-sm text-white/64">
          <span>{{ metric.label }}</span>
          <span>{{ metric.value }}</span>
        </div>
        <div class="h-2 overflow-hidden rounded-full bg-white/8">
          <div
            class="h-full rounded-full bg-[linear-gradient(90deg,#ffcab8,#c1d4ff)]"
            :style="{ width: `${Math.min(metric.value, 100)}%` }"
          ></div>
        </div>
      </div>
    </div>

    <p class="mt-4 text-sm leading-6 text-white/62">
      {{ relationship?.relationshipFeedback || relationship?.stageProgressHint || "亲近感会随着回应方式、剧情节点和情绪承接慢慢变化。" }}
    </p>
  </section>
</template>
