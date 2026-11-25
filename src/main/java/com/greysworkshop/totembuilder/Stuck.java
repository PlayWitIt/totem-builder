package com.greysworkshop.totembuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Watches the top of the farm for raiders that don't come down. In testing about one raid in ten leaves one up there
 * (a ravager with a rider wedged on the pad's edge, say), and the raid waits for it: after 40 seconds this says so,
 * and where, so you can fly up and finish it.
 */
final class Stuck {
	private Stuck() {}

	private static final Map<UUID, Long> firstSeen = new HashMap<>();
	private static long lastClean;

	static String line(MinecraftClient client, Farm farm) {
		long now = client.world.getTime();
		BlockPos top = farm.bed.add(0, FarmPlan.PAD_Y, 0);
		Box area = new Box(top).expand(9, 4, 9).offset(0, 1, 0);
		RaiderEntity worst = null;
		long worstAge = 0;
		for (RaiderEntity r : client.world.getEntitiesByClass(RaiderEntity.class, area, e -> e.isAlive())) {
			long t = firstSeen.computeIfAbsent(r.getUuid(), u -> now);
			if (now - t > worstAge) {
				worstAge = now - t;
				worst = r;
			}
		}
		if (now - lastClean > 200) {
			lastClean = now;
			firstSeen.values().removeIf(t -> now - t > 20 * 600);
		}
		if (worst == null || worstAge < 20 * 40) return null;
		return worst.getType().getName().getString() + " stuck up top at " + worst.getBlockPos().toShortString() + " for " + worstAge / 20
				+ " s: the raid waits for it. Fly up and finish it.";
	}
}
