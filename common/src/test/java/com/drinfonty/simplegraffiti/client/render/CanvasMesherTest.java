package com.drinfonty.simplegraffiti.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import com.drinfonty.simplegraffiti.canvas.Brush;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;
import com.drinfonty.simplegraffiti.canvas.PaintColor;

import org.junit.jupiter.api.Test;

/**
 * Meshing is tested by equivalence against the naive per-texel rendering it replaces, rather than
 * by asserting a particular set of rectangles: the optimisation is allowed to change, the picture
 * is not.
 */
class CanvasMesherTest {
	/** Rebuilds the texel grid from the quads, failing on any overlap. */
	private static int[] rasterise(List<PaintQuad> quads) {
		int[] grid = new int[Canvas.TEXELS];

		for (PaintQuad quad : quads) {
			assertTrue(quad.minU() >= 0 && quad.maxU() <= Canvas.SIZE, "u out of range");
			assertTrue(quad.minV() >= 0 && quad.maxV() <= Canvas.SIZE, "v out of range");
			assertTrue(quad.widthTexels() > 0 && quad.heightTexels() > 0, "degenerate quad");
			assertTrue(PaintColor.isPainted(quad.argb()), "an unpainted quad was emitted");

			for (int pv = quad.minV(); pv < quad.maxV(); pv++) {
				for (int pu = quad.minU(); pu < quad.maxU(); pu++) {
					int index = pv * Canvas.SIZE + pu;
					assertEquals(0, grid[index], "quads overlap at " + pu + "," + pv);
					grid[index] = quad.argb();
				}
			}
		}

		return grid;
	}

	private static void assertCoversExactly(int[] texels) {
		List<PaintQuad> quads = CanvasMesher.mesh(texels, FaceAxes.NORTH);

		assertTrue(quads.size() <= Canvas.TEXELS, "more quads than texels");
		assertEqualsGrid(texels, rasterise(quads));
	}

	private static void assertEqualsGrid(int[] expected, int[] actual) {
		for (int i = 0; i < Canvas.TEXELS; i++) {
			assertEquals(PaintColor.normalise(expected[i]), actual[i],
				"texel " + (i % Canvas.SIZE) + "," + (i / Canvas.SIZE));
		}
	}

	@Test
	void emptyCanvasProducesNothing() {
		assertTrue(CanvasMesher.mesh(new int[Canvas.TEXELS], FaceAxes.UP).isEmpty());
	}

	@Test
	void aFullyPaintedFaceIsOneQuad() {
		int[] texels = new int[Canvas.TEXELS];

		for (int i = 0; i < Canvas.TEXELS; i++) {
			texels[i] = PaintColor.WHITE;
		}

		List<PaintQuad> quads = CanvasMesher.mesh(texels, FaceAxes.SOUTH);
		assertEquals(1, quads.size());
		assertEquals(new PaintQuad(FaceAxes.SOUTH, 0, 0, 16, 16, PaintColor.WHITE), quads.getFirst());
	}

	@Test
	void aSingleColourTagStaysUnderSixteenQuads() {
		// SPEC 11: a single-colour tag must produce fewer than 16 quads.
		int[] texels = new int[Canvas.TEXELS];
		Brush.stamp(texels, 128, 128, Brush.SIZE_LARGE, PaintColor.opaque(0xFF0000));

		List<PaintQuad> quads = CanvasMesher.mesh(texels, FaceAxes.WEST);
		assertTrue(quads.size() < 16, "a large single-colour stamp produced " + quads.size() + " quads");
		assertCoversExactly(texels);
	}

	@Test
	void theWorstCaseIsBoundedAtTwoFiftySix() {
		int[] texels = new int[Canvas.TEXELS];

		for (int i = 0; i < Canvas.TEXELS; i++) {
			texels[i] = PaintColor.opaque(i * 4099 + 1);
		}

		List<PaintQuad> quads = CanvasMesher.mesh(texels, FaceAxes.EAST);
		assertTrue(quads.size() <= Canvas.TEXELS, "produced " + quads.size() + " quads");
		assertCoversExactly(texels);
	}

	@Test
	void coversRandomCanvasesExactly() {
		Random random = new Random(4242L);

		for (int i = 0; i < 300; i++) {
			int[] texels = new int[Canvas.TEXELS];

			for (int t = 0; t < Canvas.TEXELS; t++) {
				texels[t] = random.nextInt(3) == 0
					? PaintColor.EMPTY
					: PaintColor.opaque(random.nextInt(4) * 0x3B5A7C + 1);
			}

			assertCoversExactly(texels);
		}
	}

	@Test
	void coversRealBrushStrokesExactly() {
		Random random = new Random(7L);
		int[] texels = new int[Canvas.TEXELS];

		for (int stroke = 0; stroke < 25; stroke++) {
			Brush.stamp(texels,
				random.nextInt(256),
				random.nextInt(256),
				random.nextInt(3),
				random.nextInt(6) == 0 ? PaintColor.EMPTY : PaintColor.opaque(random.nextInt(5) * 0x2E4F6A + 1));

			assertCoversExactly(texels);
		}
	}

	@Test
	void mergesVerticallyWhereRunsLineUp() {
		// Two identical rows must become one quad, not two: this is the merge that turns a
		// 16x16 block of paint into one rectangle instead of sixteen.
		int[] texels = new int[Canvas.TEXELS];

		for (int pv = 4; pv < 12; pv++) {
			for (int pu = 3; pu < 9; pu++) {
				texels[pv * Canvas.SIZE + pu] = PaintColor.WHITE;
			}
		}

		List<PaintQuad> quads = CanvasMesher.mesh(texels, FaceAxes.DOWN);
		assertEquals(1, quads.size());
		assertEquals(new PaintQuad(FaceAxes.DOWN, 3, 4, 9, 12, PaintColor.WHITE), quads.getFirst());
	}

	@Test
	void carriesTheFaceThrough() {
		int[] texels = new int[Canvas.TEXELS];
		texels[0] = PaintColor.WHITE;

		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			assertEquals(face, CanvasMesher.mesh(texels, face).getFirst().face());
		}
	}
}
