package com.greysworkshop.totembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * /totem auto: does the lot while you watch. Finds a clean spot over the ocean (flying away from any land in sight
 * until there's nothing within the raid's spawn search), plans the farm there, flies from block to block while the
 * printer places them, goes down to the sea to fill empty buckets when the water runs out, and stops when only the
 * villager is left. It stops and says why if you run out of something; /totem auto again carries on.
 */
final class AutoBuild {
	enum State { OFF, SEARCH, TO_SPOT, CHECK, BUILD, REFILL, DONE }

	static State state = State.OFF;
	static String status = "";
	private static final Pilot pilot = new Pilot();
	private static Vec3d start;
	private static BlockPos spot;
	private static int ticks, lastPlaced, lastProgress, searchAt;
	private static final Set<BlockPos> badSpots = new HashSet<>();
	/** Blocks it flew to but couldn't place: left for a while so it doesn't circle one spot. */
	private static final Map<BlockPos, Integer> skip = new HashMap<>();
	private static BlockPos goingFor;
	private static int arrivedAt = -1;
	/** How far it will go looking for a clean spot before giving up. */
	static final int MAX_SEARCH = 3000;

	private AutoBuild() {}

	static int takeoffs() {
		return pilot.takeoffs;
	}

	static String pilotDebug(MinecraftClient client) {
		return pilot.debug(client);
	}

	static boolean active() {
		return state != State.OFF && state != State.DONE;
	}

	static void start(MinecraftClient client) {
		if (client.player == null) return;
		badSpots.clear();
		skip.clear();
		start = client.player.getEntityPos();
		ticks = 0;
		Farm farm = TotemBuilder.farm;
		if (farm != null && !Commands.builtExceptVillager(client)) {
			// Already planned here: carry on building it.
			state = State.BUILD;
			TotemBuilder.printing = true;
			say(client, "Carrying on building the farm at " + farm.bed.toShortString() + ".", Formatting.GREEN);
		} else if (farm != null) {
			state = State.DONE;
			say(client, "The farm's already built. /totem villager says where the villager goes.", Formatting.GREEN);
		} else {
			state = State.SEARCH;
			searchAt = -100;
			say(client, "Looking for a clean spot over the ocean. Sit back: it flies there and builds the farm.", Formatting.AQUA);
		}
	}

	static void stop(MinecraftClient client, String why) {
		pilot.stop(client);
		state = State.OFF;
		status = "";
		if (why != null) say(client, why, Formatting.YELLOW);
	}

	static void tick(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		ClientWorld world = client.world;
		if (p == null || world == null || !active()) return;
		ticks++;
		pilot.tick(client);
		switch (state) {
			case SEARCH -> search(client, p, world);
			case TO_SPOT -> {
				status = "Flying to a clean spot " + (int) Math.hypot(spot.getX() + 0.5 - p.getX(), spot.getZ() + 0.5 - p.getZ()) + " m away";
				if (ticks % 40 == 0) {
					// Newly loaded land near it? Look again.
					SpotFinder.Result r = SpotFinder.find(world, p.getEntityPos(), p.getRotationVector());
					if (r.spot() == null || r.spot().getSquaredDistance(spot) > 64 * 64) {
						badSpots.add(spot);
						state = State.SEARCH;
						return;
					}
				}
				if (pilot.arrived(client)) {
					TotemBuilder.planAt(client, new BlockPos(spot.getX(), spot.getY() - FarmPlan.FLOOR_Y, spot.getZ()));
					state = State.CHECK;
				}
			}
			case CHECK -> {
				status = "Checking the spot for land";
				SiteCheck c = TotemBuilder.check;
				if (c == null || !c.finished) return;
				if (!c.bad.isEmpty() || c.unloaded > 0) {
					badSpots.add(spot);
					TotemBuilder.farm = null;
					Farm.save(client, null);
					state = State.SEARCH;
					return;
				}
				say(client, "Good spot: nothing raiders could spawn on within " + FarmPlan.SPAWN_SEARCH_RADIUS + " blocks. Building.", Formatting.GREEN);
				TotemBuilder.printing = true;
				lastPlaced = TotemBuilder.printer.placedThisSession;
				lastProgress = ticks;
				state = State.BUILD;
			}
			case BUILD -> build(client, p, world);
			case REFILL -> refill(client, p, world);
			default -> { }
		}
	}

