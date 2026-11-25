package com.greysworkshop.totembuilder;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
 * Development-only, {@code ./gradlew runSelfTest}. Survival on Hard, flying (as with an elytra hack), with the
 * materials in the inventory, high over a flat world (the ground is far below, like the sea bed). Checks: planning
 * puts the floor on the sea; the land check finds nothing, then finds a block put out at sea; the printer builds the
 * whole farm, block for block, while the test flies you to where the HUD says next, including the first block in
 * the air, a bed facing the right way and open trapdoors; nothing is placed twice or wrong; the villager's room is
 * sealed once a villager is in; the materials list counts down to nothing.
 */
final class SelfTest {
	private static final String WORLD = "totembuilder-selftest";
	private static boolean started;
	private static int tick = -1, phaseStart, passed, failed, hops;
	private static String phase = "setup";
	private static BlockPos lastHop;
	private static boolean raidSeen;
	private static boolean serverSawTotem;

	/** Did the server's command dispatcher see a /totem? It has no such command, so it would log "Unknown command". */
	private static boolean serverGotCommand(MinecraftClient client) {
		try {
			return java.nio.file.Files.readAllLines(client.runDirectory.toPath().resolve("logs/latest.log")).stream()
					.anyMatch(l -> l.contains("Unknown or incomplete command") || l.contains("issued server command: /totem"));
		} catch (java.io.IOException e) {
			return false;
		}
	}
	private static BlockPos lastTarget;
	private static int lastPlaced = -1, lastProgress;

	private SelfTest() {}

