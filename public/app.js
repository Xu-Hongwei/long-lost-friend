const storageKeys = {
  visitorId: "campus-agent-visitor-id",
  sessionId: "campus-agent-session-id",
  agentId: "campus-agent-agent-id",
  preferredCity: "campus-agent-preferred-city"
};

const state = {
  visitorId: "",
  agents: [],
  currentAgentId: "",
  currentSession: null,
  analytics: null,
  pendingChoiceEvent: null,
  relationshipFeedback: "",
  endingCandidate: "",
  presenceTimer: null,
  bootedPresenceListeners: false
};

const elements = {
  visitorId: document.getElementById("visitor-id"),
  cityInput: document.getElementById("city-input"),
  saveContext: document.getElementById("save-context"),
  agentGrid: document.getElementById("agent-grid"),
  chatAgentName: document.getElementById("chat-agent-name"),
  chatAgentTagline: document.getElementById("chat-agent-tagline"),
  stageBadge: document.getElementById("stage-badge"),
  presenceBadge: document.getElementById("presence-badge"),
  expiryBadge: document.getElementById("expiry-badge"),
  llmStatusBadge: document.getElementById("llm-status-badge"),
  timeContext: document.getElementById("time-context"),
  weatherContext: document.getElementById("weather-context"),
  sceneContext: document.getElementById("scene-context"),
  chatLog: document.getElementById("chat-log"),
  composer: document.getElementById("composer"),
  messageInput: document.getElementById("message-input"),
  sendButton: document.getElementById("send-button"),
  choicePanel: document.getElementById("choice-panel"),
  choiceTitle: document.getElementById("choice-title"),
  choiceContext: document.getElementById("choice-context"),
  choiceActions: document.getElementById("choice-actions"),
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
  refreshAnalytics: document.getElementById("refresh-analytics")
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
  if (!isoString) return "--:--";
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

function setLoading(isLoading) {
  elements.sendButton.disabled = isLoading;
  elements.messageInput.disabled = isLoading || Boolean(state.pendingChoiceEvent);
  elements.sendButton.textContent = isLoading ? "发送中..." : "发送";
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
    return { label: "远程降级", className: "is-fallback", title: "远程模型失败，已使用本地兜底" };
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
  return { label: "状态未知", className: "is-idle", title: "暂时无法判断当前模型来源" };
}

function getMoodLabel(mood) {
  const labels = {
    stressed: "有点紧绷",
    warm: "柔和靠近",
    curious: "继续追问",
    neutral: "平稳聊天",
    calm: "安静靠近",
    teasing: "微微试探",
    protective: "想接住你",
    uneasy: "有点谨慎"
  };
  return labels[mood] || "暂未识别";
}

function getIntentLabel(intent) {
  const labels = {
    plan: "在抛出计划",
    question: "在等一个回答",
    share: "在认真分享",
    chat: "轻松闲聊"
  };
  return labels[intent] || "普通聊天";
}

function getCadenceLabel(cadence) {
  const labels = {
    soft_pause: "慢一点接住",
    answer_first: "先答再展开",
    lean_in: "更靠近一点",
    light_ping: "轻巧接话",
    cinematic: "画面感更强",
    steady_flow: "自然顺流"
  };
  return labels[cadence] || "自然顺流";
}

function getReplySourceLabel(replySource) {
  const labels = {
    user_turn: "当前回合",
    plot_push: "顺势推进",
    silence_heartbeat: "静默接话",
    long_chat_heartbeat: "长聊心跳",
    choice_result: "剧情结果",
    choice: "剧情选择"
  };
  return labels[replySource] || "";
}

function looksLikeActionLine(text) {
  if (!text) {
    return false;
  }
  const actionCues = [
    "视线", "目光", "眼神", "眉眼", "指尖", "手心", "手指", "发梢", "衣角", "袖口",
    "抬眼", "垂眼", "偏头", "侧过", "站在", "靠着", "望向", "看向", "看着", "落在",
    "轻声", "压低", "停了停", "顿了顿", "笑意", "呼吸", "雨", "风", "窗", "灯", "夜",
    "门口", "走廊", "桌上", "杯沿", "沉默", "安静", "耳后"
  ];
  return actionCues.some((cue) => text.includes(cue));
}

function splitAssistantMessage(text) {
  const normalized = String(text ?? "").trim();
  if (!normalized) {
    return { actionText: "", speechText: "" };
  }

  const bracketLead = normalized.match(/^[（(【〔]([^）)】〕]{2,48})[）)】〕]\s*(.+)$/);
  if (bracketLead) {
    return {
      actionText: bracketLead[1].trim(),
      speechText: bracketLead[2].trim()
    };
  }

  const firstQuestionIndex = (() => {
    const indexes = ["？", "?", "！", "!"]
      .map((mark) => normalized.indexOf(mark))
      .filter((index) => index >= 0);
    return indexes.length ? Math.min(...indexes) : -1;
  })();

  if (firstQuestionIndex > 0) {
    const beforeQuestion = normalized.slice(0, firstQuestionIndex).trim();
    const lastComma = Math.max(beforeQuestion.lastIndexOf("，"), beforeQuestion.lastIndexOf(","));
    if (lastComma >= 8) {
      const actionText = beforeQuestion.slice(0, lastComma).trim();
      const speechText = normalized.slice(lastComma + 1).trim();
      if (looksLikeActionLine(actionText) && speechText.length >= 4) {
        return { actionText, speechText };
      }
    }
  }

  const sentences = normalized.match(/[^。！？!?]+[。！？!?]?/g) || [normalized];
  if (sentences.length >= 2) {
    const firstSentence = sentences[0].trim();
    const rest = sentences.slice(1).join("").trim();
    if (looksLikeActionLine(firstSentence) && rest) {
      return {
        actionText: firstSentence,
        speechText: rest
      };
    }
  }

  return { actionText: "", speechText: normalized };
}

