package com.greysworkshop.totembuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flies you somewhere with your own flight (creative-style: Meteor's elytra fly, or any flight where forward moves you
 * the way you face, jump goes up and sneak goes down). It turns your view and holds the movement keys. Round the farm
 * it finds a way through the air between the blocks; over open sea it goes straight. It lets go of every key when it
 * arrives, when it's stopped, or when a screen is open.
 */
final class Pilot {
	/** Most it turns your view in a tick, in degrees. */
	private static final float MAX_TURN = 25;
	private Vec3d target;
	private final List<Vec3d> path = new ArrayList<>();
	private int ticks, takeoff = -1;
	/**
	 * Getting no nearer the next corner (stuck against a block, or landing on one that went in across the way and
	 * taking off again and again): ticks of it, the nearest it's been, and how many new ways it's tried.
	 */
	private int noProgress, replans;
	private double nearest = Double.MAX_VALUE;
	private boolean gaveUp;
	/** Ticks without getting nearer before it works out the way again, and new ways before it gives up on this spot. */
	private static final int STUCK_TICKS = 50, MAX_REPLANS = 3;
	int takeoffs;
	/** The area where a path is worked out block by block (round the farm); elsewhere it's a straight line. */
	private Box detailed;

	boolean active() {
		return target != null;
	}

	Vec3d target() {
		return target;
	}

	void go(MinecraftClient client, Vec3d where, Box detailedArea) {
		if (target != null && target.squaredDistanceTo(where) < 0.01) return;
		target = where;
		detailed = detailedArea;
		replans = 0;
		gaveUp = false;
		replan(client);
	}

	/** It couldn't find a way there: tried a new way {@link #MAX_REPLANS} times and still got no nearer. */
	boolean gaveUp() {
		return gaveUp;
	}

	void stop(MinecraftClient client) {
		target = null;
		gaveUp = false;
		path.clear();
		release(client);
	}

	boolean arrived(MinecraftClient client) {
		return target != null && client.player != null && client.player.getEntityPos().distanceTo(target) < 0.7 && path.isEmpty();
	}

	private void replan(MinecraftClient client) {
		path.clear();
		noProgress = 0;
		nearest = Double.MAX_VALUE;
		ClientPlayerEntity p = client.player;
		if (p == null || target == null) return;
		Vec3d from = p.getEntityPos();
		if (detailed != null && (detailed.contains(from) || detailed.contains(target) || segmentHits(client.world, from, target))) {
			List<Vec3d> found = search(client.world, BlockPos.ofFloored(from), BlockPos.ofFloored(target), detailed.expand(2));
			if (found != null) {
				path.addAll(found);
				path.add(target);
				return;
			}
		}
		path.add(target);
	}

	String debug(MinecraftClient client) {
		return "target " + target + ", path " + path.size() + (path.isEmpty() ? "" : " next " + path.get(0)) + ", keys f" + client.options.forwardKey.isPressed()
				+ " j" + client.options.jumpKey.isPressed() + " s" + client.options.sneakKey.isPressed();
	}

	/** Would a straight flight bump into a block anywhere along the way? */
	private static boolean segmentHits(ClientWorld world, Vec3d a, Vec3d b) {
		double len = a.distanceTo(b);
		for (double t = 0; t <= len; t += 0.5) {
			Vec3d c = a.lerp(b, len == 0 ? 0 : t / len);
			if (!world.isSpaceEmpty(new Box(c.x - 0.3, c.y, c.z - 0.3, c.x + 0.3, c.y + 1.8, c.z + 0.3))) return true;
		}
		return false;
	}

	private static boolean free(ClientWorld world, BlockPos c) {
		return world.getBlockState(c).getCollisionShape(world, c).isEmpty() && world.getBlockState(c.up()).getCollisionShape(world, c.up()).isEmpty()
				&& world.getFluidState(c).isEmpty();
	}

