package com.drinfonty.simplegraffiti.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Spraying near a block's edge must mark the neighbouring block too, and spraying a corner must
 * mark every block meeting there.
 *
 * <p>From a bug report: "when I paint at the corner, it only shows the blob on the active block
 * face, whereas it should show on the adjacent blocks too". The brush used to be clipped at the
 * block boundary, so a disc landing on a seam was sliced in half and the neighbour got nothing.
 *
 * <p>These tests work at the geometry level - stamping one disc into each block it overlaps, with
 * the centre expressed relative to that block - which is exactly what the applier does.
 */
class BrushBleedTest {
	private static final int UNITS = FaceStroke.UNITS_PER_BLOCK;

	/** Stamps a disc centred at a global point into every block it reaches. */
	private static Map<Long, int[]> spray(int face, int globalU, int globalV, int size) {
		Map<Long, int[]> world = new HashMap<>();
		int radius = Brush.radius(size);

		int uA = FaceStroke.blockOfU(face, globalU - radius);
		int uB = FaceStroke.blockOfU(face, globalU + radius);
		int vA = FaceStroke.blockOfV(face, globalV - radius);
		int vB = FaceStroke.blockOfV(face, globalV + radius);

		for (int bu = Math.min(uA, uB); bu <= Math.max(uA, uB); bu++) {
			for (int bv = Math.min(vA, vB); bv <= Math.max(vA, vB); bv++) {
				int[] canvas = new int[Canvas.TEXELS];

				if (Brush.stampOffCanvas(canvas,
					globalU - FaceStroke.encodeU(face, bu, 0),
					globalV - FaceStroke.encodeV(face, bv, 0),
					size, PaintColor.WHITE)) {
					world.put(((long) bu << 32) ^ (bv & 0xFFFFFFFFL), canvas);
				}
			}
		}

		return world;
	}

	private static int paintedIn(int[] canvas) {
		int n = 0;

		for (int texel : canvas) {
			if (PaintColor.isPainted(texel)) {
				n++;
			}
		}

		return n;
	}

	@Test
	void sprayingExactlyOnACornerMarksAllFourBlocks() {
		// The corner where four blocks meet on a flat plane.
		Map<Long, int[]> world = spray(FaceAxes.UP, UNITS, UNITS, Brush.SIZE_MEDIUM);

		assertEquals(4, world.size(), "a corner spray should reach all four blocks meeting there");

		for (int[] canvas : world.values()) {
			assertTrue(paintedIn(canvas) > 0);
		}
	}

	@Test
	void sprayingOnASeamMarksBothBlocks() {
		// Mid-way along the edge between two blocks: two blocks, not four.
		Map<Long, int[]> world = spray(FaceAxes.UP, UNITS, UNITS + 128, Brush.SIZE_MEDIUM);
		assertEquals(2, world.size());
	}

	@Test
	void sprayingWellInsideABlockMarksOnlyThatBlock() {
		Map<Long, int[]> world = spray(FaceAxes.UP, 128, 128, Brush.SIZE_MEDIUM);
		assertEquals(1, world.size());
	}

	@Test
	void aCornerSprayIsWholeRatherThanSliced() {
		// The whole point: the paint either side of a seam must add up to the same disc a spray in
		// open canvas produces. A clipped brush would lose everything past the boundary.
		for (int size = Brush.MIN_SIZE; size <= Brush.MAX_SIZE; size++) {
			int open = paintedIn(spray(FaceAxes.UP, 128, 128, size).values().iterator().next());

			int onCorner = 0;

			for (int[] canvas : spray(FaceAxes.UP, UNITS, UNITS, size).values()) {
				onCorner += paintedIn(canvas);
			}

			assertEquals(open, onCorner,
				"a disc on a corner covered " + onCorner + " texels but " + open + " in open canvas (size " + size + ")");
		}
	}

	@Test
	void bleedWorksOnEveryFaceIncludingInvertedAxes() {
		// NORTH and EAST have a u axis running opposite to their world axis, and every side face
		// has v running down world Y. Getting a sign wrong would put the bleed on the wrong
		// neighbour, so each face is checked for a whole disc across the seam.
		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			int open = paintedIn(spray(face, 128, 128, Brush.SIZE_MEDIUM).values().iterator().next());
			int across = 0;

			for (int[] canvas : spray(face, UNITS, UNITS, Brush.SIZE_MEDIUM).values()) {
				across += paintedIn(canvas);
			}

			assertEquals(open, across, "face " + face + " lost paint across the seam");
		}
	}
}
