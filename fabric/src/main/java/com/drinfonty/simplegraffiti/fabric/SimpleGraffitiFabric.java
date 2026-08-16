package com.drinfonty.simplegraffiti.fabric;

import com.drinfonty.simplegraffiti.GraffitiServer;
import com.drinfonty.simplegraffiti.SimpleGraffiti;
import com.drinfonty.simplegraffiti.item.GraffitiItems;
import com.drinfonty.simplegraffiti.net.GraffitiPayloads;
import com.drinfonty.simplegraffiti.net.PayloadSender;
import com.drinfonty.simplegraffiti.server.GraffitiCommands;
import com.drinfonty.simplegraffiti.world.PaintService;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Fabric registration glue, and nothing else.
 *
 * <p>Every handler here forwards straight into {@code GraffitiServer}; the only thing this module
 * genuinely contributes is a {@link PayloadSender}, because sending a packet is one of the two
 * operations the loaders spell differently.
 */
public class SimpleGraffitiFabric implements ModInitializer {
	/** Fabric's send helpers are static, so the sender itself is stateless. */
	public static final PayloadSender SENDER = new PayloadSender() {
		@Override
		public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
			return ServerPlayNetworking.canSend(player, type);
		}

		@Override
		public void send(ServerPlayer player, CustomPacketPayload payload) {
			ServerPlayNetworking.send(player, payload);
		}
	};

	@Override
	public void onInitialize() {
		// Fabric's registry is open at init, so the factory is invoked immediately.
		GraffitiItems.register((id, factory) ->
			net.minecraft.core.Registry.register(BuiltInRegistries.ITEM,
				ResourceKey.create(Registries.ITEM, id), factory.get()));

		registerPayloads();
		registerReceivers();

		// 26.2 keeps the vanilla tab keys private, so the key is rebuilt from its id rather
		// than referenced. Tools and utilities is where a spray can belongs.
		CreativeModeTabEvents.modifyOutputEvent(
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities")))
			.register(output -> {
				output.accept(new ItemStack(GraffitiItems.sprayCan));
				output.accept(new ItemStack(GraffitiItems.scrubSponge));
			});

		ServerLifecycleEvents.SERVER_STARTED.register(server ->
			GraffitiServer.start(server, SENDER, server.getServerDirectory().resolve("config")));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> GraffitiServer.stop());

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			GraffitiServer graffiti = GraffitiServer.get();

			if (graffiti != null) {
				graffiti.onPlayerJoin(handler.getPlayer());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			GraffitiServer graffiti = GraffitiServer.get();

			if (graffiti != null) {
				graffiti.onPlayerLeave(handler.getPlayer());
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			GraffitiCommands.register(dispatcher));

		// Graffiti follows the chunk it decorates: written and released when the chunk unloads,
		// so a long-lived server's resident cost tracks loaded chunks rather than total paint.
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			GraffitiServer graffiti = GraffitiServer.get();

			if (graffiti != null) {
				graffiti.onChunkUnloaded(level, chunk.getPos());
			}
		});

		// Autosave, so a crash costs at most the last save interval rather than the session.
		ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> {
			GraffitiServer graffiti = GraffitiServer.get();

			if (graffiti != null) {
				graffiti.saveAll();
			}
		});

		// Breaking a block destroys its paint (SPEC 5.4). Hooked after the break rather than
		// before, so a cancelled break does not wipe a mural.
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			GraffitiServer graffiti = GraffitiServer.get();

			if (graffiti != null && graffiti.config().clearOnBlockBreak && level instanceof ServerLevel serverLevel) {
				PaintService.clearBlock(graffiti, serverLevel, pos);
			}
		});

		SimpleGraffiti.LOGGER.info("{} initialised (Fabric)", SimpleGraffiti.MOD_NAME);
	}

	/**
	 * Registering the types is what makes the channels appear in the connection's declared set, so
	 * both sides' {@code canSend} checks have something to answer. Fabric channels are optional by
	 * construction - there is no required-channel negotiation to opt out of.
	 */
	static void registerPayloads() {
		PayloadTypeRegistry.serverboundPlay().register(GraffitiPayloads.PAINT, GraffitiPayloads.PaintC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(GraffitiPayloads.SET_COLOR, GraffitiPayloads.SetColorC2S.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(GraffitiPayloads.HELLO, GraffitiPayloads.HelloS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GraffitiPayloads.STAMP, GraffitiPayloads.StampS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GraffitiPayloads.CANVAS_SYNC, GraffitiPayloads.CanvasSyncS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GraffitiPayloads.CLEAR, GraffitiPayloads.ClearS2C.CODEC);
	}

	private void registerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(GraffitiPayloads.PAINT, (payload, context) -> {
			GraffitiServer server = GraffitiServer.get();

			if (server != null) {
				server.onPaint(context.player(), payload);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(GraffitiPayloads.SET_COLOR, (payload, context) -> {
			GraffitiServer server = GraffitiServer.get();

			if (server != null) {
				server.onSetColor(context.player(), payload);
			}
		});
	}
}
