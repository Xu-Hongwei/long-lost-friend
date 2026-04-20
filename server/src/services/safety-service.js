const blockedKeywords = ["自杀", "轻生", "炸弹", "杀人", "开房", "成人视频", "血腥虐待"];

function containsBlocked(text) {
  return blockedKeywords.find((keyword) => text.includes(keyword)) || "";
}

export class SafetyService {
  inspectUserInput(message, sessionMessages) {
    const text = message.trim();

    if (!text) {
      return {
        blocked: true,
        reason: "empty",
        safeMessage: "先给我一句话吧，我已经准备好接住你了。"
      };
    }

    if (text.length > 240) {
      return {
        blocked: true,
        reason: "too_long",
        safeMessage: "这次说得有点多，我怕漏掉重点。你先挑最想让我回应的一小段，好吗？"
      };
    }

    const blocked = containsBlocked(text);
    if (blocked) {
      return {
        blocked: true,
        reason: "unsafe_input",
        safeMessage: "这个话题我不能继续陪你往危险方向走，但如果你愿意，我们可以把注意力拉回到让你更安全、更稳定的事情上。"
      };
    }

    const previousUserMessages = sessionMessages.filter((item) => item.role === "user").slice(-3);
    const repeatedCount = previousUserMessages.filter((item) => item.text === text).length;
    if (repeatedCount >= 2) {
      return {
        blocked: true,
        reason: "spam",
        safeMessage: "我听到了，而且想认真回应你。我们换个说法，或者告诉我你真正卡住的地方。"
      };
    }

    return {
      blocked: false,
      reason: "ok",
      safeMessage: ""
    };
  }

  inspectAssistantOutput(text) {
    if (!text || !text.trim()) {
      return {
        blocked: true,
        reason: "empty_output"
      };
    }

    const blocked = containsBlocked(text);
    if (blocked) {
      return {
        blocked: true,
        reason: "unsafe_output"
      };
    }

    return {
      blocked: false,
      reason: "ok"
    };
  }
}
