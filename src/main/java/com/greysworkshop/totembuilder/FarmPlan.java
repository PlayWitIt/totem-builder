package com.greysworkshop.totembuilder;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The farm, block by block, relative to the villager's bed head at (0, 0, 0).
 *
 * <p>How it works (Java 1.21.11, read from the game's code and tested with real raids): a raid's centre is the bed
 * the villager claimed. Before each wave the game looks for a spot to spawn the raiders, starting about 100 blocks
 * out and coming inwards. Over the ocean nothing out there can be spawned on, so the search reaches its last ring,
 * which is exactly the 4 × 4 square at x, z = -3..0 from the bed: the spawn pad, 95 blocks up (the game only looks up
 * to 96 blocks above the centre). Raiders don't walk off a ledge by themselves, so the pad's open side is a water
 * channel one block down with a little cobble island across it: they head for the island, and the current carries
 * them down the channel and off its end, between two signs that hold the water back. They fall about 105 blocks onto
 * the slab floor, which kills every one of them, ravagers too (100 health, 102 damage). Totems and the captain's
 * ominous bottle drop whatever killed them. Every other block that could be stood on is glass, a slab, water or
 * covered by the pad, so the raiders can't spawn anywhere else.
 */
public final class FarmPlan {
	/** The pad's blocks, relative to the bed. */
	public static final int PAD_Y = 94;
	/** The slab floor everything falls onto, relative to the bed. */
	public static final int FLOOR_Y = -11;
	/** The game's search for a spawn spot covers about this far from the bed. */
	public static final int SPAWN_SEARCH_RADIUS = 107;
	/** How far above or below the bed a surface still counts for that search. */
	public static final int SPAWN_SEARCH_HEIGHT = 96;

	/** The villager's two-block room, inside: where the bed is and where it stands. */
	public static final BlockPos BED_HEAD = BlockPos.ORIGIN;
	public static final BlockPos BED_FOOT = new BlockPos(-1, 0, 0);
	/** Left open until a villager is inside, then filled. */
	public static final BlockPos[] ENTRANCE = {new BlockPos(-1, 0, -1), new BlockPos(-1, 1, -1)};
	/** Where you stand while the raids run: in the booth beside the floor. */
	public static final BlockPos STAND = new BlockPos(-2, FLOOR_Y + 1, 4);

	private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
	public final String design;

	public FarmPlan(String design) {
		this.design = design;
		build();
	}

	public Map<BlockPos, BlockState> blocks() {
		return blocks;
	}

	private void put(int x, int y, int z, BlockState state) {
		blocks.put(new BlockPos(x, y, z), state);
	}

	private void box(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) put(x, y, z, state);
	}

	static final BlockState COBBLE = Blocks.COBBLESTONE.getDefaultState();
	static final BlockState GLASS = Blocks.GLASS.getDefaultState();
	static final BlockState SLAB = Blocks.COBBLESTONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM);
	static final BlockState TOP_SLAB = Blocks.COBBLESTONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP);
	static final BlockState WATER = Blocks.WATER.getDefaultState();
	private static final BlockState AIR = Blocks.AIR.getDefaultState();

	static BlockState sign(Direction facing) {
		return Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, facing);
	}

	/** The pad's cells: the whole 4 × 4 spawn square. */
	static boolean padCell(int x, int z) {
		return x >= -3 && x <= 0 && z >= -3 && z <= 0;
	}

	/** The four open fence gates over the drop: they hold the water back, and nothing bumps into them. */
	static boolean dropCell(int x, int z) {
		return x >= 2 && x <= 3 && z >= 2 && z <= 3;
	}

	/** The lake's cells beside the drop: two deep, so nobody stands on the edge (they float and the current takes them). */
	static boolean deepCell(int x, int z) {
		return x >= 1 && z >= 1 && !dropCell(x, z) && (x <= 1 || z <= 1) && x <= 3 && z <= 3;
	}

	private void build() {
		int p = PAD_Y;
		// Round the pad's two far sides a walkway of top slabs (somewhere to stand, never somewhere to go), and on its
		// two near sides a lake one block down, flowing from sources at its two ends towards the drop in the corner. The
		// walls are two blocks from the pad, so a ravager spawning there never touches one.
		for (int x = -4; x <= 3; x++)
			for (int z = -4; z <= 3; z++) {
				if (padCell(x, z) || x == -4 && z <= 0 || z == -4 && x <= 0) put(x, p, z, TOP_SLAB);
				else if (dropCell(x, z)) {
					put(x, p, z, gate());
					put(x, p - 1, z, gate());
				} else if (deepCell(x, z)) {
					// The floor here is the top of the drop's glass walls, one lower.
				} else {
					put(x, p - 1, z, COBBLE);
					// A row of three sources across each arm's far end. All six are poured: nothing relies on water
					// making new sources by itself (a server can turn that off).
					if (x == -4 || z == -4) put(x, p, z, WATER);
				}
			}
		box(-5, p - 1, -5, -5, p + 4, 4, GLASS);
		box(-5, p - 1, -5, 4, p + 4, -5, GLASS);
		box(4, p - 1, -5, 4, p + 4, 4, GLASS);
		box(-5, p - 1, 4, 4, p + 4, 4, GLASS);
		// Falling raiders steer towards the bed's column: two glass walls down the drop keep them over the floor.
		box(1, FLOOR_Y + 3, 1, 1, p - 2, 5, GLASS);
		box(2, FLOOR_Y + 3, 1, 5, p - 2, 1, GLASS);
		// The island the raiders head for, past the drop and out of reach, on a glass beam from the wall.
		// Its top is four above the water: as high as a raider will aim, and too high for even a ravager to reach.
		box(4, p + 3, 5, 5, p + 3, 5, GLASS);
		box(6, p + 3, 6, 8, p + 3, 8, COBBLE);
		box(6, p + 3, 5, 6, p + 3, 5, GLASS);
		// And one the opposite way, for a raider right over the bed: whichever way it faces, an island is ahead. One
		// higher, so only raiders on the pad can aim at it, never those already in the water on their way out.
		box(-6, p + 4, -5, -5, p + 4, -5, GLASS);
		box(-6, p + 4, -6, -6, p + 4, -6, GLASS);
		box(-8, p + 4, -8, -6, p + 4, -6, COBBLE);

		// The villager's room: the bed and the space above it, walled in cobble (no corners, so the drop is clear).
		put(-1, -1, 0, COBBLE);
		put(0, -1, 0, COBBLE);
		put(-1, 3, 0, COBBLE);
		put(0, 3, 0, COBBLE);
		box(-2, 0, 0, -2, 3, 0, COBBLE);
		box(1, 0, 0, 1, 3, 0, COBBLE);
		box(-1, -1, -1, 0, 3, -1, COBBLE);
		box(-1, 0, 1, 0, 3, 1, COBBLE);
		put(-1, 0, 0, Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.EAST).with(BedBlock.PART, BedPart.FOOT));
		put(0, 0, 0, Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.EAST).with(BedBlock.PART, BedPart.HEAD));
		// A ledge by the entrance to bring the villager in from.
		box(-1, -1, -2, 0, -1, -2, COBBLE);
		// A column from the room up to the pad, so there's always something to place against.
		box(-1, 4, 0, -1, p - 1, 0, COBBLE);

		// The floor everything lands on, with a glass rim so the drops stay on it.
		int f = FLOOR_Y;
		box(1, f, 1, 7, f, 7, SLAB);
		box(0, f, 0, 8, f + 2, 0, GLASS);
		box(0, f, 8, 8, f + 2, 8, GLASS);
		box(8, f, 0, 8, f + 2, 8, GLASS);
		box(0, f, 0, 0, f + 2, 8, GLASS);
		// The booth you stand in during raids, beside the floor, with a door onto it.
		box(-5, f, 1, 0, f + 3, 7, GLASS);
		box(-4, f, 2, -1, f, 6, SLAB);
		box(-4, f + 1, 2, -1, f + 2, 6, AIR);
		box(0, f + 1, 4, 0, f + 2, 4, AIR);
		// Up from the booth to the room.
		box(-1, f + 4, 1, -1, -1, 1, GLASS);

		blocks.entrySet().removeIf(e -> e.getValue().isAir());
		for (BlockPos e : ENTRANCE) blocks.remove(e);
		// Glass only matters where it's the top of a column (nothing can spawn on glass): everywhere else it's cobble,
		// which is easier to come by. The booth stays glass so you can see out.
		Map<Long, Integer> top = new java.util.HashMap<>();
		for (BlockPos q : blocks.keySet()) top.merge(ChunkPos.toLong(q.getX(), q.getZ()), q.getY(), Math::max);
		for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
			BlockPos q = e.getKey();
			boolean booth = q.getX() >= -5 && q.getX() <= 0 && q.getZ() >= 1 && q.getZ() <= 7 && q.getY() <= FLOOR_Y + 3;
			if (e.getValue() == GLASS && !booth && q.getY() < top.get(ChunkPos.toLong(q.getX(), q.getZ()))) e.setValue(COBBLE);
		}
	}

	static BlockState gate() {
		return Blocks.OAK_FENCE_GATE.getDefaultState().with(net.minecraft.block.FenceGateBlock.OPEN, true);
	}

	private final Set<Long> footprint = new HashSet<>();

	/** Does the farm have a block in this column (relative to the bed)? */
	public boolean inFootprint(int x, int z) {
		if (footprint.isEmpty()) for (BlockPos p : blocks.keySet()) footprint.add(ChunkPos.toLong(p.getX(), p.getZ()));
		return footprint.contains(ChunkPos.toLong(x, z));
	}
}
