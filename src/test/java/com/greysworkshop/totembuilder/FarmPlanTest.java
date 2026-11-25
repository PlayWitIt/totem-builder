package com.greysworkshop.totembuilder;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class FarmPlanTest {
	@BeforeAll
	static void boot() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	@Test
	void countsAndSpawnSafety() {
		FarmPlan plan = new FarmPlan("main");
		Map<String, Integer> n = new TreeMap<>();
		for (BlockState s : plan.blocks().values()) n.merge(s.getBlock().getTranslationKey(), 1, Integer::sum);
		System.out.println("blocks: " + plan.blocks().size() + " " + n);
		// The bed head is the centre and the pad covers the last search ring.
		assertTrue(plan.blocks().get(BlockPos.ORIGIN).getBlock() instanceof net.minecraft.block.BedBlock);
		for (int x = -3; x <= 0; x++) for (int z = -3; z <= 0; z++) assertTrue(FarmPlan.padCell(x, z));
		// Nothing above the pad: each pad cell is the top of its column.
		for (BlockPos p : plan.blocks().keySet())
			if (FarmPlan.padCell(p.getX(), p.getZ())) assertTrue(p.getY() <= FarmPlan.PAD_Y, "above the pad at " + p);
		// The fall is lethal for a ravager (100 health): from the pad top to the floor's slab top.
		double fall = (FarmPlan.PAD_Y + 1) - (FarmPlan.FLOOR_Y + 0.5);
		assertTrue(Math.floor(fall - 3) >= 100, "fall " + fall);
		// The game only looks 96 blocks above the bed.
		assertTrue(FarmPlan.PAD_Y + 1 <= FarmPlan.SPAWN_SEARCH_HEIGHT);
	}

	@Test
	void everyBlockHasAPathFromTheFirst() {
		// Every block must be reachable by placing against a neighbour, starting from any one block.
		FarmPlan plan = new FarmPlan("main");
		java.util.Set<BlockPos> all = new java.util.HashSet<>();
		for (Map.Entry<BlockPos, BlockState> e : plan.blocks().entrySet()) if (!e.getValue().isOf(net.minecraft.block.Blocks.WATER)) all.add(e.getKey());
		java.util.List<java.util.Set<BlockPos>> parts = new java.util.ArrayList<>();
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		for (BlockPos start : all) {
			if (seen.contains(start)) continue;
			java.util.Set<BlockPos> part = new java.util.HashSet<>();
			java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>(java.util.List.of(start));
			seen.add(start);
			while (!q.isEmpty()) {
				BlockPos p = q.poll();
				part.add(p);
				for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
					BlockPos n = p.offset(d);
					if (all.contains(n) && seen.add(n)) q.add(n);
				}
			}
			parts.add(part);
		}
		for (java.util.Set<BlockPos> part : parts) System.out.println("part of " + part.size() + ", e.g. " + part.iterator().next());
		assertEquals(1, parts.size(), "the farm is in " + parts.size() + " separate pieces");
	}
}
