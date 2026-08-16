package com.drinfonty.simplegraffiti.world;

import java.util.ArrayList;
import java.util.List;

import com.drinfonty.simplegraffiti.GraffitiServer;
import com.drinfonty.simplegraffiti.SimpleGraffiti;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.CanvasCodec;
import com.drinfonty.simplegraffiti.canvas.CanvasKey;
import com.drinfonty.simplegraffiti.canvas.PaintColor;
import com.drinfonty.simplegraffiti.config.ServerConfig;
import com.drinfonty.simplegraffiti.item.GraffitiItems;
import com.drinfonty.simplegraffiti.item.SprayCanItem;
import com.drinfonty.simplegraffiti.net.GraffitiPayloads;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

/**
 * The authority (SPEC 6). Every paint is a <em>request</em>; this class is the only thing in the
 * mod that writes an authoritative canvas.
 *
 * <p>The validation order is normative and deliberate: the cheap, bounded checks come first, so a
 * client spamming paint packets is refused by a map lookup and a comparison long before anything
 * allocates a canvas or touches the world. The rate limiter in particular sits ahead of every
 * lookup, which is what makes packet spam cost the server almost nothing.
 *
 * <p>A rejected paint is repaired rather than refused: the client cannot be left holding a ghost,
 * so it is sent the authoritative canvas for that one face. Saying "no" would leave the two sides
 * disagreeing about what is on the wall, which is worse than a few dozen extra bytes.
 */
public final class PaintService {
	/** SPEC 6.1: two painters on one canvas inside this window get a full face, not a stamp. */
	private static final int CONTENTION_WINDOW_TICKS = 20;

	/** SPEC 6.2: corrections are capped so a rejection storm cannot become an outbound flood. */
	private static final long CORRECTION_INTERVAL_MILLIS = 250L;

	private PaintService() {
	}

	/**
	 * Handles one {@code paint} request.
	 *
	 * <p>Returns quietly on every failure. A legitimate client cannot trip most of these, and a
	 * hostile one must not learn anything from the difference between them.
	 */
	public static void handlePaint(GraffitiServer server, ServerPlayer player, GraffitiPayloads.PaintC2S request) {
		ServerConfig config = server.config();

		if (!config.enabled) {
			return;
		}

		if (request.erase() && !config.allowErase) {
			return;
		}

		// The rate limiter comes before any lookup or allocation, so spam costs one map hit.
		if (!server.rateLimiter().tryConsume(player.getUUID(), System.currentTimeMillis())) {
			return;
		}

		ServerLevel level = player.level();
		BlockPos pos = BlockPos.of(request.pos());
		ChunkPos chunkPos = ChunkPos.containing(pos);

		// A player may only touch chunks they are being sent, which bounds the addressable world
		// to what they can already see and makes "paint the other side of the map" impossible.
		if (!player.getChunkTrackingView().contains(chunkPos)) {
			return;
		}

		// Reach, plus one block of slack for the difference between where the client thought it
		// was standing and where the server thinks it was.
		if (!player.isWithinBlockInteractionRange(pos, 1.0)) {
			return;
		}

		Direction face = Direction.from3DDataValue(request.face());

		if (!Paintability.isPaintable(level, pos, face, config.restrictToTag)) {
			correct(server, player, level, pos, request.face());
			return;
		}

		if (!mayModify(server, player, pos)) {
			correct(server, player, level, pos, request.face());
			return;
		}

		InteractionHand hand = request.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		ItemStack tool = player.getItemInHand(hand);
		boolean creative = player.isCreative();

		if (request.erase()) {
			if (!GraffitiItems.isScrubSponge(tool) || (!creative && !isUsable(tool))) {
				return;
			}
		} else if (!GraffitiItems.isSprayCan(tool) || (!creative && !SprayCanItem.hasCharge(tool))) {
			return;
		}

		CanvasStore store = server.store(level);

		if (store == null) {
			return;
		}

		ChunkCanvases chunk = store.chunkForWrite(chunkPos);
		long key = CanvasStore.key(pos, request.face());
		Canvas existing = chunk.get(key);

		// The cap refuses new faces while existing ones stay paintable, so hitting it degrades
		// into "you cannot start a new tag here", not "your mural is frozen".
		if (existing == null && chunk.size() >= config.maxCanvasesPerChunk) {
			return;
		}

		int brush = Math.min(request.brush(), config.maxBrushSize);
		int value = request.erase() ? PaintColor.EMPTY : PaintColor.opaque(SprayCanItem.colorOf(tool));
		long now = System.currentTimeMillis();

		boolean changed;

		if (request.wholeFace()) {
			Canvas cleared = (existing == null ? Canvas.empty() : existing).cleared(player.getUUID(), now);
			changed = cleared != null;

			if (changed) {
				chunk.remove(key);
			}
		} else {
			// A held drag paints the whole segment since the last sample, across every block face it
			// crosses. Painting only the sample points would give one lone disc per block, which at
			// any normal drag speed is a row of blobs rather than a line.
			ServerCanvasAccess access = new ServerCanvasAccess(server, level, player, config);
			BlockPos from = request.stroke() ? BlockPos.of(request.fromPos()) : pos;
			int fromU8 = request.stroke() ? request.fromU8() : request.u8();
			int fromV8 = request.stroke() ? request.fromV8() : request.v8();

			changed = StrokeApplier.apply(request.face(), from, fromU8, fromV8, pos,
				request.u8(), request.v8(), brush, value, player.getUUID(), now, access);
		}

		// An op that changes nothing costs no charge and is not broadcast: spraying an already
		// solid wall must not generate traffic (SPEC 4.3).
		if (!changed) {
			return;
		}

		boolean contended = existing != null
			&& existing.owner() != null
			&& !existing.owner().equals(player.getUUID())
			&& now - existing.timestamp() < CONTENTION_WINDOW_TICKS * 50L;

		Canvas updated = chunk.get(key);

		spendTool(player, tool, hand, creative, request.erase());

		if (request.erase()) {
			level.playSound(null, pos, SoundEvents.SPONGE_ABSORB, SoundSource.PLAYERS, 0.4F, 1.0F);
		}

		if (contended && updated != null) {
			// Prediction reorders stamps between two painters, so overlapping texels could differ
			// forever. Sending the whole face to everyone tracking it is the only thing that
			// actually converges (SPEC 6.1) - prediction is a latency trick, not a consistency
			// model, and this is where it has to yield.
			broadcastFace(server, level, chunkPos, pos, request.face(), updated);
		} else {
			broadcast(server, level, chunkPos, new GraffitiPayloads.StampS2C(
				request.pos(), request.face(), request.u8(), request.v8(), brush, request.flags(),
				request.erase() ? 0 : PaintColor.rgb(value),
				request.fromPos(), request.fromU8(), request.fromV8()));
		}
	}

