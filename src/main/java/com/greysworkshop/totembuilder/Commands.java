package com.greysworkshop.totembuilder;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /totem: everything by command too. All client-side. */
final class Commands {
	private Commands() {}

	static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("totem")
				// Just /totem: the screen with every button. Client commands never go to the server or its chat.
				.executes(c -> run(client -> client.setScreen(new TotemScreen(null))))
				.then(literal("help").executes(c -> run(Commands::help)))
				.then(literal("here").executes(c -> run(TotemBuilder::planHere)))
				.then(literal("check").executes(c -> run(TotemBuilder::runCheck)))
				.then(literal("print").executes(c -> run(TotemBuilder::togglePrinting)))
				.then(literal("status").executes(c -> run(Commands::status)))
				.then(literal("materials").executes(c -> run(Commands::materials)))
				.then(literal("villager").executes(c -> run(Commands::villager)))
				.then(literal("speed").then(argument("blocks", IntegerArgumentType.integer(1, 8)).executes(c -> {
					TotemBuilder.perTick = IntegerArgumentType.getInteger(c, "blocks");
					return run(client -> TotemBuilder.say(client, Text.literal("Up to " + TotemBuilder.perTick + " blocks a tick.")));
				})))
				.then(literal("forget").executes(c -> run(client -> {
					TotemBuilder.farm = null;
					TotemBuilder.printing = false;
					Farm.save(client, null);
					TotemBuilder.say(client, Text.literal("Forgot the farm here. Nothing in the world was changed."));
				}))));
	}

	private static int run(java.util.function.Consumer<MinecraftClient> job) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.send(() -> job.accept(client));
		return 1;
	}

	static void help(MinecraftClient client) {
		TotemBuilder.say(client, Text.literal("/totem here: plan the farm where you are (villager's bed under you, floor on the sea) · "
				+ "/totem check: look for land in range · /totem print: printer on/off (or the Mod Menu screen) · /totem materials · "
				+ "/totem villager: where it goes · /totem status · /totem speed 1-8 · /totem forget"));
	}

	static void status(MinecraftClient client) {
		Farm farm = TotemBuilder.farm;
		if (farm == null) {
			help(client);
			return;
		}
		Map<String, Integer> m = TotemBuilder.missing(client);
		int left = m.values().stream().mapToInt(Integer::intValue).sum();
		TotemBuilder.say(client, Text.literal("Farm at bed " + farm.bed.toShortString() + ": " + (left == 0 ? "built" : left + " blocks to go")
				+ (farm.sealed ? ", villager sealed in" : "") + ". Printer " + (TotemBuilder.printing ? "on" : "off") + "."));
		if (left > 0) materials(client);
		else if (!farm.sealed) villager(client);
	}

	static void materials(MinecraftClient client) {
		Map<String, Integer> m = TotemBuilder.missing(client);
		if (m.isEmpty()) {
			TotemBuilder.say(client, Text.literal("Nothing left to place.").formatted(Formatting.GREEN));
			return;
		}
		StringBuilder b = new StringBuilder("Still to place: ");
		for (Map.Entry<String, Integer> e : m.entrySet()) {
			int have = count(client, e.getKey());
			b.append(e.getValue()).append(" ").append(e.getKey()).append(have >= e.getValue() ? "" : " (you have " + have + ")").append(", ");
		}
		TotemBuilder.say(client, Text.literal(b.substring(0, b.length() - 2)));
	}

	private static int count(MinecraftClient client, String name) {
		int n = 0;
		var inv = client.player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			var s = inv.getStack(i);
			if (s.isEmpty()) continue;
			boolean bed = name.equals("beds") && s.isIn(net.minecraft.registry.tag.ItemTags.BEDS);
			if (bed || s.getName().getString().equals(name)) n += s.getCount();
		}
		return n;
	}

	static void villager(MinecraftClient client) {
		Farm farm = TotemBuilder.farm;
		if (farm == null) return;
		BlockPos foot = farm.bed.add(FarmPlan.BED_FOOT), door = farm.bed.add(FarmPlan.ENTRANCE[0]), ledge = door.add(0, 0, -1);
		TotemBuilder.say(client, Text.literal("The villager goes on its bed at " + foot.toShortString() + " (green box). Bring it in through the "
				+ "2-high gap at " + door.toShortString() + " from the ledge at " + ledge.toShortString() + " (north side of the room). "
				+ "Once it's in, the printer fills the gap. Wait for green sparkles over it: then it has claimed the bed and raids will work. "
				+ "No job block near it, and only the one villager.").formatted(Formatting.GREEN));
	}
}
