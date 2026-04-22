export interface AgentProfile {
  id: string;
  name: string;
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
  plotState: PlotState;
  plotArcState: PlotArcState;
  sceneState: SceneState;
  presenceState: PresenceState;
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
  feedbackCompletionRate: number;
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

export interface PresenceResponse {
  online: boolean;
  presenceState: PresenceState;
  proactive_message: ConversationMessage | null;
  trigger_reason?: string;
  blocked_reason?: string;
  heartbeat_explain?: string;
  emotion_state?: EmotionState;
  plot_progress?: PlotState;
  plot_arc_state?: PlotArcState;
  scene_state?: SceneState;
  scene_frame?: string;
  reply_source?: string;
  run_status?: string;
  checkpoint_ready?: boolean;
  arc_summary_preview?: ArcSummary;
}
