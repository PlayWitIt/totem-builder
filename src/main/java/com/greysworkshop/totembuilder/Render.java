package com.greysworkshop.totembuilder;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.Map;

/**
 * In the world: each missing block nearby as a thin outline in its colour (grey cobble, pale blue glass, brown
 * trapdoors, red bed), wrong blocks in thick red, the villager's spot as a green box with a green beam above it you
 * can see from far off, and spots the land check found in red.
 */
final class Render {
	private Render() {}

	private record Ghost(int x, int y, int z, double h, int rgb, boolean wrong) {}

	private static java.util.List<Ghost> ghosts = java.util.List.of();
	/** Nearest first, and no more than this: drawing every missing block at once costs a weak graphics chip its frames. */
	private static final int MAX = 250;

	/** Refreshes what to draw, four times a second. */
	static void tick(MinecraftClient client) {
		Farm farm = TotemBuilder.farm;
		if (farm == null || client.player == null || client.world == null) {
			ghosts = java.util.List.of();
			return;
		}
		if (client.world.getTime() % 5 != 0) return;
		Vec3d me = client.player.getEntityPos();
		java.util.List<Ghost> list = new java.util.ArrayList<>();
		java.util.List<double[]> order = new java.util.ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> t : Printer.targets(farm, true).entrySet()) {
			BlockPos p = t.getKey();
			double d = Vec3d.ofCenter(p).squaredDistanceTo(me);
			if (d > 32 * 32) continue;
			Printer.Need need = Printer.need(t.getValue(), client.world.getBlockState(p));
			if (need == Printer.Need.NOTHING) continue;
			double h = t.getValue().getBlock() instanceof SlabBlock ? 0.5 : t.getValue().getBlock() instanceof BedBlock ? 0.5625 : 1;
			list.add(new Ghost(p.getX(), p.getY(), p.getZ(), h, color(t.getValue()), need == Printer.Need.WRONG));
			order.add(new double[]{d, list.size() - 1});
		}
		if (list.size() > MAX) {
			order.sort((a, b) -> Double.compare(a[0], b[0]));
			java.util.List<Ghost> near = new java.util.ArrayList<>();
			for (int i = 0; i < MAX; i++) near.add(list.get((int) order.get(i)[1]));
			list = near;
		}
		ghosts = list;
	}

	static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		Farm farm = TotemBuilder.farm;
		if (client.world == null || client.player == null || farm == null) return;
		MatrixStack matrices = context.matrices();
		if (matrices == null) return;
		Vec3d cam = client.gameRenderer.getCamera().getCameraPos();
		if (cam.squaredDistanceTo(Vec3d.ofCenter(farm.bed)) > 300 * 300) return;
		VertexConsumerProvider consumers = context.consumers();
		VertexConsumerProvider.Immediate own = null;
		if (consumers == null) {
			own = client.getBufferBuilders().getEntityVertexConsumers();
			consumers = own;
		}
		VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());
		MatrixStack.Entry e = matrices.peek();
		for (Ghost g : ghosts) {
			if (g.wrong) box(lines, e, g.x - cam.x, g.y - cam.y, g.z - cam.z, 1, 1, 1, 0xFFFF2020, 3f);
			else box(lines, e, g.x + 0.08 - cam.x, g.y + 0.08 - cam.y, g.z + 0.08 - cam.z, 0.84, g.h - 0.16, 0.84, g.rgb, 1.5f);
		}
		// The villager's spot.
		if (!farm.sealed) {
			BlockPos foot = farm.bed.add(FarmPlan.BED_FOOT);
			box(lines, e, foot.getX() - cam.x, foot.getY() - cam.y, foot.getZ() - cam.z, 2, 2, 1, 0xFF30FF50, 4f);
			double bx = foot.getX() + 1 - cam.x, bz = foot.getZ() + 0.5 - cam.z;
			line(lines, e, bx, foot.getY() + 2 - cam.y, bz, bx, foot.getY() + 40 - cam.y, bz, 0xFF30FF50, 4f);
			for (BlockPos d : FarmPlan.ENTRANCE) {
				BlockPos q = farm.bed.add(d);
				box(lines, e, q.getX() + 0.02 - cam.x, q.getY() + 0.02 - cam.y, q.getZ() + 0.02 - cam.z, 0.96, 0.96, 0.96, 0xFFFFE040, 2.5f);
			}
		}
		SiteCheck c = TotemBuilder.check;
		if (c != null) {
			for (BlockPos p : c.bad) {
				box(lines, e, p.getX() - cam.x, p.getY() + 1 - cam.y, p.getZ() - cam.z, 1, 0.05, 1, 0xFFFF2020, 3f);
				line(lines, e, p.getX() + 0.5 - cam.x, p.getY() + 1 - cam.y, p.getZ() + 0.5 - cam.z, p.getX() + 0.5 - cam.x, p.getY() + 12 - cam.y, p.getZ() + 0.5 - cam.z, 0xFFFF2020, 3f);
			}
		}
		if (own != null) own.draw(RenderLayers.lines());
	}

	private static int color(BlockState s) {
		if (s.isOf(Blocks.GLASS)) return 0xFFA0E0FF;
		if (s.isOf(Blocks.WATER)) return 0xFF3070FF;
		if (s.getBlock() instanceof net.minecraft.block.FenceGateBlock) return 0xFFC08040;
		if (s.getBlock() instanceof TrapdoorBlock) return 0xFFC08040;
		if (s.getBlock() instanceof BedBlock) return 0xFFFF4040;
		if (s.getBlock() instanceof SlabBlock) return 0xFFE0E0E0;
		return 0xFF9A9A9A;
	}

	private static void line(VertexConsumer lines, MatrixStack.Entry entry, double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width) {
		Vector3f n = new Vector3f((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1)).normalize();
		lines.vertex(entry, (float) x1, (float) y1, (float) z1).color(argb).normal(entry, n).lineWidth(width);
		lines.vertex(entry, (float) x2, (float) y2, (float) z2).color(argb).normal(entry, n).lineWidth(width);
	}

	private static void box(VertexConsumer lines, MatrixStack.Entry entry, double x, double y, double z, double w, double h, double d, int argb, float width) {
		double x2 = x + w, y2 = y + h, z2 = z + d;
		line(lines, entry, x, y, z, x2, y, z, argb, width);
		line(lines, entry, x, y, z2, x2, y, z2, argb, width);
		line(lines, entry, x, y2, z, x2, y2, z, argb, width);
		line(lines, entry, x, y2, z2, x2, y2, z2, argb, width);
		line(lines, entry, x, y, z, x, y, z2, argb, width);
		line(lines, entry, x2, y, z, x2, y, z2, argb, width);
		line(lines, entry, x, y2, z, x, y2, z2, argb, width);
		line(lines, entry, x2, y2, z, x2, y2, z2, argb, width);
		line(lines, entry, x, y, z, x, y2, z, argb, width);
		line(lines, entry, x2, y, z, x2, y2, z, argb, width);
		line(lines, entry, x, y, z2, x, y2, z2, argb, width);
		line(lines, entry, x2, y, z2, x2, y2, z2, argb, width);
	}
}
