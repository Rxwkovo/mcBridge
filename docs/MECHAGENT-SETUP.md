# MechAgent Bridge: installation and restricted construction

MechAgent Bridge has been exercised in the Native Create test server and in the isolated Mechanomania world. The [RC1 acceptance report](../reports/v1-rc1-acceptance.json) lists the remaining release gates. Make a disposable instance and world before testing.

## Install and connect

1. Install the built `mechagent-bridge-neoforge-1.0.0.jar` in the **copy** of the instance's `mods` directory. Use Minecraft 1.21.1, NeoForge 21.1.248, Create 6.0.10, and Java 21. Do not install both Fabric and NeoForge builds.
2. Start the instance once so the mod can generate `config/mcpfabric.config.json`. Keep `host` as `127.0.0.1`, `requireAuth` true, and `enableWorldWrite`, `enableCommands`, `enablePlayerControl`, `enableSupervisedControl`, `enableUiControl`, and `allowAdmin` false. Existing configuration files are not automatically updated to new defaults: inspect the actual file. Keep the generated `token` and `readToken` private.
3. Build the local MCP server with Node.js 22.16 or later: in `mcp-server`, run `npm ci` followed by `npm run build`. For an MCP host, configure `node` with the absolute path to `mcp-server/dist/index.js`; set `MCPFABRIC_URL` to the local bridge URL and `MCPFABRIC_TOKEN` to the token chosen below. `MCPFABRIC_TOOLSETS=world` exposes the region tools; `readonly` limits the MCP catalogue for observation-only clients. The MCP client launches the process over stdio. Do not print tokens to shared logs or commit them.
4. Enter a loaded world and check `get_status`, `list_capabilities`, `list_registered_blocks` and `get_block`. A running game process alone does not establish compatibility with the full modpack.

`inspect_active_recipe` checks one exact recipe ID in the running server's recipe manager; its displayed result and per-ingredient candidate checks do not guarantee a complete match or dynamic outputs. With vision enabled, `query_entities` and `get_entity` report dropped-item IDs and counts without item components. These bounded observations do not constitute a full registry, tags or data-pack export.

## Read-only and region construction

For observation only, set `accessMode` to `READ_ONLY`, use `readToken`, and keep the write and control flags above disabled. The full `token` is needed for authorized construction; the read-only token is refused for construction writes.

For restricted construction, the server operator may set `accessMode` to `REGION_BUILD` and create `config/mechagent-construction.json` using the actual absolute path to the **test world**. Example only; choose bounds for your own disposable world:

```json
{
  "enabled": true,
  "worldPath": "D:\\example\\isolated-world",
  "areas": [{
    "id": "workshop",
    "dimension": "minecraft:overworld",
    "min": {"x": 0, "y": 64, "z": 0},
    "max": {"x": 16, "y": 80, "z": 16},
    "allowedBlocks": ["create:mechanical_press"]
  }]
}
```

The server-owned list is explicit, not a wildcard: baseline allowed blocks are air, stone and Create shaft; `allowedBlocks` adds specific registered IDs, with at most 128 distinct explicit identifiers per area. A configuration change invalidates old previews. Use registry discovery to obtain legal block IDs and state properties. Preview with expected prior states, then commit with a unique idempotency key and poll the operation. Edits are non-atomic; placing a block entity can have mod side effects, and this interface does not remove or overwrite existing block entities other than its special shaft handling. In particular a placed press cannot be undone through this interface. Do not assume an undo restores kinetic state or block entity data.

`REGION_BUILD` is separate from the legacy unbounded world-write, command and player-control gates; do not enable those merely to build. Keep authorization areas and allowed blocks as small as practical. A lost response does not prove an edit failed: reuse the same idempotency key for the same request, query the operation, and after restart inspect reconciliation results before deciding on a new action.

## Environment and moving structures

`get_environment_capabilities` reports detected Create support and observation limits. Use a read-only token for environment, registry, catalog and moving-structure queries. Configuring a monitor requires a build token and a server-owned work area.

