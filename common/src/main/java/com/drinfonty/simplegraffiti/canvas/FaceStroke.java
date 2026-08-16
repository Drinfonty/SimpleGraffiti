package com.drinfonty.simplegraffiti.canvas;

/**
 * A stroke drawn across a whole plane of block faces, rather than within one canvas.
 *
 * <p>This exists because a canvas is only 16 texels wide. A player dragging at any normal speed
 * crosses several blocks per second, so a stroke that could only join two points on the *same*
 * canvas restarted on every block boundary and produced exactly one lone disc per block - the
 * "disconnected blobs" report. Only a very slow drag kept consecutive samples inside one block,
 * which is why slow dragging appeared to work.
 *
 * <p>The trick is to map both endpoints into a single coordinate system covering the whole face
 * plane, walk the segment there, and hand each block the piece of the segment that falls inside it.
 * Coordinates are in 1/16-texel units, so one block spans exactly 256 of them, and everything stays
 * integer - the walk has to be bit-identical on the client that predicts it and the server that
 * applies it.
 *
 * <p>Only points on the <em>same plane</em> can be joined: the same face direction, and the same
 * coordinate along that face's normal. Dragging from a floor onto a higher step is two strokes, not
 * a line through the air.
 */
public final class FaceStroke {
	/** One block face spans 16 texels of 16 sub-units each. */
	public static final int UNITS_PER_BLOCK = 256;

	/**
	 * The longest stroke that will be joined, in 1/16-texel units - eight blocks.
	 *
	 * <p>Comfortably past what a player can sweep between two samples inside their reach, and a hard
	 * bound on the work a single paint request can ask for, since the walk is one step per unit.
	 */
	public static final int MAX_STROKE_UNITS = 8 * UNITS_PER_BLOCK;

	/**
	 * Receives every point along the stroke, in order, already resolved to a block and a point
	 * within that block's canvas.
	 *
	 * <p>Deliberately one callback per point rather than one per block with a clipped sub-segment.
	 * Clipping meant the caller re-interpolated between the clipped endpoints, which rounds a second
	 * time and made a fast drag land on marginally different centres than a slow one over the same
	 * path. Emitting the exact points keeps a single rounding step and makes the two identical.
	 *
	 * <p>Points arrive grouped by block, so a caller can hold one canvas and only look up another
	 * when the block changes.
	 */
	public interface Point {
		/**
		 * @param blockU the block's coordinate along the face's u axis
		 * @param blockV the block's coordinate along the face's v axis
		 * @param u8     the point within that block, 0..255
		 * @param v8     the point within that block, 0..255
		 */
		void accept(int blockU, int blockV, int u8, int v8);
	}

	private FaceStroke() {
	}

	/** True when the u axis of this face runs opposite to its world axis (SPEC 4.2). */
	private static boolean uInverted(int face) {
		return face == FaceAxes.NORTH || face == FaceAxes.EAST;
	}

	/** True when the v axis of this face runs opposite to its world axis (SPEC 4.2). */
	private static boolean vInverted(int face) {
		// Every side face has v running down the world Y axis; DOWN has v running back along Z.
		return face != FaceAxes.UP;
	}

	/**
	 * The world coordinate that this face's u axis follows.
	 *
	 * @return 0 for X, 1 for Y, 2 for Z
	 */
	public static int uWorldAxis(int face) {
		return switch (face) {
			case FaceAxes.UP, FaceAxes.DOWN, FaceAxes.NORTH, FaceAxes.SOUTH -> 0;
			case FaceAxes.WEST, FaceAxes.EAST -> 2;
			default -> throw new IllegalArgumentException("bad face: " + face);
		};
	}

	/** The world coordinate that this face's v axis follows. */
	public static int vWorldAxis(int face) {
		return switch (face) {
			case FaceAxes.UP, FaceAxes.DOWN -> 2;
			case FaceAxes.NORTH, FaceAxes.SOUTH, FaceAxes.WEST, FaceAxes.EAST -> 1;
			default -> throw new IllegalArgumentException("bad face: " + face);
		};
	}

	/** The world axis this face's normal points along, which stays constant across the plane. */
	public static int normalWorldAxis(int face) {
		return switch (face) {
			case FaceAxes.UP, FaceAxes.DOWN -> 1;
			case FaceAxes.NORTH, FaceAxes.SOUTH -> 2;
			case FaceAxes.WEST, FaceAxes.EAST -> 0;
			default -> throw new IllegalArgumentException("bad face: " + face);
		};
	}

