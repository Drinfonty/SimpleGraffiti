package com.drinfonty.simplegraffiti.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class RateLimiterTest {
	private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
	private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

	@Test
	void allowsAFullBurstThenStops() {
		RateLimiter limiter = new RateLimiter(6.0, 12.0);

		for (int i = 0; i < 12; i++) {
			assertTrue(limiter.tryConsume(ALICE, 0L), "burst token " + i + " was refused");
		}

		assertFalse(limiter.tryConsume(ALICE, 0L), "the bucket did not empty at capacity");
	}

	@Test
	void refillsAtTheConfiguredRate() {
		RateLimiter limiter = new RateLimiter(6.0, 12.0);

		while (limiter.tryConsume(ALICE, 0L)) {
			// drain
		}

		assertFalse(limiter.tryConsume(ALICE, 100L), "0.1s is not enough for a token at 6/s");
		assertTrue(limiter.tryConsume(ALICE, 200L), "0.2s should mint one token at 6/s");
		assertFalse(limiter.tryConsume(ALICE, 200L));
	}

	@Test
	void doesNotRefillBeyondCapacity() {
		RateLimiter limiter = new RateLimiter(6.0, 12.0);

		while (limiter.tryConsume(ALICE, 0L)) {
			// drain
		}

		// An hour of idling must not bank an hour of sprays.
		int granted = 0;

		while (limiter.tryConsume(ALICE, 3_600_000L)) {
			granted++;
		}

		assertEquals(12, granted);
	}

	@Test
	void aClockThatJumpedBackwardsMintsNothing() {
		RateLimiter limiter = new RateLimiter(6.0, 12.0);

		while (limiter.tryConsume(ALICE, 10_000L)) {
			// drain
		}

		assertFalse(limiter.tryConsume(ALICE, 0L), "a backwards clock refilled the bucket");
	}

	@Test
	void bucketsArePerPlayer() {
		RateLimiter limiter = new RateLimiter(6.0, 12.0);

		while (limiter.tryConsume(ALICE, 0L)) {
			// drain Alice only
		}

		assertTrue(limiter.tryConsume(BOB, 0L), "Bob was rate-limited by Alice's spam");
	}

	@Test
	void aLegitimateClientIsNeverLimited() {
		// A real client sprays every 5 ticks - 4/s - against a default of 6/s, which is the whole
		// reason exceeding the limit can be treated as hostile and dropped silently.
		RateLimiter limiter = new RateLimiter(6.0, 12.0);

		for (int i = 0; i < 400; i++) {
			assertTrue(limiter.tryConsume(ALICE, i * 250L), "a 4/s sprayer was limited at spray " + i);
		}
	}

	@Test
	void forgettingAPlayerReleasesTheBucket() {
		RateLimiter limiter = new RateLimiter(6.0, 12.0);
		limiter.tryConsume(ALICE, 0L);
		limiter.tryConsume(BOB, 0L);
		assertEquals(2, limiter.trackedPlayers());

		limiter.forget(ALICE);
		assertEquals(1, limiter.trackedPlayers());
	}
}
