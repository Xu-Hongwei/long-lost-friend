import OpenAI from "openai";

const input = await readStdin();
const payload = JSON.parse(input || "{}");

const apiKey =
  process.env.PLOT_LLM_API_KEY ||
  process.env.ARK_API_KEY ||
  process.env.DASHSCOPE_API_KEY ||
  process.env.OPENAI_API_KEY;
const baseURL =
  process.env.PLOT_LLM_BASE_URL ||
  process.env.ARK_BASE_URL ||
  process.env.DASHSCOPE_BASE_URL ||
  process.env.DASHSCOPE_BASE ||
  process.env.OPENAI_API_BASE ||
  process.env.OPENAI_BASE_URL ||
  process.env.OPENAI_BASE ||
  "https://ark.cn-beijing.volces.com/api/v3";
const model =
  process.env.PLOT_LLM_MODEL ||
  process.env.ARK_MODEL ||
  process.env.DASHSCOPE_MODEL ||
  process.env.OPENAI_MODEL ||
  "ep-20260418203515-nw4jb";

if (!apiKey) {
  throw new Error("Plot director API key is missing.");
}

const openai = new OpenAI({ apiKey, baseURL });

const completion = await openai.chat.completions.create({
  model,
  temperature: payload.temperature ?? 0.2,
  messages: payload.messages ?? [],
});

console.log(completion.choices?.[0]?.message?.content ?? "");

async function readStdin() {
  let data = "";
  for await (const chunk of process.stdin) {
    data += chunk;
  }
  return data;
}
