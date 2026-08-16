package com.drinfonty.simplegraffiti.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * How the server sends a payload, supplied by whichever loader is running.
 *
 * <p>The two loaders spell this differently - {@code ServerPlayNetworking.send} against
 * {@code PacketDistributor}, {@code canSend} against {@code NetworkRegistry.hasChannel} - but the
 * rule is the same on both and is the whole point of the interface: <strong>never send to a peer
 * that has not declared the channel</strong>. Every send in the mod goes through
 * {@link #sendIfPossible}, so there is exactly one place that rule can be broken, and it is four
 * lines long.
 */
public interface PayloadSender {
	boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

	void send(ServerPlayer player, CustomPacketPayload payload);

	/**
	 * @return true if the payload was sent; false when the player's connection does not have the
	 *         channel, which is not an error - it is the mod declining to be the cause of one
	 */
	default boolean sendIfPossible(ServerPlayer player, CustomPacketPayload payload) {
		if (!canSend(player, payload.type())) {
			return false;
		}

		send(player, payload);
		return true;
	}
}