	private static void search(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		if (start != null && p.getEntityPos().distanceTo(start) > MAX_SEARCH) {
			stop(client, "No clean spot within " + MAX_SEARCH + " blocks of where you started. Try somewhere further out to sea.");
			return;
		}
		if (ticks - searchAt < 20 && pilot.active() && !pilot.arrived(client)) return;
		searchAt = ticks;
		SpotFinder.Result r = SpotFinder.find(world, p.getEntityPos(), p.getRotationVector());
		TotemBuilder.LOGGER.info("[auto] search at {}: floor {}, land seen {}, nearest {}, spot {}, away {}", p.getBlockPos().toShortString(),
				SpotFinder.floorY(world, p.getBlockX(), p.getBlockZ()), r.landSeen(), (int) Math.min(99999, r.nearestLand()), r.spot(), r.away());
		if (r.spot() != null && !badSpots.contains(r.spot())) {
			spot = r.spot();
			pilot.go(client, new Vec3d(spot.getX() + 0.5, spot.getY() + 3, spot.getZ() + 0.5), null);
			state = State.TO_SPOT;
			return;
		}
		int floor = SpotFinder.floorY(world, p.getBlockX(), p.getBlockZ());
		Vec3d dest = p.getEntityPos().add(r.away().multiply(48));
		pilot.go(client, new Vec3d(dest.x, Math.max(floor + 6, p.getY()), dest.z), null);
		status = r.landSeen() > 0 ? "Searching: land " + (int) r.nearestLand() + " m away, heading off from it" : "Searching: waiting for the area to load";
	}

	static Box farmBox(Farm farm) {
		return new Box(farm.bed.add(-10, FarmPlan.FLOOR_Y - 2, -13).toCenterPos(), farm.bed.add(11, FarmPlan.PAD_Y + 7, 13).toCenterPos());
	}

	private static void build(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		Farm farm = TotemBuilder.farm;
		if (farm == null) {
			stop(client, null);
			return;
		}
		TotemBuilder.printing = true;
		if (TotemBuilder.printer.placedThisSession != lastPlaced) {
			lastPlaced = TotemBuilder.printer.placedThisSession;
			lastProgress = ticks;
		}
		skip.values().removeIf(t -> ticks - t > 20 * 30);
		// Still placing here, or on the way somewhere: let it be.
		if (ticks - lastProgress < 10) return;
		if (pilot.active() && !pilot.arrived(client)) return;
		if (goingFor != null && pilot.arrived(client)) {
			if (arrivedAt < 0) arrivedAt = ticks;
			if (ticks - arrivedAt < 25) return;
			// There, and nothing went in: something's wrong with that one for now.
			skip.put(goingFor, ticks);
		}
		goingFor = null;
		arrivedAt = -1;

		Map<BlockPos, BlockState> targets = Printer.targets(farm, false);
		boolean waterTime = Printer.waterTime(world, farm, targets);
		BlockPos nextWater = waterTime ? Printer.nextWater(world, farm) : null;
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		int left = 0;
		Vec3d me = p.getEyePos();
		for (Map.Entry<BlockPos, BlockState> e : targets.entrySet()) {
			BlockState want = e.getValue();
			if (Printer.need(want, world.getBlockState(e.getKey())) == Printer.Need.NOTHING) continue;
			left++;
			if (skip.containsKey(e.getKey())) continue;
			if (want.isOf(Blocks.WATER) ? !e.getKey().equals(nextWater) : !Printer.placeableNow(world, e.getKey(), want)) continue;
			double d = Vec3d.ofCenter(e.getKey()).squaredDistanceTo(me);
			if (d < bd) {
				bd = d;
				best = e.getKey();
			}
		}
		status = "Building: " + (targets.size() - left) + "/" + targets.size();
		if (left == 0) {
			pilot.stop(client);
			state = State.DONE;
			status = "";
			say(client, "Farm built! Now bring the villager: green box at " + farm.bed.add(FarmPlan.BED_FOOT).toShortString()
					+ ", in through the gap from the ledge on its north side. The printer seals it in once it's there.", Formatting.GREEN);
			return;
		}
		if (best == null) {
			if (!skip.isEmpty()) return;
			stop(client, "Stuck: nothing left that can be placed from here. " + TotemBuilder.printer.lastProblem);
			return;
		}
		BlockState want = targets.get(best);
		if (want.isOf(Blocks.WATER) && Printer.find(p.getInventory(), want) < 0) {
			if (p.getInventory().count(Items.BUCKET) > 0) {
				state = State.REFILL;
				return;
			}
			stop(client, "Out of water buckets, and no empty buckets to fill at the sea. Bring some, then /totem auto.");
			return;
		}
		if (Printer.find(p.getInventory(), want) < 0) {
			stop(client, "Out of " + Printer.itemName(want) + ". Fill up, then /totem auto to carry on (" + left + " blocks to go).");
			return;
		}
		BlockPos hover = hoverSpot(world, farm, best, want);
		if (hover == null) {
			skip.put(best, ticks);
			return;
		}
		goingFor = best;
		TotemBuilder.LOGGER.info("[auto] going for {} {} from {}, hover {}", best.subtract(farm.bed).toShortString(), want.getBlock(), p.getBlockPos().subtract(farm.bed).toShortString(), hover.subtract(farm.bed).toShortString());
		pilot.go(client, new Vec3d(hover.getX() + 0.5, hover.getY() + 0.3, hover.getZ() + 0.5), farmBox(farm));
	}

