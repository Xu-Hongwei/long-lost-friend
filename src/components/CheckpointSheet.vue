<script setup lang="ts">
import type { ArcSummary } from "../types";
import { getEndingCandidateLabel } from "../lib/labels";

defineProps<{
  open: boolean;
  beatIndex: number;
  arcIndex: number;
  summary: ArcSummary | null;
  canContinue: boolean;
  canSettle: boolean;
}>();

const emits = defineEmits<{
  close: [];
  continue: [];
  settle: [];
}>();
</script>

<template>
  <teleport to="body">
    <transition name="fade">
      <div v-if="open" class="fixed inset-0 z-40 bg-[#04050d]/70 backdrop-blur-sm" @click="emits('close')"></div>
    </transition>
    <transition name="sheet-up">
      <section
        v-if="open"
        class="fixed inset-x-0 bottom-0 z-50 mx-auto w-[min(100%,860px)] rounded-t-[2rem] border border-white/12 bg-[linear-gradient(180deg,#141827,#0d1020)] px-5 pb-6 pt-5 shadow-[0_-24px_70px_rgba(0,0,0,0.42)]"
      >
        <div class="flex items-start justify-between gap-4">
          <div>
            <p class="tracking-[0.28em] text-[0.68rem] uppercase text-white/42">Checkpoint</p>
            <h3 class="mt-2 text-2xl font-semibold text-white">
              第 {{ beatIndex }} 拍阶段总结
            </h3>
            <p class="mt-2 text-sm leading-6 text-white/66">
              你们已经进入第 {{ arcIndex }} 段关系节拍，现在可以继续推进，也可以先结算这一阶段。
            </p>
          </div>
          <button
            type="button"
            class="rounded-full border border-white/10 px-3 py-2 text-sm text-white/66"
            @click="emits('close')"
          >
            暂时收起
          </button>
        </div>

        <div class="mt-5 grid gap-4 md:grid-cols-2">
          <article class="rounded-[1.5rem] border border-white/10 bg-white/5 p-4">
            <p class="text-sm font-medium text-white/84">{{ summary?.title || "关系阶段总结" }}</p>
            <p class="mt-3 text-sm leading-6 text-white/64">
              当前路线更接近：{{ getEndingCandidateLabel(summary?.endingCandidate) }}
            </p>
            <div class="mt-4 flex flex-wrap gap-2">
              <span class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/66">
                {{ summary?.routeTheme || "日常升温" }}
              </span>
            </div>
          </article>

          <article class="rounded-[1.5rem] border border-white/10 bg-white/5 p-4">
            <p class="text-sm font-medium text-white/84">下一步可期待</p>
            <p class="mt-3 text-sm leading-6 text-white/64">
              {{ summary?.nextExpectation || "继续推进会在现有关系基础上自然延续，不会清空记忆。" }}
            </p>
          </article>
        </div>

        <div v-if="summary?.highlightMoments?.length" class="mt-5 rounded-[1.5rem] border border-white/10 bg-white/5 p-4">
          <p class="text-sm font-medium text-white/84">这一段最值得记住的瞬间</p>
          <ul class="mt-3 space-y-2 text-sm leading-6 text-white/64">
            <li v-for="item in summary.highlightMoments" :key="item">· {{ item }}</li>
          </ul>
        </div>

        <div class="mt-6 flex flex-wrap gap-3">
          <button
            type="button"
            :disabled="!canContinue"
            class="rounded-full bg-white px-5 py-3 text-sm font-medium text-slate-900 transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-45"
            @click="emits('continue')"
          >
            继续推进
          </button>
          <button
            type="button"
            :disabled="!canSettle"
            class="rounded-full border border-white/12 px-5 py-3 text-sm text-white/72 transition hover:bg-white/6 disabled:cursor-not-allowed disabled:opacity-45"
            @click="emits('settle')"
          >
            现在结算本阶段
          </button>
        </div>
      </section>
    </transition>
  </teleport>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active,
.sheet-up-enter-active,
.sheet-up-leave-active {
  transition: all 0.26s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.sheet-up-enter-from,
.sheet-up-leave-to {
  opacity: 0;
  transform: translateY(28px);
}
</style>
