export function formatTime(value?: string): string {
  if (!value) {
    return "--:--";
  }
  return new Date(value).toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit"
  });
}

export function formatDateTime(value?: string): string {
  if (!value) {
    return "尚未建立";
  }
  return new Date(value).toLocaleString("zh-CN", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  });
}

export function getMoodLabel(value?: string): string {
  const labels: Record<string, string> = {
    stressed: "有点绷紧",
    warm: "正在回暖",
    curious: "愿意继续靠近",
    neutral: "平稳聊天",
    calm: "安静靠近",
    teasing: "带一点试探",
    protective: "想接住你",
    uneasy: "还有一点不安"
  };
  return labels[value || ""] || "情绪正在慢慢变化";
}

export function getIntentLabel(value?: string): string {
  const labels: Record<string, string> = {
    plan: "在聊接下来的安排",
    question: "在等一个回应",
    share: "在认真分享",
    chat: "轻松闲聊"
  };
  return labels[value || ""] || "轻松闲聊";
}

export function getCadenceLabel(value?: string): string {
  const labels: Record<string, string> = {
    soft_pause: "更轻一点接住",
    answer_first: "先回答再延展",
    lean_in: "会更主动靠近",
    light_ping: "轻轻问候",
    cinematic: "更有画面感",
    steady_flow: "顺着气氛慢慢聊"
  };
  return labels[value || ""] || "顺着气氛慢慢聊";
}

export function getReplySourceLabel(value?: string): string {
  const labels: Record<string, string> = {
    user_turn: "当前回合",
    plot_push: "剧情推进",
    silence_heartbeat: "静默问候",
    long_chat_heartbeat: "长聊心跳",
    choice_result: "剧情结果",
    choice: "关键选择"
  };
  return labels[value || ""] || "";
}

export function getEndingCandidateLabel(value?: string): string {
  const labels: Record<string, string> = {
    progress: "继续发展",
    ambiguous: "暧昧未满",
    stalled: "关系停滞"
  };
  return labels[value || ""] || (value || "继续发展");
}

export function getOnlineLabel(online?: boolean, typing?: boolean): string {
  if (typing) {
    return "输入中";
  }
  return online ? "在线" : "离线";
}
