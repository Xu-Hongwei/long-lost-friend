<script setup lang="ts">
import type { AgentProfile, AnalyticsOverview, ContextSlot, SessionRecord } from "../types";
import MessageStack from "./MessageStack.vue";
import ComposerBar from "./ComposerBar.vue";
import RelationshipMiniPanel from "./RelationshipMiniPanel.vue";
import PlotMiniPanel from "./PlotMiniPanel.vue";
import { formatDateTime, getEndingCandidateLabel, getOnlineLabel } from "../lib/labels";

type QuickJudgeMode = "off" | "smart" | "always";
type PlotPressureMode = "relaxed" | "strict";
const quickJudgeModes: Array<{ value: QuickJudgeMode; label: string }> = [
  { value: "smart", label: "智能巡检" },
  { value: "always", label: "每轮" },
  { value: "off", label: "关闭" }
];
const plotPressureModes: Array<{ value: PlotPressureMode; label: string }> = [
  { value: "relaxed", label: "轻松" },
  { value: "strict", label: "困难" }
];

defineProps<{
  session: SessionRecord | null;
  agent: AgentProfile | null;
  analytics: AnalyticsOverview | null;
  uiMode: "immersive" | "inspector";
  draft: string;
  sending: boolean;
  disabled: boolean;
  quickJudgeMode: QuickJudgeMode;
  quickJudgeEnabled: boolean;
  quickJudgeWaitSeconds: number;
  plotPressureMode: PlotPressureMode;
}>();

const emits = defineEmits<{
  "update:draft": [value: string];
  send: [];
  choose: [choiceId: string];
  setQuickJudgeMode: [mode: QuickJudgeMode];
  setQuickJudgeWaitSeconds: [value: number];
  setPlotPressureMode: [mode: PlotPressureMode];
  updateMemoryPane: [payload: { frozen?: boolean; manualNote?: string }];
  exportDebugData: [];
  toggleDrawer: [drawer: "relationship" | "memory" | "plot" | "analytics"];
}>();

function handleQuickJudgeWaitInput(event: Event) {
  const target = event.target as HTMLInputElement | null;
  emits("setQuickJudgeWaitSeconds", Number(target?.value || 0));
}

function handleMemoryNoteChange(event: Event) {
  const target = event.target as HTMLTextAreaElement | null;
  emits("updateMemoryPane", { manualNote: target?.value || "" });
}

function sceneStatusText(session: SessionRecord | null) {
  if (!session) {
    return "剧情尚未铺开";
  }
  const scene = session.sceneState;
  const summary = scene?.sceneSummary?.trim() || "";
  if (summary && !isTransitionSummary(summary)) {
    return summary;
  }
  const location = scene?.location?.trim() || "";
  const subLocation = scene?.subLocation?.trim() || "";
  if (location && location !== "聊天现场") {
    const place = subLocation && !location.includes(subLocation) ? `${location}${subLocation}` : location;
    return `${place}，${interactionModeLabel(scene?.interactionMode)}。`;
  }
  return session.plotState?.sceneFrame || "剧情尚未铺开";
}

function isTransitionSummary(summary: string) {
  return /场景.*(带到|挪到|移动|转到|换了气氛)/.test(summary)
    || summary.includes("接下来的话也跟着")
    || summary.includes("变成并肩走着");
}

function interactionModeLabel(mode?: string) {
  switch (mode) {
    case "online_chat":
      return "隔着屏幕慢慢聊着";
    case "phone_call":
      return "隔着电话继续靠近";
    case "mixed_transition":
      return "边走边继续聊天";
    case "face_to_face":
    default:
      return "面对面继续聊天";
  }
}

function promptStackSummary(session: SessionRecord | null) {
  const summary = session?.lastPromptSlotSummary || {};
  const total = Number(summary.total || 0);
  const included = Number(summary.included || 0);
  const excluded = Number(summary.excluded || 0);
  const includedTokens = Number(summary.includedTokenBudget || 0);
  return `共 ${total} 个，注入 ${included} 个，裁剪 ${excluded} 个，预算约 ${includedTokens}`;
}

