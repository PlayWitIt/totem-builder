package com.greysworkshop.totembuilder;

import net.minecraft.client.MinecraftClient;

final class Walker {
	static String status = "";
	static boolean active() { return false; }
	static void start(MinecraftClient c) {}
	static void stop(MinecraftClient c, String why) {}
}