	/** Somewhere free to float two or three blocks from it (right over it, for water: a bucket pours where you look). */
	static BlockPos hoverSpot(ClientWorld world, Farm farm, BlockPos target, BlockState want) {
		if (want.isOf(Blocks.WATER)) {
			BlockPos up = target.up(2);
			return free(world, farm, up) && free(world, farm, up.up()) ? up : null;
		}
		// Somewhere with air under it too, so it never lands (landing ends the flight).
		for (int r = 2; r <= 4; r++)
			for (int dy = -1; dy <= 2; dy++)
				for (Direction d : Direction.Type.HORIZONTAL) {
					BlockPos c = target.offset(d, r).up(dy);
					if (free(world, farm, c) && free(world, farm, c.up()) && free(world, farm, c.down())) return c;
				}
		for (int dy = 2; dy <= 4; dy++) if (free(world, farm, target.up(dy)) && free(world, farm, target.up(dy + 1))) return target.up(dy);
		return null;
	}

	private static boolean free(ClientWorld world, Farm farm, BlockPos p) {
		return world.getBlockState(p).isAir() && !farm.plan.blocks().containsKey(p.subtract(farm.bed));
	}

	private static BlockPos seaSpot;

	private static void refill(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		Farm farm = TotemBuilder.farm;
		int need = Printer.waterStillToPour(world, farm);
		int full = p.getInventory().count(Items.WATER_BUCKET), empty = p.getInventory().count(Items.BUCKET);
		status = "Filling buckets at the sea (" + full + " of " + need + ")";
		if (full >= need || empty == 0) {
			seaSpot = null;
			state = State.BUILD;
			lastProgress = ticks;
			return;
		}
		if (seaSpot == null) {
			// Open sea just outside the farm.
			int floor = farm.bed.getY() + FarmPlan.FLOOR_Y;
			outer:
			for (int r = 14; r <= 40; r += 2)
				for (int a = 0; a < 16; a++) {
					int x = farm.bed.getX() + (int) Math.round(r * Math.cos(a * Math.PI / 8)), z = farm.bed.getZ() + (int) Math.round(r * Math.sin(a * Math.PI / 8));
					if (SpotFinder.openWater(world, x, z, floor) && !farm.plan.inFootprint(x - farm.bed.getX(), z - farm.bed.getZ())) {
						seaSpot = new BlockPos(x, floor, z);
						break outer;
					}
				}
			if (seaSpot == null) {
				stop(client, "Out of water buckets, and no open sea near the farm to fill the empty ones.");
				return;
			}
			pilot.go(client, new Vec3d(seaSpot.getX() + 0.5, seaSpot.getY() + 1.2, seaSpot.getZ() + 0.5), farmBox(farm));
		}
		// The printer fills the buckets by itself once the sea is in reach.
	}

	static void say(MinecraftClient client, String text, Formatting f) {
		TotemBuilder.say(client, Text.literal(text).formatted(f));
	}
}
