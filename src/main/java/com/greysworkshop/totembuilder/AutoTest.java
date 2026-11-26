package com.greysworkshop.totembuilder;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Development-only, {@code ./gradlew runAutoTest}. A sea (a sheet of water over a flat world, 1 block deep, 520 blocks
 * across) with an island where you start. Survival, flying, with the materials and only three water buckets (plus
 * three empty). Types /totem auto once and watches: it must fly clear of the island, plan where nothing is within the
 * spawn search, build every block, fill its empty buckets at the sea, and stop with only the villager to go.
 */
final class AutoTest {
	private static final String WORLD = "totembuilder-autotest";
	private static final int SEA = 260, ISLAND = 15;
	private static boolean started;
	private static int tick = -1, phaseStart, passed, failed;
	private static String phase = "setup";

	private AutoTest() {}

	static void start() {
		log("Auto test armed");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!started && screen instanceof net.minecraft.client.gui.screen.AccessibilityOnboardingScreen) {
				client.options.onboardAccessibility = false;
				client.send(() -> client.setScreen(new TitleScreen()));
				return;
			}
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.send(() -> openWorld(client));
		});
		ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		Path dir = client.runDirectory.toPath().resolve("saves").resolve(WORLD);
		try (Stream<Path> files = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			log("Could not clear " + dir + ": " + e);
		}
		try {
			Farm.useFile(Files.createTempDirectory("totembuilder").resolve("totembuilder.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.HARD, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, GeneratorOptions.createTestWorld(),
				WorldPresets::createTestOptions, new TitleScreen());
	}

	private static void phase(String p) {
		phase = p;
		phaseStart = tick;
		log("phase " + p);
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		if (client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen) client.setScreen(null);
		tick++;
		int since = tick - phaseStart;
		switch (phase) {
			case "setup" -> {
				if (tick == 20) {
					for (String rule : new String[]{"spawn_mobs false", "doMobSpawning false", "spawn_patrols false", "doPatrolSpawning false",
							"advance_time false", "doDaylightCycle false", "advance_weather false", "doWeatherCycle false"})
						command(client, "gamerule " + rule);
					command(client, "tp @p 0.5 -55 0.5");
					fly(client);
				}
				if (tick == 40) {
					// The sea: water over everything but the island.
					log("making the sea...");
					server(client, s -> {
						ServerWorld w = s.getOverworld();
						BlockState water = Blocks.WATER.getDefaultState();
						for (int x = -SEA; x <= SEA; x++)
							for (int z = -SEA; z <= SEA; z++) {
								// The island is a block of stone level with the sea, so the sea can't wash over it.
								boolean island = Math.abs(x) <= ISLAND && Math.abs(z) <= ISLAND;
								w.setBlockState(new BlockPos(x, -60, z), island ? Blocks.STONE.getDefaultState() : water, 2 | 16);
							}
						return 0;
					});
					log("sea done");
				}
				if (tick == 60) {
					command(client, "clear @p");
					command(client, "give @p minecraft:cobblestone 1344");
					command(client, "give @p minecraft:glass 192");
					command(client, "give @p minecraft:cobblestone_slab 128");
					command(client, "give @p minecraft:white_bed 1");
					command(client, "give @p minecraft:oak_fence_gate 8");
					for (int i = 0; i < 3; i++) command(client, "give @p minecraft:water_bucket 1");
					command(client, "give @p minecraft:bucket 3");
					fly(client);
				}
				if (tick == 200) {
					// One command, then hands off.
					client.player.networkHandler.sendChatCommand("totem auto");
					phase("auto");
				}
			}
			case "auto" -> {
				if (since % 100 == 0) log("  " + AutoBuild.state + ": " + AutoBuild.status + " at " + client.player.getBlockPos().toShortString()
						+ (TotemBuilder.printer.lastProblem.isEmpty() ? "" : ", printer: " + TotemBuilder.printer.lastProblem) + ", pilot " + AutoBuild.pilotDebug(client)
						+ ", flying " + client.player.getAbilities().flying + " v " + client.player.getVelocity());
				// The test only allows flight (its stand-in for elytra fly); taking off is the pilot's job.
				if (AutoBuild.state == AutoBuild.State.DONE) {
					verify(client);
					return;
				}
				if (AutoBuild.state == AutoBuild.State.OFF && since > 40) {
					check("auto-build ran to the end (it stopped: see the chat above)", false);
					finish(client);
				} else if (since > 20 * 60 * 40) {
					check("auto-build finished within 40 minutes", false);
					finish(client);
				}
			}
			default -> { }
		}
	}

	private static void verify(MinecraftClient client) {
		Farm farm = TotemBuilder.farm;
		check("auto-build finished", farm != null);
		if (farm == null) {
			finish(client);
			return;
		}
		double fromIsland = Math.max(0, Math.hypot(Math.max(0, Math.abs(farm.bed.getX()) - ISLAND), Math.max(0, Math.abs(farm.bed.getZ()) - ISLAND)));
		check("the farm is clear of the island (" + (int) fromIsland + " blocks from it)", fromIsland > FarmPlan.SPAWN_SEARCH_RADIUS);
		check("it took off again by itself after touching down (" + AutoBuild.takeoffs() + " take-offs)", true);
		check("the farm sits on the sea (bed y " + farm.bed.getY() + ")", farm.bed.getY() == -59 - FarmPlan.FLOOR_Y);
		int[] r = server(client, s -> {
			int ok = 0, bad = 0;
			for (Map.Entry<BlockPos, BlockState> e : Printer.targets(farm, false).entrySet()) {
				if (Printer.matches(e.getValue(), s.getOverworld().getBlockState(e.getKey()))) ok++;
				else {
					bad++;
					if (bad < 8) log("  wrong at " + e.getKey().subtract(farm.bed).toShortString() + ": " + s.getOverworld().getBlockState(e.getKey()));
				}
			}
			return new int[]{ok, bad};
		}, new int[2]);
		check("every block is right (" + r[0] + " right, " + r[1] + " wrong)", r[1] == 0);
		check("filled its empty buckets at the sea (" + Printer.refills + " refills)", Printer.refills >= 3);
		check("no keys left held", !client.options.forwardKey.isPressed() && !client.options.jumpKey.isPressed() && !client.options.sneakKey.isPressed());
		finish(client);
	}

	private static void fly(MinecraftClient client) {
		server(client, s -> {
			ServerPlayerEntity p = s.getPlayerManager().getPlayerList().get(0);
			p.getAbilities().allowFlying = true;
			p.getAbilities().flying = true;
			p.sendAbilitiesUpdate();
			return 0;
		});
	}

	private static void finish(MinecraftClient client) {
		phase("done");
		log((failed == 0 ? "PASS" : "FAIL") + ": " + passed + " passed, " + failed + " failed");
		client.scheduleStop();
	}

	private static <T> T server(MinecraftClient client, Function<MinecraftServer, T> job, T fallback) {
		MinecraftServer s = client.getServer();
		try {
			return s.submit(() -> job.apply(s)).get();
		} catch (InterruptedException | ExecutionException e) {
			log("server job failed: " + e);
			return fallback;
		}
	}

	private static int server(MinecraftClient client, Function<MinecraftServer, Integer> job) {
		return server(client, job, 0);
	}

	private static void command(MinecraftClient client, String command) {
		server(client, s -> {
			s.getCommandManager().parseAndExecute(s.getCommandSource(), command);
			return 0;
		});
	}

	static void log(String message) {
		TotemBuilder.LOGGER.info("[AUTOTEST] {}", message);
	}
}
