package com.greysworkshop.totembuilder;

/**
 * Where the server should think you're looking, for the next block. It goes out inside the game's own movement
 * packet for the tick (see {@code mixin.SendLook}), so there's never more than the one movement packet a tick that the
 * game sends anyway, and your view on screen doesn't move. Sending extra look packets, as the printer once did, reads to
 * a server's anti-cheat as a sped-up game clock and gets you kicked.
 */
public final class Look {
	private static boolean active;
	private static float yaw, pitch;

	private Look() {}

	static void set(float y, float p) {
		active = true;
		yaw = y;
		pitch = p;
	}

	static void clear() {
		active = false;
	}

	public static boolean active() {
		return active;
	}

	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}
}
