package com.drinfonty.simplegraffiti;

import java.nio.file.Path;

import com.drinfonty.simplegraffiti.canvas.Brush;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.CanvasCodec;
import com.drinfonty.simplegraffiti.canvas.CanvasKey;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;
import com.drinfonty.simplegraffiti.canvas.PaintColor;
import com.drinfonty.simplegraffiti.client.ClientCanvasStore;
import com.drinfonty.simplegraffiti.client.ClientPayloadSender;
import com.drinfonty.simplegraffiti.client.ServerCapability;
import com.drinfonty.simplegraffiti.config.ClientConfig;
import com.drinfonty.simplegraffiti.item.GraffitiItems;
import com.drinfonty.simplegraffiti.item.SprayCanItem;
import com.drinfonty.simplegraffiti.net.GraffitiPayloads;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The client-side lifecycle facade both loaders call into.
 *
 * <p>It owns three things: the capability gate that decides whether this mod does anything at all
 * on the current server, the local view of the world's graffiti, and the hold-to-spray timer.
 *
 * <p>Prediction lives here too. The client applies a stamp locally before sending it, and replays
 * the server's echo of that same stamp as a no-op, so a painter sees paint at their own latency
 * rather than the server's. The correctness argument for that is entirely in {@code Brush}: the
 * operation is deterministic and idempotent, so replaying it cannot drift.
 */
public final class GraffitiClient implements ClientHooks.PaintTrigger {
	/** SPEC 5.2: holding use paints every 5 ticks - 4 sprays a second. */
	private static final int SPRAY_INTERVAL_TICKS = 5;

	private static GraffitiClient instance;

	private final ClientPayloadSender sender;
	private final ClientCanvasStore canvases = new ClientCanvasStore();
	private final Path configFile;

	private ClientConfig config;
	private ServerCapability capability = ServerCapability.NONE;
	private int serverMaxBrushSize = Brush.MAX_SIZE;
	private boolean paintingEnabled = true;

	private boolean spraying;
	private int sprayCooldown;
	private InteractionHand sprayHand = InteractionHand.MAIN_HAND;
	private boolean sprayErases;

	/** SPEC 10: the "this server does not have the mod" message is shown once per session. */
	private boolean warnedNoServer;

	private GraffitiClient(ClientPayloadSender sender, Path configDirectory) {
		this.sender = sender;
		this.configFile = configDirectory.resolve(SimpleGraffiti.MOD_ID).resolve("client.json");
		this.config = ClientConfig.load(configFile);
	}

	public static void start(ClientPayloadSender sender, Path configDirectory) {
		instance = new GraffitiClient(sender, configDirectory);
		ClientHooks.install(instance);
	}

	public static GraffitiClient get() {
		return instance;
	}

	public ClientCanvasStore canvases() {
		return canvases;
	}

	public ClientConfig config() {
		return config;
	}

	public void saveConfig() {
		ClientConfig.save(config, configFile);
	}

	public ServerCapability capability() {
		return capability;
	}

	/** The single question the renderer, the item and the picker all ask before doing anything. */
	public boolean isReady() {
		return capability == ServerCapability.READY;
	}

	public boolean canPaint() {
		return isReady() && paintingEnabled;
	}

	public boolean shouldRender() {
		return isReady() && config.renderGraffiti;
	}

	public int brushSize() {
		return Math.min(config.brushSize, serverMaxBrushSize);
	}

	public void setBrushSize(int size) {
		if (Brush.isValidSize(size)) {
			config.brushSize = size;
			saveConfig();
		}
	}

	// ------------------------------------------------------------------ lifecycle

	public void onDisconnect() {
		capability = ServerCapability.NONE;
		paintingEnabled = true;
		warnedNoServer = false;
		spraying = false;
		canvases.clear();
	}

	public void onHello(GraffitiPayloads.HelloS2C hello) {
		if (hello.protocolVersion() != GraffitiPayloads.PROTOCOL_VERSION) {
			// A version mismatch is treated exactly as "the server does not have the mod": one
			// log line, no rendering, no packets. Anything else risks two sides disagreeing about
			// a wire format, which is worse than doing nothing.
			SimpleGraffiti.LOGGER.info(
				"Server speaks graffiti protocol {}, this client speaks {}; graffiti disabled for this session",
				hello.protocolVersion(), GraffitiPayloads.PROTOCOL_VERSION);
			capability = ServerCapability.NONE;
			return;
		}

		capability = ServerCapability.READY;
		paintingEnabled = hello.paintingEnabled();
		serverMaxBrushSize = Math.clamp(hello.maxBrushSize(), Brush.MIN_SIZE, Brush.MAX_SIZE);
	}

