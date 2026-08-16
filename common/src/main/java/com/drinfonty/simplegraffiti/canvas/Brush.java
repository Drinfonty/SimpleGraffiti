package com.drinfonty.simplegraffiti.canvas;

/**
 * The single implementation of what one spray does.
 *
 * <p>This class is called by the <em>server</em> to apply the authoritative result and by the
 * <em>client</em> to predict it and to replay broadcast stamps, so it must produce bit-identical
 * output on both sides. That is why there is no floating-point arithmetic anywhere in it: the hit
 * point arrives already quantised to 1/16 of a texel as two unsigned bytes and coverage is an
 * integer comparison of squared distances. See SPEC 4.3, which this file implements literally.
 *
 * <p>The brush is a <strong>solid</strong> disc. An earlier version dithered its edge with a Bayer
 * threshold for a soft spray-paint falloff, which read in game as speckly rather than soft - and at
 * the smallest size it made strokes visibly dotty, because a dithered texel is only reached when a
 * stamp centre passes almost exactly over it.
 *
 * <p>Because the same operation replays identically everywhere, a spray costs 16 bytes on the wire
 * instead of a canvas diff, and prediction cannot drift from authority.
 */
public final class Brush {
	/** Brush size ids, as they travel on the wire. */
	public static final int SIZE_SMALL = 0;
	public static final int SIZE_MEDIUM = 1;
	public static final int SIZE_LARGE = 2;

	public static final int MIN_SIZE = SIZE_SMALL;
	public static final int MAX_SIZE = SIZE_LARGE;

	/** Radii in 1/16-texel units, indexed by size id (SPEC 4.3). */
	private static final int[] RADII = { 24, 40, 64 };

	private Brush() {
	}

	public static boolean isValidSize(int size) {
		return size >= MIN_SIZE && size <= MAX_SIZE;
	}

	public static int radius(int size) {
		if (!isValidSize(size)) {
			throw new IllegalArgumentException("brush size out of range: " + size);
		}

		return RADII[size];
	}

	/**
	 * Stamps one spray into {@code texels}, returning whether anything actually changed.
	 *
	 * <p>The return value is load-bearing: a stamp that changes no texel must not consume a charge
	 * and must not be broadcast (SPEC 4.3), which is what stops a player spraying an already-solid
	 * wall from generating traffic.
	 *
	 * @param texels the 256-entry ARGB grid, mutated in place - callers publishing a canvas to other
	 *               threads must stamp a copy, never the published array
	 * @param u8     hit point along u, in 1/16-texel units, 0..255
	 * @param v8     hit point along v, in 1/16-texel units, 0..255
	 * @param size   brush size id, 0..2
	 * @param value  0xFF_RR_GG_BB to paint, or 0 to erase
	 */
	public static boolean stamp(int[] texels, int u8, int v8, int size, int value) {
		if (texels.length != Canvas.TEXELS) {
			throw new IllegalArgumentException("canvas must be " + Canvas.TEXELS + " texels");
		}

		if (u8 < 0 || u8 > 255 || v8 < 0 || v8 > 255) {
			throw new IllegalArgumentException("hit point out of range: " + u8 + "," + v8);
		}

		int r = radius(size);
		int rr = r * r;

		// Texels outside 0..15 are clipped rather than wrapped, so paint never spills onto an
		// adjacent face or block (SPEC 4.3).
		int minV = clampTexel(Math.floorDiv(v8 - r, 16));
		int maxV = clampTexel(Math.floorDiv(v8 + r, 16));
		int minU = clampTexel(Math.floorDiv(u8 - r, 16));
		int maxU = clampTexel(Math.floorDiv(u8 + r, 16));

		boolean changed = false;

		for (int pv = minV; pv <= maxV; pv++) {
			for (int pu = minU; pu <= maxU; pu++) {
				int dx = (pu * 16 + 8) - u8;
				int dy = (pv * 16 + 8) - v8;
				int d2 = dx * dx + dy * dy;

				// A texel is painted when its centre falls inside the brush radius. Integer
				// throughout: no sqrt, no float, no platform-dependent rounding.
				if (d2 < rr) {
					int index = pv * 16 + pu;

					if (texels[index] != value) {
						texels[index] = value;
						changed = true;
					}
				}
			}
		}

		return changed;
	}

	/**
	 * Stamps a continuous stroke from {@code (u0, v0)} to {@code (u1, v1)}.
	 *
	 * <p>This is what makes a drag draw a line instead of a row of blobs. Sampling the crosshair on
	 * a timer and stamping one disc per sample can only ever produce dots spaced by however fast the
	 * player moved; the gap between two samples has to be filled in deliberately, exactly as a paint
	 * program interpolates between two mouse events.
	 *
	 * <p>The walk advances one quantisation unit - 1/16 of a texel - at a time, which is the finest
	 * step the wire format can express. That is deliberately the *finest* possible rather than
	 * something cheaper: it makes a fast drag produce byte-identical paint to dragging the same path
	 * infinitely slowly, so how a stroke looks never depends on the player's mouse speed or on how
	 * often the game sampled the crosshair.
	 *
	 * <p>The whole walk is integer, like {@link #stamp}, so the client's prediction and the server's
	 * authority stay bit-identical. It is bounded by construction: the longest possible span is 255
	 * units, so the loop runs at most 256 times however fast the player flicks the mouse.
	 */
	public static boolean stampLine(int[] texels, int u0, int v0, int u1, int v1, int size, int value) {
		if (u0 < 0 || u0 > 255 || v0 < 0 || v0 > 255) {
			throw new IllegalArgumentException("stroke start out of range: " + u0 + "," + v0);
		}

		// Validates the end point, the canvas length and the brush size, and paints the far end.
		boolean changed = stamp(texels, u1, v1, size, value);

		int du = u1 - u0;
		int dv = v1 - v0;
		int steps = Math.max(Math.abs(du), Math.abs(dv));

		// steps == 0 means both points are the same, and the stamp above already covered it.
		for (int i = 0; i < steps; i++) {
			// Integer division against a fixed step count - deterministic on every platform.
			int u = u0 + (du * i) / steps;
			int v = v0 + (dv * i) / steps;
			changed |= stamp(texels, u, v, size, value);
		}

		return changed;
	}

	private static int clampTexel(int value) {
		if (value < 0) {
			return 0;
		}

		return Math.min(value, 15);
	}
}
