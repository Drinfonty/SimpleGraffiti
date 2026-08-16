package com.drinfonty.simplegraffiti;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.drinfonty.simplegraffiti.config.ServerConfig;
import com.drinfonty.simplegraffiti.item.GraffitiItems;
import com.drinfonty.simplegraffiti.item.SprayCanItem;
import com.drinfonty.simplegraffiti.net.GraffitiPayloads;
import com.drinfonty.simplegraffiti.net.PayloadSender;
import com.drinfonty.simplegraffiti.server.RateLimiter;
import com.drinfonty.simplegraffiti.world.CanvasStore;
import com.drinfonty.simplegraffiti.world.ChunkCanvases;
import com.drinfonty.simplegraffiti.world.PaintService;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The server-side lifecycle facade that both loaders call into.
 *
 * <p>Everything a loader knows how to do - "the server started", "a chunk was sent to this player",
 * "a payload arrived" - lands here, and nothing above this line is loader-specific. The loaders
 * supply exactly one thing this class cannot work out for itself: a {@link PayloadSender}, because
 * sending a packet is the one operation the two spell differently.
 */
public final class GraffitiServer {
	private static GraffitiServer instance;

	private final MinecraftServer server;
	private final PayloadSender sender;
	private final Map<ResourceKey<Level>, CanvasStore> stores = new HashMap<>();
	private final Map<UUID, Long> lastCorrection = new HashMap<>();
	private final Map<UUID, Long> lastCharge = new HashMap<>();
	private final RateLimiter rateLimiter;
	private final Path configFile;

	private ServerConfig config;

	private GraffitiServer(MinecraftServer server, PayloadSender sender, Path configDirectory) {
		this.server = server;
		this.sender = sender;
		this.configFile = configDirectory.resolve(SimpleGraffiti.MOD_ID).resolve("server.json");
		this.config = ServerConfig.load(configFile);
		this.rateLimiter = new RateLimiter(config.spraysPerSecond, config.burstSprays);
	}

	public static void start(MinecraftServer server, PayloadSender sender, Path configDirectory) {
		instance = new GraffitiServer(server, sender, configDirectory);
		SimpleGraffiti.LOGGER.info("{} ready ({} mode, {} sprays/s)",
			SimpleGraffiti.MOD_NAME, instance.config.permissionMode(), instance.config.spraysPerSecond);
	}

	public static void stop() {
		if (instance != null) {
			instance.closeStores();
			instance = null;
		}
	}

	/** Null on a client that is not hosting, which is the normal case on a multiplayer client. */
	public static GraffitiServer get() {
		return instance;
	}

	public ServerConfig config() {
		return config;
	}

	public PayloadSender sender() {
		return sender;
	}

	public RateLimiter rateLimiter() {
		return rateLimiter;
	}

	public MinecraftServer server() {
		return server;
	}

	public void reloadConfig() {
		config = ServerConfig.load(configFile);
		rateLimiter.configure(config.spraysPerSecond, config.burstSprays);
	}

	public void saveConfig() {
		ServerConfig.save(config, configFile);
	}

	/**
	 * The store for a level, created on first use.
	 *
	 * <p>Created lazily rather than on world load because most dimensions on most servers never get
	 * painted in, and an unused store is still an open region-file handle.
	 */
	public CanvasStore store(ServerLevel level) {
		return stores.computeIfAbsent(level.dimension(), key ->
			new CanvasStore(level, dimensionPath(level), false));
	}

	private Path dimensionPath(ServerLevel level) {
		// The world path is only reachable through MinecraftServer's protected storage source, so
		// the accessor mixin exists purely to ask "where is this dimension on disk".
		return com.drinfonty.simplegraffiti.mixin.MinecraftServerAccessor.class
			.cast(server)
			.simpleGraffiti$storageSource()
			.getDimensionPath(level.dimension());
	}

	private void closeStores() {
		for (CanvasStore store : stores.values()) {
			store.close();
		}

		stores.clear();
	}

	public void saveAll() {
		for (CanvasStore store : stores.values()) {
			store.saveAll();
		}
	}

	public void onLevelUnload(ServerLevel level) {
		CanvasStore store = stores.remove(level.dimension());

		if (store != null) {
			store.close();
		}
	}

