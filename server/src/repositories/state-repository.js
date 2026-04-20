import fs from "node:fs/promises";
import path from "node:path";

function createEmptyState() {
  return {
    visitors: [],
    sessions: [],
    messages: [],
    feedback: [],
    analyticsEvents: []
  };
}

export class StateRepository {
  constructor(stateFile) {
    this.stateFile = stateFile;
    this.lock = Promise.resolve();
  }

  async ensureStateFile() {
    const dir = path.dirname(this.stateFile);
    await fs.mkdir(dir, { recursive: true });
    try {
      await fs.access(this.stateFile);
    } catch {
      await fs.writeFile(this.stateFile, JSON.stringify(createEmptyState(), null, 2), "utf8");
    }
  }

  async readState() {
    await this.ensureStateFile();
    const raw = await fs.readFile(this.stateFile, "utf8");
    const parsed = raw ? JSON.parse(raw) : createEmptyState();
    return {
      ...createEmptyState(),
      ...parsed
    };
  }

  async writeState(state) {
    await this.ensureStateFile();
    await fs.writeFile(this.stateFile, JSON.stringify(state, null, 2), "utf8");
  }

  async getState() {
    return this.readState();
  }

  async transact(mutator) {
    let externalResolve;
    let externalReject;
    const caller = new Promise((resolve, reject) => {
      externalResolve = resolve;
      externalReject = reject;
    });

    this.lock = this.lock
      .catch(() => undefined)
      .then(async () => {
        const state = await this.readState();
        const result = await mutator(state);
        await this.writeState(state);
        externalResolve(result);
      })
      .catch((error) => {
        externalReject(error);
      });

    return caller;
  }
}
