package com.greysworkshop.totembuilder;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
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
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Development-only, {@code ./gradlew runWalkTest} ({@code -PtbHalf=true}: two thirds of the farm already built by
 * hand, with a wrong block and old scaffolding in it). A sea over a flat world; survival, no flight, the materials,
 * scaffolding, a pickaxe and bread. It plans the farm, stands you on one block in the sea beside it, presses "Auto:
 * walk + scaffolding" once and watches: every block must end up right, every bit of scaffolding gone, nothing
 * stray left, you alive. Screenshots go to run/screenshots (walktest-*.png): the ghost blocks before it starts, and
 * the build as it goes.
 */
final class WalkTest {
	private static final String WORLD = "totembuilder-walktest";
	private static final int SEA = 48;
	private static boolean started, half, render;
	private static int tick = -1, phaseStart, passed, failed, shots;
	private static String phase = "setup";
	private static BlockPos bed;
	private static int toppedUp, shotLater = -1;

	private WalkTest() {}

	static void start() {
		half = Boolean.getBoolean("totembuilder.half");
		render = Boolean.getBoolean("totembuilder.render");
		if (render) half = true;
		log("Walk test armed" + (half ? " (half built)" : ""));
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
		ClientTickEvents.END_CLIENT_TICK.register(WalkTest::tick);
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
			Path tmp = Files.createTempDirectory("totembuilder");
			Farm.useFile(tmp.resolve("totembuilder.json"));
			Settings.useFile(tmp.resolve("settings.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.PEACEFUL, true,
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
					for (String rule : new String[]{"spawn_mobs false", "doMobSpawning false", "advance_time false", "doDaylightCycle false",
							"advance_weather false", "doWeatherCycle false"})
						command(client, "gamerule " + rule);
					command(client, "time set noon");
					command(client, "tp @p 0.5 -55 0.5");
				}
				if (tick == 40) {
					server(client, s -> {
						ServerWorld w = s.getOverworld();
						for (int x = -SEA; x <= SEA; x++)
							for (int z = -SEA; z <= SEA; z++) w.setBlockState(new BlockPos(x, -60, z), Blocks.WATER.getDefaultState(), 2 | 16);
						return 0;
					});
					log("sea done");
				}
				if (tick == 60) {
					command(client, "clear @p");
					command(client, "give @p minecraft:cobblestone 960");
					command(client, "give @p minecraft:glass 192");
					command(client, "give @p minecraft:cobblestone_slab 128");
					command(client, "give @p minecraft:white_bed 1");
					command(client, "give @p minecraft:oak_fence_gate 8");
					for (int i = 0; i < 6; i++) command(client, "give @p minecraft:water_bucket 1");
					command(client, "give @p minecraft:diamond_pickaxe[enchantments={\"minecraft:efficiency\":5}] 1");
					command(client, "give @p minecraft:bread 32");
					command(client, "effect give @p minecraft:saturation 1 10 true");
				}
				if (tick == 80) {
					// The farm's floor on the sea (y -59), bed 11 up.
					bed = new BlockPos(0, -59 - FarmPlan.FLOOR_Y, 0);
					TotemBuilder.planAt(client, bed);
					Farm farm = TotemBuilder.farm;
					BlockPos start = bed.add(10, FarmPlan.FLOOR_Y - 1, 4);
					server(client, s -> {
						ServerWorld w = s.getOverworld();
						w.setBlockState(start, Blocks.COBBLESTONE.getDefaultState(), 2 | 16);
						if ("water".equals(System.getProperty("totembuilder.from"))) {
							// Everything up to the lake's level already in: straight on to the water, the top and the clean-up.
							for (Map.Entry<BlockPos, BlockState> e : Printer.targets(farm, false).entrySet()) {
								BlockState want = e.getValue();
								if (want.isOf(Blocks.WATER) || e.getKey().getY() > bed.getY() + FarmPlan.PAD_Y) continue;
								if (want.getBlock() instanceof net.minecraft.block.BedBlock) {
									if (want.get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.FOOT) {
										w.setBlockState(e.getKey(), want, 3);
										w.setBlockState(e.getKey().offset(want.get(net.minecraft.block.BedBlock.FACING)),
												want.with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD), 3);
									}
									continue;
								}
								w.setBlockState(e.getKey(), want, 2 | 16);
							}
						}
						if (half) {
							Random rnd = new Random(7);
							int n = 0;
							for (Map.Entry<BlockPos, BlockState> e : Printer.targets(farm, false).entrySet()) {
								BlockState want = e.getValue();
								if (want.isOf(Blocks.WATER) || rnd.nextInt(3) == 0) continue;
								if (want.getBlock() instanceof net.minecraft.block.BedBlock) {
									if (want.get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD) continue;
									w.setBlockState(e.getKey(), want, 3);
									w.setBlockState(e.getKey().offset(want.get(net.minecraft.block.BedBlock.FACING)),
											want.with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD), 3);
									continue;
								}
								w.setBlockState(e.getKey(), want, 2 | 16);
								if (++n == 300) w.setBlockState(e.getKey(), Blocks.DIRT.getDefaultState(), 2 | 16);
							}
							w.setBlockState(bed.add(2, 40, 2), Blocks.DIRT.getDefaultState(), 2 | 16);
							w.setBlockState(bed.add(3, FarmPlan.FLOOR_Y + 1, 3), Blocks.NETHERRACK.getDefaultState(), 2 | 16);
						}
						return 0;
					});
					farm.scaffold.add(start);
					command(client, "tp @p " + (start.getX() + 0.5) + " " + (start.getY() + 1) + " " + (start.getZ() + 0.5) + " 135 -20");
				}
				if (render && tick == 100) {
					// Off to one side and up, looking at the half-built farm.
					BlockPos at = bed.add(16, 0, 16);
					server(client, s -> {
						s.getOverworld().setBlockState(at.down(), Blocks.GLASS.getDefaultState(), 2 | 16);
						return 0;
					});
					command(client, "tp @p " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 135 10");
				}
				if (render && tick == 180) shot(client, "render-side");
				if (render && tick == 190) command(client, "tp @p " + (bed.getX() + 16.5) + " " + (bed.getY() + 70) + " " + (bed.getZ() + 16.5) + " 135 20");
				if (render && tick == 240) shot(client, "render-high");
				if (render && tick == 250) {
					Settings.ghosts = Settings.Ghosts.PLACEABLE;
					shotLater = tick + 40;
				}
				if (render && tick == shotLater) shot(client, "render-placeable-only");
				if (render && tick == 300) finish(client);
				if (render) return;
				if (tick == 160) shot(client, "ghosts-from-the-start");
				if (tick == 170) {
					client.player.setYaw(135);
					client.player.setPitch(-35);
				}
				if (tick == 200) shot(client, "ghosts-looking-up");
				if (tick == 220) {
					Walker.start(client);
					check("the walking auto-builder started", Walker.active());
					phase("walk");
				}
			}
			case "walk" -> {
				if (since % 200 == 0) {
					Walk.Job j = Walker.job();
					Survey s = Survey.latest;
					log("  " + Walker.status + " at " + client.player.getBlockPos().subtract(bed).toShortString() + ", built " + (s == null ? "?" : s.done + "/" + s.total)
							+ ", scaffolding " + TotemBuilder.farm.scaffold.size() + ", placed " + Walker.placed() + ", broken " + Walker.broken() + ", health " + client.player.getHealth()
							+ ", food " + client.player.getHungerManager().getFoodLevel() + (j == null ? "" : ", job " + j.act()));
				}
				if (since % (20 * 60 * 3) == 0 && since > 0) shot(client, "building");
				// As a player would, it tops up from a shulker box now and then.
				if (since % 100 == 0) {
					int cobble = Survey.count(client.player.getInventory(), net.minecraft.item.Items.COBBLESTONE);
					int free = 0;
					for (int i = 0; i < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; i++) if (client.player.getInventory().getStack(i).isEmpty()) free++;
					if (cobble < 256 && free >= 3 && toppedUp < 1700) {
						command(client, "give @p minecraft:cobblestone 192");
						toppedUp += 192;
					}
				}
				if (!Walker.active() && since > 40) {
					verify(client);
					return;
				}
				if (since > 20 * 60 * 120) {
					check("finished within 2 hours", false);
					Walker.stop(client, "test timeout");
					verify(client);
				}
			}
			default -> { }
		}
	}

	private static void verify(MinecraftClient client) {
		Farm farm = TotemBuilder.farm;
		shot(client, "done");
		int[] r = server(client, s -> {
			ServerWorld w = s.getOverworld();
			int ok = 0, bad = 0;
			for (Map.Entry<BlockPos, BlockState> e : Printer.targets(farm, false).entrySet()) {
				if (Printer.matches(e.getValue(), w.getBlockState(e.getKey()))) ok++;
				else {
					bad++;
					if (bad < 8) log("  wrong at " + e.getKey().subtract(farm.bed).toShortString() + ": " + w.getBlockState(e.getKey()));
				}
			}
			// Anything left in the farm's space or on the sea round it that isn't the farm.
			int stray = 0;
			Map<BlockPos, BlockState> targets = Printer.targets(farm, false);
			for (BlockPos p : BlockPos.iterate(farm.bed.add(-16, FarmPlan.FLOOR_Y - 1, -16), farm.bed.add(16, FarmPlan.PAD_Y + 10, 16))) {
				if (targets.containsKey(p)) continue;
				BlockState st = w.getBlockState(p);
				if (st.isAir() || !st.getFluidState().isEmpty()) continue;
				stray++;
				if (stray < 8) log("  left over at " + p.subtract(farm.bed).toShortString() + ": " + st);
			}
			return new int[]{ok, bad, stray};
		}, new int[3]);
		check("every block of the farm is right (" + r[0] + " right, " + r[1] + " wrong)", r[1] == 0);
		check("no scaffolding or stray block left (" + r[2] + " found)", r[2] == 0);
		check("its scaffolding list is empty (" + farm.scaffold.size() + ")", farm.scaffold.isEmpty());
		check("alive and well (health " + client.player.getHealth() + ")", client.player.isAlive() && client.player.getHealth() >= 7);
		check("no keys left held", !client.options.forwardKey.isPressed() && !client.options.jumpKey.isPressed() && !client.options.sneakKey.isPressed());
		log("  it put down " + Walker.placed() + " scaffolding and took up " + Walker.broken() + "; took " + (tick - phaseStart) / 20 / 60 + " min");
		finish(client);
	}

	private static void shot(MinecraftClient client, String name) {
		String file = "walktest-" + (++shots) + "-" + name + ".png";
		ScreenshotRecorder.saveScreenshot(client.runDirectory, file, client.getFramebuffer(), 1, t -> log("screenshot " + file));
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
		}, 0);
	}

	static void log(String message) {
		TotemBuilder.LOGGER.info("[WALKTEST] {}", message);
	}
}
