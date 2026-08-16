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
}
