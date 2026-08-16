package com.drinfonty.simplegraffiti.client.render;

import java.util.ArrayList;
import java.util.List;

import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.PaintColor;

/**
 * Turns a canvas into the smallest reasonable set of coloured rectangles (SPEC 11).
 *
 * <p>Greedy in two passes: maximal horizontal runs of one colour per row, then vertically merged
 * where an identical run sits directly above. A typical tag of one or two colours collapses from
 * ~200 texels into a handful of rectangles; the worst case - every texel a different colour - is
 * bounded at 256, which is the number the performance budget is written against.
 *
 * <p>This class is the reason the renderer is split the way it is: everything expensive and
 * everything easy to get wrong happens here, in loader-neutral code with no Minecraft types, and
 * both loader adapters only translate the result. It is also why the rejected per-frame fallback
 * (DESIGN 6.3) remains available - it could drive this unchanged.
 */
public final class CanvasMesher {
	private CanvasMesher() {
	}

	public static List<PaintQuad> mesh(int[] texels, int face) {
		if (texels.length != Canvas.TEXELS) {
			throw new IllegalArgumentException("canvas must be " + Canvas.TEXELS + " texels");
		}

		List<PaintQuad> quads = new ArrayList<>();

		// Per column-run state carried from the row above, so a merge is a single comparison
		// rather than a search: pending[u] is the index in `quads` of an open rectangle whose
		// bottom edge is the previous row and whose left edge is at u.
		int[] runStart = new int[Canvas.SIZE];
		int[] runEnd = new int[Canvas.SIZE];
		int[] runColor = new int[Canvas.SIZE];
		int[] runTop = new int[Canvas.SIZE];
		int openRuns = 0;

		for (int pv = 0; pv < Canvas.SIZE; pv++) {
			int[] rowStart = new int[Canvas.SIZE];
			int[] rowEnd = new int[Canvas.SIZE];
			int[] rowColor = new int[Canvas.SIZE];
			int rowRuns = 0;

			int pu = 0;

			while (pu < Canvas.SIZE) {
				int value = PaintColor.normalise(texels[pv * Canvas.SIZE + pu]);

				if (!PaintColor.isPainted(value)) {
					pu++;
					continue;
				}

				int end = pu + 1;

				while (end < Canvas.SIZE
					&& PaintColor.normalise(texels[pv * Canvas.SIZE + end]) == value) {
					end++;
				}

				rowStart[rowRuns] = pu;
				rowEnd[rowRuns] = end;
				rowColor[rowRuns] = value;
				rowRuns++;

				pu = end;
			}

			// Extend an open run only when this row's run matches it exactly. Partial overlaps
			// are left to become their own rectangles: chasing them would cost more comparisons
			// than the handful of extra quads is worth at 16x16.
			int[] nextStart = new int[Canvas.SIZE];
			int[] nextEnd = new int[Canvas.SIZE];
			int[] nextColor = new int[Canvas.SIZE];
			int[] nextTop = new int[Canvas.SIZE];
			int nextRuns = 0;

			boolean[] consumed = new boolean[Canvas.SIZE];

			for (int r = 0; r < rowRuns; r++) {
				int matched = -1;

				for (int o = 0; o < openRuns; o++) {
					if (!consumed[o]
						&& runStart[o] == rowStart[r]
						&& runEnd[o] == rowEnd[r]
						&& runColor[o] == rowColor[r]) {
						matched = o;
						break;
					}
				}

				nextStart[nextRuns] = rowStart[r];
				nextEnd[nextRuns] = rowEnd[r];
				nextColor[nextRuns] = rowColor[r];

				if (matched >= 0) {
					consumed[matched] = true;
					nextTop[nextRuns] = runTop[matched];
				} else {
					nextTop[nextRuns] = pv;
				}

				nextRuns++;
			}

			// Anything not extended by this row is finished.
			for (int o = 0; o < openRuns; o++) {
				if (!consumed[o]) {
					quads.add(new PaintQuad(face, runStart[o], runTop[o], runEnd[o], pv, runColor[o]));
				}
			}

			System.arraycopy(nextStart, 0, runStart, 0, nextRuns);
			System.arraycopy(nextEnd, 0, runEnd, 0, nextRuns);
			System.arraycopy(nextColor, 0, runColor, 0, nextRuns);
			System.arraycopy(nextTop, 0, runTop, 0, nextRuns);
			openRuns = nextRuns;
		}

		for (int o = 0; o < openRuns; o++) {
			quads.add(new PaintQuad(face, runStart[o], runTop[o], runEnd[o], Canvas.SIZE, runColor[o]));
		}

		return quads;
	}
}