	// ------------------------------------------------------------------ incoming

	public void onStamp(GraffitiPayloads.StampS2C stamp) {
		if (!isReady()) {
			return;
		}

		BlockPos pos = BlockPos.of(stamp.pos());
		Canvas existing = canvases.get(pos, stamp.face());
		Canvas before = existing == null ? Canvas.empty() : existing;
		int value = stamp.erase() ? PaintColor.EMPTY : PaintColor.opaque(stamp.rgb());

		Canvas updated = before.withStamp(stamp.u8(), stamp.v8(), stamp.brush(), value, null, 0L);

		// Null means nothing changed, which is the normal case for the painter replaying their own
		// prediction. Idempotence is what makes that a no-op rather than a special case.
		if (updated == null) {
			return;
		}

		if (updated.isEmpty()) {
			canvases.remove(pos, stamp.face());
		} else {
			canvases.put(pos, stamp.face(), updated);
		}

		markDirty(pos);
	}

	public void onCanvasSync(GraffitiPayloads.CanvasSyncS2C sync) {
		if (!isReady()) {
			return;
		}

		Long2ObjectMap<Canvas> incoming = new Long2ObjectOpenHashMap<>(sync.entries().size());

		for (GraffitiPayloads.SyncEntry entry : sync.entries()) {
			int[] texels = CanvasCodec.decode(entry.rle());

			if (texels == null) {
				// A malformed canvas is dropped on its own; the rest of the chunk still arrives.
				SimpleGraffiti.LOGGER.warn("Dropping malformed canvas in chunk {},{}", sync.chunkX(), sync.chunkZ());
				continue;
			}

			incoming.put(
				CanvasKey.pack(entry.localX(), entry.y(), entry.localZ(), entry.face()),
				Canvas.ofOwned(texels, null, 0L));
		}

		canvases.applyChunk(sync.chunkX(), sync.chunkZ(), sync.replace(), incoming);
		markChunkDirty(sync.chunkX(), sync.chunkZ());
	}

	public void onClear(GraffitiPayloads.ClearS2C clear) {
		if (!isReady()) {
			return;
		}

		switch (clear.scope()) {
			case GraffitiPayloads.SCOPE_FACE -> {
				BlockPos pos = BlockPos.of(clear.pos());
				canvases.remove(pos, clear.face());
				markDirty(pos);
			}
			case GraffitiPayloads.SCOPE_BLOCK -> {
				BlockPos pos = BlockPos.of(clear.pos());
				canvases.removeBlock(pos);
				markDirty(pos);
			}
			default -> {
				canvases.removeChunk(clear.chunkX(), clear.chunkZ());
				markChunkDirty(clear.chunkX(), clear.chunkZ());
			}
		}
	}

	// ------------------------------------------------------------------ outgoing

	@Override
	public void onUseOnFace(BlockPos pos, Direction face, Vec3 hit, InteractionHand hand,
		boolean erase, boolean wholeFace) {
		if (!canPaint()) {
			warnUnavailable();
			return;
		}

		spraying = !wholeFace;
		sprayHand = hand;
		sprayErases = erase;
		sprayCooldown = SPRAY_INTERVAL_TICKS;

		paint(pos, face, hit, hand, erase, wholeFace);
	}

	@Override
	public void stopSpraying() {
		spraying = false;
	}

	/**
	 * Called every client tick. Repeats the spray while use is held and the crosshair is still on
	 * a paintable face, which is what turns a drag into a line.
	 */
	public void tick() {
		if (!spraying) {
			return;
		}

		Minecraft client = Minecraft.getInstance();

		if (client.player == null || client.level == null || !canPaint()) {
			spraying = false;
			return;
		}

		if (!client.options.keyUse.isDown()) {
			spraying = false;
			return;
		}

		if (--sprayCooldown > 0) {
			return;
		}

		sprayCooldown = SPRAY_INTERVAL_TICKS;

		if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}

