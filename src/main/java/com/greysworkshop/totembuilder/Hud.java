package com.greysworkshop.totembuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A few short lines at the top left while you're near the farm: how far along it is, the next block (with its item,
 * so you know what to get out) and which way it is, blocks that have to come out, and what you're short of. Nothing
 * at all once the farm is finished and running, unless a raider is stuck.
 */
final class Hud {
	private Hud() {}

	record Line(Text text, ItemStack icon) {}

	private static List<Line> cached = List.of();
	private static int age;

	static void render(DrawContext context, RenderTickCounter counter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud()) return;
		if (++age >= 10) {
			age = 0;
			cached = lines(client);
		}
		int y = 4;
		for (Line line : cached) {
			int ix = line.icon == null ? 0 : 11;
			int w = client.textRenderer.getWidth(line.text) + ix;
			context.fill(2, y - 2, 6 + w, y + 9, 0x90000000);
			if (line.icon != null) {
				context.getMatrices().pushMatrix();
				context.getMatrices().translate(4, y - 1);
				context.getMatrices().scale(0.625f, 0.625f);
				context.drawItem(line.icon, 0, 0);
				context.getMatrices().popMatrix();
			}
			context.drawTextWithShadow(client.textRenderer, line.text, 4 + ix, y, 0xFFFFFFFF);
			y += 11;
		}
	}

	private static Line line(Text t) {
		return new Line(t, null);
	}

	static List<Line> lines(MinecraftClient client) {
		List<Line> lines = new ArrayList<>();
		Farm farm = TotemBuilder.farm;
		if (Walker.active() && !Walker.status.isEmpty())
			lines.add(line(Text.literal("Auto: " + Walker.status + "  (/totem stop)").formatted(Formatting.AQUA)));
		else if (AutoBuild.active() && !AutoBuild.status.isEmpty())
			lines.add(line(Text.literal("Auto: " + AutoBuild.status + "  (/totem stop)").formatted(Formatting.AQUA)));
		Survey s = Survey.latest;
		if (farm == null || client.world == null || s == null) return lines;
		Vec3d me = client.player.getEntityPos();
		if (me.squaredDistanceTo(Vec3d.ofCenter(farm.bed)) > 200 * 200) return lines;
		String stuck = Stuck.line(client, farm);
		int left = s.left();
		boolean doorOnly = left > 0 && s.missing.stream().allMatch(c -> isEntrance(farm, c.pos()));
		boolean clean = s.extras.isEmpty();
		if ((left == 0 || doorOnly) && farm.sealed && clean) {
			// Finished and running: nothing on screen unless a raider is stuck.
			lines.clear();
			if (stuck != null) lines.add(line(Text.literal(stuck).formatted(Formatting.RED)));
			return lines;
		}
		MutableText head = Text.literal("Totem farm ").formatted(Formatting.GOLD)
				.append(Text.literal(s.done + "/" + s.total).formatted(left == 0 ? Formatting.GREEN : Formatting.WHITE))
				.append(Text.literal(left == 0 ? "" : "  " + left + " left").formatted(Formatting.GRAY));
		if (!Walker.active() && !AutoBuild.active())
			head.append(Text.literal(TotemBuilder.printing ? "  printer on" : "  printer off").formatted(TotemBuilder.printing ? Formatting.GREEN : Formatting.DARK_GRAY));
		lines.add(line(head));
		if (stuck != null) lines.add(line(Text.literal(stuck).formatted(Formatting.RED)));

		int wrong = 0, stray = 0, scaffold = 0;
		for (Survey.Extra e : s.extras) {
			if (e.wrong()) wrong++;
			else if (e.scaffold()) scaffold++;
			else stray++;
		}
		if (wrong > 0) lines.add(line(Text.literal(wrong + " wrong block" + (wrong == 1 ? "" : "s") + " (red): break and replace").formatted(Formatting.RED)));
		if (stray > 0) lines.add(line(Text.literal(stray + " block" + (stray == 1 ? "" : "s") + " that don't belong (purple): take them out").formatted(Formatting.LIGHT_PURPLE)));
		if (scaffold > 0 && !Walker.active()) lines.add(line(Text.literal(scaffold + " scaffolding left (amber): auto takes it away").formatted(Formatting.GOLD)));

		if (left == 0 || doorOnly) {
			if (!farm.sealed)
				lines.add(line(Text.literal("Built! Villager goes in the green box at " + farm.bed.add(FarmPlan.BED_FOOT).toShortString()).formatted(Formatting.GREEN)));
			return lines;
		}
		Survey.Cell next = s.next(client.player.getEyePos());
		if (TotemBuilder.printing && !TotemBuilder.printer.lastProblem.isEmpty() && !Walker.active()) {
			lines.add(line(Text.literal(TotemBuilder.printer.lastProblem).formatted(Formatting.RED)));
		} else if (next != null) {
			Item item = Survey.item(next.want());
			ItemStack icon = new ItemStack(item);
			int carry = Survey.count(client.player.getInventory(), item);
			String name = item == net.minecraft.item.Items.RED_BED ? "Bed" : icon.getName().getString();
			MutableText t = Text.literal("Next: ").formatted(Formatting.AQUA).append(Text.literal(name).formatted(Formatting.WHITE));
			if (client.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(next.pos())))
				t.append(Text.literal("  move a little, you're in its spot").formatted(Formatting.YELLOW));
			else t.append(Text.literal("  " + direction(client, next.pos())).formatted(Formatting.AQUA));
			if (carry == 0) t.append(Text.literal("  (none on you)").formatted(Formatting.RED));
			lines.add(new Line(t, icon));
		}
		// What to bring: only what you're short of.
		MutableText shortOf = null;
		int shown = 0;
		for (Map.Entry<Item, Integer> e : s.need.entrySet()) {
			int have = s.have.getOrDefault(e.getKey(), 0);
			if (have >= e.getValue()) continue;
			if (shown++ == 3) {
				shortOf.append(Text.literal(", ...").formatted(Formatting.RED));
				break;
			}
			String name = e.getKey() == net.minecraft.item.Items.RED_BED ? "Bed" : new ItemStack(e.getKey()).getName().getString();
			if (shortOf == null) shortOf = Text.literal("Short: ").formatted(Formatting.RED);
			else shortOf.append(Text.literal(", ").formatted(Formatting.RED));
			shortOf.append(Text.literal((e.getValue() - have) + " " + name).formatted(Formatting.WHITE));
		}
		if (shortOf != null) lines.add(line(shortOf));
		return lines;
	}

	static boolean isEntrance(Farm farm, BlockPos p) {
		for (BlockPos e : FarmPlan.ENTRANCE) if (farm.bed.add(e).equals(p)) return true;
		return false;
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
