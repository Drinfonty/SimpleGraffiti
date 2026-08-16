package com.drinfonty.simplegraffiti.world;

import java.util.UUID;

import com.drinfonty.simplegraffiti.SimpleGraffiti;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.CanvasCodec;
import com.drinfonty.simplegraffiti.canvas.CanvasKey;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;

/**
 * Every canvas in one chunk (SPEC 8), with its NBT form.
 *
 * <p>Chunk-granular by design: this object is loaded when the chunk is and released when the chunk
 * is, so a long-lived server's resident cost is proportional to the paint in loaded chunks rather
 * than to all the paint that has ever existed - which is what a whole-dimension {@code SavedData}
 * would have cost.
 *
 * <p>Reading is deliberately total. A malformed entry is dropped on its own, keeping the rest of
 * the chunk, and a hopeless compound yields an empty chunk rather than an exception: graffiti must
 * never be able to stop a chunk, a world or a server from loading.
 */
public final class ChunkCanvases {
	public static final int NBT_VERSION = 1;

	private final Long2ObjectMap<Canvas> canvases = new Long2ObjectOpenHashMap<>();
	private boolean dirty;

	/**
	 * Set when the chunk's stored data could not be read. Such a chunk is not written back until
	 * something paints in it, so a transient read failure cannot silently erase a mural (SPEC 8).
	 */
	private boolean readFailed;

	public Canvas get(long key) {
		return canvases.get(key);
	}

	public void put(long key, Canvas canvas) {
		canvases.put(key, canvas);
		dirty = true;
	}

	public boolean remove(long key) {
		boolean removed = canvases.remove(key) != null;
		dirty |= removed;
		return removed;
	}

	/** Removes all six faces of one block, as when it is broken or replaced. */
	public int removeBlock(int localX, int y, int localZ) {
		int removed = 0;

		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			if (remove(CanvasKey.pack(localX, y, localZ, face))) {
				removed++;
			}
		}

		return removed;
	}

	public Long2ObjectMap<Canvas> canvases() {
		return canvases;
	}

	public int size() {
		return canvases.size();
	}

	public boolean isEmpty() {
		return canvases.isEmpty();
	}

	public boolean isDirty() {
		return dirty;
	}

	public void clearDirty() {
		dirty = false;
	}

	public boolean readFailed() {
		return readFailed;
	}

	/** True when this chunk holds nothing worth writing and nothing worth keeping in memory. */
	public boolean isDisposable() {
		return canvases.isEmpty() && !dirty;
	}

	public CompoundTag save() {
		CompoundTag root = new CompoundTag();
		root.putInt("Version", NBT_VERSION);

		ListTag list = new ListTag();

		for (Long2ObjectMap.Entry<Canvas> entry : canvases.long2ObjectEntrySet()) {
			long key = entry.getLongKey();
			Canvas canvas = entry.getValue();

			CompoundTag tag = new CompoundTag();
			tag.putByte("X", (byte) CanvasKey.localX(key));
			tag.putByte("Z", (byte) CanvasKey.localZ(key));
			tag.putInt("Y", CanvasKey.y(key));
			tag.putByte("F", (byte) CanvasKey.face(key));
			tag.putByteArray("D", CanvasCodec.toBytes(canvas.texels()));

			if (canvas.owner() != null) {
				tag.putIntArray("O", uuidToIntArray(canvas.owner()));
				tag.putLong("T", canvas.timestamp());
			}

			list.add(tag);
		}

		root.put("Canvases", list);
		return root;
	}

	public static ChunkCanvases load(CompoundTag root, ChunkPos chunkPos, int minY, int maxY) {
		ChunkCanvases result = new ChunkCanvases();

		if (root == null) {
			return result;
		}

		int version = root.getIntOr("Version", 0);

		if (version != NBT_VERSION) {
			SimpleGraffiti.LOGGER.warn("Ignoring graffiti for chunk {} written by format version {}",
				chunkPos, version);
			result.readFailed = true;
			return result;
		}

		int dropped = 0;

		for (CompoundTag tag : root.getListOrEmpty("Canvases").compoundStream().toList()) {
			int localX = tag.getByteOr("X", (byte) -1);
			int localZ = tag.getByteOr("Z", (byte) -1);
			int y = tag.getIntOr("Y", Integer.MIN_VALUE);
			int face = tag.getByteOr("F", (byte) -1);

			// Every one of these is a corrupt-file case, not a code case: a canvas outside the
			// chunk, outside the build range, or with the wrong data length is dropped on its
			// own and the rest of the chunk still loads.
			if ((localX & ~0xF) != 0 || (localZ & ~0xF) != 0
				|| !FaceAxes.isValidFace(face)
				|| y < minY || y > maxY) {
				dropped++;
				continue;
			}

			int[] texels = CanvasCodec.fromBytes(tag.getByteArray("D").orElse(null));

			if (texels == null) {
				dropped++;
				continue;
			}

			UUID owner = tag.getIntArray("O")
				.filter(array -> array.length == 4)
				.map(ChunkCanvases::uuidFromIntArray)
				.orElse(null);

			result.canvases.put(
				CanvasKey.pack(localX, y, localZ, face),
				Canvas.ofOwned(texels, owner, tag.getLongOr("T", 0L)));
		}

		if (dropped > 0) {
			SimpleGraffiti.LOGGER.warn("Dropped {} malformed graffiti entries in chunk {}", dropped, chunkPos);
			result.readFailed = true;
		}

		return result;
	}

	/** Vanilla's 4-int UUID encoding, so the field is readable by any NBT tool. */
	private static int[] uuidToIntArray(UUID uuid) {
		long most = uuid.getMostSignificantBits();
		long least = uuid.getLeastSignificantBits();
		return new int[] { (int) (most >> 32), (int) most, (int) (least >> 32), (int) least };
	}

	private static UUID uuidFromIntArray(int[] array) {
		return new UUID(
			((long) array[0] << 32) | (array[1] & 0xFFFFFFFFL),
			((long) array[2] << 32) | (array[3] & 0xFFFFFFFFL));
	}
}
