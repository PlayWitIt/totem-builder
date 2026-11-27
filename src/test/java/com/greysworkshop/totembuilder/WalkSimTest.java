package com.greysworkshop.totembuilder;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The walking auto-builder's planning run over a made-up ocean, with a stand-in player that does each move at once
 * (no physics): from nothing, and from a half-built farm with stray blocks in it. Every block must end up right,
 * every bit of scaffolding gone, no stray left, and every move one a player on foot can really do.
 */
class WalkSimTest {
	static BlockState COBBLE, AIR, WATER;

	@BeforeAll
	static void boot() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
		COBBLE = Blocks.COBBLESTONE.getDefaultState();
		AIR = Blocks.AIR.getDefaultState();
		WATER = Blocks.WATER.getDefaultState();
		Walk.DEBUG = true;
	}

	/** Sea from y 49 to 52 (its surface), stone under, air over: the farm's floor goes at 53. */
	static final class Sea implements BlockView {
		final Map<BlockPos, BlockState> set = new HashMap<>();

		@Override
		public BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			BlockState s = set.get(pos);
			if (s != null) return s;
			if (pos.getY() >= 49 && pos.getY() <= 52) return WATER;
			if (pos.getY() < 49) return Blocks.STONE.getDefaultState();
			return AIR;
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return getBlockState(pos).getFluidState();
		}

		@Override
		public int getHeight() {
			return 384;
		}

		@Override
		public int getBottomY() {
			return -64;
		}

		void put(BlockPos p, BlockState s) {
			set.put(p.toImmutable(), s);
		}
	}

	static final class Run {
		final Sea world = new Sea();
		final Farm farm = new Farm(new BlockPos(0, 64, 0));
		BlockPos feet;
		int jobs, scaffoldPlaced, moves, pillars, digs, tunnels, falls, boots;
		final Set<BlockPos> skip = new HashSet<>();
		String log = "";
		boolean escaped = true;
		int stepLost;

		Walk walk() {
			return new Walk(world, farm, false, COBBLE, skip, s -> true);
		}

		double base() {
			return walk().floor(feet);
		}

		/** The printer: everything due that can go in from where it stands, again until nothing more does. */
		void print() {
			for (int round = 0; round < 50; round++) {
				Walk w = walk();
				double b = w.floor(feet);
				if (Double.isNaN(b)) return;
				boolean any = false;
				for (Map.Entry<BlockPos, BlockState> e : w.targets.entrySet()) {
					BlockPos p = e.getKey();
					if (!w.allowPlace(p) || e.getValue().isOf(Blocks.WATER)) continue;
					if (Printer.need(e.getValue(), world.getBlockState(p)) != Printer.Need.PLACE) continue;
					if (!w.canPlaceFrom(feet, b, p) || !w.safeToFill(p, feet, b)) continue;
					put(p, e.getValue());
					any = true;
				}
				if (!any) return;
			}
		}

		/** A block goes in (the bed brings its head along). */
		void put(BlockPos p, BlockState s) {
			world.put(p, s);
			if (s.getBlock() instanceof net.minecraft.block.BedBlock)
				world.put(p.offset(s.get(net.minecraft.block.BedBlock.FACING)), s.with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD));
		}

		void pourAndFlow(BlockPos t) {
			world.put(t, WATER);
			// Once all six are in, the lake fills: every open cell of it at its level, and the corner beside the drop.
			if (Printer.waterStillToPour(world, farm) == 0) {
				int p = farm.bed.getY() + FarmPlan.PAD_Y;
				for (int x = -4; x <= 3; x++)
					for (int z = -4; z <= 3; z++)
						for (int y = p - 1; y <= p; y++) {
							BlockPos q = farm.bed.add(x, 0, z).withY(y);
							if (FarmPlan.dropCell(x, z)) continue;
							if (world.getBlockState(q).isAir()) world.put(q, Blocks.WATER.getDefaultState().with(net.minecraft.block.FluidBlock.LEVEL, 2));
						}
			}
		}

		/** Runs it to the end; the reason it stopped. */
		String go(int maxJobs) {
			long began = System.currentTimeMillis();
			while (jobs < maxJobs && System.currentTimeMillis() - began < 150_000) {
				print();
				{
					Walk pw = walk();
					double pb = pw.floor(feet);
					boolean e = !Double.isNaN(pb) && pw.canEscape(feet, pb);
					if (!e && escaped) System.out.println("  LOST the way out after the printer, phase " + pw.phase + " feet " + feet.subtract(farm.bed).toShortString());
					escaped = e;
				}
				Walk w = walk();
				Walk.Job job = w.next(feet);
				jobs++;
				if (job.act() == Walk.Act.DONE) return "done";
				if (job.act() == Walk.Act.BOOT) {
					// In the sea: a block of scaffolding on the surface beside it, and up onto it.
					BlockPos sea = feet.withY(farm.bed.getY() + FarmPlan.FLOOR_Y - 1);
					world.put(sea, COBBLE);
					farm.scaffold.add(sea);
					scaffoldPlaced++;
					feet = sea.up();
					boots++;
					continue;
				}
				if (job.act() == Walk.Act.STUCK) {
					if (job.target() != null && skip.size() < 3) {
						BlockPos t = job.target();
						var path = w.search(feet, w.floor(feet), (f, bb, st) -> w.canPlaceFrom(f, bb, t), net.minecraft.util.math.Vec3d.ofCenter(t), true, 25000);
						System.out.println("  stuck: " + job.why() + " feet " + feet.subtract(farm.bed).toShortString() + " " + w.debugMoves(feet) + " expanded " + w.lastExpanded
								+ " closest " + (w.lastClosest == null ? null : w.lastClosest.subtract(farm.bed).toShortString()) + " " + (w.lastClosest == null ? "" : w.debugMoves(w.lastClosest))
								+ " target " + t.subtract(farm.bed).toShortString() + " " + w.targets.get(t));
					}
					if (job.target() != null && skip.add(job.target())) continue;
					for (BlockPos q : w.toPlace.subList(0, Math.min(12, w.toPlace.size())))
						System.out.println("  left " + q.subtract(farm.bed).toShortString() + " " + w.targets.get(q) + " now " + Printer.placeableNow(world, q, w.targets.get(q))
								+ " skip " + skip.contains(q) + " have " + world.getBlockState(q));
					for (BlockPos q : w.toPlace) if (q.getY() < farm.bed.getY() + 80)
						System.out.println("  low " + q.subtract(farm.bed).toShortString() + " " + w.targets.get(q).getBlock() + " now " + Printer.placeableNow(world, q, w.targets.get(q)));
					System.out.println("  " + w.toPlace.size() + " to place, " + w.toBreak.size() + " to break, skip " + skip.size());
					return w.phase + ": " + job.act() + " " + job.why() + " at feet " + feet.subtract(farm.bed).toShortString();
				}
				Map<BlockPos, BlockState> before = new HashMap<>(world.set);
				Set<BlockPos> scBefore = new HashSet<>(farm.scaffold);
				BlockPos feetBefore = feet;
				for (Walk.Step s : job.path()) {
					Walk now = walk();
					double b = now.floor(feet);
					switch (s.kind()) {
						case PILLAR, BRIDGE -> {
							assertTrue(now.canFill(s.cell()), "fill at " + s.cell());
							BlockState f = now.fill(s.cell());
							world.put(s.cell(), f);
							if (!now.targets.containsKey(s.cell())) {
								farm.scaffold.add(s.cell());
								scaffoldPlaced++;
							}
							if (s.kind() == Walk.Kind.PILLAR) pillars++;
						}
						case TUNNEL -> {
							world.put(s.to(), AIR);
							world.put(s.to().up(), AIR);
							tunnels++;
						}
						case DIG -> {
							world.put(s.cell(), AIR);
							farm.scaffold.remove(s.cell());
							digs++;
						}
						default -> { }
					}
					feet = s.to();
					moves++;
					{
						Walk sw = walk();
						double sb = sw.floor(feet);
						boolean e = !Double.isNaN(sb) && sw.canEscape(feet, sb);
						if (!e && escaped && stepLost < 1) {
							Map<BlockPos, BlockState> nowSet = new HashMap<>(world.set);
							Set<BlockPos> nowSc = new HashSet<>(farm.scaffold);
							world.set.clear();
							world.set.putAll(before);
							farm.scaffold.clear();
							farm.scaffold.addAll(scBefore);
							System.out.println("  replay: " + walk().replay(feetBefore, job.path()) + " start escape " + walk().canEscape(feetBefore, walk().floor(feetBefore)));
							world.set.clear();
							world.set.putAll(nowSet);
							farm.scaffold.clear();
							farm.scaffold.addAll(nowSc);
						}
						if (!e && escaped && stepLost++ < 3) System.out.println("  step lost it: " + s.kind() + " to " + feet.subtract(farm.bed).toShortString() + " cell "
								+ (s.cell() == null ? null : s.cell().subtract(farm.bed).toShortString()) + " in " + job.path().stream().map(x -> x.kind() + ">" + x.to().subtract(farm.bed).toShortString()).toList()
								+ " for " + job.act() + " " + job.target().subtract(farm.bed).toShortString() + " seen " + sw.lastSeen.keySet().stream().map(x -> x.subtract(farm.bed).toShortString()).toList()
								+ " column " + java.util.stream.IntStream.rangeClosed(-12, 10).mapToObj(y -> y + ":" + world.getBlockState(farm.bed.add(0, y, 5)).getBlock().getTranslationKey().replace("block.minecraft.", "") + (farm.scaffold.contains(farm.bed.add(0, y, 5)) ? "*" : "")).toList());
					}
					assertFalse(Double.isNaN(walk().floor(feet)), "after " + s + " not standing at " + feet.subtract(farm.bed).toShortString());
				}
				Walk now = walk();
				double b = now.floor(feet);
				if (job.act() == Walk.Act.FALL) {
					net.minecraft.util.math.Direction d = now.seaDrop(feet);
					assertNotNull(d, "a clear drop into the sea");
					feet = feet.offset(d).withY(farm.bed.getY() + FarmPlan.FLOOR_Y - 1);
					falls++;
					continue;
				}
				switch (job.act()) {
					case PLACE -> {
						if (Printer.need(job.want(), world.getBlockState(job.target())) != Printer.Need.NOTHING) {
							if (now.canPlaceFrom(feet, b, job.target()) && now.safeToFill(job.target(), feet, b)) put(job.target(), job.want());
							else {
								skip.add(job.target());
								System.out.println("  sim skip " + job.target().subtract(farm.bed).toShortString() + " from " + feet.subtract(farm.bed).toShortString()
										+ " canPlace " + now.canPlaceFrom(feet, b, job.target()) + " safe " + now.safeToFill(job.target(), feet, b));
							}
						}
					}
					case SUPPORT -> {
						world.put(job.target(), COBBLE);
						farm.scaffold.add(job.target());
						scaffoldPlaced++;
					}
					case POUR -> {
						assertTrue(now.canPourFrom(feet, b, job.target()), "pour from " + feet);
						pourAndFlow(job.target());
					}
					case BREAK -> {
						BlockPos r = job.target();
						boolean under = r.equals(feet.down());
						assertTrue(under || now.canBreakFrom(feet, b, r), "break " + r + " from " + feet);
						world.put(r, r.getY() == 52 ? WATER : AIR);
						farm.scaffold.remove(r);
						if (under) {
							digs++;
							// Fell into the sea or onto what's below.
							feet = r;
							if (Double.isNaN(walk().floor(feet))) {
								Walk end = walk();
								return end.phase == Walk.Phase.DONE ? "done" : "fell into the sea with " + end.phase + " left";
							}
						}
					}
					default -> { }
				}
				if (jobs % 200 == 0) skip.clear();
				if (jobs > 3000 && jobs < 3012) System.out.println("  late job " + job.act() + " " + job.why() + " " + (job.target() == null ? "" : job.target().subtract(farm.bed).toShortString())
						+ " path " + job.path().stream().map(x -> x.kind() + ">" + x.to().subtract(farm.bed).toShortString()).toList() + " scaffold " + farm.scaffold.stream().map(x -> x.subtract(farm.bed).toShortString()).toList());
				Walk after = walk();
				double ab = after.floor(feet);
				boolean esc = !Double.isNaN(ab) && after.canEscape(feet, ab);
				if (!esc && escaped && job.target() != null) {
					BlockState was = world.getBlockState(job.target());
					world.set.remove(job.target());
					Walk undo = walk();
					System.out.println("  (without the target: escape " + undo.canEscape(feet, undo.floor(feet)) + ", seen " + undo.lastSeen.size() + ")");
					world.put(job.target(), was);
				}
				if (!esc && escaped) System.out.println("  LOST the way out after " + job.act() + " " + job.why() + " " + (job.target() == null ? "" : job.target().subtract(farm.bed).toShortString())
						+ " path " + job.path().stream().map(x -> x.kind() + ">" + x.to().subtract(farm.bed).toShortString()).toList() + " phase " + after.phase + " feet " + feet.subtract(farm.bed).toShortString()
						+ "\n   seen " + (after.lastSeen == null ? null : after.lastSeen.keySet().stream().map(x -> x.subtract(farm.bed).toShortString()).toList())
						+ "\n   scaffold " + farm.scaffold.stream().filter(x -> x.getY() > farm.bed.getY() + 85).map(x -> x.subtract(farm.bed).toShortString()).toList());
				escaped = esc;
			}
			return "ran out of jobs in " + walk().phase;
		}

		int wrong() {
			int bad = 0;
			for (Map.Entry<BlockPos, BlockState> e : Printer.targets(farm, false).entrySet())
				if (!Printer.matches(e.getValue(), world.getBlockState(e.getKey()))) {
					if (bad < 10) System.out.println("  wrong at " + e.getKey().subtract(farm.bed).toShortString() + ": " + world.getBlockState(e.getKey()) + " want " + e.getValue());
					bad++;
				}
			return bad;
		}

		void report(String name, String end, long ms) {
			Survey s = Survey.of(world, farm);
			System.out.println(name + ": " + end + " in " + jobs + " jobs, " + moves + " moves (" + pillars + " pillar, " + digs + " dig), "
					+ scaffoldPlaced + " scaffolding placed, " + tunnels + " tunnels, " + falls + " falls into the sea, " + boots + " climbs out, " + farm.scaffold.size() + " left, " + s.extras.size() + " extras, " + ms + " ms");
		}
	}

	@Test
	void fromNothing() {
		Run r = new Run();
		// Standing on one block of scaffolding in the sea beside where the farm goes.
		BlockPos start = r.farm.bed.add(10, FarmPlan.FLOOR_Y - 1, 4);
		r.world.put(start, COBBLE);
		r.farm.scaffold.add(start);
		r.feet = start.up();
		long t = System.currentTimeMillis();
		String end = r.go(6000);
		r.report("from nothing", end, System.currentTimeMillis() - t);
		assertEquals("done", end);
		assertEquals(0, r.wrong(), "every block right");
		assertTrue(r.farm.scaffold.isEmpty(), "scaffolding left: " + r.farm.scaffold);
		assertTrue(Survey.of(r.world, r.farm).extras.isEmpty(), "nothing stray left");
	}

	@Test
	void fromHalfBuiltWithJunk() {
		Run r = new Run();
		Random rnd = new Random(7);
		Map<BlockPos, BlockState> targets = Printer.targets(r.farm, false);
		// Two thirds of it built by hand, the rest missing; the water not in.
		for (Map.Entry<BlockPos, BlockState> e : targets.entrySet())
			if (!e.getValue().isOf(Blocks.WATER) && rnd.nextInt(3) != 0) {
				if (e.getValue().getBlock() instanceof net.minecraft.block.BedBlock) {
					if (e.getValue().get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.FOOT) r.put(e.getKey(), e.getValue());
				} else r.world.put(e.getKey(), e.getValue());
			}
		// A wrong block, and some old hand scaffolding inside the farm (in the drop, on the floor, in the lake).
		BlockPos wrongAt = targets.keySet().stream().filter(p -> targets.get(p).isOf(Blocks.COBBLESTONE)).skip(300).findFirst().orElseThrow();
		r.world.put(wrongAt, Blocks.DIRT.getDefaultState());
		r.world.put(r.farm.bed.add(2, 40, 2), Blocks.DIRT.getDefaultState());
		r.world.put(r.farm.bed.add(3, FarmPlan.FLOOR_Y + 1, 3), Blocks.NETHERRACK.getDefaultState());
		r.world.put(r.farm.bed.add(-3, FarmPlan.PAD_Y, 2), Blocks.DIRT.getDefaultState());
		BlockPos start = r.farm.bed.add(-11, FarmPlan.FLOOR_Y - 1, -11);
		r.world.put(start, COBBLE);
		r.farm.scaffold.add(start);
		r.feet = start.up();
		long t = System.currentTimeMillis();
		String end = r.go(6000);
		r.report("half built", end, System.currentTimeMillis() - t);
		assertEquals("done", end);
		assertEquals(0, r.wrong(), "every block right");
		assertTrue(r.farm.scaffold.isEmpty(), "scaffolding left: " + r.farm.scaffold);
		List<Survey.Extra> extras = Survey.of(r.world, r.farm).extras;
		assertTrue(extras.isEmpty(), "stray left: " + extras);
	}
}
