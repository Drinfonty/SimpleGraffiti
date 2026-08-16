package com.drinfonty.simplegraffiti.mixin;

import com.drinfonty.simplegraffiti.GraffitiServer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one injection in the mod: "this chunk is now / no longer on this client".
 *
 * <p>It exists because neither loader exposes that seam in a form both share - Fabric API has no
 * {@code CHUNK_SENT_TO_PLAYER} event and NeoForge's {@code ChunkWatchEvent.Sent} is NeoForge-only -
 * so one mixin here serves both rather than two adapters that can drift apart.
 *
 * <p>Both hooks are tail injections into methods whose whole job is the thing being observed, so
 * there are no captured locals to re-derive and nothing to cancel. Failure here is also benign: if
 * the injection ever stops applying, graffiti simply does not sync on chunk load, which the mod
 * logs rather than crashes on.
 */
@Mixin(PlayerChunkSender.class)
public class PlayerChunkSenderMixin {
	@Inject(method = "sendChunk", at = @At("TAIL"))
	private static void simpleGraffiti$onChunkSent(ServerGamePacketListenerImpl listener,
		ServerLevel level, LevelChunk chunk, CallbackInfo callback) {
		GraffitiServer server = GraffitiServer.get();

		if (server != null) {
			server.onChunkSent(listener.getPlayer(), level, chunk);
		}
	}

	@Inject(method = "dropChunk", at = @At("TAIL"))
	private void simpleGraffiti$onChunkDropped(ServerPlayer player, ChunkPos chunkPos, CallbackInfo callback) {
		GraffitiServer server = GraffitiServer.get();

		if (server != null) {
			server.onChunkDropped(player, chunkPos);
		}
	}
}
