<script setup lang="ts">
import { computed } from "vue";
import type {
  AnalyticsOverview,
  DialogueContinuityState,
  EmotionState,
  HumanizationAudit,
  IntentState,
  MemorySummary,
  PlotArcState,
  PlotGateDecision,
  PlotState,
  PresenceState,
  RealityAudit,
  RelationshipState,
  ResponsePlan,
  SceneState,
  StoryEventProgress,
  TensionState,
  TimeContext,
  TurnContext,
  WeatherContext
} from "../types";
import { getCadenceLabel, getEndingCandidateLabel, getIntentLabel, getMoodLabel } from "../lib/labels";

const props = defineProps<{
  open: boolean;
  title: string;
  relationship: RelationshipState | null;
  memory: MemorySummary | null;
  plot: StoryEventProgress | null;
  plotState: PlotState | null;
  plotArcState: PlotArcState | null;
  analytics: AnalyticsOverview | null;
  presence: PresenceState | null;
  sceneState: SceneState | null;
  timeContext: TimeContext | null;
  weatherContext: WeatherContext | null;
  emotionState: EmotionState | null;
  intentState: IntentState | null;
  responsePlan: ResponsePlan | null;
  humanizationAudit: HumanizationAudit | null;
  realityAudit: RealityAudit | null;
  plotGate: PlotGateDecision | null;
  turnContext: TurnContext | null;
  dialogueContinuity: DialogueContinuityState | null;
  tensionState: TensionState | null;
}>();

const emits = defineEmits<{
  close: [];
}>();

const text = {
  eyebrow: "\u89c2\u5bdf\u9762\u677f",
  close: "\u5173\u95ed",
  empty: "\u6682\u65e0",
  yes: "\u662f",
  no: "\u5426"
};

function valueOrEmpty(value?: string | number | boolean | null) {
  if (value === undefined || value === null || value === "") return text.empty;
  if (typeof value === "boolean") return value ? text.yes : text.no;
  return String(value);
}

function joinList(values?: string[]) {
  return values && values.length ? values.join(" / ") : text.empty;
}

const relationshipRows = computed(() => [
  ["\u5f53\u524d\u9636\u6bb5", valueOrEmpty(props.relationship?.relationshipStage)],
  ["\u603b\u597d\u611f", valueOrEmpty(props.relationship?.affectionScore)],
  ["\u4eb2\u8fd1 / \u4fe1\u4efb / \u9ed8\u5951", `${props.relationship?.closeness ?? 0} / ${props.relationship?.trust ?? 0} / ${props.relationship?.resonance ?? 0}`],
  ["\u7ed3\u5c40\u503e\u5411", getEndingCandidateLabel(props.relationship?.endingCandidate)],
  ["\u5173\u7cfb\u53cd\u9988", valueOrEmpty(props.relationship?.relationshipFeedback || props.relationship?.stageProgressHint)]
]);

const plotRows = computed(() => [
  ["\u5267\u60c5\u62cd\u6570", valueOrEmpty(props.plotArcState?.beatIndex ?? props.plotState?.beatIndex)],
  ["\u5267\u60c5\u6bb5\u843d", valueOrEmpty(props.plotArcState?.arcIndex)],
  ["\u5267\u60c5\u72b6\u6001", valueOrEmpty(props.plotArcState?.runStatus)],
  ["\u5267\u60c5\u52a8\u4f5c", valueOrEmpty(props.turnContext?.plotDirectorAction)],
  ["\u5267\u60c5\u7f6e\u4fe1\u5ea6", valueOrEmpty(props.turnContext?.plotDirectorConfidence)],
  ["\u672c\u8f6e\u4fe1\u53f7", valueOrEmpty(props.turnContext?.plotSignal)],
  ["\u5267\u60c5\u84c4\u529b", valueOrEmpty(props.turnContext?.plotPressure)],
  ["\u4fe1\u53f7\u62c6\u5206", `scene ${props.turnContext?.plotSceneSignal ?? 0} / relation ${props.turnContext?.plotRelationshipSignal ?? 0} / event ${props.turnContext?.plotEventSignal ?? 0} / continuity ${props.turnContext?.plotContinuitySignal ?? 0} / risk ${props.turnContext?.plotRiskSignal ?? 0}`],
  ["\u95e8\u63a7\u539f\u56e0", valueOrEmpty(props.plotGate?.triggerReason || props.plotGate?.blockedReason)],
  ["\u4e0b\u4e00\u65b9\u5411", valueOrEmpty(props.plot?.nextExpectedDirection || props.plotState?.nextBeatHint)],
  ["\u63a8\u8fdb\u98ce\u9669", valueOrEmpty(props.turnContext?.plotRiskIfAdvance)],
  ["\u8fd8\u7f3a\u4fe1\u53f7", valueOrEmpty(props.turnContext?.requiredUserSignal)]
]);

