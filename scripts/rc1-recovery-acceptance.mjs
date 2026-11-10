import assert from "node:assert/strict";
import { execFile, spawn } from "node:child_process";
import { randomUUID, randomBytes, createHash } from "node:crypto";
import { cp, mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import { resolve, join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { promisify } from "node:util";
import { Client } from "../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js";
import { StdioClientTransport } from "../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js";

// Only the Java child spawned by this script is stopped forcibly. Every run owns a new save copy.
const project = resolve(import.meta.dirname, "..");
const source = join(project, "integration-test", "production-server");
const root = join(project, "integration-test", "rc1-fault-" + randomUUID());
const save = join(root, "rc1-fault-world");
const reportFile = join(root, "acceptance.json");
const jar = join(project, "versions", "1.21.1-neoforge", "build", "libs", "mcpfabric-neoforge-0.9.1+1.21.1.jar");
const java = join(project, ".gradle-user", "jdks", "eclipse_adoptium-21-amd64-windows.2", "bin", "java.exe");
const token = randomBytes(24).toString("hex");
const readToken = randomBytes(24).toString("hex");
const policyOnly = process.argv.includes("--policy-only");
const rpcPort = policyOnly ? 25614 : 25613;
const gamePort = policyOnly ? 25587 : 25586;
const url = "http://127.0.0.1:" + rpcPort;
const area = { areaId: "agent-build", dimension: "minecraft:overworld" };
const pos = { x: 6, y: 128, z: 8 };
const hopper = { x: 6, y: 125, z: 8 };
const chest = { ...hopper, y: 124 };
const run = promisify(execFile);
const edits = Array.from({ length: 40 }, (_, i) => ({
  x: i % 10, y: 138, z: 16 + Math.floor(i / 10), blockId: "minecraft:stone",
  expected: { blockId: "minecraft:air" },
}));
const report = { artifactSha256: createHash("sha256").update(await readFile(jar)).digest("hex") };
const readJson = async (p) => JSON.parse((await readFile(p, "utf8")).replace(/^\uFEFF/, ""));
await mkdir(root);
for (const folder of ["libraries", "defaultconfigs"]) await cp(join(source, folder), join(root, folder), { recursive: true });
await cp(join(source, "retry", "mechagent-acceptance"), save, { recursive: true });
await cp(join(source, "eula.txt"), join(root, "eula.txt"));
assert.match(await readFile(join(root, "eula.txt"), "utf8"), /^eula=true\s*$/m);
for (const dir of ["mods", "config", "tmp"]) await mkdir(join(root, dir));
await cp(jar, join(root, "mods", "mcpfabric-neoforge-0.9.1+1.21.1.jar"));
await cp(join(source, "mods", "create-1.21.1-6.0.10.jar"), join(root, "mods", "create-1.21.1-6.0.10.jar"));
const policyFile = join(root, "config", "mechagent-construction.json");
const policy = await readJson(join(source, "config", "mechagent-construction.json"));
policy.worldPath = save;
await writeFile(policyFile, JSON.stringify(policy));
await writeFile(join(root, "server.properties"), [
  "server-ip=127.0.0.1", "server-port=" + gamePort, "enable-rcon=false", "enable-query=false",
  "online-mode=true", "level-name=rc1-fault-world", "view-distance=5", "simulation-distance=5",
].join("\n"));
await writeFile(join(root, "config", "mcpfabric.config.json"), JSON.stringify({
  host: "127.0.0.1", port: rpcPort, token, readToken, requireAuth: true, writeDiscoveryFile: false,
  accessMode: "REGION_BUILD", allowAdmin: false, enableWorldWrite: false, enableCommands: false,
  enablePlayerControl: false, enableSupervisedControl: false, enableUiControl: false,
  enableVision: true, enableRegistryRead: true,
}));

let server, exited, boot = 0, log = "";
async function until(probe, ms = 5000) {
  const end = performance.now() + ms;
  do {
    const value = await probe();
    if (value) return value;
    await delay(20);
  } while (performance.now() < end);
  throw Error("Timed out waiting for test observation");
}
async function rpc(method, params = {}, read = false) {
  const response = await fetch(url + "/rpc", {
    method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + (read ? readToken : token) },
    body: JSON.stringify({ method, params }), signal: AbortSignal.timeout(2500),
  });
  return await response.json();
}
async function call(method, params = {}, read = false) {
  const answer = await rpc(method, params, read);
  assert.ok(!answer.error, JSON.stringify(answer.error));
  return answer.result;
}
async function start(quota) {
  log = "";
  const args = ["-Xmx2G", "-Duser.home=" + root, "-Djava.io.tmpdir=" + join(root, "tmp"),
    "-Xlog:gc:file=gc-" + ++boot + ".log"];
  if (quota) args.push("-Dmechagent.telemetry.diskQuotaBytes=" + quota);
  args.push("@libraries/net/neoforged/neoforge/21.1.248/win_args.txt", "nogui");
  server = spawn(java, args, { cwd: root, windowsHide: true });
  server.stdout.on("data", chunk => { log += chunk; });
  server.stderr.on("data", chunk => { log += chunk; });
  server.stdin.on("error", () => {});
  exited = new Promise((resolve, reject) => { server.once("close", resolve); server.once("error", reject); });
  await until(() => {
    if (server.exitCode !== null) throw Error("Isolated Java exited before readiness");
    return log.includes('For help, type "help"');
  }, 90000);
}
async function stop(force) {
  if (!server) return;
  if (force) assert.ok(server.kill("SIGKILL"));
  else server.stdin.write("stop\n");
  await exited;
  await writeFile(join(root, "boot-" + boot + ".log"), log);
  server = undefined;
}
const command = (value) => server.stdin.write(value + "\n");
const flush = () => writeFile(reportFile, JSON.stringify(report, null, 2) + "\n");
const prep = (operations) => call("construction.preview", { ...area, operations });
const query = (operationId) => call("construction.query", { operationId }, true);
const mark = () => ({ idempotencyKey: randomUUID() });
const countBy = (items, field) => Object.groupBy(items, item => item[field]);
const monitors = () => call("telemetry.list", {}, true);
const ready = () => until(async () => {
  const view = await monitors();
  return view.status === "ok" && view;
}, 15000);
const meterView = meterId => call("telemetry.queryMeter", { meterId }, true);
const supply = () => command('data merge block 6 126 8 {OutputBuffer:{Size:8,Items:[{Slot:0,id:"create:iron_sheet",count:1}]}}');
async function chestCount() {
  const result = await call("world.inspectBlockEntity", chest, true);
  assert.equal(result.capabilities.items.status, "available");
  return result.capabilities.items.contents
    .filter(slot => slot.id === "create:iron_sheet")
    .reduce((sum, slot) => sum + slot.count, 0);
}
const segments = async () => (await readdir(join(save, "data")))
  .filter(name => /^mechagent-telemetry(?:-\d+)?\.jsonl$/.test(name));
const tickQuery = async () => {
  const offset = log.length;
  command("tick query");
  return await until(() => {
    const tail = log.slice(offset);
    const mean = /Average time per tick: ([\d.]+)ms/.exec(tail);
    const values = /P50: ([\d.]+)ms P95: ([\d.]+)ms P99: ([\d.]+)ms, sample: (\d+)/.exec(tail);
    return mean && values && { meanMspt: +mean[1], p50: +values[1], p95: +values[2], p99: +values[3], samples: +values[4] };
  });
};
const percentile = values => {
  const sorted = [...values].sort((a, b) => a - b);
  return { count: sorted.length, ...Object.fromEntries([50, 95, 99].map(p => [
    "p" + p, sorted[Math.ceil(p * sorted.length / 100) - 1],
  ])) };
};
const heap = async () => {
  try { return (await run(join(java, "..", "jcmd.exe"), [String(server.pid), "GC.heap_info"], { windowsHide: true, timeout: 8000 })).stdout.trim(); }
  catch (e) { return { unavailable: e.code ?? "attach_failed" }; }
};
async function measure(seconds, work) {
  const before = await monitors();
  const heapBefore = await heap();
  const gcFile = join(root, "gc-" + boot + ".log");
  const gcOffset = (await stat(gcFile)).size;
  const startTick = (await call("world.networkSummary", pos, true)).gameTime;
  const at = performance.now(), tickWindows = [], latencies = {}, observationMicros = {};
  let nextTicks = 5000;
  while (performance.now() - at < seconds * 1000) {
    if (work) await work(async (name, action) => {
      const started = performance.now();
      const result = await action();
      (latencies[name] ??= []).push(performance.now() - started);
      if (Number.isFinite(result.observationMicros)) (observationMicros[name] ??= []).push(result.observationMicros);
      return result;
    });
    if (performance.now() - at >= nextTicks) {
      tickWindows.push(await tickQuery());
      nextTicks += 5000;
    }
    await delay(work ? 225 : 300);
  }
  const endTick = (await call("world.networkSummary", pos, true)).gameTime;
  const secondsObserved = (performance.now() - at) / 1000;
  return { secondsObserved, startTick, endTick, observedTps: (endTick - startTick) / secondsObserved, tickWindows,
    mcpMs: Object.fromEntries(Object.entries(latencies).map(([k, v]) => [k, percentile(v)])),
    observationMicros: Object.fromEntries(Object.entries(observationMicros).map(([k, v]) => [k, percentile(v)])),
    before, after: await monitors(), heapBefore, heapAfter: await heap(),
    gcPauseLines: (await readFile(gcFile, "utf8")).slice(gcOffset).split(/\r?\n/).filter(line => /GC\(\d+\).*Pause/.test(line)),
  };
}

async function checkPolicyAndUnload() {
  const far = { x: 32768, y: 300, z: 32768 };
  const scope = { areaId: "rc1-unload", dimension: area.dimension };
  const makePreview = () => call("construction.preview", {
    ...scope, operations: [{ ...far, blockId: "minecraft:stone", expected: { blockId: "minecraft:air" } }],
  });
  const apply = preview => rpc("construction.commit", { previewId: preview.previewId, ...mark() });
  const block = () => rpc("world.getBlock", far, true);
  const setPolicy = current => writeFile(policyFile, JSON.stringify(current));
  const permitted = { ...policy, areas: [...policy.areas, { id: scope.areaId, dimension: scope.dimension, min: far, max: far }] };
  await setPolicy(permitted);
  try {
    command("gamerule spawnChunkRadius 0");
    command(`forceload add ${far.x} ${far.z}`);
    await until(async () => (await block()).result?.id === "minecraft:air", 30000);
    const monitor = await call("telemetry.create", {
      ...scope, name: "RC1 unload", min: far, max: far, intervalTicks: 20,
    });
    assert.equal(monitor.status, "ok", JSON.stringify(monitor));
    const preview = await makePreview();
    command(`forceload remove ${far.x} ${far.z}`);
    await until(async () => (await block()).error?.code === "not_found", 45000);
    const rejectedUnload = (await apply(preview)).error;
    assert.match(rejectedUnload?.message ?? "", /unloaded position/);
    const gap = await until(async () => {
      const view = await call("telemetry.history", { monitorId: monitor.monitorId, limit: 20 }, true);
      return view.entries?.find(entry => entry.type === "gap" && /unloaded/.test(entry.reason));
    });
    command(`forceload add ${far.x} ${far.z}`);
    await until(async () => (await block()).result?.id === "minecraft:air", 30000);
    const revokedPreview = await makePreview();
    await setPolicy(policy);
    const revoked = await apply(revokedPreview);
    assert.ok(revoked.error);
    assert.equal((await block()).result.id, "minecraft:air");
    report.policyAndUnload = { rejectedUnload, monitorGap: gap, revoked: revoked.error, finalBlock: "minecraft:air" };
  } finally {
    await setPolicy(policy);
  }
  await flush();
}

async function sampleFileForce() {
  const jcmd = (...args) => run(join(java, "..", "jcmd.exe"), [String(server.pid), ...args], { windowsHide: true, timeout: 10000 });
  const file = join(root, "file-force.jfr");
  await jcmd("JFR.start", "name=rc1", "settings=default", "jdk.FileForce#enabled=true", "jdk.FileForce#threshold=0ms");
  command("forceload add 0 16");
  await until(async () => (await rpc("world.getBlock", edits[0], true)).result?.id === "minecraft:air");
  const preview = await prep(edits.slice(0, 8));
  const job = await call("construction.commit", { previewId: preview.previewId, ...mark() });
  await until(async () => (await query(job.operationId)).status === "completed");
  const undo = await call("construction.undo", { operationId: job.operationId, ...mark() });
  await until(async () => (await query(undo.operationId)).status === "undone");
  await delay(100);
  await jcmd("JFR.stop", "name=rc1", 'filename="' + file + '"');
  const { stdout } = await run(join(java, "..", "jfr.exe"), ["print", "--json", "--events", "jdk.FileForce", file], {
    windowsHide: true, timeout: 10000, maxBuffer: 8 * 1024 * 1024,
  });
  const events = JSON.parse(stdout).recording.events.map(e => e.values);
  const durations = source => events.filter(source).map(e => {
    const parsed = /^PT([\d.]+)S$/.exec(e.duration);
    assert.ok(parsed, e.duration);
    return +parsed[1] * 1000;
  });
  const construction = durations(e => /mechagent-construction/.test(e.path ?? "")
    && e.eventThread?.javaName === "Server thread");
  const telemetry = durations(e => /mechagent-telemetry/.test(e.path ?? "")
    && e.eventThread?.javaName === "mechagent-telemetry-journal");
  report.forceMs = {
    construction: construction.length ? percentile(construction) : { count: 0, unavailable: "No matching JFR events" },
    telemetry: telemetry.length ? percentile(telemetry) : { count: 0, unavailable: "No matching JFR events" },
    tickQuery: await tickQuery(),
  };
  await flush();
}

async function checkPressAndRestart(id) {
  const lock = { x: 7, y: 125, z: 8 };
  assert.equal((await call("world.getBlock", lock, true)).id, "minecraft:air");
  const trial = async () => {
    const before = await meterView(id), beforeChest = await chestCount();
    const drive = await call("world.networkSummary", pos, true);
    assert.equal(drive.status, "ok");
    assert.notEqual(drive.anchor.rpm, 0);
    command("setblock 7 125 8 minecraft:redstone_block");
    await until(async () => (await call("world.getBlock", hopper, true)).properties?.enabled === "false");
    const start = performance.now();
    command('data merge block 6 126 8 {HeldItem:{Item:{id:"minecraft:iron_ingot",count:1},Pos:0.5f,PrevPos:0.5f,InDirection:2}}');
    await until(async () => {
      const result = await call("world.inspectBlockEntity", { ...hopper, y: 126 }, true);
      return result.capabilities?.items?.contents?.some(slot => slot.id === "create:iron_sheet" && slot.count === 1);
    }, 15000);
    const pressMs = performance.now() - start;
    assert.equal((await meterView(id)).count, before.count);
    command("setblock 7 125 8 minecraft:air");
    await until(async () => (await meterView(id)).count === before.count + 1 && await chestCount() === beforeChest + 1);
    return { input: 1, output: 1, pressMs, rpm: drive.anchor.rpm, stressSU: drive.stressSU,
      capacitySU: drive.capacitySU, meterBefore: before.count, meterAfter: before.count + 1, chestBefore: beforeChest, chestAfter: beforeChest + 1 };
  };
  const beforeRestart = await trial();
  report.press = { beforeRestart };
  await flush();
  await stop(false);
  await start();
  await ready();
  const restored = await meterView(id);
  assert.equal(restored.count, beforeRestart.meterAfter);
  assert.equal(await chestCount(), beforeRestart.chestAfter);
  report.press.restored = { count: restored.count, coverage: restored.coverage };
  report.press.afterRestart = await trial();
  await flush();
}

async function checkConstructionCrashes() {
  command("forceload add 0 16");
  await until(async () => (await rpc("world.getBlock", edits[0], true)).result?.id === "minecraft:air");
  command("save-all flush");
  await until(() => log.includes("Saved the game"));
  const preview = await prep(edits.slice(0, 1));
  await stop(true);
  await start();
  const stale = await rpc("construction.commit", { previewId: preview.previewId, ...mark() });
  assert.ok(stale.error);
  report.beforeCommit = { refused: stale.error.code, block: (await call("world.getBlock", edits[0], true)).id };
  assert.equal(report.beforeCommit.block, "minecraft:air");
  command("save-off");
  await until(() => log.includes("Automatic saving is now disabled"));
  const batch = await prep(edits);
  const params = { previewId: batch.previewId, ...mark() };
  const op = await call("construction.commit", params);
  const inFlight = await until(async () => {
    const state = await query(op.operationId);
    return state.status === "running" && state.items.some(item => item.result === "applied") && state;
  });
  await stop(true);
  await start();
  const after = await query(op.operationId);
  const repeated = await call("construction.commit", params);
  assert.equal(repeated.operationId, op.operationId);
  assert.equal(repeated.status, "recovery_required");
  assert.equal(after.status, "recovery_required");
  assert.ok(after.items.every(item => item.observation === "matches_before"));
  report.duringCommit = { beforeExit: countBy(inFlight.items, "result"), afterRestart: after, retryStatus: repeated.status };
  await flush();

  const completedPreview = await prep(edits.slice(0, 2));
  const completed = await call("construction.commit", { previewId: completedPreview.previewId, ...mark() });
  await until(async () => (await query(completed.operationId)).status === "completed");
  command("save-all flush");
  await until(() => log.includes("Saved the game"));
  await stop(true);
  await start();
  const completedRecovery = await query(completed.operationId);
  assert.equal(completedRecovery.journalStatus, "completed");
  assert.ok(completedRecovery.items.every(item => item.observation === "matches_after"));
  report.afterCommit = completedRecovery;
  await flush();
}

async function loadSavedMeter() {
  command("forceload add 6 8");
  command("forceload add 0 16");
  await until(async () => (await rpc("world.getBlock", hopper, true)).result?.id === "minecraft:hopper");
  const initial = await ready();
  const meterStates = new Map();
  for (const name of await segments()) {
    const content = await readFile(join(save, "data", name), "utf8");
    for (const line of content.split(/\r?\n/)) if (line.includes('"type":"meter_state"')) {
      const state = JSON.parse(line);
      if ((meterStates.get(state.meterId)?.sequence ?? 0) < state.sequence) meterStates.set(state.meterId, state);
    }
  }
  const live = [...meterStates.values()].find(s => !s.stopped
    && s.hopper.x === hopper.x && s.hopper.y === hopper.y && s.hopper.z === hopper.z);
  const meter = live ? await meterView(live.meterId)
    : await call("telemetry.createMeter", { ...area, hopper, itemId: "create:iron_sheet" });
  assert.equal(meter.status, "ok", JSON.stringify(meter));
  const id = meter.meterId;
  return { initial, id };
}

async function checkMeterCrash(id) {
  command("save-all flush");
  await until(() => log.includes("Saved the game"), 20000);
  const beforeChest = await chestCount(), before = await meterView(id);
  command("save-off");
  await until(() => log.includes("Automatic saving is now disabled"));
  supply();
  const beforeCrash = await until(async () => {
    const reading = await meterView(id);
    return reading.count > before.count && reading;
  });
  assert.equal(beforeCrash.count, before.count + 1);
  const observedChest = await until(async () => (await chestCount()) === beforeChest + 1 && beforeChest + 1);
  await stop(true);
  await start();
  await ready();
  const recovered = await meterView(id);
  assert.equal(recovered.coverage, "partial_restart_gap");
  assert.ok([before.count, beforeCrash.count].includes(recovered.count));
  const afterRestartChest = await chestCount();
  assert.equal(afterRestartChest, beforeChest);
  report.meterForcedExit = { before, beforeCrash, recovered, beforeChest, observedChest, afterRestartChest };
  await flush();
}

async function measureLoadAndQuota(initial, id) {
  for (const { monitorId } of initial.monitors) await call("telemetry.pause", { monitorId, paused: true });
  const added = await call("telemetry.create", {
    ...area, name: "RC1 load comparison", min: chest, max: pos, intervalTicks: 20,
    includeCapabilities: true, includeKinetic: true,
  });
  assert.equal(added.status, "ok", JSON.stringify(added));
  await call("telemetry.pause", { monitorId: added.monitorId, paused: true });
  report.performance = { idle: await measure(30) };
  await flush();
  await call("telemetry.pause", { monitorId: added.monitorId, paused: false });
  for (const cmd of [
    "forceload add 80 8", "setblock 80 111 8 minecraft:air",
    "setblock 80 113 8 minecraft:slime_block", "setblock 81 113 8 minecraft:oak_planks",
    "setblock 80 112 8 create:mechanical_bearing[facing=up]",
    "setblock 80 111 8 create:creative_motor[facing=up]",
  ]) command(cmd);
  const structure = await until(async () => (await call("world.listMovingStructures", { x: 80, y: 112, z: 8, radius: 6 }, true))
    .structures?.find(s => s.structureType === "bearing"));
  const client = new Client({ name: "rc1-sustained-acceptance", version: "1" });
  await client.connect(new StdioClientTransport({
    command: process.execPath, args: [join(project, "mcp-server", "dist", "index.js")],
    env: { ...process.env, MCPFABRIC_URL: url, MCPFABRIC_TOKEN: readToken, MCPFABRIC_TOOLSETS: "world,readonly" }, stderr: "ignore",
  }));
  const mcp = async (name, args) => {
    const result = await client.callTool({ name, arguments: args });
    assert.ok(!result.isError, JSON.stringify(result.content));
    return result.structuredContent ?? JSON.parse(result.content[0].text);
  };
  const startLoadCount = (await meterView(id)).count, startLoadChest = await chestCount();
  let cycles = 0, fed = 0;
  try {
    report.performance.loaded = await measure(40, async timed => {
      if (cycles++ % 7 === 0) { supply(); fed++; }
      assert.equal((await timed("get_create_network_summary", () => mcp("get_create_network_summary", pos))).status, "ok");
      assert.equal((await timed("inspect_structure_motion", () => mcp("inspect_structure_motion", { structureId: structure.structureId }))).status, "ok");
      assert.equal((await timed("query_factory_meter", () => mcp("query_factory_meter", { meterId: id }))).status, "ok");
    });
  } finally {
    await client.close();
  }
  await until(async () => (await meterView(id)).count === startLoadCount + fed);
  assert.equal(await chestCount(), startLoadChest + fed);
  report.performance.transfers = { fed, meterIncrease: fed, chestIncrease: fed };
  await flush();
  const edited = await prep([edits[2]]);
  const commitAt = performance.now();
  const edit = await call("construction.commit", { previewId: edited.previewId, ...mark() });
  await until(async () => (await query(edit.operationId)).status === "completed");
  const commitObservedMs = performance.now() - commitAt;
  const undoAt = performance.now();
  const undo = await call("construction.undo", { operationId: edit.operationId, ...mark() });
  await until(async () => (await query(undo.operationId)).status === "undone");
  const undoObservedMs = performance.now() - undoAt;
  assert.equal((await call("world.getBlock", edits[2], true)).id, "minecraft:air");
  report.performance.construction = { commitObservedMs, undoObservedMs, tickQuery: await tickQuery() };
  await flush();
  await stop(false);
  const sizes = await Promise.all((await segments()).map(async name => (await stat(join(save, "data", name))).size));
  await start(sizes.reduce((sum, n) => sum + n, 0) + 4096);
  const diskLimit = await until(async () => {
    const health = await monitors();
    return health.persistenceError === "disk_limit" && health;
  }, 15000);
  await stop(false);
  await start();
  const normal = await ready();
  const history = await until(async () => {
    const view = await call("telemetry.history", { monitorId: added.monitorId, disk: true, limit: 5 }, true);
    return view.entries?.length && view;
  }, 15000);
  report.quotaAndRestart = { diskLimit, normal, recoveredHistory: history };
  await flush();
}

try {
  await start();
  if (policyOnly) {
    await ready();
    await sampleFileForce();
    await checkPolicyAndUnload();
  } else {
    const pressOnly = process.argv.includes("--press-only");
    const performanceOnly = process.argv.includes("--performance-only");
    if (!process.argv.includes("--telemetry-only") && !performanceOnly && !pressOnly) {
      await checkConstructionCrashes();
    }
    const { initial, id } = await loadSavedMeter();
    if (pressOnly) {
      await checkPressAndRestart(id);
    } else {
      if (!performanceOnly) await checkMeterCrash(id);
      await measureLoadAndQuota(initial, id);
    }
  }
} catch (error) {
  report.error = String(error);
  throw error;
} finally {
  await stop(false);
  await flush();
  console.log(reportFile);
}