function getMessagePresentation(message) {
  const rawText = String(message?.text ?? "").trim();
  if (message?.role !== "assistant") {
    return { actionText: "", speechText: rawText };
  }

  const explicitAction = String(message?.actionText ?? "").trim();
  const explicitSpeech = String(message?.speechText ?? "").trim();
  if (explicitAction || explicitSpeech) {
    return {
      actionText: explicitAction,
      speechText: explicitSpeech || rawText
    };
  }

  return splitAssistantMessage(rawText);
}

function renderMemoryGroup(title, items, tone = "") {
  if (!items?.length) {
    return "";
  }
  const chips = items
    .map((item) => `<span class="memory-chip ${tone}">${escapeHtml(item)}</span>`)
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
    renderMemoryGroup("强记忆", memory.strongMemories, "memory-chip--story"),
    renderMemoryGroup("弱记忆", memory.weakMemories, "memory-chip--warm"),
    renderMemoryGroup("临时记忆", memory.temporaryMemories, "memory-chip--accent"),
    renderMemoryGroup("偏好", memory.preferences, "memory-chip--warm"),
    renderMemoryGroup("身份信息", memory.identityNotes),
    renderMemoryGroup("约定与计划", memory.promises, "memory-chip--accent"),
    renderMemoryGroup("待回应线索", memory.openLoops, "memory-chip--accent"),
    renderMemoryGroup("共同经历", memory.sharedMoments, "memory-chip--story"),
    renderMemoryGroup("最近话题", memory.discussedTopics),
    renderMemoryGroup("近期情绪线索", memory.emotionalNotes, "memory-chip--soft"),
    renderMemoryGroup("角色惦记着的事", memory.assistantOwnedThreads, "memory-chip--story"),
    renderMemoryGroup("适合主动回调", memory.callbackCandidates, "memory-chip--accent"),
    renderMemoryGroup("关系进展", memory.milestones, "memory-chip--story")
  ].filter(Boolean);

  const meta = `
    <div class="memory-meta">
      <span class="memory-meta-pill">最近情绪：${escapeHtml(getMoodLabel(memory.lastUserMood))}</span>
      <span class="memory-meta-pill">最近意图：${escapeHtml(getIntentLabel(memory.lastUserIntent))}</span>
      <span class="memory-meta-pill">回复节奏：${escapeHtml(getCadenceLabel(memory.lastResponseCadence))}</span>
      <span class="memory-meta-pill">记忆使用：${escapeHtml(memory.lastMemoryUseMode || "hold")}</span>
    </div>
    <p class="panel-note">${escapeHtml(memory.lastMemoryRelevanceReason || "当前没有需要特意提起的记忆。")}</p>
  `;

  return `${meta}<div class="memory-groups">${groups.join("")}</div>`;
}

