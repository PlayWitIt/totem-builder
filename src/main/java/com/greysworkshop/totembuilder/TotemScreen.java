package com.greysworkshop.totembuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Map;

/**
 * Everything in one screen (Mod Menu → Totem Builder → Config): plan the farm where you are, printer on and off,
 * check the spot for land, what's left to place, where the villager goes, and forget the farm. Buttons that act in
 * the world close the screen first.
 */
public final class TotemScreen extends Screen {
	private final Screen parent;

	public TotemScreen(Screen parent) {
		super(Text.literal("Totem Builder"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		MinecraftClient mc = MinecraftClient.getInstance();
		boolean inWorld = mc.player != null && mc.world != null;
		boolean planned = TotemBuilder.farm != null;
		int w = 200, x = width / 2 - w / 2, y = height / 2 - 70;
		add(x, y, w, planned ? "Plan the farm here (moves it)" : "Plan the farm here", inWorld, TotemBuilder::planHere);
		add(x, y + 24, w, TotemBuilder.printing ? "Printer: ON (click to stop)" : "Printer: OFF (click to start)", inWorld && planned, TotemBuilder::togglePrinting);
		add(x, y + 48, w, "Check the spot for land", inWorld && planned, TotemBuilder::runCheck);
		add(x, y + 72, w / 2 - 2, "Materials left", inWorld && planned, Commands::materials);
		add(x + w / 2 + 2, y + 72, w / 2 - 2, "Villager spot", inWorld && planned, Commands::villager);
		addDrawableChild(ButtonWidget.builder(Text.literal("Forget this farm"), b -> {
			TotemBuilder.farm = null;
			TotemBuilder.printing = false;
			Farm.save(mc, null);
			clearAndInit();
		}).dimensions(x, y + 96, w, 20).build()).active = inWorld && planned;
		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x, y + 128, w, 20).build());
	}

	private void add(int x, int y, int w, String label, boolean active, java.util.function.Consumer<MinecraftClient> job) {
		ButtonWidget b = ButtonWidget.builder(Text.literal(label), btn -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.setScreen(null);
			job.accept(mc);
		}).dimensions(x, y, w, 20).build();
		b.active = active;
		addDrawableChild(b);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 100, 0xFFFFD040);
		MinecraftClient mc = MinecraftClient.getInstance();
		String status;
		if (mc.world == null) status = "Join a world first.";
		else if (TotemBuilder.farm == null) status = "Fly out over open ocean and plan the farm.";
		else {
			Map<String, Integer> m = TotemBuilder.missing(mc);
			int left = m.values().stream().mapToInt(Integer::intValue).sum();
			status = left == 0 ? "Built." + (TotemBuilder.farm.sealed ? " Villager sealed in: ready." : " Now the villager.") : left + " blocks still to place.";
		}
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, height / 2 - 86, 0xFFFFFFFF);
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}
}
