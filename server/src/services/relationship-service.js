const positiveKeywords = ["喜欢", "想你", "陪", "真诚", "晚安", "谢谢", "开心", "期待", "相信", "认真"];
const negativeKeywords = ["滚", "烦", "无聊", "讨厌", "闭嘴", "蠢", "恶心"];
const trustKeywords = ["其实", "心事", "担心", "害怕", "压力", "迷茫", "秘密"];
const resonanceKeywords = ["一起", "以后", "记得", "默契", "答应", "陪伴", "未来"];

const stageDefinitions = [
  { name: "初识", min: 0 },
  { name: "升温", min: 12 },
  { name: "心动", min: 28 },
  { name: "靠近", min: 50 },
  { name: "确认线路", min: 76 }
];

function countMatches(text, keywords) {
  return keywords.reduce((sum, keyword) => sum + (text.includes(keyword) ? 1 : 0), 0);
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

export class RelationshipService {
  createInitialState() {
    return {
      closeness: 0,
      trust: 0,
      resonance: 0,
      affectionScore: 0,
      relationshipStage: "初识",
      ending: null
    };
  }

  evaluateTurn(userMessage, previousState, event) {
    const text = userMessage.trim();
    const positive = countMatches(text, positiveKeywords);
    const negative = countMatches(text, negativeKeywords);
    const trust = countMatches(text, trustKeywords);
    const resonance = countMatches(text, resonanceKeywords);
    const sharesPersonalDetail = /我(觉得|想|会|喜欢|害怕|担心|希望|最近)/.test(text) ? 1 : 0;
    const questionBonus = /[？?]/.test(text) ? 1 : 0;

    const closenessDelta = clamp(1 + positive + questionBonus - negative, -3, 4);
    const trustDelta = clamp(trust + sharesPersonalDetail - negative, -2, 4);
    const resonanceDelta = clamp(resonance + (event ? event.affectionBonus : 0), -2, 6);

    const nextState = {
      closeness: Math.max(0, previousState.closeness + closenessDelta),
      trust: Math.max(0, previousState.trust + trustDelta),
      resonance: Math.max(0, previousState.resonance + resonanceDelta),
      affectionScore: 0,
      relationshipStage: previousState.relationshipStage,
      ending: previousState.ending
    };

    nextState.affectionScore = nextState.closeness + nextState.trust + nextState.resonance;
    nextState.relationshipStage = this.getRelationshipStage(nextState.affectionScore);

    if (nextState.affectionScore >= 90) {
      nextState.ending = "继续发展";
    } else if (negative >= 2 && nextState.affectionScore <= 8) {
      nextState.ending = "关系停滞";
    }

    return {
      nextState,
      affectionDelta: {
        closeness: closenessDelta,
        trust: trustDelta,
        resonance: resonanceDelta,
        total: closenessDelta + trustDelta + resonanceDelta
      }
    };
  }

  getRelationshipStage(score) {
    let current = stageDefinitions[0].name;
    for (const stage of stageDefinitions) {
      if (score >= stage.min) {
        current = stage.name;
      }
    }
    return current;
  }
}
