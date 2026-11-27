package com.greysworkshop.totembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * A copy of the blocks round the farm, taken on the game's thread, so the walking auto-builder can plan on another
 * thread without the game stuttering. Outside the copy: air above the sea, the sea's water at its level and under.
 */
final class Snapshot implements BlockView {
	private final int x0, y0, z0, w, h, d, seaTop, bottom, height;
	private final BlockState[] states;

	Snapshot(BlockView world, Farm farm, int bottomY, int heightY) {
		x0 = farm.bed.getX() - 16;
		z0 = farm.bed.getZ() - 16;
		y0 = farm.bed.getY() + FarmPlan.FLOOR_Y - 4;
		w = 33;
		d = 33;
		h = FarmPlan.PAD_Y - FarmPlan.FLOOR_Y + 18;
		seaTop = farm.bed.getY() + FarmPlan.FLOOR_Y - 1;
		bottom = bottomY;
		height = heightY;
		states = new BlockState[w * h * d];
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = 0; x < w; x++)
			for (int y = 0; y < h; y++)
				for (int z = 0; z < d; z++) states[(x * h + y) * d + z] = world.getBlockState(m.set(x0 + x, y0 + y, z0 + z));
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		int x = pos.getX() - x0, y = pos.getY() - y0, z = pos.getZ() - z0;
		if (x >= 0 && x < w && y >= 0 && y < h && z >= 0 && z < d) return states[(x * h + y) * d + z];
		return pos.getY() <= seaTop ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public int getHeight() {
		return height;
	}

	@Override
	public int getBottomY() {
		return bottom;
	}
}
