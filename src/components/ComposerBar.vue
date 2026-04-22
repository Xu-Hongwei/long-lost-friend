<script setup lang="ts">
defineProps<{
  modelValue: string;
  disabled?: boolean;
  loading?: boolean;
  city?: string;
  sceneSummary?: string;
  agentName?: string;
}>();

const emits = defineEmits<{
  "update:modelValue": [value: string];
  send: [];
}>();
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
