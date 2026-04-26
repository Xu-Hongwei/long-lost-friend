<script setup lang="ts">
import { motion } from "motion-v";
import type { AgentProfile } from "../types";

const props = defineProps<{
  agents: AgentProfile[];
  selectedAgentId: string;
  activeSessionAgentId?: string;
  activeSessionUserTurnCount?: number;
}>();

const emits = defineEmits<{
  select: [agentId: string];
  start: [agentId: string];
}>();

function hasStartedChat(agentId: string) {
  return props.activeSessionAgentId === agentId && (props.activeSessionUserTurnCount || 0) > 0;
}

function startLabel(agent: AgentProfile) {
  return hasStartedChat(agent.id) ? "回到聊天" : `选${agent.objectPronoun || "TA"}开场`;
}
</script>

<template>
  <section class="space-y-4">
    <div class="flex items-end justify-between gap-4">
      <div>
        <p class="tracking-[0.28em] text-[0.72rem] uppercase text-white/45">Tonight's Cast</p>
        <h2 class="mt-2 text-2xl font-semibold tracking-[-0.04em] text-white sm:text-[2rem]">
          选一个今晚想遇见的人
        </h2>
      </div>
      <p class="hidden max-w-md text-sm leading-6 text-white/58 xl:block">
        每个角色都有自己的节奏、边界和靠近方式。先看一眼，再决定今晚从谁开始。
      </p>
    </div>

    <div class="flex snap-x snap-mandatory gap-4 overflow-x-auto pb-4 pr-3 [scrollbar-color:rgba(255,255,255,0.34)_rgba(255,255,255,0.08)] [scrollbar-width:thin]">
      <motion.button
        v-for="agent in agents"
        :key="agent.id"
        type="button"
        :initial="{ opacity: 0, y: 20 }"
        :animate="{ opacity: 1, y: 0 }"
        :transition="{ duration: 0.32 }"
        class="group relative min-w-[250px] flex-[0_0_clamp(250px,19vw,292px)] snap-start overflow-hidden rounded-[1.75rem] border text-left transition duration-300 hover:-translate-y-1"
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
              <span v-if="hasStartedChat(agent.id)" class="rounded-full bg-white/12 px-3 py-1 text-[11px] text-white/72">
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
              {{ startLabel(agent) }}
            </button>
          </div>
        </div>
      </motion.button>
    </div>
  </section>
</template>
