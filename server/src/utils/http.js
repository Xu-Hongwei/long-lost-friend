import { URL } from "node:url";

export async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }

  const raw = Buffer.concat(chunks).toString("utf8");
  if (!raw) {
    return {};
  }

  return JSON.parse(raw);
}

export function getQuery(req) {
  const url = new URL(req.url, "http://localhost");
  return Object.fromEntries(url.searchParams.entries());
}

export function sendJson(res, statusCode, payload) {
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store"
  });
  res.end(JSON.stringify(payload));
}

export function sendError(res, statusCode, message, code = "REQUEST_FAILED", extra = {}) {
  sendJson(res, statusCode, {
    ok: false,
    error: {
      code,
      message,
      ...extra
    }
  });
}
