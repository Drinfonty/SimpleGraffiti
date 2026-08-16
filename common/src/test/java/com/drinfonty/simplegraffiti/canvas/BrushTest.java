package com.drinfonty.simplegraffiti.canvas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The brush is the one piece of logic that runs on both sides of the network and must agree
 * bit-for-bit, so it gets the most thorough tests in the project. A brush that rounds differently
 * on the client than on the server looks like lag, not like a bug, which is exactly the kind of
 * defect a playtest will never localise.
 */
class BrushTest {
	/**
	 * An independent transcription of the pseudocode in SPEC 4.3, written from the document rather
	 * than from {@link Brush}, so the two can disagree.
	 */
	private static int[] reference(int[] texels, int u8, int v8, int size, int value) {
		int[] out = texels.clone();
		int r = switch (size) {
			case 0 -> 24;
			case 1 -> 40;
			case 2 -> 64;
			default -> throw new IllegalArgumentException();
		};
		for (int pv = clamp(Math.floorDiv(v8 - r, 16)); pv <= clamp(Math.floorDiv(v8 + r, 16)); pv++) {
			for (int pu = clamp(Math.floorDiv(u8 - r, 16)); pu <= clamp(Math.floorDiv(u8 + r, 16)); pu++) {
				int dx = (pu * 16 + 8) - u8;
				int dy = (pv * 16 + 8) - v8;
				int d2 = dx * dx + dy * dy;

				if (d2 < r * r) {
					out[pv * 16 + pu] = value;
				}
			}
		}

		return out;
	}

	private static int clamp(int value) {
		return Math.max(0, Math.min(15, value));
	}

	@Test
	void matchesTheSpecPseudocodeEverywhere() {
		// Every quantised hit point at every brush size, not a sample: the whole input space is
		// 3 * 256 * 256, which is cheap enough to enumerate exhaustively and leaves no corner
		// for a rounding difference to hide in.
		for (int size = Brush.MIN_SIZE; size <= Brush.MAX_SIZE; size++) {
			for (int u8 = 0; u8 <= 255; u8++) {
				for (int v8 = 0; v8 <= 255; v8++) {
					int[] mine = new int[Canvas.TEXELS];
					Brush.stamp(mine, u8, v8, size, PaintColor.WHITE);

					assertArrayEquals(
						reference(new int[Canvas.TEXELS], u8, v8, size, PaintColor.WHITE),
						mine,
						"size " + size + " at " + u8 + "," + v8);
				}
			}
		}
	}

	@Test
	void isDeterministicAcrossRepeatedApplication() {
		Random random = new Random(1234L);

		for (int i = 0; i < 200; i++) {
			int u8 = random.nextInt(256);
			int v8 = random.nextInt(256);
			int size = random.nextInt(3);
			int value = PaintColor.opaque(random.nextInt(0x1000000));

			int[] first = new int[Canvas.TEXELS];
			int[] second = new int[Canvas.TEXELS];
			Brush.stamp(first, u8, v8, size, value);
			Brush.stamp(second, u8, v8, size, value);

			assertArrayEquals(first, second);
		}
	}

	@Test
	void replayingTheSameStampIsIdempotent() {
		// This is what lets a predicting client apply the server's echo of its own stamp without
		// checking whether it was the painter (SPEC 6.1).
		int[] texels = new int[Canvas.TEXELS];
		assertTrue(Brush.stamp(texels, 128, 128, Brush.SIZE_MEDIUM, PaintColor.WHITE));

		int[] snapshot = texels.clone();
		assertFalse(Brush.stamp(texels, 128, 128, Brush.SIZE_MEDIUM, PaintColor.WHITE),
			"a stamp that changes nothing must report no change, so no charge is spent");
		assertArrayEquals(snapshot, texels);
	}

