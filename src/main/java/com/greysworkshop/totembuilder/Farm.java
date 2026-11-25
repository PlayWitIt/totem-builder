package com.greysworkshop.totembuilder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where the farm is: the villager's bed head, per server and dimension, kept in config/totembuilder.json. */
public final class Farm {
	public final BlockPos bed;
	public final FarmPlan plan;
	/** You said the villager is in, or it was seen there: the entrance is filled. */
	public boolean sealed;

	public Farm(BlockPos bed) {
		this.bed = bed;
		this.plan = new FarmPlan("main");
	}

	private static Path file = FabricLoader.getInstance().getConfigDir().resolve("totembuilder.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	static void useFile(Path p) {
		file = p;
	}

	/** This server and dimension, as the key the farm is saved under. */
	static String key(MinecraftClient client) {
		ServerInfo info = client.getCurrentServerEntry();
		String server = info != null ? info.address : client.getServer() != null ? "local:" + client.getServer().getSaveProperties().getLevelName() : "unknown";
		String dim = client.world != null ? client.world.getRegistryKey().getValue().toString() : "?";
		return server + "|" + dim;
	}

	static Farm load(MinecraftClient client) {
		try {
			if (!Files.exists(file)) return null;
			JsonObject all = GSON.fromJson(Files.readString(file), JsonObject.class);
			JsonObject o = all == null ? null : all.getAsJsonObject(key(client));
			if (o == null) return null;
			Farm f = new Farm(new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
			f.sealed = o.has("sealed") && o.get("sealed").getAsBoolean();
			return f;
		} catch (Exception e) {
			TotemBuilder.LOGGER.warn("Could not read {}: {}", file, e.toString());
			return null;
		}
	}

	static void save(MinecraftClient client, Farm farm) {
		try {
			JsonObject all = Files.exists(file) ? GSON.fromJson(Files.readString(file), JsonObject.class) : null;
			if (all == null) all = new JsonObject();
			String k = key(client);
			if (farm == null) all.remove(k);
			else {
				JsonObject o = new JsonObject();
				o.addProperty("x", farm.bed.getX());
				o.addProperty("y", farm.bed.getY());
				o.addProperty("z", farm.bed.getZ());
				o.addProperty("sealed", farm.sealed);
				all.add(k, o);
			}
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(all));
		} catch (IOException e) {
			TotemBuilder.LOGGER.warn("Could not save {}: {}", file, e.toString());
		}
	}
}
