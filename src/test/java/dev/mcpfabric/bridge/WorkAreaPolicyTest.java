package dev.mcpfabric.bridge;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkAreaPolicyTest {
	@TempDir Path root;

	@Test
	void changesAndRestoresAccessWithoutRestart() throws Exception {
		var policy = new WorkAreaPolicy();
		Path file = root.resolve("work.json"), world = Files.createDirectory(root.resolve("world"));
		var json = JsonParser.parseString("""
				{"enabled":true,"areas":[{"id":"work","dimension":"minecraft:overworld",
				"min":{"x":0,"y":0,"z":0},"max":{"x":1,"y":1,"z":1}}]}
				""").getAsJsonObject();
		json.addProperty("worldPath", world.toString());
		Files.writeString(file, json.toString());
		var stamp = Files.getLastModifiedTime(file);
		var first = policy.require(file, world, "work", "minecraft:overworld");
		assertSame(first, policy.require(file, world, "work", "minecraft:overworld"));
		assertThrows(RpcException.class, () -> policy.require(file, root, "work", "minecraft:overworld"));

		var area = json.getAsJsonArray("areas").get(0).getAsJsonObject();
		area.addProperty("id", "away");
		Files.writeString(file, json.toString());
		Files.setLastModifiedTime(file, stamp);
		assertThrows(RpcException.class, () -> policy.require(file, world, "work", "minecraft:overworld"));
		assertNotEquals(first.fingerprint(), policy.require(file, world, "away", "minecraft:overworld").fingerprint());

		Files.writeString(file, "{");
		assertThrows(RpcException.class, () -> policy.require(file, world, "away", "minecraft:overworld"));
		area.addProperty("id", "work");
		Files.writeString(file, json.toString());
		assertEquals(first.fingerprint(), policy.require(file, world, "work", "minecraft:overworld").fingerprint());
	}
}
