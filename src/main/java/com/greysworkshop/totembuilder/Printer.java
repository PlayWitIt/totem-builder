package com.greysworkshop.totembuilder;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Places the farm while you fly around it: every tick, the missing blocks within reach that have something next to
 * them to place against, nearest first, a few per tick. It takes the right item from anywhere in your inventory,
 * faces the way a block needs (the bed, the trapdoors), and never clicks a block that would open or toggle. Blocks
 * placed but not yet confirmed by the server are left alone for a second so it never double-places.
 */
public final class Printer {
	public enum Need { NOTHING, PLACE, WRONG }

	private final Map<BlockPos, Long> pending = new HashMap<>();
	private final Map<BlockPos, Long> opened = new HashMap<>();
	public String lastProblem = "";
	public int placedThisSession;
	static int refills;
	private long ticks;

	/** Trapdoors and fence gates: placed shut, then opened with a click. */
	static boolean opens(BlockState s) {
		return s.getBlock() instanceof TrapdoorBlock || s.getBlock() instanceof FenceGateBlock;
	}

	static boolean isOpen(BlockState s) {
		return s.getBlock() instanceof TrapdoorBlock ? s.get(TrapdoorBlock.OPEN) : s.get(FenceGateBlock.OPEN);
	}

	/** Placed, but still shut: it needs a click to open, not another block. */
	static boolean needsOpening(BlockState want, BlockState have) {
		return opens(want) && have.isOf(want.getBlock()) && isOpen(want) && !isOpen(have);
	}

	/** What the cell needs: nothing, a block placed, or a wrong block (that you have to break). */
	public static Need need(BlockState want, BlockState have) {
		if (matches(want, have)) return Need.NOTHING;
		if (have.isAir() || have.isReplaceable() || have.isOf(Blocks.WATER) || have.isOf(Blocks.BUBBLE_COLUMN)) return Need.PLACE;
		if (needsOpening(want, have)) return Need.PLACE;
		return Need.WRONG;
	}

	public static boolean matches(BlockState want, BlockState have) {
		if (!have.isOf(want.getBlock())) {
			// Any colour of bed will do.
			return want.getBlock() instanceof BedBlock && have.getBlock() instanceof BedBlock
					&& have.get(BedBlock.PART) == want.get(BedBlock.PART) && have.get(BedBlock.FACING) == want.get(BedBlock.FACING);
		}
		if (want.isOf(Blocks.WATER)) return have.getFluidState().isStill();
		if (want.getBlock() instanceof SlabBlock) return have.get(SlabBlock.TYPE) == want.get(SlabBlock.TYPE);
		if (opens(want)) return isOpen(have) == isOpen(want);
		if (want.getBlock() instanceof BedBlock) return have.get(BedBlock.PART) == want.get(BedBlock.PART) && have.get(BedBlock.FACING) == want.get(BedBlock.FACING);
		return true;
	}

	/** The item that places this block. */
	public static boolean isItemFor(BlockState want, ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (want.getBlock() instanceof BedBlock) return stack.isIn(ItemTags.BEDS);
		if (want.isOf(Blocks.WATER)) return stack.isOf(Items.WATER_BUCKET);
		return stack.isOf(want.getBlock().asItem());
	}

	/** Every target cell: the plan plus the entrance once the villager is in. */
	public static Map<BlockPos, BlockState> targets(Farm farm, boolean sealed) {
		Map<BlockPos, BlockState> out = new LinkedHashMap<>();
		for (Map.Entry<BlockPos, BlockState> e : farm.plan.blocks().entrySet()) out.put(farm.bed.add(e.getKey()), e.getValue());
		if (sealed) for (BlockPos e : FarmPlan.ENTRANCE) out.put(farm.bed.add(e), Blocks.COBBLESTONE.getDefaultState());
		return out;
	}

	/** Is a villager standing in the room? */
	public static boolean villagerInside(ClientWorld world, Farm farm) {
		Box room = new Box(farm.bed.add(FarmPlan.BED_FOOT)).union(new Box(farm.bed.add(FarmPlan.BED_HEAD).up(2)));
		return !world.getEntitiesByClass(VillagerEntity.class, room.expand(0.2), e -> e.isAlive()).isEmpty();
	}

