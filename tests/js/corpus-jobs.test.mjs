import test from "node:test";
import assert from "node:assert/strict";
import {createJobMonitor, hasActiveJobs} from "../../src/main/resources/static/js/corpus-jobs.js";

const running = {jobId: "index", stage: "EMBEDDING", terminal: false};
const finished = {...running, stage: "COMPLETED", terminal: true};

function harness(overrides = {}) {
  const pending = new Map(), renders = [], failures = [], completions = [];
  let next = 0;
  const monitor = createJobMonitor({
    read: async () => finished,
    render: status => renders.push(status),
    complete: async status => completions.push(status),
    error: (error, phase, status) => failures.push({error, phase, status}),
    schedule: callback => {pending.set(++next, callback); return next;},
    cancel: id => pending.delete(id),
    ...overrides
  });
  async function tick() {
    const [id, callback] = pending.entries().next().value;
    pending.delete(id);
    await callback();
  }
  return {monitor, pending, renders, failures, completions, tick};
}

test("version history reads the terminal boolean for completed and running jobs", () => {
  assert.equal(hasActiveJobs([finished]), false);
  assert.equal(hasActiveJobs([finished, running]), true);
  assert.equal(hasActiveJobs([]), false);
});

test("completion stops polling even when the subsequent panel refresh fails", async () => {
  let refreshes = 0;
  const h = harness({complete: async () => {refreshes++; throw new Error("history refresh failed");}});
  h.monitor.watch(running, "corpus");
  await h.tick();
  assert.deepEqual(h.renders, [running, finished]);
  assert.equal(h.pending.size, 0);
  assert.equal(refreshes, 1);
  assert.equal(h.failures[0].phase, "refresh");
  assert.equal(h.failures[0].status.terminal, true);
});

test("transient status-read failures retry without rerendering the stale embedding state", async () => {
  let reads = 0;
  const h = harness({read: async () => {if (++reads === 1) throw new Error("connection lost"); return finished;}});
  h.monitor.watch(running, "corpus");
  await h.tick();
  assert.equal(h.pending.size, 1);
  assert.deepEqual(h.renders, [running]);
  assert.equal(h.failures[0].phase, "read");
  await h.tick();
  assert.deepEqual(h.renders, [running, finished]);
  assert.equal(h.completions.length, 1);
  assert.equal(h.pending.size, 0);
});

test("switching corpora ignores a previous corpus's in-flight response", async () => {
  let resolve;
  const h = harness({read: () => new Promise(done => {resolve = done;})});
  h.monitor.watch(running, "previous");
  const inFlight = h.tick();
  h.monitor.stop();
  resolve(finished);
  await inFlight;
  assert.deepEqual(h.renders, [running]);
  assert.equal(h.completions.length, 0);
  assert.equal(h.pending.size, 0);
});

test("loading completed job history does not trigger another completion refresh", () => {
  const h = harness();
  h.monitor.watch(finished, "corpus");
  assert.deepEqual(h.renders, [finished]);
  assert.equal(h.completions.length, 0);
  assert.equal(h.pending.size, 0);
});