		paint(hit.getBlockPos(), hit.getDirection(), hit.getLocation(), sprayHand, sprayErases, false);
	}

	private void paint(BlockPos pos, Direction face, Vec3 hit, InteractionHand hand,
		boolean erase, boolean wholeFace) {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null || client.level == null) {
			return;
		}

		ItemStack tool = client.player.getItemInHand(hand);

		if (erase) {
			if (!GraffitiItems.isScrubSponge(tool)) {
				return;
			}
		} else if (!GraffitiItems.isSprayCan(tool)) {
			return;
		} else if (!client.player.isCreative() && !SprayCanItem.hasCharge(tool)) {
			actionBar(Component.translatable("message.simple_graffiti.empty"));
			spraying = false;
			return;
		}

		int faceId = face.get3DDataValue();

		// The client runs the same paintability test as the server, so a hopeless paint costs no
		// packet at all rather than a packet and a correction.
		if (!com.drinfonty.simplegraffiti.world.Paintability.isPaintable(client.level, pos, face, false)) {
			return;
		}

		Vec3 local = hit.subtract(pos.getX(), pos.getY(), pos.getZ());
		int u8 = FaceAxes.quantise(FaceAxes.u(faceId, local.x, local.y, local.z));
		int v8 = FaceAxes.quantise(FaceAxes.v(faceId, local.x, local.y, local.z));
		int brush = brushSize();

		int flags = 0;

		if (erase) {
			flags |= GraffitiPayloads.FLAG_ERASE;
		}

		if (hand == InteractionHand.OFF_HAND) {
			flags |= GraffitiPayloads.FLAG_OFFHAND;
		}

		if (wholeFace) {
			flags |= GraffitiPayloads.FLAG_WHOLE_FACE;
		}

		if (!sender.sendIfPossible(new GraffitiPayloads.PaintC2S(pos.asLong(), faceId, u8, v8, brush, flags))) {
			return;
		}

		predict(pos, faceId, u8, v8, brush, erase, wholeFace,
			erase ? PaintColor.EMPTY : PaintColor.opaque(SprayCanItem.colorOf(tool)));

		if (!erase) {
			client.level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				SoundEvents.GENERIC_EXTINGUISH_FIRE, net.minecraft.sounds.SoundSource.PLAYERS,
				0.3F, 1.6F, false);
		}
	}

	/** Applies the stamp locally so paint appears at the painter's latency, not the server's. */
	private void predict(BlockPos pos, int face, int u8, int v8, int brush,
		boolean erase, boolean wholeFace, int value) {
		Canvas existing = canvases.get(pos, face);
		Canvas before = existing == null ? Canvas.empty() : existing;
		Canvas updated = wholeFace
			? before.cleared(null, 0L)
			: before.withStamp(u8, v8, brush, value, null, 0L);

		if (updated == null) {
			return;
		}

		if (updated.isEmpty()) {
			canvases.remove(pos, face);
		} else {
			canvases.put(pos, face, updated);
		}

		markDirty(pos);
	}

	private void warnUnavailable() {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null) {
			return;
		}

		if (!isReady()) {
			if (!warnedNoServer) {
				warnedNoServer = true;
				actionBar(Component.translatable("message.simple_graffiti.no_server"));
			}

			return;
		}

		actionBar(Component.translatable("message.simple_graffiti.disabled"));
	}

	// ------------------------------------------------------------------ remeshing

	/**
	 * A canvas change dirties the containing section and its neighbours - exactly what vanilla does
	 * for a block placement, and the whole reason rendering costs nothing per frame.
	 */
	public void markDirty(BlockPos pos) {
		ClientLevel level = Minecraft.getInstance().level;

		if (level != null) {
			level.setSectionDirtyWithNeighbors(
				SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getY()),
				SectionPos.blockToSectionCoord(pos.getZ()));
		}
	}

	private void markChunkDirty(int chunkX, int chunkZ) {
		ClientLevel level = Minecraft.getInstance().level;

		if (level == null) {
			return;
		}

		int minSection = SectionPos.blockToSectionCoord(level.getMinY());
		int maxSection = SectionPos.blockToSectionCoord(level.getMaxY());

		for (int section = minSection; section <= maxSection; section++) {
			level.setSectionDirtyWithNeighbors(chunkX, section, chunkZ);
		}
	}

	/**
	 * Applied when the client config's render toggle changes (SPEC 9.3): every section the player
	 * can see is rebuilt, so paint appears or vanishes without a reconnect.
	 *
	 * <p>Done by dirtying sections rather than through the level renderer's own full invalidation,
	 * which in 26.2 wants a camera and a block-colour resolver this call site has no business
	 * assembling.
	 */
	public void refreshAllSections() {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;

		if (level == null || client.player == null) {
			return;
		}

		int radius = client.options.getEffectiveRenderDistance();
		int centreX = SectionPos.blockToSectionCoord(client.player.getBlockX());
		int centreZ = SectionPos.blockToSectionCoord(client.player.getBlockZ());

		for (int x = centreX - radius; x <= centreX + radius; x++) {
			for (int z = centreZ - radius; z <= centreZ + radius; z++) {
				markChunkDirty(x, z);
			}
		}
	}

	private static void actionBar(Component message) {
		Minecraft client = Minecraft.getInstance();

		if (client.player != null) {
			client.gui.hud.setOverlayMessage(message, false);
		}
	}

	public ClientPayloadSender sender() {
		return sender;
	}
}
