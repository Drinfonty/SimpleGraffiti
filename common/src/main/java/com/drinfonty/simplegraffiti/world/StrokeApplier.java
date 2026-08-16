package com.drinfonty.simplegraffiti.world;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.drinfonty.simplegraffiti.canvas.Brush;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.FaceStroke;

import net.minecraft.core.BlockPos;

/**
 * Applies one stroke across every block face it crosses.
 *
 * <p>Shared deliberately: the client runs it to predict and the server runs it to decide, over the
 * same block data with the same integer walk, so the two cannot draw different lines. Splitting this
 * into a client copy and a server copy is exactly how prediction drifts.
 *
 * <p>Canvases are replaced rather than mutated, as everywhere else, so a chunk-mesher thread reading
 * one never sees a half-drawn stroke.
 */
public final class StrokeApplier {
	/** How the caller reaches canvases and decides which faces may be painted at all. */
	public interface CanvasAccess {
		/** The current canvas for a face, or null when it has never been painted. */
		Canvas get(BlockPos pos, int face);

		/** Stores a replacement canvas, or removes it when {@code canvas} is null. */
		void put(BlockPos pos, int face, Canvas canvas);

		/**
		 * Whether this face may take paint. A stroke sweeping over an unpaintable block skips it and
		 * carries on, so dragging across a wall interrupted by a window paints the wall either side
		 * rather than stopping dead at the glass.
		 */
		boolean mayPaint(BlockPos pos, int face);
	}

	private StrokeApplier() {
	}

	/**
	 * @return true when at least one canvas changed
	 */
	public static boolean apply(int face, BlockPos from, int fromU8, int fromV8,
		BlockPos to, int u8, int v8, int size, int value, UUID painter, long now, CanvasAccess access) {

		int normal = FaceStroke.normal(face, to.getX(), to.getY(), to.getZ());
		Walker walker = new Walker(face, normal, size, value, painter, now, access);

		int toU = FaceStroke.encodeU(face, FaceStroke.blockU(face, to.getX(), to.getY(), to.getZ()), u8);
		int toV = FaceStroke.encodeV(face, FaceStroke.blockV(face, to.getX(), to.getY(), to.getZ()), v8);

		// Only points on one plane can be joined - a drag from a floor up onto a step is two
		// strokes, not a line through the air - and a segment longer than the bound is a bad or
		// hostile packet rather than a drag. Either way the op degrades to a single stamp, which
		// is the same walk with no distance to cover.
		boolean joinable = FaceStroke.normal(face, from.getX(), from.getY(), from.getZ()) == normal;
		int fromU = toU;
		int fromV = toV;

		if (joinable) {
			fromU = FaceStroke.encodeU(face, FaceStroke.blockU(face, from.getX(), from.getY(), from.getZ()), fromU8);
			fromV = FaceStroke.encodeV(face, FaceStroke.blockV(face, from.getX(), from.getY(), from.getZ()), fromV8);

			if (FaceStroke.tooFar(fromU, fromV, toU, toV)) {
				fromU = toU;
				fromV = toV;
			}
		}

		FaceStroke.walk(face, fromU, fromV, toU, toV, walker);
		walker.publish();
		return walker.changed;
	}

	/** A single stamp is just a stroke with no distance to cover, so it bleeds the same way. */
	public static boolean applyStamp(int face, BlockPos pos, int u8, int v8, int size, int value,
		UUID painter, long now, CanvasAccess access) {
		return apply(face, pos, u8, v8, pos, u8, v8, size, value, painter, now, access);
	}

	/**
	 * Accumulates stamps across every block a stroke touches and publishes each canvas once.
	 *
	 * <p>Each point of the walk is stamped into every block its disc <em>overlaps</em>, not just the
	 * block holding its centre, so a spray near a seam marks the neighbour too and a spray on a
	 * corner marks all the blocks meeting there. That means several canvases can be open at once,
	 * which is why working copies are held in a map and published together at the end rather than
	 * one at a time.
	 */
	private static final class Walker implements FaceStroke.Point {
		private final int face;
		private final int normal;
		private final int size;
		private final int radius;
		private final int value;
		private final UUID painter;
		private final long now;
		private final CanvasAccess access;
		private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		/** Working copies keyed by the block's face-plane coordinates. */
		private final Map<Long, int[]> working = new LinkedHashMap<>();

		/** Blocks that may not be painted, remembered so they are only asked about once. */
		private final Set<Long> refused = new HashSet<>();

		private final Set<Long> touched = new HashSet<>();

		private boolean changed;

		private Walker(int face, int normal, int size, int value, UUID painter, long now, CanvasAccess access) {
			this.face = face;
			this.normal = normal;
			this.size = size;
			this.radius = Brush.radius(size);
			this.value = value;
			this.painter = painter;
			this.now = now;
			this.access = access;
		}

		private static long key(int blockU, int blockV) {
			return ((long) blockU << 32) ^ (blockV & 0xFFFFFFFFL);
		}

		@Override
		public void accept(int blockU, int blockV, int u8, int v8) {
			// Re-derive the point in plane coordinates so the disc's reach can be measured across
			// block boundaries rather than within one canvas.
			int globalU = FaceStroke.encodeU(face, blockU, u8);
			int globalV = FaceStroke.encodeV(face, blockV, v8);

			int uA = FaceStroke.blockOfU(face, globalU - radius);
			int uB = FaceStroke.blockOfU(face, globalU + radius);
			int vA = FaceStroke.blockOfV(face, globalV - radius);
			int vB = FaceStroke.blockOfV(face, globalV + radius);

			for (int bu = Math.min(uA, uB); bu <= Math.max(uA, uB); bu++) {
				for (int bv = Math.min(vA, vB); bv <= Math.max(vA, vB); bv++) {
					stampInto(bu, bv, globalU - FaceStroke.encodeU(face, bu, 0),
						globalV - FaceStroke.encodeV(face, bv, 0));
				}
			}
		}

		private void stampInto(int blockU, int blockV, int localU, int localV) {
			long key = key(blockU, blockV);

			if (refused.contains(key)) {
				return;
			}

			int[] canvas = working.get(key);

			if (canvas == null) {
				moveCursor(blockU, blockV);

				if (!access.mayPaint(cursor, face)) {
					refused.add(key);
					return;
				}

				Canvas existing = access.get(cursor, face);
				canvas = existing == null ? new int[Canvas.TEXELS] : existing.texels().clone();
				working.put(key, canvas);
			}

			if (Brush.stampOffCanvas(canvas, localU, localV, size, value)) {
				touched.add(key);
			}
		}

		private void moveCursor(int blockU, int blockV) {
			cursor.set(
				FaceStroke.worldX(face, blockU, blockV, normal),
				FaceStroke.worldY(face, blockU, blockV, normal),
				FaceStroke.worldZ(face, blockU, blockV, normal));
		}

		private void publish() {
			for (Map.Entry<Long, int[]> entry : working.entrySet()) {
				long key = entry.getKey();

				// Blocks the disc reached but never actually marked are left untouched, so a
				// stroke passing near a face does not stamp an owner on it or dirty its chunk.
				if (!touched.contains(key)) {
					continue;
				}

				moveCursor((int) (key >> 32), (int) key);
				Canvas updated = Canvas.ofOwned(entry.getValue(), painter, now);
				access.put(cursor, face, updated.isEmpty() ? null : updated);
				changed = true;
			}

			working.clear();
			touched.clear();
		}
	}
}
