package com.drinfonty.simplegraffiti.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;
import com.drinfonty.simplegraffiti.canvas.PaintColor;

import org.junit.jupiter.api.Test;

/**
 * The property that matters here is that {@link PaintGeometry} is the exact inverse of
 * {@link FaceAxes}. If the two disagree even by a flip, paint lands mirrored on some faces - a bug
 * that reads to a player as "the crosshair is wrong" and is miserable to localise by eye.
 */
class PaintGeometryTest {
	private static final float EPSILON = 1e-5F;

	@Test
	void isTheInverseOfTheFaceTableOnEveryFace() {
		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			// A one-texel quad in a distinctive, non-symmetric spot: a centred or square patch
			// would pass even with the axes swapped.
			PaintQuad quad = new PaintQuad(face, 2, 5, 3, 6, PaintColor.WHITE);
			float[] corners = PaintGeometry.corners(quad, new float[12]);

			for (int corner = 0; corner < 4; corner++) {
				double x = corners[corner * 3];
				double y = corners[corner * 3 + 1];
				double z = corners[corner * 3 + 2];

				double u = FaceAxes.u(face, clampToBlock(x), clampToBlock(y), clampToBlock(z));
				double v = FaceAxes.v(face, clampToBlock(x), clampToBlock(y), clampToBlock(z));

				assertTrue(u >= 2.0 / Canvas.SIZE - EPSILON && u <= 3.0 / Canvas.SIZE + EPSILON,
					"face " + face + " corner " + corner + " has u=" + u);
				assertTrue(v >= 5.0 / Canvas.SIZE - EPSILON && v <= 6.0 / Canvas.SIZE + EPSILON,
					"face " + face + " corner " + corner + " has v=" + v);
			}
		}
	}

	/** Undoes the decal offset, which pushes corners just outside the unit cube by design. */
	private static double clampToBlock(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	@Test
	void eachFaceSitsJustOutsideItsOwnPlane() {
		assertPlane(FaceAxes.NORTH, 2, -PaintGeometry.DECAL_OFFSET);
		assertPlane(FaceAxes.SOUTH, 2, 1.0F + PaintGeometry.DECAL_OFFSET);
		assertPlane(FaceAxes.WEST, 0, -PaintGeometry.DECAL_OFFSET);
		assertPlane(FaceAxes.EAST, 0, 1.0F + PaintGeometry.DECAL_OFFSET);
		assertPlane(FaceAxes.UP, 1, 1.0F + PaintGeometry.DECAL_OFFSET);
		assertPlane(FaceAxes.DOWN, 1, -PaintGeometry.DECAL_OFFSET);
	}

	private static void assertPlane(int face, int axis, float expected) {
		float[] corners = PaintGeometry.corners(
			new PaintQuad(face, 0, 0, 16, 16, PaintColor.WHITE), new float[12]);

		for (int corner = 0; corner < 4; corner++) {
			assertEquals(expected, corners[corner * 3 + axis], EPSILON,
				"face " + face + " corner " + corner + " is off its plane");
		}
	}

	@Test
	void aFullFaceQuadCoversTheWholeFace() {
		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			float[] corners = PaintGeometry.corners(
				new PaintQuad(face, 0, 0, 16, 16, PaintColor.WHITE), new float[12]);

			for (int corner = 0; corner < 4; corner++) {
				for (int axis = 0; axis < 3; axis++) {
					float value = corners[corner * 3 + axis];
					assertTrue(value >= -PaintGeometry.DECAL_OFFSET - EPSILON
							&& value <= 1.0F + PaintGeometry.DECAL_OFFSET + EPSILON,
						"face " + face + " strays outside the block at " + value);
				}
			}
		}
	}

	@Test
	void cornersAreDistinct() {
		// A degenerate quad renders as nothing at all, silently.
		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			float[] corners = PaintGeometry.corners(
				new PaintQuad(face, 4, 4, 12, 12, PaintColor.WHITE), new float[12]);

			for (int a = 0; a < 4; a++) {
				for (int b = a + 1; b < 4; b++) {
					boolean same = corners[a * 3] == corners[b * 3]
						&& corners[a * 3 + 1] == corners[b * 3 + 1]
						&& corners[a * 3 + 2] == corners[b * 3 + 2];
					assertTrue(!same, "face " + face + " has duplicate corners " + a + " and " + b);
				}
			}
		}
	}
}
