package com.greysworkshop.totembuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything in one screen (Mod Menu → Totem Builder → Config, or /totem). On the left the buttons: the two auto
 * modes, plan here, printer, ghost blocks, the scaffolding block, the land check, the villager's spot. On the right
 * what's left, as pictures: a bar for each part of the farm, and every block still needed with how many you carry
 * (red where you're short). Sized for a small window (854 × 480 at GUI scale 2 is 427 × 240).
 */
public final class TotemScreen extends Screen {
	private final Screen parent;
	private Survey survey;
	private int frames;
	private int left, top, panelX, panelW;

	public TotemScreen(Screen parent) {
		super(Text.literal("Totem Builder"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		MinecraftClient mc = MinecraftClient.getInstance();
		survey = Survey.of(mc);
		boolean inWorld = mc.player != null && mc.world != null;
		boolean planned = TotemBuilder.farm != null;
		int total = Math.min(width - 12, 440);
		left = (width - total) / 2;
		top = Math.max(26, height / 2 - 100);
		int bw = Math.min(172, total / 2 - 6);
		panelX = left + bw + 10;
		panelW = left + total - panelX;
		int x = left, y = top, row = 22;
		boolean busy = Walker.active() || AutoBuild.active();
		if (busy) {
			add(x, y, bw, "Stop auto-build", inWorld, c -> {
				Walker.stop(c, "Stopped.");
				AutoBuild.stop(c, null);
				TotemBuilder.printing = false;
			});
			y += row;
		} else {
			add(x, y, bw, "Auto: walk + scaffolding", inWorld, Walker::start, "Builds it all on foot, no flying needed: it pillars and bridges on "
					+ Settings.scaffoldName() + " where it must, and takes all of it away at the end.");
			y += row;
			add(x, y, bw, "Auto: fly (elytra fly)", inWorld, AutoBuild::start, "Finds a clean spot, flies there with your flight hack and builds it, flying from block to block.");
			y += row;
		}
		add(x, y, bw, planned ? "Plan the farm here (moves it)" : "Plan the farm here", inWorld && !busy, TotemBuilder::planHere,
				"The villager's bed goes right under you; the floor sits on the sea.");
		y += row;
		add(x, y, bw, TotemBuilder.printing ? "Printer: ON" : "Printer: OFF", inWorld && planned && !busy, TotemBuilder::togglePrinting,
				"Places every missing block within reach as you move round the farm.");
		y += row;
		addDrawableChild(ButtonWidget.builder(Text.literal(Settings.ghosts.label), b -> {
			Settings.ghosts = Settings.ghosts.next();
			Settings.save();
			b.setMessage(Text.literal(Settings.ghosts.label));
		}).dimensions(x, y, bw, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
				"See-through copies of the blocks still to place. Bright ones can go in now, faint ones need something next to them first."))).build());
		y += row;
		int held = 44;
		addDrawableChild(ButtonWidget.builder(Text.literal("Scaffold: " + Settings.scaffoldName()), b -> {
			Settings.nextScaffold();
			clearAndInit();
		}).dimensions(x, y, bw - held - 2, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
				"The block the walking auto-builder stands on and climbs with. Click to change."))).build());
		ButtonWidget useHeld = ButtonWidget.builder(Text.literal("Held"), b -> {
			if (mc.player != null && Settings.useHeld(mc.player.getMainHandStack())) clearAndInit();
		}).dimensions(x + bw - held, y, held, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
				"Use the block in your hand as scaffolding (any plain full block)."))).build();
		useHeld.active = mc.player != null && Settings.usable(mc.player.getMainHandStack().getItem());
		addDrawableChild(useHeld);
		y += row;
		int half = bw / 2 - 1;
		add(x, y, half, "Check for land", inWorld && planned, TotemBuilder::runCheck, "Looks for anything within 107 blocks raiders could spawn on instead.");
		add(x + bw - half, y, half, "Villager spot", inWorld && planned, Commands::villager, "Where the villager goes, in chat.");
		y += row;
		ButtonWidget forget = ButtonWidget.builder(Text.literal("Forget farm"), b -> {
			TotemBuilder.farm = null;
			TotemBuilder.printing = false;
			Farm.save(mc, null);
			clearAndInit();
		}).dimensions(x, y, half, 20).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Forgets where the farm is. Nothing in the world changes."))).build();
		forget.active = inWorld && planned && !busy;
		addDrawableChild(forget);
		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x + bw - half, y, half, 20).build());
	}

	private void add(int x, int y, int w, String label, boolean active, java.util.function.Consumer<MinecraftClient> job) {
		add(x, y, w, label, active, job, null);
	}

	private void add(int x, int y, int w, String label, boolean active, java.util.function.Consumer<MinecraftClient> job, String tip) {
		ButtonWidget.Builder b = ButtonWidget.builder(Text.literal(label), btn -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.setScreen(null);
			job.accept(mc);
		}).dimensions(x, y, w, 20);
		if (tip != null) b.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(tip)));
		ButtonWidget built = b.build();
		built.active = active;
		addDrawableChild(built);
	}

	@Override
	public void tick() {
		if (++frames % 10 == 0) survey = Survey.of(MinecraftClient.getInstance());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, Math.max(4, top - 22), 0xFFFFD040);
		MinecraftClient mc = MinecraftClient.getInstance();
		String status;
		if (mc.world == null) status = "Join a world first.";
		else if (TotemBuilder.farm == null) status = "No farm yet: get out over open ocean and plan it, or start an auto mode.";
		else if (survey == null) status = "You're far from the farm.";
		else if (survey.left() == 0) status = "Built." + (TotemBuilder.farm.sealed ? " Villager sealed in: ready." : " Now the villager (green box).");
		else status = survey.left() + " of " + survey.total + " blocks still to place.";
		if (Walker.active()) status = "Auto: " + Walker.status;
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, Math.max(14, top - 12), 0xFFFFFFFF);
		if (survey != null && TotemBuilder.farm != null) panel(context, mouseX, mouseY);
	}

	/** What's left, drawn: a bar per part, then every block still needed as its item with the count. */
	private void panel(DrawContext g, int mouseX, int mouseY) {
		Survey s = survey;
		int x = panelX, y = top, w = panelW;
		g.fill(x - 4, y - 3, x + w, y + 200, 0x80000000);
		g.drawTextWithShadow(textRenderer, Text.literal("What's left"), x, y, 0xFFFFD040);
		y += 11;
		int barX = x + Math.min(112, w - 70), barW = Math.max(30, w - (barX - x) - 34);
		for (Map.Entry<Survey.Part, int[]> e : s.parts.entrySet()) {
			int done = e.getValue()[0], all = e.getValue()[1];
			if (all == 0) continue;
			boolean full = done >= all;
			String label = e.getKey().label;
			while (textRenderer.getWidth(label) > barX - x - 4 && label.length() > 4) label = label.substring(0, label.length() - 2) + ".";
			g.drawTextWithShadow(textRenderer, Text.literal(label), x, y, full ? 0xFF60E060 : 0xFFE0E0E0);
			g.fill(barX, y + 1, barX + barW, y + 7, 0xFF303030);
			g.fill(barX, y + 1, barX + (int) Math.round(barW * (double) done / all), y + 7, full ? 0xFF40C040 : 0xFF3090E0);
			String n = full ? "done" : (all - done) + " left";
			g.drawTextWithShadow(textRenderer, Text.literal(n), barX + barW + 4, y, full ? 0xFF60E060 : 0xFFB0B0B0);
			y += 10;
		}
		y += 3;
		if (s.need.isEmpty()) {
			g.drawTextWithShadow(textRenderer, Text.literal("No more blocks needed."), x, y, 0xFF60E060);
			y += 12;
		} else {
			g.drawTextWithShadow(textRenderer, Text.literal("Blocks still needed  (you have)"), x, y, 0xFFFFD040);
			y += 11;
			int cellW = 72, cols = Math.max(1, w / cellW), i = 0;
			List<Runnable> tips = new ArrayList<>();
			for (Map.Entry<Item, Integer> e : s.need.entrySet()) {
				int cx = x + (i % cols) * cellW, cy = y + (i / cols) * 20;
				ItemStack stack = new ItemStack(e.getKey());
				int have = s.have.getOrDefault(e.getKey(), 0), want = e.getValue();
				g.drawItem(stack, cx, cy);
				boolean ok = have >= want;
				g.drawTextWithShadow(textRenderer, Text.literal("×" + want), cx + 18, cy + 1, 0xFFFFFFFF);
				g.drawTextWithShadow(textRenderer, Text.literal("(" + have + ")"), cx + 18, cy + 10, ok ? 0xFF60E060 : 0xFFFF5050);
				if (mouseX >= cx && mouseX < cx + cellW && mouseY >= cy && mouseY < cy + 20) {
					String name = e.getKey() == Items.RED_BED ? "Bed (any colour)" : stack.getName().getString();
					String line = name + ": " + want + " to place, you have " + have + (ok ? "" : ", bring " + (want - have) + " more");
					tips.add(() -> g.drawTooltip(textRenderer, Text.literal(line), mouseX, mouseY));
				}
				i++;
			}
			y += ((i + cols - 1) / cols) * 20 + 2;
			for (Runnable t : tips) t.run();
		}
		int wrong = 0, stray = 0, scaffold = 0;
		for (Survey.Extra e : s.extras) {
			if (e.wrong()) wrong++;
			else if (e.scaffold()) scaffold++;
			else stray++;
		}
		if (wrong > 0) {
			g.drawTextWithShadow(textRenderer, Text.literal(wrong + " wrong block" + (wrong == 1 ? "" : "s") + " to break (red)"), x, y, 0xFFFF5050);
			y += 10;
		}
		if (stray > 0) {
			g.drawTextWithShadow(textRenderer, Text.literal(stray + " stray block" + (stray == 1 ? "" : "s") + " to take out (purple)"), x, y, 0xFFE070FF);
			y += 10;
		}
		if (scaffold > 0) {
			g.drawTextWithShadow(textRenderer, Text.literal(scaffold + " scaffolding left (amber)"), x, y, 0xFFFFB020);
			y += 10;
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			int sc = Survey.count(mc.player.getInventory(), Settings.scaffold);
			g.drawItem(new ItemStack(Settings.scaffold), x, y);
			g.drawTextWithShadow(textRenderer, Text.literal("Scaffolding: you have " + sc), x + 18, y + 4, 0xFFC0C0C0);
		}
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}
}
