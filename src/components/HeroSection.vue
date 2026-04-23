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
        <p class="mt-5 max-w-2xl text-sm leading-7 text-white/72 sm:text-base">
          主页不再让一张大图占满视线，而是把角色吸引力、聊天入口和氛围感放在一个更轻、更顺眼的自适应布局里。你能先被角色打动，再自然进入今晚的聊天。
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
            轻甜电影感 · 自适应布局 · 沉浸式夜聊
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
            <p class="text-[0.7rem] uppercase tracking-[0.3em] text-white/52">Tonight's Highlight</p>
            <h2 class="mt-2 text-2xl font-semibold tracking-[-0.04em] text-white sm:text-[2rem]">
              {{ agent?.name || "选择一个角色" }}
            </h2>
            <p class="mt-2 text-sm leading-6 text-white/74">
              {{ agent?.tagline || "今晚的氛围还在等一个人先开口。" }}
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
