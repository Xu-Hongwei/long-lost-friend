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
</script>

<template>
  <section class="relative overflow-hidden rounded-[2rem] border border-white/10 bg-white/6 px-5 py-6 shadow-[0_24px_80px_rgba(7,9,20,0.38)] backdrop-blur sm:px-6 xl:px-8">
    <div class="absolute inset-0 bg-[radial-gradient(circle_at_top_left,rgba(255,193,170,0.24),transparent_30%),radial-gradient(circle_at_bottom_right,rgba(155,182,255,0.24),transparent_30%)]"></div>

    <div class="relative grid gap-6 lg:grid-cols-[minmax(0,1.18fr),minmax(320px,440px)] lg:items-end xl:gap-8">
      <motion.div
        :initial="{ opacity: 0, y: 22 }"
        :animate="{ opacity: 1, y: 0 }"
        :transition="{ duration: 0.55 }"
        class="max-w-3xl"
      >
        <p class="tracking-[0.32em] text-[0.72rem] uppercase text-white/50">Campus Pulse</p>
        <h1 class="mt-3 max-w-[11ch] text-4xl font-semibold leading-[0.92] tracking-[-0.05em] text-white sm:text-5xl xl:text-7xl">
          今晚先心动，再慢慢靠近。
        </h1>
        <p class="mt-6 max-w-[690px] text-sm leading-7 text-white/72 sm:text-base">
          选一个想靠近的人，让聊天从此刻的时间、天气和场景里自然开始。有些关系不是一下子确定的，是在一句句被接住的话里慢慢变近。
        </p>

        <div class="mt-7 flex flex-wrap items-center gap-3">
          <button
            type="button"
            class="rounded-full bg-white px-5 py-3 text-sm font-medium text-slate-900 transition hover:-translate-y-0.5 hover:bg-[#fff4ef]"
            @click="emits('start')"
          >
            {{ session ? `继续和 ${session.agent.name} 聊` : agent ? `和 ${agent.name} 开始今晚` : "开始聊天" }}
          </button>
          <span class="rounded-full border border-white/12 bg-white/6 px-4 py-2 text-xs text-white/64">
            校园夜聊 · 实时场景 · 慢慢靠近
          </span>
        </div>
      </motion.div>

      <motion.article
        :initial="{ opacity: 0, scale: 0.98, x: 18 }"
        :animate="{ opacity: 1, scale: 1, x: 0 }"
        :transition="{ duration: 0.55, delay: 0.08 }"
        class="hero-visual mx-auto w-full max-w-[440px] overflow-hidden rounded-[1.85rem] border border-white/12 bg-[#120f1f]/80 lg:justify-self-end"
      >
        <div class="relative aspect-[4/4.45] overflow-hidden">
          <img
            v-if="agent?.coverAsset || agent?.portraitAsset"
            :src="agent?.coverAsset || agent?.portraitAsset"
            :alt="`${agent?.name || '角色'}角色海报`"
            class="h-full w-full object-cover object-center"
          />
          <div class="absolute inset-0 bg-gradient-to-t from-[#0d0f1d] via-transparent to-transparent"></div>

          <div class="absolute inset-x-0 bottom-0 p-5 sm:p-6">
            <p class="text-[0.7rem] uppercase tracking-[0.3em] text-white/52">Tonight's Meet</p>
            <h2 class="mt-2 text-2xl font-semibold tracking-[-0.04em] text-white sm:text-[2rem]">
              {{ agent?.name || "选择一个角色" }}
            </h2>
            <p class="mt-2 text-sm leading-6 text-white/74">
              {{ agent?.tagline || "今晚，总要有人先把第一句话递出去。" }}
            </p>
            <div class="mt-4 flex flex-wrap gap-2">
              <span
                v-for="tag in tags"
                :key="tag"
                class="rounded-full border border-white/12 bg-black/16 px-3 py-1 text-xs text-white/72"
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