	@Test
	void clipsAtTheBorderRatherThanWrapping() {
		// Paint must not spill onto an adjacent face or block (SPEC 4.3). A large brush in the
		// corner is the case that would wrap if the bounds were computed with a mask.
		int[] texels = new int[Canvas.TEXELS];
		Brush.stamp(texels, 0, 0, Brush.SIZE_LARGE, PaintColor.WHITE);

		for (int pv = 0; pv < Canvas.SIZE; pv++) {
			for (int pu = 0; pu < Canvas.SIZE; pu++) {
				if (PaintColor.isPainted(texels[pv * Canvas.SIZE + pu])) {
					assertTrue(pu < 8 && pv < 8,
						"a corner stamp reached the far side at " + pu + "," + pv);
				}
			}
		}
	}

	@Test
	void erasingUsesTheSameFootprintAsPainting() {
		// An erase stroke must feather exactly the way a paint stroke does, dither included.
		int[] painted = new int[Canvas.TEXELS];
		Brush.stamp(painted, 100, 90, Brush.SIZE_MEDIUM, PaintColor.WHITE);

		int[] erased = painted.clone();
		assertTrue(Brush.stamp(erased, 100, 90, Brush.SIZE_MEDIUM, PaintColor.EMPTY));

		for (int value : erased) {
			assertFalse(PaintColor.isPainted(value));
		}
	}

	@Test
	void everySizePaintsSomethingAndLargerPaintsMore() {
		int previous = -1;

		for (int size = Brush.MIN_SIZE; size <= Brush.MAX_SIZE; size++) {
			int[] texels = new int[Canvas.TEXELS];
			Brush.stamp(texels, 128, 128, size, PaintColor.WHITE);

			int painted = 0;

			for (int value : texels) {
				if (PaintColor.isPainted(value)) {
					painted++;
				}
			}

			assertTrue(painted > previous, "size " + size + " painted " + painted + " texels");
			previous = painted;
		}
	}

	@Test
	void rejectsOutOfRangeInput() {
		assertThrows(IllegalArgumentException.class,
			() -> Brush.stamp(new int[Canvas.TEXELS], -1, 0, 0, PaintColor.WHITE));
		assertThrows(IllegalArgumentException.class,
			() -> Brush.stamp(new int[Canvas.TEXELS], 256, 0, 0, PaintColor.WHITE));
		assertThrows(IllegalArgumentException.class,
			() -> Brush.stamp(new int[Canvas.TEXELS], 0, 0, 3, PaintColor.WHITE));
		assertThrows(IllegalArgumentException.class,
			() -> Brush.stamp(new int[16], 0, 0, 0, PaintColor.WHITE));
	}

	@Test
	void radiiMatchTheSpecTable() {
		assertEquals(24, Brush.radius(Brush.SIZE_SMALL));
		assertEquals(40, Brush.radius(Brush.SIZE_MEDIUM));
		assertEquals(64, Brush.radius(Brush.SIZE_LARGE));
	}

	/**
	 * What a player dragging slowly would paint: one stamp at every quantisation step along the path.
	 *
	 * <p>Written out independently of {@link Brush#stampLine} so the two can disagree.
	 */
	private static int[] slowDrag(int u0, int v0, int u1, int v1, int size) {
		int[] texels = new int[Canvas.TEXELS];
		int steps = Math.max(Math.abs(u1 - u0), Math.abs(v1 - v0));

		for (int i = 0; i <= steps; i++) {
			int u = steps == 0 ? u0 : u0 + ((u1 - u0) * i) / steps;
			int v = steps == 0 ? v0 : v0 + ((v1 - v0) * i) / steps;
			Brush.stamp(texels, u, v, size, PaintColor.WHITE);
		}

		return texels;
	}

