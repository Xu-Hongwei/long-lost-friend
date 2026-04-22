const storageKeys = {
  visitorId: "campus-agent-visitor-id",
  sessionId: "campus-agent-session-id",
  agentId: "campus-agent-agent-id",
  preferredCity: "campus-agent-preferred-city",
  uiMode: "campus-agent-ui-mode"
};

const state = {
  visitorId: "",
  agents: [],
  currentAgentId: "",
  currentSession: null,
  analytics: null,
  pendingChoiceEvent: null,
  pendingCheckpoint: null,
  relationshipFeedback: "",
  endingCandidate: "",
  isTyping: false,
  draftLength: 0,
  lastInputAt: "",
  presenceTimer: null,
  typingTimer: null,
  pingDebounceTimer: null,
  bootedPresenceListeners: false,
  uiMode: window.innerWidth <= 980
    ? "immersive"
    : (localStorage.getItem(storageKeys.uiMode) || "inspector"),
  activeDrawer: "none"
};

const elements = {
  visitorId: document.getElementById("visitor-id"),
  cityInput: document.getElementById("city-input"),
  saveContext: document.getElementById("save-context"),
  refreshAnalytics: document.getElementById("refresh-analytics"),
  modeImmersive: document.getElementById("mode-immersive"),
  modeInspector: document.getElementById("mode-inspector"),
  agentGrid: document.getElementById("agent-grid"),
  heroAgentOrb: document.getElementById("hero-agent-orb"),
  heroAgentName: document.getElementById("hero-agent-name"),
  heroAgentTagline: document.getElementById("hero-agent-tagline"),
  heroAgentBio: document.getElementById("hero-agent-bio"),
  heroAgentTags: document.getElementById("hero-agent-tags"),
  heroAgentButton: document.getElementById("hero-agent-button"),
  chatAgentName: document.getElementById("chat-agent-name"),
  chatAgentTagline: document.getElementById("chat-agent-tagline"),
  stageBadge: document.getElementById("stage-badge"),
  presenceBadge: document.getElementById("presence-badge"),
  expiryBadge: document.getElementById("expiry-badge"),
  llmStatusBadge: document.getElementById("llm-status-badge"),
  timeContext: document.getElementById("time-context"),
  weatherContext: document.getElementById("weather-context"),
  sceneContext: document.getElementById("scene-context"),
  insightButtons: Array.from(document.querySelectorAll(".insight-button")),
  chatLog: document.getElementById("chat-log"),
  composer: document.getElementById("composer"),
  messageInput: document.getElementById("message-input"),
  sendButton: document.getElementById("send-button"),
  choicePanel: document.getElementById("choice-panel"),
  choiceTitle: document.getElementById("choice-title"),
  choiceContext: document.getElementById("choice-context"),
  choiceActions: document.getElementById("choice-actions"),
  checkpointPanel: document.getElementById("checkpoint-panel"),
  checkpointTitle: document.getElementById("checkpoint-title"),
  checkpointContext: document.getElementById("checkpoint-context"),
  checkpointSummary: document.getElementById("checkpoint-summary"),
  checkpointContinue: document.getElementById("checkpoint-continue"),
  checkpointSettle: document.getElementById("checkpoint-settle"),
  scoreTotal: document.getElementById("score-total"),
  scoreCloseness: document.getElementById("score-closeness"),
  scoreTrust: document.getElementById("score-trust"),
  scoreResonance: document.getElementById("score-resonance"),
  scoreBar: document.getElementById("score-bar"),
  stageProgressHint: document.getElementById("stage-progress-hint"),
  relationshipFeedback: document.getElementById("relationship-feedback"),
  endingHint: document.getElementById("ending-hint"),
  emotionSummary: document.getElementById("emotion-summary"),
  plotSummary: document.getElementById("plot-summary"),
  memorySummary: document.getElementById("memory-summary"),
  eventSummary: document.getElementById("event-summary"),
  analyticsGrid: document.getElementById("analytics-grid"),
  analyticsList: document.getElementById("analytics-list"),
  feedbackForm: document.getElementById("feedback-form"),
  feedbackRating: document.getElementById("feedback-rating"),
  feedbackLiked: document.getElementById("feedback-liked"),
  feedbackImprove: document.getElementById("feedback-improve"),
  feedbackContinue: document.getElementById("feedback-continue"),
  insightSheet: document.getElementById("insight-sheet"),
  sheetTitle: document.getElementById("sheet-title"),
  sheetBody: document.getElementById("sheet-body"),
  sheetClose: document.getElementById("sheet-close"),
  sheetBackdrop: document.querySelector("[data-sheet-close]")
};

const drawerLabels = {
  relationship: "关系状态",
  plot: "剧情进度",
  memory: "长期记忆",
  analytics: "试玩概览"
};

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function formatTime(isoString) {
  if (!isoString) {
    return "--:--";
  }
  return new Date(isoString).toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit"
  });
}

function formatDateTime(isoString) {
  if (!isoString) {
    return "未建立";
  }
  return new Date(isoString).toLocaleString("zh-CN", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  });
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: {
      "Content-Type": "application/json"
    },
    ...options
  });

  const payload = await response.json();
  if (!response.ok || !payload.ok) {
    throw new Error(payload.error?.message || "请求失败");
  }
  return payload.data;
}

function getMoodLabel(mood) {
  const labels = {
    stressed: "有点紧绷",
    warm: "正在回暖",
    curious: "愿意继续靠近",
    neutral: "平稳聊天",
    calm: "安静靠近",
    teasing: "带一点试探",
    protective: "想接住你",
    uneasy: "还有一点不安"
  };
  return labels[mood] || "还在慢慢变化";
}