const contextRows = computed(() => [
  ["\u573a\u666f\u4f4d\u7f6e", valueOrEmpty(props.sceneState?.location)],
  ["\u5b50\u573a\u666f", valueOrEmpty(props.sceneState?.subLocation)],
  ["\u4e92\u52a8\u6a21\u5f0f", valueOrEmpty(props.sceneState?.interactionMode)],
  ["\u573a\u666f\u6982\u8981", valueOrEmpty(props.sceneState?.sceneSummary)],
  ["\u5f53\u524d\u65f6\u95f4", valueOrEmpty(props.timeContext?.label || props.timeContext?.phase)],
  ["\u5929\u6c14\u57ce\u5e02", valueOrEmpty(props.weatherContext?.city)],
  ["\u5929\u6c14\u6458\u8981", valueOrEmpty(props.weatherContext?.summary)],
  ["\u5929\u6c14\u6765\u6e90", props.weatherContext?.live ? "\u5b9e\u65f6" : "\u56de\u9000 / \u672a\u8bbe\u7f6e"]
]);

const memoryRows = computed(() => [
  ["\u6700\u8fd1\u60c5\u7eea", getMoodLabel(props.memory?.lastUserMood)],
  ["\u6700\u8fd1\u610f\u56fe", getIntentLabel(props.memory?.lastUserIntent)],
  ["\u56de\u590d\u8282\u594f", getCadenceLabel(props.memory?.lastResponseCadence)],
  ["\u8bb0\u5fc6\u7b56\u7565", valueOrEmpty(props.memory?.lastMemoryUseMode)],
  ["\u8bb0\u5fc6\u539f\u56e0", valueOrEmpty(props.memory?.lastMemoryRelevanceReason)],
  ["\u56de\u8c03\u5019\u9009", joinList((props.memory?.callbackCandidates || []).slice(0, 4))],
  ["\u672a\u5b8c\u6210\u8bdd\u9898", joinList((props.memory?.openLoopItems || []).slice(0, 4).map((item) => item.summary || ""))]
]);

const runtimeRows = computed(() => [
  ["\u4e3b\u610f\u56fe", valueOrEmpty(props.intentState?.primaryIntent)],
  ["\u6b21\u610f\u56fe", valueOrEmpty(props.intentState?.secondaryIntent)],
  ["\u6e05\u6670\u5ea6", valueOrEmpty(props.intentState?.clarity)],
  ["\u7528\u6237\u60c5\u7eea", valueOrEmpty(props.intentState?.emotion || props.turnContext?.userEmotion)],
  ["\u56de\u590d\u7b56\u7565", valueOrEmpty(props.responsePlan?.coreTask)],
  ["\u9996\u4e2a\u52a8\u4f5c", valueOrEmpty(props.responsePlan?.firstMove)],
  ["\u4e3b\u52a8\u7a0b\u5ea6", valueOrEmpty(props.responsePlan?.initiativeLevel)],
  ["\u672c\u8f6e\u597d\u611f delta", valueOrEmpty(props.turnContext?.affectionDeltaTotal)],
  ["\u8bc4\u5206\u539f\u56e0", joinList(props.turnContext?.scoreReasons)],
  ["\u884c\u4e3a\u6807\u7b7e", joinList(props.turnContext?.behaviorTags)],
  ["\u98ce\u9669\u6807\u7b7e", joinList(props.turnContext?.riskFlags)]
]);

