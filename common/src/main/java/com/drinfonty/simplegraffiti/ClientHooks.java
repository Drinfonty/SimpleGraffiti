package com.drinfonty.simplegraffiti;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * The one-way door from shared code into client-only code.
 *
 * <p>The items live in {@code :common} and run on both sides, but "start spraying" is a purely
 * client-side act: it drives prediction and the hold-to-spray timer, neither of which exists on a
 * dedicated server. Rather than have {@code SprayCanItem} reference a client class - which would
 * make the item unloadable on a server - the client installs an implementation here at startup and
 * the item calls through the interface.
 *
 * <p>The field stays null on a dedicated server, and every call site checks {@code isClientSide}
 * first, so nothing here is ever reached there.
 */
public final class ClientHooks {
	private static volatile PaintTrigger trigger;

	private ClientHooks() {
	}

	public static void install(PaintTrigger paintTrigger) {
		trigger = paintTrigger;
	}

	public static PaintTrigger trigger() {
		return trigger;
	}

	public interface PaintTrigger {
		/**
		 * The player used a paint tool on a face.
		 *
		 * @param erase true for the scrub sponge, false for the spray can
		 * @param wholeFace true for a sneak-use with the sponge, which clears the whole face
		 */
		void onUseOnFace(BlockPos pos, Direction face, Vec3 hit, InteractionHand hand, boolean erase, boolean wholeFace);

		/** The player released use, or is no longer aiming at a paintable face. */
		void stopSpraying();
	}
}