function getIntentLabel(intent) {
  const labels = {
    plan: "在聊接下来的安排",
    question: "在等一个回应",
    share: "在认真分享",
    chat: "轻松闲聊"
  };
  return labels[intent] || "轻松闲聊";
}

function getCadenceLabel(cadence) {
  const labels = {
    soft_pause: "更轻一点接住",
    answer_first: "先回答再展开",
    lean_in: "会更主动靠近",
    light_ping: "轻轻问候",
    cinematic: "更有画面感",
    steady_flow: "顺着气氛慢慢聊"
  };
  return labels[cadence] || "顺着气氛慢慢聊";
}

function getReplySourceLabel(replySource) {
  const labels = {
    user_turn: "当前回合",
    plot_push: "顺势推进",
    silence_heartbeat: "静默问候",
    long_chat_heartbeat: "长聊心跳",
    choice_result: "剧情结果",
    choice: "剧情选择"
  };
  return labels[replySource] || "";
}

function getRuntimeStatusMeta(session) {
  if (!session?.history?.length) {
    return {
      label: "模型未运行",
      className: "is-idle",
      title: "当前还没有模型回复"
    };
  }

  const assistantMessages = session.history.filter((message) => message.role === "assistant");
  const latestAssistant = assistantMessages[assistantMessages.length - 1];
  const status = latestAssistant?.confidenceStatus || "";

  if (status === "remote") {
    return { label: "远程模型", className: "is-remote", title: "当前回复来自远程模型" };
  }
  if (status === "fallback") {
    return { label: "远程降级", className: "is-fallback", title: "远程模型失败，已使用兜底逻辑" };
  }
  if (status === "mock") {
    return { label: "本地 Mock", className: "is-mock", title: "当前回复来自本地模拟逻辑" };
  }
  if (status === "guarded") {
    return { label: "安全拦截", className: "is-guarded", title: "当前回复来自安全拦截或兜底逻辑" };
  }
  if (status === "system") {
    return { label: "系统开场", className: "is-system", title: "当前显示的是系统初始化开场白" };
  }
  return {
    label: "状态未知",
    className: "is-idle",
    title: "暂时无法判断当前模型来源"
  };
}

function getSelectedAgent() {
  const fromSession = state.currentSession?.agent;
  if (fromSession) {
    return fromSession;
  }
  if (state.currentAgentId) {
    return state.agents.find((agent) => agent.id === state.currentAgentId) || state.agents[0] || null;
  }
  return state.agents[0] || null;
}

function openDrawer(drawer) {
  state.activeDrawer = drawer;
  renderUiMode();
}

function closeDrawer() {
  state.activeDrawer = "none";
  renderUiMode();
}

function setUiMode(mode) {
  state.uiMode = mode;
  localStorage.setItem(storageKeys.uiMode, mode);
  if (mode === "inspector") {
    closeDrawer();
  }
  renderUiMode();
}

function renderUiMode() {
  document.body.dataset.uiMode = state.uiMode;

  elements.modeImmersive.classList.toggle("is-active", state.uiMode === "immersive");
  elements.modeInspector.classList.toggle("is-active", state.uiMode === "inspector");

  elements.insightButtons.forEach((button) => {
    button.classList.toggle("is-active", button.dataset.drawer === state.activeDrawer);
  });

  if (state.activeDrawer === "none") {
    elements.insightSheet.classList.add("hidden");
    elements.insightSheet.setAttribute("aria-hidden", "true");
    return;
  }

  const content = getDrawerContent(state.activeDrawer);
  elements.sheetTitle.textContent = content.title;
  elements.sheetBody.innerHTML = content.html;
  elements.insightSheet.classList.remove("hidden");
  elements.insightSheet.setAttribute("aria-hidden", "false");
}

function looksLikeActionLine(text) {
  if (!text) {
    return false;
  }
  const cues = [
    "视线", "目光", "眼神", "眉眼", "手指", "指尖", "手心", "发梢", "袖口", "衣角",
    "抬眼", "垂眼", "偏头", "靠近", "看向", "望向", "轻声", "笑意", "呼吸", "停顿"
  ];
  return cues.some((cue) => text.includes(cue));
}

function splitAssistantMessage(text) {
  const normalized = String(text ?? "").trim();
  if (!normalized) {
    return { sceneText: "", actionText: "", speechText: "" };
  }

  const bracketLead = normalized.match(/^[（(【\[]([^）)】\]]{2,48})[）)】\]]\s*(.+)$/);
  if (bracketLead && looksLikeActionLine(bracketLead[1])) {
    return {
      sceneText: "",
      actionText: bracketLead[1].trim(),
      speechText: bracketLead[2].trim()
    };
  }

  return { sceneText: "", actionText: "", speechText: normalized };
}

function getMessagePresentation(message) {
  const rawText = String(message?.text ?? "").trim();
  if (message?.role !== "assistant") {
    return { sceneText: "", actionText: "", speechText: rawText };
  }

  const explicitScene = String(message?.sceneText ?? "").trim();
  const explicitAction = String(message?.actionText ?? "").trim();
  const explicitSpeech = String(message?.speechText ?? "").trim();

  if (explicitScene || explicitAction || explicitSpeech) {
    return {
      sceneText: explicitScene,
      actionText: explicitAction,
      speechText: explicitSpeech || rawText
    };
  }

  return splitAssistantMessage(rawText);
}

