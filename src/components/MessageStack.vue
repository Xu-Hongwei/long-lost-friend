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
  <div ref="container" class="no-scrollbar flex min-h-[420px] flex-col gap-4 overflow-y-auto pr-1">
    <div v-if="!messages.length" class="rounded-[1.6rem] border border-dashed border-white/12 bg-white/4 px-5 py-8 text-center text-sm leading-7 text-white/56">
      {{ emptyMessage || "从上方选择一个角色，今晚的对话会从第一句开场白慢慢长出来。" }}
    </div>

    <AnimatePresence>
      <motion.div
        v-for="message in messages"
        :key="message.id"
        :initial="{ opacity: 0, y: 20 }"
        :animate="{ opacity: 1, y: 0 }"
        :exit="{ opacity: 0, y: -8 }"
        :transition="{ duration: 0.26 }"
        class="space-y-2"
        :class="message.role === 'user' ? 'items-end self-end' : 'items-start self-start'"
      >
        <div
          v-if="message.sceneText"
          class="mx-auto max-w-[92%] rounded-full border border-white/8 bg-white/6 px-4 py-2 text-center text-xs leading-6 tracking-[0.18em] text-white/48"
        >
          {{ message.sceneText }}
        </div>

        <div
          v-if="message.actionText"
          class="max-w-[78%] rounded-2xl border border-white/10 bg-[#171926]/80 px-4 py-3 text-sm leading-6 text-white/64"
          :class="message.role === 'user' ? 'ml-auto' : ''"
        >
          {{ message.actionText }}
        </div>

        <div
          class="max-w-[82%] overflow-hidden rounded-[1.6rem] border px-4 py-4 shadow-[0_16px_36px_rgba(4,6,18,0.26)] sm:px-5"
          :class="message.role === 'user'
            ? 'ml-auto border-[#4f6c93]/30 bg-[linear-gradient(160deg,rgba(58,78,109,0.92),rgba(27,39,60,0.96))] text-white'
            : 'border-[#6b5a57]/30 bg-[linear-gradient(160deg,rgba(70,50,54,0.94),rgba(36,32,46,0.96))] text-white'"
        >
          <div class="flex items-start justify-between gap-4">
            <div>
              <div class="text-xs text-white/48">{{ message.role === "user" ? "你" : agent?.name || "角色" }}</div>
              <div class="mt-2 whitespace-pre-wrap text-[15px] leading-7 text-white/94">
                {{ message.speechText || message.text }}
              </div>
            </div>
            <div class="space-y-1 text-right">
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
