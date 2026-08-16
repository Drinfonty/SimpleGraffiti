package com.drinfonty.simplegraffiti;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod identity and the handful of things every package needs.
 *
 * <p>Nothing here may touch a loader API or a client class: this type is loaded on a dedicated
 * server as readily as on a client.
 */
public final class SimpleGraffiti {
	public static final String MOD_ID = "simple_graffiti";
	public static final String MOD_NAME = "Simple Graffiti";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * Verbose diagnostics, off unless {@code -Dsimple_graffiti.debug=true} is passed. The Gradle
	 * dev run configurations set it; a real launcher never does, so players never see it.
	 *
	 * <p>Guard at the call site rather than logging unconditionally - the paint path runs several
	 * times a second per painter and its arguments are not free to compute. Being
	 * {@code static final}, the JIT folds the branch away when disabled.
	 */
	public static final boolean DEBUG = Boolean.getBoolean("simple_graffiti.debug");

	private SimpleGraffiti() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