const continuityRows = computed(() => [
  ["\u5f53\u524d\u5171\u540c\u76ee\u6807", valueOrEmpty(props.dialogueContinuity?.currentObjective)],
  ["\u5f85\u56de\u5e94\u63d0\u8bae", valueOrEmpty(props.dialogueContinuity?.pendingUserOffer)],
  ["\u5df2\u786e\u8ba4\u8ba1\u5212", valueOrEmpty(props.dialogueContinuity?.acceptedPlan)],
  ["\u4e0a\u8f6e AI \u95ee\u9898", valueOrEmpty(props.dialogueContinuity?.lastAssistantQuestion)],
  ["\u662f\u5426\u56de\u7b54\u4e0a\u4e00\u95ee", valueOrEmpty(props.dialogueContinuity?.userAnsweredLastQuestion)],
  ["\u662f\u5426\u9700\u8981\u8f6c\u573a", valueOrEmpty(props.dialogueContinuity?.sceneTransitionNeeded)],
  ["\u4e0b\u4e00\u53e5\u6700\u597d\u627f\u63a5", valueOrEmpty(props.dialogueContinuity?.nextBestMove)],
  ["\u7981\u6b62\u8fdd\u80cc", joinList(props.dialogueContinuity?.mustNotContradict)],
  ["\u4e0a\u4e0b\u6587\u7f6e\u4fe1\u5ea6", valueOrEmpty(props.dialogueContinuity?.confidence)]
]);

const emotionRows = computed(() => [
  ["\u5f53\u524d\u5fc3\u60c5", valueOrEmpty(props.emotionState?.currentMood)],
  ["\u67d4\u8f6f\u5ea6 warmth", valueOrEmpty(props.emotionState?.warmth)],
  ["\u5b89\u5168\u611f safety", valueOrEmpty(props.emotionState?.safety)],
  ["\u60f3\u9760\u8fd1 longing", valueOrEmpty(props.emotionState?.longing)],
  ["\u4e3b\u52a8\u6027 initiative", valueOrEmpty(props.emotionState?.initiative)],
  ["\u5f20\u529b annoyance", valueOrEmpty(props.tensionState?.annoyance)],
  ["\u53d7\u4f24 hurt", valueOrEmpty(props.tensionState?.hurt)],
  ["\u9632\u5907 guarded", valueOrEmpty(props.tensionState?.guarded)]
]);

const auditRows = computed(() => [
  ["\u63a5\u4f4f\u7528\u6237", valueOrEmpty(props.humanizationAudit?.feltHeard)],
  ["\u56de\u7b54\u6838\u5fc3\u95ee\u9898", valueOrEmpty(props.humanizationAudit?.answeredCoreQuestion)],
  ["\u8bb0\u5fc6\u81ea\u7136", valueOrEmpty(props.humanizationAudit?.usedMemoryNaturally)],
  ["\u4e3b\u52a8\u6027\u5408\u9002", valueOrEmpty(props.humanizationAudit?.initiativeAppropriate)],
  ["\u573a\u666f\u4e00\u81f4", valueOrEmpty(props.realityAudit?.sceneConsistent)],
  ["\u4e92\u52a8\u4e00\u81f4", valueOrEmpty(props.realityAudit?.interactionConsistent)],
  ["\u65f6\u95f4\u4e00\u81f4", valueOrEmpty(props.realityAudit?.timeConsistent)],
  ["\u5929\u6c14\u4e00\u81f4", valueOrEmpty(props.realityAudit?.weatherConsistent)],
  ["\u5ba1\u8ba1\u5907\u6ce8", joinList([...(props.humanizationAudit?.notes || []), ...(props.realityAudit?.notes || [])])]
]);

