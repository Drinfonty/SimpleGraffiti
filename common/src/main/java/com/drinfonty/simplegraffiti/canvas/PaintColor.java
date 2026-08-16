package com.drinfonty.simplegraffiti.canvas;

/**
 * ARGB texel helpers (SPEC 2).
 *
 * <p>A texel is 4 bytes, {@code A R G B}. Alpha 0 means no paint and the RGB bytes must be zero;
 * alpha 255 means painted. Any other alpha is treated as 255 on read - the format reserves alpha
 * for a future opacity feature, but v1.0 paint is either present or absent, which is what keeps
 * rendering in the cutout layer.
 *
 * <p>Deliberately free of Minecraft types so it can be unit-tested in a plain JVM. Sampling a
 * block's colour, which needs {@code DyeColor} and {@code MapColor}, lives in
 * {@code com.drinfonty.simplegraffiti.item.ColorSampler} instead.
 */
public final class PaintColor {
	public static final int EMPTY = 0;
	public static final int WHITE = 0xFFFFFFFF;

	/** The colour a freshly crafted, undyed can sprays (SPEC 3). */
	public static final int DEFAULT_RGB = 0xFFFFFF;

	private PaintColor() {
	}

	public static boolean isPainted(int texel) {
		return (texel >>> 24) != 0;
	}

	/** Packs a 24-bit RGB value into an opaque texel. */
	public static int opaque(int rgb) {
		return 0xFF000000 | (rgb & 0xFFFFFF);
	}

	public static int rgb(int texel) {
		return texel & 0xFFFFFF;
	}

	/**
	 * Normalises a texel as read from disk or the network: alpha is forced to 0 or 255, and an
	 * unpainted texel's colour bytes are dropped so that two "empty" texels always compare equal.
	 */
	public static int normalise(int texel) {
		return isPainted(texel) ? opaque(texel) : EMPTY;
	}

	public static int red(int texel) {
		return (texel >>> 16) & 0xFF;
	}

	public static int green(int texel) {
		return (texel >>> 8) & 0xFF;
	}

	public static int blue(int texel) {
		return texel & 0xFF;
	}

	/**
	 * Parses {@code #RRGGBB} or {@code RRGGBB}.
	 *
	 * @return the 24-bit RGB value, or -1 when the input is not a valid hex colour. The picker
	 *         screen disables its confirm button on -1 rather than throwing (SPEC 5.3).
	 */
	public static int parseHex(String text) {
		if (text == null) {
			return -1;
		}

		String body = text.trim();

		if (body.startsWith("#")) {
			body = body.substring(1);
		}

		if (body.length() != 6) {
			return -1;
		}

		int value = 0;

		for (int i = 0; i < 6; i++) {
			int digit = Character.digit(body.charAt(i), 16);

			if (digit < 0) {
				return -1;
			}

			value = (value << 4) | digit;
		}

		return value;
	}

	public static String toHex(int rgb) {
		return String.format("#%06X", rgb & 0xFFFFFF);
	}
}
