package com.greysworkshop.totembuilder;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The walking auto-builder's planning, with no game client in it, so a test can run it over a made-up world.
 *
 * <p>It works on foot, like a player in survival with no flight: it walks (sneaking, so it never steps off an edge),
 * steps up and drops down, pillars up by jumping and putting a block under itself, bridges out by putting a block in
 * front of its feet, and digs back down through its own pillar. Where it climbs on something that isn't the farm it
 * uses the scaffolding block, keeps a list of every one, and takes them all away at the end.
 *
 * <p>Phases, in order: {@code BUILD} everything up to the lake's level (and break wrong or stray blocks there),
 * {@code CLEAR_LAKE} take its scaffolding out of where the water goes, {@code WATER} pour the six sources standing on
 * the pad's walkway, {@code TOP} the walls above the water and the islands, {@code CLEAN} take away the rest of the
 * scaffolding from the top down, then {@code DONE}.
 *
 * <p>Each call to {@link #next} picks the next job (what to place or break, and the way to a spot it can do that
 * from) for where the player stands now; the caller does the first steps and asks again, so anything that changed
 * (a block placed by the printer, a step that didn't come off) is taken into account.
 */
final class Walk {
	/** How far from the eyes it plans to click, a little under the game's 4.5 so it never falls short. */
	static final double REACH = 4.2;
	static final double EYE = 1.62;

	enum Kind { WALK, ASCEND, DESCEND, PILLAR, BRIDGE, DIG, TUNNEL }

	/** One move: where the feet end up, and the cell it fills (pillar, bridge) or digs out (dig). */
	record Step(Kind kind, BlockPos to, BlockPos cell) {}

	enum Phase { BUILD, CLEAR_LAKE, WATER, TOP, CLEAN, DONE }

	enum Act { PLACE, BREAK, POUR, SUPPORT, BOOT, FALL, STUCK, DONE }

	/** What to do: go along {@code path}, then do {@code act} to {@code target}. */
	record Job(Act act, BlockPos target, BlockState want, List<Step> path, String why) {}

	final BlockView world;
	final Farm farm;
	final BlockPos bed;
	final Map<BlockPos, BlockState> targets;
	final BlockState scaffoldState;
	final int padY, floorY;
	final Phase phase;
	/** Farm cells still to fill in this phase, and blocks that have to come out. */
	final List<BlockPos> toPlace = new ArrayList<>(), toBreak = new ArrayList<>();
	final List<BlockPos> water = new ArrayList<>();
	final Set<BlockPos> scaffoldLeft = new HashSet<>();
	private final Set<BlockPos> skip;
	private final Map<BlockPos, BlockState> over = new HashMap<>();
	private final Box region;
	/** What's in the bag: false makes a fill with that block impossible. */
	private final java.util.function.Predicate<BlockState> have;

	Walk(BlockView world, Farm farm, boolean sealed, BlockState scaffoldState, Set<BlockPos> skip, java.util.function.Predicate<BlockState> have) {
		this.world = world;
		this.farm = farm;
		this.bed = farm.bed;
		this.targets = Printer.targets(farm, sealed);
		this.scaffoldState = scaffoldState;
		this.skip = skip;
		this.have = have;
		padY = bed.getY() + FarmPlan.PAD_Y;
		floorY = bed.getY() + FarmPlan.FLOOR_Y;
		region = new Box(bed.getX() - 14, floorY - 1, bed.getZ() - 14, bed.getX() + 15, padY + 9, bed.getZ() + 15);

		List<BlockPos> lowPlace = new ArrayList<>(), highPlace = new ArrayList<>(), lowBreak = new ArrayList<>(), highBreak = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> e : targets.entrySet()) {
			BlockPos p = e.getKey();
			BlockState want = e.getValue(), now = world.getBlockState(p);
			Printer.Need n = Printer.need(want, now);
			if (n == Printer.Need.NOTHING) continue;
			if (want.isOf(Blocks.WATER)) {
				water.add(p);
				continue;
			}
			boolean high = p.getY() > padY;
			// The bed's head comes in with its foot.
			if (want.getBlock() instanceof BedBlock && want.get(BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD && n == Printer.Need.PLACE) continue;
			if (n == Printer.Need.WRONG) (high ? highBreak : lowBreak).add(p);
			else (high ? highPlace : lowPlace).add(p);
		}
		for (BlockPos p : farm.scaffold) {
			BlockState s = world.getBlockState(p);
			if (!s.isAir() && s.getFluidState().isEmpty() && !targets.containsKey(p)) scaffoldLeft.add(p);
		}
		// Stray blocks inside the farm: everything not of the farm has to be air there.
		BlockPos lo = bed.add(-9, FarmPlan.FLOOR_Y, -9), hi = bed.add(9, FarmPlan.PAD_Y + 5, 9);
		for (BlockPos q : BlockPos.iterate(lo, hi)) {
			if (targets.containsKey(q) || scaffoldLeft.contains(q)) continue;
			BlockState s = world.getBlockState(q);
			if (s.isAir() || !s.getFluidState().isEmpty() || s.isOf(Blocks.BUBBLE_COLUMN)) continue;
			if (breakable(s)) lowBreak.add(q.toImmutable());
		}
		int low = Integer.MAX_VALUE;
		for (BlockPos p : (lowPlace.isEmpty() ? highPlace : lowPlace)) if (Printer.placeableNow(world, p, targets.get(p))) low = Math.min(low, p.getY());
		lowPlaceable = low;
		boolean lakeScaffold = scaffoldLeft.stream().anyMatch(this::lakeZone);
		if (!lowPlace.isEmpty() || !lowBreak.isEmpty()) {
			phase = Phase.BUILD;
			toPlace.addAll(lowPlace);
			toBreak.addAll(lowBreak);
		} else if (lakeScaffold && !water.isEmpty()) {
			phase = Phase.CLEAR_LAKE;
			for (BlockPos p : scaffoldLeft) if (lakeZone(p)) toBreak.add(p);
		} else if (!water.isEmpty()) {
			phase = Phase.WATER;
		} else if (!highPlace.isEmpty() || !highBreak.isEmpty()) {
			phase = Phase.TOP;
			toPlace.addAll(highPlace);
			toBreak.addAll(highBreak);
		} else if (!scaffoldLeft.isEmpty()) {
			phase = Phase.CLEAN;
			toBreak.addAll(scaffoldLeft);
		} else phase = Phase.DONE;
	}

	static boolean breakable(BlockState s) {
		return !s.hasBlockEntity() && !(s.getBlock() instanceof BedBlock) && s.getBlock().getHardness() >= 0 && s.getBlock().getHardness() < 30;
	}

	/** The lowest layer with a block that can go in now: it builds a layer or two at a time, from the bottom. */
	final int lowPlaceable;

	/**
	 * May the printer put this farm block in now? Layer by layer from the bottom (so a room's floor goes in before its
	 * walls shut it off), water waits for its phase, the top for the water.
	 */
	boolean allowPlace(BlockPos p) {
		BlockState want = targets.get(p);
		if (want == null) return false;
		if (!want.isOf(Blocks.WATER) && p.getY() > lowPlaceable + 1) return false;
		return switch (phase) {
			case BUILD -> !want.isOf(Blocks.WATER) && p.getY() <= padY;
			case WATER -> want.isOf(Blocks.WATER);
			case TOP -> !want.isOf(Blocks.WATER) && p.getY() > padY;
			default -> false;
		};
	}

	// ---- The world as it would be ----

	private void restore(BlockPos p, BlockState was) {
		if (was == null) over.remove(p);
		else over.put(p, was);
	}

	BlockState get(BlockPos p) {
		BlockState s = over.get(p);
		return s != null ? s : world.getBlockState(p);
	}

	private VoxelShape shape(BlockPos p) {
		return get(p).getCollisionShape(world, p);
	}

	/** Nothing to bump into and no water. */
	boolean clear(BlockPos p) {
		BlockState s = get(p);
		return s.getCollisionShape(world, p).isEmpty() && s.getFluidState().isEmpty();
	}

	/** Where the farm needs air and nobody should stand: the villager's room and its door. */
	boolean protectedCell(BlockPos p) {
		BlockPos r = p.subtract(bed);
		if (r.getZ() == 0 && r.getX() >= -1 && r.getX() <= 0 && r.getY() >= 0 && r.getY() <= 2) return true;
		for (BlockPos e : FarmPlan.ENTRANCE) if (e.equals(r)) return true;
		return false;
	}

	/** Where the lake's water goes: scaffolding there is taken out before it's poured. */
	boolean lakeZone(BlockPos p) {
		BlockPos r = p.subtract(bed);
		return r.getX() >= -4 && r.getX() <= 3 && r.getZ() >= -4 && r.getZ() <= 3 && r.getY() >= FarmPlan.PAD_Y - 3 && r.getY() <= FarmPlan.PAD_Y;
	}

	/** The open sea just under the farm's floor, where a block can go in against the water itself. */
	boolean sea(BlockPos p) {
		return p.getY() == floorY - 1 && get(p).isOf(Blocks.WATER) && get(p).getFluidState().isStill();
	}

	/**
	 * Can the feet stand in this cell? The height above the cell's bottom the feet are at (0 on a full block below,
	 * 0.5 on a slab in the cell), or NaN.
	 */
	double floor(BlockPos f) {
		if (!region.contains(Vec3d.ofCenter(f)) || protectedCell(f)) return Double.NaN;
		BlockState at = get(f);
		if (!at.getFluidState().isEmpty()) return Double.NaN;
		VoxelShape s = at.getCollisionShape(world, f);
		double base;
		if (!s.isEmpty()) {
			double top = s.getMax(Direction.Axis.Y);
			if (top > 0.6) return Double.NaN;
			base = top;
		} else {
			BlockPos d = f.down();
			VoxelShape b = shape(d);
			if (b.isEmpty() || b.getMax(Direction.Axis.Y) < 0.99 || b.getMax(Direction.Axis.Y) > 1.01) return Double.NaN;
			if (protectedCell(d)) return Double.NaN;
			base = 0;
		}
		if (!clear(f.up())) return Double.NaN;
		if (base > 0.2 && !clear(f.up(2))) return Double.NaN;
		return base;
	}

	/** Something solid that does nothing when clicked, to place against (with the hypothetical blocks too). */
	boolean clickable(BlockPos p) {
		if (over.containsKey(p)) return !over.get(p).isAir();
		return Printer.clickable(world, p);
	}

	/** Can a block go in this cell as a step (scaffolding, or a farm block that's a full cube and due now)? */
	boolean canFill(BlockPos c) {
		if (!region.contains(Vec3d.ofCenter(c)) || protectedCell(c)) return false;
		BlockState s = get(c);
		if (!(s.isAir() || s.isReplaceable() && !s.getFluidState().isEmpty() && sea(c) || s.isReplaceable() && s.getFluidState().isEmpty())) return false;
		if (c.getY() < floorY && !sea(c)) return false;
		if (phase.ordinal() >= Phase.WATER.ordinal() && lakeZone(c)) return false;
		if (!s.getFluidState().isEmpty() && !sea(c)) return false;
		BlockState want = targets.get(c);
		if (want != null) return isCube(want) && allowPlace(c) && have.test(want);
		return have.test(scaffoldState);
	}

	/**
	 * The one column it climbs, above the farm's bottom: beside the far end of the drop wall, standing on the booth's
	 * roof, with nothing of the farm in it all the way up, so at the end it rides it back down digging it out.
	 * Everything else up high it reaches by bridging across from it.
	 */
	static final int SPINE_X = 0, SPINE_Z = 5;

	boolean onSpine(BlockPos a) {
		return a.getX() - bed.getX() == SPINE_X && a.getZ() - bed.getZ() == SPINE_Z;
	}

	/** What goes in a fill: the farm's own block where the farm has one, else scaffolding. */
	BlockState fill(BlockPos c) {
		BlockState want = targets.get(c);
		return want != null ? want : scaffoldState;
	}

	static boolean isCube(BlockState s) {
		return s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.GLASS);
	}

	private boolean diggable(BlockPos p) {
		BlockState s = get(p);
		if (s.isAir() || !s.getFluidState().isEmpty()) return false;
		return scaffoldLeft.contains(p) || !targets.containsKey(p) && phase == Phase.BUILD && toBreak.contains(p);
	}

	/** Air already, or a plain farm block it may break through and put back: above the lake, away from water. */
	boolean tunnelable(BlockPos p) {
		if (clear(p)) return true;
		BlockState s = get(p), want = targets.get(p);
		if (want == null || !Printer.matches(want, s) || !(isCube(want) || want.getBlock() instanceof net.minecraft.block.SlabBlock)) return false;
		if (p.getY() <= padY || protectedCell(p)) return false;
		for (Direction d : Direction.values()) if (!get(p.offset(d)).getFluidState().isEmpty()) return false;
		return true;
	}

	static Vec3d eye(BlockPos feet, double base) {
		return new Vec3d(feet.getX() + 0.5, feet.getY() + base + EYE, feet.getZ() + 0.5);
	}

	static Box body(BlockPos feet, double base) {
		return new Box(feet.getX() + 0.2, feet.getY() + base, feet.getZ() + 0.2, feet.getX() + 0.8, feet.getY() + base + 1.8, feet.getZ() + 0.8);
	}

	// ---- Moves ----

	private record Move(Step step, double base, double cost) {}

	String debugMoves(BlockPos a) {
		StringBuilder b = new StringBuilder("floor " + floor(a) + " clear up2 " + clear(a.up(2)) + " canFill " + canFill(a) + " clickable below " + clickable(a.down()) + ":");
		for (Move m : moves(a, floor(a), true)) b.append(" ").append(m.step.kind()).append(">").append(m.step.to().subtract(bed).toShortString());
		return b.toString();
	}

	private List<Move> moves(BlockPos a, double baseA, boolean allowFill) {
		// Standing here means its own scaffolding in these two cells has been dug out on the way.
		BlockState w0 = null, w1 = null;
		boolean d0 = !clear(a) && scaffoldLeft.contains(a), d1 = !clear(a.up()) && scaffoldLeft.contains(a.up());
		if (d0) w0 = over.put(a, Blocks.AIR.getDefaultState());
		if (d1) w1 = over.put(a.up(), Blocks.AIR.getDefaultState());
		try {
			return movesHere(a, baseA, allowFill);
		} finally {
			if (d1) restore(a.up(), w1);
			if (d0) restore(a, w0);
		}
	}

	private List<Move> movesHere(BlockPos a, double baseA, boolean allowFill) {
		List<Move> out = new ArrayList<>(8);
		double ha = a.getY() + baseA;
		// Standing on a block this plan puts in on the way: it's there by the time this step comes.
		boolean planned = baseA == 0 && Double.isNaN(floor(a));
		boolean under = planned || clickable(a.down());
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos b = a.offset(d);
			for (int dy : new int[]{0, 1, -1, -2, -3}) {
				BlockPos c = b.up(dy);
				double bc = floor(c);
				if (Double.isNaN(bc)) continue;
				double dh = c.getY() + bc - ha;
				if (dy == 1 && !clear(a.up(2))) continue;
				if (dy < 0) {
					boolean open = clear(b) && clear(b.up());
					for (int k = dy + 1; k < 0 && open; k++) open = clear(b.up(k));
					if (!open) continue;
				}
				if (Math.abs(dh) <= 0.6) out.add(new Move(new Step(Kind.WALK, c, null), bc, 1));
				else if (dh > 0.6 && dh <= 1.2) out.add(new Move(new Step(Kind.ASCEND, c, null), bc, 2));
				else if (dh < -0.6 && dh >= -3.05) out.add(new Move(new Step(Kind.DESCEND, c, null), bc, 1.5 - dh * 0.4));
			}
			// Last resort, walled in: break through a farm block or two (above the water, never next to it); the
			// printer puts them back once it's out.
			if (allowFill && baseA == 0 && !(clear(b) && clear(b.up())) && tunnelable(b) && tunnelable(b.up())) {
				BlockState w1 = over.put(b, Blocks.AIR.getDefaultState()), w2 = over.put(b.up(), Blocks.AIR.getDefaultState());
				double bb = floor(b);
				restore(b.up(), w2);
				restore(b, w1);
				if (!Double.isNaN(bb) && Math.abs(bb) < 0.01 && !protectedCell(b))
					out.add(new Move(new Step(Kind.TUNNEL, b, clear(b) ? b.up() : b), 0, 30));
			}
			if (allowFill && baseA == 0 && Double.isNaN(floor(b)) && clear(b) && clear(b.up()) && canFill(b.down()) && !(onSpine(b.down()) && b.down().getY() > floorY + 3)
					&& (under || sea(b.down()))) {
				boolean farmBlock = targets.containsKey(b.down());
				out.add(new Move(new Step(Kind.BRIDGE, b, b.down()), 0, farmBlock ? 2 : fillCost()));
			}
		}
		// Pillars are always scaffolding, never the farm's own walls: it's the way back down (dug out at the end).
		if (allowFill && baseA == 0 && clear(a.up(2)) && canFill(a) && !targets.containsKey(a) && under && (a.getY() <= floorY + 3 || onSpine(a)))
			out.add(new Move(new Step(Kind.PILLAR, a.up(), a), 0, fillCost()));
		if (baseA == 0 && diggable(a.down())) {
			BlockPos d = a.down();
			BlockState was = over.put(d, Blocks.AIR.getDefaultState());
			double bd = floor(d);
			restore(d, was);
			if (!Double.isNaN(bd)) out.add(new Move(new Step(Kind.DIG, d, d), bd, 3));
		}
		return out;
	}

	static boolean DEBUG;
	Map<BlockPos, Double> lastSeen;
	int lastExpanded;
	BlockPos lastClosest;

	/** Putting new scaffolding down while taking it away is a last resort. */
	private double fillCost() {
		return phase == Phase.CLEAN || phase == Phase.CLEAR_LAKE ? 6 : 5;
	}

	interface Goal {
		boolean at(BlockPos feet, double base, Step last);
	}

	/** Cheapest way (A*) from the feet to somewhere that meets the goal; null if there's none in reach of the search. */
	List<Step> search(BlockPos start, double startBase, Goal goal, Vec3d toward, boolean allowFill, int limit) {
		if (goal.at(start, startBase, null)) return List.of();
		record Node(BlockPos pos, double g, double f) {}
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
		Map<BlockPos, Double> best = new HashMap<>();
		Map<BlockPos, Double> baseOf = new HashMap<>();
		Map<BlockPos, Step> via = new HashMap<>();
		Map<BlockPos, BlockPos> parent = new HashMap<>();
		best.put(start, 0.0);
		baseOf.put(start, startBase);
		open.add(new Node(start, 0, h(start, startBase, toward)));
		int expanded = 0;
		double closest = Double.MAX_VALUE;
		while (!open.isEmpty() && expanded < limit) {
			Node n = open.poll();
			if (toward != null) {
				double d = eye(n.pos, baseOf.get(n.pos)).distanceTo(toward);
				if (d < closest) {
					closest = d;
					lastClosest = n.pos;
				}
			}
			if (n.g > best.getOrDefault(n.pos, Double.MAX_VALUE)) continue;
			expanded++;
			double base = baseOf.get(n.pos);
			if (n.pos != start && reached(goal, n.pos, base, start, via, parent)) {
				List<Step> path = new ArrayList<>();
				for (BlockPos p = n.pos; !p.equals(start); p = parent.get(p)) path.add(via.get(p));
				java.util.Collections.reverse(path);
				return path;
			}
			for (Move m : moves(n.pos, base, allowFill)) {
				BlockPos to = m.step.to();
				double g = n.g + m.cost;
				if (g >= best.getOrDefault(to, Double.MAX_VALUE)) continue;
				best.put(to, g);
				baseOf.put(to, m.base);
				via.put(to, m.step);
				parent.put(to, n.pos);
				open.add(new Node(to, g, g + h(to, m.base, toward)));
			}
		}
		lastExpanded = expanded;
		return null;
	}

	/** The goal, checked in the world as the path there leaves it (its pillars and bridges in, what it dug out gone). */
	private boolean reached(Goal goal, BlockPos at, double base, BlockPos start, Map<BlockPos, Step> via, Map<BlockPos, BlockPos> parent) {
		List<BlockPos> changed = new ArrayList<>();
		for (BlockPos p = at; !p.equals(start); p = parent.get(p)) {
			Step st = via.get(p);
			switch (st.kind()) {
				case PILLAR, BRIDGE -> {
					if (!over.containsKey(st.cell())) {
						over.put(st.cell(), fill(st.cell()));
						changed.add(st.cell());
					}
				}
				case DIG -> {
					if (!over.containsKey(st.cell())) {
						over.put(st.cell(), Blocks.AIR.getDefaultState());
						changed.add(st.cell());
					}
				}
				case TUNNEL -> {
					for (BlockPos q : new BlockPos[]{st.to(), st.to().up()})
						if (!over.containsKey(q)) {
							over.put(q, Blocks.AIR.getDefaultState());
							changed.add(q);
						}
				}
				default -> { }
			}
		}
		// New scaffolding on the way counts as scaffolding.
		List<BlockPos> added = new ArrayList<>();
		for (BlockPos c : changed) if (!targets.containsKey(c) && !over.get(c).isAir() && scaffoldLeft.add(c)) added.add(c);
		List<BlockPos> dug = new ArrayList<>();
		for (BlockPos c : changed) if (over.get(c).isAir() && scaffoldLeft.remove(c)) dug.add(c);
		try {
			return goal.at(at, base, via.get(at));
		} finally {
			for (BlockPos c : changed) over.remove(c);
			scaffoldLeft.removeAll(added);
			scaffoldLeft.addAll(dug);
		}
	}

	/** For the tests: the escape check at the end of a path, in the world as the path leaves it. */
	String replay(BlockPos start, List<Step> path) {
		Map<BlockPos, Step> via = new HashMap<>();
		Map<BlockPos, BlockPos> parent = new HashMap<>();
		BlockPos prev = start;
		for (Step st : path) {
			via.put(st.to(), st);
			parent.put(st.to(), prev);
			prev = st.to();
		}
		BlockPos end = prev;
		StringBuilder out = new StringBuilder();
		reached((f, b, st) -> {
			out.append("over ").append(over.keySet().stream().map(x -> x.subtract(bed).toShortString()).toList()).append(" at022 ").append(get(bed.add(0, 2, 2))).append(" floor022 ").append(floor(bed.add(0, 2, 2)));
			out.append(" end floor ").append(floor(f)).append(" escape ").append(canEscape(f, b)).append(" seen ").append(lastSeen.keySet().stream().map(x -> x.subtract(bed).toShortString() + (escape(x, lastSeen.get(x)) ? "!" : "")).toList());
			return true;
		}, end, 0, start, via, parent);
		return out.toString();
	}

	private static double h(BlockPos p, double base, Vec3d toward) {
		return toward == null ? 0 : Math.max(0, eye(p, base).distanceTo(toward) - REACH) * 7.0;
	}

	// ---- What can be done from a spot ----

	/** Could the printer put this farm block in from here? */
	boolean canPlaceFrom(BlockPos feet, double base, BlockPos t) {
		BlockState want = targets.get(t);
		if (want == null || body(feet, base).intersects(new Box(t))) return false;
		return Printer.placement(world, t, want, eye(feet, base), REACH) != null;
	}

	/** Can a full block go in {@code c} from here (scaffolding, or a step's farm block)? */
	boolean canFillFrom(BlockPos feet, double base, BlockPos c) {
		if (body(feet, base).intersects(new Box(c))) return false;
		Vec3d eye = eye(feet, base);
		if (sea(c)) return Vec3d.ofCenter(c).distanceTo(eye) <= REACH;
		for (Direction d : Direction.values()) {
			BlockPos n = c.offset(d);
			if (!clickable(n)) continue;
			Vec3d hit = Vec3d.ofCenter(c).add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
			if (hit.distanceTo(eye) <= REACH) return true;
		}
		return false;
	}

	boolean canBreakFrom(BlockPos feet, double base, BlockPos r) {
		if (r.equals(feet.down()) && base == 0 || r.equals(feet) || r.equals(feet.up())) return false;
		if (body(feet, base).intersects(new Box(r))) return false;
		return Vec3d.ofCenter(r).distanceTo(eye(feet, base)) <= REACH;
	}

	/** A bucket pours where you look: from here, does a look at the water's spot land on the floor under it? */
	boolean canPourFrom(BlockPos feet, double base, BlockPos t) {
		Vec3d eye = eye(feet, base);
		Vec3d at = new Vec3d(t.getX() + 0.5, t.getY(), t.getZ() + 0.5);
		if (at.distanceTo(eye) > REACH) return false;
		if (body(feet, base).intersects(new Box(t))) return false;
		BlockHitResult ray = world.raycast(new RaycastContext(eye, at.add(at.subtract(eye).normalize().multiply(0.2)), RaycastContext.ShapeType.OUTLINE,
				RaycastContext.FluidHandling.NONE, ShapeContext.absent()));
		return ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(t.down()) && ray.getSide() == Direction.UP;
	}

	// ---- Not walling itself in ----

	/**
	 * After a block goes in {@code c}, can the player still get out from where it stands, by walking, stepping and
	 * dropping, to somewhere it could pillar up from (or out of the farm)? A block that would shut it in (the last
	 * gap in the floor's glass rim with it inside, say) waits until it has left.
	 */
	boolean safeToFill(BlockPos c, BlockPos feet, double base) {
		if (body(feet, base).intersects(new Box(c))) return false;
		BlockState was = over.put(c, targets.getOrDefault(c, scaffoldState));
		try {
			return canEscape(feet, base);
		} finally {
			restore(c, was);
		}
	}

	/** Can it get from here to a way out ({@link #escape}) by walking, stepping and dropping? */
	boolean canEscape(BlockPos feet, double base) {
		{
			ArrayDeque<BlockPos> q = new ArrayDeque<>();
			Map<BlockPos, Double> seen = new HashMap<>();
			q.add(feet);
			seen.put(feet, base);
			lastSeen = seen;
			while (!q.isEmpty() && seen.size() < 2500) {
				BlockPos a = q.poll();
				double ba = seen.get(a);
				if (escape(a, ba)) return true;
				for (Move m : moves(a, ba, false)) {
					if (seen.containsKey(m.step.to())) continue;
					seen.put(m.step.to(), m.base);
					q.add(m.step.to());
				}
			}
			return false;
		}
	}

	/**
	 * A way out, at the end of walking, stepping, dropping and digging down through its own scaffolding: off the
	 * farm, or down at the bottom somewhere it could climb again from (anywhere at the bottom, once it's clearing up).
	 * Up high nowhere counts: with walls round the pad it could be left with nothing to come down by.
	 */
	private boolean escape(BlockPos a, double base) {
		BlockPos r = a.subtract(bed);
		if (Math.abs(r.getX()) > 10 || Math.abs(r.getZ()) > 10) return true;
		// Above the water's level, once the lake's in: a step off the edge straight down into the open sea (landing
		// in water takes no fall damage, from any height).
		if (phase != Phase.BUILD && phase != Phase.CLEAN && seaDrop(a) != null) return true;
		if (a.getY() + base > floorY + 4.01) return false;
		// Down at the bottom: done, when clearing up; otherwise somewhere it can climb again from.
		if (phase == Phase.CLEAN) return true;
		// (Its own scaffolding overhead is what it dug down through to get here.)
		return base == 0 && (clear(a.up(2)) || scaffoldLeft.contains(a.up(2))) && (clear(a.up(3)) || scaffoldLeft.contains(a.up(3)))
				&& (clickable(a.down()) || Double.isNaN(floor(a)));
	}

	/**
	 * Which way it could step off straight down into the open sea from here, with nothing to hit on the way; null if
	 * none.
	 */
	Direction seaDrop(BlockPos a) {
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos b = a.offset(d);
			if (!clear(b) || !clear(b.up())) continue;
			BlockPos r = b.subtract(bed);
			if (Math.abs(r.getX()) <= 9 && Math.abs(r.getZ()) <= 9 && farm.plan.inFootprint(r.getX(), r.getZ())) continue;
			boolean open = true;
			for (int y = b.getY() - 1; y >= floorY && open; y--) open = clear(b.withY(y));
			if (open && sea(b.withY(floorY - 1))) return d;
		}
		return null;
	}

	/** After pouring here and the lake filling, is there still a way out? */
	boolean safeToPour(BlockPos feet, double base) {
		List<BlockPos> wet = new ArrayList<>();
		for (int x = -4; x <= 3; x++)
			for (int z = -4; z <= 3; z++)
				for (int y = FarmPlan.PAD_Y - 1; y <= FarmPlan.PAD_Y; y++) {
					if (FarmPlan.dropCell(x, z)) continue;
					BlockPos q = bed.add(x, y, z);
					if (get(q).isAir() && !over.containsKey(q)) {
						over.put(q, Blocks.WATER.getDefaultState());
						wet.add(q);
					}
				}
		try {
			return canEscape(feet, base);
		} finally {
			for (BlockPos q : wet) over.remove(q);
		}
	}

	/** After breaking {@code r} from here, is there still a way out? */
	boolean safeToBreak(BlockPos r, BlockPos feet, double base) {
		BlockState was = over.put(r, r.getY() == floorY - 1 ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState());
		try {
			double b = floor(feet);
			if (Double.isNaN(b)) b = base;
			return canEscape(feet, b);
		} finally {
			restore(r, was);
		}
	}

	// ---- Choosing the next job ----

	/** The next job for a player whose feet are in {@code feet}, {@code base} up from its bottom (NaN: not standing). */
	Job next(BlockPos feet) {
		double base;
		if (phase == Phase.DONE) return new Job(Act.DONE, null, null, List.of(), "done");
		base = floor(feet);
		if (Double.isNaN(base)) return new Job(Act.BOOT, null, null, List.of(), "not standing on anything");
		Vec3d eye = eye(feet, base);
		final double b0 = base;
		if (phase == Phase.WATER) {
			BlockPos t = Printer.nextWater(world, farm);
			if (t == null) return new Job(Act.STUCK, null, null, List.of(), "no water spot");
			List<Step> path = search(feet, base, (f, b, s) -> canPourFrom(f, b, t) && safeToPour(f, b), Vec3d.ofCenter(t), true, 30000);
			if (path == null) return new Job(Act.STUCK, t, targets.get(t), List.of(), "can't get to the water spot at " + t.toShortString());
			return new Job(Act.POUR, t, targets.get(t), path, "water");
		}

		List<BlockPos> breaks = new ArrayList<>(toBreak);
		breaks.removeIf(skip::contains);
		if (phase == Phase.CLEAN || phase == Phase.CLEAR_LAKE) {
			// Top down; on each level the block nearest the way down (a pillar's top) last.
			// Never on to a lower layer while anything above is left (skipped or not): it would leave it stranded.
			int topY = toBreak.stream().mapToInt(BlockPos::getY).max().orElse(0);
			List<BlockPos> level = new ArrayList<>();
			for (BlockPos p : breaks) if (p.getY() == topY) level.add(p);
			if (level.isEmpty()) for (BlockPos p : toBreak) if (p.getY() == topY) level.add(p);
			BlockPos exit = null;
			double ed = Double.MAX_VALUE;
			for (BlockPos p : level) {
				boolean pillar = !get(p.down()).getCollisionShape(world, p.down()).isEmpty();
				double d = Vec3d.ofCenter(p).squaredDistanceTo(eye) + (pillar ? 0 : 1e4);
				if (d < ed) {
					ed = d;
					exit = p;
				}
			}
			final BlockPos ex = exit;
			level.sort(Comparator.comparingDouble((BlockPos p) -> -p.getSquaredDistance(ex)));
			// The last one is the block underfoot: break it and drop into the sea (or onto whatever is below).
			if (toBreak.size() == 1 && toBreak.get(0).equals(feet.down())) return new Job(Act.BREAK, feet.down(), null, List.of(), "last");
			// When all that's left is on the sea, nothing new goes in to reach the rest: it walks to them.
			boolean fills = toBreak.stream().anyMatch(p -> p.getY() >= floorY);
			int tried = 0;
			for (BlockPos r : level) {
				if (tried++ >= 4) break;
				List<Step> path = search(feet, base, (f, b, s) -> s != null && s.kind() == Kind.DIG && r.equals(s.cell())
								|| canBreakFrom(f, b, r) && safeToBreak(r, f, b), Vec3d.ofCenter(r), fills, 30000);
				if (path == null && DEBUG) System.out.println("  clean miss " + r.subtract(bed).toShortString() + " from " + feet.subtract(bed).toShortString() + " fills " + fills
						+ " expanded " + lastExpanded + " closest " + (lastClosest == null ? null : lastClosest.subtract(bed).toShortString())
						+ (lastClosest == null ? "" : " canBreak " + canBreakFrom(lastClosest, floor(lastClosest), r) + " safe " + safeToBreak(r, lastClosest, floor(lastClosest))));
				if (path != null) {
					// Digging down through it is the break itself.
					if (!path.isEmpty() && path.get(path.size() - 1).kind() == Kind.DIG && r.equals(path.get(path.size() - 1).cell()))
						return new Job(Act.BREAK, r, null, path.subList(0, path.size() - 1), "dig");
					return new Job(Act.BREAK, r, null, path, "scaffolding");
				}
			}
			// Up high with no way down left to it: step off into the sea and climb back up from there.
			if (feet.getY() > floorY + 4) {
				List<Step> path = search(feet, base, (f, b, s) -> seaDrop(f) != null, null, false, 20000);
				if (path != null) return new Job(Act.FALL, null, null, path, "into the sea");
			}
			return new Job(Act.STUCK, level.isEmpty() ? null : level.get(0), null, List.of(), "can't reach the scaffolding at "
					+ (level.isEmpty() ? "?" : level.get(0).toShortString()));
		}

		// BUILD and TOP: blocks to break first, then farm blocks that can go in now, low and near first.
		record Cand(BlockPos pos, boolean brk, double score) {}
		List<Cand> cands = new ArrayList<>();
		// Bottom up, a few layers at a time: it can't climb down off the farm, only dig down its own pillar.
		int lowY = Integer.MAX_VALUE;
		List<BlockPos> place = new ArrayList<>();
		for (BlockPos p : toPlace) {
			if (skip.contains(p)) continue;
			if (!Printer.placeableNow(world, p, targets.get(p))) continue;
			if (!have.test(targets.get(p))) continue;
			place.add(p);
			lowY = Math.min(lowY, p.getY());
		}
		for (BlockPos p : breaks) lowY = Math.min(lowY, p.getY());
		for (int band : new int[]{1, 4, 12, 1000}) {
			cands.clear();
			for (BlockPos p : place) if (p.getY() <= lowY + band) cands.add(new Cand(p, false, Vec3d.ofCenter(p).distanceTo(eye) + 0.6 * (p.getY() - lowY)));
			for (BlockPos p : breaks) if (p.getY() <= lowY + band) cands.add(new Cand(p, true, Vec3d.ofCenter(p).distanceTo(eye) + 0.6 * (p.getY() - lowY) - 3));
			cands.sort(Comparator.comparingDouble(Cand::score));
			int tries = 0;
			for (Cand c : cands) {
				if (tries++ >= 8) break;
				BlockPos t = c.pos;
				List<Step> path = c.brk
						? search(feet, base, (f, b, s) -> s != null && s.kind() == Kind.DIG && t.equals(s.cell()) || canBreakFrom(f, b, t) && safeToBreak(t, f, b), Vec3d.ofCenter(t), true, 25000)
						: search(feet, base, (f, b, s) -> (s != null && t.equals(s.cell()) || canPlaceFrom(f, b, t) && safeToFill(t, f, b)) && canEscape(f, b), Vec3d.ofCenter(t), true, 25000);
				if (path == null) {
					skip.add(t);
					if (DEBUG) System.out.println("  skip " + t.subtract(bed).toShortString() + " from " + feet.subtract(bed).toShortString() + " expanded " + lastExpanded
							+ " closest " + (lastClosest == null ? null : lastClosest.subtract(bed).toShortString()) + " start escapes " + canEscape(feet, base) + " seen " + lastSeen.size()
							+ " spine top moves: " + debugMoves(bed.add(SPINE_X, feet.getY() - bed.getY(), SPINE_Z)) + " / " + debugMoves(bed.add(SPINE_X, feet.getY() - bed.getY() + 1, SPINE_Z))
							+ " below start " + get(feet.down()).getBlock() + "/" + scaffoldLeft.contains(feet.down()) + " " + get(feet.down(2)).getBlock() + "/" + scaffoldLeft.contains(feet.down(2)));
					continue;
				}
				if (!path.isEmpty() && path.get(path.size() - 1).cell() != null && t.equals(path.get(path.size() - 1).cell()))
					return new Job(c.brk ? Act.BREAK : Act.PLACE, t, targets.get(t), path, "by stepping on it");
				return new Job(c.brk ? Act.BREAK : Act.PLACE, t, targets.get(t), path, c.brk ? "break" : "place");
			}
			if (!cands.isEmpty() && band >= 12) break;
		}
		if (cands.isEmpty() && !toPlace.isEmpty()) {
			// Nothing can go in yet (a fresh farm over open water): put a block of scaffolding under the lowest one.
			BlockPos t = toPlace.stream().filter(p -> !skip.contains(p)).min(Comparator.comparingInt(BlockPos::getY)
					.thenComparingDouble(p -> Vec3d.ofCenter(p).squaredDistanceTo(eye))).orElse(null);
			if (t != null) {
				for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
					BlockPos s = t.offset(d);
					if (targets.containsKey(s) || !canFill(s)) continue;
					boolean supported = sea(s);
					for (Direction e : Direction.values()) supported |= clickable(s.offset(e));
					if (!supported) continue;
					List<Step> path = search(feet, b0, (f, b, st) -> canFillFrom(f, b, s) && safeToFill(s, f, b), Vec3d.ofCenter(s), true, 25000);
					if (path != null) return new Job(Act.SUPPORT, s, scaffoldState, path, "a block to build on");
				}
			}
		}
		if (!cands.isEmpty() && feet.getY() > floorY + 4) {
			// Nothing in reach from up here any more: step off into the sea and come back up somewhere better.
			List<Step> path = search(feet, base, (f, b, s) -> seaDrop(f) != null, null, false, 20000);
			if (path != null) return new Job(Act.FALL, null, null, path, "into the sea, to start again from below");
		}
		if (cands.isEmpty()) {
			String why = toPlace.isEmpty() ? "nothing to do" : "nothing can be placed (out of " + missingItem() + "?)";
			return new Job(Act.STUCK, null, null, List.of(), why);
		}
		return new Job(Act.STUCK, cands.get(0).pos, null, List.of(), "can't find a way to " + cands.get(0).pos.toShortString());
	}

	private String missingItem() {
		for (BlockPos p : toPlace) if (!have.test(targets.get(p))) return Printer.itemName(targets.get(p));
		return "blocks";
	}
}