Start with `list_moving_structures`, then call `inspect_moving_structure`, `get_structure_blocks` or `inspect_structure_motion` with the entity UUID. Member positions are local. Use the origin and basis from the same capture to project a local center. Pass its snapshot ID and next offset for additional pages. Snapshots expire after 30 seconds and capture up to 512 candidate blocks within a soft time budget. Check `truncated` when the structure is large.

Add `includeMovingStructures: true` to a region monitor to record loaded structure origins and motion samples. An absent entity may have left the region, unloaded or disassembled. World block observations can help distinguish those cases.

See the [test profiles and native minecart fixture](../scripts/profiles/README.md) and the [compatibility matrix](COMPATIBILITY.md).

## Troubleshooting

- `no_server`: enter a world; a client at its title screen has no server world to inspect.
- `forbidden` / HTTP 403: check the selected token, access mode and tool set. Never share the full token just to make a read-only client work.
- `No enabled, valid server-owned work area`: check the absolute world path, dimension, bounds and trusted config syntax. A copied world has a different path.
- `Block is not in construction allowlist`: add only the exact registered ID to the operator-owned area's `allowedBlocks`; clients cannot grant themselves permissions.
- A preview conflict, unloaded chunk or changed policy requires a fresh observation and preview. Cross-tick world observations are not atomic.
- Zero actual RPM is not proof of absent power: inspect theoretical RPM, network and overload state. SU network totals are Create caches and may be stale.
- A `pending` disk-history query must be repeated with identical arguments. The current history, telemetry and performance limitations are in `MECHAGENT-STATUS.md`.
- `query_factory_metrics` returns bounded per-position last-interval inventory observations in `points`. A zero net change does not mean no production or transfer. These samples are non-atomic and are not time-window throughput statistics.
- Add `windowSeconds` (1–86400), or explicit `fromUtc`/`toUtc`, to aggregate wall-time history. For game ticks use `timeBasis: "game_ticks"` and optionally `fromTick`/`toTick`; relative windows use 20 ticks per game second. Repeat identical parameters while the result says `pending`. The scan is non-atomic and may read the full journal; only intervals bounded by two adjacent samples are weighted, large gaps are excluded and coverage is reported. RPM durations describe rotation rather than proven processing; SU is cached stress rather than energy. Inventory deltas are net changes and production/throughput remain unavailable.
- `query_kinetic_history` projects raw sample pages; its cursor and limit refer to all raw samples, so a page may contain fewer kinetic entries. `compare_factory_periods` compares two explicit UTC windows through separate non-atomic scans. Repeat identical arguments while either tool reports `pending` and inspect coverage before interpreting deltas.
- `kineticPoints` contains per-position RPM and cached network SU where available. `networkUtilization` is calculated from those cached values only when capacity is positive; it can be stale and is not energy consumption.

## Factory Meter (NeoForge 21.1.248 / Create 6.0.10 only)

With vision enabled and a server-owned authorized area covering both blocks, use `create_factory_meter` with `areaId`, `dimension`, the vanilla `hopper` coordinates and one `itemId`. The block immediately above the hopper must be a Create depot. Keep the returned `meterId` and use `query_factory_meter` to read the cumulative observed transfers; `stop_factory_meter` stops it. Creation and stopping require the REGION_BUILD full token. The read token may query an authorized meter. Stop is permanent; pause/resume is not implemented.

The count covers observed transfers at this boundary. The asynchronous journal can lag the returned count. `partial_restart_gap` marks uncertain coverage across a restart, including a normal world reload. World saves and journal writes happen independently, so a forced exit can roll back inventory while retaining a recorded transfer. Factory Meter cannot guarantee exactly-once recovery. Compare observed inventory and meter increments within a known loaded, authorized interval. Revoked authorization, unloaded chunks, ambiguous callbacks and storage failures can also leave gaps.

Telemetry stops writing at its disk quota and reports `disk_limit` through `list_region_monitors`. The default quota is 64 MiB. To retain existing segments and continue writing, set the server JVM property `-Dmechagent.telemetry.diskQuotaBytes=134217728` for a 128 MiB quota and restart. The allowed range is 4–512 MiB. Writes made during the gap have incomplete coverage. Authorization reads the server-owned policy file on the game thread.

General item and fluid metering, log compression and retention, and large-network optimization remain future work.
