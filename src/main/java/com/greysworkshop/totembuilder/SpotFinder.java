package com.greysworkshop.totembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds somewhere over open ocean with nothing a raider could spawn on within the raid's spawn search (107 blocks),
 * among the chunks the game has sent us. Each column is one height-map lookup, so the whole loaded area takes a few
 * milliseconds. With no clean spot in sight it gives the way to go: straight away from the land it can see.
 */
final class SpotFinder {
	private SpotFinder() {}

	/** The height the farm's floor would sit at here: on the water's surface, or the world's sea level. */
	static int floorY(ClientWorld world, int x, int z) {
		WorldChunk chunk = world.getChunkManager().getWorldChunk(x >> 4, z >> 4);
		if (chunk == null || chunk.isEmpty()) return world.getSeaLevel();
		int top = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
		BlockPos p = new BlockPos(x, top, z);
		if (world.getFluidState(p).isIn(FluidTags.WATER)) return top + 1;
		return world.getSeaLevel();
	}

	/** Is this column open water at the farm's floor height (no land, no ice, nothing built)? */
	static boolean openWater(ClientWorld world, int x, int z, int floor) {
		WorldChunk chunk = world.getChunkManager().getWorldChunk(x >> 4, z >> 4);
		if (chunk == null || chunk.isEmpty()) return false;
		int top = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
		return top == floor - 1 && world.getFluidState(new BlockPos(x, top, z)).isIn(FluidTags.WATER);
	}

	/** Columns a raid could spawn on for a bed at {@code bedY}, in every loaded chunk round {@code centre}. */
	static List<BlockPos> land(ClientWorld world, BlockPos centre, int radius, int bedY) {
		List<BlockPos> out = new ArrayList<>();
		int cx = centre.getX() >> 4, cz = centre.getZ() >> 4, cr = (radius >> 4) + 1;
		for (int i = cx - cr; i <= cx + cr; i++)
			for (int j = cz - cr; j <= cz + cr; j++) {
				WorldChunk chunk = world.getChunkManager().getWorldChunk(i, j);
				if (chunk == null || chunk.isEmpty()) continue;
				for (int x = 0; x < 16; x++)
					for (int z = 0; z < 16; z++) {
						int top = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z);
						if (Math.abs(top + 1 - bedY) > FarmPlan.SPAWN_SEARCH_HEIGHT) continue;
						BlockPos p = new BlockPos((i << 4) + x, top, (j << 4) + z);
						BlockState s = chunk.getBlockState(p);
						if (s.isAir()) continue;
						if (SiteCheck.spawnable(world, p)) out.add(p);
					}
			}
		return out;
	}

	/** Are all chunks within the spawn search round this column loaded? */
	static boolean fullyLoaded(ClientWorld world, int x, int z) {
		int r = FarmPlan.SPAWN_SEARCH_RADIUS;
		for (int i = (x - r) >> 4; i <= (x + r) >> 4; i++)
			for (int j = (z - r) >> 4; j <= (z + r) >> 4; j++) {
				// Only chunks that touch the circle.
				int nx = Math.max(i << 4, Math.min(x, (i << 4) + 15)), nz = Math.max(j << 4, Math.min(z, (j << 4) + 15));
				if ((nx - x) * (nx - x) + (nz - z) * (nz - z) > r * r) continue;
				WorldChunk c = world.getChunkManager().getWorldChunk(i, j);
				if (c == null || c.isEmpty()) return false;
			}
		return true;
	}

	record Result(BlockPos spot, Vec3d away, int landSeen, double nearestLand) {}

	/**
	 * The nearest good spot to {@code me}, if there's one in what's loaded; otherwise, which way to go (away from the
	 * land in sight, or straight on if there's none but the area isn't loaded yet).
	 */
	static Result find(ClientWorld world, Vec3d me, Vec3d heading) {
		BlockPos here = BlockPos.ofFloored(me);
		int floor = floorY(world, here.getX(), here.getZ());
		int bedY = floor - FarmPlan.FLOOR_Y;
		int view = 16 * 16;
		List<BlockPos> land = land(world, here, view, bedY);
		double nearest = Double.MAX_VALUE;
		for (BlockPos b : land) nearest = Math.min(nearest, Math.hypot(b.getX() - me.x, b.getZ() - me.z));
		int r = FarmPlan.SPAWN_SEARCH_RADIUS + 3;
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (int dx = -view; dx <= view; dx += 8)
			for (int dz = -view; dz <= view; dz += 8) {
				int x = here.getX() + dx, z = here.getZ() + dz;
				double d = Math.hypot(dx, dz);
				if (d >= bd) continue;
				if (!openWater(world, x, z, floor)) continue;
				boolean clean = true;
				for (BlockPos b : land) {
					double ex = b.getX() - x, ez = b.getZ() - z;
					if (ex * ex + ez * ez <= r * r) {
						clean = false;
						break;
					}
				}
				if (!clean || !fullyLoaded(world, x, z)) continue;
				bd = d;
				best = new BlockPos(x, floor, z);
			}
		Vec3d away = Vec3d.ZERO;
		for (BlockPos b : land) {
			Vec3d v = new Vec3d(me.x - b.getX(), 0, me.z - b.getZ());
			double l = v.length();
			if (l > 1) away = away.add(v.multiply(1 / (l * l)));
		}
		if (away.lengthSquared() < 1e-9) away = new Vec3d(heading.x, 0, heading.z);
		if (away.lengthSquared() < 1e-9) away = new Vec3d(1, 0, 0);
		return new Result(best, away.normalize(), land.size(), nearest);
	}

	static boolean loaded(ClientWorld world, ChunkPos c) {
		WorldChunk w = world.getChunkManager().getWorldChunk(c.x, c.z);
		return w != null && !w.isEmpty();
	}
}
