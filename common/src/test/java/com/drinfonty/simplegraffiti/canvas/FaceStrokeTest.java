package com.drinfonty.simplegraffiti.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * These tests exist because of a real bug report: "the drag is still not smooth, I still get
 * disconnected blobs unless I drag very slowly". The cause was that a stroke could only join two
 * points on the same canvas, and a canvas is only 16 texels wide - so at any normal drag speed
 * nearly every sample landed on a new block and became a lone disc.
 *
 * <p>The property under test is therefore explicitly about crossing block boundaries.
 */
class FaceStrokeTest {
	/** Paints a stroke into a sparse world of canvases, keyed by block, exactly as callers do. */
	private static Map<Long, int[]> paint(int face, int fromU, int fromV, int toU, int toV, int size) {
		Map<Long, int[]> world = new HashMap<>();

		FaceStroke.walk(face, fromU, fromV, toU, toV,
			(blockU, blockV, u8, v8) -> {
				long key = ((long) blockU << 32) ^ (blockV & 0xFFFFFFFFL);
				Brush.stamp(world.computeIfAbsent(key, k -> new int[Canvas.TEXELS]), u8, v8, size, PaintColor.WHITE);
			});

		return world;
	}

	/** True when the global point is painted in whichever block's canvas contains it. */
	private static boolean paintedAt(Map<Long, int[]> world, int face, int u, int v) {
		int blockU = Math.floorDiv(u, FaceStroke.UNITS_PER_BLOCK);
		int blockV = Math.floorDiv(v, FaceStroke.UNITS_PER_BLOCK);
		long key = ((long) blockU << 32) ^ (blockV & 0xFFFFFFFFL);
		int[] canvas = world.get(key);

		if (canvas == null) {
			return false;
		}

		int u8 = Math.floorMod(u, FaceStroke.UNITS_PER_BLOCK);
		int v8 = Math.floorMod(v, FaceStroke.UNITS_PER_BLOCK);
		return PaintColor.isPainted(canvas[(v8 / 16) * Canvas.SIZE + (u8 / 16)]);
	}

	@Test
	void aStrokeAcrossManyBlocksIsSolidThroughout() {
		// A long horizontal sweep across eight blocks - the case that used to produce eight
		// disconnected blobs, one per block.
		for (int size = Brush.MIN_SIZE; size <= Brush.MAX_SIZE; size++) {
			int fromU = 40;
			int fromV = 130;
			int toU = 40 + 7 * FaceStroke.UNITS_PER_BLOCK;
			int toV = 130;

			Map<Long, int[]> world = paint(FaceAxes.UP, fromU, fromV, toU, toV, size);
			assertEquals(8, world.size(), "expected the stroke to reach eight blocks");

			for (int u = fromU; u <= toU; u++) {
				assertTrue(paintedAt(world, FaceAxes.UP, u, fromV),
					"gap at global u=" + u + " (size " + size + ")");
			}
		}
	}

	@Test
	void aDiagonalStrokeAcrossBlocksIsSolidThroughout() {
		int fromU = 100;
		int fromV = 100;
		int toU = 100 + 3 * FaceStroke.UNITS_PER_BLOCK;
		int toV = 100 + 2 * FaceStroke.UNITS_PER_BLOCK;

		Map<Long, int[]> world = paint(FaceAxes.UP, fromU, fromV, toU, toV, Brush.SIZE_MEDIUM);

		int steps = Math.max(Math.abs(toU - fromU), Math.abs(toV - fromV));

		for (int i = 0; i <= steps; i++) {
			int u = fromU + ((toU - fromU) * i) / steps;
			int v = fromV + ((toV - fromV) * i) / steps;
			assertTrue(paintedAt(world, FaceAxes.UP, u, v), "gap at " + u + "," + v);
		}
	}

