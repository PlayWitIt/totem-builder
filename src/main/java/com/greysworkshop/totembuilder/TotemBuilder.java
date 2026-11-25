package com.greysworkshop.totembuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.TreeMap;

public final class TotemBuilder implements ClientModInitializer {
	public static final String ID = "totembuilder";
	public static final Logger LOGGER = LoggerFactory.getLogger("Totem Builder");

	static Farm farm;
	static SiteCheck check;
	static final Printer printer = new Printer();
	static boolean printing;
	static int perTick = 2;
	private static String loadedFor;
	private static boolean villagerSeen;
	static KeyBinding printKey, planKey;

	@Override
	public void onInitializeClient() {
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(ID, "main"));
		printKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.totembuilder.print", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_8, category));
		planKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.totembuilder.plan", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_KP_9, category));
		ClientTickEvents.END_CLIENT_TICK.register(TotemBuilder::tick);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(Render::render);
		HudElementRegistry.addLast(Identifier.of(ID, "hud"), Hud::render);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> Commands.register(dispatcher));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			printing = false;
			farm = null;
			check = null;
			loadedFor = null;
		}));
		for (String test : new String[]{"raidtest", "selftest"}) {
			if (!Boolean.getBoolean("totembuilder." + test)) continue;
			String cls = test.equals("raidtest") ? "RaidTest" : "SelfTest";
			try {
				Class.forName("com.greysworkshop.totembuilder." + cls).getDeclaredMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Test missing", e);
			}
		}
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null) return;
		String key = Farm.key(client);
		if (!key.equals(loadedFor)) {
			loadedFor = key;
			farm = Farm.load(client);
			check = null;
			villagerSeen = false;
		}
		while (printKey.wasPressed()) togglePrinting(client);
		while (planKey.wasPressed()) {
			if (farm == null) planHere(client);
			else Commands.status(client);
		}
		if (check != null && !check.finished) {
			check.step(client.world, 6);
			if (check.finished) reportCheck(client);
		}
		Render.tick(client);
		if (farm == null) return;
		if (!farm.sealed && Printer.villagerInside(client.world, farm)) {
			if (!villagerSeen) {
				villagerSeen = true;
				say(client, Text.literal("Villager is in its room. Wait for green sparkles over it (it has claimed the bed), then the printer fills the entrance.").formatted(Formatting.GREEN));
			}
		}
		if (printing) printer.tick(client, farm, perTick);
		if (!farm.sealed && villagerSeen && Printer.villagerInside(client.world, farm) && entranceFilled(client)) {
			farm.sealed = true;
			Farm.save(client, farm);
			say(client, Text.literal("Room sealed. The farm is ready: stand in the booth and drink an ominous bottle.").formatted(Formatting.GREEN));
		}
	}

	private static boolean entranceFilled(MinecraftClient client) {
		for (BlockPos e : FarmPlan.ENTRANCE) if (client.world.getBlockState(farm.bed.add(e)).isAir()) return false;
		return true;
	}

	static void togglePrinting(MinecraftClient client) {
		if (farm == null) {
			say(client, Text.literal("No farm planned yet. Fly to your spot over the ocean and press Numpad 9 (or /totem here).").formatted(Formatting.YELLOW));
			return;
		}
		printing = !printing;
		say(client, Text.literal(printing ? "Printer on: fly round the farm and it places every block within reach." : "Printer off.")
				.formatted(printing ? Formatting.GREEN : Formatting.GRAY));
	}

	/** Plans the farm with the villager's bed right where you are, at the height that puts the floor on the sea. */
	static void planHere(MinecraftClient client) {
		int sea = client.world.getSeaLevel();
		BlockPos bed = new BlockPos(client.player.getBlockX(), sea - FarmPlan.FLOOR_Y, client.player.getBlockZ());
		farm = new Farm(bed);
		Farm.save(client, farm);
		villagerSeen = false;
		say(client, Text.literal("Farm planned. The villager's bed is at " + bed.toShortString() + "; the floor sits on the sea at y " + sea
				+ ", the spawn pad at y " + (bed.getY() + FarmPlan.PAD_Y) + ". Checking for land in range...").formatted(Formatting.AQUA));
		if (client.world.getDifficulty() != Difficulty.HARD) {
			say(client, Text.literal("This world is on " + client.world.getDifficulty().getName() + ". Raids only bring evokers on Hard (8 totems a raid) or Normal (2).")
					.formatted(Formatting.YELLOW));
		}
		check = new SiteCheck(bed, farm.plan);
	}

	/** Plans the farm with its bed exactly here (the tests use it to build high over a flat world). */
	static void planAt(MinecraftClient client, BlockPos bed) {
		farm = new Farm(bed);
		Farm.save(client, farm);
		villagerSeen = false;
		check = new SiteCheck(bed, farm.plan);
	}

	static void runCheck(MinecraftClient client) {
		if (farm == null) return;
		check = new SiteCheck(farm.bed, farm.plan);
		say(client, Text.literal("Checking for land in range...").formatted(Formatting.GRAY));
	}

	private static void reportCheck(MinecraftClient client) {
		SiteCheck c = check;
		if (!c.bad.isEmpty()) {
			BlockPos n = c.nearest();
			int dist = (int) Math.sqrt(n.getSquaredDistance(farm.bed.getX(), n.getY(), farm.bed.getZ()));
			say(client, Text.literal("Not a good spot: " + c.bad.size() + (c.bad.size() >= 200 ? "+" : "") + " places within "
					+ FarmPlan.SPAWN_SEARCH_RADIUS + " blocks raiders could spawn on instead of the pad. Nearest: "
					+ client.world.getBlockState(n).getBlock().getName().getString() + " at " + n.toShortString() + ", " + dist
					+ " blocks out. Move further out to sea, or cover them with slabs or glass. Red lines show them.").formatted(Formatting.RED));
		} else if (c.unloaded > 0) {
			say(client, Text.literal("No land in what's loaded, but " + c.unloaded + " columns of the " + FarmPlan.SPAWN_SEARCH_RADIUS
					+ "-block circle aren't loaded yet (render distance). Fly round the spot and run /totem check again.").formatted(Formatting.YELLOW));
		} else {
			say(client, Text.literal("Good spot: nothing raiders could spawn on within " + FarmPlan.SPAWN_SEARCH_RADIUS + " blocks.").formatted(Formatting.GREEN));
		}
	}

	/** What's still to place, by item. */
	static Map<String, Integer> missing(MinecraftClient client) {
		Map<String, Integer> out = new TreeMap<>();
		if (farm == null) return out;
		for (Map.Entry<BlockPos, BlockState> e : Printer.targets(farm, true).entrySet()) {
			BlockState have = client.world.getBlockState(e.getKey());
			if (Printer.need(e.getValue(), have) == Printer.Need.NOTHING) continue;
			if (e.getValue().getBlock() instanceof net.minecraft.block.TrapdoorBlock && have.isOf(e.getValue().getBlock())) continue;
			if (e.getValue().getBlock() instanceof net.minecraft.block.BedBlock && e.getValue().get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD) continue;
			out.merge(Printer.itemName(e.getValue()), 1, Integer::sum);
		}
		return out;
	}

	static void say(MinecraftClient client, Text text) {
		if (client.player != null) client.player.sendMessage(Text.literal("[Totem] ").formatted(Formatting.GOLD).append(text), false);
	}
}
