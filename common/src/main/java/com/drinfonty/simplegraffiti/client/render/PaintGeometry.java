package com.drinfonty.simplegraffiti.client.render;

import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;

/**
 * Turns a {@link PaintQuad}'s texel bounds into block-local corner positions.
 *
 * <p>This is the other half of the loader-neutral renderer: {@link CanvasMesher} decides *which*
 * rectangles exist, and this decides *where they sit in the block*. Both are plain maths over
 * primitives with no Minecraft types, so the geometry is written once, tested once, and the two
 * loader adapters only translate it into their own vertex format.
 *
 * <p>The corner order is the same for every face: it walks the rectangle so the resulting quad
 * faces outwards along the face normal, with the first vertex at (minU, maxV) - the bottom-left as
 * seen by a player looking at the wall. Getting that order wrong is invisible until the quad is
 * back-face culled and the paint mysteriously vanishes from one side, so it is fixed here rather
 * than repeated per loader.
 */
public final class PaintGeometry {
	/**
	 * How far off the surface paint floats, in blocks.
	 *
	 * <p>Small enough not to be visible as a gap at touching distance, large enough to survive the
	 * depth buffer's precision at render distance. Paint quads are emitted with no cull face
	 * precisely because they no longer lie in the block's plane.
	 */
	public static final float DECAL_OFFSET = 0.005F;

	private static final float TEXEL = 1.0F / Canvas.SIZE;

	private PaintGeometry() {
	}

	/**
	 * Writes four {@code (x, y, z)} block-local corners into {@code out}, which must have room for
	 * 12 floats.
	 *
	 * @return {@code out}, for chaining
	 */
	public static float[] corners(PaintQuad quad, float[] out) {
		if (out.length < 12) {
			throw new IllegalArgumentException("need room for 12 floats");
		}

		float u0 = quad.minU() * TEXEL;
		float u1 = quad.maxU() * TEXEL;
		float v0 = quad.minV() * TEXEL;
		float v1 = quad.maxV() * TEXEL;

		// (u, v) walked anticlockwise as seen from outside the face: bottom-left, bottom-right,
		// top-right, top-left.
		write(out, 0, quad.face(), u0, v1);
		write(out, 3, quad.face(), u1, v1);
		write(out, 6, quad.face(), u1, v0);
		write(out, 9, quad.face(), u0, v0);

		return out;
	}

	/**
	 * The block-local position of a point on a face, given face coordinates in [0,1].
	 *
	 * <p>The inverse of the table in SPEC 4.2, plus the decal offset along the face normal. Being
	 * the literal inverse matters: if this and {@link FaceAxes} disagree by so much as a flip, paint
	 * lands mirrored, and the bug looks like "the crosshair is wrong" rather than "the renderer is".
	 */
	private static void write(float[] out, int offset, int face, float u, float v) {
		float x;
		float y;
		float z;
		float d = DECAL_OFFSET;

		switch (face) {
			case FaceAxes.NORTH -> {
				x = 1.0F - u;
				y = 1.0F - v;
				z = -d;
			}
			case FaceAxes.SOUTH -> {
				x = u;
				y = 1.0F - v;
				z = 1.0F + d;
			}
			case FaceAxes.WEST -> {
				x = -d;
				y = 1.0F - v;
				z = u;
			}
			case FaceAxes.EAST -> {
				x = 1.0F + d;
				y = 1.0F - v;
				z = 1.0F - u;
			}
			case FaceAxes.UP -> {
				x = u;
				y = 1.0F + d;
				z = v;
			}
			case FaceAxes.DOWN -> {
				x = u;
				y = -d;
				z = 1.0F - v;
			}
			default -> throw new IllegalArgumentException("bad face: " + face);
		}

		out[offset] = x;
		out[offset + 1] = y;
		out[offset + 2] = z;
	}
}
