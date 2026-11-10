import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { Client } from "../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js";
import { StdioClientTransport } from "../mcp-server/node_modules/@modelcontextprotocol/sdk/dist/esm/client/stdio.js";

const [mode, id] = process.argv.slice(2);
const base = mode === "base";
const fullpack = mode === "fullpack" || mode === "fullpack-motion" || mode === "fullpack-production";
const root = fullpack ? "production-client/.minecraft/versions/Mechanomania"
  : base ? "production-server-base" : "production-server";
const config = JSON.parse(readFileSync(`integration-test/${root}/config/mcpfabric.config.json`, "utf8").replace(/^\uFEFF/, ""));
assert.equal(config.host, "127.0.0.1");
assert.equal(config.port, fullpack ? 25604 : base ? 25605 : 25603);
assert.equal(config.requireAuth, true);
for (const flag of ["enableWorldWrite", "enableCommands", "enablePlayerControl", "allowAdmin"]) {
  assert.equal(config[flag], false);
}
assert.ok(config.readToken);
const client = new Client({ name: "isolated-moving-acceptance", version: "1" });
await client.connect(new StdioClientTransport({
  command: process.execPath,
  args: [resolve("mcp-server/dist/index.js")],
  env: {
    ...process.env,
    MCPFABRIC_URL: `http://127.0.0.1:${config.port}`,
    MCPFABRIC_TOKEN: mode === "monitor-create" ? config.token : config.readToken,
    MCPFABRIC_TOOLSETS: mode === "monitor-create" ? "world" : "world,readonly",
  },
  stderr: "ignore",
}));

const call = async (name, args = {}) => {
  const response = await client.callTool({ name, arguments: args });
  assert.equal(!!response.isError, false, JSON.stringify(response.content));
  return response.structuredContent ?? JSON.parse(response.content[0].text);
};

function verifyTransform(page) {
  const { origin, xAxis, yAxis, zAxis } = page.transform;
  for (const block of page.blocks) {
    for (const axis of ["x", "y", "z"]) {
      const projected = origin[axis] + (block.x + 0.5) * xAxis[axis]
        + (block.y + 0.5) * yAxis[axis] + (block.z + 0.5) * zAxis[axis];
      assert.ok(Math.abs(projected - block.worldCenter[axis]) < 1e-7);
    }
  }
}

