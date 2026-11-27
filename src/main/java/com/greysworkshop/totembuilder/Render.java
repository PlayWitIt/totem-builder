package com.greysworkshop.totembuilder;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The farm drawn into the world, the way a schematic mod shows a build: every block still to place as a see-through
 * copy of the real block (brighter where it can go in right now, faint where it's still floating), the next one to
 * place outlined in pulsing cyan, wrong blocks and stray blocks that have to come out as red and purple boxes,
 * scaffolding the auto-builder left as amber outlines, the villager's spot as a green box with a beam, and spots the
 * land check found in red.
 */
final class Render {
	private Render() {}

	private record Ghost(BlockPos pos, BlockState state, boolean now, int faces) {}

	private record Mark(BlockPos pos, int kind) {}

	private static final int WRONG = 0, STRAY = 1, SCAFFOLD = 2;

	private static List<Ghost> ghosts = List.of();
	private static List<Mark> marks = List.of();
	private static BlockPos next;
	private static int age, frames;
	/** Nearest first, and no more than this: enough for the whole farm round you, light enough for a weak laptop. */
	private static final int MAX_GHOSTS = 1600, MAX_MARKS = 300;
	private static final Map<BlockState, List<BlockModelPart>> MODELS = new HashMap<>();

	/** Refreshes what to draw from the latest survey. */
	static void tick(MinecraftClient client) {
		Survey s = Survey.latest;
		if (s == null || client.player == null || client.world == null) {
			ghosts = List.of();
			marks = List.of();
			next = null;
			return;
		}
		if (++age % 5 != 0) return;
		Vec3d eye = client.player.getEyePos();
		double range = Settings.ghostRange;
		Settings.Ghosts mode = Settings.ghosts;
		Survey.Cell n = s.next(eye);
		next = n == null ? null : n.pos();

		Set<BlockPos> wrongAt = new HashSet<>();
		List<Mark> m = new ArrayList<>();
		for (Survey.Extra e : s.extras) {
			if (e.wrong()) wrongAt.add(e.pos());
			if (Vec3d.ofCenter(e.pos()).squaredDistanceTo(eye) > 96 * 96) continue;
			m.add(new Mark(e.pos(), e.wrong() ? WRONG : e.scaffold() ? SCAFFOLD : STRAY));
		}
		m.sort((a, b) -> Double.compare(Vec3d.ofCenter(a.pos).squaredDistanceTo(eye), Vec3d.ofCenter(b.pos).squaredDistanceTo(eye)));
		marks = m.size() > MAX_MARKS ? m.subList(0, MAX_MARKS) : m;

		if (mode == Settings.Ghosts.OFF) {
			ghosts = List.of();
			return;
		}
		// Full-cube ghosts hide each other's touching faces, as real blocks do.
		Map<BlockPos, BlockState> full = new HashMap<>();
		for (Survey.Cell c : s.missing) if (isCube(c.want())) full.put(c.pos(), c.want());
		List<Ghost> g = new ArrayList<>();
		for (Survey.Cell c : s.missing) {
			if (wrongAt.contains(c.pos())) continue;
			if (mode == Settings.Ghosts.PLACEABLE && !c.now() && !c.pos().equals(next)) continue;
			if (Vec3d.ofCenter(c.pos()).squaredDistanceTo(eye) > range * range && !c.pos().equals(next)) continue;
			int faces = 0;
			for (Direction d : Direction.values()) {
				BlockPos o = c.pos().offset(d);
				boolean hidden = isCube(c.want()) && (full.containsKey(o) || client.world.getBlockState(o).isOpaqueFullCube());
				if (!hidden) faces |= 1 << d.ordinal();
			}
			if (faces != 0 || !isCube(c.want())) g.add(new Ghost(c.pos(), c.want(), c.now(), faces));
		}
		if (g.size() > MAX_GHOSTS) {
			g.sort((a, b) -> Double.compare(Vec3d.ofCenter(a.pos).squaredDistanceTo(eye), Vec3d.ofCenter(b.pos).squaredDistanceTo(eye)));
			g = new ArrayList<>(g.subList(0, MAX_GHOSTS));
		}
		ghosts = g;
	}

	private static boolean isCube(BlockState s) {
		return s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.GLASS);
	}

	/**
	 * The see-through blocks and the filled boxes go to the game's render queue (the 1.21.11 way: drawn with the rest
	 * of the world at the right point of the frame). Called before the entities are queued.
	 */
	static void queue(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		Farm farm = TotemBuilder.farm;
		if (client.world == null || client.player == null || farm == null || context.commandQueue() == null || context.matrices() == null) return;
		Vec3d cam = client.gameRenderer.getCamera().getCameraPos();
		if (cam.squaredDistanceTo(Vec3d.ofCenter(farm.bed)) > 300 * 300) return;
		List<Ghost> gs = ghosts;
		List<Mark> ms = marks;
		MatrixStack matrices = context.matrices();
		if (!gs.isEmpty()) {
			context.commandQueue().submitCustom(matrices, RenderLayers.translucentMovingBlock(), (entry, vc) -> ghostQuads(client, entry, vc, gs, cam));
		}
		boolean anyFill = false;
		for (Ghost g : gs) anyFill |= g.state.isOf(Blocks.WATER) || g.state.getBlock() instanceof BedBlock;
		for (Mark mk : ms) anyFill |= mk.kind != SCAFFOLD;
		if (anyFill) context.commandQueue().submitCustom(matrices, RenderLayers.debugQuads(), (entry, vc) -> fills(entry, vc, gs, ms, cam));
	}

	private static void ghostQuads(MinecraftClient client, MatrixStack.Entry base, VertexConsumer vc, List<Ghost> gs, Vec3d cam) {
		MatrixStack matrices = new MatrixStack();
		matrices.peek().getPositionMatrix().set(base.getPositionMatrix());
		matrices.peek().getNormalMatrix().set(base.getNormalMatrix());
		int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
		for (Ghost g : gs) {
			List<BlockModelPart> parts = MODELS.computeIfAbsent(g.state, st -> client.getBlockRenderManager().getModel(st).getParts(Random.create(42L)));
			matrices.push();
			matrices.translate(g.pos.getX() - cam.x, g.pos.getY() - cam.y, g.pos.getZ() - cam.z);
			// Shrunk a touch, so a ghost never fights with a real block beside it.
			matrices.translate(0.5, 0.5, 0.5);
			matrices.scale(0.998f, 0.998f, 0.998f);
			matrices.translate(-0.5, -0.5, -0.5);
			MatrixStack.Entry e = matrices.peek();
			float a = g.now ? 0.75f : 0.4f;
			float r = g.now ? 1f : 0.75f, gr = g.now ? 1f : 0.85f;
			for (BlockModelPart part : parts) {
				for (Direction d : Direction.values()) {
					if ((g.faces & 1 << d.ordinal()) == 0) continue;
					for (BakedQuad q : part.getQuads(d)) vc.quad(e, q, r, gr, 1f, a, light, OverlayTexture.DEFAULT_UV);
				}
				for (BakedQuad q : part.getQuads(null)) vc.quad(e, q, r, gr, 1f, a, light, OverlayTexture.DEFAULT_UV);
			}
			matrices.pop();
		}
	}

	/** Filled boxes: water, the bed (no block model of their own), wrong and stray blocks. */
	private static void fills(MatrixStack.Entry base, VertexConsumer fill, List<Ghost> gs, List<Mark> ms, Vec3d cam) {
		for (Ghost g : gs) {
			if (g.state.isOf(Blocks.WATER)) filled(fill, base, g.pos, cam, 0, 0.875, g.now ? 0x703070FF : 0x403070FF);
			else if (g.state.getBlock() instanceof BedBlock) filled(fill, base, g.pos, cam, 0, 0.5625, g.now ? 0x90FF4040 : 0x50FF4040);
		}
		for (Mark mk : ms) {
			if (mk.kind == WRONG) filled(fill, base, mk.pos, cam, -0.01, 1.02, 0x60FF2020);
			else if (mk.kind == STRAY) filled(fill, base, mk.pos, cam, -0.01, 1.02, 0x60D030FF);
		}
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
		long time = client.world.getTime() + ++frames;
		if (frames % 200 == 1) TotemBuilder.LOGGER.info("[render] {} ghosts, {} marks, next {}, survey {}, consumers {}", ghosts.size(), marks.size(), next, Survey.latest == null ? null : Survey.latest.extras.size(),
				consumers.getClass().getName());

		// Lines straight in, as before: they show from this point in the frame.
		// 3. Lines.
		VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());
		MatrixStack.Entry e = matrices.peek();
		for (Mark mk : marks) {
			BlockPos p = mk.pos;
			int c = mk.kind == WRONG ? 0xFFFF2020 : mk.kind == STRAY ? 0xFFD030FF : 0xFFFFB020;
			box(lines, e, p.getX() - 0.005 - cam.x, p.getY() - 0.005 - cam.y, p.getZ() - 0.005 - cam.z, 1.01, 1.01, 1.01, c, mk.kind == SCAFFOLD ? 1.5f : 2.5f);
		}
		if (next != null) {
			BlockState want = Printer.targets(farm, true).get(next);
			double h = want == null ? 1 : height(want), y0 = want != null && want.getBlock() instanceof SlabBlock && want.get(SlabBlock.TYPE) == SlabType.TOP ? 0.5 : 0;
			float pulse = 2f + 1.5f * (float) Math.abs(Math.sin(time * 0.25));
			box(lines, e, next.getX() - cam.x, next.getY() + y0 - cam.y, next.getZ() - cam.z, 1, h, 1, 0xFF30FFFF, pulse);
			// A short cyan stalk above it, so it's easy to find from across the farm.
			double cx = next.getX() + 0.5 - cam.x, cz = next.getZ() + 0.5 - cam.z;
			line(lines, e, cx, next.getY() + y0 + h - cam.y, cz, cx, next.getY() + 6 - cam.y, cz, 0x9030FFFF, 2f);
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

	private static double height(BlockState s) {
		if (s.getBlock() instanceof SlabBlock) return s.get(SlabBlock.TYPE) == SlabType.DOUBLE ? 1 : 0.5;
		if (s.getBlock() instanceof BedBlock) return 0.5625;
		return 1;
	}

	/** A see-through box over a cell, from {@code y0} up {@code h}. */
	private static void filled(VertexConsumer vc, MatrixStack.Entry e, BlockPos p, Vec3d cam, double y0, double h, int argb) {
		float x1 = (float) (p.getX() - cam.x), y1 = (float) (p.getY() + y0 - cam.y), z1 = (float) (p.getZ() - cam.z);
		float x2 = x1 + 1, y2 = (float) (y1 + h), z2 = z1 + 1;
		if (h > 1) {
			x1 -= 0.01f;
			z1 -= 0.01f;
			x2 += 0.01f;
			z2 += 0.01f;
		}
		quad(vc, e, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, argb);
		quad(vc, e, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, argb);
		quad(vc, e, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, argb);
		quad(vc, e, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, argb);
		quad(vc, e, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, argb);
		quad(vc, e, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, argb);
	}

	private static void quad(VertexConsumer vc, MatrixStack.Entry e, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
			float dx, float dy, float dz, int argb) {
		vc.vertex(e, ax, ay, az).color(argb);
		vc.vertex(e, bx, by, bz).color(argb);
		vc.vertex(e, cx, cy, cz).color(argb);
		vc.vertex(e, dx, dy, dz).color(argb);
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
