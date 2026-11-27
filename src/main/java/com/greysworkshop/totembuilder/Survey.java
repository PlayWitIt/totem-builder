package com.greysworkshop.totembuilder;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the build stands, worked out a few times a second and shared by the screen, the HUD and the ghost blocks:
 * every block still to place (and whether it can go in now), wrong blocks in the farm's cells, stray blocks inside
 * the farm that don't belong (old scaffolding, a misplaced block), how far along each part is, and how many of each
 * item are still needed against how many you carry.
 */
final class Survey {
	record Cell(BlockPos pos, BlockState want, boolean now) {}

	/** A block in the farm that has to go: a wrong one in a farm cell, or one where the farm needs air. */
	record Extra(BlockPos pos, BlockState have, boolean scaffold, boolean wrong) {}

	/** One part of the farm, in build order. */
	enum Part {
		FLOOR("Floor and booth"), ROOM("Villager room"), COLUMNS("Support columns"), DROP("Drop walls"), PAD("Spawn pad and lake"),
		WATER("Water (goes in last)"), WALLS("Walls round the pad"), ISLANDS("Islands"), DOOR("Villager's door (after it's in)");

		final String label;

		Part(String label) {
			this.label = label;
		}
	}

	final int total, done;
	final List<Cell> missing = new ArrayList<>();
	final List<Extra> extras = new ArrayList<>();
	final Map<Part, int[]> parts = new LinkedHashMap<>();
	/** Item → still to place, in the order they matter. */
	final Map<Item, Integer> need = new LinkedHashMap<>();
	final Map<Item, Integer> have = new LinkedHashMap<>();
	final boolean waterTime, sealed;

	static Survey latest;
	private static int age;

	private Survey(BlockView world, Farm farm, PlayerInventory inv, Set<BlockPos> scaffold) {
		sealed = farm.sealed || world instanceof net.minecraft.client.world.ClientWorld cw && Printer.villagerInside(cw, farm);
		Map<BlockPos, BlockState> targets = Printer.targets(farm, sealed);
		for (Part p : Part.values()) parts.put(p, new int[2]);
		waterTime = Printer.waterTime(world, farm, targets);
		int t = 0, d = 0;
		for (Map.Entry<BlockPos, BlockState> e : targets.entrySet()) {
			BlockPos pos = e.getKey();
			BlockState want = e.getValue();
			// The bed's head comes with its foot: count the bed once.
			boolean bedHead = want.getBlock() instanceof BedBlock && want.get(BedBlock.PART) == BedPart.HEAD;
			BlockState have = world.getBlockState(pos);
			Printer.Need n = Printer.need(want, have);
			Part part = part(pos.subtract(farm.bed), want);
			int[] c = parts.get(part);
			if (!bedHead) {
				t++;
				c[1]++;
			}
			if (n == Printer.Need.NOTHING) {
				if (!bedHead) {
					d++;
					c[0]++;
				}
				continue;
			}
			if (n == Printer.Need.WRONG) extras.add(new Extra(pos, have, scaffold.contains(pos), true));
			boolean now = want.isOf(Blocks.WATER) ? waterTime && Printer.clickable(world, pos.down()) : Printer.placeableNow(world, pos, want);
			missing.add(new Cell(pos, want, now));
			if (!bedHead && !(Printer.needsOpening(want, have))) need.merge(item(want), 1, Integer::sum);
		}
		if (!sealed) parts.remove(Part.DOOR);
		total = t;
		done = d;
		// Inside the farm, everything that isn't the farm has to be air (the sea's water doesn't count).
		BlockPos lo = farm.bed.add(-9, FarmPlan.FLOOR_Y, -9), hi = farm.bed.add(9, FarmPlan.PAD_Y + 5, 9);
		for (BlockPos p : BlockPos.iterate(lo, hi)) {
			if (targets.containsKey(p)) continue;
			BlockState s = world.getBlockState(p);
			if (s.isAir() || !s.getFluidState().isEmpty() && s.getCollisionShape(world, p).isEmpty() || s.isOf(Blocks.BUBBLE_COLUMN)) continue;
			extras.add(new Extra(p.toImmutable(), s, scaffold.contains(p), false));
		}
		// Scaffolding left out at sea level, just outside the box.
		for (BlockPos p : scaffold) {
			if (p.getX() >= lo.getX() && p.getX() <= hi.getX() && p.getZ() >= lo.getZ() && p.getZ() <= hi.getZ() && p.getY() >= lo.getY() && p.getY() <= hi.getY()) continue;
			BlockState s = world.getBlockState(p);
			if (!s.isAir() && s.getFluidState().isEmpty()) extras.add(new Extra(p, s, true, false));
		}
		if (inv != null) for (Item i : need.keySet()) have.put(i, count(inv, i));
	}

