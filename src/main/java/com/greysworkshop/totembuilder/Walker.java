package com.greysworkshop.totembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The walking auto-builder, in the game: asks {@link Walk} (on its own thread, over a {@link Snapshot}) what to do
 * next and does it with your movement keys and clicks, as a player would. It walks sneaking (so it never steps off an
 * edge), jumps up steps, pillars with your scaffolding block looking straight down, bridges by placing in front of its
 * feet, and digs back down its pillar with the best tool you carry. Farm blocks within reach go in through the
 * printer, held to what's due now (layer by layer, and never one that would shut it in). Every block it puts in or
 * takes out counts against the printer's speed limit, and it turns your view to look at what it clicks, the tick
 * before, so a server sees nothing a player couldn't do. You can stop it any time ({@code /totem stop}).
 */
final class Walker {
	private Walker() {}

	static String status = "";
	private static boolean on;
	private static Walk.Job job;
	private static int step, sub, ticks, stepTicks, actTicks, failures;
	private static CompletableFuture<Walk.Job> planning;
	private static Set<BlockPos> planningSkip;
	private static Walk live;
	private static int liveAge, saveAge;
	private static final Set<BlockPos> skip = new HashSet<>();
	private static int skipAge;
	private static boolean dirty;
	private static final java.util.Map<BlockPos, Boolean> safe = new java.util.HashMap<>();
	private static BlockPos safeFeet;
	private static int safeAt;
	private static int placed, broken;
	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Totem Builder planner");
		t.setDaemon(true);
		return t;
	});

	/** A block it is putting in itself: looks at it one tick, clicks the next, then waits to see it there. */
	private static BlockPos placing;
	private static BlockState placingState;
	private static int placeStage, placeTicks;
	private static BlockHitResult placeHit;

	static boolean active() {
		return on;
	}

	static int placed() {
		return placed;
	}

	static int broken() {
		return broken;
	}

	static Walk.Job job() {
		return job;
	}

	static void start(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (p == null || client.world == null) return;
		AutoBuild.stop(client, null);
		if (p.getAbilities().flying || p.isGliding()) {
			say(client, "Land first: stand on a block (the farm, or one you put down), or be in the water by it. It builds on foot, no flying.", Formatting.YELLOW);
			return;
		}
		if (TotemBuilder.farm == null) {
			TotemBuilder.planHere(client);
			say(client, "Planned the farm right here. It builds it all on foot once the land check is done.", Formatting.AQUA);
		}
		Farm farm = TotemBuilder.farm;
		if (p.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(farm.bed.add(0, FarmPlan.FLOOR_Y, 0))) > 40 * 40 && p.getY() < farm.bed.getY() + FarmPlan.PAD_Y + 20
				&& Math.hypot(p.getX() - farm.bed.getX(), p.getZ() - farm.bed.getZ()) > 14) {
			say(client, "Get next to the farm first (within 14 blocks of its middle, on a block or in the water beside it).", Formatting.YELLOW);
			return;
		}
		farm.plan.inFootprint(0, 0);
		on = true;
		job = null;
		planning = null;
		skip.clear();
		failures = 0;
		ticks = 0;
		TotemBuilder.printing = true;
		Survey s = Survey.of(client);
		int sc = Survey.count(p.getInventory(), Settings.scaffold);
		StringBuilder shortOf = new StringBuilder();
		if (s != null) for (var e : s.need.entrySet()) {
			int have = s.have.getOrDefault(e.getKey(), 0);
			if (have < e.getValue()) shortOf.append(shortOf.isEmpty() ? "" : ", ").append(e.getValue() - have).append(" ").append(name(e.getKey()));
		}
		say(client, "Building on foot with " + Settings.scaffoldName() + " as scaffolding (you have " + sc + "; it uses a few hundred and takes them all back up at the end)."
				+ (shortOf.isEmpty() ? "" : " Short of: " + shortOf + ": it stops and says when it runs out.") + " /totem stop stops it.", Formatting.AQUA);
		if (!hasPickaxe(p.getInventory())) say(client, "Bring a pickaxe: taking the scaffolding back is slow by hand.", Formatting.YELLOW);
	}

	static void stop(MinecraftClient client, String why) {
		boolean was = on;
		on = false;
		job = null;
		planning = null;
		placing = null;
		status = "";
		Printer.allow = null;
		Printer.paused = false;
		release(client);
		if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		if (was && TotemBuilder.farm != null) Farm.save(client, TotemBuilder.farm);
		if (why != null && was) say(client, why, Formatting.YELLOW);
	}

	static void tick(MinecraftClient client) {
		if (!on) return;
		ClientPlayerEntity p = client.player;
		ClientWorld world = client.world;
		Farm farm = TotemBuilder.farm;
		if (p == null || world == null || farm == null) {
			stop(client, null);
			return;
		}
		if (client.currentScreen != null) {
			release(client);
			return;
		}
		if (p.isDead()) {
			stop(client, "Stopped: you died.");
			return;
		}
		if (p.getHealth() < 7) {
			stop(client, "Stopped: low on health. Eat and heal, then start it again.");
			return;
		}
		ticks++;
		TotemBuilder.printing = true;
		forgetGone(world, farm);
		if (++saveAge > 200 && dirty) {
			saveAge = 0;
			dirty = false;
			Farm.save(client, farm);
		}
		if (++skipAge > 20 * 60) {
			skipAge = 0;
			skip.clear();
		}
		BlockPos feet = feetCell(p);
		// The printer works from the live world: only what's due, never shutting it in.
		if (live == null || ++liveAge >= 40) {
			liveAge = 0;
			live = new Walk(world, farm, farm.sealed || Printer.villagerInside(world, farm), scaffoldState(), new HashSet<>(), have(p.getInventory()));
		}
		Walk lw = live;
		double base = lw.floor(feet);
		// Each answer kept for a second while it stands in the same place: the check walks the farm, so not every tick.
		if (!feet.equals(safeFeet) || ticks - safeAt > 20) {
			safe.clear();
			safeFeet = feet;
			safeAt = ticks;
		}
		Printer.allow = pos -> lw.allowPlace(pos) && !Double.isNaN(base) && safe.computeIfAbsent(pos, q -> lw.safeToFill(q, feet, base));

		if (eat(client, p)) return;
		// The printer opens a gate next tick: stand still and stop sneaking, or the click would put a block in instead.
		if (TotemBuilder.printer.opensNext()) {
			release(client);
			return;
		}
		if (placing != null) {
			Printer.paused = true;
			placeTick(client, p, world, farm);
			return;
		}
		if (job == null) {
			Printer.paused = false;
			release(client);
			plan(client, p, world, farm, feet);
			return;
		}
		if (step < job.path().size()) {
			Walk.Step s = job.path().get(step);
			status = describe(job) + " (step " + (step + 1) + "/" + job.path().size() + ")";
			Printer.paused = s.kind() != Walk.Kind.WALK && s.kind() != Walk.Kind.ASCEND && s.kind() != Walk.Kind.DESCEND;
			stepTicks++;
			int result = doStep(client, p, world, farm, s);
			if (result > 0) {
				step++;
				sub = 0;
				stepTicks = 0;
			} else if (result < 0 || stepTicks > 20 * 12) {
				TotemBuilder.LOGGER.info("[walk] step {} {} failed after {} ticks at {}", s.kind(), s.to().subtract(farm.bed).toShortString(), stepTicks, feet.subtract(farm.bed).toShortString());
				release(client);
				job = null;
			}
			return;
		}
		status = describe(job);
		actTicks++;
		if (act(client, p, world, farm, feet)) {
			release(client);
			job = null;
		}
	}

	private static void plan(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, Farm farm, BlockPos feet) {
		if (planning == null) {
			// Stand still while it thinks: it's a tick or two.
			if (p.getVelocity().horizontalLengthSquared() > 0.0004 && p.isOnGround()) return;
			Snapshot snap = new Snapshot(world, farm, world.getBottomY(), world.getHeight());
			planningSkip = new HashSet<>(skip);
			Walk w = new Walk(snap, farm, farm.sealed || Printer.villagerInside(world, farm), scaffoldState(), planningSkip, have(p.getInventory()));
			planning = CompletableFuture.supplyAsync(() -> w.next(feet), POOL);
			return;
		}
		if (!planning.isDone()) return;
		Walk.Job j;
		try {
			j = planning.get();
		} catch (Exception e) {
			TotemBuilder.LOGGER.warn("Planning failed", e);
			planning = null;
			stop(client, "Stopped: the planner hit a problem (" + e + ").");
			return;
		}
		planning = null;
		skip.addAll(planningSkip);
		TotemBuilder.LOGGER.info("[walk] job {} {} {} path {} from {}", j.act(), j.why(), j.target() == null ? "" : j.target().subtract(farm.bed).toShortString(), j.path().size(),
				feet.subtract(farm.bed).toShortString());
		switch (j.act()) {
			case DONE -> {
				stop(client, null);
				say(client, "All done: every block of the farm is in and all the scaffolding is gone ("
						+ placed + " scaffolding put down and taken up). The villager goes in the green box: /totem villager.", Formatting.GREEN);
				return;
			}
			case STUCK -> {
				if (j.target() != null && skip.add(j.target())) return;
				if (++failures >= 4) {
					stop(client, "Stopped: " + j.why() + ". " + TotemBuilder.printer.lastProblem);
					return;
				}
				skip.clear();
				return;
			}
			default -> { }
		}
		failures = 0;
		job = j;
		step = 0;
		sub = 0;
		stepTicks = 0;
		actTicks = 0;
	}

	// ---- The moves ----

	/** 1 done, 0 still going, -1 it didn't work. */
	private static int doStep(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, Farm farm, Walk.Step s) {
		Vec3d to = stand(world, s.to());
		switch (s.kind()) {
			case WALK -> {
				return walkTo(client, p, to, true, false) ? 1 : 0;
			}
			case ASCEND -> {
				return walkTo(client, p, to, true, true) ? 1 : 0;
			}
			case DESCEND -> {
				return walkTo(client, p, to, false, false) ? 1 : 0;
			}
			case PILLAR -> {
				return pillar(client, p, world, farm, s);
			}
			case BRIDGE -> {
				if (sub == 0) {
					if (solid(world, s.cell())) sub = 1;
					else {
						startPlace(s.cell(), fillFor(farm, s.cell()));
						sub = 1;
						return 0;
					}
				}
				if (!solid(world, s.cell())) return -1;
				return walkTo(client, p, to, true, false) ? 1 : 0;
			}
			case DIG -> {
				return dig(client, p, world, farm, s.cell(), s.to());
			}
			case TUNNEL -> {
				for (BlockPos c : new BlockPos[]{s.to().up(), s.to()}) {
					if (world.getBlockState(c).getCollisionShape(world, c).isEmpty()) continue;
					breakBlock(client, p, world, farm, c);
					return stepTicks > 200 ? -1 : 0;
				}
				return walkTo(client, p, to, true, false) ? 1 : 0;
			}
		}
		return -1;
	}

	/** Jump, and at the top of the jump put a block where the feet were. */
	private static int pillar(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, Farm farm, Walk.Step s) {
		BlockPos cell = s.cell();
		if (sub == 0) {
			// Right over the middle of the block first.
			Vec3d c = new Vec3d(cell.getX() + 0.5, p.getY(), cell.getZ() + 0.5);
			if (!walkTo(client, p, c, true, false)) return 0;
			sub = 1;
		}
		if (solid(world, cell)) {
			client.options.jumpKey.setPressed(false);
			look(p, p.getYaw(), 90);
			if (p.isOnGround() && p.getY() >= cell.getY() + 0.99) return 1;
			return 0;
		}
		BlockState fill = fillFor(farm, cell);
		int slot = Printer.find(p.getInventory(), fill);
		if (slot < 0) {
			outOf(client, fill);
			return -1;
		}
		Printer.select(client, p, slot);
		look(p, p.getYaw(), 90);
		client.options.sneakKey.setPressed(true);
		client.options.forwardKey.setPressed(false);
		client.options.jumpKey.setPressed(p.isOnGround());
		if (p.getY() >= cell.getY() + 1.02 && !p.isOnGround() && TotemBuilder.printer.take()) {
			BlockHitResult hit = new BlockHitResult(new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5), Direction.UP, cell.down(), false);
			client.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
			p.swingHand(Hand.MAIN_HAND);
			if (!TotemBuilder.farm.plan.blocks().containsKey(cell.subtract(farm.bed))) {
				farm.scaffold.add(cell);
				placed++;
				dirty = true;
			}
		}
		return stepTicks > 80 ? -1 : 0;
	}

	/** Break the block underfoot and drop onto the one below it. */
	private static int dig(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, Farm farm, BlockPos cell, BlockPos to) {
		if (!world.getBlockState(cell).isAir() && world.getBlockState(cell).getFluidState().isEmpty()) {
			client.options.sneakKey.setPressed(true);
			client.options.forwardKey.setPressed(false);
			breakBlock(client, p, world, farm, cell);
			return stepTicks > 300 ? -1 : 0;
		}
		client.interactionManager.cancelBlockBreaking();
		return p.isOnGround() && Math.abs(p.getY() - stand(world, to).y) < 0.2 ? 1 : 0;
	}

	/** At the end of the way: the job itself. True when it's over (done or given up). */
	private static boolean act(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, Farm farm, BlockPos feet) {
		BlockPos t = job.target();
		switch (job.act()) {
			case PLACE, POUR -> {
				// The printer puts it in (it's in reach now); wait for it.
				Printer.paused = false;
				centre(client, p, world, feet);
				BlockState want = Printer.targets(farm, farm.sealed).get(t);
				if (want == null || Printer.need(want, world.getBlockState(t)) == Printer.Need.NOTHING) return true;
				if (job.act() == Walk.Act.POUR && Printer.find(p.getInventory(), want) < 0 && Printer.refillSpot(world, farm) == null && p.getInventory().count(Items.BUCKET) == 0) {
					stop(client, "Out of water buckets: it needs " + Printer.waterStillToPour(world, farm) + " more. Bring them and start it again.");
					return true;
				}
				// A gate that's in but shut only needs a click to open: no item for that.
				if (Printer.find(p.getInventory(), want) < 0 && !want.isOf(Blocks.WATER) && !Printer.needsOpening(want, world.getBlockState(t))) {
					outOf(client, want);
					return true;
				}
				if (actTicks > 60) {
					skip.add(t);
					return true;
				}
				return false;
			}
			case SUPPORT -> {
				if (solid(world, t)) return true;
				if (actTicks == 1) {
					startPlace(t, scaffoldState());
					return false;
				}
				return actTicks > 40;
			}
			case BREAK -> {
				Printer.paused = true;
				if (world.getBlockState(t).isAir() || !world.getBlockState(t).getFluidState().isEmpty()) {
					client.interactionManager.cancelBlockBreaking();
					// Broke the one it stood on: wait to land (or to be in the sea).
					return p.isOnGround() || p.isTouchingWater() || actTicks > 60;
				}
				client.options.sneakKey.setPressed(true);
				breakBlock(client, p, world, farm, t);
				if (actTicks > 400) {
					skip.add(t);
					return true;
				}
				return false;
			}
			case FALL -> {
				Printer.paused = true;
				if (p.isTouchingWater()) {
					release(client);
					return true;
				}
				Direction d = live.seaDrop(feet);
				if (d != null && p.isOnGround()) {
					BlockPos b = feet.offset(d);
					walkTo(client, p, new Vec3d(b.getX() + 0.5, p.getY(), b.getZ() + 0.5), false, false);
				} else release(client);
				if (actTicks > 20 * 20) {
					stop(client, "Stopped: couldn't step off into the sea.");
					return true;
				}
				return false;
			}
			case BOOT -> {
				return boot(client, p, world, farm);
			}
			default -> {
				return true;
			}
		}
	}

	/** Not standing anywhere: in the water, put a block on the surface beside it and climb up onto it. */
	private static boolean boot(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, Farm farm) {
		Printer.paused = true;
		if (p.getAbilities().flying || p.isGliding()) {
			stop(client, "Land first: it builds on foot.");
			return true;
		}
		if (!p.isTouchingWater() && !p.isOnGround()) {
			// Falling: wait a moment.
			release(client);
			if (actTicks > 100) {
				stop(client, "Stopped: not standing on anything it can build from. Stand on a block, or get in the water beside the farm.");
				return true;
			}
			return false;
		}
		int seaY = farm.bed.getY() + FarmPlan.FLOOR_Y - 1;
		BlockPos me = BlockPos.ofFloored(p.getX(), seaY, p.getZ());
		BlockPos block = null;
		for (BlockPos c : new BlockPos[]{me, me.north(), me.south(), me.east(), me.west()}) {
			if (solid(world, c) && world.getBlockState(c.up()).isAir() && world.getBlockState(c.up(2)).isAir()) {
				block = c;
				break;
			}
		}
		if (block == null) {
			BlockPos best = null;
			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos c = me.offset(d);
				if (!Printer.isSource(world, c) || !world.getBlockState(c.up()).isAir()) continue;
				if (p.getBoundingBox().intersects(new Box(c))) continue;
				if (farm.plan.blocks().containsKey(c.subtract(farm.bed))) continue;
				best = c;
				break;
			}
			if (best == null) {
				// Swim a little to open water.
				client.options.forwardKey.setPressed(true);
				client.options.jumpKey.setPressed(true);
				return actTicks > 200;
			}
			startPlace(best, scaffoldState());
			return false;
		}
		// Up onto it.
		Vec3d top = new Vec3d(block.getX() + 0.5, block.getY() + 1, block.getZ() + 0.5);
		face(p, top);
		client.options.forwardKey.setPressed(Math.hypot(top.x - p.getX(), top.z - p.getZ()) > 0.2);
		client.options.jumpKey.setPressed(true);
		client.options.sneakKey.setPressed(false);
		if (p.isOnGround() && !p.isTouchingWater() && p.getY() >= block.getY() + 0.99) {
			release(client);
			return true;
		}
		return actTicks > 200;
	}

	// ---- Putting a block in, taking one out ----

	private static void startPlace(BlockPos cell, BlockState state) {
		placing = cell;
		placingState = state;
		placeStage = 0;
		placeTicks = 0;
		placeHit = null;
	}

	private static void placeTick(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, Farm farm) {
		placeTicks++;
		release(client);
		client.options.sneakKey.setPressed(true);
		if (solid(world, placing)) {
			if (!farm.plan.blocks().containsKey(placing.subtract(farm.bed)) && placeStage >= 2) {
				farm.scaffold.add(placing);
				placed++;
				dirty = true;
			}
			placing = null;
			return;
		}
		if (placeTicks > 40) {
			TotemBuilder.LOGGER.info("[walk] couldn't place {} at {}", placingState.getBlock(), placing.subtract(farm.bed).toShortString());
			placing = null;
			job = null;
			return;
		}
		if (placeStage == 0) {
			placeHit = hitFor(world, placing, p.getEyePos());
			if (placeHit == null) {
				placing = null;
				job = null;
				return;
			}
			face(p, placeHit.getPos());
			placeStage = 1;
			return;
		}
		if (placeStage == 1) {
			// The look went out with last tick's movement: click now.
			int slot = Printer.find(p.getInventory(), placingState);
			if (slot < 0) {
				outOf(client, placingState);
				placing = null;
				return;
			}
			if (!TotemBuilder.printer.take()) return;
			Printer.select(client, p, slot);
			face(p, placeHit.getPos());
			client.interactionManager.interactBlock(p, Hand.MAIN_HAND, placeHit);
			p.swingHand(Hand.MAIN_HAND);
			placeStage = 2;
			return;
		}
		// Waiting to see it; try again after a while.
		if (placeTicks % 12 == 0) placeStage = 0;
	}

	/** Where to click to put a full block in {@code cell}: a face of a solid neighbour in reach, or the sea's own surface. */
	private static BlockHitResult hitFor(ClientWorld world, BlockPos cell, Vec3d eye) {
		double reach = 4.5;
		BlockHitResult best = null;
		double bd = Double.MAX_VALUE;
		for (Direction d : Direction.values()) {
			BlockPos n = cell.offset(d);
			if (!Printer.clickable(world, n)) continue;
			Vec3d hit = Vec3d.ofCenter(cell).add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
			double dist = hit.distanceTo(eye);
			if (dist > reach || dist >= bd) continue;
			bd = dist;
			best = new BlockHitResult(hit, d.getOpposite(), n, false);
		}
		if (best == null && world.getBlockState(cell).isOf(Blocks.WATER)) {
			// Nothing to build on out at sea: into the water itself.
			Vec3d hit = new Vec3d(cell.getX() + 0.5, cell.getY() + 0.9, cell.getZ() + 0.5);
			if (hit.distanceTo(eye) <= reach) best = new BlockHitResult(hit, Direction.UP, cell, false);
		}
		return best;
	}

	private static void breakBlock(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, Farm farm, BlockPos pos) {
		Vec3d c = Vec3d.ofCenter(pos);
		face(p, c);
		int tool = bestTool(p.getInventory(), world.getBlockState(pos));
		if (tool >= 0 && tool != p.getInventory().getSelectedSlot()) {
			Printer.select(client, p, tool);
			return;
		}
		Direction side = Direction.getFacing(p.getEyePos().subtract(c));
		client.interactionManager.updateBlockBreakingProgress(pos, side);
		p.swingHand(Hand.MAIN_HAND);
		if (world.getBlockState(pos).isAir() || !world.getBlockState(pos).getFluidState().isEmpty()) {
			if (farm.scaffold.remove(pos)) {
				broken++;
				dirty = true;
			}
		}
	}

	private static int bestTool(PlayerInventory inv, BlockState state) {
		int best = -1;
		float speed = 1.0001f;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			float v = s.getMiningSpeedMultiplier(state);
			if (v > speed) {
				speed = v;
				best = i;
			}
		}
		return best;
	}

	private static boolean hasPickaxe(PlayerInventory inv) {
		for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isIn(ItemTags.PICKAXES)) return true;
		return false;
	}

	/** Hungry: eat something before carrying on. */
	private static int eating;

	private static boolean eat(MinecraftClient client, ClientPlayerEntity p) {
		if (eating > 0) {
			eating--;
			client.options.useKey.setPressed(eating > 0);
			if (eating == 0) client.options.useKey.setPressed(false);
			return eating > 0;
		}
		if (p.getHungerManager().getFoodLevel() > 8 || job != null && step < job.path().size()) return false;
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || s.get(DataComponentTypes.FOOD) == null || s.isOf(Items.ROTTEN_FLESH) || s.isOf(Items.SPIDER_EYE) || s.isOf(Items.POISONOUS_POTATO)
					|| s.isOf(Items.PUFFERFISH) || s.isOf(Items.CHORUS_FRUIT)) continue;
			release(client);
			Printer.select(client, p, i);
			client.options.useKey.setPressed(true);
			eating = 45;
			status = "Eating";
			return true;
		}
		return false;
	}

	// ---- Helpers ----

	/** Scaffolding it put down that's gone (broken by hand, or by it): off the list. */
	private static void forgetGone(ClientWorld world, Farm farm) {
		if (ticks % 40 != 0) return;
		if (farm.scaffold.removeIf(q -> world.isChunkLoaded(q.getX() >> 4, q.getZ() >> 4) && (world.getBlockState(q).isAir() || !world.getBlockState(q).getFluidState().isEmpty())))
			dirty = true;
	}

	static BlockPos feetCell(ClientPlayerEntity p) {
		return BlockPos.ofFloored(p.getX(), p.getY() + 0.01, p.getZ());
	}

	/** Where the feet go standing in this cell (on a slab: half up). */
	private static Vec3d stand(ClientWorld world, BlockPos cell) {
		var sh = world.getBlockState(cell).getCollisionShape(world, cell);
		double y = sh.isEmpty() ? cell.getY() : cell.getY() + sh.getMax(Direction.Axis.Y);
		return new Vec3d(cell.getX() + 0.5, y, cell.getZ() + 0.5);
	}

	private static void centre(MinecraftClient client, ClientPlayerEntity p, ClientWorld world, BlockPos feet) {
		Vec3d c = stand(world, feet);
		if (Math.hypot(c.x - p.getX(), c.z - p.getZ()) > 0.2) walkTo(client, p, c, true, false);
		else release(client);
	}

	/**
	 * Walk to a spot, turned towards it, sneaking if asked (it can't step off an edge then), jumping if asked. True
	 * once it's there, on the ground and still.
	 */
	private static boolean walkTo(MinecraftClient client, ClientPlayerEntity p, Vec3d to, boolean sneak, boolean jump) {
		double dx = to.x - p.getX(), dz = to.z - p.getZ(), flat = Math.sqrt(dx * dx + dz * dz);
		double speed = p.getVelocity().horizontalLength();
		boolean there = flat < 0.15 && p.isOnGround() && Math.abs(p.getY() - to.y) < 0.2 && speed < 0.05;
		if (there) {
			release(client);
			return true;
		}
		if (flat > 0.05) p.setYaw((float) (MathHelper.atan2(dz, dx) * 180 / Math.PI) - 90);
		p.setPitch(40);
		// Ease off close in, so it stops on the spot.
		boolean forward = flat > 0.15 && !(flat < 0.45 && speed > 0.06);
		client.options.forwardKey.setPressed(forward);
		client.options.backKey.setPressed(false);
		client.options.leftKey.setPressed(false);
		client.options.rightKey.setPressed(false);
		client.options.sneakKey.setPressed(sneak);
		client.options.sprintKey.setPressed(false);
		client.options.jumpKey.setPressed(jump && p.isOnGround() && flat < 1.3 && p.getY() < to.y - 0.3);
		return false;
	}

	private static void face(ClientPlayerEntity p, Vec3d at) {
		Vec3d d = at.subtract(p.getEyePos());
		look(p, (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90, (float) -(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI));
	}

	private static void look(ClientPlayerEntity p, float yaw, float pitch) {
		p.setYaw(yaw);
		p.setPitch(MathHelper.clamp(pitch, -90, 90));
	}

	static void release(MinecraftClient client) {
		client.options.forwardKey.setPressed(false);
		client.options.backKey.setPressed(false);
		client.options.leftKey.setPressed(false);
		client.options.rightKey.setPressed(false);
		client.options.jumpKey.setPressed(false);
		client.options.sneakKey.setPressed(false);
		client.options.sprintKey.setPressed(false);
		client.options.useKey.setPressed(false);
	}

	private static boolean solid(ClientWorld world, BlockPos p) {
		BlockState s = world.getBlockState(p);
		return !s.isAir() && s.getFluidState().isEmpty() && !s.isReplaceable();
	}

	private static BlockState fillFor(Farm farm, BlockPos cell) {
		BlockState want = farm.plan.blocks().get(cell.subtract(farm.bed));
		return want != null ? want : scaffoldState();
	}

	static BlockState scaffoldState() {
		return Settings.scaffold instanceof BlockItem b ? b.getBlock().getDefaultState() : Blocks.COBBLESTONE.getDefaultState();
	}

	/** What's in the bag, for the planner: a block it has none of can't be planned in. */
	private static java.util.function.Predicate<BlockState> have(PlayerInventory inv) {
		Set<Item> items = new HashSet<>();
		boolean bed = false;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			items.add(s.getItem());
			bed |= s.isIn(ItemTags.BEDS);
		}
		boolean anyBed = bed;
		return st -> st.getBlock() instanceof net.minecraft.block.BedBlock ? anyBed
				: st.isOf(Blocks.WATER) ? items.contains(Items.WATER_BUCKET) || items.contains(Items.BUCKET) : items.contains(st.getBlock().asItem());
	}

	private static void outOf(MinecraftClient client, BlockState want) {
		stop(client, "Out of " + Printer.itemName(want) + ". Bring more and start it again: it carries on from where it is.");
	}

	private static String name(Item i) {
		return i == Items.RED_BED ? "beds" : new ItemStack(i).getName().getString();
	}

	private static String describe(Walk.Job j) {
		String where = j.target() == null ? "" : " " + j.target().subtract(TotemBuilder.farm.bed).toShortString();
		return switch (j.act()) {
			case PLACE -> "building" + (j.want() == null ? "" : " " + Printer.itemName(j.want()));
			case POUR -> "pouring the lake";
			case BREAK -> j.why().equals("scaffolding") || j.why().equals("dig") || j.why().equals("last") ? "taking scaffolding away" : "breaking a block that doesn't belong";
			case SUPPORT -> "a block to build on";
			case FALL -> "stepping off into the sea";
			case BOOT -> "climbing out of the water";
			default -> j.act().name().toLowerCase();
		} + (j.act() == Walk.Act.PLACE ? "" : where);
	}

	private static void say(MinecraftClient client, String text, Formatting f) {
		TotemBuilder.say(client, Text.literal(text).formatted(f));
	}
}
