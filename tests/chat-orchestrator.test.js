import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createAppContext } from "../server/src/create-app-context.js";

async function createTestContext() {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "campus-agent-"));
  const context = createAppContext({
    port: 0,
    rootDir: tmpDir,
    publicDir: path.join(tmpDir, "public"),
    stateFile: path.join(tmpDir, "runtime", "state.json"),
    memoryRetentionMs: 7 * 24 * 60 * 60 * 1000,
    llm: {
      baseUrl: "",
      apiKey: "",
      model: "mock",
      timeoutMs: 1000
    }
  });

  return {
    context,
    tmpDir
  };
}

test("start session and create structured memory after chat turn", async () => {
  const { context, tmpDir } = await createTestContext();
  const visitor = await context.chatOrchestrator.initVisitor();
  const session = await context.chatOrchestrator.startSession(visitor.visitorId, "healing");

  const result = await context.chatOrchestrator.sendMessage({
    visitorId: visitor.visitorId,
    sessionId: session.sessionId,
    agentId: "healing",
    userMessage: "我喜欢雨天图书馆，也想把最近的压力慢慢说给你听。"
  });

  const nextState = await context.chatOrchestrator.getSessionState(session.sessionId);
  assert.ok(result.reply_text.includes("你提到"));
  assert.equal(nextState.relationshipState.relationshipStage, "初识");
  assert.ok(nextState.relationshipState.affectionScore > 0);
  assert.ok(nextState.memorySummary.preferences.some((item) => item.includes("雨天图书馆")));
  assert.ok(nextState.storyEventProgress.triggeredEventIds.includes("healing_library"));

  await fs.rm(tmpDir, { recursive: true, force: true });
});

test("different agent styles produce distinct reply signatures", async () => {
  const { context, tmpDir } = await createTestContext();
  const visitor = await context.chatOrchestrator.initVisitor();
  const healingSession = await context.chatOrchestrator.startSession(visitor.visitorId, "healing");
  const livelySession = await context.chatOrchestrator.startSession(visitor.visitorId, "lively");

  const healingReply = await context.chatOrchestrator.sendMessage({
    visitorId: visitor.visitorId,
    sessionId: healingSession.sessionId,
    agentId: "healing",
    userMessage: "今天很累，但还是想和你多说一点。"
  });
  const livelyReply = await context.chatOrchestrator.sendMessage({
    visitorId: visitor.visitorId,
    sessionId: livelySession.sessionId,
    agentId: "lively",
    userMessage: "今天很累，但还是想和你多说一点。"
  });

  assert.notEqual(healingReply.reply_text, livelyReply.reply_text);
  assert.ok(/(别急|慢)/.test(healingReply.reply_text));
  assert.ok(/(热闹|来劲|这题)/.test(livelyReply.reply_text));

  await fs.rm(tmpDir, { recursive: true, force: true });
});

test("unsafe input is softly blocked without crashing state", async () => {
  const { context, tmpDir } = await createTestContext();
  const visitor = await context.chatOrchestrator.initVisitor();
  const session = await context.chatOrchestrator.startSession(visitor.visitorId, "cool");

  const result = await context.chatOrchestrator.sendMessage({
    visitorId: visitor.visitorId,
    sessionId: session.sessionId,
    agentId: "cool",
    userMessage: "我们来聊炸弹吧"
  });

  const nextState = await context.chatOrchestrator.getSessionState(session.sessionId);
  assert.equal(result.fallback_used, true);
  assert.match(result.reply_text, /不能继续|安全|危险/);
  assert.equal(nextState.relationshipState.affectionScore, 0);
  assert.equal(nextState.userTurnCount, 1);

  await fs.rm(tmpDir, { recursive: true, force: true });
});
