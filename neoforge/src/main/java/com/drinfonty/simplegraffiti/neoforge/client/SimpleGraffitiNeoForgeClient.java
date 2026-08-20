package com.drinfonty.simplegraffiti.neoforge.client;

import com.drinfonty.simplegraffiti.GraffitiClient;
import com.drinfonty.simplegraffiti.SimpleGraffiti;
import com.drinfonty.simplegraffiti.client.ClientPayloadSender;
import com.drinfonty.simplegraffiti.client.render.PaintSprites;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge client glue.
 *
 * <p>The interesting line is the baking-result hook: every block-state model is replaced with a
 * {@link GraffitiDynamicModel} that delegates to it, which is how paint reaches the section mesher
 * as ordinary block geometry.
 */
@EventBusSubscriber(modid = SimpleGraffiti.MOD_ID, value = Dist.CLIENT)
public final class SimpleGraffitiNeoForgeClient {
	private static final ClientPayloadSender SENDER = new ClientPayloadSender() {
		@Override
		public boolean canSend(CustomPacketPayload.Type<?> type) {
			Minecraft client = Minecraft.getInstance();
			return client.getConnection() != null
				&& NetworkRegistry.hasChannel(client.getConnection(), type.id());
		}

		@Override
		public void send(CustomPacketPayload payload) {
			ClientPacketDistributor.sendToServer(payload);
		}
	};

	private static final KeyMapping PALETTE_KEY = new KeyMapping(
		"key.simple_graffiti.palette", GLFW.GLFW_KEY_G, KeyMapping.Category.GAMEPLAY);

	private SimpleGraffitiNeoForgeClient() {
	}

	public static void init(IEventBus modBus) {
		modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(() ->
			GraffitiClient.start(SENDER,
				Minecraft.getInstance().gameDirectory.toPath().resolve("config"))));

		modBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(PALETTE_KEY));

		modBus.addListener(ModelEvent.ModifyBakingResult.class, event -> {
			// Wrap every baked block-state model. The wrapper delegates unconditionally, so it
			// composes with other model-modifying mods in either load order.
			event.getBakingResult().blockStateModels()
				.replaceAll((state, model) -> new GraffitiDynamicModel(model));
			GraffitiDynamicModel.clearCache();
			PaintSprites.invalidate();
		});

		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
			GraffitiClient graffiti = GraffitiClient.get();

			if (graffiti == null) {
				return;
			}

			graffiti.tick();

			while (PALETTE_KEY.consumeClick()) {
				// The same entry point sneak-use takes, so the two ways in cannot drift apart -
				// including the sampling of whatever the player is looking at.
				graffiti.openPalette();
			}
		});

		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
			GraffitiClient graffiti = GraffitiClient.get();

			if (graffiti != null) {
				graffiti.onDisconnect();
			}

			GraffitiDynamicModel.clearCache();
		});

		SimpleGraffiti.LOGGER.info("{} client initialised (NeoForge)", SimpleGraffiti.MOD_NAME);
	}
}
