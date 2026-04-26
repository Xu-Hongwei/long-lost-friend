export interface AgentBackstory {
  age?: number;
  grade?: string;
  major?: string;
  hometown?: string;
  currentCity?: string;
  campusPlaces?: string[];
  hobbies?: string[];
  lifestyle?: string;
  boundaryDetails?: string;
  emotionPattern?: string;
  hiddenFacts?: string[];
  plotHooks?: string[];
}

export interface AgentProfile {
  id: string;
  name: string;
  gender?: "female" | "male" | "unknown" | string;
  subjectPronoun?: string;
  objectPronoun?: string;
  possessivePronoun?: string;
  archetype: string;
  tagline: string;
  palette: string[];
  avatarGlyph?: string;
  bio?: string;
  likes?: string[];
  portraitAsset?: string;
  coverAsset?: string;
  styleTags?: string[];
  moodPalette?: string[];
  backstory?: AgentBackstory;
}

export interface ConversationMessage {
  id: string;
  sessionId: string;
  role: "assistant" | "user";
  text: string;
  sceneText?: string;
  actionText?: string;
  speechText?: string;
  createdAt: string;
  emotionTag?: string;
  confidenceStatus?: string;
  tokenUsage?: number;
  fallbackUsed?: boolean;
  triggeredEventId?: string;
  affectionDelta?: number;
  replySource?: string;
}

export interface ChoiceOption {
  id: string;
  label: string;
  toneHint?: string;
  outcomeType?: string;
}

export interface RelationshipState {
  closeness: number;
  trust: number;
  resonance: number;
  affectionScore: number;
  relationshipStage: string;
  ending?: string;
  stageProgressHint?: string;
  stagnationLevel?: number;
  routeTag?: string;
  endingCandidate?: string;
  relationshipFeedback?: string;
}

export interface MemoryFactItem {
  key?: string;
  value?: string;
  confidence?: number;
  sourceTurn?: number;
  lastUsedTurn?: number;
}

export interface SceneLedgerItem {
  summary?: string;
  location?: string;
  lastTurn?: number;
}

export interface OpenLoopItem {
  summary?: string;
  status?: string;
  lastMentionTurn?: number;
}

export interface MemorySummary {
  preferences?: string[];
  identityNotes?: string[];
  promises?: string[];
  callbackCandidates?: string[];
  assistantOwnedThreads?: string[];
  factMemory?: MemoryFactItem[];
  sceneLedger?: SceneLedgerItem[];
  openLoopItems?: OpenLoopItem[];
  lastUserMood?: string;
  lastUserIntent?: string;
  lastResponseCadence?: string;
  lastMemoryUseMode?: string;
  lastMemoryRelevanceReason?: string;
  updatedAt?: string;
}

export interface EmotionState {
  warmth: number;
  safety: number;
  longing: number;
  initiative: number;
  vulnerability: number;
  currentMood: string;
  updatedAt?: string;
}

export interface TensionState {
  annoyance?: number;
  hurt?: number;
  guarded?: boolean;
  repairReadiness?: number;
  recentBoundaryHits?: number;
  updatedAt?: string;
}

export interface IntentState {
  primaryIntent?: string;
  secondaryIntent?: string;
  emotion?: string;
  clarity?: string;
  needsEmpathy?: boolean;
  needsStructure?: boolean;
  needsFollowup?: boolean;
  isBoundarySensitive?: boolean;
  rationale?: string;
  updatedAt?: string;
}

export interface ResponsePlan {
  firstMove?: string;
  coreTask?: string;
  initiativeLevel?: string;
  responseLength?: string;
  dialogueMode?: string;
  shouldReferenceMemory?: boolean;
  shouldAdvanceScene?: boolean;
  shouldAdvancePlot?: boolean;
  shouldUseUncertainty?: boolean;
  allowFollowupQuestion?: boolean;
  explanation?: string;
  updatedAt?: string;
}

export interface HumanizationAudit {
  feltHeard?: boolean;
  answeredCoreQuestion?: boolean;
  usedMemoryNaturally?: boolean;
  initiativeAppropriate?: boolean;
  sceneConsistent?: boolean;
  emotionMatched?: boolean;
  overacted?: boolean;
  tooMechanical?: boolean;
  notes?: string[];
}

export interface RealityAudit {
  timeConsistent?: boolean;
  weatherConsistent?: boolean;
  sceneConsistent?: boolean;
  interactionConsistent?: boolean;
  grounded?: boolean;
  notes?: string[];
}

export interface PlotGateDecision {
  allowed?: boolean;
  triggerReason?: string;
  blockedReason?: string;
  requiredScene?: string;
  requiredRelationFloor?: number;
  requiredGap?: number;
  candidateEventId?: string;
  updatedAt?: string;
}

export interface TurnContext {
  primaryIntent?: string;
  secondaryIntent?: string;
  clarity?: string;
  userEmotion?: string;
  replySource?: string;
  affectionDeltaTotal?: number;
  closenessDelta?: number;
  trustDelta?: number;
  resonanceDelta?: number;
  behaviorTags?: string[];
  riskFlags?: string[];
  sceneLocation?: string;
  interactionMode?: string;
  plotGap?: number;
  plotSignal?: number;
  plotDirectorAction?: string;
  plotWhyNow?: string;
  plotDirectorConfidence?: number;
  plotRiskIfAdvance?: string;
  requiredUserSignal?: string;
  sceneMoveIntent?: string;
  sceneMoveTarget?: string;
  sceneMoveReason?: string;
  sceneMoveConfidence?: number;
  continuityObjective?: string;
  continuityAcceptedPlan?: string;
  continuityNextBestMove?: string;
  continuityGuards?: string[];
  updatedAt?: string;
}

