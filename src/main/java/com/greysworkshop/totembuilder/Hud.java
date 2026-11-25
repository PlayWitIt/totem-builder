package com.greysworkshop.totembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Two short lines at the top left while you're near the farm: how far along it is, and where to fly next. */
final class Hud {
	private Hud() {}

	private static List<Text> cached = List.of();
	private static int age;

	static void render(DrawContext context, RenderTickCounter counter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud()) return;
		if (++age >= 10) {
			age = 0;
			cached = lines(client);
		}
		int y = 4;
		for (Text line : cached) {
			int w = client.textRenderer.getWidth(line);
			context.fill(2, y - 2, 6 + w, y + 9, 0x90000000);
			context.drawTextWithShadow(client.textRenderer, line, 4, y, 0xFFFFFFFF);
			y += 11;
		}
	}

	static List<Text> lines(MinecraftClient client) {
		List<Text> lines = new ArrayList<>();
		Farm farm = TotemBuilder.farm;
		if (farm == null || client.world == null) return lines;
		Vec3d me = client.player.getEntityPos();
		if (me.squaredDistanceTo(Vec3d.ofCenter(farm.bed)) > 200 * 200) return lines;
		int total = 0, left = 0, wrong = 0;
		BlockPos nearest = null;
		double nd = Double.MAX_VALUE;
		boolean water = Printer.waterTime(client.world, farm, Printer.targets(farm, false));
		for (Map.Entry<BlockPos, BlockState> e : Printer.targets(farm, true).entrySet()) {
			total++;
			Printer.Need need = Printer.need(e.getValue(), client.world.getBlockState(e.getKey()));
			if (need == Printer.Need.NOTHING) continue;
			if (need == Printer.Need.WRONG) wrong++;
			left++;
			boolean entrance = false;
			for (BlockPos p : FarmPlan.ENTRANCE) entrance |= farm.bed.add(p).equals(e.getKey());
			if (entrance || e.getValue().isOf(net.minecraft.block.Blocks.WATER) && (!water || !e.getKey().equals(Printer.nextWater(client.world, farm)))) continue;
			double d = Vec3d.ofCenter(e.getKey()).squaredDistanceTo(me);
			// Somewhere it can go now (something next to it to place against) beats somewhere floating.
			boolean support = Printer.placeableNow(client.world, e.getKey(), e.getValue());
			if (!support) d += 10000;
			if (d < nd) {
				nd = d;
				nearest = e.getKey();
			}
		}
		// Raiders that have been up top a long while: one left there holds up the whole raid.
		String stuck = Stuck.line(client, farm);
		Text head = Text.literal("Totem farm ").formatted(Formatting.GOLD)
				.append(Text.literal((total - left) + "/" + total).formatted(left == 0 ? Formatting.GREEN : Formatting.WHITE))
				.append(Text.literal(TotemBuilder.printing ? "  printer on" : "  printer off (Mod Menu)").formatted(TotemBuilder.printing ? Formatting.GREEN : Formatting.GRAY));
		lines.add(head);
		boolean onlyEntrance = left > 0 && left <= FarmPlan.ENTRANCE.length && nearest == null;
		if (left == 0 && farm.sealed || onlyEntrance && farm.sealed) {
			// Finished and running: nothing on screen unless a raider is stuck.
			lines.clear();
			if (stuck != null) lines.add(Text.literal(stuck).formatted(Formatting.RED));
			return lines;
		}
		if (stuck != null) lines.add(Text.literal(stuck).formatted(Formatting.RED));
		if (left == 0 || onlyEntrance) {
			lines.add(Text.literal("Built! Villager goes in the green box at " + farm.bed.add(FarmPlan.BED_FOOT).toShortString() + " (/totem villager)").formatted(Formatting.GREEN));
			return lines;
		}
		if (!TotemBuilder.printer.lastProblem.isEmpty() && TotemBuilder.printing) {
			lines.add(Text.literal(TotemBuilder.printer.lastProblem).formatted(Formatting.RED));
		} else if (wrong > 0) {
			lines.add(Text.literal(wrong + " wrong block" + (wrong == 1 ? "" : "s") + " in the way (red): break them").formatted(Formatting.RED));
		} else if (nearest != null && client.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(nearest))) {
			lines.add(Text.literal("Move a little: you're where the next block goes").formatted(Formatting.YELLOW));
		} else if (nearest != null) {
			lines.add(Text.literal("Next: " + direction(client, nearest)).formatted(Formatting.AQUA));
		} else if (!farm.sealed) {
			BlockPos v = farm.bed.add(FarmPlan.BED_FOOT);
			lines.add(Text.literal("Built! Villager goes in the green box at " + v.toShortString()).formatted(Formatting.GREEN));
		}
		return lines;
	}

	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

	/** "↗ 23 m, 40 up": where a block is from where you're looking. */
	static String direction(MinecraftClient client, BlockPos target) {
		Vec3d d = Vec3d.ofCenter(target).subtract(client.player.getEyePos());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		double yawTo = Math.toDegrees(Math.atan2(-d.x, d.z));
		double rel = MathHelper.wrapDegrees(yawTo - client.player.getYaw());
		int i = Math.floorMod((int) Math.round(rel / 45.0), 8);
		int up = (int) Math.round(d.y);
		String vert = Math.abs(up) < 2 ? "" : ", " + Math.abs(up) + (up > 0 ? " up" : " down");
		return (flat < 3 ? "" : ARROWS[i] + " " + (int) flat + " m") + vert + (flat < 3 && vert.isEmpty() ? "here" : "");
	}
}