	@Test
	void everyFaceRoundTripsBlockAndLocalCoordinates() {
		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			for (int block = -4; block <= 4; block++) {
				for (int local : new int[] { 0, 1, 127, 255 }) {
					int global = FaceStroke.encodeU(face, block, local);

					// Walking a zero-length stroke must report exactly the block and point it
					// started from, whatever the axis direction.
					int[] seen = new int[2];
					FaceStroke.walk(face, global, FaceStroke.encodeV(face, block, local),
						global, FaceStroke.encodeV(face, block, local),
						(bu, bv, u8, v8) -> {
							seen[0] = bu;
							seen[1] = u8;
						});

					assertEquals(block, seen[0], "face " + face + " block " + block);
					assertEquals(local, seen[1], "face " + face + " local " + local);
				}
			}
		}
	}

	@Test
	void adjacentBlocksAreContiguousOnEveryFace() {
		// Stepping one unit past a block's edge must land in the neighbouring block at the
		// opposite edge - the invariant that stops a stroke jumping when it crosses a boundary.
		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			int edge = FaceStroke.encodeU(face, 0, 255);
			int next = edge + 1;

			int[] seen = new int[2];
			FaceStroke.walk(face, next, FaceStroke.encodeV(face, 0, 0), next, FaceStroke.encodeV(face, 0, 0),
				(bu, bv, u8, v8) -> {
					seen[0] = bu;
					seen[1] = u8;
				});

			assertEquals(0, seen[1], "face " + face + " did not wrap to the far edge of the next block");
		}
	}

	@Test
	void blocksAreVisitedInOrderAndContiguously() {
		java.util.List<Integer> blocks = new java.util.ArrayList<>();

		FaceStroke.walk(FaceAxes.UP, 10, 50, 10 + 4 * FaceStroke.UNITS_PER_BLOCK, 50,
			(bu, bv, u8, v8) -> {
				if (blocks.isEmpty() || blocks.getLast() != bu) {
					blocks.add(bu);
				}
			});

		assertEquals(java.util.List.of(0, 1, 2, 3, 4), blocks);
	}

	@Test
	void refusesStrokesBeyondTheBound() {
		int far = FaceStroke.MAX_STROKE_UNITS + 1;
		assertTrue(FaceStroke.tooFar(0, 0, far, 0));
		assertThrows(IllegalArgumentException.class,
			() -> FaceStroke.walk(FaceAxes.UP, 0, 0, far, 0, (a, b, c, d) -> { }));
	}

	@Test
	void aFastCrossBlockDragMatchesASlowOne() {
		// The same invariant as the single-canvas case, now across blocks: how a stroke looks must
		// not depend on how far the crosshair moved between two samples.
		Random random = new Random(4242L);

		for (int i = 0; i < 40; i++) {
			int fromU = random.nextInt(2000) - 1000;
			int fromV = random.nextInt(2000) - 1000;
			int toU = fromU + random.nextInt(1024) - 512;
			int toV = fromV + random.nextInt(1024) - 512;

			Map<Long, int[]> fast = paint(FaceAxes.UP, fromU, fromV, toU, toV, Brush.SIZE_SMALL);

			// A slow drag: the same path delivered as many short strokes.
			Map<Long, int[]> slow = new HashMap<>();
			int steps = Math.max(Math.abs(toU - fromU), Math.abs(toV - fromV));
			int prevU = fromU;
			int prevV = fromV;

			for (int s = 1; s <= steps; s++) {
				int u = fromU + ((toU - fromU) * s) / steps;
				int v = fromV + ((toV - fromV) * s) / steps;
				int pu = prevU;
				int pv = prevV;

				FaceStroke.walk(FaceAxes.UP, pu, pv, u, v,
					(blockU, blockV, u8, v8) -> {
						long key = ((long) blockU << 32) ^ (blockV & 0xFFFFFFFFL);
						Brush.stamp(slow.computeIfAbsent(key, k -> new int[Canvas.TEXELS]),
							u8, v8, Brush.SIZE_SMALL, PaintColor.WHITE);
					});

				prevU = u;
				prevV = v;
			}

			assertEquals(slow.keySet(), fast.keySet(), "different blocks touched");

			for (Map.Entry<Long, int[]> entry : fast.entrySet()) {
				org.junit.jupiter.api.Assertions.assertArrayEquals(slow.get(entry.getKey()), entry.getValue(),
					"fast and slow drags differ on block " + entry.getKey());
			}
		}
	}
}