	/**
	 * The property that actually matters for "dragging gives disconnected blobs": how a stroke looks
	 * must not depend on how fast the player moved or on how often the game sampled the crosshair.
	 *
	 * <p>Deliberately not "every texel along the path is painted" - that is false by design, because
	 * the Bayer dither gives the brush a soft stippled edge and some texels are only reachable when a
	 * stamp centre passes almost exactly over them. The honest invariant is equivalence with the
	 * slowest possible drag, and it is what stops anyone making the walk coarser to save cycles.
	 */
	@Test
	void aFastDragPaintsExactlyWhatASlowDragWould() {
		Random random = new Random(31337L);

		for (int size = Brush.MIN_SIZE; size <= Brush.MAX_SIZE; size++) {
			for (int i = 0; i < 300; i++) {
				int u0 = random.nextInt(256);
				int v0 = random.nextInt(256);
				int u1 = random.nextInt(256);
				int v1 = random.nextInt(256);

				int[] fast = new int[Canvas.TEXELS];
				Brush.stampLine(fast, u0, v0, u1, v1, size, PaintColor.WHITE);

				assertArrayEquals(slowDrag(u0, v0, u1, v1, size), fast,
					"fast drag differs from slow drag: " + u0 + "," + v0 + " -> " + u1 + "," + v1);
			}
		}
	}

	@Test
	void theFastestPossibleDragMatchesTheSlowest() {
		// Corner to corner in a single sample is the worst case a player can produce: the crosshair
		// crossed the whole face between two ticks.
		for (int size = Brush.MIN_SIZE; size <= Brush.MAX_SIZE; size++) {
			for (int[] ends : new int[][] { {0, 0, 255, 255}, {255, 0, 0, 255}, {0, 128, 255, 128}, {128, 0, 128, 255} }) {
				int[] fast = new int[Canvas.TEXELS];
				Brush.stampLine(fast, ends[0], ends[1], ends[2], ends[3], size, PaintColor.WHITE);
				assertArrayEquals(slowDrag(ends[0], ends[1], ends[2], ends[3], size), fast);
			}
		}
	}

	/**
	 * Guards the whole point of the feature: a stroke must fill in the middle, not just stamp its two
	 * ends. Deleting the interpolation loop would still pass every other test in this file.
	 */
	@Test
	void aStrokeFillsBetweenItsEndpointsRatherThanJustStampingThem() {
		int[] stroke = new int[Canvas.TEXELS];
		Brush.stampLine(stroke, 24, 128, 232, 128, Brush.SIZE_MEDIUM, PaintColor.WHITE);

		int[] endsOnly = new int[Canvas.TEXELS];
		Brush.stamp(endsOnly, 24, 128, Brush.SIZE_MEDIUM, PaintColor.WHITE);
		Brush.stamp(endsOnly, 232, 128, Brush.SIZE_MEDIUM, PaintColor.WHITE);

		int strokePainted = 0;
		int endsPainted = 0;

		for (int i = 0; i < Canvas.TEXELS; i++) {
			if (PaintColor.isPainted(stroke[i])) {
				strokePainted++;
			}

			if (PaintColor.isPainted(endsOnly[i])) {
				endsPainted++;
			}
		}

		assertTrue(strokePainted > endsPainted * 2,
			"stroke painted " + strokePainted + " texels, two lone stamps painted " + endsPainted);
	}

	/**
	 * With a solid brush the strong property holds: every point the crosshair passed through lands
	 * inside a painted texel, so a drag is a continuous line at any speed. This could not be
	 * asserted while the brush dithered its edge, because a dithered texel is deliberately skipped.
	 */
	@Test
	void aStrokeIsSolidAlongItsWholePath() {
		Random random = new Random(9001L);

		for (int size = Brush.MIN_SIZE; size <= Brush.MAX_SIZE; size++) {
			for (int i = 0; i < 200; i++) {
				int u0 = random.nextInt(256);
				int v0 = random.nextInt(256);
				int u1 = random.nextInt(256);
				int v1 = random.nextInt(256);

				int[] texels = new int[Canvas.TEXELS];
				Brush.stampLine(texels, u0, v0, u1, v1, size, PaintColor.WHITE);

				int span = Math.max(Math.abs(u1 - u0), Math.abs(v1 - v0));

				for (int step = 0; step <= span; step++) {
					int u = span == 0 ? u0 : u0 + ((u1 - u0) * step) / span;
					int v = span == 0 ? v0 : v0 + ((v1 - v0) * step) / span;

					assertTrue(PaintColor.isPainted(texels[(v / 16) * Canvas.SIZE + (u / 16)]),
						"gap at texel " + (u / 16) + "," + (v / 16) + " along stroke "
							+ u0 + "," + v0 + " -> " + u1 + "," + v1 + " (size " + size + ")");
				}
			}
		}
	}

