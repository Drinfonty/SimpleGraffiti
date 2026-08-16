package com.drinfonty.simplegraffiti.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A per-player token bucket (SPEC 9.2): capacity {@code burstSprays}, refilled at
 * {@code spraysPerSecond}.
 *
 * <p>The check happens before any canvas lookup or allocation, so packet spam costs one map lookup
 * and nothing else. A legitimate client cannot exceed the limit, since it sprays at 4/s against a
 * default of 6/s, which is why exceeding it drops the packet silently rather than sending a
 * correction - a correction storm is exactly what an attacker would be trying to provoke.
 *
 * <p>Time is passed in rather than read from the clock so the refill behaviour can be tested
 * deterministically.
 */
public final class RateLimiter {
	private final Map<UUID, Bucket> buckets = new HashMap<>();

	private double tokensPerSecond;
	private double capacity;

	public RateLimiter(double tokensPerSecond, double capacity) {
		configure(tokensPerSecond, capacity);
	}

	/** Applied on {@code /graffiti reload}; existing buckets keep their current level. */
	public void configure(double tokensPerSecond, double capacity) {
		this.tokensPerSecond = Math.max(0.0, tokensPerSecond);
		this.capacity = Math.max(1.0, capacity);
	}

	/**
	 * @return true when the player had a token, which is then consumed
	 */
	public boolean tryConsume(UUID player, long nowMillis) {
		Bucket bucket = buckets.get(player);

		if (bucket == null) {
			bucket = new Bucket(capacity, nowMillis);
			buckets.put(player, bucket);
		} else {
			bucket.refill(nowMillis, tokensPerSecond, capacity);
		}

		if (bucket.tokens < 1.0) {
			return false;
		}

		bucket.tokens -= 1.0;
		return true;
	}

	/** Called on disconnect, so a long-lived server does not accumulate a bucket per visitor. */
	public void forget(UUID player) {
		buckets.remove(player);
	}

	public void clear() {
		buckets.clear();
	}

	public int trackedPlayers() {
		return buckets.size();
	}

	private static final class Bucket {
		private double tokens;
		private long lastMillis;

		private Bucket(double tokens, long lastMillis) {
			this.tokens = tokens;
			this.lastMillis = lastMillis;
		}

		private void refill(long nowMillis, double tokensPerSecond, double capacity) {
			// A clock that jumped backwards must not mint tokens.
			long elapsed = Math.max(0L, nowMillis - lastMillis);
			lastMillis = nowMillis;
			tokens = Math.min(capacity, tokens + (elapsed / 1000.0) * tokensPerSecond);
		}
	}
}