	/**
	 * A chunk has become visible to a player. Loads its graffiti if needed and sends what is
	 * already known - but only to a player whose connection has our channel.
	 */
	public void onChunkSent(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
		if (!config.enabled) {
			return;
		}

		CanvasStore store = store(level);
		ChunkPos chunkPos = chunk.getPos();

		store.loadChunk(chunkPos, () -> {
			ChunkCanvases canvases = store.chunk(chunkPos);

			if (canvases != null && !canvases.isEmpty()) {
				sendChunkTo(player, chunkPos, canvases);
			}
		});

		ChunkCanvases canvases = store.chunk(chunkPos);

		if (canvases != null && !canvases.isEmpty()) {
			sendChunkTo(player, chunkPos, canvases);
		}
	}

	private void sendChunkTo(ServerPlayer player, ChunkPos chunkPos, ChunkCanvases canvases) {
		for (GraffitiPayloads.CanvasSyncS2C payload : PaintService.fullSync(chunkPos, canvases)) {
			if (!sender.sendIfPossible(player, payload)) {
				return;
			}
		}
	}

	public void onChunkDropped(ServerPlayer player, ChunkPos chunkPos) {
		if (config.enabled) {
			sender.sendIfPossible(player, GraffitiPayloads.ClearS2C.chunk(chunkPos.x(), chunkPos.z()));
		}
	}

	public void onChunkUnloaded(ServerLevel level, ChunkPos chunkPos) {
		CanvasStore store = stores.get(level.dimension());

		if (store != null) {
			store.unloadChunk(chunkPos);
		}
	}

	/** Sends the handshake, if this player can hear it at all (SPEC 7.1). */
	public void onPlayerJoin(ServerPlayer player) {
		sender.sendIfPossible(player, new GraffitiPayloads.HelloS2C(
			GraffitiPayloads.PROTOCOL_VERSION,
			config.enabled ? GraffitiPayloads.HelloS2C.FLAG_PAINTING_ENABLED : 0,
			config.maxBrushSize,
			config.maxCanvasesPerChunk));
	}

	public void onPlayerLeave(ServerPlayer player) {
		rateLimiter.forget(player.getUUID());
		lastCorrection.remove(player.getUUID());
		lastCharge.remove(player.getUUID());
	}

	/**
	 * Whether this player's tool should give up a charge now.
	 *
	 * <p>Charge drains on a <em>timer</em> rather than once per paint request, so how smoothly the
	 * client samples the crosshair is decoupled from how fast the can empties. Sampling every tick
	 * is what stops the line trailing behind the crosshair, and charging per request would have
	 * made that cost five times as much paint for the same stroke. It is also the fairer rule: a
	 * client sampling slowly cannot paint the same wall for fewer charges.
	 */
	public boolean tryConsumeCharge(UUID player, long nowMillis, long intervalMillis) {
		Long previous = lastCharge.get(player);

		if (previous != null && nowMillis - previous < intervalMillis) {
			return false;
		}

		lastCharge.put(player, nowMillis);
		return true;
	}

	public void onPaint(ServerPlayer player, GraffitiPayloads.PaintC2S request) {
		PaintService.handlePaint(this, player, request);
	}

	public void onSetColor(ServerPlayer player, GraffitiPayloads.SetColorC2S request) {
		if (!config.enabled) {
			return;
		}

		InteractionHand hand = request.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		ItemStack stack = player.getItemInHand(hand);

		// The only thing worth validating: that the player really holds a can in that hand. Any
		// 24-bit value is a legal colour, and setting one costs nothing.
		if (GraffitiItems.isSprayCan(stack)) {
			SprayCanItem.setColor(stack, request.rgb());
		}
	}

	/** Token check for corrections, kept here so the per-player state has one owner. */
	public boolean tryCorrect(UUID player, long nowMillis, long intervalMillis) {
		Long previous = lastCorrection.get(player);

		if (previous != null && nowMillis - previous < intervalMillis) {
			return false;
		}

		lastCorrection.put(player, nowMillis);
		return true;
	}

	public List<ServerLevel> levels() {
		List<ServerLevel> levels = new java.util.ArrayList<>();
		server.getAllLevels().forEach(levels::add);
		return levels;
	}
}