function renderEmotionSummary(emotion) {
  if (!emotion) {
    return "<p>还没有足够的数据来判断角色的情感状态。</p>";
  }

  const items = [
    ["柔软感", emotion.warmth],
    ["安全感", emotion.safety],
    ["想靠近", emotion.longing],
    ["主动性", emotion.initiative],
    ["愿暴露自己", emotion.vulnerability]
  ];

  return `
    <div class="emotion-meter-list">
      ${items
        .map(
          ([label, value]) => `
            <div class="emotion-meter">
              <div class="emotion-meter-head">
                <span>${label}</span>
                <strong>${value}</strong>
              </div>
              <div class="progress-line is-mini">
                <div class="progress-fill" style="width:${Math.min(100, value)}%"></div>
              </div>
            </div>
          `
        )
        .join("")}
    </div>
    <p class="panel-note">当前心情：${escapeHtml(getMoodLabel(emotion.currentMood))}</p>
  `;
}

function renderPlotSummary(plotState) {
  if (!plotState) {
    return "<p>剧情还在等待铺开。</p>";
  }

  const threads = plotState.openThreads?.length
    ? `<div class="event-chip-list">${plotState.openThreads.map((item) => `<span class="event-chip">${escapeHtml(item)}</span>`).join("")}</div>`
    : "";

  return `
    <p><strong>${escapeHtml(plotState.plotProgress || "第 0/10 拍 · 相识")}</strong></p>
    <p>${escapeHtml(plotState.sceneFrame || "剧情还在缓慢铺开。")}</p>
    <p class="panel-note">下一步期待：${escapeHtml(plotState.nextBeatHint || "先把日常节奏聊顺。")}</p>
    ${threads}
  `;
}

function renderEventSummary(progress) {
  if (!progress) {
    return "<p>尚未触发关键剧情。</p>";
  }

  const items = [];
  if (progress.lastTriggeredTitle) {
    items.push(`<p><strong>最近剧情：</strong>${escapeHtml(progress.lastTriggeredTitle)}</p>`);
  }
  if (progress.lastTriggeredTheme) {
    items.push(`<p><strong>剧情主题：</strong>${escapeHtml(progress.lastTriggeredTheme)}</p>`);
  }
  if (progress.currentRouteTheme) {
    items.push(`<p><strong>当前路线：</strong>${escapeHtml(progress.currentRouteTheme)}</p>`);
  }
  if (progress.nextExpectedDirection) {
    items.push(`<p><strong>可期待方向：</strong>${escapeHtml(progress.nextExpectedDirection)}</p>`);
  }
  if (progress.triggeredEventIds?.length) {
    items.push(
      `<div class="event-chip-list">${progress.triggeredEventIds
        .map((item) => `<span class="event-chip">${escapeHtml(item)}</span>`)
        .join("")}</div>`
    );
  }
  return items.length ? items.join("") : "<p>尚未触发关键剧情。</p>";
}

function renderAgents() {
  elements.agentGrid.innerHTML = "";
  state.agents.forEach((agent) => {
    const card = document.createElement("button");
    card.type = "button";
    card.className = `agent-card ${state.currentAgentId === agent.id ? "active" : ""}`;
    card.innerHTML = `
      <div class="agent-avatar" style="background: linear-gradient(135deg, ${agent.palette[0]}, ${agent.palette[1]})">${agent.avatarGlyph}</div>
      <div>
        <p class="section-kicker">${escapeHtml(agent.archetype)}</p>
        <h3>${escapeHtml(agent.name)}</h3>
        <p>${escapeHtml(agent.tagline)}</p>
      </div>
      <div class="agent-tags">${agent.likes.slice(0, 3).map((item) => `<span class="agent-tag">${escapeHtml(item)}</span>`).join("")}</div>
    `;
    card.addEventListener("click", () => startSession(agent.id));
    elements.agentGrid.appendChild(card);
  });
}