export interface DialogueContinuityState {
  currentObjective?: string;
  pendingUserOffer?: string;
  acceptedPlan?: string;
  lastAssistantQuestion?: string;
  userAnsweredLastQuestion?: boolean;
  sceneTransitionNeeded?: boolean;
  nextBestMove?: string;
  mustNotContradict?: string[];
  confidence?: number;
  updatedAt?: string;
}

export interface QuickJudgeStatus {
  attempted?: boolean;
  used?: boolean;
  applied?: boolean;
  status?: string;
  reason?: string;
  confidence?: number;
  primaryIntent?: string;
  secondaryIntent?: string;
  emotion?: string;
  sharedObjective?: string;
  nextBestMove?: string;
  replyPriority?: string;
  updatedAt?: string;
}

export interface PlotState {
  beatIndex?: number;
  phase?: string;
  sceneFrame?: string;
  openThreads?: string[];
  plotProgress?: string;
  nextBeatHint?: string;
}

export interface ArcSummary {
  title?: string;
  routeTheme?: string;
  highlightMoments?: string[];
  relationshipChanges?: string[];
  nextExpectation?: string;
  endingCandidate?: string;
}

export interface PlotArcState {
  beatIndex?: number;
  arcIndex?: number;
  phase?: string;
  checkpointReady?: boolean;
  runStatus?: string;
  endingCandidate?: string;
  canSettleScore?: boolean;
  canContinue?: boolean;
  latestArcSummary?: ArcSummary;
}

export interface SceneState {
  location?: string;
  subLocation?: string;
  interactionMode?: string;
  timeOfScene?: string;
  weatherMood?: string;
  transitionPending?: boolean;
  sceneSummary?: string;
}

export interface PresenceState {
  online?: boolean;
  typing?: boolean;
  visible?: boolean;
  focused?: boolean;
  blockedReason?: string;
  heartbeatExplain?: string;
  triggerReason?: string;
}

export interface StoryEventProgress {
  triggeredEventIds?: string[];
  lastTriggeredEventId?: string;
  eventCooldownUntilTurn?: Record<string, number>;
  lastTriggeredTitle?: string;
  lastTriggeredTheme?: string;
  currentRouteTheme?: string;
  nextExpectedDirection?: string;
}

export interface TimeContext {
  label?: string;
  phase?: string;
  timezone?: string;
}

export interface WeatherContext {
  city?: string;
  summary?: string;
  temperatureC?: number;
  live?: boolean;
  updatedAt?: string;
}

export interface VisitorContext {
  timezone?: string;
  preferredCity?: string;
}

export interface SessionRecord {
  sessionId: string;
  visitorId: string;
  agent: AgentProfile;
  relationshipState: RelationshipState;
  memorySummary: MemorySummary;
  storyEventProgress: StoryEventProgress;
  emotionState: EmotionState;
  tensionState?: TensionState;
  plotState: PlotState;
  plotArcState: PlotArcState;
  sceneState: SceneState;
  presenceState: PresenceState;
  lastIntentState?: IntentState;
  lastResponsePlan?: ResponsePlan;
  lastHumanizationAudit?: HumanizationAudit;
  lastRealityAudit?: RealityAudit;
  lastPlotGateDecision?: PlotGateDecision;
  lastTurnContext?: TurnContext;
  dialogueContinuityState?: DialogueContinuityState;
  lastQuickJudgeStatus?: QuickJudgeStatus;
  visitorContext: VisitorContext;
  timeContext: TimeContext;
  weatherContext: WeatherContext;
  memoryExpireAt: string;
  userTurnCount: number;
  history: ConversationMessage[];
  pendingChoiceEventId?: string;
  pendingChoices?: ChoiceOption[];
  pendingEventContext?: string;
  lastProactiveMessageAt?: string;
}

export interface AnalyticsAgentPreference {
  agentId: string;
  name: string;
  count: number;
}

export interface AnalyticsOverview {
  visitorCount: number;
  sessionCount: number;
  avgTurns: number;
  avgSessionMinutes: number;
  retention7d: number;
  agentPreference: AnalyticsAgentPreference[];
}

export interface VisitorInitResult {
  visitorId: string;
  timezone?: string;
  preferredCity?: string;
  restoredSession: null | {
    sessionId: string;
    agentId: string;
    affectionScore: number;
    relationshipStage: string;
    memoryExpireAt: string;
  };
}

export interface VisitorContextUpdateResult {
  saved: boolean;
  timezone?: string;
  preferredCity?: string;
  timeContext?: TimeContext;
  weatherContext?: WeatherContext;
}

export interface PresenceResponse {
  online: boolean;
  presenceState: PresenceState;
  proactive_message: ConversationMessage | null;
  trigger_reason?: string;
  blocked_reason?: string;
  heartbeat_explain?: string;
  initiative_decision?: Record<string, unknown>;
  humanization_audit?: HumanizationAudit;
  reality_audit?: RealityAudit;
  interaction_mode?: string;
  emotion_state?: EmotionState;
  turn_context?: TurnContext;
  dialogue_continuity?: DialogueContinuityState;
  quick_judge_status?: QuickJudgeStatus;
  plot_progress?: PlotState;
  plot_arc_state?: PlotArcState;
  scene_state?: SceneState;
  scene_frame?: string;
  reply_source?: string;
  plot_director_decision?: string;
  run_status?: string;
  checkpoint_ready?: boolean;
  arc_summary_preview?: ArcSummary;
}