	/**
	 * The server's view of which faces a stroke may touch and where their canvases live.
	 *
	 * <p>Every block the stroke crosses is checked in its own right - paintability, permission and
	 * the per-chunk cap - so sweeping over a chest or someone else's claim skips those blocks
	 * instead of painting them, and a stroke can cross a chunk boundary safely.
	 */
	private record ServerCanvasAccess(GraffitiServer server, ServerLevel level, ServerPlayer player,
		ServerConfig config) implements StrokeApplier.CanvasAccess {

		@Override
		public Canvas get(BlockPos pos, int face) {
			CanvasStore store = server.store(level);
			return store == null ? null : store.get(pos, face);
		}

		@Override
		public void put(BlockPos pos, int face, Canvas canvas) {
			CanvasStore store = server.store(level);

			if (store == null) {
				return;
			}

			ChunkCanvases chunk = store.chunkForWrite(ChunkPos.containing(pos));
			long key = CanvasStore.key(pos, face);

			if (canvas == null) {
				chunk.remove(key);
			} else {
				chunk.put(key, canvas);
			}
		}

		@Override
		public boolean mayPaint(BlockPos pos, int face) {
			if (!player.getChunkTrackingView().contains(ChunkPos.containing(pos))) {
				return false;
			}

			if (!Paintability.isPaintable(level, pos, Direction.from3DDataValue(face), config.restrictToTag)) {
				return false;
			}

			if (!mayModify(server, player, pos)) {
				return false;
			}

			CanvasStore store = server.store(level);

			if (store == null) {
				return false;
			}

			ChunkCanvases chunk = store.chunkForWrite(ChunkPos.containing(pos));

			// The cap refuses new faces while existing ones stay paintable.
			return chunk.get(CanvasStore.key(pos, face)) != null
				|| chunk.size() < config.maxCanvasesPerChunk;
		}
	}

	private static boolean isUsable(ItemStack stack) {
		return stack.getDamageValue() < stack.getMaxDamage();
	}

