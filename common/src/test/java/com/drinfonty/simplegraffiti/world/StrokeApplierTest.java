package com.drinfonty.simplegraffiti.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.drinfonty.simplegraffiti.canvas.Brush;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;
import com.drinfonty.simplegraffiti.canvas.PaintColor;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * From a bug report: the sponge threw particles even when it erased nothing, and when it did erase
 * something the flecks were often the wrong colour.
 *
 * <p>Both came from guessing the colour off the canvas before erasing - a brush over bare stone on
 * an otherwise painted face looked identical to a brush over paint, and the fallback that searched
 * the rest of the face for *a* colour reliably found the wrong one. The fix is for the applier to
 * report what it genuinely changed, which is what these tests pin down.
 */
class StrokeApplierTest {
	/** A canvas store backed by a plain map, standing in for the client or server one. */
	private static final class FakeAccess implements StrokeApplier.CanvasAccess {
		private final Map<Long, Canvas> canvases = new HashMap<>();
		private boolean allowAll = true;

		private static long key(BlockPos pos, int face) {
			return (((long) pos.getX() & 0xFFFF) << 32) ^ (((long) pos.getZ() & 0xFFFF) << 16) ^ face;
		}

		@Override
		public Canvas get(BlockPos pos, int face) {
			return canvases.get(key(pos, face));
		}

		@Override
		public void put(BlockPos pos, int face, Canvas canvas) {
			if (canvas == null) {
				canvases.remove(key(pos, face));
			} else {
				canvases.put(key(pos, face), canvas);
			}
		}

		@Override
		public boolean mayPaint(BlockPos pos, int face) {
			return allowAll;
		}
	}

	private static List<Integer> applyCollecting(FakeAccess access, BlockPos pos, int u8, int v8, int value) {
		List<Integer> seen = new ArrayList<>();
		StrokeApplier.apply(FaceAxes.UP, pos, u8, v8, pos, u8, v8, Brush.SIZE_SMALL, value,
			null, 0L, access, seen::add);
		return seen;
	}

	@Test
	void erasingBareCanvasReportsNothing() {
		FakeAccess access = new FakeAccess();
		BlockPos pos = new BlockPos(0, 70, 0);

		List<Integer> seen = applyCollecting(access, pos, 128, 128, PaintColor.EMPTY);

		assertTrue(seen.isEmpty(), "erasing an unpainted face reported changes");
	}

	@Test
	void erasingAwayFromThePaintReportsNothing() {
		// The bug exactly: the face has paint, but the brush is nowhere near it. Sampling the face
		// for "a" colour used to return one here, so particles flew off bare stone.
		FakeAccess access = new FakeAccess();
		BlockPos pos = new BlockPos(0, 70, 0);

		int[] texels = new int[Canvas.TEXELS];
		Brush.stamp(texels, 16, 16, Brush.SIZE_SMALL, PaintColor.opaque(0xFF0000));
		access.put(pos, FaceAxes.UP, Canvas.ofOwned(texels, null, 0L));

		List<Integer> seen = applyCollecting(access, pos, 240, 240, PaintColor.EMPTY);

		assertTrue(seen.isEmpty(), "erasing bare canvas on a painted face reported changes");
	}

	@Test
	void erasingPaintReportsExactlyThatColour() {
		FakeAccess access = new FakeAccess();
		BlockPos pos = new BlockPos(0, 70, 0);

		int[] texels = new int[Canvas.TEXELS];
		Brush.stamp(texels, 128, 128, Brush.SIZE_MEDIUM, PaintColor.opaque(0x3C44AA));
		access.put(pos, FaceAxes.UP, Canvas.ofOwned(texels, null, 0L));

		List<Integer> seen = applyCollecting(access, pos, 128, 128, PaintColor.EMPTY);

		assertFalse(seen.isEmpty(), "erasing paint reported nothing");

		for (int previous : seen) {
			assertEquals(PaintColor.opaque(0x3C44AA), previous,
				"reported a colour that was not the one erased");
		}
	}

	@Test
	void theReportedColourIsTheOneUnderTheBrushNotElsewhereOnTheFace() {
		// The second half of the bug: two colours on one face, and the flecks must match the one
		// actually scrubbed rather than whichever the old scan happened to reach first.
		FakeAccess access = new FakeAccess();
		BlockPos pos = new BlockPos(0, 70, 0);

		int[] texels = new int[Canvas.TEXELS];
		Brush.stamp(texels, 24, 24, Brush.SIZE_SMALL, PaintColor.opaque(0xFF0000));
		Brush.stamp(texels, 232, 232, Brush.SIZE_SMALL, PaintColor.opaque(0x00FF00));
		access.put(pos, FaceAxes.UP, Canvas.ofOwned(texels, null, 0L));

		for (int previous : applyCollecting(access, pos, 232, 232, PaintColor.EMPTY)) {
			assertEquals(PaintColor.opaque(0x00FF00), previous,
				"reported the colour from the other side of the face");
		}
	}

	@Test
	void paintingOverPaintReportsThePreviousColour() {
		// Not just erasing: the sink reports whatever a texel used to be, so repainting red over
		// blue reports blue.
		FakeAccess access = new FakeAccess();
		BlockPos pos = new BlockPos(0, 70, 0);

		int[] texels = new int[Canvas.TEXELS];
		Brush.stamp(texels, 128, 128, Brush.SIZE_MEDIUM, PaintColor.opaque(0x0000FF));
		access.put(pos, FaceAxes.UP, Canvas.ofOwned(texels, null, 0L));

		List<Integer> seen = applyCollecting(access, pos, 128, 128, PaintColor.opaque(0xFF0000));

		assertFalse(seen.isEmpty());

		for (int previous : seen) {
			assertTrue(previous == PaintColor.opaque(0x0000FF) || previous == PaintColor.EMPTY,
				"unexpected previous colour " + Integer.toHexString(previous));
		}
	}

	@Test
	void repaintingTheSameColourReportsNothing() {
		FakeAccess access = new FakeAccess();
		BlockPos pos = new BlockPos(0, 70, 0);

		int[] texels = new int[Canvas.TEXELS];
		Brush.stamp(texels, 128, 128, Brush.SIZE_MEDIUM, PaintColor.opaque(0x123456));
		access.put(pos, FaceAxes.UP, Canvas.ofOwned(texels, null, 0L));

		assertTrue(applyCollecting(access, pos, 128, 128, PaintColor.opaque(0x123456)).isEmpty(),
			"a no-op repaint reported changes");
	}
}
