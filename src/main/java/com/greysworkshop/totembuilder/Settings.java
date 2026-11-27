package com.greysworkshop.totembuilder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

/** The choices on the screen, kept in config/totembuilder-settings.json. */
final class Settings {
	private Settings() {}

	/** How much of the farm is drawn in the world. */
	enum Ghosts {
		ALL("Ghosts: everything left"), PLACEABLE("Ghosts: what goes on next"), OFF("Ghosts: off");

		final String label;

		Ghosts(String label) {
			this.label = label;
		}

		Ghosts next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	/** Blocks that make good scaffolding: cheap, full, and quick to break with a pickaxe or by hand. */
	static final Item[] SCAFFOLD_CHOICES = {Items.COBBLESTONE, Items.NETHERRACK, Items.DIRT, Items.COBBLED_DEEPSLATE, Items.STONE,
			Items.BLACKSTONE, Items.END_STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.OAK_PLANKS};

	static Ghosts ghosts = Ghosts.ALL;
	static Item scaffold = Items.COBBLESTONE;
	/** How far round you the ghost blocks are drawn. */
	static int ghostRange = 40;

	private static Path file = FabricLoader.getInstance().getConfigDir().resolve("totembuilder-settings.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	static void useFile(Path p) {
		file = p;
	}

	static void load() {
		try {
			if (!Files.exists(file)) return;
			JsonObject o = GSON.fromJson(Files.readString(file), JsonObject.class);
			if (o == null) return;
			if (o.has("ghosts")) {
				try {
					ghosts = Ghosts.valueOf(o.get("ghosts").getAsString());
				} catch (IllegalArgumentException ignored) {
				}
			}
			if (o.has("scaffold")) {
				Item i = Registries.ITEM.get(Identifier.tryParse(o.get("scaffold").getAsString()));
				if (usable(i)) scaffold = i;
			}
			if (o.has("ghostRange")) ghostRange = Math.max(8, Math.min(128, o.get("ghostRange").getAsInt()));
		} catch (Exception e) {
			TotemBuilder.LOGGER.warn("Could not read {}: {}", file, e.toString());
		}
	}

	static void save() {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("ghosts", ghosts.name());
			o.addProperty("scaffold", Registries.ITEM.getId(scaffold).toString());
			o.addProperty("ghostRange", ghostRange);
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(o));
		} catch (Exception e) {
			TotemBuilder.LOGGER.warn("Could not save {}: {}", file, e.toString());
		}
	}

	/** A block that stands on its own as a full cube, so it can be built on and pillared up. */
	static boolean usable(Item item) {
		if (!(item instanceof BlockItem b)) return false;
		var state = b.getBlock().getDefaultState();
		return state.isOpaqueFullCube() && !b.getBlock().getDefaultState().hasBlockEntity()
				&& !(b.getBlock() instanceof net.minecraft.block.FallingBlock);
	}

	static void nextScaffold() {
		int i = 0;
		for (; i < SCAFFOLD_CHOICES.length; i++) if (SCAFFOLD_CHOICES[i] == scaffold) break;
		scaffold = SCAFFOLD_CHOICES[(i + 1) % SCAFFOLD_CHOICES.length];
		save();
	}

	/** Uses the block you're holding, if it makes good scaffolding. */
	static boolean useHeld(ItemStack held) {
		if (held.isEmpty() || !usable(held.getItem())) return false;
		scaffold = held.getItem();
		save();
		return true;
	}

	static String scaffoldName() {
		return new ItemStack(scaffold).getName().getString();
	}
}