	private static void spendTool(ServerPlayer player, ItemStack tool, InteractionHand hand,
		boolean creative, boolean erase) {
		if (creative) {
			return;
		}

		if (erase) {
			// The sponge is an ordinary tool and does break at zero, unlike the can - it has no
			// refill recipe, and a sponge that lingered at zero uses would just be litter.
			tool.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND
				? net.minecraft.world.entity.EquipmentSlot.MAINHAND
				: net.minecraft.world.entity.EquipmentSlot.OFFHAND);
		} else {
			SprayCanItem.consumeCharge(tool, false);
		}
	}

	private static boolean mayModify(GraffitiServer server, ServerPlayer player, BlockPos pos) {
		return switch (server.config().permissionMode()) {
			case ANYONE -> true;
			// 26.2 replaced numeric op levels with named permissions; COMMANDS_GAMEMASTER is
			// what used to be level 2, which is the level SPEC 9.4 asks for.
			case OPS_ONLY -> player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
			// Delegating to the server's own "may this player build here" check is what makes
			// land-protection mods govern painting for free, with no integration API either side.
			case BUILD_PERMISSION -> player.mayBuild() && player.level().mayInteract(player, pos);
		};
	}

	/** Sends one face's authoritative content to the player who was refused (SPEC 6.2). */
	private static void correct(GraffitiServer server, ServerPlayer player, ServerLevel level,
		BlockPos pos, int face) {
		if (!server.tryCorrect(player.getUUID(), System.currentTimeMillis(), CORRECTION_INTERVAL_MILLIS)) {
			return;
		}

		CanvasStore store = server.store(level);
		Canvas canvas = store == null ? null : store.get(pos, face);
		ChunkPos chunkPos = ChunkPos.containing(pos);

		if (canvas == null) {
			server.sender().sendIfPossible(player, GraffitiPayloads.ClearS2C.face(pos.asLong(), face));
			return;
		}

		server.sender().sendIfPossible(player, syncFor(chunkPos, pos, face, canvas));
	}

	private static void broadcastFace(GraffitiServer server, ServerLevel level, ChunkPos chunkPos,
		BlockPos pos, int face, Canvas canvas) {
		GraffitiPayloads.CanvasSyncS2C payload = syncFor(chunkPos, pos, face, canvas);
		broadcast(server, level, chunkPos, payload);
	}

	private static GraffitiPayloads.CanvasSyncS2C syncFor(ChunkPos chunkPos, BlockPos pos, int face, Canvas canvas) {
		List<GraffitiPayloads.SyncEntry> entries = new ArrayList<>(1);
		entries.add(new GraffitiPayloads.SyncEntry(
			pos.getX() & 0xF, pos.getY(), pos.getZ() & 0xF, face,
			CanvasCodec.encode(canvas.texels())));

		return new GraffitiPayloads.CanvasSyncS2C(chunkPos.x(), chunkPos.z(), false, entries);
	}

	public static void broadcast(GraffitiServer server, ServerLevel level, ChunkPos chunkPos,
		net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
		for (ServerPlayer observer : level.getChunkSource().chunkMap.getPlayers(chunkPos, false)) {
			server.sender().sendIfPossible(observer, payload);
		}
	}

	/** Every canvas in a chunk, split into payloads of at most {@code MAX_SYNC_ENTRIES}. */
	public static List<GraffitiPayloads.CanvasSyncS2C> fullSync(ChunkPos chunkPos, ChunkCanvases canvases) {
		List<GraffitiPayloads.CanvasSyncS2C> payloads = new ArrayList<>();
		List<GraffitiPayloads.SyncEntry> batch = new ArrayList<>(GraffitiPayloads.MAX_SYNC_ENTRIES);
		boolean first = true;

		for (var entry : canvases.canvases().long2ObjectEntrySet()) {
			long key = entry.getLongKey();
			batch.add(new GraffitiPayloads.SyncEntry(
				CanvasKey.localX(key), CanvasKey.y(key), CanvasKey.localZ(key), CanvasKey.face(key),
				CanvasCodec.encode(entry.getValue().texels())));

			if (batch.size() == GraffitiPayloads.MAX_SYNC_ENTRIES) {
				payloads.add(new GraffitiPayloads.CanvasSyncS2C(chunkPos.x(), chunkPos.z(), first, List.copyOf(batch)));
				batch.clear();
				first = false;
			}
		}

		// Only the first payload replaces; the rest add to it, so a chunk over the batch size does
		// not repeatedly wipe what the previous payload just delivered.
		if (!batch.isEmpty() || first) {
			payloads.add(new GraffitiPayloads.CanvasSyncS2C(chunkPos.x(), chunkPos.z(), first, List.copyOf(batch)));
		}

		return payloads;
	}

	/** Clears every canvas on a block, as when it is broken or replaced (SPEC 5.4). */
	public static void clearBlock(GraffitiServer server, ServerLevel level, BlockPos pos) {
		CanvasStore store = server.store(level);

		if (store == null) {
			return;
		}

		ChunkPos chunkPos = ChunkPos.containing(pos);
		ChunkCanvases chunk = store.chunk(chunkPos);

		if (chunk == null || chunk.isEmpty()) {
			return;
		}

		if (chunk.removeBlock(pos.getX() & 0xF, pos.getY(), pos.getZ() & 0xF) > 0) {
			broadcast(server, level, chunkPos, GraffitiPayloads.ClearS2C.block(pos.asLong()));

			if (SimpleGraffiti.DEBUG) {
				SimpleGraffiti.LOGGER.info("Cleared graffiti at {} (block changed)", pos);
			}
		}
	}
}