	private static int encode(int blockCoord, int local8, boolean inverted) {
		// An inverted axis still has to be contiguous across block boundaries, so the block's own
		// span is reflected as well as its position - otherwise a stroke would jump 256 units
		// backwards every time it crossed a boundary.
		return inverted
			? -blockCoord * UNITS_PER_BLOCK - UNITS_PER_BLOCK + local8
			: blockCoord * UNITS_PER_BLOCK + local8;
	}

	private static int decodeBlock(int global, boolean inverted) {
		return inverted
			? Math.floorDiv(-global - 1, UNITS_PER_BLOCK)
			: Math.floorDiv(global, UNITS_PER_BLOCK);
	}

	private static int decodeLocal(int global) {
		return Math.floorMod(global, UNITS_PER_BLOCK);
	}

	public static int encodeU(int face, int blockCoord, int u8) {
		return encode(blockCoord, u8, uInverted(face));
	}

	public static int encodeV(int face, int blockCoord, int v8) {
		return encode(blockCoord, v8, vInverted(face));
	}

	private static int axis(int which, int x, int y, int z) {
		return switch (which) {
			case 0 -> x;
			case 1 -> y;
			default -> z;
		};
	}

	/** The block coordinate along this face's u axis. */
	public static int blockU(int face, int x, int y, int z) {
		return axis(uWorldAxis(face), x, y, z);
	}

	/** The block coordinate along this face's v axis. */
	public static int blockV(int face, int x, int y, int z) {
		return axis(vWorldAxis(face), x, y, z);
	}

	/**
	 * The block coordinate along this face's normal - constant across the plane, and therefore the
	 * test for whether two points can be joined at all.
	 */
	public static int normal(int face, int x, int y, int z) {
		return axis(normalWorldAxis(face), x, y, z);
	}

	/** Rebuilds a world X from face-plane block coordinates. */
	public static int worldX(int face, int blockU, int blockV, int normal) {
		return rebuild(face, 0, blockU, blockV, normal);
	}

	public static int worldY(int face, int blockU, int blockV, int normal) {
		return rebuild(face, 1, blockU, blockV, normal);
	}

	public static int worldZ(int face, int blockU, int blockV, int normal) {
		return rebuild(face, 2, blockU, blockV, normal);
	}

	private static int rebuild(int face, int which, int blockU, int blockV, int normal) {
		if (which == uWorldAxis(face)) {
			return blockU;
		}

		if (which == vWorldAxis(face)) {
			return blockV;
		}

		return normal;
	}

	/**
	 * Whether two points are far enough apart to be worth refusing rather than joining.
	 *
	 * <p>Checked before any work: the walk costs one step per unit, so an unbounded segment from a
	 * hostile client would otherwise be an unbounded amount of server work.
	 */
	public static boolean tooFar(int fromU, int fromV, int toU, int toV) {
		return Math.abs(toU - fromU) > MAX_STROKE_UNITS || Math.abs(toV - fromV) > MAX_STROKE_UNITS;
	}

	/**
	 * Walks the segment one quantisation unit at a time, reporting every point in order.
	 *
	 * <p>Coordinates are the face-plane globals produced by {@link #encodeU}/{@link #encodeV}. One
	 * unit is the finest step the wire format can express, which is what makes a fast drag paint
	 * byte-identically to dragging the same path infinitely slowly.
	 */
	public static void walk(int face, int fromU, int fromV, int toU, int toV, Point out) {
		if (!FaceAxes.isValidFace(face)) {
			throw new IllegalArgumentException("bad face: " + face);
		}

		if (tooFar(fromU, fromV, toU, toV)) {
			throw new IllegalArgumentException("stroke longer than the bound");
		}

		int du = toU - fromU;
		int dv = toV - fromV;
		int steps = Math.max(Math.abs(du), Math.abs(dv));

		boolean uInv = uInverted(face);
		boolean vInv = vInverted(face);

		for (int i = 0; i <= steps; i++) {
			int u = steps == 0 ? fromU : fromU + (du * i) / steps;
			int v = steps == 0 ? fromV : fromV + (dv * i) / steps;

			out.accept(decodeBlock(u, uInv), decodeBlock(v, vInv), decodeLocal(u), decodeLocal(v));
		}
	}
}