	/** Breadth-first through the free air (two blocks tall) in the box; the corners of the way, cell centres. */
	private static List<Vec3d> search(ClientWorld world, BlockPos start, BlockPos goal, Box box) {
		if (!free(world, goal)) return null;
		ArrayDeque<BlockPos> q = new ArrayDeque<>();
		Map<BlockPos, BlockPos> from = new HashMap<>();
		q.add(start);
		from.put(start, start);
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		int limit = 400_000;
		while (!q.isEmpty() && from.size() < limit) {
			BlockPos c = q.poll();
			if (c.equals(goal)) break;
			for (int[] d : dirs) {
				BlockPos n = c.add(d[0], d[1], d[2]);
				if (from.containsKey(n) || !box.contains(Vec3d.ofCenter(n)) && !n.equals(goal)) continue;
				if (!free(world, n)) continue;
				from.put(n, c);
				q.add(n);
			}
		}
		if (!from.containsKey(goal)) return null;
		List<BlockPos> cells = new ArrayList<>();
		for (BlockPos c = goal; !c.equals(start); c = from.get(c)) cells.add(c);
		java.util.Collections.reverse(cells);
		// Keep only where it turns.
		List<Vec3d> out = new ArrayList<>();
		for (int i = 0; i < cells.size(); i++) {
			boolean last = i == cells.size() - 1;
			if (!last) {
				BlockPos a = i == 0 ? start : cells.get(i - 1), b = cells.get(i), c = cells.get(i + 1);
				if (b.subtract(a).equals(c.subtract(b))) continue;
			}
			BlockPos b = cells.get(i);
			out.add(new Vec3d(b.getX() + 0.5, b.getY() + 0.1, b.getZ() + 0.5));
		}
		return out;
	}

	void tick(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (p == null || target == null) return;
		if (client.currentScreen != null) {
			release(client);
			return;
		}
		ticks++;
		Vec3d me = p.getEntityPos();
		// Stuck, however it's stuck (standing still, or bouncing off a block it keeps landing on): a new way, and in
		// the end give up on this spot so the job can pick another.
		if (!path.isEmpty() && !gaveUp) {
			double d = path.get(0).distanceTo(me);
			if (d < nearest - 0.15) {
				nearest = d;
				noProgress = 0;
			} else if (++noProgress > STUCK_TICKS) {
				if (++replans > MAX_REPLANS) {
					gaveUp = true;
					release(client);
					return;
				}
				replan(client);
			}
		}
		if (gaveUp) return;
		// Touched down (which ends elytra and creative flight alike): take off again. Jump, then jump again in the
		// air, which opens the elytra (or turns creative flight back on).
		boolean airborne = p.getAbilities().flying || p.isGliding();
		if (!airborne && takeoff < 0 && (p.isOnGround() || p.isTouchingWater())) takeoff = 0;
		if (takeoff >= 0) {
			client.options.forwardKey.setPressed(false);
			client.options.sneakKey.setPressed(false);
			client.options.jumpKey.setPressed(takeoff == 0 || takeoff == 1 || takeoff == 4 || takeoff == 5);
			takeoff++;
			if (airborne || takeoff > 12) {
				if (airborne) takeoffs++;
				takeoff = -1;
				client.options.jumpKey.setPressed(false);
			}
			return;
		}
		while (!path.isEmpty() && path.get(0).distanceTo(me) < (path.size() == 1 ? 0.5 : 0.8)) {
			path.remove(0);
			nearest = Double.MAX_VALUE;
			noProgress = 0;
		}
		if (path.isEmpty()) {
			release(client);
			p.setVelocity(p.getVelocity().multiply(0.5, 0.5, 0.5));
			return;
		}
		Vec3d next = path.get(0);
		Vec3d d = next.subtract(me);
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		// Turn smoothly, never snap: a snapped view jerks the screen, and the server sees a turn no player makes.
		float off = 0;
		if (flat > 0.25) {
			off = MathHelper.wrapDegrees((float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90 - p.getYaw());
			p.setYaw(p.getYaw() + MathHelper.clamp(off, -MAX_TURN, MAX_TURN));
		}
		p.setPitch(p.getPitch() + MathHelper.clamp(-p.getPitch(), -MAX_TURN, MAX_TURN));
		// Slow down close in: let go of forward and let the air stop you. Only go forward once facing about the
		// right way, so it doesn't swing out wide while it turns.
		Vec3d v = p.getVelocity();
		double speed = Math.sqrt(v.x * v.x + v.z * v.z);
		boolean forward = flat > 0.3 && Math.abs(off) < 40 && !(flat < 2.5 && speed > flat * 0.35);
		client.options.forwardKey.setPressed(forward);
		client.options.backKey.setPressed(false);
		// The same up and down: let go before it gets there, so it doesn't bob over and under the spot.
		boolean coastingUp = v.y > 0 && d.y < 1.5 && v.y > d.y * 0.4, coastingDown = v.y < 0 && d.y > -1.5 && -v.y > -d.y * 0.4;
		client.options.jumpKey.setPressed(d.y > 0.25 && !coastingUp);
		client.options.sneakKey.setPressed(d.y < -0.25 && !coastingDown);
		client.options.sprintKey.setPressed(false);
	}

	void release(MinecraftClient client) {
		client.options.forwardKey.setPressed(false);
		client.options.backKey.setPressed(false);
		client.options.jumpKey.setPressed(false);
		client.options.sneakKey.setPressed(false);
	}
}
