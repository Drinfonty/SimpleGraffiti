package com.drinfonty.simplegraffiti;

import java.nio.file.Path;

import com.drinfonty.simplegraffiti.canvas.Brush;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.CanvasCodec;
import com.drinfonty.simplegraffiti.canvas.CanvasKey;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;
import com.drinfonty.simplegraffiti.canvas.FaceStroke;
import com.drinfonty.simplegraffiti.canvas.PaintColor;
import com.drinfonty.simplegraffiti.client.ClientCanvasStore;
import com.drinfonty.simplegraffiti.client.ClientPayloadSender;
import com.drinfonty.simplegraffiti.client.ServerCapability;
import com.drinfonty.simplegraffiti.config.ClientConfig;
import com.drinfonty.simplegraffiti.item.GraffitiItems;
import com.drinfonty.simplegraffiti.item.SprayCanItem;
import com.drinfonty.simplegraffiti.net.GraffitiPayloads;
import com.drinfonty.simplegraffiti.world.StrokeApplier;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
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
	/**
	 * Holding use samples the crosshair every tick (SPEC 5.2).
	 *
	 * <p>Every tick rather than every fifth because a stroke is only ever drawn as far as the last
	 * sample: at 5-tick sampling the painted line trailed up to a quarter second of mouse movement
	 * behind the crosshair, which reads as lag even though the line itself is continuous. Charge
	 * drains on its own timer, so sampling this often costs no extra paint.
	 */
	private static final int SPRAY_INTERVAL_TICKS = 1;

	/** Sound and particles stay at their old cadence; twenty hisses a second is not a spray can. */
	private static final int FEEDBACK_INTERVAL_TICKS = 5;

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
	private int feedbackCooldown;
	private InteractionHand sprayHand = InteractionHand.MAIN_HAND;
	private boolean sprayErases;

	// Where the last sample landed, so the next one paints the segment between the two rather than
	// a lone disc. The anchor may be on a different *block* - a canvas is only 16 texels wide, so a
	// drag at any normal speed crosses blocks constantly, and a stroke that could not cross them
	// was just a row of blobs. It must stay on the same face and the same plane, though.
	private boolean strokeAnchored;
	private BlockPos strokePos;
	private int strokeFace;
	private int strokeU8;
	private int strokeV8;

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
		strokeAnchored = false;
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
		int value = stamp.erase() ? PaintColor.EMPTY : PaintColor.opaque(stamp.rgb());

		// Always the shared applier, stroke or not: an observer replaying a stamp must reproduce
		// exactly what the painter predicted, bleed onto neighbouring blocks included. Replaying
		// our own broadcast is a no-op, because the walk is deterministic and stamping the same
		// texels the same colour changes nothing.
		BlockPos from = stamp.stroke() ? BlockPos.of(stamp.fromPos()) : pos;
		int fromU8 = stamp.stroke() ? stamp.fromU8() : stamp.u8();
		int fromV8 = stamp.stroke() ? stamp.fromV8() : stamp.v8();

		StrokeApplier.apply(stamp.face(), from, fromU8, fromV8, pos, stamp.u8(), stamp.v8(),
			stamp.brush(), value, null, 0L, clientAccess());
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

		// Vanilla repeats useOn every four ticks for as long as use is held, so this fires
		// continuously during a drag - not once per press. Treating each call as a new press
		// reset the stroke anchor just before nearly every stamp, which is what made a held
		// drag come out as disconnected blobs however well the stroke maths worked. While a
		// spray is already running the tick loop owns both the cadence and the anchor.
		if (spraying && !wholeFace) {
			return;
		}

		spraying = !wholeFace;
		sprayHand = hand;
		sprayErases = erase;
		sprayCooldown = SPRAY_INTERVAL_TICKS;
		feedbackCooldown = 0;

		// A genuinely new press starts a fresh stroke; the previous one's end point must not be
		// joined to it, or clicking elsewhere would draw a line across the gap.
		strokeAnchored = false;

		paint(pos, face, hit, hand, erase, wholeFace);
	}

	@Override
	public void stopSpraying() {
		spraying = false;
		strokeAnchored = false;
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
			strokeAnchored = false;
			return;
		}

		if (--sprayCooldown > 0) {
			return;
		}

		sprayCooldown = SPRAY_INTERVAL_TICKS;

		if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			// Off a block entirely: the stroke restarts when the crosshair comes back, rather than
			// leaping across whatever the player swept over in between.
			strokeAnchored = false;
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

		// Continue the stroke across blocks, as long as the crosshair stayed on the same face of the
		// same plane - joining a floor to a higher step would draw a line through the air.
		boolean continues = strokeAnchored && !wholeFace && strokeFace == faceId
			&& FaceStroke.normal(faceId, strokePos.getX(), strokePos.getY(), strokePos.getZ())
				== FaceStroke.normal(faceId, pos.getX(), pos.getY(), pos.getZ());

		if (continues) {
			flags |= GraffitiPayloads.FLAG_STROKE;
		}

		BlockPos from = continues ? strokePos : pos;
		int fromU8 = continues ? strokeU8 : u8;
		int fromV8 = continues ? strokeV8 : v8;

		if (!sender.sendIfPossible(new GraffitiPayloads.PaintC2S(
			pos.asLong(), faceId, u8, v8, brush, flags, from.asLong(), fromU8, fromV8))) {
			return;
		}

		strokeAnchored = !wholeFace;
		strokePos = pos.immutable();
		strokeFace = faceId;
		strokeU8 = u8;
		strokeV8 = v8;

		// Counts the colour of every texel the erase actually clears, so the flecks match the paint
		// that came off and none appear when nothing did.
		Int2IntOpenHashMap removed = erase ? new Int2IntOpenHashMap() : null;

		predict(pos, faceId, from, fromU8, fromV8, u8, v8, brush, wholeFace,
			erase ? PaintColor.EMPTY : PaintColor.opaque(SprayCanItem.colorOf(tool)),
			removed == null ? null : previous -> {
				if (PaintColor.isPainted(previous)) {
					removed.addTo(PaintColor.rgb(previous), 1);
				}
			});

		if (--feedbackCooldown > 0) {
			return;
		}

		feedbackCooldown = FEEDBACK_INTERVAL_TICKS;

		if (erase) {
			// No sound here: the server plays block.sponge.absorb for everyone nearby, and only
			// when something actually changed. Particles are purely local decoration, and an
			// empty map means this stroke took no paint off - scrubbing bare stone should look
			// like nothing is happening, because nothing is.
			if (!removed.isEmpty()) {
				spawnEraseParticles(client, hit, face, dominantColor(removed));
			}

			return;
		}

		client.level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
			SoundEvents.GENERIC_EXTINGUISH_FIRE, net.minecraft.sounds.SoundSource.PLAYERS,
			0.3F, 1.6F, false);

		spawnPaintParticles(client, hit, face, SprayCanItem.colorOf(tool));
	}

	/**
	 * A puff of the paint being removed, drifting off the surface and falling.
	 *
	 * <p>Deliberately the colour that is being erased rather than a generic grey: it reads as the
	 * paint coming off, and it tells the player at a glance that the sponge actually bit.
	 */
	private void spawnEraseParticles(Minecraft client, Vec3 hit, Direction face, int rgb) {
		if (!config.showPaintParticles || client.level == null) {
			return;
		}

		DustParticleOptions dust = new DustParticleOptions(PaintColor.opaque(rgb), 0.6F);
		RandomSource random = client.level.getRandom();

		for (int i = 0; i < 3; i++) {
			// Scattered across the brush rather than a single point, and drifting outwards and
			// downwards, so it looks like flecks being scrubbed loose instead of a spray.
			client.level.addParticle(dust,
				hit.x + face.getStepX() * 0.03 + (random.nextDouble() - 0.5) * 0.2,
				hit.y + face.getStepY() * 0.03 + (random.nextDouble() - 0.5) * 0.2,
				hit.z + face.getStepZ() * 0.03 + (random.nextDouble() - 0.5) * 0.2,
				face.getStepX() * 0.02 + (random.nextDouble() - 0.5) * 0.02,
				face.getStepY() * 0.02 - 0.03,
				face.getStepZ() * 0.02 + (random.nextDouble() - 0.5) * 0.02);
		}
	}

	/**
	 * A puff of paint-coloured dust at the hit point, drifting off the wall (SPEC 5.2).
	 *
	 * <p>Purely local decoration: it is never sent, never replayed, and disabled by a client
	 * setting, because nothing about it is authoritative.
	 */
	private void spawnPaintParticles(Minecraft client, Vec3 hit, Direction face, int rgb) {
		if (!config.showPaintParticles || client.level == null) {
			return;
		}

		DustParticleOptions dust = new DustParticleOptions(PaintColor.opaque(rgb), 0.8F);
		double drift = 0.02;

		for (int i = 0; i < 2; i++) {
			client.level.addParticle(dust,
				hit.x + face.getStepX() * 0.02,
				hit.y + face.getStepY() * 0.02,
				hit.z + face.getStepZ() * 0.02,
				face.getStepX() * drift, face.getStepY() * drift, face.getStepZ() * drift);
		}
	}

	/** Applies the op locally so paint appears at the painter's latency, not the server's. */
	private void predict(BlockPos pos, int face, BlockPos from, int fromU8, int fromV8,
		int u8, int v8, int brush, boolean wholeFace, int value, StrokeApplier.ChangeSink changes) {
		if (wholeFace) {
			Canvas existing = canvases.get(pos, face);
			Canvas cleared = existing == null ? null : existing.cleared(null, 0L);

			if (cleared != null) {
				// A whole-face wipe bypasses the walker, so it reports its own removals.
				if (changes != null) {
					for (int texel : existing.texels()) {
						if (PaintColor.isPainted(texel)) {
							changes.changed(texel);
						}
					}
				}

				canvases.remove(pos, face);
				markDirty(pos);
			}

			return;
		}

		// The same code the server runs, over the same block data, so the two cannot draw
		// different lines - a separate client implementation is how prediction drifts.
		StrokeApplier.apply(face, from, fromU8, fromV8, pos, u8, v8, brush, value, null, 0L,
			clientAccess(), changes);
	}

	/**
	 * The colour to throw off as flecks: whichever was erased most.
	 *
	 * <p>A stroke can cross several colours, and picking the commonest is both the closest match to
	 * what the player saw disappear and stable frame to frame.
	 */
	private static int dominantColor(Int2IntOpenHashMap counts) {
		int best = PaintColor.DEFAULT_RGB;
		int bestCount = -1;

		for (Int2IntMap.Entry entry : counts.int2IntEntrySet()) {
			if (entry.getIntValue() > bestCount) {
				bestCount = entry.getIntValue();
				best = entry.getIntKey();
			}
		}

		return best;
	}

	/**
	 * The client's view of canvases for the shared stroke code.
	 *
	 * <p>Paintability is judged locally against the same rule the server uses. Both sides see the
	 * same blocks, so they reach the same answer; if they ever did not, the server's correction
	 * would replace the face anyway.
	 */
	private StrokeApplier.CanvasAccess clientAccess() {
		ClientLevel level = Minecraft.getInstance().level;

		return new StrokeApplier.CanvasAccess() {
			@Override
			public Canvas get(BlockPos pos, int face) {
				return canvases.get(pos, face);
			}

			@Override
			public void put(BlockPos pos, int face, Canvas canvas) {
				if (canvas == null) {
					canvases.remove(pos, face);
				} else {
					canvases.put(pos, face, canvas);
				}

				markDirty(pos);
			}

			@Override
			public boolean mayPaint(BlockPos pos, int face) {
				return level != null && com.drinfonty.simplegraffiti.world.Paintability.isPaintable(
					level, pos, Direction.from3DDataValue(face), false);
			}
		};
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
