<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from "vue";
import { AnimatePresence, motion } from "motion-v";
import type { AgentProfile, ConversationMessage } from "../types";
import { formatTime, getReplySourceLabel } from "../lib/labels";

const props = defineProps<{
  messages: ConversationMessage[];
  agent: AgentProfile | null;
  emptyMessage?: string;
}>();

const container = ref<HTMLElement | null>(null);

async function scrollToBottom() {
  await nextTick();
  if (container.value) {
    container.value.scrollTop = container.value.scrollHeight;
  }
}

watch(() => props.messages.length, async () => {
  await scrollToBottom();
});

onMounted(async () => {
  await scrollToBottom();
});
</script>

<template>
  <div ref="container" class="no-scrollbar flex h-[clamp(480px,58vh,720px)] flex-col gap-4 overflow-y-auto pr-1">
    <div v-if="!messages.length" class="rounded-[1.6rem] border border-dashed border-white/12 bg-white/4 px-5 py-8 text-center text-sm leading-7 text-white/56">
      {{ emptyMessage || "从上面选择一个角色，今晚的对话会从第一句开场白慢慢长出来。" }}
    </div>

    <AnimatePresence>
      <motion.div
        v-for="message in messages"
        :key="message.id"
        :initial="{ opacity: 0, y: 20 }"
        :animate="{ opacity: 1, y: 0 }"
        :exit="{ opacity: 0, y: -8 }"
        :transition="{ duration: 0.26 }"
        class="flex w-full flex-col gap-2"
        :class="message.role === 'user' ? 'items-end' : 'items-start'"
      >
        <div
          v-if="message.sceneText"
          class="max-w-[88%] rounded-full border border-white/8 bg-white/6 px-4 py-2 text-left text-xs leading-6 tracking-[0.12em] text-white/50"
          :class="message.role === 'user' ? 'ml-auto' : 'ml-2'"
        >
          {{ message.sceneText }}
        </div>

        <div
          v-if="message.actionText"
          class="min-w-[8.5rem] max-w-[88%] rounded-2xl border border-white/10 bg-[#171926]/80 px-4 py-3 text-sm leading-6 text-white/64 sm:min-w-[10rem]"
          :class="message.role === 'user' ? 'ml-auto' : ''"
        >
          {{ message.actionText }}
        </div>

        <div
          class="min-w-[8.5rem] max-w-[88%] overflow-hidden rounded-[1.6rem] border px-4 py-4 shadow-[0_16px_36px_rgba(4,6,18,0.26)] sm:min-w-[10rem] sm:px-5"
          :class="message.role === 'user'
            ? 'ml-auto border-[#4f6c93]/30 bg-[linear-gradient(160deg,rgba(58,78,109,0.92),rgba(27,39,60,0.96))] text-white'
            : 'border-[#6b5a57]/30 bg-[linear-gradient(160deg,rgba(70,50,54,0.94),rgba(36,32,46,0.96))] text-white'"
        >
          <div class="flex items-start justify-between gap-4">
            <div class="min-w-0 flex-1">
              <div class="text-xs text-white/48">{{ message.role === "user" ? "你" : agent?.name || "角色" }}</div>
              <div class="mt-2 whitespace-pre-wrap break-words text-[15px] leading-7 text-white/94 sm:text-[16px]">
                {{ message.speechText || message.text }}
              </div>
            </div>

            <div class="shrink-0 space-y-1 text-right">
              <div class="text-xs text-white/48">{{ formatTime(message.createdAt) }}</div>
              <div v-if="message.replySource && message.role === 'assistant'" class="text-[11px] text-white/38">
                {{ getReplySourceLabel(message.replySource) }}
              </div>
            </div>
          </div>
        </div>
      </motion.div>
    </AnimatePresence>
  </div>
</template>
