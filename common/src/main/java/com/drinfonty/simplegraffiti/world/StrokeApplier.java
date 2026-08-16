package com.drinfonty.simplegraffiti.world;

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

		// Only points on one plane can be joined. A drag from a floor up onto a step is two strokes,
		// not a line through the air.
		if (FaceStroke.normal(face, from.getX(), from.getY(), from.getZ()) != normal) {
			return applySingle(face, to, u8, v8, size, value, painter, now, access);
		}

		int fromU = FaceStroke.encodeU(face, FaceStroke.blockU(face, from.getX(), from.getY(), from.getZ()), fromU8);
		int fromV = FaceStroke.encodeV(face, FaceStroke.blockV(face, from.getX(), from.getY(), from.getZ()), fromV8);
		int toU = FaceStroke.encodeU(face, FaceStroke.blockU(face, to.getX(), to.getY(), to.getZ()), u8);
		int toV = FaceStroke.encodeV(face, FaceStroke.blockV(face, to.getX(), to.getY(), to.getZ()), v8);

		// A stroke longer than the bound is not a drag, it is a bad or hostile packet.
		if (FaceStroke.tooFar(fromU, fromV, toU, toV)) {
			return applySingle(face, to, u8, v8, size, value, painter, now, access);
		}

		Walker walker = new Walker(face, normal, size, value, painter, now, access);
		FaceStroke.walk(face, fromU, fromV, toU, toV, walker);
		walker.publish();
		return walker.changed;
	}

	private static boolean applySingle(int face, BlockPos pos, int u8, int v8, int size, int value,
		UUID painter, long now, CanvasAccess access) {
		if (!access.mayPaint(pos, face)) {
			return false;
		}

		Canvas existing = access.get(pos, face);
		Canvas updated = (existing == null ? Canvas.empty() : existing)
			.withStamp(u8, v8, size, value, painter, now);

		if (updated == null) {
			return false;
		}

		access.put(pos, face, updated.isEmpty() ? null : updated);
		return true;
	}

	/**
	 * Accumulates stamps per block and publishes each canvas once.
	 *
	 * <p>Points arrive grouped by block, so one working copy is held at a time and swapped in when
	 * the walk moves on. Publishing per point instead would copy a 1 KB canvas for every one of up
	 * to 2 048 steps.
	 */
	private static final class Walker implements FaceStroke.Point {
		private final int face;
		private final int normal;
		private final int size;
		private final int value;
		private final UUID painter;
		private final long now;
		private final CanvasAccess access;
		private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		private boolean hasBlock;
		private int blockU;
		private int blockV;
		private int[] working;
		private boolean workingChanged;
		private boolean workingAllowed;

		private boolean changed;

		private Walker(int face, int normal, int size, int value, UUID painter, long now, CanvasAccess access) {
			this.face = face;
			this.normal = normal;
			this.size = size;
			this.value = value;
			this.painter = painter;
			this.now = now;
			this.access = access;
		}

		@Override
		public void accept(int nextBlockU, int nextBlockV, int u8, int v8) {
			if (!hasBlock || nextBlockU != blockU || nextBlockV != blockV) {
				publish();
				blockU = nextBlockU;
				blockV = nextBlockV;
				hasBlock = true;
				begin();
			}

			if (workingAllowed && Brush.stamp(working, u8, v8, size, value)) {
				workingChanged = true;
			}
		}

		private void moveCursor() {
			cursor.set(
				FaceStroke.worldX(face, blockU, blockV, normal),
				FaceStroke.worldY(face, blockU, blockV, normal),
				FaceStroke.worldZ(face, blockU, blockV, normal));
		}

		private void begin() {
			moveCursor();
			workingChanged = false;
			workingAllowed = access.mayPaint(cursor, face);

			if (!workingAllowed) {
				working = null;
				return;
			}

			Canvas existing = access.get(cursor, face);
			working = existing == null ? new int[Canvas.TEXELS] : existing.texels().clone();
		}

		private void publish() {
			if (!hasBlock || !workingChanged) {
				return;
			}

			moveCursor();
			Canvas updated = Canvas.ofOwned(working, painter, now);
			access.put(cursor, face, updated.isEmpty() ? null : updated);
			changed = true;

			// The array has been handed over, so it must not be stamped into again.
			working = null;
			workingChanged = false;
		}
	}
}