function renderSpotlightTags(agent) {
  return (agent?.likes || []).slice(0, 3)
    .map((item) => `<span class="spotlight-chip">${escapeHtml(item)}</span>`)
    .join("");
}

function renderHero() {
  const agent = getSelectedAgent();
  if (!agent) {
    elements.heroAgentName.textContent = "选择一个角色";
    elements.heroAgentTagline.textContent = "今晚的氛围还在等一个人先开口。";
    elements.heroAgentBio.textContent = "选中角色后，这里会展示 TA 此刻的气质和进入聊天的方向。";
    elements.heroAgentTags.innerHTML = "";
    elements.heroAgentButton.textContent = "开始今晚的聊天";
    elements.heroAgentOrb.style.background = "";
    return;
  }

  const palette = agent.palette || ["#f3b2a6", "#c7d4ff"];
  elements.heroAgentOrb.style.background = `linear-gradient(135deg, ${palette[0]}, ${palette[1]})`;
  elements.heroAgentName.textContent = `${agent.name} · ${agent.archetype}`;
  elements.heroAgentTagline.textContent = agent.tagline || "今晚从一句话开始靠近。";
  elements.heroAgentBio.textContent = agent.bio || "选中角色后，聊天会沿着人物设定、情绪和剧情慢慢展开。";
  elements.heroAgentTags.innerHTML = renderSpotlightTags(agent);

  if (state.currentSession?.agent?.id === agent.id) {
    elements.heroAgentButton.textContent = `继续和 ${agent.name} 聊`;
  } else {
    elements.heroAgentButton.textContent = `和 ${agent.name} 开始今晚`;
  }
}

function renderAgents() {
  elements.agentGrid.innerHTML = "";
  state.agents.forEach((agent) => {
    const card = document.createElement("button");
    card.type = "button";
    card.className = `agent-card ${state.currentAgentId === agent.id ? "active" : ""}`;
    card.innerHTML = `
      <div class="agent-avatar" style="background: linear-gradient(135deg, ${agent.palette[0]}, ${agent.palette[1]})">${escapeHtml(agent.avatarGlyph)}</div>
      <div>
        <p class="section-kicker">${escapeHtml(agent.archetype)}</p>
        <h3>${escapeHtml(agent.name)}</h3>
        <p>${escapeHtml(agent.tagline)}</p>
      </div>
      <div class="agent-tags">
        ${(agent.likes || []).slice(0, 3).map((item) => `<span class="agent-tag">${escapeHtml(item)}</span>`).join("")}
      </div>
    `;
    card.addEventListener("click", () => startSession(agent.id));
    elements.agentGrid.appendChild(card);
  });
}

function renderStructuredMemoryGroup(title, items, mapper, tone = "") {
  if (!items?.length) {
    return "";
  }

  const chips = items
    .map((item) => `<span class="memory-chip ${tone}">${escapeHtml(mapper(item))}</span>`)
    .join("");

  return `
    <section class="memory-group">
      <h4 class="memory-group-title">${escapeHtml(title)}</h4>
      <div class="memory-group-chips">${chips}</div>
    </section>
  `;
}

function renderMemorySummary(memory) {
  if (!memory) {
    return "<p>还没有足够的长期记忆，先聊几轮看看。</p>";
  }

  const groups = [
    renderStructuredMemoryGroup("锁定事实", memory.factMemories, (item) => `${item.key || "fact"}：${item.value || ""}`, "memory-chip--warm"),
    renderStructuredMemoryGroup("场景账本", memory.sceneLedger, (item) => `${item.location || "场景"}：${item.summary || ""}`, "memory-chip--story"),
    renderStructuredMemoryGroup("未完话题", memory.openLoopItems, (item) => item.summary || "", "memory-chip--accent"),
    renderStructuredMemoryGroup("长期偏好", memory.preferences, (item) => item, "memory-chip--warm"),
    renderStructuredMemoryGroup("共同经历", memory.sharedMoments, (item) => item, "memory-chip--story"),
    renderStructuredMemoryGroup("角色挂念", memory.assistantOwnedThreads, (item) => item, "memory-chip--story"),
    renderStructuredMemoryGroup("适合回调", memory.callbackCandidates, (item) => item, "memory-chip--accent")
  ].filter(Boolean);

  const meta = `
    <div class="memory-meta">
      <span class="memory-meta-pill">最近情绪：${escapeHtml(getMoodLabel(memory.lastUserMood))}</span>
      <span class="memory-meta-pill">最近意图：${escapeHtml(getIntentLabel(memory.lastUserIntent))}</span>
      <span class="memory-meta-pill">回复节奏：${escapeHtml(getCadenceLabel(memory.lastResponseCadence))}</span>
      <span class="memory-meta-pill">记忆模式：${escapeHtml(memory.lastMemoryUseMode || "hold")}</span>
    </div>
    <p class="panel-note">${escapeHtml(memory.lastMemoryRelevanceReason || "这轮先顺着眼前的话题慢慢聊。")}</p>
  `;

  return `${meta}<div class="memory-groups">${groups.join("")}</div>`;
}

function renderEmotionSummary(emotion) {
  if (!emotion) {
    return "<p>还没有足够的数据来判断角色的情绪状态。</p>";
  }

  const items = [
    ["柔软感", emotion.warmth],
    ["安全感", emotion.safety],
    ["想靠近", emotion.longing],
    ["主动性", emotion.initiative],
    ["愿意坦露", emotion.vulnerability]
  ];

  return `
    <div class="emotion-meter-list">
      ${items.map(([label, value]) => `
        <div class="emotion-meter">
          <div class="emotion-meter-head">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value)}</strong>
          </div>
          <div class="progress-line is-mini">
            <div class="progress-fill" style="width:${Math.min(100, value || 0)}%"></div>
          </div>
        </div>
      `).join("")}
    </div>
    <p class="panel-note">当前情绪：${escapeHtml(getMoodLabel(emotion.currentMood))}</p>
  `;
}

