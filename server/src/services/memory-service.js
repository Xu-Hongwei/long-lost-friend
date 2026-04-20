function pushUniqueLimited(target, value, limit = 6) {
  if (!value) {
    return;
  }

  if (!target.includes(value)) {
    target.unshift(value);
  }

  target.splice(limit);
}

function extractMatches(text, patterns) {
  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match?.[1]) {
      return match[1].trim().replace(/[。！？.!?]+$/, "");
    }
  }

  return "";
}

export class MemoryService {
  constructor(retentionMs) {
    this.retentionMs = retentionMs;
  }

  createMemorySummary(nowIso) {
    return {
      preferences: [],
      identityNotes: [],
      promises: [],
      milestones: [],
      updatedAt: nowIso
    };
  }

  createSessionMemoryExpiry(now) {
    return new Date(now.getTime() + this.retentionMs).toISOString();
  }

  isExpired(session, now = new Date()) {
    return Date.parse(session.memoryExpireAt) <= now.getTime();
  }

  getShortTermContext(messages, limit = 20) {
    return messages.slice(-limit).map((message) => ({
      role: message.role,
      text: message.text
    }));
  }

  getSummaryText(summary) {
    const chunks = [];

    if (summary.preferences.length) {
      chunks.push(`用户偏好：${summary.preferences.join("；")}`);
    }
    if (summary.identityNotes.length) {
      chunks.push(`用户信息：${summary.identityNotes.join("；")}`);
    }
    if (summary.promises.length) {
      chunks.push(`约定事项：${summary.promises.join("；")}`);
    }
    if (summary.milestones.length) {
      chunks.push(`重要进展：${summary.milestones.join("；")}`);
    }

    return chunks.join("\n");
  }

  updateSummary(summary, userMessage, event, relationshipStage, nowIso) {
    const next = {
      preferences: [...summary.preferences],
      identityNotes: [...summary.identityNotes],
      promises: [...summary.promises],
      milestones: [...summary.milestones],
      updatedAt: nowIso
    };

    const preference = extractMatches(userMessage, [
      /我喜欢([^，。！？]+)/,
      /我爱([^，。！？]+)/,
      /我最想([^，。！？]+)/
    ]);
    const identity = extractMatches(userMessage, [
      /我是([^，。！？]+)/,
      /我在([^，。！？]+)/,
      /我来自([^，。！？]+)/
    ]);
    const promise = extractMatches(userMessage, [
      /明天([^，。！？]+)/,
      /下次([^，。！？]+)/,
      /我会([^，。！？]+)/
    ]);

    pushUniqueLimited(next.preferences, preference, 5);
    pushUniqueLimited(next.identityNotes, identity, 5);
    pushUniqueLimited(next.promises, promise, 4);

    if (event) {
      pushUniqueLimited(next.milestones, `${event.title}：${event.theme}`, 6);
    } else if (userMessage.length >= 10) {
      pushUniqueLimited(next.milestones, `${relationshipStage}阶段里，你提到“${userMessage.slice(0, 20)}”`, 6);
    }

    return next;
  }
}
