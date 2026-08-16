package com.drinfonty.simplegraffiti.net;

import java.util.ArrayList;
import java.util.List;

import com.drinfonty.simplegraffiti.SimpleGraffiti;
import com.drinfonty.simplegraffiti.canvas.Brush;
import com.drinfonty.simplegraffiti.canvas.CanvasCodec;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The wire protocol (SPEC 7), version 1.
 *
 * <p>Every payload is a plain record with a hand-written codec. Hand-written because the bounds
 * matter: a decoder here is the mod's entire attack surface, so each one validates before it
 * allocates and throws only the {@link IllegalArgumentException} the loader glue catches and turns
 * into "drop the packet, log one line". Out-of-range values are rejected rather than clamped, so a
 * malformed packet can never be silently reinterpreted as a valid one.
 *
 * <p>No loader API appears in this file. Registration and dispatch differ per loader; the format
 * does not.
 */
public final class GraffitiPayloads {
	/** Bumped whenever the wire format changes; a mismatch is treated exactly as "no mod". */
	public static final int PROTOCOL_VERSION = 2;

	/** SPEC 7.4: a chunk with more canvases than this is sent as several payloads. */
	public static final int MAX_SYNC_ENTRIES = 512;

	public static final CustomPacketPayload.Type<HelloS2C> HELLO =
		new CustomPacketPayload.Type<>(SimpleGraffiti.id("hello"));
	public static final CustomPacketPayload.Type<PaintC2S> PAINT =
		new CustomPacketPayload.Type<>(SimpleGraffiti.id("paint"));
	public static final CustomPacketPayload.Type<StampS2C> STAMP =
		new CustomPacketPayload.Type<>(SimpleGraffiti.id("stamp"));
	public static final CustomPacketPayload.Type<CanvasSyncS2C> CANVAS_SYNC =
		new CustomPacketPayload.Type<>(SimpleGraffiti.id("canvas_sync"));
	public static final CustomPacketPayload.Type<ClearS2C> CLEAR =
		new CustomPacketPayload.Type<>(SimpleGraffiti.id("clear"));
	public static final CustomPacketPayload.Type<SetColorC2S> SET_COLOR =
		new CustomPacketPayload.Type<>(SimpleGraffiti.id("set_color"));

	/** Flag bits shared by {@link PaintC2S} and {@link StampS2C}. */
	public static final int FLAG_ERASE = 1;
	public static final int FLAG_OFFHAND = 1 << 1;
	public static final int FLAG_WHOLE_FACE = 1 << 2;

	/**
	 * The op continues a stroke: paint the segment from the previous point to this one, rather than
	 * a lone disc at this one. Without it a drag can only ever be a row of dots spaced by however
	 * fast the player moved.
	 */
	public static final int FLAG_STROKE = 1 << 3;

	private static final int FLAG_MASK = FLAG_ERASE | FLAG_OFFHAND | FLAG_WHOLE_FACE | FLAG_STROKE;

	/** Scopes for {@link ClearS2C}. */
	public static final int SCOPE_FACE = 0;
	public static final int SCOPE_BLOCK = 1;
	public static final int SCOPE_CHUNK = 2;

	private GraffitiPayloads() {
	}

	private static int readUnsignedByte(FriendlyByteBuf buffer) {
		return buffer.readByte() & 0xFF;
	}

	private static void checkFace(int face) {
		if (!FaceAxes.isValidFace(face)) {
			throw new IllegalArgumentException("face out of range: " + face);
		}
	}

	private static void checkFlags(int flags) {
		if ((flags & ~FLAG_MASK) != 0) {
			throw new IllegalArgumentException("unknown flag bits: " + flags);
		}

		// A whole-face clear is an erase; the combination "wipe the face with paint" has no
		// meaning and must not be reachable by setting one bit.
		if ((flags & FLAG_WHOLE_FACE) != 0 && (flags & FLAG_ERASE) == 0) {
			throw new IllegalArgumentException("whole-face flag without erase flag");
		}
	}

