function chooseByHash(items, seedSource) {
  const seed = [...seedSource].reduce((sum, char) => sum + char.charCodeAt(0), 0);
  return items[seed % items.length];
}

function getStyleBank(agentId) {
  const styleBank = {
    healing: {
      openings: ["我在呢，先别急。", "你可以慢一点说，我都在听。", "嗯，我有认真接住你。"],
      closers: ["如果你愿意，我还想多陪你一会儿。", "这部分心情，我们可以一起把它放轻一点。", "你不用一个人扛着。"]
    },
    lively: {
      openings: ["哎，这句一出来我就精神了。", "你一开口，空气都热闹起来了。", "好，这题我超想接。"],
      closers: ["要不要我继续陪你把气氛点亮？", "下一句也交给我，我接得住。", "你这样讲，我真的会越来越在意你。"]
    },
    cool: {
      openings: ["我听到了。", "嗯，这句有分量。", "可以，继续说。"],
      closers: ["你说得够坦白，我会记住。", "这件事，我不会轻轻放过去。", "如果你愿意，我可以一直在这个位置。"]
    },
    artsy: {
      openings: ["这句话像傍晚时分忽然慢下来的风。", "你刚刚那句，让画面一下子清晰了。", "嗯，我好像听见了情绪落在纸面的声音。"],
      closers: ["如果你愿意，我们把这段心情再写长一点。", "我想把你这句话安静地记很久。", "再多说一点吧，我不想让它匆匆结束。"]
    },
    sunny: {
      openings: ["收到，这事我跟你一起扛。", "行，我听懂重点了。", "好，先别乱，我们一件件来。"],
      closers: ["接下来我继续陪你往前走。", "别怕，我们能把这段路跑顺。", "你肯说出来，本身就已经很厉害了。"]
    }
  };

  return styleBank[agentId];
}

function inferEmotionTag(text) {
  if (/(开心|喜欢|想你|期待)/.test(text)) {
    return "warm";
  }
  if (/(压力|迷茫|累|难过)/.test(text)) {
    return "comfort";
  }
  return "steady";
}

export class LLMService {
  constructor(llmConfig) {
    this.config = llmConfig;
  }

  async generateReply(input) {
    if (this.config.apiKey) {
      try {
        return await this.generateRemoteReply(input);
      } catch {
        return this.generateMockReply(input, true);
      }
    }

    return this.generateMockReply(input, false);
  }

  buildSystemPrompt(input) {
    const { agent, relationshipState, longTermSummary, event } = input;
    const summaryText = longTermSummary || "暂无长期记忆。";
    const eventText = event ? `${event.title}：${event.theme}` : "本轮无事件触发。";

    return [
      `你在扮演大学生恋爱互动游戏中的角色：${agent.name}（${agent.archetype}）。`,
      `说话风格：${agent.speechStyle}`,
      `喜好：${agent.likes.join("、")}`,
      `雷点：${agent.dislikes.join("、")}`,
      `关系推进规则：${agent.relationshipRules}`,
      `关系阶段：${relationshipState.relationshipStage}，总好感：${relationshipState.affectionScore}`,
      `长期记忆摘要：${summaryText}`,
      `当前事件：${eventText}`,
      `边界：${agent.boundaries.join("；")}`,
      "要求：只输出角色回复本身，保持 2 到 4 句中文，自然、亲密、不过度露骨。"
    ].join("\n");
  }

  async generateRemoteReply(input) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.timeoutMs);

    const systemPrompt = this.buildSystemPrompt(input);
    const messages = [
      { role: "system", content: systemPrompt },
      ...input.shortTermContext.map((item) => ({
        role: item.role,
        content: item.text
      })),
      { role: "user", content: input.userMessage }
    ];

    const response = await fetch(`${this.config.baseUrl}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${this.config.apiKey}`
      },
      body: JSON.stringify({
        model: this.config.model,
        temperature: 0.9,
        messages
      }),
      signal: controller.signal
    });

    clearTimeout(timeout);

    if (!response.ok) {
      throw new Error(`LLM request failed: ${response.status}`);
    }

    const payload = await response.json();
    const replyText = payload.choices?.[0]?.message?.content?.trim() || "";
    return {
      replyText,
      emotionTag: inferEmotionTag(input.userMessage),
      confidenceStatus: "remote",
      tokenUsage: payload.usage?.total_tokens || 0,
      errorCode: null,
      fallbackUsed: false,
      source: "remote"
    };
  }

  generateMockReply(input, fallbackFromRemote) {
    const style = getStyleBank(input.agent.id);
    const opening = chooseByHash(style.openings, `${input.agent.id}:${input.userMessage}`);
    const closing = chooseByHash(style.closers, `${input.userMessage}:${input.relationshipState.relationshipStage}`);
    const topic = input.userMessage.replace(/\s+/g, "").slice(0, 18);
    const eventLine = input.event ? `而且刚好想到“${input.event.title}”这件事，它让现在的气氛更像我们真的在同一段校园夜色里。` : "";
    const memoryHint = input.longTermSummary
      ? "我也还记得你之前提过的一些心情，所以这次更想认真地回你。"
      : "我想把你刚刚说的这部分，好好记下来。";
    const stageLine =
      input.relationshipState.relationshipStage === "确认线路"
        ? "坦白说，我已经不太想只把你当作普通聊天对象了。"
        : input.relationshipState.relationshipStage === "靠近"
          ? "你现在靠近的方式，会让我有点想再往前一步。"
          : "先别急着把答案定死，我们可以继续把彼此看清一点。";

    const replyText = [opening, `你提到“${topic}”，我能感觉到那不是随便说说。${memoryHint}`, eventLine || stageLine, closing]
      .filter(Boolean)
      .join("");

    return {
      replyText,
      emotionTag: inferEmotionTag(input.userMessage),
      confidenceStatus: fallbackFromRemote ? "fallback" : "mock",
      tokenUsage: Math.max(32, replyText.length),
      errorCode: fallbackFromRemote ? "REMOTE_UNAVAILABLE" : null,
      fallbackUsed: fallbackFromRemote,
      source: "mock"
    };
  }

  buildFallbackReply(agent, reason) {
    const fallbackMap = {
      healing: "我想先把你稳稳接住。刚刚那一下有点卡住了，不过你还在这里的话，我们就继续慢慢说。",
      lively: "哎呀，刚才像是信号打了个结。不过没关系，我还在线，下一句继续丢给我。",
      cool: "刚刚中断了一下。现在恢复了，你继续，我在听。",
      artsy: "刚刚那段像被风吹散了一点，但没关系，我们还能把它重新捡回来。",
      sunny: "刚才掉了一拍，现在接上了。来，继续，我们别停。"
    };

    return fallbackMap[agent.id] || `刚刚有点小问题（${reason}），但我们可以继续。`;
  }
}