	/** Refreshes {@link #latest} every few ticks while you're near the farm. */
	static void tick(MinecraftClient client) {
		Farm farm = TotemBuilder.farm;
		if (farm == null || client.player == null || client.world == null
				|| client.player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(farm.bed)) > 320 * 320) {
			latest = null;
			return;
		}
		if (latest != null && ++age < 10) return;
		age = 0;
		latest = of(client);
	}

	static Survey of(MinecraftClient client) {
		Farm farm = TotemBuilder.farm;
		if (farm == null || client.world == null) return null;
		return new Survey(client.world, farm, client.player == null ? null : client.player.getInventory(), farm.scaffold);
	}

	/** For the planner's tests: the same count over any world. */
	static Survey of(BlockView world, Farm farm) {
		return new Survey(world, farm, null, farm.scaffold);
	}

	int left() {
		return total - done;
	}

	static Item item(BlockState want) {
		if (want.getBlock() instanceof BedBlock) return Items.RED_BED;
		if (want.isOf(Blocks.WATER)) return Items.WATER_BUCKET;
		return want.getBlock().asItem();
	}

	/** How many of this you carry (any colour of bed counts as a bed). */
	static int count(PlayerInventory inv, Item item) {
		int n = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (item == Items.RED_BED ? s.isIn(ItemTags.BEDS) : s.isOf(item)) n += s.getCount();
		}
		return n;
	}

	/** Which part of the farm a cell belongs to (relative to the bed). */
	static Part part(BlockPos r, BlockState want) {
		int x = r.getX(), y = r.getY(), z = r.getZ(), f = FarmPlan.FLOOR_Y, p = FarmPlan.PAD_Y;
		for (BlockPos e : FarmPlan.ENTRANCE) if (e.equals(r)) return Part.DOOR;
		if (want.isOf(Blocks.WATER)) return Part.WATER;
		if (y >= p - 1) {
			boolean ring = (x == -5 || x == 4) && z >= -5 && z <= 4 || (z == -5 || z == 4) && x >= -5 && x <= 4;
			if (ring) return Part.WALLS;
			if (x >= -4 && x <= 3 && z >= -4 && z <= 3 && y <= p) return Part.PAD;
			return Part.ISLANDS;
		}
		if ((x == 1 && z >= 1 && z <= 5 || z == 1 && x >= 2 && x <= 5) && y >= f + 3) return Part.DROP;
		if (y >= -2 && y <= 3 && x >= -2 && x <= 1 && z >= -2 && z <= 1) return Part.ROOM;
		if (y <= f + 3) return Part.FLOOR;
		return Part.COLUMNS;
	}

	/** The block to go for next by hand: the nearest that can go in now, else the nearest of all. */
	Cell next(Vec3d eye) {
		Cell best = null;
		double bd = Double.MAX_VALUE;
		for (Cell c : missing) {
			boolean entrance = false;
			for (BlockPos p : FarmPlan.ENTRANCE) entrance |= TotemBuilder.farm != null && TotemBuilder.farm.bed.add(p).equals(c.pos());
			if (entrance && !sealed) continue;
			if (c.want().isOf(Blocks.WATER) && !c.now()) continue;
			double d = Vec3d.ofCenter(c.pos()).squaredDistanceTo(eye) + (c.now() ? 0 : 1e6);
			if (d < bd) {
				bd = d;
				best = c;
			}
		}
		return best;
	}
}