function renderEventSummary(progress) {
  if (!progress) {
    return "<p>尚未触发关键剧情。</p>";
  }

  const lines = [];
  if (progress.lastTriggeredTitle) {
    lines.push(`<p><strong>最近剧情：</strong>${escapeHtml(progress.lastTriggeredTitle)}</p>`);
  }
  if (progress.lastTriggeredTheme) {
    lines.push(`<p><strong>当前主题：</strong>${escapeHtml(progress.lastTriggeredTheme)}</p>`);
  }
  if (progress.currentRouteTheme) {
    lines.push(`<p><strong>路线方向：</strong>${escapeHtml(progress.currentRouteTheme)}</p>`);
  }
  if (progress.nextExpectedDirection) {
    lines.push(`<p><strong>下个期待：</strong>${escapeHtml(progress.nextExpectedDirection)}</p>`);
  }
  if (progress.triggeredEventIds?.length) {
    lines.push(`
      <div class="event-chip-list">
        ${progress.triggeredEventIds.map((item) => `<span class="event-chip">${escapeHtml(item)}</span>`).join("")}
      </div>
    `);
  }
  return lines.length ? lines.join("") : "<p>尚未触发关键剧情。</p>";
}

function renderPlotSummary(plotArcState, plotState, presenceState) {
  const source = plotArcState || plotState;
  if (!source) {
    return "<p>剧情还在等待铺开。</p>";
  }

  const threads = source.openThreads?.length
    ? `<div class="event-chip-list">${source.openThreads.map((item) => `<span class="event-chip">${escapeHtml(item)}</span>`).join("")}</div>`
    : "";

  const explain = presenceState?.heartbeatExplain
    ? `<p class="panel-note">${escapeHtml(presenceState.heartbeatExplain)}</p>`
    : "";

  return `
    <p><strong>${escapeHtml(source.plotProgress || `第 ${source.beatIndex || 0} 拍`)}</strong></p>
    <p>${escapeHtml(source.sceneFrame || "当前剧情还在自然铺开。")}</p>
    <p class="panel-note">下一步期待：${escapeHtml(source.nextBeatHint || "先把当前场景聊顺。")}</p>
    ${threads}
    ${explain}
  `;
}

function renderArcSummary(summary) {
  if (!summary) {
    return "";
  }

  const blocks = [
    ["路线主题", summary.routeTheme || "当前阶段仍在推进中"],
    ["关系变化", summary.relationshipSummary || "关系还在慢慢往前走"],
    ["重要场景", summary.sceneSummary || "这一段的场景还在继续展开"],
    ["当前倾向", summary.endingTendency || "继续发展"]
  ];

  return blocks.map(([title, text]) => `
    <section class="checkpoint-summary-block">
      <h4>${escapeHtml(title)}</h4>
      <p>${escapeHtml(text)}</p>
    </section>
  `).join("");
}

function renderAnalytics() {
  const analytics = state.analytics;
  if (!analytics) {
    elements.analyticsGrid.innerHTML = "";
    elements.analyticsList.innerHTML = "";
    return;
  }

  const tiles = [
    ["访客数", analytics.visitorCount],
    ["会话数", analytics.sessionCount],
    ["平均轮次", analytics.avgTurns],
    ["平均时长(分钟)", analytics.avgSessionMinutes],
    ["7 天续玩率", `${analytics.retention7d}%`],
    ["反馈完成率", `${analytics.feedbackCompletionRate}%`]
  ];

  elements.analyticsGrid.innerHTML = tiles.map(([label, value]) => `
    <div class="analytics-tile">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
    </div>
  `).join("");

  elements.analyticsList.innerHTML = (analytics.agentPreference || []).map((item) => `
    <div class="analytics-item">
      <span>${escapeHtml(item.name)}</span>
      <strong>${escapeHtml(item.count)}</strong>
    </div>
  `).join("");
}

function renderMessages() {
  const session = state.currentSession;
  if (!session || !session.history?.length) {
    elements.chatLog.innerHTML = `
      <div class="empty-state">
        <p>从上面挑一个角色，系统会自动创建 7 天匿名会话，并送出第一句开场白。</p>
      </div>
    `;
    return;
  }

  elements.chatLog.innerHTML = "";
  const template = document.getElementById("message-template");

  session.history.forEach((message) => {
    const node = template.content.firstElementChild.cloneNode(true);
    node.classList.add(message.role);

    const presentation = getMessagePresentation(message);
    node.querySelector(".message-role").textContent =
      message.role === "assistant"
        ? session.agent.name
        : message.confidenceStatus === "choice"
          ? "你的选择"
          : "你";
    node.querySelector(".message-time").textContent = formatTime(message.createdAt);

    const sceneNode = node.querySelector(".message-scene");
    const actionNode = node.querySelector(".message-action");
    const speechNode = node.querySelector(".message-text");

    if (presentation.sceneText) {
      sceneNode.textContent = presentation.sceneText;
      sceneNode.classList.remove("hidden");
    } else {
      sceneNode.remove();
    }

    if (presentation.actionText) {
      actionNode.textContent = presentation.actionText;
      actionNode.classList.remove("hidden");
    } else {
      actionNode.remove();
    }

    speechNode.textContent = presentation.speechText || message.text || "";

    const badge = node.querySelector(".message-badge");
    const label = getReplySourceLabel(message.replySource);
    if (label && message.role === "assistant") {
      badge.textContent = label;
      badge.classList.add("is-visible");
    } else {
      badge.remove();
    }

    elements.chatLog.appendChild(node);
  });

  elements.chatLog.scrollTop = elements.chatLog.scrollHeight;
}