	/**
	 * SPEC 7.1. Sent once on join, only to players whose connection has the channel.
	 */
	public record HelloS2C(int protocolVersion, int flags, int maxBrushSize, int maxCanvasesPerChunk)
		implements CustomPacketPayload {

		public static final int FLAG_PAINTING_ENABLED = 1;

		public static final StreamCodec<FriendlyByteBuf, HelloS2C> CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeVarInt(payload.protocolVersion);
				buffer.writeByte(payload.flags);
				buffer.writeByte(payload.maxBrushSize);
				buffer.writeVarInt(payload.maxCanvasesPerChunk);
			},
			buffer -> new HelloS2C(
				buffer.readVarInt(),
				readUnsignedByte(buffer),
				readUnsignedByte(buffer),
				buffer.readVarInt()));

		public boolean paintingEnabled() {
			return (flags & FLAG_PAINTING_ENABLED) != 0;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return HELLO;
		}
	}

	/**
	 * SPEC 7.2, 13 bytes. The colour is deliberately absent: the server reads it from the can the
	 * player is holding, so a client cannot paint a colour it does not have.
	 */
	public record PaintC2S(long pos, int face, int u8, int v8, int brush, int flags, int fromU8, int fromV8)
		implements CustomPacketPayload {

		public PaintC2S {
			checkFace(face);
			checkFlags(flags);

			if (!Brush.isValidSize(brush)) {
				throw new IllegalArgumentException("brush out of range: " + brush);
			}

			if (u8 < 0 || u8 > 255 || v8 < 0 || v8 > 255
				|| fromU8 < 0 || fromU8 > 255 || fromV8 < 0 || fromV8 > 255) {
				throw new IllegalArgumentException("hit point out of range");
			}
		}

		/** A one-shot stamp; the stroke origin is ignored. */
		public static PaintC2S stamp(long pos, int face, int u8, int v8, int brush, int flags) {
			return new PaintC2S(pos, face, u8, v8, brush, flags, u8, v8);
		}

		public boolean stroke() {
			return (flags & FLAG_STROKE) != 0;
		}

		public static final StreamCodec<FriendlyByteBuf, PaintC2S> CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeLong(payload.pos);
				buffer.writeByte(payload.face);
				buffer.writeByte(payload.u8);
				buffer.writeByte(payload.v8);
				buffer.writeByte(payload.brush);
				buffer.writeByte(payload.flags);
				buffer.writeByte(payload.fromU8);
				buffer.writeByte(payload.fromV8);
			},
			buffer -> new PaintC2S(
				buffer.readLong(),
				readUnsignedByte(buffer),
				readUnsignedByte(buffer),
				readUnsignedByte(buffer),
				readUnsignedByte(buffer),
				readUnsignedByte(buffer),
				readUnsignedByte(buffer),
				readUnsignedByte(buffer)));

		public boolean erase() {
			return (flags & FLAG_ERASE) != 0;
		}

		public boolean offhand() {
			return (flags & FLAG_OFFHAND) != 0;
		}

		public boolean wholeFace() {
			return (flags & FLAG_WHOLE_FACE) != 0;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return PAINT;
		}
	}

	/**
	 * SPEC 7.3, 16 bytes: the paint fields plus the colour the server actually applied. Recipients
	 * replay {@code Brush.stamp} with exactly these arguments, which is why the operation has to be
	 * deterministic and idempotent.
	 */
	public record StampS2C(long pos, int face, int u8, int v8, int brush, int flags, int rgb,
		int fromU8, int fromV8) implements CustomPacketPayload {

		public StampS2C {
			checkFace(face);
			checkFlags(flags);

			if (!Brush.isValidSize(brush)) {
				throw new IllegalArgumentException("brush out of range: " + brush);
			}

			if (u8 < 0 || u8 > 255 || v8 < 0 || v8 > 255
				|| fromU8 < 0 || fromU8 > 255 || fromV8 < 0 || fromV8 > 255) {
				throw new IllegalArgumentException("hit point out of range");
			}
		}

		public boolean stroke() {
			return (flags & FLAG_STROKE) != 0;
		}

		public static final StreamCodec<FriendlyByteBuf, StampS2C> CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeLong(payload.pos);
				buffer.writeByte(payload.face);
				buffer.writeByte(payload.u8);
				buffer.writeByte(payload.v8);
				buffer.writeByte(payload.brush);
				buffer.writeByte(payload.flags);
				buffer.writeByte(payload.rgb >>> 16);
				buffer.writeByte(payload.rgb >>> 8);
				buffer.writeByte(payload.rgb);
				buffer.writeByte(payload.fromU8);
				buffer.writeByte(payload.fromV8);
			},
			buffer -> {
				long pos = buffer.readLong();
				int face = readUnsignedByte(buffer);
				int u8 = readUnsignedByte(buffer);
				int v8 = readUnsignedByte(buffer);
				int brush = readUnsignedByte(buffer);
				int flags = readUnsignedByte(buffer);
				int red = readUnsignedByte(buffer);
				int green = readUnsignedByte(buffer);
				int blue = readUnsignedByte(buffer);
				int fromU8 = readUnsignedByte(buffer);
				int fromV8 = readUnsignedByte(buffer);
				return new StampS2C(pos, face, u8, v8, brush, flags,
					(red << 16) | (green << 8) | blue, fromU8, fromV8);
			});

		public boolean erase() {
			return (flags & FLAG_ERASE) != 0;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return STAMP;
		}
	}

	/** One canvas inside a {@link CanvasSyncS2C}. */
	public record SyncEntry(int localX, int y, int localZ, int face, byte[] rle) {
		public SyncEntry {
			if ((localX & ~0xF) != 0 || (localZ & ~0xF) != 0) {
				throw new IllegalArgumentException("chunk-local coordinates out of range");
			}

			checkFace(face);

			if (rle == null || rle.length == 0 || rle.length > CanvasCodec.MAX_ENCODED_BYTES) {
				throw new IllegalArgumentException("canvas payload out of range");
			}
		}
	}

	/**
	 * SPEC 7.4. Also the correction channel: rejecting a paint the client may have predicted sends
	 * one of these for the single affected face, so no ghost paint can persist.
	 */
	public record CanvasSyncS2C(int chunkX, int chunkZ, boolean replace, List<SyncEntry> entries)
		implements CustomPacketPayload {

		public CanvasSyncS2C {
			if (entries.size() > MAX_SYNC_ENTRIES) {
				throw new IllegalArgumentException("too many entries: " + entries.size());
			}
		}

		public static final StreamCodec<FriendlyByteBuf, CanvasSyncS2C> CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeVarInt(payload.chunkX);
				buffer.writeVarInt(payload.chunkZ);
				buffer.writeBoolean(payload.replace);
				buffer.writeVarInt(payload.entries.size());

				for (SyncEntry entry : payload.entries) {
					buffer.writeByte((entry.localX() << 4) | entry.localZ());
					buffer.writeVarInt(entry.y());
					buffer.writeByte(entry.face());
					buffer.writeByteArray(entry.rle());
				}
			},
			buffer -> {
				int chunkX = buffer.readVarInt();
				int chunkZ = buffer.readVarInt();
				boolean replace = buffer.readBoolean();
				int count = buffer.readVarInt();

				// Bound the count before allocating the list, not after reading the entries:
				// otherwise a four-byte header could ask for an arbitrarily large allocation.
				if (count < 0 || count > MAX_SYNC_ENTRIES) {
					throw new IllegalArgumentException("entry count out of range: " + count);
				}

				List<SyncEntry> entries = new ArrayList<>(count);

				for (int i = 0; i < count; i++) {
					int xz = readUnsignedByte(buffer);
					int y = buffer.readVarInt();
					int face = readUnsignedByte(buffer);
					byte[] rle = buffer.readByteArray(CanvasCodec.MAX_ENCODED_BYTES);
					entries.add(new SyncEntry(xz >> 4, y, xz & 0xF, face, rle));
				}

				return new CanvasSyncS2C(chunkX, chunkZ, replace, entries);
			});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return CANVAS_SYNC;
		}
	}

	/** SPEC 7.5. One shape for all three scopes; the unused fields are simply not read. */
	public record ClearS2C(int scope, long pos, int face, int chunkX, int chunkZ)
		implements CustomPacketPayload {

		public static ClearS2C face(long pos, int face) {
			checkFace(face);
			return new ClearS2C(SCOPE_FACE, pos, face, 0, 0);
		}

		public static ClearS2C block(long pos) {
			return new ClearS2C(SCOPE_BLOCK, pos, 0, 0, 0);
		}

		public static ClearS2C chunk(int chunkX, int chunkZ) {
			return new ClearS2C(SCOPE_CHUNK, 0L, 0, chunkX, chunkZ);
		}

		public static final StreamCodec<FriendlyByteBuf, ClearS2C> CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeByte(payload.scope);

				switch (payload.scope) {
					case SCOPE_FACE -> {
						buffer.writeLong(payload.pos);
						buffer.writeByte(payload.face);
					}
					case SCOPE_BLOCK -> buffer.writeLong(payload.pos);
					default -> {
						buffer.writeVarInt(payload.chunkX);
						buffer.writeVarInt(payload.chunkZ);
					}
				}
			},
			buffer -> {
				int scope = readUnsignedByte(buffer);

				return switch (scope) {
					case SCOPE_FACE -> {
						long pos = buffer.readLong();
						int face = readUnsignedByte(buffer);
						checkFace(face);
						yield new ClearS2C(SCOPE_FACE, pos, face, 0, 0);
					}
					case SCOPE_BLOCK -> new ClearS2C(SCOPE_BLOCK, buffer.readLong(), 0, 0, 0);
					case SCOPE_CHUNK -> new ClearS2C(SCOPE_CHUNK, 0L, 0, buffer.readVarInt(), buffer.readVarInt());
					default -> throw new IllegalArgumentException("bad clear scope: " + scope);
				};
			});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return CLEAR;
		}
	}

	/**
	 * SPEC 7.6. Any 24-bit value is valid, so there is nothing to validate beyond the length; the
	 * server's only check is that the player really holds a can in the stated hand.
	 */
	public record SetColorC2S(int rgb, int hand) implements CustomPacketPayload {
		public SetColorC2S {
			if (hand != 0 && hand != 1) {
				throw new IllegalArgumentException("bad hand: " + hand);
			}
		}

		public static final StreamCodec<FriendlyByteBuf, SetColorC2S> CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeByte(payload.rgb >>> 16);
				buffer.writeByte(payload.rgb >>> 8);
				buffer.writeByte(payload.rgb);
				buffer.writeByte(payload.hand);
			},
			buffer -> {
				int red = readUnsignedByte(buffer);
				int green = readUnsignedByte(buffer);
				int blue = readUnsignedByte(buffer);
				return new SetColorC2S((red << 16) | (green << 8) | blue, readUnsignedByte(buffer));
			});

		public boolean offhand() {
			return hand == 1;
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return SET_COLOR;
		}
	}
}
