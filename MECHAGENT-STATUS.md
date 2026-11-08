# MechAgent Bridge — POC-01 status, 2026-11-08

Fork of MCPFabric commit `9f22c7382d032850c3b69122b27d671902daa587`. The upstream MIT license and attribution remain in `LICENSE`. This is not a completed POC acceptance.

## Target and changes

Local Mechanomania profile: Minecraft 1.21.1, NeoForge 21.1.248 (profile/log), Create 6.0.10 (installed jar). Only the NeoForge 1.21.1 build was compiled in this phase. Create integration is optional and restricted to the exact 6.0.10 version; Fabric and other Create versions are not validated. The adapter has no compile-time Create dependency.

- `world.listRegisteredBlocks` / `list_registered_blocks` uses `enableRegistryRead` independently of `enableVision`, with the read-only token. Registry names may be translation keys. The current implementation requires a running integrated/dedicated server.
- `world.inspectBlockEntity` / `inspect_block_entity` reports block state and type, tick, and unsided NeoForge item (up to 64 slots), fluid (up to 16 tanks) and energy capability views. Null-side queries may not expose sided capabilities; omitted item components may contain more data. Capabilities are live mod-provided code, not a universal guarantee of side-effect-free implementations. Raw NBT is not returned. `includePersistent=true` invokes the mod serializer and returns only numeric BurnTime, CookTime, CookTimeTotal, Energy and energy; serialization itself may be expensive and there is no size bound on the intermediate mod-created tag. Fields absent or of different types are omitted, not zero-filled.
- `world.inspectCreateKinetic` / `inspect_create_kinetic` reads Create getSpeed, getTheoreticalSpeed, isOverStressed and existing entity network fields. Reflection errors and unsupported versions return explicit states. Sign follows Create's axis convention, not a guaranteed screen-relative clockwise direction.
- `world.inspectCreateNetwork` / `inspect_create_network` uses an anchor coordinate and the existing TorquePropagator per-level network map. It reads Create's currentCapacity/currentStress cache in SU including unloaded contributions, reports remaining SU and utilization only for positive capacity. The cache may be stale; per-node SU calls Create's read-only getActualStressOf/getActualCapacityOf, which multiply cached coefficients by absolute theoretical/generated RPM. No network is created, modified or globally scanned. Pages return up to 64 and visit at most 4096 entries. HashMap order can change between calls, so pages can repeat/omit members; beyond the traversal ceiling continuation is explicitly unavailable. Source positions are Create fields, not proof of complete physical topology. Network IDs are not stable across reloads or changes. There is no long-running task to cancel.
- `world.getBlocks` validates the page limit and cursor, checks overflow, visits at most 4096 positions, and returns offset, nextOffset, gameTime and number of unloaded positions. A cursor is not bound to its original dimensions/bounds; use identical query arguments for every page. Unloaded positions are skipped and require a later rescan. Cross-tick pages are not atomic.
- World queries use the server thread and vision gate; read-only token allowlist includes the new reads. Fresh configs default world write, arbitrary command and player control to false; existing configs are not changed. There is no protected work-area editor.

Main implementation files: `src/main/java/dev/mcpfabric/handlers/WorldHandlers.java`, `handlers/support/RegionCursor.java`, `handlers/support/Gates.java`, `platform/Platform.java`, `neoforge/NeoForgePlatform.java`, `neoforge/NeoForgeCapabilities.java`, `adapters/CreateKineticAdapter.java`, `adapters/CreateNetworkAdapter.java`, `bridge/ReadAccess.java`, `config/McpConfig.java`, `handlers/InfoHandlers.java`, and `mcp-server/src/tools.ts`; tests are under `src/test/java/dev/mcpfabric/` and `mcp-server/test/`.

## Build and tests

PowerShell, from the project directory:

```powershell
$env:GRADLE_USER_HOME = "$PWD\.gradle-user"
New-Item -ItemType Directory -Force .test-tmp | Out-Null
$env:TEMP = (Resolve-Path .test-tmp).Path
$env:TMP = $env:TEMP
.\gradlew.bat :1.21.1-neoforge:build --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m' -Pcompiler_java_version=25
cd mcp-server
npm ci --cache ..\.npm-cache
npm run typecheck
npm test
```

JDK 25 compiled with Java 21 release output; JDK 21 is also an intended toolchain. Node 22.16+ is required. On this machine the Gradle dependency download used its existing HTTPS proxy. NeoForge build succeeded; Java tests 81/81; TypeScript typecheck and MCP tests 127/127. Tests cover synthetic cache snapshots, pagination arithmetic, parameter bounds and static tool/auth contracts; they do not establish Create runtime correctness.

JAR: `versions/1.21.1-neoforge/build/libs/mcpfabric-neoforge-0.9.1+1.21.1.jar`, SHA-256 `DF2A7D94C92B6681C67F0DDB45E497DE9154DA95CCEFB189201C818DA415732C`. An independent instance copy lives under ignored `integration-test/instance`, with the JAR installed and no original saves copied. It does not include a complete launcher setup.

## Actual isolated launch

The development client used its own `versions/1.21.1-neoforge/run` directory, copied mods/config/kubejs, no original saves, authentication enabled and writes/commands/player control disabled. First launch crashed in NeoForge's development GameTest discovery: Create Central Kitchen's HearthAndHarvestGameTests referenced a missing optional CaskBlockEntity. On a second attempt only the copied `create-central-kitchen-2.6.2.jar` was renamed `.disabled`; no original instance file was touched. The modified-copy client loaded NeoForge 21.1.248 and the bridge. A real MCP stdio client called `get_status` successfully with a read-only token; a write RPC with that token returned HTTP 403. No world was loaded; `list_registered_blocks` returned `no_server`. The client was stopped after the check. The newly added network adapter was compiled after that client launch and has not been loaded with Create. This is not evidence of compatibility with the complete unchanged pack.

A separate development-client launch with an empty mods directory loaded NeoForge and the current bridge without Create; `info.status` succeeded and included the network RPC. No world was loaded, so the adapter's `create_not_loaded` in-world response was not exercised. The client was stopped and the isolated mods directory restored afterward.

## Remaining verification and work

No in-world BlockEntity, NeoForge capability, RPM, SU, network member or TPS/MSPT verification has occurred. No full topology or evidence-based diagnosis tool is implemented. Exact version reflection and internal fields need regression coverage in the actual environment; `calculateStress`/`calculateCapacity` are intentionally not called because Create may mutate its network maps. Signed zero and missing data must not be interpreted as a fault. Create contraptions are unsupported.

Manual disposable-world acceptance, using an isolated launcher instance with its own game directory, correct Java 21 and the final JAR (do not enable unrestricted writes):

1. Start the copied instance and create a new creative test world; verify loader/mod errors and confirm no original save is selected. Configure the MCP client with the read-only token from the test instance config, local URL and `MCPFABRIC_TOOLSETS=readonly`; do not publish the token.
2. Call `get_status`, then `list_registered_blocks` with `modId=create`. Inspect an ordinary block, a Create kinetic block and a block with inventory/fluid/energy capabilities. Compare with the in-game UI; test an unloaded chunk and an absent BlockEntity.
3. Manually assemble water wheel → shaft → consumer. Record tick, actual/theoretical RPM, network ID, cached SU, and member page. Add consumers and record the change; then overload the network and verify actual/theoretical RPM and over-stress independently. Do not equate an absent field with zero.
4. Break a shaft, wait for network stabilization, and compare network IDs, RPM, member pages and cache freshness. Add a compatible source and repeat. Test a zero-capacity network and a Create-free installation separately.
5. Record query latency and server TPS/MSPT at idle and during representative bounded pagination. Record versions, ticks, screenshots/logs and whether cached results were dirty or stale. Never join pages as an atomic snapshot.