function renderChoicePanel() {
  const session = state.currentSession;
  const pendingChoices = session?.pendingChoices || [];
  const eventContext = session?.pendingEventContext || "";
  const lastTitle = session?.storyEventProgress?.lastTriggeredTitle || "剧情选择";

  state.pendingChoiceEvent = pendingChoices.length
    ? {
        title: lastTitle,
        context: eventContext,
        choices: pendingChoices
      }
    : null;

  if (!state.pendingChoiceEvent) {
    elements.choicePanel.classList.add("hidden");
    elements.choiceTitle.textContent = "剧情选择";
    elements.choiceContext.textContent = "当关系推进到关键节点时，这里会出现 2 到 3 个可选回应。";
    elements.choiceActions.innerHTML = "";
    updateComposerAvailability();
    return;
  }

  elements.choicePanel.classList.remove("hidden");
  elements.choiceTitle.textContent = state.pendingChoiceEvent.title;
  elements.choiceContext.textContent = state.pendingChoiceEvent.context || "你们来到一个关键节点，这次回应会影响后续路线。";
  elements.choiceActions.innerHTML = state.pendingChoiceEvent.choices.map((choice) => `
    <button class="choice-button" data-choice-id="${escapeHtml(choice.id)}" type="button">
      <span class="choice-label">${escapeHtml(choice.label)}</span>
      <span class="choice-hint">${escapeHtml(choice.toneHint)}</span>
    </button>
  `).join("");

  elements.choiceActions.querySelectorAll("[data-choice-id]").forEach((button) => {
    button.addEventListener("click", () => submitChoice(button.getAttribute("data-choice-id")));
  });

  updateComposerAvailability();
}

function renderCheckpointPanel() {
  const plotArcState = state.currentSession?.plotArcState;
  const summary = plotArcState?.latestArcSummary || null;

  state.pendingCheckpoint = plotArcState?.checkpointReady
    ? {
        title: summary?.title || `第 ${plotArcState.beatIndex || 10} 拍阶段总结`,
        summary
      }
    : null;

  if (!state.pendingCheckpoint) {
    elements.checkpointPanel.classList.add("hidden");
    elements.checkpointTitle.textContent = "当前阶段总结";
    elements.checkpointContext.textContent = "每 10 拍会出现一次阶段节点，你可以继续推进，也可以先结算这一阶段。";
    elements.checkpointSummary.innerHTML = "";
    updateComposerAvailability();
    return;
  }

  elements.checkpointPanel.classList.remove("hidden");
  elements.checkpointTitle.textContent = state.pendingCheckpoint.title;
  elements.checkpointContext.textContent = `当前已推进到第 ${plotArcState.beatIndex || 10} 拍，你可以继续推进下一阶段，或先结算这一阶段。`;
  elements.checkpointSummary.innerHTML = renderArcSummary(summary);
  elements.checkpointContinue.disabled = false;
  elements.checkpointSettle.disabled = !plotArcState?.canSettleScore;
  updateComposerAvailability();
}

function renderPresence(session) {
  const online = Boolean(session?.presenceState?.online);
  const typing = Boolean(session?.presenceState?.typing);
  elements.presenceBadge.textContent = typing ? "输入中" : online ? "在线" : "离线";
  elements.presenceBadge.className = `presence-badge ${online ? "is-online" : "is-offline"}`;
}

