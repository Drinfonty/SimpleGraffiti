package com.drinfonty.simplegraffiti.fabric;

import com.drinfonty.simplegraffiti.GraffitiClient;
import com.drinfonty.simplegraffiti.SimpleGraffiti;
import com.drinfonty.simplegraffiti.client.ClientPayloadSender;
import com.drinfonty.simplegraffiti.client.gui.PaletteScreen;
import com.drinfonty.simplegraffiti.client.render.PaintSprites;
import com.drinfonty.simplegraffiti.fabric.render.GraffitiWrapperModel;
import com.drinfonty.simplegraffiti.item.GraffitiItems;
import com.drinfonty.simplegraffiti.net.GraffitiPayloads;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.lwjgl.glfw.GLFW;

/**
 * Fabric client glue.
 *
 * <p>The interesting line is the model modifier: registered in {@code WRAP_LAST_PHASE} so this
 * wrapper sits outermost and delegates unconditionally to whatever other model-modifying mods
 * (connected textures and their kin) have already produced. Wrapping is cooperative only if
 * everyone delegates, so this mod goes last and always calls through.
 */
public class SimpleGraffitiFabricClient implements ClientModInitializer {
	private static final ClientPayloadSender SENDER = new ClientPayloadSender() {
		@Override
		public boolean canSend(CustomPacketPayload.Type<?> type) {
			return ClientPlayNetworking.canSend(type);
		}

		@Override
		public void send(CustomPacketPayload payload) {
			ClientPlayNetworking.send(payload);
		}
	};

	private static KeyMapping paletteKey;

	@Override
	public void onInitializeClient() {
		GraffitiClient.start(SENDER, Minecraft.getInstance().gameDirectory.toPath().resolve("config"));

		paletteKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.simple_graffiti.palette", GLFW.GLFW_KEY_G, KeyMapping.Category.GAMEPLAY));

		registerReceivers();

		ModelLoadingPlugin.register(context -> {
			// This runs on every model reload, which is exactly when the atlas is restitched, so
			// it is the right moment to drop the cached sprite reference.
			PaintSprites.invalidate();
			context.modifyBlockModelAfterBake().register(ModelModifier.WRAP_LAST_PHASE,
				(model, modifierContext) -> new GraffitiWrapperModel(model));
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			GraffitiClient graffiti = GraffitiClient.get();

			if (graffiti == null) {
				return;
			}

			graffiti.tick();

			while (paletteKey.consumeClick()) {
				// Only while holding a can, and only when the server can actually apply the
				// choice - a picker that silently does nothing is worse than no picker.
				if (graffiti.canPaint()
					&& client.player != null
					&& GraffitiItems.isSprayCan(client.player.getMainHandItem())) {
					client.gui.setScreen(new PaletteScreen());
				}
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			GraffitiClient graffiti = GraffitiClient.get();

			if (graffiti != null) {
				graffiti.onDisconnect();
			}
		});

		SimpleGraffiti.LOGGER.info("{} client initialised (Fabric)", SimpleGraffiti.MOD_NAME);
	}

	private void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(GraffitiPayloads.HELLO, (payload, context) ->
			withClient(graffiti -> graffiti.onHello(payload)));
		ClientPlayNetworking.registerGlobalReceiver(GraffitiPayloads.STAMP, (payload, context) ->
			withClient(graffiti -> graffiti.onStamp(payload)));
		ClientPlayNetworking.registerGlobalReceiver(GraffitiPayloads.CANVAS_SYNC, (payload, context) ->
			withClient(graffiti -> graffiti.onCanvasSync(payload)));
		ClientPlayNetworking.registerGlobalReceiver(GraffitiPayloads.CLEAR, (payload, context) ->
			withClient(graffiti -> graffiti.onClear(payload)));
	}

	private static void withClient(java.util.function.Consumer<GraffitiClient> action) {
		GraffitiClient graffiti = GraffitiClient.get();

		if (graffiti != null) {
			action.accept(graffiti);
		}
	}
}
