package com.drinfonty.simplegraffiti.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaintColorTest {
	@Test
	void packsAndUnpacksComponents() {
		int texel = PaintColor.opaque(0x123456);

		assertTrue(PaintColor.isPainted(texel));
		assertEquals(0x123456, PaintColor.rgb(texel));
		assertEquals(0x12, PaintColor.red(texel));
		assertEquals(0x34, PaintColor.green(texel));
		assertEquals(0x56, PaintColor.blue(texel));
	}

	@Test
	void emptyIsNotPainted() {
		assertFalse(PaintColor.isPainted(PaintColor.EMPTY));
		assertEquals(PaintColor.EMPTY, PaintColor.normalise(0x00FFFFFF));
	}

	@Test
	void normalisationForcesReservedAlphaToOpaque() {
		assertEquals(PaintColor.opaque(0x112233), PaintColor.normalise(0x7F112233));
		assertEquals(PaintColor.opaque(0x112233), PaintColor.normalise(0x01112233));
		assertEquals(PaintColor.opaque(0x112233), PaintColor.normalise(0xFF112233));
	}

	@Test
	void parsesHexWithAndWithoutHash() {
		assertEquals(0x1A2B3C, PaintColor.parseHex("#1A2B3C"));
		assertEquals(0x1A2B3C, PaintColor.parseHex("1a2b3c"));
		assertEquals(0x1A2B3C, PaintColor.parseHex("  #1A2B3C  "));
	}

	@Test
	void rejectsInvalidHexWithoutThrowing() {
		// The picker disables its confirm button on -1 rather than throwing (SPEC 5.3).
		assertEquals(-1, PaintColor.parseHex(null));
		assertEquals(-1, PaintColor.parseHex(""));
		assertEquals(-1, PaintColor.parseHex("#12345"));
		assertEquals(-1, PaintColor.parseHex("#1234567"));
		assertEquals(-1, PaintColor.parseHex("#12345G"));
		assertEquals(-1, PaintColor.parseHex("not a colour"));
	}

	@Test
	void formatsHexRoundTrip() {
		assertEquals("#1A2B3C", PaintColor.toHex(0x1A2B3C));
		assertEquals("#000000", PaintColor.toHex(0));
		assertEquals(0x1A2B3C, PaintColor.parseHex(PaintColor.toHex(0x1A2B3C)));
	}
}
