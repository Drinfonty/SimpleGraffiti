package com.drinfonty.simplegraffiti.neoforge;

import com.drinfonty.simplegraffiti.GraffitiServer;
import com.drinfonty.simplegraffiti.SimpleGraffiti;
import com.drinfonty.simplegraffiti.item.GraffitiItems;
import com.drinfonty.simplegraffiti.net.GraffitiPayloads;
import com.drinfonty.simplegraffiti.net.PayloadSender;
import com.drinfonty.simplegraffiti.server.GraffitiCommands;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * NeoForge registration glue, and nothing else.
 *
 * <p>The one line that carries real weight is {@code registrar.optional()}: it keeps this mod's
 * channels out of the connection negotiation's required set, so a client running this mod is not
 * refused by a <em>vanilla</em> server. (The reverse - a vanilla client on a server running this
 * mod - is refused, and deliberately not worked around: registering items is what makes a content
 * mod a content mod.)
 */
@Mod(SimpleGraffiti.MOD_ID)
public class SimpleGraffitiNeoForge {
	private static final DeferredRegister.Items ITEMS =
		DeferredRegister.createItems(SimpleGraffiti.MOD_ID);

	public static final PayloadSender SENDER = new PayloadSender() {
		@Override
		public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
			return NetworkRegistry.hasChannel(player.connection, type.id());
		}

		@Override
		public void send(ServerPlayer player, CustomPacketPayload payload) {
			PacketDistributor.sendToPlayer(player, payload);
		}
	};

	public SimpleGraffitiNeoForge(IEventBus modBus) {
		// The factory is invoked by the deferred register during the registry event, not here:
		// constructing an Item at mod-construction time throws "Registry is already frozen".
		GraffitiItems.register((id, factory) -> ITEMS.register(id.getPath(), factory::get));
		ITEMS.register(modBus);

		modBus.addListener(RegisterPayloadHandlersEvent.class, SimpleGraffitiNeoForge::registerPayloads);
		modBus.addListener(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent.class,
			SimpleGraffitiNeoForge::addToCreativeTab);

		NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event ->
			GraffitiServer.start(event.getServer(), SENDER,
				event.getServer().getServerDirectory().resolve("config")));
		NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> GraffitiServer.stop());

		NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event ->
			GraffitiCommands.register(event.getDispatcher()));

		NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
			GraffitiServer server = GraffitiServer.get();

			if (server != null && event.getEntity() instanceof ServerPlayer player) {
				server.onPlayerJoin(player);
			}
		});

		NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
			GraffitiServer server = GraffitiServer.get();

			if (server != null && event.getEntity() instanceof ServerPlayer player) {
				server.onPlayerLeave(player);
			}
		});

		// Graffiti follows the chunk it decorates: written and released when the chunk unloads,
		// so a long-lived server's resident cost tracks loaded chunks rather than total paint.
		NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> {
			GraffitiServer server = GraffitiServer.get();

			if (server != null && event.getLevel() instanceof ServerLevel level) {
				server.onChunkUnloaded(level, event.getChunk().getPos());
			}
		});

		// Autosave, so a crash costs at most the last save interval rather than the session.
		NeoForge.EVENT_BUS.addListener(LevelEvent.Save.class, event -> {
			GraffitiServer server = GraffitiServer.get();

			if (server != null) {
				server.saveAll();
			}
		});

		// Client-only wiring is reached through a Dist guard, so the class - and everything it
		// references in net.minecraft.client - is never loaded on a dedicated server.
		if (net.neoforged.fml.loading.FMLEnvironment.getDist() == net.neoforged.api.distmarker.Dist.CLIENT) {
			com.drinfonty.simplegraffiti.neoforge.client.SimpleGraffitiNeoForgeClient.init(modBus);
		}

		SimpleGraffiti.LOGGER.info("{} initialised (NeoForge)", SimpleGraffiti.MOD_NAME);
	}

	private static void registerPayloads(RegisterPayloadHandlersEvent event) {
		// optional() is the whole degradation story on NeoForge: it stops these channels being
		// required, so their absence on the other side is a fact to check, not a disconnect.
		var registrar = event.registrar(String.valueOf(GraffitiPayloads.PROTOCOL_VERSION)).optional();

		registrar.playToServer(GraffitiPayloads.PAINT, GraffitiPayloads.PaintC2S.CODEC,
			(payload, context) -> {
				GraffitiServer server = GraffitiServer.get();

				if (server != null && context.player() instanceof ServerPlayer player) {
					server.onPaint(player, payload);
				}
			});

		registrar.playToServer(GraffitiPayloads.SET_COLOR, GraffitiPayloads.SetColorC2S.CODEC,
			(payload, context) -> {
				GraffitiServer server = GraffitiServer.get();

				if (server != null && context.player() instanceof ServerPlayer player) {
					server.onSetColor(player, payload);
				}
			});

		// Clientbound types must be registered on both sides so the channel is declared, but
		// their handlers only ever run on a client. Each body touches GraffitiClient, which
		// touches net.minecraft.client - safe here because a lambda body resolves its types
		// when it is first invoked, which on a dedicated server is never.
		registrar.playToClient(GraffitiPayloads.HELLO, GraffitiPayloads.HelloS2C.CODEC,
			(payload, context) -> withClient(graffiti -> graffiti.onHello(payload)));
		registrar.playToClient(GraffitiPayloads.STAMP, GraffitiPayloads.StampS2C.CODEC,
			(payload, context) -> withClient(graffiti -> graffiti.onStamp(payload)));
		registrar.playToClient(GraffitiPayloads.CANVAS_SYNC, GraffitiPayloads.CanvasSyncS2C.CODEC,
			(payload, context) -> withClient(graffiti -> graffiti.onCanvasSync(payload)));
		registrar.playToClient(GraffitiPayloads.CLEAR, GraffitiPayloads.ClearS2C.CODEC,
			(payload, context) -> withClient(graffiti -> graffiti.onClear(payload)));
	}

	private static void withClient(java.util.function.Consumer<com.drinfonty.simplegraffiti.GraffitiClient> action) {
		com.drinfonty.simplegraffiti.GraffitiClient graffiti =
			com.drinfonty.simplegraffiti.GraffitiClient.get();

		if (graffiti != null) {
			action.accept(graffiti);
		}
	}

	private static void addToCreativeTab(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
		// 26.2 keeps the vanilla tab keys private, so the key is rebuilt from its id.
		ResourceKey<CreativeModeTab> tools = ResourceKey.create(Registries.CREATIVE_MODE_TAB,
			Identifier.withDefaultNamespace("tools_and_utilities"));

		if (event.getTabKey().equals(tools)) {
			event.accept(new ItemStack(GraffitiItems.sprayCan));
			event.accept(new ItemStack(GraffitiItems.scrubSponge));
		}
	}
}