function renderMessages() {
  const session = state.currentSession;
  if (!session || !session.history?.length) {
    elements.chatLog.innerHTML = `<div class="empty-state"><p>从左侧挑一个角色，系统会自动创建 7 天匿名会话，并送出第一句开场白。</p></div>`;
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
    const actionNode = node.querySelector(".message-action");
    const speechNode = node.querySelector(".message-text");
    if (presentation.actionText) {
      actionNode.textContent = presentation.actionText;
      actionNode.classList.remove("hidden");
    } else {
      actionNode.remove();
    }
    speechNode.textContent = presentation.speechText || message.text;
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
    elements.choiceActions.innerHTML = "";
    elements.choiceContext.textContent = "当关系推进到关键节点时，这里会出现 2 到 3 个可选回应。";
    elements.choiceTitle.textContent = "剧情选择";
    elements.messageInput.disabled = false;
    elements.sendButton.disabled = false;
    elements.composer.classList.remove("is-disabled");
    return;
  }

  elements.choicePanel.classList.remove("hidden");
  elements.choiceTitle.textContent = state.pendingChoiceEvent.title;
  elements.choiceContext.textContent = state.pendingChoiceEvent.context || "你们来到了一个关键节点，这次回应会影响后续路线。";
  elements.choiceActions.innerHTML = state.pendingChoiceEvent.choices
    .map(
      (choice) => `
        <button class="choice-button" data-choice-id="${escapeHtml(choice.id)}" type="button">
          <span class="choice-label">${escapeHtml(choice.label)}</span>
          <span class="choice-hint">${escapeHtml(choice.toneHint)}</span>
        </button>
      `
    )
    .join("");

  elements.choiceActions.querySelectorAll("[data-choice-id]").forEach((button) => {
    button.addEventListener("click", () => submitChoice(button.getAttribute("data-choice-id")));
  });

  elements.messageInput.disabled = true;
  elements.sendButton.disabled = true;
  elements.composer.classList.add("is-disabled");
}

function renderPresence(session) {
  const online = Boolean(session?.presenceState?.online);
  elements.presenceBadge.textContent = online ? "在线" : "离线";
  elements.presenceBadge.className = `presence-badge ${online ? "is-online" : "is-offline"}`;
}