	public void tick(MinecraftClient client, Farm farm, int perTick) {
		ticks++;
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		if (player == null || world == null || client.interactionManager == null || client.currentScreen != null) return;
		pending.values().removeIf(t -> ticks - t > 20);
		opened.values().removeIf(t -> ticks - t > 20);
		double reach = player.getBlockInteractionRange() - 0.25;
		Vec3d eye = player.getEyePos();
		boolean sealed = farm.sealed || villagerInside(world, farm);
		List<BlockPos> candidates = new ArrayList<>();
		Map<BlockPos, BlockState> targets = targets(farm, sealed);
		// Water goes in last, once every block is in, so it never pours off an unfinished edge.
		boolean dry = !waterTime(world, farm, targets);
		// Water still to pour and no full bucket: fill the empty ones at any water in reach that isn't the farm's.
		if (!dry && ticks % 4 == 0 && player.getInventory().count(Items.WATER_BUCKET) < waterStillToPour(world, farm)
				&& refill(client, player, world, farm, eye, reach)) return;
		for (Map.Entry<BlockPos, BlockState> e : targets.entrySet()) {
			BlockPos pos = e.getKey();
			if (pending.containsKey(pos)) continue;
			if (e.getValue().isOf(Blocks.WATER) && dry) continue;
			if (Vec3d.ofCenter(pos).squaredDistanceTo(eye) > (reach + 0.8) * (reach + 0.8)) continue;
			if (need(e.getValue(), world.getBlockState(pos)) != Need.PLACE) continue;
			// Not where you're flying: the block wouldn't go in.
			if (player.getBoundingBox().intersects(new Box(pos)) && !needsOpening(e.getValue(), world.getBlockState(pos)) && !e.getValue().isOf(Blocks.WATER)) continue;
			candidates.add(pos);
		}
		if (candidates.isEmpty()) return;
		// The bed before the room closes round it, then nearest first.
		candidates.sort((a, b) -> {
			boolean ba = targets.get(a).getBlock() instanceof BedBlock, bb = targets.get(b).getBlock() instanceof BedBlock;
			if (ba != bb) return ba ? -1 : 1;
			return Double.compare(Vec3d.ofCenter(a).squaredDistanceTo(eye), Vec3d.ofCenter(b).squaredDistanceTo(eye));
		});
		int done = 0;
		String problem = "";
		for (BlockPos pos : candidates) {
			if (done >= perTick) break;
			BlockState want = targets.get(pos);
			BlockState have = world.getBlockState(pos);
			// Shut trapdoor or gate: open it.
			if (needsOpening(want, have)) {
				if (opened.containsKey(pos)) continue;
				Vec3d hit = Vec3d.ofCenter(pos);
				if (hit.distanceTo(eye) > reach) continue;
				face(player, hit);
				client.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos, false));
				opened.put(pos, ticks);
				done++;
				continue;
			}
			if (want.isOf(Blocks.WATER) && !pos.equals(nextWater(world, farm))) continue;
			int slot = find(player.getInventory(), want);
			if (slot < 0 && want.isOf(Blocks.WATER) && refill(client, player, world, farm, eye, reach)) {
				done++;
				continue;
			}
			if (slot < 0) {
				BlockPos spot = want.isOf(Blocks.WATER) ? refillSpot(world, farm) : null;
				problem = spot != null && player.getInventory().contains(new ItemStack(Items.BUCKET))
						? "out of water buckets: fill your empty ones at the water at " + spot.toShortString()
						: "out of " + itemName(want);
				continue;
			}
			if (want.isOf(Blocks.WATER)) {
				// A bucket goes where you look: look down at the floor under the spot, from where nothing's in the way.
				BlockPos floor = pos.down();
				Vec3d at = new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
				if (at.distanceTo(eye) > reach) continue;
				BlockHitResult ray = world.raycast(new RaycastContext(eye, at.add(at.subtract(eye).normalize().multiply(0.2)), RaycastContext.ShapeType.OUTLINE,
						RaycastContext.FluidHandling.NONE, player));
				if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(floor) || ray.getSide() != Direction.UP) {
					problem = "fly above the water spot to fill it";
					continue;
				}
				select(client, player, slot);
				Vec3d d = at.subtract(eye);
				float yaw = (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90;
				float pitch = (float) -(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI);
				float oy = player.getYaw(), op = player.getPitch();
				player.setYaw(yaw);
				player.setPitch(pitch);
				client.interactionManager.interactItem(player, Hand.MAIN_HAND);
				player.setYaw(oy);
				player.setPitch(op);
				pending.put(pos, ticks);
				placedThisSession++;
				done++;
				continue;
			}
			BlockHitResult hit = placement(world, pos, want, eye, reach);
			if (hit == null && !anyBuilt(world, targets) && !(want.getBlock() instanceof BedBlock) && !(want.getBlock() instanceof TrapdoorBlock)
					&& !(want.getBlock() instanceof SlabBlock) && Vec3d.ofCenter(pos).distanceTo(eye) <= reach) {
				// Nothing of the farm stands yet and there's nothing to build on over open water: the very first block
				// goes in against thin air (the server allows it), and everything after is placed against a neighbour.
				hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
				lastProblem = "";
				TotemBuilder.LOGGER.info("First block of the farm placed in the air at {}", pos);
			}
			if (hit == null) continue;
			select(client, player, slot);
			Direction look = want.getBlock() instanceof BedBlock ? want.get(BedBlock.FACING) : null;
			float yaw = player.getYaw(), pitch = player.getPitch();
			if (look != null) {
				// The bed points the way you face: face east for a moment.
				player.setYaw(look.getPositiveHorizontalDegrees());
				player.setPitch(60);
				player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(player.getYaw(), player.getPitch(), player.isOnGround(), player.horizontalCollision));
			} else {
				face(player, hit.getPos());
			}
			// Against a gate or trapdoor, sneak so the click places instead of opening it.
			boolean sneak = opens(world.getBlockState(hit.getBlockPos()));
			if (sneak) sneak(player, true);
			client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
			if (sneak) sneak(player, false);
			player.swingHand(Hand.MAIN_HAND);
			if (look != null) {
				player.setYaw(yaw);
				player.setPitch(pitch);
			}
			pending.put(pos, ticks);
			if (want.getBlock() instanceof BedBlock) pending.put(pos.offset(want.get(BedBlock.FACING)), ticks);
			placedThisSession++;
			done++;
		}
		if (done == 0 && !problem.isEmpty()) lastProblem = problem;
		else if (done > 0) lastProblem = "";
	}

	/**
	 * The one water spot to pour next, so two buckets always finish a row: the far end of a row whose first end is in,
	 * else the first end of the first unfinished row, else a middle that didn't fill itself. Null when the lake's done.
	 */
	static BlockPos nextWater(ClientWorld world, Farm farm) {
		java.util.List<BlockPos> water = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> e : farm.plan.blocks().entrySet())
			if (e.getValue().isOf(Blocks.WATER) && !isSource(world, farm.bed.add(e.getKey()))) water.add(farm.bed.add(e.getKey()));
		water.sort(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
		return water.isEmpty() ? null : water.get(0);
	}

	static int waterStillToPour(ClientWorld world, Farm farm) {
		int n = 0;
		for (Map.Entry<BlockPos, BlockState> e : farm.plan.blocks().entrySet())
			if (e.getValue().isOf(Blocks.WATER) && !isSource(world, farm.bed.add(e.getKey()))) n++;
		return n;
	}

	static boolean isSource(ClientWorld world, BlockPos p) {
		return world.getFluidState(p).isStill() && world.getFluidState(p).isIn(net.minecraft.registry.tag.FluidTags.WATER);
	}

	/**
	 * The middle source of a row of three fills itself once both ends are in: pour the ends first and leave it, so
	 * four buckets do the whole lake.
	 */
	static boolean middleWaiting(ClientWorld world, Map<BlockPos, BlockState> targets, BlockPos pos) {
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos a = pos.offset(d), b = pos.offset(d.getOpposite());
			BlockState ta = targets.get(a), tb = targets.get(b);
			if (ta != null && tb != null && ta.isOf(Blocks.WATER) && tb.isOf(Blocks.WATER))
				return !(world.getFluidState(a).isStill() && world.getFluidState(b).isStill());
		}
		return false;
	}

	/** The nearest water source that isn't part of the farm (the sea, a puddle) to fill buckets from; null if none near. */
	static BlockPos refillSpot(ClientWorld world, Farm farm) {
		Vec3d me = MinecraftClient.getInstance().player.getEyePos();
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		BlockPos c = BlockPos.ofFloored(me);
		for (BlockPos p : BlockPos.iterate(c.add(-16, -16, -16), c.add(16, 16, 16))) {
			if (!isSource(world, p) || !world.getBlockState(p.up()).isAir()) continue;
			BlockState planned = farm.plan.blocks().get(p.subtract(farm.bed));
			if (planned != null && planned.isOf(Blocks.WATER)) continue;
			if (farm.plan.inFootprint(p.getX() - farm.bed.getX(), p.getZ() - farm.bed.getZ()) && p.getY() > farm.bed.getY()) continue;
			double d = Vec3d.ofCenter(p).squaredDistanceTo(me);
			if (d < bd) {
				bd = d;
				best = p.toImmutable();
			}
		}
		return best;
	}

	/**
	 * Out of water buckets: fill an empty one from a source that refills itself (two sources beside it and something
	 * under it), so a couple of buckets are enough however it goes.
	 */
	private boolean refill(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Farm farm, Vec3d eye, double reach) {
		int empty = -1;
		PlayerInventory inv = player.getInventory();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isOf(Items.BUCKET)) { empty = i; break; }
		if (empty < 0) return false;
		// Each pour leaves an empty bucket: fill them all while here, not one at a time.
		BlockPos me = BlockPos.ofFloored(eye);
		int r = (int) Math.ceil(reach);
		for (BlockPos p : BlockPos.iterateOutwards(me, r, r, r)) {
			if (!world.getFluidState(p).isStill() || !world.getFluidState(p).isIn(net.minecraft.registry.tag.FluidTags.WATER)) continue;
			if (!world.getBlockState(p.up()).isAir()) continue;
			BlockState planned = farm.plan.blocks().get(p.subtract(farm.bed));
			if (planned != null && planned.isOf(Blocks.WATER)) continue;
			Vec3d at = Vec3d.ofCenter(p).add(0, 0.4, 0);
			if (at.distanceTo(eye) > reach) continue;
			BlockHitResult ray = world.raycast(new RaycastContext(eye, at.add(at.subtract(eye).normalize().multiply(0.3)), RaycastContext.ShapeType.OUTLINE,
					RaycastContext.FluidHandling.SOURCE_ONLY, player));
			if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(p)) continue;
			select(client, player, empty);
			Vec3d d = at.subtract(eye);
			float oy = player.getYaw(), op = player.getPitch();
			player.setYaw((float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90);
			player.setPitch((float) -(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI));
			client.interactionManager.interactItem(player, Hand.MAIN_HAND);
			player.setYaw(oy);
			player.setPitch(op);
			refills++;
			TotemBuilder.LOGGER.info("Filled a bucket at {}", p);
			return true;
		}
		return false;
	}

	/** Can this block go in right now, from somewhere (something next to it, placed against the right way)? */
	static boolean placeableNow(ClientWorld world, BlockPos pos, BlockState want) {
		if (want.isOf(Blocks.WATER)) return clickable(world, pos.down());
		return placement(world, pos, want, Vec3d.ofCenter(pos), 3) != null;
	}

	/** Is everything but the water (and the villager's entrance) in place, so the water can go in? */
	static boolean waterTime(ClientWorld world, Farm farm, Map<BlockPos, BlockState> targets) {
		for (Map.Entry<BlockPos, BlockState> e : targets.entrySet())
			if (!e.getValue().isOf(Blocks.WATER) && need(e.getValue(), world.getBlockState(e.getKey())) != Need.NOTHING) {
				boolean entrance = false;
				for (BlockPos q : FarmPlan.ENTRANCE) entrance |= farm.bed.add(q).equals(e.getKey());
				if (!entrance) return false;
			}
		return true;
	}

	/** Tells the server you're sneaking (or not) for the next click, as the game does when you hold shift. */
	private static void sneak(ClientPlayerEntity player, boolean on) {
		PlayerInput in = player.input.playerInput;
		player.networkHandler.sendPacket(new PlayerInputC2SPacket(new PlayerInput(in.forward(), in.backward(), in.left(), in.right(), in.jump(), on || in.sneak(), in.sprint())));
	}

	private static boolean anyBuilt(ClientWorld world, Map<BlockPos, BlockState> targets) {
		for (Map.Entry<BlockPos, BlockState> e : targets.entrySet()) if (!world.getBlockState(e.getKey()).isAir() && need(e.getValue(), world.getBlockState(e.getKey())) == Need.NOTHING) return true;
		return false;
	}

	/** Server-side look at the spot, without turning your view. */
	private static void face(ClientPlayerEntity player, Vec3d at) {
		Vec3d d = at.subtract(player.getEyePos());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90;
		float pitch = (float) -(MathHelper.atan2(d.y, flat) * 180 / Math.PI);
		player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, player.isOnGround(), player.horizontalCollision));
	}

	/**
	 * Where to click to put {@code want} at {@code pos}: a face of a neighbour that's solid and does nothing when
	 * clicked, chosen so the block comes out the right way (slabs on the bottom half, the bed from its foot), and in
	 * reach. Null if there's nothing to place against yet.
	 */
	static BlockHitResult placement(ClientWorld world, BlockPos pos, BlockState want, Vec3d eye, double reach) {
		Block block = want.getBlock();
		if (block instanceof BedBlock) {
			// The bed goes in from its foot, against the floor under it; its head cell must be free.
			if (want.get(BedBlock.PART) != BedPart.FOOT) return null;
			BlockPos below = pos.down();
			if (!clickable(world, below)) return null;
			Vec3d hit = new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			return hit.distanceTo(eye) <= reach ? new BlockHitResult(hit, Direction.UP, below, false) : null;
		}
		BlockHitResult best = null;
		double bestD = Double.MAX_VALUE;
		for (Direction side : Direction.values()) {
			BlockPos against = pos.offset(side);
			if (!clickable(world, against)) continue;
			Direction face = side.getOpposite();
			Vec3d hit;
			if (block instanceof SlabBlock) {
				// Bottom slab: the top of the block under it, or a side low down. Top slab: the underside of the block
				// above, or a side high up. Never onto a slab's open half, which would make it double.
				boolean top = want.get(SlabBlock.TYPE) == SlabType.TOP;
				if (face == (top ? Direction.UP : Direction.DOWN)) continue;
				if (face.getAxis().isVertical()) {
					if (world.getBlockState(against).getBlock() instanceof SlabBlock) continue;
					hit = new Vec3d(pos.getX() + 0.5, pos.getY() + (top ? 1 : 0), pos.getZ() + 0.5);
				} else {
					hit = new Vec3d(side.getOffsetX() != 0 ? pos.getX() + (side.getOffsetX() > 0 ? 1 : 0) : pos.getX() + 0.5, pos.getY() + (top ? 0.75 : 0.25),
							side.getOffsetZ() != 0 ? pos.getZ() + (side.getOffsetZ() > 0 ? 1 : 0) : pos.getZ() + 0.5);
				}
			} else if (block instanceof TrapdoorBlock) {
				// Against a side, low down: it comes out on the bottom half, hinged on that side.
				if (side.getAxis().isVertical()) continue;
				hit = new Vec3d(side.getOffsetX() != 0 ? pos.getX() + (side.getOffsetX() > 0 ? 1 : 0) : pos.getX() + 0.5, pos.getY() + 0.25,
						side.getOffsetZ() != 0 ? pos.getZ() + (side.getOffsetZ() > 0 ? 1 : 0) : pos.getZ() + 0.5);
			} else {
				hit = Vec3d.ofCenter(pos).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
			}
			double d = hit.distanceTo(eye);
			if (d > reach || d >= bestD) continue;
			bestD = d;
			best = new BlockHitResult(hit, face, against, false);
		}
		return best;
	}

	/** A block we can place against that does nothing when right-clicked. */
	static boolean clickable(ClientWorld world, BlockPos pos) {
		BlockState s = world.getBlockState(pos);
		if (s.isAir() || s.isReplaceable() || !s.getFluidState().isEmpty() && s.getCollisionShape(world, pos).isEmpty()) return false;
		if (opens(s)) return true;
		if (s.getBlock() instanceof BedBlock || s.isIn(BlockTags.DOORS)
				|| s.isIn(BlockTags.BUTTONS) || s.isIn(BlockTags.SHULKER_BOXES) || s.isOf(Blocks.CHEST) || s.isOf(Blocks.ENDER_CHEST)
				|| s.isOf(Blocks.CRAFTING_TABLE) || s.isOf(Blocks.FURNACE) || s.isOf(Blocks.LEVER) || s.isIn(BlockTags.ANVIL)
				|| s.isIn(BlockTags.FENCE_GATES) || s.isOf(Blocks.HOPPER) || s.isOf(Blocks.BARREL)) return false;
		return !s.getCollisionShape(world, pos).isEmpty() || s.getBlock() instanceof net.minecraft.block.WallSignBlock;
	}

	static int find(PlayerInventory inv, BlockState want) {
		int selected = inv.getSelectedSlot();
		if (isItemFor(want, inv.getStack(selected))) return selected;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (isItemFor(want, inv.getStack(i))) return i;
		return -1;
	}

	static void select(MinecraftClient client, ClientPlayerEntity player, int slot) {
		PlayerInventory inv = player.getInventory();
		if (slot < PlayerInventory.getHotbarSize()) {
			inv.setSelectedSlot(slot);
			return;
		}
		int hotbar = inv.getSwappableHotbarSlot();
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, hotbar, SlotActionType.SWAP, player);
		inv.setSelectedSlot(hotbar);
	}

	public static String itemName(BlockState want) {
		if (want.getBlock() instanceof BedBlock) return "beds";
		if (want.isOf(Blocks.WATER)) return "water buckets";
		Item item = want.getBlock().asItem();
		return item.getName().getString();
	}
}
