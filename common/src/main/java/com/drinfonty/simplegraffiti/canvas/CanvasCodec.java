package com.drinfonty.simplegraffiti.canvas;

/**
 * Run-length encoding of a canvas (SPEC 7.7): runs of {@code (count: unsigned byte 1..255, texel: 4
 * bytes ARGB)}.
 *
 * <p>Worst case is 1 280 bytes for a canvas of 256 distinct colours; a single-colour tag is a
 * handful of bytes, which is what makes a full-face correction cheap enough to send whenever
 * prediction cannot be trusted.
 *
 * <p>Decoding is deliberately total: malformed input returns {@code null} rather than throwing, so
 * a hostile or truncated payload costs one log line and leaves the canvas unchanged instead of
 * unwinding a network handler. Every length is bounded before anything is allocated.
 */
public final class CanvasCodec {
	/** Maximum encoded size: 256 runs of one texel each. */
	public static final int MAX_ENCODED_BYTES = Canvas.TEXELS * 5;

	private CanvasCodec() {
	}

	public static byte[] encode(int[] texels) {
		if (texels.length != Canvas.TEXELS) {
			throw new IllegalArgumentException("canvas must be " + Canvas.TEXELS + " texels");
		}

		byte[] out = new byte[MAX_ENCODED_BYTES];
		int length = 0;
		int index = 0;

		while (index < Canvas.TEXELS) {
			int value = PaintColor.normalise(texels[index]);
			int run = 1;

			// Runs cap at 255 because the count is one unsigned byte; a fully blank canvas is
			// therefore two runs, not one, which is still six bytes.
			while (run < 255
				&& index + run < Canvas.TEXELS
				&& PaintColor.normalise(texels[index + run]) == value) {
				run++;
			}

			out[length++] = (byte) run;
			out[length++] = (byte) (value >>> 24);
			out[length++] = (byte) (value >>> 16);
			out[length++] = (byte) (value >>> 8);
			out[length++] = (byte) value;

			index += run;
		}

		byte[] trimmed = new byte[length];
		System.arraycopy(out, 0, trimmed, 0, length);
		return trimmed;
	}

	/**
	 * @return the decoded 256 texels, or {@code null} when the input is malformed - wrong length,
	 *         a zero run count, or a decoded length other than exactly 256 texels
	 */
	public static int[] decode(byte[] encoded) {
		if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES || encoded.length % 5 != 0) {
			return null;
		}

		int[] texels = new int[Canvas.TEXELS];
		int written = 0;

		for (int offset = 0; offset < encoded.length; offset += 5) {
			int count = encoded[offset] & 0xFF;

			if (count == 0 || written + count > Canvas.TEXELS) {
				return null;
			}

			int value = ((encoded[offset + 1] & 0xFF) << 24)
				| ((encoded[offset + 2] & 0xFF) << 16)
				| ((encoded[offset + 3] & 0xFF) << 8)
				| (encoded[offset + 4] & 0xFF);
			value = PaintColor.normalise(value);

			for (int i = 0; i < count; i++) {
				texels[written++] = value;
			}
		}

		if (written != Canvas.TEXELS) {
			return null;
		}

		return texels;
	}

	/** The raw 1 024-byte form used on disk (SPEC 8), where region-file compression does the work. */
	public static byte[] toBytes(int[] texels) {
		if (texels.length != Canvas.TEXELS) {
			throw new IllegalArgumentException("canvas must be " + Canvas.TEXELS + " texels");
		}

		byte[] out = new byte[Canvas.BYTES];

		for (int i = 0; i < Canvas.TEXELS; i++) {
			int value = PaintColor.normalise(texels[i]);
			out[i * 4] = (byte) (value >>> 24);
			out[i * 4 + 1] = (byte) (value >>> 16);
			out[i * 4 + 2] = (byte) (value >>> 8);
			out[i * 4 + 3] = (byte) value;
		}

		return out;
	}

	/**
	 * @return the decoded texels, or {@code null} when {@code bytes} is not exactly 1 024 long -
	 *         such an entry is dropped individually, keeping the rest of the chunk (SPEC 8)
	 */
	public static int[] fromBytes(byte[] bytes) {
		if (bytes == null || bytes.length != Canvas.BYTES) {
			return null;
		}

		int[] texels = new int[Canvas.TEXELS];

		for (int i = 0; i < Canvas.TEXELS; i++) {
			int value = ((bytes[i * 4] & 0xFF) << 24)
				| ((bytes[i * 4 + 1] & 0xFF) << 16)
				| ((bytes[i * 4 + 2] & 0xFF) << 8)
				| (bytes[i * 4 + 3] & 0xFF);
			texels[i] = PaintColor.normalise(value);
		}

		return texels;
	}
}
