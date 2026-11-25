package com.greysworkshop.totembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Is the spot any good? The game picks where raiders spawn from the top block of each column within about 107 blocks
 * of the bed, if it's within 96 blocks of the bed's height and a ravager could stand on it. Anything like that other
 * than the farm's pad (an island, a beach, a boat dock, someone's build, a snowy iceberg) would steal the spawns, and
 * the raiders would land somewhere they don't fall. This looks at every such column the game has sent us, and says
 * which aren't loaded yet.
 */
public final class SiteCheck {
	public final List<BlockPos> bad = new ArrayList<>();
	public int checked, unloaded;
	public boolean finished;
	private final BlockPos bed;
	private final FarmPlan plan;
	private int nextX;

	public SiteCheck(BlockPos bed, FarmPlan plan) {
		this.bed = bed;
		this.plan = plan;
		nextX = -FarmPlan.SPAWN_SEARCH_RADIUS;
	}

	/** Checks a few rows of columns; call each tick until {@link #finished}. */
	public void step(ClientWorld world, int rows) {
		int r = FarmPlan.SPAWN_SEARCH_RADIUS;
		for (int n = 0; n < rows && nextX <= r; n++, nextX++) {
			for (int dz = -r; dz <= r; dz++) {
				if (nextX * nextX + dz * dz > r * r) continue;
				int x = bed.getX() + nextX, z = bed.getZ() + dz;
				WorldChunk chunk = world.getChunkManager().getWorldChunk(x >> 4, z >> 4);
				if (chunk == null || chunk.isEmpty()) {
					unloaded++;
					continue;
				}
				checked++;
				BlockPos top = topBlock(world, chunk, x, z);
				if (top == null) continue;
				BlockPos rel = top.subtract(bed);
				// The farm's own blocks are planned so that only the pad counts.
				if (plan.inFootprint(rel.getX(), rel.getZ())) continue;
				if (Math.abs(top.getY() + 1 - bed.getY()) > FarmPlan.SPAWN_SEARCH_HEIGHT) continue;
				if (spawnable(world, top)) {
					if (bad.size() < 200) bad.add(top);
				}
			}
		}
		if (nextX > r) finished = true;
	}

	/** The highest block in the column that isn't air, as the game's WORLD_SURFACE height map sees it. */
	static BlockPos topBlock(ClientWorld world, WorldChunk chunk, int x, int z) {
		BlockPos.Mutable m = new BlockPos.Mutable(x, world.getTopYInclusive(), z);
		for (; m.getY() >= world.getBottomY(); m.move(0, -1, 0)) {
			if (!chunk.getBlockState(m).isAir()) return m.toImmutable();
		}
		return null;
	}

	/** Would the game let a raid spawn on top of this block? (The ravager rule, as {@code Raid} uses it.) */
	static boolean spawnable(ClientWorld world, BlockPos top) {
		BlockState state = world.getBlockState(top);
		BlockPos above = top.up();
		if (state.isOf(Blocks.SNOW) && world.getBlockState(above).isAir()) return true;
		if (!state.getFluidState().isEmpty() && !state.isSideSolidFullSquare(world, top, net.minecraft.util.math.Direction.UP)) return false;
		return state.allowsSpawning(world, top, EntityType.RAVAGER);
	}

	public double progress() {
		int r = FarmPlan.SPAWN_SEARCH_RADIUS;
		return Math.min(1, (nextX + r) / (2.0 * r + 1));
	}

	/** The nearest problem, for the chat message. */
	public BlockPos nearest() {
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (BlockPos p : bad) {
			double d = p.getSquaredDistance(bed);
			if (d < bd) {
				bd = d;
				best = p;
			}
		}
		return best;
	}

	static ChunkPos chunkOf(BlockPos p) {
		return new ChunkPos(p);
	}
}
