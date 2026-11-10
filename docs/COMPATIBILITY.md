# Compatibility and capability discovery

## MechAgent Bridge v1.0 scope

The v1.0.0 release target is Java 21, Minecraft **1.21.1**, NeoForge **21.1.248** and Create **6.0.10**. It covers native static kinetic networks, authorized construction and recovery, production monitoring with the verified metering boundary, and basic observation of loaded native Contraptions. Dynamic observation covers discovery, structure snapshots, local-to-world transforms and sampled anchor displacement where available. The acceptance report records runtime evidence by mechanism.

Mechanomania is an integration profile for the exact release artifact. Specialized diesel, electrical, nuclear and aeronautics adapters, Fabric, and other Create versions are deferred. Generic capability inspection and internal adapter interfaces remain available on the release target.

## Observed compatibility

| Profile | Evidence |
| --- | --- |
| Base NeoForge 21.1.248 | The previous candidate started a separate world without Create. MCP reported `create_not_loaded` and returned live tags and selected packs. |
| Native Create 6.0.10 | Java 21 dedicated-world tests verified a minecart, mechanical bearing and mechanical piston. Elevator and train runtime checks are pending. See the [moving-structure report](../reports/v1-moving-structure-acceptance.json). |
| Mechanomania 1.1.13.1 | The RC1 JAR matched both Create gauges at two loads, observed two fresh iron sheets crossing the verified depot-to-hopper boundary across a normal world reload, and captured a native rotating bearing with two real members. See the [full-pack report](../reports/v1-fullpack-acceptance.json). |

`get_environment_capabilities` now reports loader mod versions, registered RPCs, Create detection, server configuration and query limits. Live observations carry their own result status. The HTTP server decides token and area access for each call. `get_runtime_catalog` provides registry-gated item IDs, recipe IDs, item/block tag IDs and members, and selected server pack IDs. Restart pagination after a data reload. Pair block IDs with `list_registered_blocks`, recipe IDs with `inspect_active_recipe`, and live kinetic fields with `inspect_create_kinetic`. Full serializer-level recipe export, config fingerprints and static machine rules are pending.

The four moving-structure tools report whether the adapter can observe the loaded entity, motion sample or block snapshot. A returned type such as `elevator` or `train_carriage` identifies the detected Create class. It does not establish tested compatibility. An `ok` listing can be empty. Internal capabilities, live angular velocity and mechanical stress inside a moving structure remain unsupported.

## Compatibility layers

| Layer | Current implementation / evidence | Target contract |
| --- | --- | --- |
| Minecraft and loader | Stonecutter nodes, separate loader packages; the release ships the verified NeoForge 1.21.1 artifact | A tested matrix per Minecraft + loader version, with actual loader lifecycle, capabilities, mappings and transitive dependencies |
| Create | `CreateKineticAdapter` checks exactly 6.0.10 using loader metadata supplied through the platform; network adapter accesses internal fields; absent/mismatched versions degrade | Version-keyed providers with feature-level probing and tests against the actual artifact; no broad version claims from similar signatures |
| Create addons / other mods | No universal addon contract; generic NeoForge unsided item/fluid/energy capabilities and registered blocks can be inspected | Discover each available capability and, only when installed and verified, register an optional addon adapter; distinguish capability present, inaccessible, unsupported and error |
| Modpack recipes/configuration | `inspect_active_recipe` looks up one live recipe ID; registry query shows live block properties. `knowledge/` fingerprints jars and KubeJS, not all inputs | Runtime recipe/tag export and explicit source/coverage; fingerprints cover identified active datapacks, mods and relevant config with omissions declared, without leaking secrets |
| World state | APIs depend on server availability, loaded chunks and live state; queries may span ticks, network caches may be stale | Include world/session, loaded status, sample tick, time, consistency, adapter version, freshness and recovery gaps; never load chunks implicitly |

The first verified production meter is narrower than Create support: NeoForge 21.1.248 + Create 6.0.10, a particular depot→vanilla hopper path, one item filter, and only non-simulated extraction with corroborated destination evidence. `require=0` mixin injection and an exact version check do not prove that the hook actually applied; no observed event is not proof of no transfers. A future descriptor must distinguish declared compatibility from verified hook coverage. The meter does not cover all hopper paths or machine output, and there is no general fluid or FE transfer counter.