	/** A single stamp is a solid disc: no holes anywhere inside its radius. */
	@Test
	void aStampIsSolidWithNoHoles() {
		for (int size = Brush.MIN_SIZE; size <= Brush.MAX_SIZE; size++) {
			int[] texels = new int[Canvas.TEXELS];
			Brush.stamp(texels, 128, 128, size, PaintColor.WHITE);

			int radius = Brush.radius(size);

			for (int pv = 0; pv < Canvas.SIZE; pv++) {
				for (int pu = 0; pu < Canvas.SIZE; pu++) {
					int dx = (pu * 16 + 8) - 128;
					int dy = (pv * 16 + 8) - 128;
					boolean inside = dx * dx + dy * dy < radius * radius;

					assertEquals(inside, PaintColor.isPainted(texels[pv * Canvas.SIZE + pu]),
						"texel " + pu + "," + pv + " at size " + size);
				}
			}
		}
	}

	@Test
	void aStrokeCoversBothEndsAndTheMiddle() {
		int[] line = new int[Canvas.TEXELS];
		Brush.stampLine(line, 8, 128, 248, 128, Brush.SIZE_SMALL, PaintColor.WHITE);

		// Every texel column along the path should have been reached, which a row of spaced dots
		// at the same brush size would not manage.
		for (int pu = 0; pu < Canvas.SIZE; pu++) {
			boolean any = false;

			for (int pv = 0; pv < Canvas.SIZE; pv++) {
				any |= PaintColor.isPainted(line[pv * Canvas.SIZE + pu]);
			}

			assertTrue(any, "column " + pu + " was skipped by the stroke");
		}
	}

	@Test
	void aZeroLengthStrokeEqualsASingleStamp() {
		int[] stroke = new int[Canvas.TEXELS];
		int[] single = new int[Canvas.TEXELS];

		Brush.stampLine(stroke, 100, 100, 100, 100, Brush.SIZE_MEDIUM, PaintColor.WHITE);
		Brush.stamp(single, 100, 100, Brush.SIZE_MEDIUM, PaintColor.WHITE);

		assertArrayEquals(single, stroke);
	}

	@Test
	void strokesAreDeterministicAndIdempotent() {
		int[] first = new int[Canvas.TEXELS];
		int[] second = new int[Canvas.TEXELS];
		Brush.stampLine(first, 20, 40, 200, 190, Brush.SIZE_LARGE, PaintColor.WHITE);
		Brush.stampLine(second, 20, 40, 200, 190, Brush.SIZE_LARGE, PaintColor.WHITE);
		assertArrayEquals(first, second);

		assertFalse(Brush.stampLine(first, 20, 40, 200, 190, Brush.SIZE_LARGE, PaintColor.WHITE),
			"replaying a stroke must be a no-op, or prediction would drift");
	}

	@Test
	void strokeRejectsOutOfRangeEndpoints() {
		assertThrows(IllegalArgumentException.class,
			() -> Brush.stampLine(new int[Canvas.TEXELS], -1, 0, 10, 10, 0, PaintColor.WHITE));
		assertThrows(IllegalArgumentException.class,
			() -> Brush.stampLine(new int[Canvas.TEXELS], 0, 0, 256, 10, 0, PaintColor.WHITE));
	}
}