function renderRelationship() {
  const session = state.currentSession;
  if (!session) {
    elements.chatAgentName.textContent = "请选择角色";
    elements.chatAgentTagline.textContent = "选定角色后会自动开启 7 天匿名会话，聊天会从当前氛围自然展开。";
    elements.stageBadge.textContent = "未开始";
    elements.expiryBadge.textContent = "记忆未建立";
    elements.llmStatusBadge.textContent = "模型未运行";
    elements.llmStatusBadge.className = "llm-status-badge is-idle";
    elements.presenceBadge.textContent = "离线";
    elements.presenceBadge.className = "presence-badge is-offline";
    elements.timeContext.textContent = "等待会话";
    elements.weatherContext.textContent = "尚未设置城市";
    elements.sceneContext.textContent = "剧情尚未铺开";
    elements.scoreTotal.textContent = "0";
    elements.scoreCloseness.textContent = "0";
    elements.scoreTrust.textContent = "0";
    elements.scoreResonance.textContent = "0";
    elements.scoreBar.style.width = "0%";
    elements.stageProgressHint.textContent = "先把聊天聊顺，关系会慢慢往前走。";
    elements.relationshipFeedback.textContent = "这里会显示最近一轮关系变化的解释。";
    elements.endingHint.textContent = "当前结局倾向会随着关系阶段和关键事件变化。";
    elements.emotionSummary.innerHTML = renderEmotionSummary(null);
    elements.plotSummary.innerHTML = renderPlotSummary(null, null, null);
    elements.eventSummary.innerHTML = renderEventSummary(null);
    elements.memorySummary.innerHTML = renderMemorySummary(null);
    return;
  }

  const relationship = session.relationshipState || {};
  const runtimeStatus = getRuntimeStatusMeta(session);
  const plotArcState = session.plotArcState;
  const timeContext = session.timeContext;
  const weatherContext = session.weatherContext;
  const sceneState = session.sceneState;

  state.relationshipFeedback = relationship.relationshipFeedback || "";
  state.endingCandidate = relationship.endingCandidate || relationship.ending || plotArcState?.endingCandidate || "";

  elements.chatAgentName.textContent = `${session.agent.name} · ${session.agent.archetype}`;
  elements.chatAgentTagline.textContent = session.agent.tagline || "聊天会从当前氛围自然展开。";
  elements.stageBadge.textContent = relationship.relationshipStage || "初识";
  elements.expiryBadge.textContent = `记忆保留至 ${formatDateTime(session.memoryExpireAt)}`;
  elements.llmStatusBadge.textContent = runtimeStatus.label;
  elements.llmStatusBadge.className = `llm-status-badge ${runtimeStatus.className}`;
  elements.llmStatusBadge.title = runtimeStatus.title;
  renderPresence(session);

  elements.timeContext.textContent = timeContext ? `${timeContext.dayPart} · ${timeContext.localTime}` : "等待会话";
  elements.weatherContext.textContent = weatherContext?.city
    ? `${weatherContext.city} · ${weatherContext.summary || "天气回退"}${weatherContext.temperatureC ?? weatherContext.temperatureC === 0 ? ` · ${weatherContext.temperatureC}°C` : ""}`
    : "尚未设置城市";
  elements.sceneContext.textContent = sceneState?.sceneSummary || plotArcState?.sceneFrame || session.plotState?.sceneFrame || "剧情尚未铺开";

  elements.scoreTotal.textContent = relationship.affectionScore ?? 0;
  elements.scoreCloseness.textContent = relationship.closeness ?? 0;
  elements.scoreTrust.textContent = relationship.trust ?? 0;
  elements.scoreResonance.textContent = relationship.resonance ?? 0;
  elements.scoreBar.style.width = `${Math.min(100, relationship.affectionScore || 0)}%`;
  elements.stageProgressHint.textContent = relationship.stageProgressHint || "先顺着这段聊天慢慢往前走。";
  elements.relationshipFeedback.textContent = relationship.relationshipFeedback || session.presenceState?.heartbeatExplain || "这里会显示最近一轮关系变化的解释。";
  elements.endingHint.textContent = state.endingCandidate
    ? `当前结局倾向：${state.endingCandidate}`
    : "当前结局倾向会随着关系阶段和关键事件变化。";

  elements.emotionSummary.innerHTML = renderEmotionSummary(session.emotionState);
  elements.plotSummary.innerHTML = renderPlotSummary(plotArcState, session.plotState, session.presenceState);
  elements.eventSummary.innerHTML = renderEventSummary(session.storyEventProgress);
  elements.memorySummary.innerHTML = renderMemorySummary(session.memorySummary);
}

function getDrawerContent(drawer) {
  if (drawer === "relationship") {
    return {
      title: drawerLabels.relationship,
      html: `
        <div class="score-grid">
          <div><span>总好感</span><strong>${escapeHtml(elements.scoreTotal.textContent)}</strong></div>
          <div><span>亲近</span><strong>${escapeHtml(elements.scoreCloseness.textContent)}</strong></div>
          <div><span>信任</span><strong>${escapeHtml(elements.scoreTrust.textContent)}</strong></div>
          <div><span>默契</span><strong>${escapeHtml(elements.scoreResonance.textContent)}</strong></div>
        </div>
        <div class="progress-line">
          <div class="progress-fill" style="width:${escapeHtml(elements.scoreBar.style.width || "0%")}"></div>
        </div>
        <p class="panel-note">${escapeHtml(elements.stageProgressHint.textContent)}</p>
        <p class="panel-note">${escapeHtml(elements.relationshipFeedback.textContent)}</p>
        <p class="panel-note">${escapeHtml(elements.endingHint.textContent)}</p>
        ${renderEmotionSummary(state.currentSession?.emotionState)}
      `
    };
  }

  if (drawer === "plot") {
    return {
      title: drawerLabels.plot,
      html: `${renderPlotSummary(state.currentSession?.plotArcState, state.currentSession?.plotState, state.currentSession?.presenceState)}${renderEventSummary(state.currentSession?.storyEventProgress)}`
    };
  }

  if (drawer === "memory") {
    return {
      title: drawerLabels.memory,
      html: renderMemorySummary(state.currentSession?.memorySummary)
    };
  }

  return {
    title: drawerLabels.analytics,
    html: `
      <div class="analytics-grid">${elements.analyticsGrid.innerHTML}</div>
      <div class="analytics-list">${elements.analyticsList.innerHTML}</div>
    `
  };
}

function setLoading(isLoading) {
  elements.sendButton.disabled = isLoading;
  elements.messageInput.disabled = isLoading || Boolean(state.pendingChoiceEvent) || Boolean(state.pendingCheckpoint);
  elements.sendButton.textContent = isLoading ? "发送中..." : "发送";
}

function updateComposerAvailability(isLoading = false) {
  const blocked = isLoading || Boolean(state.pendingChoiceEvent) || Boolean(state.pendingCheckpoint);
  elements.messageInput.disabled = blocked;
  elements.sendButton.disabled = blocked;
  elements.composer.classList.toggle("is-disabled", blocked);
}

function syncContextInput(session) {
  const city = session?.visitorContext?.preferredCity || localStorage.getItem(storageKeys.preferredCity) || "";
  elements.cityInput.value = city;
}