const analyticsRows = computed(() => [
  ["UV", valueOrEmpty(props.analytics?.visitorCount)],
  ["\u4f1a\u8bdd\u6570", valueOrEmpty(props.analytics?.sessionCount)],
  ["\u5e73\u5747\u8f6e\u6b21", valueOrEmpty(props.analytics?.avgTurns)],
  ["\u5e73\u5747\u65f6\u957f", valueOrEmpty(props.analytics?.avgSessionMinutes)],
  ["7 \u65e5\u7eed\u73a9\u7387", `${props.analytics?.retention7d ?? "--"}%`]
]);

const sections = computed(() => [
  { title: "\u5173\u7cfb\u4e0e\u597d\u611f", rows: relationshipRows.value },
  { title: "\u5267\u60c5\u4e0e\u95e8\u63a7", rows: plotRows.value },
  { title: "\u573a\u666f / \u65f6\u95f4 / \u5929\u6c14", rows: contextRows.value },
  { title: "\u8bb0\u5fc6\u7ebf\u7d22", rows: memoryRows.value },
  { title: "\u4e0a\u4e0b\u6587\u667a\u80fd\u5c42", rows: continuityRows.value },
  { title: "\u672c\u8f6e\u51b3\u7b56\u95ed\u73af", rows: runtimeRows.value },
  { title: "\u60c5\u7eea\u4e0e\u5f20\u529b", rows: emotionRows.value },
  { title: "\u771f\u5b9e\u6027\u4e0e\u4eba\u6027\u5316\u5ba1\u8ba1", rows: auditRows.value },
  { title: "\u8bd5\u73a9\u6570\u636e", rows: analyticsRows.value }
]);
</script>

<template>
  <teleport to="body">
    <transition name="fade">
      <div v-if="open" class="fixed inset-0 z-40 bg-[#05060d]/72 backdrop-blur-sm" @click="emits('close')"></div>
    </transition>
    <transition name="slide-up">
      <aside
        v-if="open"
        class="fixed inset-x-0 bottom-0 z-50 mx-auto max-h-[88vh] w-[min(100%,1080px)] overflow-y-auto rounded-t-[2rem] border border-white/12 bg-[#0d1020]/96 p-5 shadow-[0_-20px_60px_rgba(0,0,0,0.35)]"
      >
        <div class="mb-4 flex items-center justify-between">
          <div>
            <p class="tracking-[0.28em] text-[0.68rem] uppercase text-white/42">{{ text.eyebrow }}</p>
            <h3 class="mt-2 text-xl font-semibold text-white">{{ title }}</h3>
          </div>
          <button
            type="button"
            class="rounded-full border border-white/10 px-3 py-2 text-sm text-white/68"
            @click="emits('close')"
          >
            {{ text.close }}
          </button>
        </div>

        <div class="grid gap-4 md:grid-cols-2">
          <section
            v-for="group in sections"
            :key="group.title"
            class="rounded-[1.4rem] border border-white/10 bg-white/5 p-4"
          >
            <h4 class="text-sm font-medium text-white/84">{{ group.title }}</h4>
            <dl class="mt-3 space-y-2 text-sm text-white/64">
              <div v-for="row in group.rows" :key="row[0]" class="flex gap-3">
                <dt class="w-28 shrink-0 text-white/42">{{ row[0] }}</dt>
                <dd class="min-w-0 flex-1 break-words text-white/70">{{ row[1] }}</dd>
              </div>
            </dl>
          </section>
        </div>
      </aside>
    </transition>
  </teleport>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active,
.slide-up-enter-active,
.slide-up-leave-active {
  transition: all 0.24s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.slide-up-enter-from,
.slide-up-leave-to {
  opacity: 0;
  transform: translateY(20px);
}
</style>
