package com.drinfonty.simplegraffiti.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.drinfonty.simplegraffiti.GraffitiServer;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.CanvasKey;
import com.drinfonty.simplegraffiti.net.GraffitiPayloads;
import com.drinfonty.simplegraffiti.world.CanvasStore;
import com.drinfonty.simplegraffiti.world.ChunkCanvases;
import com.drinfonty.simplegraffiti.world.PaintService;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.ChunkPos;

/**
 * {@code /graffiti} (SPEC 9.4), written against Brigadier only so both loaders register the same
 * tree.
 *
 * <p>Painting is a build action, so it has to be governable like one: an operator must be able to
 * clean up after a griefer without editing region files by hand. That is also why every canvas
 * records who painted it - {@code clear player} would be impossible after the fact otherwise.
 */
public final class GraffitiCommands {
	private static final int MIN_RADIUS = 1;
	private static final int MAX_RADIUS = 128;

	private GraffitiCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("graffiti")
			// 26.2 replaced numeric op levels with named permissions; COMMANDS_GAMEMASTER is the
			// old level 2, which is what the spec asks for.
			.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));

		root.then(Commands.literal("clear")
			.then(Commands.literal("radius")
				.then(Commands.argument("blocks", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
					.executes(context -> clearRadius(context.getSource(),
						IntegerArgumentType.getInteger(context, "blocks")))))
			.then(Commands.literal("chunk")
				.executes(context -> clearChunk(context.getSource())))
			.then(Commands.literal("player")
				.then(Commands.argument("target", GameProfileArgument.gameProfile())
					.executes(context -> clearPlayer(context.getSource(),
						GameProfileArgument.getGameProfiles(context, "target").iterator().next().id())))));

		root.then(Commands.literal("stats").executes(context -> stats(context.getSource())));
		root.then(Commands.literal("reload").executes(context -> reload(context.getSource())));
		root.then(Commands.literal("enable").executes(context -> setEnabled(context.getSource(), true)));
		root.then(Commands.literal("disable").executes(context -> setEnabled(context.getSource(), false)));

		dispatcher.register(root);
	}

	private static GraffitiServer server(CommandSourceStack source) {
		return GraffitiServer.get();
	}

	private static int clearRadius(CommandSourceStack source, int radius) {
		GraffitiServer server = server(source);

		if (server == null) {
			return 0;
		}

		ServerLevel level = source.getLevel();
		BlockPos centre = BlockPos.containing(source.getPosition());
		CanvasStore store = server.store(level);
		int radiusSquared = radius * radius;

		int removed = removeMatching(server, level, store, (chunkPos, key) -> {
			int x = chunkPos.getMinBlockX() + CanvasKey.localX(key);
			int z = chunkPos.getMinBlockZ() + CanvasKey.localZ(key);
			int y = CanvasKey.y(key);
			long dx = x - centre.getX();
			long dy = y - centre.getY();
			long dz = z - centre.getZ();
			return dx * dx + dy * dy + dz * dz <= radiusSquared;
		});

		final int total = removed;
		source.sendSuccess(() -> Component.translatable("command.simple_graffiti.cleared", total), true);
		return total;
	}

	private static int clearChunk(CommandSourceStack source) {
		GraffitiServer server = server(source);

		if (server == null) {
			return 0;
		}

		ServerLevel level = source.getLevel();
		ChunkPos target = ChunkPos.containing(BlockPos.containing(source.getPosition()));
		CanvasStore store = server.store(level);
		ChunkCanvases canvases = store.chunk(target);
		int removed = canvases == null ? 0 : canvases.size();

		if (canvases != null && removed > 0) {
			List<Long> keys = new ArrayList<>(canvases.canvases().keySet());
			keys.forEach(canvases::remove);
			PaintService.broadcast(server, level, target, GraffitiPayloads.ClearS2C.chunk(target.x(), target.z()));
		}

		final int total = removed;
		source.sendSuccess(() -> Component.translatable("command.simple_graffiti.cleared", total), true);
		return total;
	}

	private static int clearPlayer(CommandSourceStack source, UUID target) {
		GraffitiServer server = server(source);

		if (server == null) {
			return 0;
		}

		int removed = 0;

		for (ServerLevel level : server.levels()) {
			CanvasStore store = server.store(level);
			removed += removeMatching(server, level, store, (chunkPos, key) -> {
				Canvas canvas = store.chunk(chunkPos).get(key);
				return canvas != null && target.equals(canvas.owner());
			});
		}

		final int total = removed;
		// Stated in the output rather than only in the docs: an operator who clears a griefer's
		// paint and then finds more of it tomorrow should know why, not file a bug.
		source.sendSuccess(() -> Component.translatable("command.simple_graffiti.cleared_player", total), true);
		return total;
	}

	private interface CanvasFilter {
		boolean test(ChunkPos chunkPos, long key);
	}

	private static int removeMatching(GraffitiServer server, ServerLevel level, CanvasStore store,
		CanvasFilter filter) {
		int removed = 0;

		for (CanvasStore.ChunkEntry entry : store.loadedChunks()) {
			ChunkPos chunkPos = entry.pos();
			ChunkCanvases canvases = entry.canvases();
			List<Long> doomed = new ArrayList<>();

			for (long key : canvases.canvases().keySet()) {
				if (filter.test(chunkPos, key)) {
					doomed.add(key);
				}
			}

			if (doomed.isEmpty()) {
				continue;
			}

			for (long key : doomed) {
				canvases.remove(key);
				PaintService.broadcast(server, level, chunkPos, GraffitiPayloads.ClearS2C.face(
					BlockPos.asLong(
						chunkPos.getMinBlockX() + CanvasKey.localX(key),
						CanvasKey.y(key),
						chunkPos.getMinBlockZ() + CanvasKey.localZ(key)),
					CanvasKey.face(key)));
			}

			removed += doomed.size();
		}

		return removed;
	}

	private static int stats(CommandSourceStack source) {
		GraffitiServer server = server(source);

		if (server == null) {
			return 0;
		}

		int canvases = 0;
		int chunks = 0;

		for (ServerLevel level : server.levels()) {
			CanvasStore store = server.store(level);
			canvases += store.loadedCanvasCount();

			for (CanvasStore.ChunkEntry entry : store.loadedChunks()) {
				if (!entry.canvases().isEmpty()) {
					chunks++;
				}
			}
		}

		final int totalCanvases = canvases;
		final int totalChunks = chunks;
		// Bytes are reported uncompressed and in memory, which is the number that matters for
		// heap; on disk RLE and region compression make it far smaller.
		final long bytes = (long) canvases * Canvas.BYTES;

		source.sendSuccess(() -> Component.translatable("command.simple_graffiti.stats",
			totalCanvases, totalChunks, bytes / 1024L), false);
		return totalCanvases;
	}

	private static int reload(CommandSourceStack source) {
		GraffitiServer server = server(source);

		if (server == null) {
			return 0;
		}

		server.reloadConfig();
		source.sendSuccess(() -> Component.translatable("command.simple_graffiti.reloaded"), true);
		return 1;
	}

	private static int setEnabled(CommandSourceStack source, boolean enabled) {
		GraffitiServer server = server(source);

		if (server == null) {
			return 0;
		}

		server.config().enabled = enabled;
		server.saveConfig();

		// Existing canvases are left untouched: disabling is a moderation switch, not a delete.
		source.sendSuccess(() -> Component.translatable(enabled
			? "command.simple_graffiti.enabled"
			: "command.simple_graffiti.disabled"), true);

		for (ServerPlayer player : server.server().getPlayerList().getPlayers()) {
			server.onPlayerJoin(player);
		}

		return 1;
	}
}