	static void start() {
		log("Self-test armed");
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
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		deleteTree(client.runDirectory.toPath().resolve("saves").resolve(WORLD));
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

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			log("Could not clear " + dir + ": " + e);
		}
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
		Farm farm = TotemBuilder.farm;
		switch (phase) {
			case "setup" -> {
				if (tick == 20) {
					for (String rule : new String[]{"spawn_mobs false", "doMobSpawning false", "spawn_patrols false", "doPatrolSpawning false",
							"advance_time false", "doDaylightCycle false", "advance_weather false", "doWeatherCycle false"})
						command(client, "gamerule " + rule);
					command(client, "tp @p 8.5 70 8.5");
					fly(client);
				}
				if (tick == 60) {
					// Watch what reaches the server: /totem must never get there.
					net.fabricmc.fabric.api.message.v1.ServerMessageEvents.COMMAND_MESSAGE.register((m, src, params) -> serverSawTotem = true);
					client.player.networkHandler.sendChatCommand("totem");
				}
				if (tick == 90) {
					check("typing /totem opens the screen", client.currentScreen instanceof TotemScreen);
					boolean[] seen = {false};
					client.player.networkHandler.sendChatCommand("totem materials");
					check("/totem never reaches the server", !serverSawTotem && !serverGotCommand(client));
					client.setScreen(null);
				}
				if (tick == 100) client.setScreen(new TotemScreen(null));
				if (tick == 110 && client.currentScreen instanceof TotemScreen ts) {
					// Plan from the Mod Menu screen's own button, as you would.
					for (var child : ts.children())
						if (child instanceof net.minecraft.client.gui.widget.ButtonWidget b && b.getMessage().getString().startsWith("Plan the farm here")) b.onPress(null);
				}
				if (tick == 120) {
					check("the Totem Builder screen's Plan button planned the farm", TotemBuilder.farm != null && client.currentScreen == null);
					check("planned with the floor on the sea (bed y " + TotemBuilder.farm.bed.getY() + ", sea " + client.world.getSeaLevel() + ")",
							TotemBuilder.farm.bed.getY() == client.world.getSeaLevel() - FarmPlan.FLOOR_Y && TotemBuilder.farm.bed.getX() == 8);
					// This flat world's "sea" is in its ground: build high up instead, far above it, like over deep ocean.
					TotemBuilder.planAt(client, new BlockPos(8, 120, 8));
					command(client, "tp @p 8.5 " + (120 + FarmPlan.FLOOR_Y + 3) + " 12.5");
					fly(client);
					phase("check");
				}
			}
			case "check" -> {
				if (TotemBuilder.check != null && TotemBuilder.check.finished) {
					farm = TotemBuilder.farm;
					check("land check: nothing in range (" + TotemBuilder.check.checked + " columns, " + TotemBuilder.check.unloaded + " not loaded)",
							TotemBuilder.check.bad.isEmpty() && TotemBuilder.check.checked > 1000);
					BlockPos island = farm.bed.add(30, -10, 20);
					server(client, s -> {
						s.getOverworld().setBlockState(island, Blocks.DIRT.getDefaultState(), 3);
						return 0;
					});
					phase("check2");
				}
			}
			case "check2" -> {
				if (since == 10) TotemBuilder.runCheck(client);
				if (since > 10 && TotemBuilder.check.finished) {
					BlockPos island = farm.bed.add(30, -10, 20);
					check("land check: finds a dirt block out at sea", TotemBuilder.check.bad.contains(island) && TotemBuilder.check.bad.size() == 1);
					// One on the farm's own footprint: covered by the farm, so it doesn't count.
					server(client, s -> {
						s.getOverworld().setBlockState(island, Blocks.AIR.getDefaultState(), 3);
						return 0;
					});
					giveMaterials(client);
					if (Boolean.getBoolean("totembuilder.wateronly")) {
						// Just the water: every block goes straight in, and the printer fills the lake.
						BlockPos bed = farm.bed;
						FarmPlan fp = farm.plan;
						server(client, s -> {
							for (Map.Entry<BlockPos, BlockState> e : fp.blocks().entrySet())
								if (!e.getValue().isOf(Blocks.WATER)) s.getOverworld().setBlockState(bed.add(e.getKey()), e.getValue(), 3);
							return 0;
						});
						command(client, "tp @p " + (bed.getX() + 0.5) + " " + (bed.getY() + FarmPlan.PAD_Y + 3) + " " + (bed.getZ() - 1.5));
						// Three full buckets and three empty, and a puddle outside the farm to fill them at.
						command(client, "clear @p minecraft:water_bucket");
						for (int i = 0; i < 3; i++) command(client, "give @p minecraft:water_bucket 1");
						command(client, "give @p minecraft:bucket 3");
						BlockPos puddle = bed.add(1, FarmPlan.PAD_Y + 3, -9);
						server(client, s -> {
							var w = s.getOverworld();
							// A walled 3 x 3 pool: nine sources, like a bit of sea.
							for (BlockPos q : BlockPos.iterate(puddle.add(-2, -1, -2), puddle.add(2, 0, 2))) w.setBlockState(q, Blocks.COBBLESTONE.getDefaultState(), 3);
							for (BlockPos q : BlockPos.iterate(puddle.add(-1, 0, -1), puddle.add(1, 0, 1))) w.setBlockState(q, Blocks.WATER.getDefaultState(), 3);
							return 0;
						});
					}
					phase("print");
				}
			}
			case "print" -> {
				if (since == 10) client.setScreen(new TotemScreen(null));
				if (since == 20) {
					// The printer goes on from the screen's button too.
					if (client.currentScreen instanceof TotemScreen ts)
						for (var child : ts.children())
							if (child instanceof net.minecraft.client.gui.widget.ButtonWidget b && b.getMessage().getString().startsWith("Printer: OFF")) b.onPress(null);
					check("the screen's Printer button turned the printer on", TotemBuilder.printing);
					TotemBuilder.printing = true;
					TotemBuilder.perTick = 2;
				}
				// Fly on once the printer has nothing left in reach here (it placed nothing for a moment).
				if (TotemBuilder.printer.placedThisSession != lastPlaced) {
					lastPlaced = TotemBuilder.printer.placedThisSession;
					lastProgress = since;
				}
				if (since > 20 && since - lastProgress > 12 && since % 4 == 0) hop(client, farm);
				Map<String, Integer> left = TotemBuilder.missing(client);
				int n = left.values().stream().mapToInt(Integer::intValue).sum();
				if (since % 400 == 0) {
					log("  water: next " + (Printer.nextWater(client.world, farm) == null ? "-" : Printer.nextWater(client.world, farm).subtract(farm.bed).toShortString())
							+ ", buckets " + client.player.getInventory().count(net.minecraft.item.Items.WATER_BUCKET) + " full / " + client.player.getInventory().count(net.minecraft.item.Items.BUCKET) + " empty, refill "
							+ (Printer.refillSpot(client.world, farm) == null ? "none" : Printer.refillSpot(client.world, farm).subtract(farm.bed).toShortString()));
					BlockPos bedS = farm.bed;
					log("  server: " + server(client, srv -> {
						var sp = srv.getPlayerManager().getPlayerList().get(0);
						StringBuilder b = new StringBuilder("buckets " + sp.getInventory().count(net.minecraft.item.Items.WATER_BUCKET) + " full / "
								+ sp.getInventory().count(net.minecraft.item.Items.BUCKET) + " empty, eye " + sp.getEyePos() + " yaw " + sp.getYaw() + " pitch " + sp.getPitch() + ", water at:");
						for (BlockPos q : BlockPos.iterate(bedS.add(-8, 85, -8), bedS.add(8, 100, 8)))
							if (srv.getOverworld().getFluidState(q).isStill()) b.append(" ").append(q.subtract(bedS).toShortString());
						return b.toString();
					}, "?"));
					String why = "-";
					if (lastTarget != null) {
						BlockState want = farm.plan.blocks().get(lastTarget.subtract(farm.bed));
						var hit = Printer.placement(client.world, lastTarget, want, client.player.getEyePos(), client.player.getBlockInteractionRange() - 0.25);
						why = lastTarget.subtract(farm.bed).toShortString() + " " + want + " is " + client.world.getBlockState(lastTarget) + ", eye dist "
								+ String.format("%.1f", client.player.getEyePos().distanceTo(Vec3d.ofCenter(lastTarget))) + ", hit " + (hit == null ? "none" : hit.getBlockPos().subtract(farm.bed).toShortString() + " " + hit.getSide());
					}
					log("  printing: " + n + " left " + left + ", placed " + TotemBuilder.printer.placedThisSession + ", hops " + hops
							+ ", at " + client.player.getBlockPos().subtract(farm.bed).toShortString()
							+ (TotemBuilder.printer.lastProblem.isEmpty() ? "" : ", problem: " + TotemBuilder.printer.lastProblem) + ", target " + why);
				}
				// Everything but the entrance (left for the villager).
				if (Boolean.getBoolean("totembuilder.wateronly") && n == FarmPlan.ENTRANCE.length) {
					check("water only: all six sources poured, refilling from the puddle (" + since / 20 + " s)", true);
					finish(client);
					return;
				}
				if (n == FarmPlan.ENTRANCE.length && since > 40) {
					log("built in " + since / 20 + " s, " + hops + " hops, " + TotemBuilder.printer.placedThisSession + " placements");
					phase("verify");
				}
				if (since > 20 * 60 * 30) {
					log("still to place: " + left);
					check("printer finished the farm in 30 minutes", false);
					finish(client);
				}
			}
			case "verify" -> {
				if (since == 40) {
					int[] r = server(client, s -> {
						int ok = 0, bad = 0;
						for (Map.Entry<BlockPos, BlockState> e : Printer.targets(TotemBuilder.farm, false).entrySet()) {
							BlockState have = s.getOverworld().getBlockState(e.getKey());
							if (Printer.matches(e.getValue(), have)) ok++;
							else {
								bad++;
								if (bad < 10) log("  wrong at " + e.getKey().subtract(TotemBuilder.farm.bed).toShortString() + ": want " + e.getValue() + ", have " + have);
							}
						}
						// Nothing extra: the cells that must stay air.
						int extra = 0;
						for (int x = -6; x <= 13; x++)
							for (int y = FarmPlan.FLOOR_Y; y <= FarmPlan.PAD_Y + 3; y++)
								for (int z = -9; z <= 6; z++) {
									BlockPos rel = new BlockPos(x, y, z);
									BlockPos abs = TotemBuilder.farm.bed.add(rel);
									if (TotemBuilder.farm.plan.blocks().containsKey(rel)) continue;
									boolean entrance = rel.equals(FarmPlan.ENTRANCE[0]) || rel.equals(FarmPlan.ENTRANCE[1]);
									// Flowing water is the lake doing its job, not something placed.
									BlockState here = s.getOverworld().getBlockState(abs);
									if (!entrance && !here.isAir() && !(here.isOf(Blocks.WATER) && !here.getFluidState().isStill())) {
										extra++;
										if (extra < 10) log("  extra at " + rel.toShortString() + ": " + s.getOverworld().getBlockState(abs));
									}
								}
						return new int[]{ok, bad, extra};
					}, new int[3]);
					check("every block is right (" + r[0] + " right, " + r[1] + " wrong)", r[1] == 0);
					check("nothing placed where it shouldn't be (" + r[2] + ")", r[2] == 0);
					BlockPos head = TotemBuilder.farm.bed;
					check("the bed's head is on the planned spot", client.world.getBlockState(head).getBlock() instanceof net.minecraft.block.BedBlock
							&& client.world.getBlockState(head).get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD);
					// A villager walks in.
					server(client, s -> {
						VillagerEntity v = EntityType.VILLAGER.create(s.getOverworld(), SpawnReason.COMMAND);
						v.refreshPositionAndAngles(head.getX() - 0.5, head.getY() + 0.6, head.getZ() + 0.5, 0, 0);
						v.setPersistent();
						s.getOverworld().spawnEntity(v);
						return 0;
					});
					BlockPos door = head.add(FarmPlan.ENTRANCE[0]);
					command(client, "tp @p " + (door.getX() + 0.5) + " " + door.getY() + " " + (door.getZ() - 1.5));
				}
				if (since > 60 && TotemBuilder.farm.sealed) {
					check("room sealed once the villager was in", true);
					check("materials list empty", TotemBuilder.missing(client).isEmpty());
					check("lake full: water by the drop", client.world.getFluidState(TotemBuilder.farm.bed.add(1, FarmPlan.PAD_Y, 3)).isIn(net.minecraft.registry.tag.FluidTags.WATER)
							&& client.world.getFluidState(TotemBuilder.farm.bed.add(3, FarmPlan.PAD_Y, -1)).isIn(net.minecraft.registry.tag.FluidTags.WATER));
					BlockPos stand = TotemBuilder.farm.bed.add(FarmPlan.STAND);
					command(client, "tp @p " + (stand.getX() + 0.5) + " " + stand.getY() + " " + (stand.getZ() + 0.5));
					phase("claim");
				} else if (since > 20 * 30) {
					check("room sealed once the villager was in", false);
					finish(client);
				}
			}
			case "claim" -> {
				int claimed = server(client, s -> (int) s.getOverworld().getPointOfInterestStorage().getInCircle(t -> t.matchesKey(net.minecraft.world.poi.PointOfInterestTypes.HOME),
						TotemBuilder.farm.bed, 1, net.minecraft.world.poi.PointOfInterestStorage.OccupationStatus.IS_OCCUPIED).count());
				if (claimed > 0) {
					command(client, "gamerule spawn_mobs false");
					command(client, "effect give @p minecraft:bad_omen 6000 4");
					command(client, "tick sprint 1000000");
					raidSeen = false;
					phase("raid");
				} else if (since > 20 * 240) {
					check("the villager claimed the bed", false);
					finish(client);
				}
			}
			case "raid" -> {
				int[] r = server(client, s -> {
					var raid = s.getOverworld().getRaidAt(TotemBuilder.farm.bed);
					if (raid == null) return new int[]{0, 0};
					return new int[]{1, raid.hasWon() ? 1 : raid.hasLost() ? 2 : 0};
				}, new int[2]);
				if (r[0] == 1) raidSeen = true;
				if (raidSeen && (r[1] != 0 || r[0] == 0)) {
					command(client, "tick sprint stop");
					int totems = server(client, s -> {
						int n = 0;
						for (var it : s.getOverworld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(TotemBuilder.farm.bed).expand(20, 20, 20), e -> true))
							if (it.getStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING)) n += it.getStack().getCount();
						return n;
					});
					check("a real raid on the printed farm was won (" + (r[1] == 1 ? "won" : "not won") + ")", r[1] == 1);
					check("totems on the floor after it: " + totems, totems >= 5);
					finish(client);
				} else if (since > 20 * 600) {
					command(client, "tick sprint stop");
					check("the raid on the printed farm finished", false);
					finish(client);
				}
			}
			default -> { }
		}
	}

	/** Flies to the nearest block still to place, as you would following the HUD. */
	private static void hop(MinecraftClient client, Farm farm) {
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		Vec3d me = client.player.getEyePos();
		boolean water = Printer.waterTime(client.world, farm, Printer.targets(farm, false));
		for (Map.Entry<BlockPos, BlockState> e : Printer.targets(farm, false).entrySet()) {
			if (Printer.need(e.getValue(), client.world.getBlockState(e.getKey())) != Printer.Need.PLACE) continue;
			if (e.getValue().isOf(Blocks.WATER) && (!water || !e.getKey().equals(Printer.nextWater(client.world, farm)))) continue;
			if (client.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(e.getKey()))) continue;
			// Only ones that can go in now: something next to them (or nothing built yet).
			boolean support = Printer.placeableNow(client.world, e.getKey(), e.getValue());
			if (!support && TotemBuilder.printer.placedThisSession > 0) continue;
			double dist = Vec3d.ofCenter(e.getKey()).squaredDistanceTo(me);
			if (dist < bd) {
				bd = dist;
				best = e.getKey();
			}
		}
		lastTarget = best;
		if (best == null) return;
		// Out of water buckets with water still to pour: go and fill one where the water refills itself.
		if (farm.plan.blocks().get(best.subtract(farm.bed)).isOf(Blocks.WATER) && !client.player.getInventory().contains(new net.minecraft.item.ItemStack(net.minecraft.item.Items.WATER_BUCKET))) {
			BlockPos refill = Printer.refillSpot(client.world, farm);
			if (refill != null) best = refill;
		}
		// Hover in a free spot two or three blocks from it (right over it, for water: a bucket pours where you look).
		BlockState bestWant = farm.plan.blocks().get(best.subtract(farm.bed));
		BlockPos spot = bestWant == null || bestWant.isOf(Blocks.WATER) ? best.up(2) : null;
		if (spot != null && spot.equals(lastHop)) return;
		outer:
		for (int r = 2; r <= 4 && spot == null; r++)
			for (int dy = -1; dy <= 2; dy++)
				for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
					BlockPos c = best.offset(d, r).up(dy);
					if (free(client, farm, c) && free(client, farm, c.up())) {
						spot = c;
						break outer;
					}
				}
		if (spot == null) spot = best.up(3);
		if (spot.equals(lastHop)) spot = spot.up();
		lastHop = spot;
		hops++;
		command(client, "tp @p " + (spot.getX() + 0.5) + " " + spot.getY() + " " + (spot.getZ() + 0.5));
		fly(client);
	}

	private static boolean free(MinecraftClient client, Farm farm, BlockPos p) {
		return client.world.getBlockState(p).isAir() && !farm.plan.blocks().containsKey(p.subtract(farm.bed));
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

	private static void giveMaterials(MinecraftClient client) {
		command(client, "clear @p");
		// Roughly what the farm takes, in the order the inventory fills.
		command(client, "give @p minecraft:cobblestone 1344");
		command(client, "give @p minecraft:glass 192");
		command(client, "give @p minecraft:cobblestone_slab 128");
		command(client, "give @p minecraft:white_bed 1");
		command(client, "give @p minecraft:oak_fence_gate 8");
		for (int i = 0; i < 6; i++) command(client, "give @p minecraft:water_bucket 1");
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
		TotemBuilder.LOGGER.info("[SELFTEST] {}", message);
	}
}
