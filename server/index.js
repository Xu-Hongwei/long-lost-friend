import http from "node:http";
import fs from "node:fs/promises";
import path from "node:path";
import { createAppContext } from "./src/create-app-context.js";
import { config } from "./src/config.js";
import { getQuery, readJson, sendError, sendJson } from "./src/utils/http.js";

const context = createAppContext(config);

function getContentType(filePath) {
  const extension = path.extname(filePath);
  switch (extension) {
    case ".html":
      return "text/html; charset=utf-8";
    case ".js":
      return "application/javascript; charset=utf-8";
    case ".css":
      return "text/css; charset=utf-8";
    case ".json":
      return "application/json; charset=utf-8";
    default:
      return "text/plain; charset=utf-8";
  }
}

async function serveStatic(req, res) {
  const requestPath = req.url === "/" ? "/index.html" : req.url.split("?")[0];
  const safePath = path.normalize(requestPath).replace(/^(\.\.[/\\])+/, "");
  const filePath = path.join(config.publicDir, safePath);

  try {
    const content = await fs.readFile(filePath);
    res.writeHead(200, {
      "Content-Type": getContentType(filePath)
    });
    res.end(content);
  } catch {
    const indexPath = path.join(config.publicDir, "index.html");
    const content = await fs.readFile(indexPath);
    res.writeHead(200, {
      "Content-Type": "text/html; charset=utf-8"
    });
    res.end(content);
  }
}

async function handleApi(req, res) {
  try {
    if (req.method === "GET" && req.url === "/api/agents") {
      return sendJson(res, 200, {
        ok: true,
        data: await context.chatOrchestrator.listAgents()
      });
    }

    if (req.method === "POST" && req.url === "/api/visitor/init") {
      const body = await readJson(req);
      return sendJson(res, 200, {
        ok: true,
        data: await context.chatOrchestrator.initVisitor(body.visitor_id)
      });
    }

    if (req.method === "POST" && req.url === "/api/session/start") {
      const body = await readJson(req);
      return sendJson(res, 200, {
        ok: true,
        data: await context.chatOrchestrator.startSession(body.visitor_id, body.agent_id)
      });
    }

    if (req.method === "POST" && req.url === "/api/chat/send") {
      const body = await readJson(req);
      return sendJson(res, 200, {
        ok: true,
        data: await context.chatOrchestrator.sendMessage({
          visitorId: body.visitor_id,
          sessionId: body.session_id,
          agentId: body.agent_id,
          userMessage: body.user_message
        })
      });
    }

    if (req.method === "GET" && req.url.startsWith("/api/session/state")) {
      const query = getQuery(req);
      return sendJson(res, 200, {
        ok: true,
        data: await context.chatOrchestrator.getSessionState(query.session_id)
      });
    }

    if (req.method === "GET" && req.url.startsWith("/api/session/history")) {
      const query = getQuery(req);
      return sendJson(res, 200, {
        ok: true,
        data: await context.chatOrchestrator.getSessionHistory(query.session_id)
      });
    }

    if (req.method === "POST" && req.url === "/api/feedback") {
      const body = await readJson(req);
      return sendJson(res, 200, {
        ok: true,
        data: await context.chatOrchestrator.submitFeedback({
          visitorId: body.visitor_id,
          sessionId: body.session_id,
          agentId: body.agent_id,
          rating: body.rating,
          likedPoint: body.liked_point || "",
          improvementPoint: body.improvement_point || "",
          continueIntent: body.continue_intent || false
        })
      });
    }

    if (req.method === "GET" && req.url === "/api/analytics/overview") {
      return sendJson(res, 200, {
        ok: true,
        data: await context.chatOrchestrator.getAnalyticsOverview()
      });
    }

    if (req.method === "GET" && req.url === "/api/health") {
      return sendJson(res, 200, {
        ok: true,
        data: {
          status: "up"
        }
      });
    }

    return sendError(res, 404, "Not found", "NOT_FOUND");
  } catch (error) {
    const message = String(error.message || error);
    const codeMap = {
      VISITOR_NOT_FOUND: 404,
      AGENT_NOT_FOUND: 404,
      SESSION_NOT_FOUND: 404,
      SESSION_EXPIRED: 410,
      VISITOR_SESSION_MISMATCH: 400
    };
    const statusCode = codeMap[message] || 500;
    return sendError(res, statusCode, message, message);
  }
}

const server = http.createServer(async (req, res) => {
  if (req.url.startsWith("/api/")) {
    return handleApi(req, res);
  }

  return serveStatic(req, res);
});

server.listen(config.port, () => {
  console.log(`Campus agent MVP is running at http://localhost:${config.port}`);
});
