package com.drinfonty.simplegraffiti.canvas;

import java.util.Arrays;
import java.util.UUID;

/**
 * The paint on one face of one block: a 16x16 grid of ARGB texels, plus who last painted it and
 * when (SPEC 4.1).
 *
 * <p>A canvas is <strong>replaced, not mutated in place</strong> once published. A paint operation
 * copies, stamps, and swaps the reference in the owning map, which is what makes it safe for the
 * client's chunk-mesher threads to read a canvas with no locking: a worker sees either the old
 * canvas or the new one, never a half-stamped one. {@link #texels()} therefore hands out the live
 * array only to callers that promise not to write to it; {@link #withStamp} is the supported way to
 * change one.
 */
public final class Canvas {
	public static final int SIZE = 16;
	public static final int TEXELS = SIZE * SIZE;

	/** Bytes on disk and pre-RLE on the wire: 256 texels of ARGB. */
	public static final int BYTES = TEXELS * 4;

	private final int[] texels;
	private final UUID owner;
	private final long timestamp;

	private Canvas(int[] texels, UUID owner, long timestamp) {
		this.texels = texels;
		this.owner = owner;
		this.timestamp = timestamp;
	}

	public static Canvas empty() {
		return new Canvas(new int[TEXELS], null, 0L);
	}

	/** Wraps an array this canvas takes ownership of; the caller must not retain it. */
	public static Canvas ofOwned(int[] texels, UUID owner, long timestamp) {
		if (texels.length != TEXELS) {
			throw new IllegalArgumentException("canvas must be " + TEXELS + " texels");
		}

		return new Canvas(texels, owner, timestamp);
	}

	public static Canvas copyOf(int[] texels, UUID owner, long timestamp) {
		return ofOwned(texels.clone(), owner, timestamp);
	}

	/**
	 * The live texel array. Read-only by contract - see the class comment. Handing out the array
	 * rather than copying is what keeps meshing allocation-free on the render workers.
	 */
	public int[] texels() {
		return texels;
	}

	public int texel(int pu, int pv) {
		return texels[pv * SIZE + pu];
	}

	public UUID owner() {
		return owner;
	}

	public long timestamp() {
		return timestamp;
	}

	public boolean isEmpty() {
		for (int texel : texels) {
			if (PaintColor.isPainted(texel)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Applies one stamp to a <em>copy</em> of this canvas.
	 *
	 * @return the new canvas, or {@code null} if the stamp changed nothing - the caller must then
	 *         neither consume a charge nor broadcast (SPEC 4.3)
	 */
	public Canvas withStamp(int u8, int v8, int size, int value, UUID painter, long now) {
		int[] copy = texels.clone();

		if (!Brush.stamp(copy, u8, v8, size, value)) {
			return null;
		}

		return new Canvas(copy, painter, now);
	}

	/** Clears every texel. Returns null when the canvas was already blank. */
	public Canvas cleared(UUID painter, long now) {
		if (isEmpty()) {
			return null;
		}

		return new Canvas(new int[TEXELS], painter, now);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}

		// Deliberately compares paint only, not owner or timestamp: two canvases with the same
		// pixels render and serialise identically, and the tests care about the pixels.
		return o instanceof Canvas other && Arrays.equals(texels, other.texels);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(texels);
	}
}