function promptSlotPreview(slot: ContextSlot) {
  const content = slot.content?.trim() || "";
  if (!content) {
    return "暂无内容";
  }
  return content.length > 96 ? `${content.slice(0, 96)}…` : content;
}
</script>

<template>
  <section
    class="min-w-0 gap-5 [overflow-wrap:anywhere]"
    :class="uiMode === 'inspector'
      ? 'grid xl:grid-cols-[minmax(0,1fr),360px]'
      : 'mx-auto block w-full'"
  >
    <div class="min-w-0 w-full rounded-[2rem] border border-white/10 bg-white/6 p-4 shadow-[0_20px_60px_rgba(5,6,18,0.28)] backdrop-blur sm:p-5">
      <div class="flex flex-wrap items-start justify-between gap-4 border-b border-white/8 pb-4">
        <div class="min-w-0">
          <p class="tracking-[0.28em] text-[0.68rem] text-white/44">当前对话</p>
          <h2 class="mt-2 text-2xl font-semibold tracking-[-0.04em] text-white">
            {{ agent ? `${agent.name} · ${agent.archetype}` : "选择角色后开始聊天" }}
          </h2>
          <p class="mt-2 max-w-2xl text-sm leading-6 text-white/64">
            {{ agent?.tagline || "选定角色后，第一句话会从当前场景里自然落下。" }}
          </p>
        </div>

        <div class="min-w-0 flex flex-wrap gap-2 text-xs text-white/62">
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

      <div class="mt-4 grid min-w-0 gap-3 md:grid-cols-2 xl:grid-cols-3">
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
          <div class="mt-2 text-[15px] leading-6 text-white/82">{{ sceneStatusText(session) }}</div>
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
          empty-message="选一个角色，第一句话会在这里慢慢亮起来。"
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
          :scene-summary="sceneStatusText(session)"
          :agent-name="agent?.name"
          @update:model-value="emits('update:draft', $event)"
          @send="emits('send')"
        />
      </div>
    </div>

    <aside v-if="uiMode === 'inspector'" class="min-w-0 space-y-4">
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
        <div class="flex flex-wrap items-center justify-between gap-3">
          <p class="tracking-[0.28em] text-[0.68rem] text-white/45">会话记忆窗格</p>
          <button
            type="button"
            class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/56 transition hover:border-white/20 hover:bg-white/8 hover:text-white/78"
            :disabled="!session"
            @click="emits('updateMemoryPane', { frozen: !session?.memoryPaneState?.frozen })"
          >
            {{ session?.memoryPaneState?.frozen ? "已冻结" : "自动更新" }}
          </button>
        </div>
        <p class="mt-3 text-sm leading-6 text-white/58">
          {{ session?.memoryPaneState?.directorNote || "用于把当前几轮的事实、计划、场景和修正整理成主回复可读取的工作记忆。" }}
        </p>
        <label class="mt-4 block">
          <span class="text-xs text-white/38">手动备注</span>
          <textarea
            class="mt-2 min-h-[4.5rem] w-full resize-none rounded-2xl border border-white/10 bg-black/16 px-3 py-2 text-xs leading-5 text-white/68 outline-none placeholder:text-white/28 focus:border-white/22"
            :value="session?.memoryPaneState?.manualNote || ''"
            placeholder="给本会话固定一条人工提示，比如：别再把图书馆误判成新转场。"
            :disabled="!session"
            @change="handleMemoryNoteChange"
          />
        </label>
        <div class="mt-4 space-y-3 text-xs leading-5 text-white/62">
          <div>
            <div class="mb-2 text-white/38">已确认事实</div>
            <div class="flex flex-wrap gap-2">
              <span
                v-for="item in (session?.memoryPaneState?.pinnedFacts || []).slice(0, 4)"
                :key="item"
                class="rounded-full border border-white/10 bg-black/16 px-3 py-1"
              >
                {{ item }}
              </span>
              <span v-if="!(session?.memoryPaneState?.pinnedFacts || []).length" class="text-white/34">暂无</span>
            </div>
          </div>
          <div>
            <div class="mb-2 text-white/38">本轮工作事实</div>
            <div class="space-y-1">
              <div v-for="item in (session?.memoryPaneState?.workingFacts || []).slice(0, 5)" :key="item">{{ item }}</div>
              <div v-if="!(session?.memoryPaneState?.workingFacts || []).length" class="text-white/34">暂无</div>
            </div>
          </div>
          <div>
            <div class="mb-2 text-white/38">计划 / 场景 / 修正</div>
            <div class="space-y-1">
              <div v-for="item in (session?.memoryPaneState?.pendingPlans || []).slice(0, 3)" :key="`plan-${item}`">{{ item }}</div>
              <div v-for="item in (session?.memoryPaneState?.sceneAnchors || []).slice(0, 3)" :key="`scene-${item}`">{{ item }}</div>
              <div v-for="item in (session?.memoryPaneState?.repairNotes || []).slice(0, 3)" :key="`repair-${item}`" class="text-amber-100/72">{{ item }}</div>
              <div
                v-if="!(session?.memoryPaneState?.pendingPlans || []).length && !(session?.memoryPaneState?.sceneAnchors || []).length && !(session?.memoryPaneState?.repairNotes || []).length"
                class="text-white/34"
              >
                暂无
              </div>
            </div>
          </div>
          <div>
            <div class="mb-2 text-white/38">World Info 候选</div>
            <div class="space-y-1">
              <div
                v-for="item in (session?.memoryPaneState?.loreNotes || []).slice(0, 4)"
                :key="`lore-${item}`"
                class="text-sky-100/68"
              >
                {{ item }}
              </div>
              <div v-if="!(session?.memoryPaneState?.loreNotes || []).length" class="text-white/34">暂无候选背景</div>
            </div>
          </div>
        </div>
      </section>

      <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <p class="tracking-[0.28em] text-[0.68rem] text-white/45">World Info 激活</p>
          <span class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/56">
            {{ session?.worldInfoActivations?.length || 0 }} 条
          </span>
        </div>
        <p class="mt-3 text-xs leading-5 text-white/46">
          这些是可配置背景候选，只帮助主回复理解“可以提什么”，不会直接决定转场或剧情推进。
        </p>
        <details
          v-if="session?.lastPromptRawText"
          class="mt-4 rounded-2xl border border-white/10 bg-black/16 px-4 py-3 text-xs text-white/58"
        >
          <summary class="cursor-pointer select-none text-white/72">Raw prompt preview</summary>
          <pre class="mt-3 max-h-72 max-w-full overflow-auto whitespace-pre-wrap break-words leading-5 text-white/54">{{ session?.lastPromptRawText }}</pre>
        </details>
        <div class="mt-4 space-y-2">
          <div
            v-for="item in (session?.worldInfoActivations || []).slice(0, 4)"
            :key="item.id"
            class="rounded-2xl border border-sky-200/12 bg-sky-200/6 px-3 py-3 text-xs text-white/62"
          >
            <div class="flex flex-wrap items-center justify-between gap-2">
              <span class="font-medium text-sky-50/86">{{ item.title || item.id }}</span>
              <span class="font-mono text-white/42">score {{ item.score ?? 0 }}</span>
            </div>
            <p class="mt-2 leading-5 text-white/46">
              {{ (item.content || '').length > 88 ? `${(item.content || '').slice(0, 88)}…` : item.content }}
            </p>
            <div class="mt-2 flex flex-wrap gap-2 text-[0.65rem] text-white/42">
              <span
                v-for="keyword in (item.matchedKeywords || []).slice(0, 4)"
                :key="`${item.id}-${keyword}`"
                class="rounded-full border border-white/10 bg-black/16 px-2 py-0.5"
              >
                {{ keyword }}
              </span>
              <span v-if="item.eventCandidate" class="rounded-full border border-amber-200/15 bg-amber-200/8 px-2 py-0.5 text-amber-50/70">
                可生成候选事件
              </span>
            </div>
          </div>
          <div v-if="!(session?.worldInfoActivations || []).length" class="rounded-2xl border border-white/10 bg-black/12 px-4 py-3 text-sm text-white/42">
            暂无激活。普通闲聊不会强塞背景，这是有意设计。
          </div>
        </div>
      </section>

      <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <p class="tracking-[0.28em] text-[0.68rem] text-white/45">本轮决策链路</p>
          <button
            type="button"
            class="rounded-full border border-white/10 bg-black/16 px-3 py-1.5 text-xs text-white/62 transition hover:border-white/20 hover:bg-white/8 hover:text-white"
            :disabled="!session"
            @click="emits('exportDebugData')"
          >
            导出调试数据
          </button>
        </div>
        <div class="mt-4 space-y-2 text-sm text-white/64">
          <div class="flex justify-between gap-4"><span class="text-white/42">主意图</span><span>{{ session?.lastIntentState?.primaryIntent || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">回复任务</span><span>{{ session?.lastResponsePlan?.coreTask || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">主动程度</span><span>{{ session?.lastResponsePlan?.initiativeLevel || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">好感变化</span><span>{{ session?.lastTurnContext?.affectionDeltaTotal ?? 0 }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">场景移动意图</span><span>{{ session?.lastTurnContext?.sceneMoveKind || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">本轮任务</span><span>{{ session?.lastTurnContext?.turnMission || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">本轮信号</span><span>{{ session?.lastTurnContext?.plotSignal ?? 0 }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">剧情蓄力</span><span>{{ session?.lastTurnContext?.plotPressure ?? session?.plotArcState?.plotPressure ?? 0 }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">剧情间隔</span><span>{{ session?.lastTurnContext?.plotGap ?? 0 }}</span></div>
        </div>
        <div class="mt-4 rounded-2xl border border-white/10 bg-black/12 p-3">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <span class="text-xs text-white/54">剧情节奏</span>
            <div class="flex rounded-full border border-white/10 bg-black/16 p-1 text-xs">
              <button
                v-for="mode in plotPressureModes"
                :key="mode.value"
                type="button"
                class="rounded-full px-3 py-1.5 transition"
                :class="plotPressureMode === mode.value
                  ? 'bg-amber-200/18 text-amber-50'
                  : 'text-white/48 hover:bg-white/8 hover:text-white/74'"
                @click="emits('setPlotPressureMode', mode.value)"
              >
                {{ mode.label }}
              </button>
            </div>
          </div>
          <p class="mt-3 text-xs leading-5 text-white/46">
            {{ plotPressureMode === "relaxed"
              ? "轻松模式不做剧情蓄力减分；普通闲聊也会 +1，让关系和剧情更容易自然往前走。"
              : "困难模式保留严格衰减；低信号、显式转场或被结构化规则压住时，剧情蓄力可能下降。" }}
          </p>
        </div>
      </section>

      <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <p class="tracking-[0.28em] text-[0.68rem] text-white/45">Prompt Stack Viewer</p>
          <span class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/56">
            {{ promptStackSummary(session) }}
          </span>
        </div>
        <p class="mt-3 text-sm leading-6 text-white/54">
          最新主回复实际使用的上下文槽；`memory.session` 如果在这里显示“已注入”，说明会话记忆窗格已经进入主回复。
        </p>
        <div class="mt-4 space-y-2">
          <div
            v-for="slot in (session?.lastPromptSlotsUsed || [])"
            :key="slot.key"
            class="rounded-2xl border px-3 py-3 text-xs transition"
            :class="slot.included
              ? 'border-emerald-200/14 bg-emerald-200/7 text-white/70'
              : 'border-white/8 bg-black/14 text-white/38'"
          >
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="font-mono text-[0.72rem]" :class="slot.key === 'memory.session' ? 'text-emerald-100' : 'text-white/74'">
                {{ slot.key || "unknown.slot" }}
              </div>
              <div class="flex flex-wrap gap-2 text-[0.65rem]">
                <span class="rounded-full border border-white/10 bg-black/16 px-2 py-0.5">
                  {{ slot.included ? "已注入" : "已裁剪" }}
                </span>
                <span class="rounded-full border border-white/10 bg-black/16 px-2 py-0.5">
                  p{{ slot.priority ?? 0 }}
                </span>
                <span class="rounded-full border border-white/10 bg-black/16 px-2 py-0.5">
                  {{ slot.tokenBudget ?? 0 }}
                </span>
              </div>
            </div>
            <p class="mt-2 leading-5 text-white/46">
              {{ promptSlotPreview(slot) }}
            </p>
          </div>
          <div v-if="!(session?.lastPromptSlotsUsed || []).length" class="rounded-2xl border border-white/10 bg-black/12 px-4 py-3 text-sm text-white/42">
            暂无 Prompt Stack。发送一轮消息后会显示最新主回复使用的上下文槽。
          </div>
        </div>
      </section>

      <section class="rounded-[1.6rem] border border-white/10 bg-white/6 p-5 backdrop-blur">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <p class="tracking-[0.28em] text-[0.68rem] text-white/45">轻判断修正</p>
          <div class="flex rounded-full border border-white/10 bg-black/16 p-1 text-xs">
            <button
              v-for="mode in quickJudgeModes"
              :key="mode.value"
              type="button"
              class="rounded-full px-3 py-1.5 transition"
              :class="quickJudgeMode === mode.value
                ? 'bg-emerald-300/18 text-emerald-50'
                : 'text-white/48 hover:bg-white/8 hover:text-white/74'"
              @click="emits('setQuickJudgeMode', mode.value)"
            >
              {{ mode.label }}
            </button>
          </div>
        </div>
        <p class="mt-3 text-xs leading-5 text-white/46">
          {{ quickJudgeMode === "always" ? "每次回复都尝试远程修正，效果更稳，但可能增加等待；晚到结果会并入下一轮。"
            : quickJudgeMode === "smart" ? "模糊/高价值轮会机会型启动；用户纠错会按下方时间等待；每 4 轮会后台巡检一次且不阻塞，晚到并入下一轮。"
              : "远程轻判断已关闭；系统仍会保留本地修正和事实承接。" }}
        </p>
        <div class="mt-4 rounded-2xl border border-white/10 bg-black/12 p-3">
          <label class="flex items-center justify-between gap-4 text-xs text-white/54">
            <span>最多等待</span>
            <span class="font-mono text-white/72">{{ quickJudgeWaitSeconds.toFixed(2) }}s</span>
          </label>
          <input
            class="mt-3 w-full accent-emerald-200"
            type="range"
            min="0.06"
            max="5"
            step="0.01"
            :value="quickJudgeWaitSeconds"
            :disabled="!quickJudgeEnabled"
            @input="handleQuickJudgeWaitInput"
          />
          <div class="mt-2 flex justify-between text-[0.65rem] text-white/36">
            <span>0.06s</span>
            <span>5.00s</span>
          </div>
        </div>
        <div class="mt-4 flex flex-wrap gap-2">
          <span class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/72">
            状态 {{ session?.lastQuickJudgeStatus?.status || "暂无" }}
          </span>
          <span class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/72">
            置信 {{ session?.lastQuickJudgeStatus?.confidence ?? 0 }}
          </span>
          <span class="rounded-full border border-white/10 bg-black/16 px-3 py-1 text-xs text-white/72">
            已采纳 {{ session?.lastQuickJudgeStatus?.applied ? "是" : "否" }}
          </span>
        </div>
        <div class="mt-4 space-y-2 text-sm text-white/64">
          <div class="flex justify-between gap-4"><span class="text-white/42">回复优先级</span><span>{{ session?.lastQuickJudgeStatus?.replyPriority || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">修正主意图</span><span>{{ session?.lastQuickJudgeStatus?.primaryIntent || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">情绪覆盖</span><span>{{ session?.lastQuickJudgeStatus?.emotion || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">共享目标</span><span>{{ session?.lastQuickJudgeStatus?.sharedObjective || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">本地分数</span><span>{{ session?.lastQuickJudgeStatus?.triggerScore ?? 0 }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">触发原因</span><span class="text-right">{{ session?.lastQuickJudgeStatus?.triggerReasons?.join(" · ") || "暂无" }}</span></div>
          <div class="flex justify-between gap-4"><span class="text-white/42">抑制原因</span><span class="text-right">{{ session?.lastQuickJudgeStatus?.suppressedReasons?.join(" · ") || "暂无" }}</span></div>
        </div>
        <div class="mt-4 rounded-2xl border border-white/10 bg-black/12 px-4 py-3 text-sm leading-6 text-white/62">
          {{ session?.lastQuickJudgeStatus?.nextBestMove || session?.lastQuickJudgeStatus?.reason || "当前这一轮没有额外的轻判断修正。" }}
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
