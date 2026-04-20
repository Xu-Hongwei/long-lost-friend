import path from "node:path";

const rootDir = path.resolve(process.cwd());

export const config = {
  port: Number(process.env.PORT || 3000),
  rootDir,
  publicDir: path.join(rootDir, "public"),
  stateFile: path.join(rootDir, "data", "runtime", "state.json"),
  memoryRetentionMs: 7 * 24 * 60 * 60 * 1000,
  llm: {
    baseUrl: process.env.OPENAI_BASE_URL || "https://api.openai.com/v1",
    apiKey: process.env.OPENAI_API_KEY || "",
    model: process.env.OPENAI_MODEL || "gpt-4o-mini",
    timeoutMs: 12000
  }
};
