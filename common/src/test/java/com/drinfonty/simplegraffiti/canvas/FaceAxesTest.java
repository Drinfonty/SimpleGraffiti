package com.drinfonty.simplegraffiti.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The face table (SPEC 4.2) is the definition of "where did I just spray", shared by the client
 * that predicts, the server that validates and the renderer that draws. If any two of them disagree
 * the paint lands somewhere other than the crosshair, which is the single most visible way this mod
 * can be wrong.
 */
class FaceAxesTest {
	private static final double EPSILON = 1e-9;

	@Test
	void matchesTheSpecTable() {
		double lx = 0.25;
		double ly = 0.75;
		double lz = 0.125;

		assertEquals(1.0 - lx, FaceAxes.u(FaceAxes.NORTH, lx, ly, lz), EPSILON);
		assertEquals(1.0 - ly, FaceAxes.v(FaceAxes.NORTH, lx, ly, lz), EPSILON);

		assertEquals(lx, FaceAxes.u(FaceAxes.SOUTH, lx, ly, lz), EPSILON);
		assertEquals(1.0 - ly, FaceAxes.v(FaceAxes.SOUTH, lx, ly, lz), EPSILON);

		assertEquals(lz, FaceAxes.u(FaceAxes.WEST, lx, ly, lz), EPSILON);
		assertEquals(1.0 - ly, FaceAxes.v(FaceAxes.WEST, lx, ly, lz), EPSILON);

		assertEquals(1.0 - lz, FaceAxes.u(FaceAxes.EAST, lx, ly, lz), EPSILON);
		assertEquals(1.0 - ly, FaceAxes.v(FaceAxes.EAST, lx, ly, lz), EPSILON);

		assertEquals(lx, FaceAxes.u(FaceAxes.UP, lx, ly, lz), EPSILON);
		assertEquals(lz, FaceAxes.v(FaceAxes.UP, lx, ly, lz), EPSILON);

		assertEquals(lx, FaceAxes.u(FaceAxes.DOWN, lx, ly, lz), EPSILON);
		assertEquals(1.0 - lz, FaceAxes.v(FaceAxes.DOWN, lx, ly, lz), EPSILON);
	}

	@Test
	void everyFaceMapsTheWholeBlockIntoTheUnitSquare() {
		// No face may produce a coordinate outside [0,1] for a hit inside the block, or the
		// quantiser would clamp real hits onto the border texels.
		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			for (double lx = 0.0; lx <= 1.0; lx += 0.125) {
				for (double ly = 0.0; ly <= 1.0; ly += 0.125) {
					for (double lz = 0.0; lz <= 1.0; lz += 0.125) {
						double u = FaceAxes.u(face, lx, ly, lz);
						double v = FaceAxes.v(face, lx, ly, lz);

						assertTrue(u >= 0.0 && u <= 1.0, "u=" + u + " face=" + face);
						assertTrue(v >= 0.0 && v <= 1.0, "v=" + v + " face=" + face);
					}
				}
			}
		}
	}

	@Test
	void quantisationCoversTheBordersWithoutOverflowing() {
		assertEquals(0, FaceAxes.quantise(0.0));
		assertEquals(255, FaceAxes.quantise(1.0));
		assertEquals(128, FaceAxes.quantise(0.5));

		// Floating-point slop just outside the face - a ray hit on the seam - must clamp rather
		// than produce an out-of-range byte the server would then drop as malformed.
		assertEquals(0, FaceAxes.quantise(-0.0001));
		assertEquals(255, FaceAxes.quantise(1.0001));
	}

	@Test
	void texelIndicesStayInRange() {
		assertEquals(0, FaceAxes.texel(0.0));
		assertEquals(15, FaceAxes.texel(1.0));
		assertEquals(15, FaceAxes.texel(0.999));
		assertEquals(8, FaceAxes.texel(0.5));
	}

	@Test
	void oppositeIsAnInvolution() {
		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			assertEquals(face, FaceAxes.opposite(FaceAxes.opposite(face)));
		}
	}
}