function renderRelationship() {
  const session = state.currentSession;
  if (!session) {
    elements.chatAgentName.textContent = "请选择角色";
    elements.chatAgentTagline.textContent = "选定角色后会自动开启匿名会话，并进入聊天。";
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
    elements.stageProgressHint.textContent = "先把聊天聊顺，关系就会慢慢抬头。";
    elements.relationshipFeedback.textContent = "这里会显示最近一轮关系变化的解释。";
    elements.endingHint.textContent = "当前结局倾向会随着关系阶段和关键事件变化。";
    elements.emotionSummary.innerHTML = renderEmotionSummary(null);
    elements.plotSummary.innerHTML = renderPlotSummary(null);
    elements.memorySummary.innerHTML = renderMemorySummary(null);
    elements.eventSummary.innerHTML = "<p>尚未触发关键剧情。</p>";
    renderChoicePanel();
    return;
  }

  const relationship = session.relationshipState;
  const runtimeStatus = getRuntimeStatusMeta(session);
  state.relationshipFeedback = relationship.relationshipFeedback || "";
  state.endingCandidate = relationship.endingCandidate || relationship.ending || "";

  elements.chatAgentName.textContent = `${session.agent.name} · ${session.agent.archetype}`;
  elements.chatAgentTagline.textContent = session.agent.tagline;
  elements.stageBadge.textContent = relationship.relationshipStage;
  elements.expiryBadge.textContent = `记忆保留至 ${formatDateTime(session.memoryExpireAt)}`;
  elements.llmStatusBadge.textContent = runtimeStatus.label;
  elements.llmStatusBadge.className = `llm-status-badge ${runtimeStatus.className}`;
  elements.llmStatusBadge.title = runtimeStatus.title;
  renderPresence(session);

  const timeContext = session.timeContext;
  const weatherContext = session.weatherContext;
  elements.timeContext.textContent = timeContext ? `${timeContext.dayPart} · ${timeContext.localTime}` : "等待会话";
  elements.weatherContext.textContent = weatherContext?.city
    ? `${weatherContext.city} · ${weatherContext.summary || "天气回退"}${weatherContext.temperatureC ?? weatherContext.temperatureC === 0 ? ` · ${weatherContext.temperatureC}°C` : ""}`
    : "尚未设置城市";
  elements.sceneContext.textContent = session.plotState?.sceneFrame || "剧情尚未铺开";

  elements.scoreTotal.textContent = relationship.affectionScore;
  elements.scoreCloseness.textContent = relationship.closeness;
  elements.scoreTrust.textContent = relationship.trust;
  elements.scoreResonance.textContent = relationship.resonance;
  elements.scoreBar.style.width = `${Math.min(100, relationship.affectionScore)}%`;
  elements.stageProgressHint.textContent = relationship.stageProgressHint || "继续聊天，关系会慢慢往前走。";
  elements.relationshipFeedback.textContent = relationship.relationshipFeedback || "这里会显示最近一轮关系变化的解释。";
  elements.endingHint.textContent = state.endingCandidate
    ? `当前结局倾向：${state.endingCandidate}`
    : "当前结局倾向会随着关系阶段和关键事件变化。";

  elements.emotionSummary.innerHTML = renderEmotionSummary(session.emotionState);
  elements.plotSummary.innerHTML = renderPlotSummary(session.plotState);
  elements.memorySummary.innerHTML = renderMemorySummary(session.memorySummary);
  elements.eventSummary.innerHTML = renderEventSummary(session.storyEventProgress);
  renderChoicePanel();
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
    ["平均时长(分)", analytics.avgSessionMinutes],
    ["7 天续玩率", `${analytics.retention7d}%`],
    ["反馈率", `${analytics.feedbackCompletionRate}%`]
  ];

  elements.analyticsGrid.innerHTML = tiles
    .map(
      ([label, value]) => `
        <div class="analytics-tile">
          <span>${label}</span>
          <strong>${value}</strong>
        </div>
      `
    )
    .join("");

  elements.analyticsList.innerHTML = analytics.agentPreference
    .map(
      (item) => `
        <div class="analytics-item">
          <span>${item.name}</span>
          <strong>${item.count}</strong>
        </div>
      `
    )
    .join("");
}

async function loadAnalytics() {
  state.analytics = await api("/api/analytics/overview");
  renderAnalytics();
}

function syncContextInput(session) {
  const city =
    session?.visitorContext?.preferredCity ||
    localStorage.getItem(storageKeys.preferredCity) ||
    "";
  elements.cityInput.value = city;
}

async function hydrateSession(sessionId) {
  const session = await api(`/api/session/state?session_id=${encodeURIComponent(sessionId)}`);
  state.currentSession = session;
  state.currentAgentId = session.agent.id;
  renderAgents();
  renderMessages();
  renderRelationship();
  syncContextInput(session);
  startPresenceLoop();
}

async function saveVisitorContext() {
  if (!state.visitorId) return;
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
}

function stopPresenceLoop() {
  if (state.presenceTimer) {
    clearInterval(state.presenceTimer);
    state.presenceTimer = null;
  }
}

function startPresenceLoop() {
  stopPresenceLoop();
  if (!state.currentSession) {
    return;
  }
  ensurePresenceListeners();
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
        client_time: new Date().toISOString()
      })
    });

    if (state.currentSession) {
      state.currentSession.presenceState = result.presenceState;
    }
    renderPresence(state.currentSession);

    if (result.proactive_message) {
      await hydrateSession(state.currentSession.sessionId);
    }
  } catch (error) {
    console.warn("presence ping failed", error);
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
  renderAgents();

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
    }
  }

  await loadAnalytics();
}

async function startSession(agentId) {
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
  renderAgents();
  renderMessages();
  renderRelationship();
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
      rating: Number(document.getElementById("feedback-rating").value || 4),
      liked_point: document.getElementById("feedback-liked").value.trim(),
      improvement_point: document.getElementById("feedback-improve").value.trim(),
      continue_intent: document.getElementById("feedback-continue").checked
    })
  });

  document.getElementById("feedback-liked").value = "";
  document.getElementById("feedback-improve").value = "";
  alert("反馈已收到，感谢你帮助我们校准试点方向。");
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

boot().catch((error) => {
  console.error(error);
  alert(`初始化失败：${error.message}`);
});
