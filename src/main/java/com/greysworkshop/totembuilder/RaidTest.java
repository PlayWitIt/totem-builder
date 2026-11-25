package com.greysworkshop.totembuilder;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.raid.Raid;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Development-only, {@code ./gradlew runRaidTest -PtbRaids=3 -PtbDesign=main}. Builds the farm straight into a flat
 * Hard world high in the air (the ground is far more than 96 blocks below, so nothing there counts, like an ocean),
 * puts a villager in its room and runs real raids with Bad Omen V: how long each wave takes to clear, where it
 * spawned, raiders that got stuck, totems on the floor, the villager alive at the end.
 */
final class RaidTest {
	private static final String WORLD = "totembuilder-raidtest";
	static final BlockPos BED = new BlockPos(8, 120, 8);
	private static boolean started;
	private static int tick = -1, phaseStart;
	private static String phase = "setup";
	private static int raidsWanted, raidsDone, failed;
	private static FarmPlan plan;
	private static final List<String> results = new ArrayList<>();
	// Per raid.
	private static int lastWaves, waveSpawnTick, raidStartTick, raidTotems, raidBottles;
	private static final List<Integer> waveTimes = new ArrayList<>();
	private static final Map<UUID, Integer> raiderBorn = new HashMap<>();
	private static int stuckKilled;

	private RaidTest() {}

