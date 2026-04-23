<script setup lang="ts">
import { motion } from "motion-v";
import type { AgentProfile } from "../types";

defineProps<{
  agents: AgentProfile[];
  selectedAgentId: string;
  activeSessionAgentId?: string;
}>();

const emits = defineEmits<{
  select: [agentId: string];
  start: [agentId: string];
}>();
</script>

<template>
  <section class="space-y-4">
    <div class="flex items-end justify-between gap-4">
      <div>
        <p class="tracking-[0.28em] text-[0.72rem] uppercase text-white/45">Character Rail</p>
        <h2 class="mt-2 text-2xl font-semibold tracking-[-0.04em] text-white sm:text-[2rem]">
          先挑一个你会想多看两眼的人
        </h2>
      </div>
      <p class="hidden max-w-md text-sm leading-6 text-white/58 xl:block">
        角色卡先用图像、色温和标签建立第一眼吸引力，再把你带进今晚的聊天。
      </p>
    </div>

    <div class="no-scrollbar flex gap-4 overflow-x-auto pb-2">
      <motion.button
        v-for="agent in agents"
        :key="agent.id"
        type="button"
        :initial="{ opacity: 0, y: 20 }"
        :animate="{ opacity: 1, y: 0 }"
        :transition="{ duration: 0.32 }"
        class="group relative min-w-[250px] flex-1 overflow-hidden rounded-[1.75rem] border text-left transition duration-300 hover:-translate-y-1"
        :class="selectedAgentId === agent.id
          ? 'border-white/20 bg-white/10 shadow-[0_22px_50px_rgba(8,10,26,0.38)]'
          : 'border-white/8 bg-white/5'"
        @click="emits('select', agent.id)"
      >
        <div class="absolute inset-0 bg-gradient-to-br from-white/10 to-transparent opacity-0 transition group-hover:opacity-100"></div>
        <div class="relative flex h-full flex-col">
          <div class="relative aspect-[4/5] overflow-hidden">
            <img
              v-if="agent.portraitAsset"
              :src="agent.portraitAsset"
              :alt="`${agent.name}角色立绘`"
              class="h-full w-full object-cover object-center"
            />
            <div
              class="absolute inset-0"
              :style="{ background: `linear-gradient(180deg, transparent 0%, ${agent.palette?.[2] || '#23192a'} 100%)` }"
            ></div>
          </div>

          <div class="flex flex-1 flex-col p-4">
            <div class="flex items-start justify-between gap-3">
              <div>
                <p class="text-[0.72rem] uppercase tracking-[0.28em] text-white/42">{{ agent.archetype }}</p>
                <h3 class="mt-2 text-xl font-semibold tracking-[-0.04em] text-white">{{ agent.name }}</h3>
                <p v-if="agent.backstory" class="mt-1 text-xs leading-5 text-white/50">
                  {{ agent.backstory.grade }} · {{ agent.backstory.major }} · {{ agent.backstory.hometown }}
                </p>
                <p class="mt-2 text-sm leading-6 text-white/68">{{ agent.tagline }}</p>
              </div>
              <span v-if="activeSessionAgentId === agent.id" class="rounded-full bg-white/12 px-3 py-1 text-[11px] text-white/72">
                续聊中
              </span>
            </div>

            <div class="mt-4 flex flex-wrap gap-2">
              <span
                v-for="tag in (agent.styleTags || agent.likes || []).slice(0, 3)"
                :key="tag"
                class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/72"
              >
                {{ tag }}
              </span>
            </div>

            <button
              type="button"
              class="mt-auto inline-flex items-center self-start rounded-full border border-white/12 px-4 py-2 text-sm text-white/78 transition hover:border-white/24 hover:bg-white/6"
              @click.stop="emits('start', agent.id)"
            >
              {{ activeSessionAgentId === agent.id ? "回到聊天" : "选她开场" }}
            </button>
          </div>
        </div>
      </motion.button>
    </div>
  </section>
</template>
