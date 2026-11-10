package dev.mcpfabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.adapters.CreateAdapterProvider;
import dev.mcpfabric.adapters.CreateNetworkAdapter;
import dev.mcpfabric.adapters.CreateNetworkJobs;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.handlers.support.CommandRunner;
import dev.mcpfabric.handlers.support.Gates;
import dev.mcpfabric.handlers.support.Levels;
import dev.mcpfabric.handlers.support.RegionCursor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** World read (block/region/find/time/weather/dimensions/raycast) and write (set/fill/time/weather). */
public final class WorldHandlers {
	private static final int DEFAULT_REGION_CAP = 4096;
	/**
	 * Most positions one region read may visit (256x256x256). The scan runs on the server thread, so
	 * maxBlocks alone (it limits the answer, not the work) let a huge mostly-air region freeze the server.
	 */
	private static final long MAX_REGION_VOLUME = 1L << 24;
	private static final int SCAN_BUDGET = 250_000;

	private WorldHandlers() {}

	public static void register(RpcRouter router) {
		router.register("world.listMovingStructures", ctx -> onServer(server -> {
			var level = Levels.resolve(server, ctx.optString("dimension", null));
			double x = ctx.getDouble("x"), y = ctx.getDouble("y"), z = ctx.getDouble("z");
			int radius = ctx.optInt("radius", 16);
			if (radius < 1 || radius > 32 || !level.isInWorldBounds(BlockPos.containing(x, y, z)))
				throw RpcException.badRequest("Invalid search center or radius (1..32).");
			return CreateAdapterProvider.moving().list(level, new Vec3(x, y, z), radius);
		}));
		router.register("world.inspectMovingStructure", ctx -> onServer(server ->
				CreateAdapterProvider.moving().inspect(
						Levels.resolve(server, ctx.optString("dimension", null)), structureId(ctx))));
		router.register("world.getStructureBlocks", ctx -> onServer(server ->
				CreateAdapterProvider.moving().blocks(
						Levels.resolve(server, ctx.optString("dimension", null)), structureId(ctx),
						ctx.optString("snapshotId", null), ctx.optInt("offset", 0), ctx.optInt("limit", 32))));
		router.register("world.inspectStructureMotion", ctx -> onServer(server -> {
			var result = CreateAdapterProvider.moving().inspect(
					Levels.resolve(server, ctx.optString("dimension", null)), structureId(ctx));
			result.remove("internalCapabilityStatus");
			return result;
		}));
		router.register("world.networkSummary", ctx -> onServer(server -> { ServerLevel level=Levels.resolve(server, ctx.optString("dimension", null)); BlockPos p=BlockPos.containing(ctx.getDouble("x"),ctx.getDouble("y"),ctx.getDouble("z")); if (!level.hasChunkAt(p)) throw RpcException.notFound("Chunk not loaded"); JsonObject result=CreateAdapterProvider.network().summary(level.getBlockEntity(p)); result.addProperty("gameTime",level.getGameTime()); return result; }));
		router.register("world.networkJobStart", ctx -> onServer(server -> { ServerLevel level=Levels.resolve(server,ctx.optString("dimension",null)); return CreateNetworkJobs.start(level,BlockPos.containing(ctx.getDouble("x"),ctx.getDouble("y"),ctx.getDouble("z")),ctx.optInt("target",CreateNetworkJobs.DEFAULT_TARGET)); }));
		router.register("world.networkJobStatus", ctx -> onServer(server -> CreateNetworkJobs.status(ctx.getString("jobId"))));
		router.register("world.networkJobCancel", ctx -> onServer(server -> CreateNetworkJobs.cancel(ctx.getString("jobId"))));
		router.register("world.networkJobPage", ctx -> onServer(server -> CreateNetworkJobs.page(ctx.getString("jobId"),ctx.optInt("offset",0),ctx.optInt("limit",32))));
		router.register("world.inspectCreateNetwork", ctx -> onServer(server -> {
			if (ctx.has("snapshotId")) {
				String id = ctx.getString("snapshotId");
				if (id.length() > 64) throw RpcException.badRequest("Invalid snapshotId.");
				return CreateAdapterProvider.network().page(id, ctx.optInt("offset", 0), ctx.optInt("limit", 32));
			}
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			int offset = ctx.optInt("offset", 0), limit = ctx.optInt("limit", 32);
			if (offset < 0 || offset >= CreateNetworkAdapter.MAX_VISITS || limit < 1 || limit > CreateNetworkAdapter.MAX_PAGE_SIZE)
				throw RpcException.badRequest("offset must be 0..4095 and limit 1..64.");
			if (!level.hasChunkAt(pos)) throw RpcException.notFound("Chunk not loaded at " + pos.toShortString());
			JsonObject result = CreateAdapterProvider.network().inspect(level.getBlockEntity(pos), offset, limit);
			result.addProperty("x", pos.getX()); result.addProperty("y", pos.getY()); result.addProperty("z", pos.getZ());
			result.addProperty("dimension", Levels.dimensionId(level));
			result.addProperty("gameTime", level.getGameTime());
			return result;
		}));
		router.register("world.inspectCreateKinetic", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			if (!level.hasChunkAt(pos)) throw RpcException.notFound("Chunk not loaded at " + pos.toShortString());
			JsonObject result = CreateAdapterProvider.kinetic().inspect(level.getBlockEntity(pos));
			if ("ok".equals(result.get("status").getAsString()) && result.get("hasNetwork").getAsBoolean()) {
				JsonObject network = CreateAdapterProvider.network().inspect(level.getBlockEntity(pos), 0, 1);
				for (String key : List.of("anchorStressSU", "anchorCapacitySU", "anchorContributionSource", "totalsStatus"))
					if (network.has(key)) result.add(key, network.get(key));
			}
			var blockEntity = level.getBlockEntity(pos);
			if (blockEntity != null) result.addProperty("blockEntityType", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString());
			result.addProperty("x", pos.getX()); result.addProperty("y", pos.getY()); result.addProperty("z", pos.getZ());
			result.addProperty("blockId", Levels.blockId(level.getBlockState(pos)));
			result.addProperty("dimension", Levels.dimensionId(level));
			result.addProperty("gameTime", level.getGameTime());
			return result;
		}));
		router.register("world.inspectBlockEntity", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			if (!level.hasChunkAt(pos)) throw RpcException.notFound("Chunk not loaded at " + pos.toShortString());
			var state = level.getBlockState(pos);
			var entity = level.getBlockEntity(pos);
			JsonObject result = Levels.describeBlock(level, pos, state);
			result.addProperty("dimension", Levels.dimensionId(level));
			result.addProperty("gameTime", level.getGameTime());
			if (entity == null) {
				result.addProperty("status", "no_block_entity");
				return result;
			}
			result.addProperty("status", "available");
			result.addProperty("blockEntityType", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString());
			JsonObject persistent = new JsonObject();
			if (ctx.optBool("includePersistent", false)) {
				persistent.addProperty("status", "unavailable");
				persistent.addProperty("reason", "Generic serialization has no enforceable allocation/time bound; use capabilities or a bounded adapter.");
			} else persistent.addProperty("status", "not_requested");
			result.add("persistentData", persistent);
			result.add("capabilities", McpFabric.platform().inspectBlockCapabilities(level, pos));
			JsonObject runtime = new JsonObject();
			runtime.addProperty("status", "unsupported_without_adapter");
			result.add("runtimeData", runtime);
			return result;
		}));
		router.register("world.runtimeCatalog", ctx -> {
			Gates.registryRead();
			return onServerRegistry(server -> RuntimeCatalog.read(server, ctx));
		});
		router.register("world.listRegisteredBlocks", ctx -> {
			Gates.registryRead();
			return onServerRegistry(server -> {
				String mod = ctx.optString("modId", "").toLowerCase(java.util.Locale.ROOT);
				String search = ctx.optString("search", "").toLowerCase(java.util.Locale.ROOT);
				int offset = ctx.optInt("offset", 0), limit = ctx.optInt("limit", 100);
				if (offset < 0 || limit < 1 || limit > 200 || mod.length() > 128 || search.length() > 128)
					throw RpcException.badRequest("Invalid offset, limit (1..200), or filter length.");
				JsonArray blocks = new JsonArray();
				int matched = 0;
				for (var block : BuiltInRegistries.BLOCK) {
					var id = BuiltInRegistries.BLOCK.getKey(block);
					String name = block.getName().getString();
					if (!mod.isEmpty() && !id.getNamespace().equals(mod)) continue;
					if (!search.isEmpty() && !id.toString().toLowerCase(java.util.Locale.ROOT).contains(search)
							&& !name.toLowerCase(java.util.Locale.ROOT).contains(search)) continue;
					if (matched++ < offset || blocks.size() >= limit) continue;
					JsonObject item = new JsonObject();
					item.addProperty("id", id.toString());
					item.addProperty("name", name);
					JsonObject properties = new JsonObject();
					for (Property<?> property : block.getStateDefinition().getProperties()) {
						JsonArray values = new JsonArray();
						addPropertyValues(property, values);
						properties.add(property.getName(), values);
					}
					item.add("properties", properties);
					blocks.add(item);
				}
				JsonObject result = new JsonObject();
				result.addProperty("offset", offset);
				result.addProperty("total", matched);
				result.addProperty("hasMore", (long) offset + blocks.size() < matched);
				result.add("blocks", blocks);
				return result;
			});
		});
		router.register("world.inspectRecipe", ctx -> {
			Gates.registryRead();
			return onServerRegistry(server -> {
				String id = ctx.getString("recipeId"), input = ctx.optString("inputItem", null);
				ResourceLocation key = ResourceLocation.tryParse(id);
				if (key == null || id.length() > 256) throw RpcException.badRequest("Invalid recipeId.");
				var holder = server.getRecipeManager().byKey(key).orElseThrow(() -> RpcException.notFound("Recipe not loaded."));
				var recipe = holder.value(); JsonObject out = new JsonObject();
				out.addProperty("recipeId", key.toString()); out.addProperty("type", BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());
				out.addProperty("source", "active_server_recipe_manager"); out.addProperty("gameTime", server.overworld().getGameTime());
				ItemStack result = recipe.getResultItem(server.registryAccess());
				if (!result.isEmpty()) { JsonObject item = new JsonObject(); item.addProperty("id", BuiltInRegistries.ITEM.getKey(result.getItem()).toString()); item.addProperty("count", result.getCount()); out.add("displayResult", item); }
				else out.addProperty("displayResultStatus", "unavailable_or_dynamic");
				var ingredients = recipe.getIngredients(); out.addProperty("ingredientCount", ingredients.size());
				if (input != null) {
					ResourceLocation inputKey = ResourceLocation.tryParse(input);
					if (inputKey == null || input.length() > 256 || !BuiltInRegistries.ITEM.containsKey(inputKey)) throw RpcException.badRequest("Unknown inputItem.");
					if (ingredients.size() > 16) out.addProperty("inputMatchStatus", "too_many_ingredients");
					else { ItemStack candidate = new ItemStack(BuiltInRegistries.ITEM.get(inputKey)); JsonArray matches = new JsonArray();
						for (var ingredient : ingredients) matches.add(ingredient.test(candidate));
						out.add("inputMatchesIngredient", matches); }
				}
				out.addProperty("note", "Display result and per-ingredient candidate matches do not prove a complete recipe match or account for dynamic outputs.");
				return out;
			});
		});
		router.register("world.getBlock", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			if (!level.hasChunkAt(pos)) throw RpcException.notFound("Chunk not loaded at " + pos.toShortString());
			BlockState state = level.getBlockState(pos);
			JsonObject o = Levels.describeBlock(level, pos, state);
			o.addProperty("dimension", Levels.dimensionId(level));
			o.addProperty("gameTime", level.getGameTime());
			var blockEntity = level.getBlockEntity(pos);
			if (blockEntity != null) {
				JsonObject entity = new JsonObject();
				entity.addProperty("type", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString());
				entity.addProperty("source", "block_entity_type_registry");
				entity.addProperty("persistentData", "not collected");
				entity.addProperty("runtimeData", "not collected");
				o.add("blockEntity", entity);
			}
			o.addProperty("blockLight", level.getBrightness(LightLayer.BLOCK, pos));
			o.addProperty("skyLight", level.getBrightness(LightLayer.SKY, pos));
			o.addProperty("hardness", state.getDestroySpeed(level, pos));
			return o;
		}));

		router.register("world.getBlocks", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			BlockPos from = blockPos(ctx.getVec3("from"));
			BlockPos to = blockPos(ctx.getVec3("to"));
			int minX = Math.min(from.getX(), to.getX()), minY = Math.min(from.getY(), to.getY()), minZ = Math.min(from.getZ(), to.getZ());
			int maxX = Math.max(from.getX(), to.getX()), maxY = Math.max(from.getY(), to.getY()), maxZ = Math.max(from.getZ(), to.getZ());
			boolean includeAir = ctx.optBool("includeAir", false);
			long dx = (long) maxX - minX + 1, dy = (long) maxY - minY + 1, dz = (long) maxZ - minZ + 1;
			long volume;
			try { volume = RegionCursor.volume(dx, dy, dz, MAX_REGION_VOLUME); }
			catch (IllegalArgumentException e) { throw RpcException.badRequest("Region exceeds the maximum volume."); }
			int cap = ctx.optInt("maxBlocks", DEFAULT_REGION_CAP);
			if (cap < 1 || cap > 4096) throw RpcException.badRequest("maxBlocks must be between 1 and 4096.");
			long offset = ctx.optLong("offset", 0);
			if (!RegionCursor.validOffset(offset, volume)) throw RpcException.badRequest("offset must be within the region volume.");

			JsonArray blocks = new JsonArray();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			int scanned = 0;
			int unloaded = 0;
			long cursor = offset;
			while (cursor < volume && scanned < 4096 && blocks.size() < cap) {
				long index = cursor++;
				long[] relative = RegionCursor.position(index, dx, dz);
				int x = (int) (minX + relative[0]);
				int y = (int) (minY + relative[1]);
				int z = (int) (minZ + relative[2]);
				scanned++;
				m.set(x, y, z);
				if (!level.hasChunkAt(m)) { unloaded++; continue; }
				BlockState state = level.getBlockState(m);
				if (!includeAir && state.isAir()) continue;
				JsonObject b = new JsonObject();
				b.addProperty("x", x);
				b.addProperty("y", y);
				b.addProperty("z", z);
				b.addProperty("id", Levels.blockId(state));
				blocks.add(b);
			}
			JsonObject o = new JsonObject();
			o.addProperty("dimension", Levels.dimensionId(level));
			o.addProperty("volume", volume);
			o.addProperty("scanned", scanned);
			o.addProperty("unloaded", unloaded);
			o.addProperty("offset", offset);
			o.addProperty("nextOffset", cursor);
			o.addProperty("gameTime", level.getGameTime());
			o.addProperty("count", blocks.size());
			o.addProperty("truncated", cursor < volume);
			o.add("blocks", blocks);
			return o;
		}));

		router.register("world.findBlocks", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			BlockPos center = blockPos(ctx.getVec3("center"));
			int cx = center.getX(), cy = center.getY(), cz = center.getZ();
			int radius = ctx.getInt("radius");
			int maxResults = ctx.optInt("maxResults", 64);
			Set<String> wanted = new HashSet<>(ctx.getStringList("blockIds"));
			if (wanted.isEmpty()) throw RpcException.badRequest("blockIds must not be empty.");

			List<JsonObject> found = new ArrayList<>();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			long scanned = 0;
			boolean truncated = false;
			int r2 = radius * radius;
			int searchedRadius = -1;
			// Cube shells from the center outwards (shell s = positions whose largest offset is s), so the
			// scan budget is spent on the positions nearest to the center first.
			outer:
			for (int s = 0; s <= radius; s++) {
				for (int dx = -s; dx <= s; dx++) {
					for (int dy = -s; dy <= s; dy++) {
						// Inside the shell's x/y extent, only its two z faces belong to the shell.
						int dzStep = Math.abs(dx) == s || Math.abs(dy) == s ? 1 : 2 * s;
						for (int dz = -s; dz <= s; dz += dzStep) {
							if (++scanned > SCAN_BUDGET) { truncated = true; break outer; }
							int dist2 = dx * dx + dy * dy + dz * dz;
							if (dist2 > r2) continue;
							m.set(cx + dx, cy + dy, cz + dz);
							if (!level.hasChunkAt(m)) continue;
							BlockState state = level.getBlockState(m);
							String id = Levels.blockId(state);
							if (!wanted.contains(id)) continue;
							JsonObject b = new JsonObject();
							b.addProperty("x", m.getX());
							b.addProperty("y", m.getY());
							b.addProperty("z", m.getZ());
							b.addProperty("id", id);
							b.addProperty("distance", Math.sqrt(dist2));
							found.add(b);
						}
					}
				}
				searchedRadius = s;
			}
			found.sort((a, b) -> Double.compare(a.get("distance").getAsDouble(), b.get("distance").getAsDouble()));
			JsonArray matches = new JsonArray();
			for (int i = 0; i < Math.min(maxResults, found.size()); i++) matches.add(found.get(i));

			JsonObject o = new JsonObject();
			o.addProperty("dimension", Levels.dimensionId(level));
			o.addProperty("totalFound", found.size());
			o.addProperty("returned", matches.size());
			o.addProperty("truncated", truncated);
			// Every position within this distance of the center was searched (the whole radius unless truncated).
			o.addProperty("searchedRadius", searchedRadius);
			o.add("matches", matches);
			return o;
		}));

		router.register("world.getTimeAndWeather", ctx -> onServer(server -> {
			ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
			long dayTime = Levels.dayTime(level) % 24000L;
			if (dayTime < 0) dayTime += 24000L;
			JsonObject o = new JsonObject();
			o.addProperty("dimension", Levels.dimensionId(level));
			o.addProperty("dayTime", dayTime);
			o.addProperty("gameTime", level.getGameTime());
			o.addProperty("day", Levels.dayTime(level) / 24000L);
			o.addProperty("raining", level.isRaining());
			o.addProperty("thundering", level.isThundering());
			return o;
		}));

		router.register("world.getDimensions", ctx -> onServer(server -> {
			JsonArray dims = new JsonArray();
			for (ServerLevel level : server.getAllLevels()) {
				dims.add(Levels.dimensionId(level));
			}
			JsonObject o = new JsonObject();
			o.add("dimensions", dims);
			// The dimension each online player is in; playerDimension when there is exactly one (single player).
			JsonArray players = new JsonArray();
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				JsonObject pl = new JsonObject();
				pl.addProperty("name", p.getName().getString());
				pl.addProperty("dimension", Levels.dimensionId(p.level()));
				players.add(pl);
			}
			o.add("players", players);
			if (players.size() == 1) o.addProperty("playerDimension", players.get(0).getAsJsonObject().get("dimension").getAsString());
			return o;
		}));

		router.register("world.raycast", ctx -> onServer(server -> raycast(server, ctx)));

		// --- writes ----------------------------------------------------------------------------

		router.register("world.setBlock", ctx -> writeCommand(ctx, ctx2 -> {
			MinecraftServer server = ServerHolder.get();
			ServerLevel level = Levels.resolve(server, ctx2.optString("dimension", null));
			int x = (int) Math.floor(ctx2.getDouble("x"));
			int y = (int) Math.floor(ctx2.getDouble("y"));
			int z = (int) Math.floor(ctx2.getDouble("z"));
			String block = ctx2.getString("blockId");
			Gates.dataTags(block);
			return CommandRunner.run(server, level, "setblock " + x + " " + y + " " + z + " " + block).toJson();
		}));

		router.register("world.fill", ctx -> writeCommand(ctx, ctx2 -> {
			MinecraftServer server = ServerHolder.get();
			ServerLevel level = Levels.resolve(server, ctx2.optString("dimension", null));
			BlockPos from = blockPos(ctx2.getVec3("from"));
			BlockPos to = blockPos(ctx2.getVec3("to"));
			String block = ctx2.getString("blockId");
			Gates.dataTags(block);
			String cmd = String.format("fill %d %d %d %d %d %d %s",
					from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), block);
			return CommandRunner.run(server, level, cmd).toJson();
		}));

		router.register("world.setTime", ctx -> writeCommand(ctx, ctx2 -> {
			MinecraftServer server = ServerHolder.get();
			return CommandRunner.run(server, "time set " + ctx2.getInt("time")).toJson();
		}));

		router.register("world.setWeather", ctx -> writeCommand(ctx, ctx2 -> {
			MinecraftServer server = ServerHolder.get();
			String weather = ctx2.getString("weather");
			if (!weather.equals("clear") && !weather.equals("rain") && !weather.equals("thunder")) {
				throw RpcException.badRequest("weather must be clear|rain|thunder.");
			}
			// The vanilla /weather command parses the duration via TimeArgument, where a bare number is
			// TICKS. Append "s" so durationSeconds is interpreted as seconds, matching the tool schema.
			String cmd = "weather " + weather + (ctx2.has("durationSeconds") ? " " + ctx2.getInt("durationSeconds") + "s" : "");
			return CommandRunner.run(server, cmd).toJson();
		}));
	}

	// --- raycast ---------------------------------------------------------------------------------

	private static JsonElement raycast(MinecraftServer server, RpcContext ctx) throws RpcException {
		ServerLevel level = Levels.resolve(server, ctx.optString("dimension", null));
		double[] origin = ctx.getVec3("origin");
		Vec3 start = new Vec3(origin[0], origin[1], origin[2]);

		Vec3 dir;
		double[] direction = ctx.optVec3("direction");
		if (direction != null) {
			dir = new Vec3(direction[0], direction[1], direction[2]);
		} else if (ctx.has("yaw") && ctx.has("pitch")) {
			double yaw = Math.toRadians(ctx.getDouble("yaw"));
			double pitch = Math.toRadians(ctx.getDouble("pitch"));
			dir = new Vec3(-Math.cos(pitch) * Math.sin(yaw), -Math.sin(pitch), Math.cos(pitch) * Math.cos(yaw));
		} else {
			throw RpcException.badRequest("Provide either 'direction' or both 'yaw' and 'pitch'.");
		}
		if (dir.lengthSqr() < 1.0e-6) throw RpcException.badRequest("Direction has zero length.");
		dir = dir.normalize();

		double maxDistance = ctx.optDouble("maxDistance", 32.0);
		boolean includeFluids = ctx.optBool("includeFluids", false);
		boolean includeEntities = ctx.optBool("includeEntities", true);

		Vec3 end = start.add(dir.scale(maxDistance));

		// Blocks: outline shapes, as the crosshair sees them (a ray above a bottom slab passes).
		double blockDist = -1;
		BlockPos hitBlock = null;
		BlockState hitState = null;
		Vec3 blockHitPos = null;
		BlockHitResult blockHit = clipLoaded(level, start, end, includeFluids);
		if (blockHit.getType() == HitResult.Type.BLOCK) {
			hitBlock = blockHit.getBlockPos();
			hitState = level.getBlockState(hitBlock);
			blockHitPos = blockHit.getLocation();
			blockDist = blockHitPos.distanceTo(start);
		}

		// Entities in front of the block hit, with the vanilla pick box (bounding box + pick radius).
		double entDist = -1;
		Entity hitEntity = null;
		Vec3 entityHitPos = null;
		if (includeEntities) {
			Vec3 limit = blockHitPos != null ? blockHitPos : end;
			AABB box = new AABB(start, limit).inflate(1.0);
			for (Entity e : level.getEntities((Entity) null, box, ent -> ent.isAlive() && ent.isPickable())) {
				var clip = e.getBoundingBox().inflate(e.getPickRadius()).clip(start, limit);
				if (clip.isPresent()) {
					double d = clip.get().distanceTo(start);
					if (entDist < 0 || d < entDist) {
						entDist = d;
						hitEntity = e;
						entityHitPos = clip.get();
					}
				}
			}
		}

		JsonObject o = new JsonObject();
		o.addProperty("dimension", Levels.dimensionId(level));
		boolean entityFirst = hitEntity != null && (hitBlock == null || entDist <= blockDist);
		if (entityFirst) {
			o.addProperty("hitType", "entity");
			o.addProperty("distance", entDist);
			JsonObject ej = new JsonObject();
			ej.addProperty("uuid", hitEntity.getUUID().toString());
			ej.addProperty("type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(hitEntity.getType()).toString());
			ej.addProperty("name", hitEntity.getName().getString());
			o.add("entity", ej);
			o.add("hitPos", vec(entityHitPos));
		} else if (hitBlock != null) {
			o.addProperty("hitType", "block");
			o.addProperty("distance", blockDist);
			o.add("block", Levels.describeBlock(level, hitBlock, hitState));
			o.add("hitPos", vec(blockHitPos));
			o.addProperty("face", blockHit.getDirection().getName());
		} else {
			o.addProperty("hitType", "miss");
		}
		return o;
	}

	/**
	 * Vanilla block ray trace ({@code BlockGetter.clip} with outline shapes and optional fluids) that
	 * stops at the first unloaded chunk instead of loading it on the server thread.
	 */
	private static BlockHitResult clipLoaded(ServerLevel level, Vec3 from, Vec3 to, boolean includeFluids) {
		ClipContext clip = new ClipContext(from, to, ClipContext.Block.OUTLINE,
				includeFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, CollisionContext.empty());
		return BlockGetter.traverseBlocks(from, to, clip, (c, pos) -> {
			if (!level.hasChunkAt(pos)) return BlockHitResult.miss(Vec3.atCenterOf(pos), Direction.UP, pos.immutable());
			BlockState state = level.getBlockState(pos);
			BlockHitResult blockHit = level.clipWithInteractionOverride(from, to, pos, c.getBlockShape(state, level, pos), state);
			BlockHitResult fluidHit = c.getFluidShape(level.getFluidState(pos), level, pos).clip(from, to, pos);
			double blockDist = blockHit == null ? Double.MAX_VALUE : from.distanceToSqr(blockHit.getLocation());
			double fluidDist = fluidHit == null ? Double.MAX_VALUE : from.distanceToSqr(fluidHit.getLocation());
			return blockDist <= fluidDist ? blockHit : fluidHit;
		}, c -> BlockHitResult.miss(to, Direction.UP, BlockPos.containing(to)));
	}

	/** The block containing a point, as {@code BlockPos.containing} (floor, not truncation, for negatives). */
	private static BlockPos blockPos(double[] v) {
		return BlockPos.containing(v[0], v[1], v[2]);
	}

	private static JsonObject vec(Vec3 v) {
		JsonObject o = new JsonObject();
		o.addProperty("x", v.x);
		o.addProperty("y", v.y);
		o.addProperty("z", v.z);
		return o;
	}

	// --- helpers ---------------------------------------------------------------------------------

	private interface ServerWork {
		JsonElement run(MinecraftServer server) throws RpcException;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void addPropertyValues(Property property, JsonArray values) {
		for (Object value : property.getPossibleValues()) values.add(property.getName((Comparable) value));
	}

	private static java.util.UUID structureId(RpcContext ctx) throws RpcException {
		String id = ctx.getString("structureId");
		try {
			var uuid = java.util.UUID.fromString(id);
			if (uuid.toString().equals(id)) return uuid;
		} catch (IllegalArgumentException ignored) {}
		throw RpcException.badRequest("Invalid structure UUID.");
	}

	private static JsonElement onServer(ServerWork work) throws RpcException {
		Gates.vision();
		return onServerRegistry(work);
	}

	private static JsonElement onServerRegistry(ServerWork work) throws RpcException {
		MinecraftServer server = ServerHolder.get();
		if (server == null) throw RpcException.noServer();
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
			long started = System.nanoTime();
			JsonElement result = work.run(server);
			if (result.isJsonObject()) result.getAsJsonObject().addProperty("observationMicros", (System.nanoTime() - started) / 1000);
			return result;
		});
	}

	private interface CtxWork {
		JsonElement run(RpcContext ctx) throws RpcException;
	}

	private static JsonElement writeCommand(RpcContext ctx, CtxWork work) throws RpcException {
		if (!McpFabric.config().enableWorldWrite) {
			throw RpcException.unavailable("World writes are disabled in mcpfabric.config.json (enableWorldWrite=false).");
		}
		MinecraftServer server = ServerHolder.get();
		if (server == null) throw RpcException.noServer();
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> work.run(ctx));
	}
}