function renderAll() {
  renderUiMode();
  renderHero();
  renderAgents();
  renderMessages();
  renderRelationship();
  renderChoicePanel();
  renderCheckpointPanel();
  renderAnalytics();
}

async function loadAnalytics() {
  state.analytics = await api("/api/analytics/overview");
  renderAnalytics();
  renderUiMode();
}

async function saveVisitorContext() {
  if (!state.visitorId) {
    return;
  }

  const preferredCity = elements.cityInput.value.trim();
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Shanghai";
  const result = await api("/api/visitor/context", {
    method: "POST",
    body: JSON.stringify({
      visitor_id: state.visitorId,
      timezone,
      preferred_city: preferredCity
    })
  });

  localStorage.setItem(storageKeys.preferredCity, preferredCity);

  if (state.currentSession) {
    state.currentSession.visitorContext = {
      timezone: result.timezone,
      preferredCity: result.preferredCity
    };
    state.currentSession.timeContext = result.timeContext;
    state.currentSession.weatherContext = result.weatherContext;
    renderRelationship();
  }
}

function stopPresenceLoop() {
  if (state.presenceTimer) {
    clearInterval(state.presenceTimer);
    state.presenceTimer = null;
  }
}

function ensurePresenceListeners() {
  if (state.bootedPresenceListeners) {
    return;
  }

  state.bootedPresenceListeners = true;

  document.addEventListener("visibilitychange", () => {
    pingPresence();
  });

  window.addEventListener("focus", () => {
    pingPresence();
  });

  window.addEventListener("blur", () => {
    pingPresence();
  });

  window.addEventListener("resize", () => {
    if (window.innerWidth <= 980 && state.uiMode !== "immersive") {
      state.uiMode = "immersive";
    }
    renderUiMode();
  });

  elements.messageInput.addEventListener("input", () => {
    state.draftLength = elements.messageInput.value.length;
    state.isTyping = elements.messageInput.value.trim().length > 0;
    state.lastInputAt = new Date().toISOString();

    if (state.typingTimer) {
      clearTimeout(state.typingTimer);
    }
    if (state.pingDebounceTimer) {
      clearTimeout(state.pingDebounceTimer);
    }

    state.pingDebounceTimer = window.setTimeout(() => {
      pingPresence();
    }, 220);

    state.typingTimer = window.setTimeout(() => {
      state.isTyping = elements.messageInput.value.trim().length > 0;
      pingPresence();
    }, 1200);
  });
}

function startPresenceLoop() {
  stopPresenceLoop();
  if (!state.currentSession) {
    return;
  }
  ensurePresenceListeners();
  pingPresence();
  state.presenceTimer = window.setInterval(() => {
    pingPresence();
  }, 15000);
}

async function pingPresence() {
  if (!state.currentSession || !state.visitorId) {
    return;
  }

  try {
    const result = await api("/api/session/presence", {
      method: "POST",
      body: JSON.stringify({
        visitor_id: state.visitorId,
        session_id: state.currentSession.sessionId,
        visible: !document.hidden,
        focused: document.hasFocus(),
        is_typing: state.isTyping,
        draft_length: state.draftLength,
        last_input_at: state.lastInputAt,
        client_time: new Date().toISOString()
      })
    });

    if (state.currentSession) {
      state.currentSession.presenceState = result.presenceState;
    }

    renderRelationship();

    if (result.proactive_message) {
      await hydrateSession(state.currentSession.sessionId);
    }
  } catch (error) {
    console.warn("presence ping failed", error);
  }
}

async function hydrateSession(sessionId) {
  const session = await api(`/api/session/state?session_id=${encodeURIComponent(sessionId)}`);
  state.currentSession = session;
  state.currentAgentId = session.agent.id;
  syncContextInput(session);
  renderAll();
  updateComposerAvailability();
  startPresenceLoop();
}

async function startSession(agentId) {
  state.currentAgentId = agentId;
  renderHero();
  renderAgents();

  const session = await api("/api/session/start", {
    method: "POST",
    body: JSON.stringify({
      visitor_id: state.visitorId,
      agent_id: agentId
    })
  });

  state.currentSession = session;
  state.currentAgentId = agentId;
  localStorage.setItem(storageKeys.sessionId, session.sessionId);
  localStorage.setItem(storageKeys.agentId, agentId);
  renderAll();
  updateComposerAvailability();
  startPresenceLoop();
  await loadAnalytics();
}

async function submitChoice(choiceId) {
  if (!state.currentSession) {
    return;
  }

  try {
    const result = await api("/api/event/choose", {
      method: "POST",
      body: JSON.stringify({
        visitor_id: state.visitorId,
        session_id: state.currentSession.sessionId,
        choice_id: choiceId
      })
    });

    await hydrateSession(state.currentSession.sessionId);
    if (result.relationship_feedback) {
      elements.relationshipFeedback.textContent = result.relationship_feedback;
    }
    if (result.ending_candidate) {
      elements.endingHint.textContent = `当前结局倾向：${result.ending_candidate}`;
    }
    await loadAnalytics();
  } catch (error) {
    alert(error.message);
  }
}

async function continueCheckpoint() {
  if (!state.currentSession) {
    return;
  }

  try {
    await api("/api/session/checkpoint", {
      method: "POST",
      body: JSON.stringify({
        visitor_id: state.visitorId,
        session_id: state.currentSession.sessionId
      })
    });
    await hydrateSession(state.currentSession.sessionId);
  } catch (error) {
    alert(error.message);
  }
}

