package com.drinfonty.simplegraffiti.client;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * How the client sends a payload, supplied by whichever loader is running - the mirror of
 * {@code PayloadSender}, and subject to the same single rule: never send to a peer that has not
 * declared the channel.
 *
 * <p>On a vanilla server {@link #canSend} is false for every type, which is the mechanical reason
 * the mod cannot spam a server that would not understand it.
 */
public interface ClientPayloadSender {
	boolean canSend(CustomPacketPayload.Type<?> type);

	void send(CustomPacketPayload payload);

	default boolean sendIfPossible(CustomPacketPayload payload) {
		if (!canSend(payload.type())) {
			return false;
		}

		send(payload);
		return true;
	}
}
