package com.drinfonty.simplegraffiti.canvas;

/**
 * The single definition of face orientation (SPEC 4.2), used identically by the client, the server
 * and the renderer.
 *
 * <p>Faces are identified by their vanilla 3D data value so that the value travelling on the wire,
 * the value stored on disk and the value used here are all the same number, with no translation
 * table to get out of step. Deliberately expressed over that {@code int} rather than over
 * {@code Direction}, so the maths is unit-testable with no game bootstrap - and so that the
 * eventual "paint on slabs" feature changes exactly one file.
 *
 * <p>{@code u} increases to the right and {@code v} increases downwards as seen by a player looking
 * at the face; for UP and DOWN, {@code v} increases southwards.
 */
public final class FaceAxes {
	public static final int DOWN = 0;
	public static final int UP = 1;
	public static final int NORTH = 2;
	public static final int SOUTH = 3;
	public static final int WEST = 4;
	public static final int EAST = 5;

	public static final int FACE_COUNT = 6;

	private FaceAxes() {
	}

	public static boolean isValidFace(int face) {
		return face >= 0 && face < FACE_COUNT;
	}

	/**
	 * Face-local {@code u} in [0,1] for a hit at block-local {@code (lx, ly, lz)}.
	 */
	public static double u(int face, double lx, double ly, double lz) {
		return switch (face) {
			case NORTH -> 1.0 - lx;
			case SOUTH -> lx;
			case WEST -> lz;
			case EAST -> 1.0 - lz;
			case UP, DOWN -> lx;
			default -> throw new IllegalArgumentException("bad face: " + face);
		};
	}

	/**
	 * Face-local {@code v} in [0,1] for a hit at block-local {@code (lx, ly, lz)}.
	 */
	public static double v(int face, double lx, double ly, double lz) {
		return switch (face) {
			case NORTH, SOUTH, WEST, EAST -> 1.0 - ly;
			case UP -> lz;
			case DOWN -> 1.0 - lz;
			default -> throw new IllegalArgumentException("bad face: " + face);
		};
	}

	/**
	 * Quantises a face coordinate to 1/16 of a texel, the unit that travels on the wire.
	 *
	 * <p>This is the only place a float becomes an integer in the paint path. Everything downstream
	 * of it - the brush, the canvas, the protocol - is integer, so the client and the server cannot
	 * round differently (SPEC 4.3).
	 */
	public static int quantise(double coordinate) {
		int value = (int) Math.floor(coordinate * 256.0);

		if (value < 0) {
			return 0;
		}

		return Math.min(value, 255);
	}

	/** The texel index 0..15 containing a face coordinate in [0,1]. */
	public static int texel(double coordinate) {
		int value = (int) Math.floor(coordinate * Canvas.SIZE);

		if (value < 0) {
			return 0;
		}

		return Math.min(value, Canvas.SIZE - 1);
	}

	/** The opposite face's 3D data value, for the "is this face occluded" test (SPEC 5.1). */
	public static int opposite(int face) {
		return switch (face) {
			case DOWN -> UP;
			case UP -> DOWN;
			case NORTH -> SOUTH;
			case SOUTH -> NORTH;
			case WEST -> EAST;
			case EAST -> WEST;
			default -> throw new IllegalArgumentException("bad face: " + face);
		};
	}
}