try {
  if (mode === "fullpack-motion") {
    const listing = await call("list_moving_structures", { x: -457, y: 83, z: 42, radius: 8 });
    const bearing = listing.structures.find(({ structureType }) => structureType === "bearing");
    assert.ok(bearing?.assembled && !bearing.stalled);
    const selector = { structureId: bearing.structureId };
    const details = await call("inspect_moving_structure", selector);
    assert.equal(details.structureType, "bearing");
    assert.deepEqual(details.entityPosition, { x: -457, y: 83, z: 42 });
    const samples = [];
    for (let i = 0; i < 6; i++) {
      const motion = await call("inspect_structure_motion", selector);
      assert.equal(motion.status, "ok");
      const page = await call("get_structure_blocks", selector);
      assert.equal(page.memberCount, 2);
      verifyTransform(page);
      const pivot = page.blocks.find(({ id }) => id === "minecraft:slime_block");
      const plank = page.blocks.find(({ id }) => id === "minecraft:oak_planks");
      assert.deepEqual([pivot.x, pivot.y, pivot.z], [0, 0, 0]);
      assert.deepEqual([plank.x, plank.y, plank.z], [1, 0, 0]);
      assert.deepEqual(pivot.worldCenter, { x: -456.5, y: 83.5, z: 42.5 });
      assert.equal(plank.worldCenter.y, pivot.worldCenter.y);
      assert.ok(Math.abs(Math.hypot(plank.worldCenter.x - pivot.worldCenter.x,
        plank.worldCenter.z - pivot.worldCenter.z) - 1) < 1e-6);
      samples.push({ motion, page });
      await delay(150);
    }
    assert.ok(samples.every(({ page }, i) => i === 0 || page.gameTime > samples[i - 1].page.gameTime));
    assert.ok(samples.some(({ page }) => Math.abs(page.transform.xAxis.x - samples[0].page.transform.xAxis.x) > 0.1));
    console.log(JSON.stringify({ listing, details, samples }, null, 2));
  } else if (mode === "fullpack-production") {
    assert.ok(id, "Pass the meter ID");
    const network = await call("get_create_network_summary", { x: -448, y: 78, z: 30 });
    const recipe = await call("inspect_active_recipe", { recipeId: "create:pressing/iron_ingot", inputItem: "minecraft:iron_ingot" });
    const depot = await call("inspect_block_entity", { x: -448, y: 76, z: 30 });
    const hopper = await call("get_block", { x: -448, y: 75, z: 30 });
    const chest = await call("inspect_block_entity", { x: -448, y: 74, z: 30 });
    const meter = await call("query_factory_meter", { meterId: id });
    const journal = await call("list_region_monitors");
    assert.equal(network.status, "ok");
    assert.equal(recipe.displayResult?.id, "create:iron_sheet");
    assert.equal(meter.meterId, id);
    assert.equal(journal.status, "ok");
    console.log(JSON.stringify({ observedUtc: new Date().toISOString(), network, recipe, depot, hopper, chest, meter, journal }, null, 2));
  } else if (fullpack) {
    const environment = await call("get_environment_capabilities");
    assert.equal(environment.serverPresent, true, "Enter MechAgent RC1 Test first");
    assert.equal(environment.minecraftVersion, "1.21.1");
    assert.equal(environment.loaderVersion, "21.1.248");
    assert.equal(environment.create.version, "6.0.10");
    const press = { x: -448, y: 78, z: 30 };
    const kinetic = await call("inspect_create_kinetic", press);
    const network = await call("get_create_network_summary", press);
    const recipe = await call("inspect_active_recipe", {
      recipeId: "create:pressing/iron_ingot", inputItem: "minecraft:iron_ingot",
    });
    const chest = await call("inspect_block_entity", { x: -448, y: 74, z: 30 });
    const structures = await call("list_moving_structures", { ...press, radius: 16 });
    const registry = await call("list_registered_blocks", { modId: "create", search: "shaft", limit: 10 });
    const meters = id ? await call("query_factory_meter", { meterId: id }) : null;
    const monitors = await call("list_region_monitors");
    const history = [];
    for (const { monitorId } of monitors.monitors ?? []) {
      const args = { monitorId, limit: 5, disk: true };
      for (let i = 0; i < 20; i++) {
        const page = await call("query_region_history", args);
        if (page.status !== "pending") { history.push(page); break; }
        await delay(200);
      }
    }
    const authRequest = (method, params, token) => fetch(`http://127.0.0.1:${config.port}/rpc`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify({ method, params }), signal: AbortSignal.timeout(5000),
    });
    const unauthenticated = (await authRequest("info.status", {})).status;
    const readOnlyMutation = (await authRequest("construction.preview", {}, config.readToken)).status;
    const rejectedBoundary = await authRequest("construction.preview", {
      areaId: "rc1-test", dimension: "minecraft:overworld",
      operations: [{ x: -439, y: 90, z: 30, blockId: "minecraft:stone" }],
    }, config.token);
    const boundary = (await rejectedBoundary.json()).error;
    assert.equal(unauthenticated, 401);
    assert.equal(readOnlyMutation, 403);
    assert.equal(boundary?.code, "bad_request");
    assert.match(boundary.message, /outside authorized area/);
    assert.equal(kinetic.status, "ok");
    assert.equal(network.status, "ok");
    assert.ok(recipe.inputMatchesIngredient?.includes(true));
    assert.equal(recipe.displayResult?.id, "create:iron_sheet");
    assert.equal(chest.id, "minecraft:chest");
    assert.equal(chest.capabilities?.items?.status, "available");
    assert.equal(structures.status, "ok");
    console.log(JSON.stringify({ environment, kinetic, network, recipe, chest, structures, registry,
      meters, monitors, history, authorization: { unauthenticated, readOnlyMutation, boundary } }));
  } else if (mode === "base" || mode === "environment") {
    const environment = await call("get_environment_capabilities");
    const listing = await call("list_moving_structures", { x: 80, y: 100, z: 0, radius: 24 });
    const tags = await call("get_runtime_catalog", { kind: "item_tags", tagId: "minecraft:logs", limit: 3 });
    const dataPacks = await call("get_runtime_catalog", { kind: "data_packs" });
    assert.equal(tags.status, "ok");
    assert.ok(tags.ids.length > 0);
    assert.equal(listing.status, base ? "create_not_loaded" : "ok");
    console.log(JSON.stringify({ environment, listing, tags, dataPacks }));
  } else if (mode === "latency") {
    const requests = [];
    for (let i = 0; i < 100; i++) {
      const name = i % 2 === 0 ? "get_create_network_summary" : "list_moving_structures";
      const args = i % 2 === 0 ? { x: 6, y: 128, z: 8 } : { x: 80, y: 100, z: 0, radius: 24 };
      const started = performance.now();
      const result = await call(name, args);
      assert.equal(result.status, "ok");
      requests.push({ name, roundTripMs: performance.now() - started, observationMicros: result.observationMicros });
      await delay(200);
    }
    console.log(JSON.stringify(requests));
  } else if (mode === "meter") {
    const meter = await call("query_factory_meter", { meterId: id });
    const kinetic = await call("inspect_create_kinetic", { x: 6, y: 128, z: 8 });
    const chest = await call("inspect_block_entity", { x: 6, y: 124, z: 8 });
    console.log(JSON.stringify({ meter, kinetic, chest }));
  } else if (mode === "monitor-create") {
    const monitor = await call("create_region_monitor", { name: "Native minecart smoke", areaId: "motion-fixture", dimension: "minecraft:overworld", min: {x:65,y:100,z:0}, max: {x:95,y:100,z:0}, intervalTicks: 20, includeMovingStructures: true });
    assert.equal(monitor.status, "ok");
    console.log(JSON.stringify(monitor));
  } else if (mode === "monitor-read") {
    const fromUtc = process.argv[4];
    const query = { monitorId: id, limit: 100, ...(fromUtc ? { disk: true, fromUtc } : {}) };
    let history;
    for (let attempt = 0; attempt < 80; attempt++) {
      history = await call("query_region_history", query);
      if (history.status !== "pending") break;
      await delay(100);
    }
    assert.equal(history.status, "ok");
    const samples = history.entries.filter((event) => event.movingStructures);
    assert.ok(samples.length > 0);
    console.log(JSON.stringify(samples));
  } else if (mode === "assembled") {
    const listing = await call("list_moving_structures", { x: 80, y: 100, z: 0, radius: 24 });
    assert.equal(listing.status, "ok");
    const cart = listing.structures.find((structure) => structure.structureType === "minecart");
    assert.ok(cart, JSON.stringify(listing));
    const args = { structureId: cart.structureId };
    const details = await call("inspect_moving_structure", args);
    const first = await call("get_structure_blocks", { ...args, limit: 1 });
    verifyTransform(first);
    const pages = [first];
    while (pages.at(-1).nextOffset !== undefined) {
      const page = await call("get_structure_blocks", { ...args, limit: 1, snapshotId: first.snapshotId, offset: pages.at(-1).nextOffset });
      assert.equal(page.gameTime, first.gameTime);
      verifyTransform(page);
      pages.push(page);
    }
    assert.equal(pages.flatMap((page) => page.blocks).filter((block) => block.id === "minecraft:oak_planks").length, 1);
    console.log(JSON.stringify({ listing, details, pages }));
  } else if (mode === "bearing") {
    const position = { x: 80, y: 112, z: 8 };
    const listing = await call("list_moving_structures", { ...position, radius: 6 });
    assert.equal(listing.status, "ok");
    const bearing = listing.structures.find((structure) => structure.structureType === "bearing");
    assert.ok(bearing, JSON.stringify(listing));
    const args = { structureId: bearing.structureId };
    const kinetic = await call("inspect_create_kinetic", position);
    assert.equal(kinetic.status, "ok");
    assert.notEqual(kinetic.rpm, 0);
    const firstPage = await call("get_structure_blocks", { ...args, limit: 1 });
    assert.equal(firstPage.status, "ok");
    assert.equal(firstPage.nextOffset, 1);
    await delay(120);
    const nextPage = await call("get_structure_blocks", {
      ...args, snapshotId: firstPage.snapshotId, offset: firstPage.nextOffset, limit: 1,
    });
    assert.equal(nextPage.status, "ok");
    assert.equal(nextPage.gameTime, firstPage.gameTime);
    assert.deepEqual(nextPage.transform, firstPage.transform);
    verifyTransform(firstPage);
    verifyTransform(nextPage);
    const captures = [];
    for (let i = 0; i < 5; i++) {
      const page = await call("get_structure_blocks", args);
      assert.equal(page.status, "ok");
      verifyTransform(page);
      assert.ok(page.blocks.some((block) => block.id === "minecraft:oak_planks"));
      captures.push(page);
      await delay(120);
    }
    const first = captures[0];
    assert.ok(captures.some((page) => page.gameTime > first.gameTime
      && Math.abs(page.transform.xAxis.x - first.transform.xAxis.x) > 0.05));
    console.log(JSON.stringify({ bearing, kinetic, captures, pages: [firstPage, nextPage] }));
  } else if (mode === "piston") {
    const position = { x: 70, y: 114, z: 5 };
    const samples = [];
    let capture;
    let structureId;
    for (let i = 0; i < 70; i++) {
      if (!structureId) {
        const listing = await call("list_moving_structures", { ...position, radius: 12 });
        structureId = listing.structures.find((structure) => structure.structureType === "piston")?.structureId;
      }
      if (structureId) {
        const sample = await call("inspect_structure_motion", { structureId });
        samples.push(sample);
        if (!capture && sample.status === "ok") {
          capture = await call("get_structure_blocks", { structureId });
          assert.equal(capture.status, "ok");
          verifyTransform(capture);
          assert.ok(capture.blocks.some((block) => block.id === "minecraft:oak_planks"));
        }
      }
      await delay(65);
    }
    assert.ok(capture, "The piston contraption was not observed");
    assert.ok(samples.some((sample) => sample.motion?.anchorDisplacementBlocks?.x > 0.01));
    console.log(JSON.stringify({ capture, samples }));
  } else if (mode === "motion") {
    const samples = [];
    for (let i = 0; i < 70; i++) {
      samples.push(await call("inspect_structure_motion", { structureId: id }));
      await delay(65);
    }
    assert.ok(samples.some((sample) => Math.abs(sample.motion?.anchorDisplacementBlocks?.x ?? 0) > 0.01));
    console.log(JSON.stringify(samples));
  } else if (mode === "disassembled") {
    const details = await call("inspect_moving_structure", { structureId: id });
    const block = await call("get_block", { x: 93, y: 101, z: 0 });
    assert.equal(details.status, "not_found_or_unloaded");
    assert.equal(block.id, "minecraft:oak_planks");
    console.log(JSON.stringify({ details, block }));
  } else throw new Error("Unknown acceptance mode.");
} finally {
  await client.close();
}
