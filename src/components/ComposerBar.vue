<script setup lang="ts">
import { computed } from "vue";
import type { StageTimings } from "../types";

const props = defineProps<{
  modelValue: string;
  disabled?: boolean;
  loading?: boolean;
  loadingElapsedMs?: number;
  lastRoundTripMs?: number;
  stageTimings?: StageTimings;
  city?: string;
  sceneSummary?: string;
  agentName?: string;
}>();

const emits = defineEmits<{
  "update:modelValue": [value: string];
  send: [];
}>();

function formatMs(ms?: number) {
  const safe = Math.max(0, ms || 0);
  if (safe >= 1000) {
    return `${(safe / 1000).toFixed(1)}s`;
  }
  return `${safe}ms`;
}

const requestStatusLabel = computed(() => {
  if (props.loading) {
    return `本次请求 ${formatMs(props.loadingElapsedMs)}`;
  }
  if ((props.lastRoundTripMs || 0) > 0) {
    return `上次响应 ${formatMs(props.lastRoundTripMs)}`;
  }
  return "等待发送";
});

const orderedTimings = computed(() => {
  const source = props.stageTimings || {};
  const entries = Object.entries(source).filter(([, value]) => typeof value === "number");
  entries.sort(([leftKey, leftValue], [rightKey, rightValue]) => {
    if (leftKey === "total") return -1;
    if (rightKey === "total") return 1;
    return rightValue - leftValue;
  });
  return entries.slice(0, 8);
});

const hasTimings = computed(() => orderedTimings.value.length > 0);
</script>

<template>
  <form
    class="sticky bottom-0 z-10 rounded-[1.8rem] border border-white/12 bg-[rgba(10,11,22,0.82)] p-3 backdrop-blur-xl"
    @submit.prevent="emits('send')"
  >
    <div class="mb-3 flex flex-wrap items-center gap-2 px-2">
      <span class="rounded-full border border-white/10 bg-white/6 px-3 py-1 text-[11px] uppercase tracking-[0.22em] text-white/55">
        {{ agentName || "聊天中" }}
      </span>
      <span v-if="sceneSummary" class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/62">
        {{ sceneSummary }}
      </span>
      <span v-if="city" class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/62">
        {{ city }}
      </span>
      <span
        class="rounded-full border px-3 py-1 text-xs"
        :class="loading ? 'border-amber-200/25 bg-amber-100/10 text-amber-100/88' : 'border-white/10 bg-black/16 text-white/62'"
      >
        {{ requestStatusLabel }}
      </span>
    </div>

    <div v-if="hasTimings" class="mb-3 flex flex-wrap gap-2 px-2">
      <span
        v-for="[stageName, elapsedMs] in orderedTimings"
        :key="stageName"
        class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-[11px] text-white/55"
      >
        {{ stageName }} {{ formatMs(elapsedMs) }}
      </span>
    </div>

    <div class="flex items-end gap-3">
      <label class="sr-only" for="message-input">输入消息</label>
      <textarea
        id="message-input"
        :value="modelValue"
        rows="3"
        maxlength="240"
        :disabled="disabled"
        class="min-h-[86px] flex-1 resize-none rounded-[1.35rem] border border-white/10 bg-white/7 px-4 py-3 text-sm leading-7 text-white outline-none placeholder:text-white/34 focus:border-white/20"
        placeholder="比如：今天其实有点累，但还是想和你多聊一会。"
        @input="emits('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
      ></textarea>
      <button
        type="submit"
        :disabled="disabled"
        class="inline-flex h-12 items-center justify-center rounded-full bg-white px-5 text-sm font-medium text-slate-900 transition hover:-translate-y-0.5 hover:bg-[#fff2ea] disabled:cursor-not-allowed disabled:opacity-50"
      >
        {{ loading ? "发送中" : "发送" }}
      </button>
    </div>
  </form>
</template>
