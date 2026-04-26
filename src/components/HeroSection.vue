<script setup lang="ts">
import { computed } from "vue";
import { motion } from "motion-v";
import type { AgentProfile, SessionRecord } from "../types";

const props = defineProps<{
  agent: AgentProfile | null;
  session: SessionRecord | null;
}>();

const emits = defineEmits<{
  start: [];
}>();

const tags = computed(() => props.agent?.styleTags?.slice(0, 3) || props.agent?.likes?.slice(0, 3) || []);
const ctaLabel = computed(() => {
  if (!props.agent) {
    return "开始聊天";
  }
  if (props.session?.agent.id === props.agent.id && props.session.userTurnCount > 0) {
    return `继续和 ${props.agent.name} 聊`;
  }
  return `和 ${props.agent.name} 开始今晚`;
});
</script>

<template>
  <section class="relative overflow-hidden rounded-[2rem] border border-white/10 bg-white/5 px-6 py-10 shadow-[0_24px_80px_rgba(7,9,20,0.38)] backdrop-blur sm:px-10 md:py-14 lg:px-14">
    <div class="absolute inset-0 bg-[radial-gradient(circle_at_top_left,rgba(255,193,170,0.15),transparent_40%),radial-gradient(circle_at_bottom_right,rgba(155,182,255,0.15),transparent_40%)] pointer-events-none"></div>

    <div class="relative flex flex-col md:flex-row md:items-center md:justify-between gap-12 lg:gap-20">
      
      <motion.div
        :initial="{ opacity: 0, y: 22 }"
        :animate="{ opacity: 1, y: 0 }"
        :transition="{ duration: 0.55 }"
        class="flex-1 w-full max-w-2xl"
      >
        <p class="tracking-[0.32em] text-[0.72rem] uppercase text-white/50">Campus Pulse</p>
        
        <h1 class="mt-4 text-3xl font-semibold leading-[1.15] tracking-tight text-white sm:text-4xl lg:text-[3rem] xl:text-[3.5rem]">
          今晚，先从一句话靠近。
        </h1>
        
        <p class="mt-6 max-w-[560px] text-base leading-relaxed text-white/70 sm:text-lg">
          选一个想靠近的人，让聊天从此刻的时间、天气和场景里自然开始。有些关系不是一下子确定的，是在一句句被接住的话里慢慢变近。
        </p>

        <div class="mt-10 flex flex-wrap items-center gap-5">
          <button
            type="button"
            class="rounded-full bg-white px-8 py-3.5 text-[15px] font-medium text-slate-900 transition-all duration-300 hover:-translate-y-1 hover:bg-white hover:shadow-[0_8px_20px_rgba(255,255,255,0.25)] active:translate-y-0"
            @click="emits('start')"
          >
            {{ ctaLabel }}
          </button>
          
          <span class="text-[13px] tracking-wide text-white/40">
            校园夜聊 · 实时场景 · 慢慢靠近
          </span>
        </div>
      </motion.div>

      <motion.article
        :initial="{ opacity: 0, scale: 0.98, x: 18 }"
        :animate="{ opacity: 1, scale: 1, x: 0 }"
        :transition="{ duration: 0.55, delay: 0.08 }"
        class="hero-visual group w-full max-w-[380px] lg:max-w-[420px] shrink-0 cursor-pointer overflow-hidden rounded-[2rem] border border-white/12 bg-[#120f1f]/80 transition-all duration-500 hover:-translate-y-2 hover:shadow-[0_30px_60px_rgba(0,0,0,0.5)] mx-auto md:mx-0"
      >
        <div class="relative aspect-[4/4.8] overflow-hidden">
          <img
            v-if="agent?.coverAsset || agent?.portraitAsset"
            :src="agent?.coverAsset || agent?.portraitAsset"
            :alt="`${agent?.name || '角色'}角色海报`"
            class="h-full w-full object-cover object-center transition-transform duration-700 ease-out group-hover:scale-105"
          />
          <div class="absolute inset-0 bg-gradient-to-t from-[#0d0f1d] via-[#0d0f1d]/50 to-transparent transition-opacity duration-500 group-hover:opacity-90"></div>

          <div class="absolute inset-x-0 bottom-0 p-6 sm:p-8">
            <p class="text-[0.7rem] uppercase tracking-[0.3em] text-white/50">Tonight's Meet</p>
            <h2 class="mt-2 text-[1.75rem] font-semibold tracking-tight text-white sm:text-4xl">
              {{ agent?.name || "选择一个角色" }}
            </h2>
            <p class="mt-3 text-sm leading-relaxed text-white/70">
              {{ agent?.tagline || "今晚，总要有人先把第一句话递出去。" }}
            </p>
            <div class="mt-5 flex flex-wrap gap-2.5">
              <span
                v-for="tag in tags"
                :key="tag"
                class="rounded-full border border-white/10 bg-white/5 px-3.5 py-1.5 text-xs text-white/80 backdrop-blur-md transition-colors duration-300 group-hover:border-white/25 group-hover:text-white"
              >
                {{ tag }}
              </span>
            </div>
          </div>
        </div>
      </motion.article>
      
    </div>
  </section>
</template>