## Current discovery and gaps

`info.status` exposes bridge/game version, loader, access mode, server presence and registered methods. `info.capabilities` exposes coarse booleans for gates and client/server side. Registry pages expose IDs and legal properties, while server-owned `mechagent-construction.json` and runtime checks determine which of those may actually be edited. MCP toolset selection and annotations affect what a client sees, not what the server authorizes. `inspect_active_recipe` inspects one exact recipe and returns display output plus per-ingredient matches, not a full recipe export or proof of successful crafting. Per-tool readiness, area-specific state values, runtime hook health and knowledge fingerprints still need dedicated evidence.

## Proposed follow-up discovery contract

A follow-up schema could add these per-feature readiness fields to the existing environment call.

```json
{
  "schemaVersion": 1,
  "environment": {"minecraft": "1.21.1", "loader": {"id": "neoforge", "version": "21.1.248"}, "create": {"version": "6.0.10", "status": "detected"}},
  "features": [{"id": "create.kinetic.inspect", "implementation": "create-6.0.10", "status": "available", "reason": null, "limitations": ["static_world_only", "network_values_cached"]}, {"id": "meter.depot_to_hopper", "status": "unverified", "reason": "hook_not_observed", "limitations": ["boundary_only"]}],
  "authority": {"scope": "current_token_and_area", "status": "not_evaluated"},
  "observation": {"gameTick": null, "wallTimeUtc": "…", "atomic": false}
}
```

Do not expose token values, absolute paths, world seed, player names, secrets, config content, hashes of low-entropy secrets or private mod metadata by default. Check actual disclosure and client identity/permissions before returning per-area or per-world data. The manifest distinguishes (1) registered, (2) loader/mod/version present, (3) adapter probed and healthy, (4) runtime world/chunk available, and (5) allowed for the current token and work area. If HTTP auth does not pass principal context through `RpcContext` today, do not infer principal-specific availability from global config; first introduce and test a trustworthy principal context. Use stable statuses `available`, `unsupported`, `unavailable`, `unverified`, `disabled`, `forbidden`, `stale`, `error`, plus a reason and scope; avoid conflating `false`, `zero` and missing.

Capabilities should advertise budgets and cache TTLs, sampling/atomicity semantics, units, Create versions tested, and supported metering boundaries. Version strings alone never prove a mixin is active. Independently test actual entrypoint application, optional-mod absence, mod mismatch and reflection failures without loading missing classes. If a runtime value is independently retrievable, a single inaccessible field must not fail the rest of the observation. A concrete block may not implement an advertised generic capability.

For construction, a future discovery response must intersect live block IDs/properties with the current area policy and current token; it must also state that block entities and context-dependent placement can still be refused. Keep existing preview/expected-state/commit as the final authority. A read-only token cannot infer or acquire mutation authority through discovery. No unrestricted command or general world-write APIs are enabled for profile convenience.

## Profile and version strategy

1. Keep isolated launcher/server scripts, explicit instance paths, test-only configurations, evidence and pack-specific expectations in test profiles. The old Java `AcceptanceFixture` was removed from production; the isolated-console recipes remain in [the integration profiles](../scripts/profiles/README.md). Exclude secrets and saves.
2. Add a minimal Create-only profile and a Create-absent profile before broadening versions. Validate Java 21 / NeoForge 21.1.248 / Create 6.0.10 first; then add each new combination using its real artifacts and run tests. Ensure the base mod loads without Create.
3. Treat mod, KubeJS, active datapack and relevant configuration identities as provenance, not an authorization bypass. Avoid including credential material in fingerprint manifests; mark unsupported/unobserved inputs as incomplete. Cache invalidation requires an explicit world reload/environment epoch and real datapack/config-change semantics.
4. Knowledge indices are keyed by a schema and environment generation, while a world session is a separate runtime scope. Never automatically reuse an index merely because a filename or pack name matches. A clone or restart may share static content but must not share mutable world identity without an explicit policy.
5. Differential acceptance compares the same observable invariants across profiles: default-deny modes, registry and no-Create behavior, unloaded chunks, semantics for zero/missing/stale, bounded capture and safe pagination. Full pack success means actual loaded-world results plus recorded known differences; a successful compile or menu is insufficient.
