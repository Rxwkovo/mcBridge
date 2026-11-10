# Integration profiles

Every profile uses Java 21, Minecraft 1.21.1 and NeoForge 21.1.248. Install the current Bridge build and use an authenticated loopback endpoint. Keep world writes, arbitrary commands, player control and admin mode disabled. Use server-owned work areas for authorized construction.

| Profile | Isolated runtime | Mods | Entry point |
| --- | --- | --- | --- |
| Base | `integration-test/production-server-base` | Bridge and NeoForge | Install a dedicated NeoForge server in that directory, then use `scripts/isolated-production-server.ps1 -Profile Base -Start` |
| Native Create | `integration-test/production-server` | Base plus Create 6.0.10 and its embedded dependencies | `scripts/isolated-production-server.ps1 -Start` |
| Mechanomania | `integration-test/production-client` | Mechanomania's existing profile and Bridge | Existing `scripts/prepare-production-client.ps1` or `scripts/profiles/prepare-mechanomania.ps1` |

For a prepared Mechanomania instance, stop its isolated client and copy only the new Bridge JAR into the existing profile's mods directory. Keep the current profile and world configuration. The preparation command is for a fresh test copy. Its optional source parameters name the launcher and game root, and it uses `integration-test/instance` as the staged modpack source. It copies no launcher account or save.

The dedicated launcher checks that the selected port and profile are isolated and requires a local EULA file. Base uses game port 25577 and Bridge port 25605. Native Create uses 25576 and 25603. Use a separate test world per profile. Never launch an existing Create test world with the Base profile.

## Native minecart fixture

Use the local console of the isolated **Native Create** dedicated server. Keep the MCP tools authenticated and read-only for this test. The fixture assumes these coordinates are clear and have no other entities or builds.

```text
forceload add 64 0 96 0
fill 64 99 0 96 99 0 minecraft:stone
fill 64 100 0 96 100 0 minecraft:rail[shape=east_west]
setblock 66 100 0 create:cart_assembler[shape=east_west]
setblock 66 101 0 minecraft:oak_planks
setblock 66 99 0 minecraft:redstone_block
setblock 93 100 0 create:cart_assembler[shape=east_west]
summon minecraft:minecart 66.5 100.15 0.5 {Tags:["mechagent_motion_fixture"]}
```

Run `node scripts/isolated-moving-acceptance.mjs assembled`. Save the reported structure UUID. Prepare the track before starting the motion sampler.

```text
fill 68 99 0 92 99 0 minecraft:redstone_block
fill 68 100 0 92 100 0 minecraft:powered_rail[shape=east_west,powered=true]
```

Start `node scripts/isolated-moving-acceptance.mjs motion <uuid>`. While it is sampling, issue the following console command to start the cart.

```text
data merge entity @e[type=minecraft:minecart,tag=mechagent_motion_fixture,limit=1] {Motion:[0.42d,0.0d,0.0d]}
```

The unpowered assembler at the other end disassembles the native structure. Run `node scripts/isolated-moving-acceptance.mjs disassembled <uuid>` to verify the world block. Use a fresh test world or explicitly clean up the tagged cart and test track before rerunning.

## Native bearing and piston fixtures

Use the isolated Native Create console. Keep the fixture volumes empty before placing these blocks. Create assembles the structures when the drive starts.

```text
forceload add 80 8
setblock 80 113 8 minecraft:slime_block
setblock 81 113 8 minecraft:oak_planks
setblock 80 112 8 create:mechanical_bearing[facing=up]
setblock 80 111 8 create:creative_motor[facing=up]
```

Run `node scripts/isolated-moving-acceptance.mjs bearing`. It checks the rotating basis and recomputes the captured world center of each member.

```text
forceload add 64 5 79 5
setblock 70 114 5 create:mechanical_piston[facing=east,axis_along_first=true]
fill 64 114 5 69 114 5 create:piston_extension_pole[facing=east]
setblock 71 114 5 minecraft:oak_planks
```

Start `node scripts/isolated-moving-acceptance.mjs piston`, then run `setblock 70 115 5 create:creative_motor[facing=down]` while the sampler polls. The piston extends six blocks. After it stops, use `execute if block 77 114 5 minecraft:oak_planks` to verify that Create placed the payload back into the world.

## Mechanomania release smoke

Enter the isolated `MechAgent RC1 Test` world with the candidate installed and run `node scripts/isolated-moving-acceptance.mjs fullpack [meter-id]`. Use the prepared `rc1-test` work area. This probe reads environment versions, the saved press and its network, the iron pressing recipe, the chest, the Create registry, persistent monitor history and the moving-structure endpoint. It also checks authentication and rejects an out-of-area preview without applying edits. Supplying the existing meter ID verifies its retained counter. Record actual instrument, production and moving-entity observations separately.

For the [final RC1 observations](../../reports/v1-fullpack-acceptance.json), back up the isolated save before setting up fixtures. Use local singleplayer commands only if needed and restore the saved command setting after testing.

1. Wear Create goggles and inspect the speedometer at `(-450, 78, 30)` and stressometer at `(-449, 78, 30)`. Save screenshots and query `get_create_network_summary` at both positions. Repeat after adding an east-facing mechanical press at `(-451, 78, 30)`, then remove the extra press. Keep the load stable while capturing each pair.
2. Use the press at `(-448, 78, 30)`, the depot two blocks below it, and the hopper directly below the depot. Create a fresh meter for `create:iron_sheet` and take snapshots with `node scripts/isolated-moving-acceptance.mjs fullpack-production <meter-id>`. Lock the hopper with a redstone block at `(-449, 75, 30)` before feeding one iron ingot to the depot. Observe one iron sheet on the depot, unlock the hopper and check that both the meter and chest increased by one. Save and exit normally, reload, confirm the same meter ID and baseline, then repeat. Use `query_region_history` with `disk: true` around each transfer timestamp to check the chest history.
3. In a verified empty fixture area, place an upward-facing creative motor at `(-457, 81, 42)`, an upward-facing mechanical bearing above it, a slime block at `(-457, 83, 42)` and oak planks immediately east of the slime. Place the motor last to start rotation. Run `node scripts/isolated-moving-acceptance.mjs fullpack-motion` to capture discovery, details, motion and transformed member centers. Save a screenshot of the assembled bearing.

## RC1 process-exit and load checks

Run `node scripts/rc1-recovery-acceptance.mjs` from the repository after the Windows NeoForge build and `npm run build` in `mcp-server`. It requires the existing Native Create profile, including its saved test press, approved EULA and prepared Java 21 launcher. It makes a new copy of the disposable save, binds to loopback ports 25586 and 25613, and forcibly exits only the Java child started by the script. The test writes reports and server logs under its unique ignored `integration-test/rc1-fault-*` directory. `--performance-only` skips the forced exits, `--telemetry-only` skips the construction exits, and `--policy-only` uses ports 25587 and 25614 for the unload, authorization and JFR file-force checks. The load test supplies known iron-sheet stock to test transfer. Use `--press-only` to supply iron ingots and exercise actual pressing, collection and meter continuation after a normal restart.
