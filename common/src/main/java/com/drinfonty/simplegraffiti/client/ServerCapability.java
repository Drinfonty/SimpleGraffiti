package com.drinfonty.simplegraffiti.client;

/**
 * The degradation gate (SPEC 10), and the reason a modded client on a vanilla server does nothing
 * at all rather than something surprising.
 *
 * <p>Two states, no more:
 *
 * <pre>
 *   NONE  -- hello with a compatible protocol version --&gt;  READY
 *   READY -- disconnect / world unload                --&gt;  NONE
 * </pre>
 *
 * <p>Everything the client does - rendering, painting, erasing, storing, the picker's apply - is
 * gated on {@code READY}. On a vanilla server no {@code hello} ever arrives, so the can is an inert
 * item: right-click does nothing, shows one message per session, and sends no packet.
 */
public enum ServerCapability {
	NONE,
	READY,
}
