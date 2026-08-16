package com.drinfonty.simplegraffiti.client;

import java.util.concurrent.ConcurrentHashMap;

import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.CanvasKey;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * What this client knows about graffiti - and, unusually, a data structure read from
 * <em>chunk-mesher worker threads</em> rather than only from the client thread.
 *
 * <p>That constraint shapes the whole design. A worker asking "is this block painted" must never
 * see a half-written map or a half-stamped canvas, and must never block. So:
 *
 * <ul>
 *   <li>chunks live in a {@link ConcurrentHashMap}, safe for concurrent lookup;
 *   <li>each chunk's canvases live in a {@link Long2ObjectMap} that is <strong>never mutated after
 *       publication</strong> - a change builds a new map and swaps the reference;
 *   <li>a {@link Canvas} is likewise replaced rather than stamped in place.
 * </ul>
 *
 * <p>A worker therefore sees either the old state or the new state, never a torn one. Reading one
 * rebuild stale is harmless: the {@code setSectionDirtyWithNeighbors} call that accompanies every
 * change schedules another rebuild.
 *
 * <p>The cost of copy-on-write is one map copy per stamp. That is deliberate. A stamp copies a map
 * of at most {@code maxCanvasesPerChunk} references a few times a second per painter, which is
 * nothing next to the section rebuild it triggers anyway - and it buys lock-free reads on the path
 * that runs for every block in every section.
 */
public final class ClientCanvasStore {
	private final ConcurrentHashMap<Long, Long2ObjectMap<Canvas>> chunks = new ConcurrentHashMap<>();

	public Canvas get(BlockPos pos, int face) {
		Long2ObjectMap<Canvas> canvases = chunks.get(ChunkPos.pack(pos));

		if (canvases == null) {
			return null;
		}

		return canvases.get(CanvasKey.pack(pos.getX() & 0xF, pos.getY(), pos.getZ() & 0xF, face));
	}

	/**
	 * Whether this block has any paint at all.
	 *
	 * <p>The hot path: the model wrapper calls this for every block in every section rebuild, so it
	 * must early-out in one map lookup with no allocation for the unpainted 99.9%.
	 */
	public boolean isPainted(BlockPos pos) {
		Long2ObjectMap<Canvas> canvases = chunks.get(ChunkPos.pack(pos));

		if (canvases == null || canvases.isEmpty()) {
			return false;
		}

		long base = CanvasKey.pack(pos.getX() & 0xF, pos.getY(), pos.getZ() & 0xF, 0);

		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			if (canvases.containsKey(CanvasKey.withFace(base, face))) {
				return true;
			}
		}

		return false;
	}

	public void put(BlockPos pos, int face, Canvas canvas) {
		long chunkKey = ChunkPos.pack(pos);
		long canvasKey = CanvasKey.pack(pos.getX() & 0xF, pos.getY(), pos.getZ() & 0xF, face);

		chunks.compute(chunkKey, (key, existing) -> {
			Long2ObjectOpenHashMap<Canvas> copy = existing == null
				? new Long2ObjectOpenHashMap<>()
				: new Long2ObjectOpenHashMap<>(existing);
			copy.put(canvasKey, canvas);
			return Long2ObjectMaps.unmodifiable(copy);
		});
	}

	public void remove(BlockPos pos, int face) {
		removeKeys(ChunkPos.pack(pos), CanvasKey.pack(pos.getX() & 0xF, pos.getY(), pos.getZ() & 0xF, face));
	}

	public void removeBlock(BlockPos pos) {
		long base = CanvasKey.pack(pos.getX() & 0xF, pos.getY(), pos.getZ() & 0xF, 0);
		long[] keys = new long[FaceAxes.FACE_COUNT];

		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			keys[face] = CanvasKey.withFace(base, face);
		}

		removeKeys(ChunkPos.pack(pos), keys);
	}

	private void removeKeys(long chunkKey, long... canvasKeys) {
		chunks.computeIfPresent(chunkKey, (key, existing) -> {
			Long2ObjectOpenHashMap<Canvas> copy = new Long2ObjectOpenHashMap<>(existing);
			boolean changed = false;

			for (long canvasKey : canvasKeys) {
				changed |= copy.remove(canvasKey) != null;
			}

			if (!changed) {
				return existing;
			}

			return copy.isEmpty() ? null : Long2ObjectMaps.unmodifiable(copy);
		});
	}

	/**
	 * Replaces or merges a whole chunk in one swap, which is what a {@code canvas_sync} does. Doing
	 * it as one publication rather than per canvas is why a 1 024-canvas chunk costs one map build
	 * instead of a thousand copies.
	 */
	public void applyChunk(int chunkX, int chunkZ, boolean replace, Long2ObjectMap<Canvas> incoming) {
		long chunkKey = ChunkPos.pack(chunkX, chunkZ);

		chunks.compute(chunkKey, (key, existing) -> {
			Long2ObjectOpenHashMap<Canvas> merged = replace || existing == null
				? new Long2ObjectOpenHashMap<>()
				: new Long2ObjectOpenHashMap<>(existing);
			merged.putAll(incoming);
			return merged.isEmpty() ? null : Long2ObjectMaps.unmodifiable(merged);
		});
	}

	public void removeChunk(int chunkX, int chunkZ) {
		chunks.remove(ChunkPos.pack(chunkX, chunkZ));
	}

	/** Called on disconnect and world unload; the capability gate resets with it. */
	public void clear() {
		chunks.clear();
	}

	public boolean isEmpty() {
		return chunks.isEmpty();
	}

	public int canvasCount() {
		int total = 0;

		for (Long2ObjectMap<Canvas> canvases : chunks.values()) {
			total += canvases.size();
		}

		return total;
	}
}
