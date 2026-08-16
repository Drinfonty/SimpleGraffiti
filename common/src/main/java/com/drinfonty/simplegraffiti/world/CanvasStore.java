package com.drinfonty.simplegraffiti.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.drinfonty.simplegraffiti.SimpleGraffiti;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.CanvasKey;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/**
 * All graffiti in one dimension, held per loaded chunk and backed by its own region files at
 * {@code <world>/<dimension>/simple_graffiti/r.<x>.<z>.mca} (SPEC 8).
 *
 * <p>Using {@link SimpleRegionStorage} - the same class vanilla uses for {@code region/},
 * {@code entities/} and {@code poi/} - buys chunk-granular async reads, zlib compression and the
 * region-file crash resilience, with no bespoke file format to get wrong.
 *
 * <p>All access is from the server thread. Reads are dispatched to the IO worker and their results
 * applied back on the server thread, so a slow disk delays graffiti appearing, never the tick.
 */
public final class CanvasStore implements AutoCloseable {
	private final ServerLevel level;
	private final SimpleRegionStorage storage;
	private final Long2ObjectMap<ChunkCanvases> loaded = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectMap<ChunkCanvases> loading = new Long2ObjectOpenHashMap<>();

	public CanvasStore(ServerLevel level, Path dimensionPath, boolean sync) {
		this.level = level;
		this.storage = new SimpleRegionStorage(
			new RegionStorageInfo(level.getServer().getWorldData().getLevelName(),
				level.dimension(),
				SimpleGraffiti.MOD_ID),
			dimensionPath.resolve(SimpleGraffiti.MOD_ID),
			// Graffiti has never been through a data fix - it carries its own Version in the
			// root tag - but the storage still wants a fixer, so it gets the server's.
			level.getServer().getFixerUpper(),
			sync,
			DataFixTypes.CHUNK);
	}

	public ServerLevel level() {
		return level;
	}

	/**
	 * Begins loading a chunk's graffiti. The chunk is tracked immediately with an empty set so a
	 * paint that arrives before the read completes has somewhere to land; the read then merges into
	 * whatever is there rather than replacing it.
	 */
	public void loadChunk(ChunkPos chunkPos, Runnable onLoaded) {
		long key = chunkPos.pack();

		if (loaded.containsKey(key) || loading.containsKey(key)) {
			return;
		}

		ChunkCanvases placeholder = new ChunkCanvases();
		loading.put(key, placeholder);

		storage.read(chunkPos).thenAccept(optional -> level.getServer().execute(() -> {
			ChunkCanvases pending = loading.remove(key);

			if (pending == null) {
				// The chunk was unloaded while the read was in flight; nothing to publish.
				return;
			}

			ChunkCanvases result = ChunkCanvases.load(optional.orElse(null), chunkPos,
				level.getMinY(), level.getMaxY());

			// Anything painted while the read was in flight wins: it is newer.
			for (Long2ObjectMap.Entry<Canvas> entry : pending.canvases().long2ObjectEntrySet()) {
				result.put(entry.getLongKey(), entry.getValue());
			}

			loaded.put(key, result);

			if (!result.isEmpty() && onLoaded != null) {
				onLoaded.run();
			}
		})).exceptionally(throwable -> {
			SimpleGraffiti.LOGGER.error("Failed to read graffiti for chunk {}: {}", chunkPos, throwable.toString());
			level.getServer().execute(() -> {
				ChunkCanvases pending = loading.remove(key);

				if (pending != null) {
					loaded.put(key, pending);
				}
			});
			return null;
		});
	}

	public void unloadChunk(ChunkPos chunkPos) {
		long key = chunkPos.pack();
		ChunkCanvases canvases = loaded.remove(key);
		loading.remove(key);

		if (canvases != null) {
			write(chunkPos, canvases);
		}
	}

	/** The canvases for a loaded chunk, or null when the chunk is not tracked. */
	public ChunkCanvases chunk(ChunkPos chunkPos) {
		long key = chunkPos.pack();
		ChunkCanvases canvases = loaded.get(key);
		return canvases != null ? canvases : loading.get(key);
	}

	/**
	 * The canvases for a chunk, created on demand. Used on the paint path, where the chunk is
	 * known to be loaded because the player tracks it.
	 */
	public ChunkCanvases chunkForWrite(ChunkPos chunkPos) {
		long key = chunkPos.pack();
		ChunkCanvases canvases = loaded.get(key);

		if (canvases != null) {
			return canvases;
		}

		canvases = loading.get(key);

		if (canvases != null) {
			return canvases;
		}

		canvases = new ChunkCanvases();
		loaded.put(key, canvases);
		return canvases;
	}

	public Canvas get(BlockPos pos, int face) {
		ChunkCanvases canvases = chunk(ChunkPos.containing(pos));
		return canvases == null ? null : canvases.get(key(pos, face));
	}

	public static long key(BlockPos pos, int face) {
		return CanvasKey.pack(pos.getX() & 0xF, pos.getY(), pos.getZ() & 0xF, face);
	}

	public int loadedChunkCount() {
		return loaded.size();
	}

	public int loadedCanvasCount() {
		int total = 0;

		for (ChunkCanvases canvases : loaded.values()) {
			total += canvases.size();
		}

		return total;
	}

	/** Every loaded chunk that has at least one canvas, for {@code /graffiti} and for saving. */
	public List<ChunkEntry> loadedChunks() {
		List<ChunkEntry> entries = new ArrayList<>(loaded.size());

		for (Long2ObjectMap.Entry<ChunkCanvases> entry : loaded.long2ObjectEntrySet()) {
			entries.add(new ChunkEntry(ChunkPos.unpack(entry.getLongKey()), entry.getValue()));
		}

		return entries;
	}

	public void saveAll() {
		for (Long2ObjectMap.Entry<ChunkCanvases> entry : loaded.long2ObjectEntrySet()) {
			ChunkCanvases canvases = entry.getValue();

			if (canvases.isDirty()) {
				write(ChunkPos.unpack(entry.getLongKey()), canvases);
			}
		}
	}

	private void write(ChunkPos chunkPos, ChunkCanvases canvases) {
		if (!canvases.isDirty()) {
			return;
		}

		// A chunk whose stored data failed to read is not overwritten until something paints in
		// it, so a transient read failure cannot quietly destroy a mural (SPEC 8).
		if (canvases.readFailed() && canvases.isEmpty()) {
			return;
		}

		canvases.clearDirty();

		// The cast picks write(ChunkPos, CompoundTag) over the Supplier overload; a null tag is
		// how vanilla deletes a region entry, which is what an emptied chunk should do.
		storage.write(chunkPos, canvases.isEmpty() ? (CompoundTag) null : canvases.save());
	}

	@Override
	public void close() {
		saveAll();

		try {
			storage.close();
		} catch (IOException e) {
			SimpleGraffiti.LOGGER.error("Failed to close graffiti storage for {}: {}",
				level.dimension().identifier(), e.toString());
		}
	}

	public record ChunkEntry(ChunkPos pos, ChunkCanvases canvases) {
	}

	/** Present so callers do not have to import {@link CompoundTag} to talk about the format. */
	public static int nbtVersion() {
		return ChunkCanvases.NBT_VERSION;
	}
}
