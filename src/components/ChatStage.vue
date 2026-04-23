<script setup lang="ts">
import type { AgentProfile, AnalyticsOverview, SessionRecord } from "../types";
import MessageStack from "./MessageStack.vue";
import ComposerBar from "./ComposerBar.vue";
import RelationshipMiniPanel from "./RelationshipMiniPanel.vue";
import PlotMiniPanel from "./PlotMiniPanel.vue";
import { formatDateTime, getEndingCandidateLabel, getOnlineLabel } from "../lib/labels";

defineProps<{
  session: SessionRecord | null;
  agent: AgentProfile | null;
  analytics: AnalyticsOverview | null;
  uiMode: "immersive" | "inspector";
  draft: string;
  sending: boolean;
  disabled: boolean;
}>();

const emits = defineEmits<{
  "update:draft": [value: string];
  send: [];
  choose: [choiceId: string];
  toggleDrawer: [drawer: "relationship" | "memory" | "plot" | "analytics"];
}>();
</script>

<template>
  <section class="grid gap-5 xl:grid-cols-[minmax(0,980px),360px] xl:justify-between">
    <div class="rounded-[2rem] border border-white/10 bg-white/6 p-4 shadow-[0_20px_60px_rgba(5,6,18,0.28)] backdrop-blur sm:p-5 xl:max-w-[980px]">
      <div class="flex flex-wrap items-start justify-between gap-4 border-b border-white/8 pb-4">
        <div>
          <p class="tracking-[0.28em] text-[0.68rem] text-white/44">聊天舞台</p>
          <h2 class="mt-2 text-2xl font-semibold tracking-[-0.04em] text-white">
            {{ agent ? `${agent.name} · ${agent.archetype}` : "选择角色后开始聊天" }}
          </h2>
          <p class="mt-2 max-w-2xl text-sm leading-6 text-white/64">
            {{ agent?.tagline || "选定角色后，聊天会从当前场景自然铺开。" }}
          </p>
        </div>

        <div class="flex flex-wrap gap-2 text-xs text-white/62">
          <span class="rounded-full border border-white/10 bg-black/16 px-3 py-2">
            {{ getOnlineLabel(session?.presenceState?.online, session?.presenceState?.typing) }}
          </span>
          <span class="rounded-full border border-white/10 bg-black/16 px-3 py-2">
            {{ session?.relationshipState?.relationshipStage || "尚未开始" }}
          </span>
          <span class="rounded-full border border-white/10 bg-black/16 px-3 py-2">
            记忆至 {{ formatDateTime(session?.memoryExpireAt) }}
          </span>
        </div>
      </div>

      <div class="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        <div class="rounded-[1.35rem] border border-white/8 bg-black/12 px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.2em] text-white/38">当前时间</div>
          <div class="mt-2 text-[15px] leading-6 text-white/82">{{ session?.timeContext?.label || "等待会话建立" }}</div>
        </div>
        <div class="rounded-[1.35rem] border border-white/8 bg-black/12 px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.2em] text-white/38">天气氛围</div>
          <div class="mt-2 text-[15px] leading-6 text-white/82">{{ session?.weatherContext?.summary || "尚未设置城市" }}</div>
        </div>
        <div class="rounded-[1.35rem] border border-white/8 bg-black/12 px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.2em] text-white/38">当前场景</div>
          <div class="mt-2 text-[15px] leading-6 text-white/82">{{ session?.sceneState?.sceneSummary || session?.plotState?.sceneFrame || "剧情尚未铺开" }}</div>
        </div>
      </div>

      <div class="mt-4 flex flex-wrap gap-2" v-if="uiMode === 'immersive'">
        <button
          type="button"
          class="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-xs text-white/68"
          @click="emits('toggleDrawer', 'relationship')"
        >
          关系
        </button>
        <button
          type="button"
          class="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-xs text-white/68"
          @click="emits('toggleDrawer', 'plot')"
        >
          剧情
        </button>
        <button
          type="button"
          class="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-xs text-white/68"
          @click="emits('toggleDrawer', 'memory')"
        >
          记忆
        </button>
        <button
          type="button"
          class="rounded-full border border-white/10 bg-white/5 px-4 py-2 text-xs text-white/68"
          @click="emits('toggleDrawer', 'analytics')"
        >
          数据
        </button>
      </div>

      <div class="mt-5">
        <MessageStack
          :messages="session?.history || []"
          :agent="agent"
          empty-message="从上面选一个角色，今晚的第一句开场白会自然落下来。"
        />
      </div>

      <section
        v-if="session?.pendingChoices?.length"
        class="mt-5 rounded-[1.55rem] border border-[#f5cfb7]/14 bg-[linear-gradient(180deg,rgba(255,226,213,0.08),rgba(255,255,255,0.04))] p-4"
      >
        <p class="tracking-[0.24em] text-[0.68rem] text-[#f3c9b1]/62">关键选择</p>
        <h3 class="mt-2 text-lg font-semibold text-white">关键剧情节点</h3>
        <p class="mt-2 text-sm leading-6 text-white/64">
          {{ session.pendingEventContext || "这一次你的回应会影响接下来的关系节奏。" }}
        </p>
        <div class="mt-4 flex flex-wrap gap-3">
          <button
            v-for="choice in session.pendingChoices"
            :key="choice.id"
            type="button"
            class="rounded-full border border-white/12 bg-black/16 px-4 py-2 text-sm text-white/78 transition hover:bg-white/8"
            @click="emits('choose', choice.id)"
          >
            {{ choice.label }}
          </button>
        </div>
      </section>

      <div class="mt-5">
        <ComposerBar
          :model-value="draft"
          :disabled="disabled"
          :loading="sending"
          :city="session?.visitorContext?.preferredCity"
          :scene-summary="session?.sceneState?.sceneSummary || session?.plotState?.sceneFrame"
          :agent-name="agent?.name"
          @update:model-value="emits('update:draft', $event)"
          @send="emits('send')"
        />
      </div>
    </div>

    <aside v-if="uiMode === 'inspector'" class="space-y-4">
      <RelationshipMiniPanel :relationship="session?.relationshipState || null" />
      <PlotMiniPanel
        :plot-state="session?.plotState || null"
        :plot-arc-state="session?.plotArcState || null"
        :story-event-progress="session?.storyEventProgress || null"
        :presence-state="session?.presenceState || null"
        :plot-gate="session?.lastPlotGateDecision || null"
        :turn-context="session?.lastTurnContext || null"
        :plot-director-decision="session?.lastTurnContext?.plotDirectorAction || ''"
      />

      <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
        <p class="tracking-[0.28em] text-[0.68rem] text-white/45">记忆与回调</p>
        <p class="mt-3 text-sm leading-6 text-white/64">
          {{ session?.memorySummary?.lastMemoryRelevanceReason || "当前会优先承接最近场景、明确事实和未完成话题。" }}
        </p>
        <div class="mt-4 flex flex-wrap gap-2">
          <span
            v-for="item in (session?.memorySummary?.callbackCandidates || []).slice(0, 3)"
            :key="item"
            class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/66"
          >
            {{ item }}
          </span>
        </div>
      </section>

      <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
        <p class="tracking-[0.28em] text-[0.68rem] text-white/45">本轮智能体闭环</p>
        <div class="mt-4 space-y-2 text-sm text-white/64">
          <div class="flex justify-between gap-4"><span class="text-white/42">主意图</span><span>{{ session?.lastIntentState?.primaryIntent || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">回复任务</span><span>{{ session?.lastResponsePlan?.coreTask || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">主动程度</span><span>{{ session?.lastResponsePlan?.initiativeLevel || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">好感变化</span><span>{{ session?.lastTurnContext?.affectionDeltaTotal ?? 0 }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">剧情信号</span><span>{{ session?.lastTurnContext?.plotSignal ?? 0 }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">剧情间隔</span><span>{{ session?.lastTurnContext?.plotGap ?? 0 }}</span></div>
        </div>
      </section>

      <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
        <p class="tracking-[0.28em] text-[0.68rem] text-white/45">上下文智能层</p>
        <div class="mt-4 space-y-2 text-sm text-white/64">
          <div class="flex justify-between gap-4"><span class="text-white/42">共同目标</span><span>{{ session?.dialogueContinuityState?.currentObjective || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">已确认计划</span><span>{{ session?.dialogueContinuityState?.acceptedPlan || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">下一步承接</span><span>{{ session?.dialogueContinuityState?.nextBestMove || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">需要转场</span><span>{{ session?.dialogueContinuityState?.sceneTransitionNeeded ? "是" : "否" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">置信度</span><span>{{ session?.dialogueContinuityState?.confidence ?? 0 }}</span></div>
        </div>
      </section>

      <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
        <p class="tracking-[0.28em] text-[0.68rem] text-white/45">现实上下文</p>
        <div class="mt-4 space-y-2 text-sm text-white/64">
          <div class="flex justify-between gap-4"><span class="text-white/42">地点</span><span>{{ session?.sceneState?.location || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">互动模式</span><span>{{ session?.sceneState?.interactionMode || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">时间</span><span>{{ session?.timeContext?.label || session?.timeContext?.phase || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">天气城市</span><span>{{ session?.weatherContext?.city || "未设置" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">天气</span><span>{{ session?.weatherContext?.summary || "未设置" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">实时天气</span><span>{{ session?.weatherContext?.live ? "是" : "否" }}</span></div>
        </div>
      </section>

      <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
        <p class="tracking-[0.28em] text-[0.68rem] text-white/45">情绪与张力</p>
        <div class="mt-4 grid grid-cols-2 gap-3 text-sm text-white/64">
          <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">温度 {{ session?.emotionState?.warmth ?? 0 }}</div>
          <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">安全感 {{ session?.emotionState?.safety ?? 0 }}</div>
          <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">主动性 {{ session?.emotionState?.initiative ?? 0 }}</div>
          <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">防备 {{ session?.tensionState?.guarded ? "是" : "否" }}</div>
        </div>
      </section>

      <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
        <p class="tracking-[0.28em] text-[0.68rem] text-white/45">试玩数据</p>
        <div class="mt-4 grid grid-cols-2 gap-3">
          <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">
            <div class="text-[11px] uppercase tracking-[0.2em] text-white/40">UV</div>
            <div class="mt-2 text-lg font-semibold text-white">{{ analytics?.visitorCount ?? "--" }}</div>
          </div>
          <div class="rounded-2xl border border-white/10 bg-black/12 px-3 py-3">
            <div class="text-[11px] uppercase tracking-[0.2em] text-white/40">平均轮次</div>
            <div class="mt-2 text-lg font-semibold text-white">{{ analytics?.avgTurns ?? "--" }}</div>
          </div>
        </div>
        <div class="mt-4 text-sm leading-6 text-white/58">
          当前结局倾向：{{ getEndingCandidateLabel(session?.relationshipState?.endingCandidate) }}
        </div>
      </section>
    </aside>
  </section>
</template>