	static void start() {
		raidsWanted = Integer.getInteger("totembuilder.raids", 3);
		plan = new FarmPlan(System.getProperty("totembuilder.design", "main"));
		log("Raid test armed: " + raidsWanted + " raids, design " + plan.design + ", " + plan.blocks().size() + " blocks");
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
		ClientTickEvents.END_CLIENT_TICK.register(RaidTest::tick);
		// Every server tick: what the raid looks like just before each wave is due, and if it stops.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(s -> {
			Raid raid = s.getOverworld().getRaidAt(BED);
			if (raid == null) {
				if (tracked != null) {
					log("  STOPPED: now status " + field(tracked, "status") + " active " + tracked.isActive() + "; the tick before: " + trackedState);
					StringBuilder b = new StringBuilder();
					for (RaiderEntity e : tracked.getAllRaiders()) b.append(e.getType().getUntranslatedName()).append(" at ").append(e.getBlockPos().subtract(BED).toShortString()).append("; ");
					log("  its raiders: " + b);
					log("  raiders a tick before: " + lastRaiders);
					tracked = null;
				}
				return;
			}
			tracked = raid;
			StringBuilder rs = new StringBuilder();
			for (RaiderEntity e : raid.getAllRaiders()) rs.append(e.getType().getUntranslatedName()).append(" at ").append(e.getBlockPos().subtract(BED).toShortString())
					.append(" alive ").append(e.isAlive()).append(" removed ").append(e.isRemoved()).append(" age ").append(e.age).append(" outOfRaid ").append(e.getOutOfRaidCounter())
					.append(" despawn ").append(e.getDespawnCounter()).append("; ");
			lastRaiders = rs.toString();
			for (RaiderEntity e : raid.getAllRaiders()) {
				BlockPos rel = e.getBlockPos().subtract(BED);
				boolean overFloor = rel.getX() >= 0 && rel.getX() <= 8 && rel.getZ() >= 0 && rel.getZ() <= 8;
				if (rel.getY() < 80 && !overFloor && strays.add(e.getUuid()))
					log("  STRAY: " + e.getType().getUntranslatedName() + " falling at " + rel.toShortString() + " v " + e.getVelocity());
			}
			trackedState = "status " + field(raid, "status") + " pre " + field(raid, "preRaidTicks") + " raiders " + raid.getRaiderCount() + " waves "
					+ field(raid, "wavesSpawned") + " ticksActive " + field(raid, "ticksActive") + " nearPoi " + s.getOverworld().isNearOccupiedPointOfInterest(raid.getCenter())
					+ " chunkLoaded " + s.getOverworld().isChunkLoaded(raid.getCenter()) + " level " + raid.getBadOmenLevel();
			String pre = field(raid, "preRaidTicks");
			if (pre.equals("1") || pre.equals("2")) log("  just before the wave: preRaidTicks " + pre + " precalc " + field(raid, "preCalculatedRaidersSpawnLocation")
					+ " centre " + raid.getCenter().subtract(BED).toShortString() + " raiders " + raid.getRaiderCount());
		});
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		deleteTree(client.runDirectory.toPath().resolve("saves").resolve(WORLD));
		LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.HARD, true,
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

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		if (client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen) client.setScreen(null);
		tick++;
		int since = tick - phaseStart;
		switch (phase) {
			case "setup" -> {
				if (tick == 20) {
					for (String rule : new String[]{"spawn_mobs false", "doMobSpawning false", "spawn_patrols false",
							"doPatrolSpawning false", "spawn_wandering_traders false", "doTraderSpawning false",
							"advance_weather false", "doWeatherCycle false"})
						command(client, "gamerule " + rule);
					command(client, "difficulty hard");
					command(client, "tp @p " + (BED.getX() + 0.5) + " " + (BED.getY() + FarmPlan.FLOOR_Y + 1) + " " + (BED.getZ() - 1.5));
				}
				if (tick == 100) {
					server(client, s -> {
						ServerWorld w = s.getOverworld();
						for (Map.Entry<BlockPos, BlockState> e : plan.blocks().entrySet())
							w.setBlockState(BED.add(e.getKey()), e.getValue(), 3);
						for (BlockPos e : FarmPlan.ENTRANCE) w.setBlockState(BED.add(e), net.minecraft.block.Blocks.COBBLESTONE.getDefaultState(), 3);
						VillagerEntity v = EntityType.VILLAGER.create(w, SpawnReason.COMMAND);
						v.refreshPositionAndAngles(BED.getX() - 0.5, BED.getY() + 0.6, BED.getZ() + 0.5, 0, 0);
						v.setPersistent();
						w.spawnEntity(v);
						return 0;
					});
					command(client, "tp @p " + (BED.getX() + FarmPlan.STAND.getX() + 0.5) + " " + (BED.getY() + FarmPlan.STAND.getY()) + " " + (BED.getZ() + FarmPlan.STAND.getZ() + 0.5));
					phase("claim");
				}
			}
			case "claim" -> {
				if (Boolean.getBoolean("totembuilder.climbtest") && since == 20) {
					server(client, s -> {
						ServerWorld w = s.getOverworld();
						for (int x = -3; x <= 0; x++)
							for (int z = -3; z <= 0; z++) {
								if (!FarmPlan.padCell(x, z)) continue;
								var r = EntityType.RAVAGER.create(w, SpawnReason.EVENT);
								r.refreshPositionAndAngles(BED.getX() + x + 0.5, BED.getY() + 95, BED.getZ() + z + 0.5, 0, 0);
								r.setOnGround(true);
								r.setPersistent();
								w.spawnEntity(r);
							}
						return 0;
					});
				}
				if (Boolean.getBoolean("totembuilder.climbtest") && since == 20 + 20 * 20) {
					server(client, s -> {
						for (var r : s.getOverworld().getEntitiesByType(EntityType.RAVAGER, new Box(BED).expand(40, 120, 40), e -> true))
							log("  ravager at " + r.getBlockPos().subtract(BED).toShortString() + " y " + String.format("%.2f", r.getY() - BED.getY()));
						return 0;
					});
					finish(client);
					return;
				}
				if (Boolean.getBoolean("totembuilder.climbtest")) return;
				if (since % 20 == 0 && claimed(client)) {
					log("villager claimed the bed after " + since + " ticks");
					command(client, "tick sprint 1000000");
					phase("omen");
				} else if (since > 20 * 240) {
					log("FAIL: the villager never claimed the bed");
					finish(client);
				}
			}
			case "omen" -> {
				if (since == 1) {
					command(client, "effect give @p minecraft:bad_omen 6000 4");
					lastWaves = 0;
					waveTimes.clear();
					raidTotems = raidBottles = stuckKilled = 0;
					raidStartTick = serverTick(client);
				}
				int[] r = raid(client);
				if (r[0] == 1) {
					log("raid started, level " + r[3]);
					phase("raid");
				} else if (since > 20 * 120) {
					log("FAIL: no raid started");
					failed++;
					finish(client);
				}
			}
			case "raid" -> raidTick(client, since);
			case "between" -> {
				if (raid(client)[0] == 0 && since > 20) {
					if (raidsDone >= raidsWanted) finish(client);
					else phase("omen");
				}
			}
			default -> { }
		}
	}

	private static void raidTick(MinecraftClient client, int since) {
		int[] r = raid(client);
		// r: active, raiders, wavesSpawned, level, won(1)/lost(2)
		int now = serverTick(client);
		if (r[2] > lastWaves) {
			waveSpawnTick = now;
			lastWaves = r[2];
			String where = server(client, s -> {
				Raid raid = s.getOverworld().getRaidAt(BED);
				if (raid == null) return "?";
				StringBuilder b = new StringBuilder();
				for (RaiderEntity e : raid.getAllRaiders()) {
					if (b.isEmpty()) b.append(e.getBlockPos().subtract(BED).toShortString());
				}
				return b + " (" + raid.getRaiderCount() + " raiders)";
			}, "");
			log("wave " + r[2] + " spawned at " + where);
		}
		if (r[1] == 0 && waveSpawnTick > 0) {
			int t = now - waveSpawnTick;
			waveTimes.add(t);
			log("wave " + lastWaves + " cleared in " + String.format("%.1f", t / 20.0) + " s");
			waveSpawnTick = 0;
		}
		// A raider alive 40 s after its wave came is stuck: say where, then kill it so the test goes on.
		if (since % 20 == 0) {
			int k = server(client, s -> {
				Raid raid = s.getOverworld().getRaidAt(BED);
				if (raid == null) return 0;
				int killed = 0;
				for (RaiderEntity e : new ArrayList<>(raid.getAllRaiders())) {
					int born = raiderBorn.computeIfAbsent(e.getUuid(), u -> s.getTicks());
					if (s.getTicks() - born > 20 * 40) {
						log("STUCK: " + e.getType().getUntranslatedName() + " at " + e.getBlockPos().subtract(BED).toShortString()
								+ (e.hasVehicle() ? " riding" : "") + " hp " + e.getHealth() + " in " + s.getOverworld().getBlockState(e.getBlockPos()).getBlock().getTranslationKey()
								+ " on " + s.getOverworld().getBlockState(e.getBlockPos().down()).getBlock().getTranslationKey() + " ground " + e.isOnGround() + " water " + e.isTouchingWater()
								+ " v " + String.format("%.2f", e.getVelocity().y));
						e.kill(s.getOverworld());
						killed++;
					}
				}
				return killed;
			});
			stuckKilled += k;
		}
		if (r[4] != 0 || r[0] == 0) {
			int[] loot = server(client, s -> {
				ServerWorld w = s.getOverworld();
				Box floor = new Box(BED.add(-2, FarmPlan.FLOOR_Y - 1, 0)).union(new Box(BED.add(9, FarmPlan.FLOOR_Y + 3, 11)));
				int totems = 0, bottles = 0, elsewhere = 0;
				for (ItemEntity it : w.getEntitiesByClass(ItemEntity.class, new Box(BED).expand(64), e -> true)) {
					boolean on = floor.contains(it.getEntityPos());
					if (it.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
						if (on) totems += it.getStack().getCount();
						else elsewhere += it.getStack().getCount();
					}
					if (it.getStack().isOf(Items.OMINOUS_BOTTLE) && on) bottles += it.getStack().getCount();
					it.discard();
				}
				boolean villager = !w.getEntitiesByClass(VillagerEntity.class, new Box(BED).expand(3), e -> e.isAlive()).isEmpty();
				return new int[]{totems, bottles, elsewhere, villager ? 1 : 0};
			}, new int[5]);
			if (r[4] == 0) server(client, s -> {
				ServerWorld w = s.getOverworld();
				for (int x = -3; x <= 0; x++)
					for (int z = -3; z <= 0; z++) {
						int top = w.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, BED.getX() + x, BED.getZ() + z);
						log("  pad column " + x + "," + z + ": top " + (top - BED.getY()) + " " + w.getBlockState(new BlockPos(BED.getX() + x, top - 1, BED.getZ() + z)));
					}
				log("  poi near centre: " + w.isNearOccupiedPointOfInterest(BED) + ", difficulty " + w.getDifficulty());
				return 0;
			});
			raidsDone++;
			double total = (now - raidStartTick) / 20.0;
			String line = "raid " + raidsDone + ": " + (r[4] == 1 ? "won" : r[4] == 2 ? "LOST" : "ended") + ", " + lastWaves + " waves, "
					+ loot[0] + " totems (" + loot[2] + " off the floor), " + loot[1] + " bottles, " + stuckKilled + " stuck, "
					+ String.format("%.0f", total) + " s from the omen, waves " + waveTimes.stream().map(t -> String.format("%.1f", t / 20.0)).toList()
					+ ", villager " + (loot[3] == 1 ? "alive" : "DEAD");
			log(line);
			results.add(line);
			if (r[4] != 1 || stuckKilled > 0 || loot[3] == 0 || loot[2] > 0) failed++;
			if (loot[3] == 0) {
				finish(client);
				return;
			}
			phase("between");
		}
		if (now - raidStartTick > 20 * 60 * 20) {
			log("FAIL: raid ran for 20 minutes");
			failed++;
			finish(client);
		}
	}

	private static int serverTick(MinecraftClient client) {
		return server(client, MinecraftServer::getTicks);
	}

	private static String lastState = "";
	private static Raid tracked;
	private static String lastRaiders = "";
	private static final java.util.Set<UUID> strays = new java.util.HashSet<>();
	private static String trackedState = "";

	private static String field(Raid raid, String name) {
		try {
			java.lang.reflect.Field f = Raid.class.getDeclaredField(name);
			f.setAccessible(true);
			return String.valueOf(f.get(raid));
		} catch (ReflectiveOperationException e) {
			return "?";
		}
	}

	/** active, raiders alive, waves spawned, level, 1 won / 2 lost. */
	private static int[] raid(MinecraftClient client) {
		return server(client, s -> {
			Raid raid = s.getOverworld().getRaidAt(BED);
			if (raid == null || raid.hasStopped()) {
				if (!lastState.isEmpty()) {
					log("raid gone; last seen " + lastState + (raid == null ? "" : ", now " + field(raid, "status")));
					try {
						java.lang.reflect.Field f = net.minecraft.village.raid.RaidManager.class.getDeclaredField("raids");
						f.setAccessible(true);
						Map<?, ?> all = (Map<?, ?>) f.get(s.getOverworld().getRaidManager());
						for (Object o : all.values()) {
							Raid x = (Raid) o;
							log("  raid at " + x.getCenter().subtract(BED).toShortString() + " status " + field(x, "status") + " active " + x.isActive()
									+ " waves " + field(x, "wavesSpawned") + " raiders " + x.getRaiderCount());
						}
					} catch (ReflectiveOperationException e) {
						log("  " + e);
					}
					log("  bed claimed: " + s.getOverworld().getPointOfInterestStorage().getInCircle(t -> t.matchesKey(PointOfInterestTypes.HOME), BED, 1, PointOfInterestStorage.OccupationStatus.IS_OCCUPIED).count()
							+ ", near poi " + s.getOverworld().isNearOccupiedPointOfInterest(BED) + ", player at " + s.getPlayerManager().getPlayerList().get(0).getBlockPos().subtract(BED).toShortString());
				}
				lastState = "";
				return new int[5];
			}
			lastState = "status " + field(raid, "status") + " preRaidTicks " + field(raid, "preRaidTicks") + " waves " + field(raid, "wavesSpawned")
					+ "/" + field(raid, "waveCount") + " raiders " + raid.getRaiderCount() + " ticksActive " + field(raid, "ticksActive")
					+ " precalc " + field(raid, "preCalculatedRaidersSpawnLocation");
			return new int[]{1, raid.getRaiderCount(), raid.getGroupsSpawned(), raid.getBadOmenLevel(),
					raid.hasWon() ? 1 : raid.hasLost() ? 2 : 0};
		}, new int[5]);
	}

	private static boolean claimed(MinecraftClient client) {
		return server(client, s -> (int) s.getOverworld().getPointOfInterestStorage()
				.getInCircle(t -> t.matchesKey(PointOfInterestTypes.HOME), BED, 1, PointOfInterestStorage.OccupationStatus.IS_OCCUPIED)
				.count()) > 0;
	}

	private static void finish(MinecraftClient client) {
		phase("done");
		command(client, "tick sprint stop");
		log("RESULTS (" + plan.design + "):");
		for (String r : results) log("  " + r);
		log((failed == 0 && raidsDone >= raidsWanted ? "PASS" : "FAIL") + ": " + raidsDone + " raids, " + failed + " with problems");
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
		TotemBuilder.LOGGER.info("[RAIDTEST] {}", message);
	}
}
