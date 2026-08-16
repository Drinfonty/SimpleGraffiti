package com.drinfonty.simplegraffiti.canvas;

/**
 * Packs a canvas address into a single {@code long}, so canvases live in a {@code Long2ObjectMap}
 * with no allocation per lookup.
 *
 * <p><strong>Chunk-local, not world-absolute.</strong> DESIGN describes packing a whole
 * {@code BlockPos} plus a face as "26 bits X, 12 bits Y, 26 bits Z, 3 bits face", which is 67 bits
 * and does not fit: vanilla's own {@code BlockPos} packing already uses all 64. Since storage,
 * sync and lookup are all chunk-granular anyway - a canvas is only ever addressed relative to the
 * {@code ChunkCanvases} that owns it - the key is chunk-local instead, which fits comfortably and
 * costs nothing:
 *
 * <pre>
 *   bits 0..2    face (3D data value, 0..5)
 *   bits 3..6    local Z (0..15)
 *   bits 7..10   local X (0..15)
 *   bits 11..42  world Y (full signed int, so no build-height assumption is baked in)
 * </pre>
 */
public final class CanvasKey {
	private static final int FACE_BITS = 3;
	private static final int LOCAL_BITS = 4;

	private static final int Z_SHIFT = FACE_BITS;
	private static final int X_SHIFT = Z_SHIFT + LOCAL_BITS;
	private static final int Y_SHIFT = X_SHIFT + LOCAL_BITS;

	private static final long FACE_MASK = (1L << FACE_BITS) - 1L;
	private static final long LOCAL_MASK = (1L << LOCAL_BITS) - 1L;

	private CanvasKey() {
	}

	/**
	 * @param localX 0..15, the block's X within its chunk
	 * @param localZ 0..15, the block's Z within its chunk
	 * @param y      absolute world Y
	 * @param face   3D data value, 0..5
	 */
	public static long pack(int localX, int y, int localZ, int face) {
		if ((localX & ~0xF) != 0 || (localZ & ~0xF) != 0) {
			throw new IllegalArgumentException("chunk-local coordinates out of range: " + localX + "," + localZ);
		}

		if (!FaceAxes.isValidFace(face)) {
			throw new IllegalArgumentException("bad face: " + face);
		}

		return ((long) y << Y_SHIFT)
			| ((long) localX << X_SHIFT)
			| ((long) localZ << Z_SHIFT)
			| face;
	}

	public static int localX(long key) {
		return (int) ((key >> X_SHIFT) & LOCAL_MASK);
	}

	public static int localZ(long key) {
		return (int) ((key >> Z_SHIFT) & LOCAL_MASK);
	}

	public static int y(long key) {
		// Arithmetic shift, so negative Y (which every overworld cave has) round-trips.
		return (int) (key >> Y_SHIFT);
	}

	public static int face(long key) {
		return (int) (key & FACE_MASK);
	}

	/** The same block's key for a different face, without unpacking the rest. */
	public static long withFace(long key, int face) {
		if (!FaceAxes.isValidFace(face)) {
			throw new IllegalArgumentException("bad face: " + face);
		}

		return (key & ~FACE_MASK) | face;
	}
}