async function settleCheckpoint() {
  if (!state.currentSession) {
    return;
  }

  try {
    await api("/api/session/settle", {
      method: "POST",
      body: JSON.stringify({
        visitor_id: state.visitorId,
        session_id: state.currentSession.sessionId
      })
    });
    await hydrateSession(state.currentSession.sessionId);
  } catch (error) {
    alert(error.message);
  }
}

async function boot() {
  const storedVisitorId = localStorage.getItem(storageKeys.visitorId) || "";
  const visitorPayload = await api("/api/visitor/init", {
    method: "POST",
    body: JSON.stringify({ visitor_id: storedVisitorId })
  });

  state.visitorId = visitorPayload.visitorId;
  localStorage.setItem(storageKeys.visitorId, state.visitorId);
  elements.visitorId.textContent = state.visitorId.slice(0, 18);

  state.agents = await api("/api/agents");

  const savedAgentId = localStorage.getItem(storageKeys.agentId) || state.agents[0]?.id || "";
  state.currentAgentId = savedAgentId;

  const rememberedCity = localStorage.getItem(storageKeys.preferredCity) || visitorPayload.preferredCity || "";
  elements.cityInput.value = rememberedCity;
  await saveVisitorContext();

  if (visitorPayload.restoredSession) {
    await hydrateSession(visitorPayload.restoredSession.sessionId);
    localStorage.setItem(storageKeys.agentId, visitorPayload.restoredSession.agentId);
    localStorage.setItem(storageKeys.sessionId, visitorPayload.restoredSession.sessionId);
  } else {
    const savedSessionId = localStorage.getItem(storageKeys.sessionId);
    if (savedSessionId) {
      try {
        await hydrateSession(savedSessionId);
      } catch {
        localStorage.removeItem(storageKeys.sessionId);
      }
    } else {
      renderAll();
    }
  }

  renderAll();
  await loadAnalytics();
}

elements.modeImmersive.addEventListener("click", () => {
  setUiMode("immersive");
});

elements.modeInspector.addEventListener("click", () => {
  setUiMode("inspector");
});

elements.heroAgentButton.addEventListener("click", async () => {
  const agent = getSelectedAgent();
  if (!agent) {
    return;
  }
  if (state.currentSession?.agent?.id === agent.id) {
    await hydrateSession(state.currentSession.sessionId);
    return;
  }
  await startSession(agent.id);
});

elements.insightButtons.forEach((button) => {
  button.addEventListener("click", () => {
    const drawer = button.dataset.drawer;
    state.activeDrawer = state.activeDrawer === drawer ? "none" : drawer;
    renderUiMode();
  });
});

elements.sheetClose.addEventListener("click", () => {
  closeDrawer();
});

elements.sheetBackdrop.addEventListener("click", () => {
  closeDrawer();
});

elements.composer.addEventListener("submit", async (event) => {
  event.preventDefault();

  if (!state.currentSession) {
    alert("请先选择一个角色。");
    return;
  }

  if (state.pendingChoiceEvent) {
    alert("先完成当前剧情选择，再继续自由聊天。");
    return;
  }

  if (state.pendingCheckpoint) {
    alert("先处理当前阶段总结，再继续聊天。");
    return;
  }

  const value = elements.messageInput.value.trim();
  if (!value) {
    return;
  }

  setLoading(true);
  try {
    const result = await api("/api/chat/send", {
      method: "POST",
      body: JSON.stringify({
        visitor_id: state.visitorId,
        session_id: state.currentSession.sessionId,
        agent_id: state.currentSession.agent.id,
        user_message: value
      })
    });

    elements.messageInput.value = "";
    state.isTyping = false;
    state.draftLength = 0;
    state.lastInputAt = new Date().toISOString();

    await hydrateSession(state.currentSession.sessionId);

    if (result.relationship_feedback) {
      elements.relationshipFeedback.textContent = result.relationship_feedback;
    }
    if (result.ending_candidate) {
      elements.endingHint.textContent = `当前结局倾向：${result.ending_candidate}`;
    }

    await loadAnalytics();
  } catch (error) {
    alert(error.message);
  } finally {
    setLoading(false);
  }
});

elements.feedbackForm.addEventListener("submit", async (event) => {
  event.preventDefault();

  if (!state.currentSession) {
    alert("请先开始一段会话。");
    return;
  }

  await api("/api/feedback", {
    method: "POST",
    body: JSON.stringify({
      visitor_id: state.visitorId,
      session_id: state.currentSession.sessionId,
      agent_id: state.currentSession.agent.id,
      rating: Number(elements.feedbackRating.value || 4),
      liked_point: elements.feedbackLiked.value.trim(),
      improvement_point: elements.feedbackImprove.value.trim(),
      continue_intent: elements.feedbackContinue.checked
    })
  });

  elements.feedbackLiked.value = "";
  elements.feedbackImprove.value = "";
  alert("反馈已收到，感谢你帮我们继续把体验磨得更顺。");
  await loadAnalytics();
});

elements.refreshAnalytics.addEventListener("click", async () => {
  await loadAnalytics();
});

elements.saveContext.addEventListener("click", async () => {
  try {
    await saveVisitorContext();
    if (state.currentSession) {
      await hydrateSession(state.currentSession.sessionId);
    }
  } catch (error) {
    alert(error.message);
  }
});

elements.checkpointContinue.addEventListener("click", async () => {
  await continueCheckpoint();
});

elements.checkpointSettle.addEventListener("click", async () => {
  await settleCheckpoint();
});

boot().catch((error) => {
  console.error(error);
  alert(`初始化失败：${error.message}`);
});
