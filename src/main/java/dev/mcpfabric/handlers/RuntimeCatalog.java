package dev.mcpfabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;

/** Versioned, read-only index inputs for separately maintained knowledge stores. */
final class RuntimeCatalog {
	private RuntimeCatalog() {}

	static JsonObject read(MinecraftServer server, RpcContext ctx) throws RpcException {
		JsonObject out = new JsonObject();
		out.addProperty("schemaVersion", 1);
		//? if <1.21.2 {
		String kind = ctx.getString("kind"), tagId = ctx.optString("tagId", null);
		int offset = ctx.optInt("offset", 0), limit = ctx.optInt("limit", 100);
		if (offset < 0 || offset > 65_535 || limit < 1 || limit > 100
				|| (tagId != null && (tagId.length() > 256 || !kind.endsWith("_tags"))))
			throw RpcException.badRequest("Invalid catalog page or tag filter.");
		java.util.stream.Stream<String> stream = switch (kind) {
			case "items" -> BuiltInRegistries.ITEM.keySet().stream().map(Object::toString);
			case "recipe_ids" -> server.getRecipeManager().getRecipeIds().map(Object::toString);
			case "item_tags" -> tags(BuiltInRegistries.ITEM, tagId);
			case "block_tags" -> tags(BuiltInRegistries.BLOCK, tagId);
			case "data_packs" -> server.getPackRepository().getSelectedIds().stream();
			default -> throw RpcException.badRequest("Unknown catalog kind.");
		};
		var page = stream.skip(offset).limit(limit + 1L).toList();
		var rows = new JsonArray();
		page.stream().limit(limit).forEach(rows::add);
		out.addProperty("status", "ok");
		out.addProperty("kind", kind);
		out.addProperty("offset", offset);
		out.addProperty("sampleGameTime", server.overworld().getGameTime());
		out.addProperty("consistency", "live_pages_invalidate_after_reload");
		if (tagId != null) out.addProperty("tagId", tagId);
		out.add("ids", rows);
		if (page.size() > limit && offset + limit <= 65_535) out.addProperty("nextOffset", offset + limit);
		out.addProperty("truncatedAtOffsetLimit", page.size() > limit && offset + limit > 65_535);
		//?} else
		/*out.addProperty("status", "unsupported_version");*/
		return out;
	}

	//? if <1.21.2 {
	private static <T> java.util.stream.Stream<String> tags(Registry<T> registry, String id) throws RpcException {
		if (id == null) return registry.getTagNames().map(tag -> tag.location().toString());
		var key = net.minecraft.resources.ResourceLocation.tryParse(id);
		if (key == null) throw RpcException.badRequest("Invalid tag ID.");
		var values = registry.getTag(net.minecraft.tags.TagKey.create(registry.key(), key))
				.orElseThrow(() -> RpcException.notFound("Tag not loaded."));
		return values.stream().map(holder -> registry.getKey(holder.value()).toString());
	}
	//?}
}
