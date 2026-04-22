<script setup lang="ts">
import type { AnalyticsOverview, MemorySummary, PresenceState, RelationshipState, StoryEventProgress } from "../types";
import { getCadenceLabel, getIntentLabel, getMoodLabel } from "../lib/labels";

defineProps<{
  open: boolean;
  title: string;
  relationship: RelationshipState | null;
  memory: MemorySummary | null;
  plot: StoryEventProgress | null;
  analytics: AnalyticsOverview | null;
  presence: PresenceState | null;
}>();

const emits = defineEmits<{
  close: [];
}>();
</script>

<template>
  <teleport to="body">
    <transition name="fade">
      <div v-if="open" class="fixed inset-0 z-40 bg-[#05060d]/72 backdrop-blur-sm" @click="emits('close')"></div>
    </transition>
    <transition name="slide-up">
      <aside
        v-if="open"
        class="fixed inset-x-0 bottom-0 z-50 mx-auto w-[min(100%,980px)] rounded-t-[2rem] border border-white/12 bg-[#0d1020]/96 p-5 shadow-[0_-20px_60px_rgba(0,0,0,0.35)]"
      >
        <div class="mb-4 flex items-center justify-between">
          <div>
            <p class="tracking-[0.28em] text-[0.68rem] uppercase text-white/42">Insight Drawer</p>
            <h3 class="mt-2 text-xl font-semibold text-white">{{ title }}</h3>
          </div>
          <button
            type="button"
            class="rounded-full border border-white/10 px-3 py-2 text-sm text-white/68"
            @click="emits('close')"
          >
            关闭
          </button>
        </div>

        <div class="grid gap-4 md:grid-cols-2">
          <section class="rounded-[1.4rem] border border-white/10 bg-white/5 p-4">
            <h4 class="text-sm font-medium text-white/84">关系与情绪</h4>
            <p class="mt-3 text-sm leading-6 text-white/64">
              {{ relationship?.relationshipFeedback || relationship?.stageProgressHint || "关系会随着剧情节点慢慢偏移。" }}
            </p>
            <ul class="mt-4 space-y-2 text-sm text-white/64">
              <li>当前阶段：{{ relationship?.relationshipStage || "未开始" }}</li>
              <li>结局倾向：{{ relationship?.endingCandidate || "继续发展" }}</li>
              <li>在线状态：{{ presence?.heartbeatExplain || "顺着当前节奏继续" }}</li>
            </ul>
          </section>

          <section class="rounded-[1.4rem] border border-white/10 bg-white/5 p-4">
            <h4 class="text-sm font-medium text-white/84">记忆线索</h4>
            <ul class="mt-3 space-y-2 text-sm text-white/64">
              <li>最近情绪：{{ getMoodLabel(memory?.lastUserMood) }}</li>
              <li>最近意图：{{ getIntentLabel(memory?.lastUserIntent) }}</li>
              <li>回复节奏：{{ getCadenceLabel(memory?.lastResponseCadence) }}</li>
              <li>记忆策略：{{ memory?.lastMemoryUseMode || "轻带出" }}</li>
            </ul>
          </section>

          <section class="rounded-[1.4rem] border border-white/10 bg-white/5 p-4">
            <h4 class="text-sm font-medium text-white/84">剧情挂钩</h4>
            <ul class="mt-3 space-y-2 text-sm text-white/64">
              <li>最近事件：{{ plot?.lastTriggeredTitle || "尚未触发关键事件" }}</li>
              <li>路线主题：{{ plot?.currentRouteTheme || "日常升温" }}</li>
              <li>下一方向：{{ plot?.nextExpectedDirection || "等待更自然的承接" }}</li>
            </ul>
          </section>

          <section class="rounded-[1.4rem] border border-white/10 bg-white/5 p-4">
            <h4 class="text-sm font-medium text-white/84">试玩概览</h4>
            <ul class="mt-3 space-y-2 text-sm text-white/64">
              <li>UV：{{ analytics?.visitorCount ?? "--" }}</li>
              <li>会话：{{ analytics?.sessionCount ?? "--" }}</li>
              <li>平均轮次：{{ analytics?.avgTurns ?? "--" }}</li>
              <li>7 日续玩率：{{ analytics?.retention7d ?? "--" }}%</li>
            </ul>
          </section>
        </div>
      </aside>
    </transition>
  </teleport>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active,
.slide-up-enter-active,
.slide-up-leave-active {
  transition: all 0.24s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.slide-up-enter-from,
.slide-up-leave-to {
  opacity: 0;
  transform: translateY(20px);
}
</style>
