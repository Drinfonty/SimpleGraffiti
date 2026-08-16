package com.drinfonty.simplegraffiti.canvas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class CanvasCodecTest {
	@Test
	void roundTripsAnEmptyCanvasCheaply() {
		int[] texels = new int[Canvas.TEXELS];
		byte[] encoded = CanvasCodec.encode(texels);

		// 256 texels is two runs, because the count is a single unsigned byte.
		assertEquals(10, encoded.length);
		assertArrayEquals(texels, CanvasCodec.decode(encoded));
	}

	@Test
	void roundTripsASingleColourTagInAHandfulOfBytes() {
		int[] texels = new int[Canvas.TEXELS];
		Brush.stamp(texels, 128, 128, Brush.SIZE_LARGE, PaintColor.opaque(0x123456));

		byte[] encoded = CanvasCodec.encode(texels);
		assertTrue(encoded.length < 400, "encoded to " + encoded.length + " bytes");
		assertArrayEquals(texels, CanvasCodec.decode(encoded));
	}

	@Test
	void roundTripsThePathologicalCanvas() {
		// Every texel a different colour: the worst case the bandwidth bound is written against.
		int[] texels = new int[Canvas.TEXELS];

		for (int i = 0; i < Canvas.TEXELS; i++) {
			texels[i] = PaintColor.opaque(i * 4099);
		}

		byte[] encoded = CanvasCodec.encode(texels);
		assertEquals(CanvasCodec.MAX_ENCODED_BYTES, encoded.length);
		assertEquals(1280, encoded.length);
		assertArrayEquals(texels, CanvasCodec.decode(encoded));
	}

	@Test
	void roundTripsRandomCanvases() {
		Random random = new Random(99L);

		for (int i = 0; i < 100; i++) {
			int[] texels = new int[Canvas.TEXELS];

			for (int t = 0; t < Canvas.TEXELS; t++) {
				texels[t] = random.nextInt(4) == 0
					? PaintColor.EMPTY
					: PaintColor.opaque(random.nextInt(8) * 0x112233);
			}

			assertArrayEquals(texels, CanvasCodec.decode(CanvasCodec.encode(texels)));
		}
	}

	@Test
	void nearIdenticalColoursSurviveAsDistinctValues() {
		// Acceptance criterion 8: no palette quantisation anywhere in the pipeline.
		int[] texels = new int[Canvas.TEXELS];
		texels[0] = PaintColor.opaque(0x123456);
		texels[1] = PaintColor.opaque(0x123457);

		int[] decoded = CanvasCodec.decode(CanvasCodec.encode(texels));
		assertNotNull(decoded);
		assertEquals(PaintColor.opaque(0x123456), decoded[0]);
		assertEquals(PaintColor.opaque(0x123457), decoded[1]);

		int[] fromDisk = CanvasCodec.fromBytes(CanvasCodec.toBytes(texels));
		assertNotNull(fromDisk);
		assertEquals(PaintColor.opaque(0x123456), fromDisk[0]);
		assertEquals(PaintColor.opaque(0x123457), fromDisk[1]);
	}

	@Test
	void rejectsMalformedInputWithoutThrowing() {
		// Every one of these is something a hostile or truncated payload can contain. None may
		// throw out of a network handler (SPEC 7.7).
		assertNull(CanvasCodec.decode(null));
		assertNull(CanvasCodec.decode(new byte[0]));
		assertNull(CanvasCodec.decode(new byte[] { 1, 2, 3 }), "length not a multiple of 5");
		assertNull(CanvasCodec.decode(new byte[] { 0, 0, 0, 0, 0 }), "zero run count");
		assertNull(CanvasCodec.decode(new byte[] { (byte) 255, 0, 0, 0, 0 }), "decodes to 255, not 256");
		assertNull(CanvasCodec.decode(new byte[CanvasCodec.MAX_ENCODED_BYTES + 5]), "over the bound");

		// Decodes to 510 texels, which overruns the canvas.
		byte[] overrun = new byte[] { (byte) 255, 0, 0, 0, 0, (byte) 255, 0, 0, 0, 0, (byte) 255, 0, 0, 0, 0 };
		assertNull(CanvasCodec.decode(overrun));
	}

	@Test
	void diskFormIsExactlyOneKilobyte() {
		int[] texels = new int[Canvas.TEXELS];
		texels[7] = PaintColor.opaque(0xABCDEF);

		byte[] bytes = CanvasCodec.toBytes(texels);
		assertEquals(1024, bytes.length);
		assertArrayEquals(texels, CanvasCodec.fromBytes(bytes));
	}

	@Test
	void rejectsDiskEntriesOfTheWrongLength() {
		// Such an entry is dropped individually, keeping the rest of the chunk (SPEC 8).
		assertNull(CanvasCodec.fromBytes(null));
		assertNull(CanvasCodec.fromBytes(new byte[1023]));
		assertNull(CanvasCodec.fromBytes(new byte[1025]));
	}

	@Test
	void normalisesAlphaOnRead() {
		// The format reserves alpha for a future opacity feature; v1.0 reads any non-zero alpha
		// as fully opaque, and drops the colour bytes of an unpainted texel so two "empty" texels
		// always compare equal.
		byte[] bytes = new byte[Canvas.BYTES];
		bytes[0] = 0x7F;
		bytes[1] = 0x11;
		bytes[2] = 0x22;
		bytes[3] = 0x33;
		bytes[4] = 0x00;
		bytes[5] = 0x44;
		bytes[6] = 0x55;
		bytes[7] = 0x66;

		int[] texels = CanvasCodec.fromBytes(bytes);
		assertNotNull(texels);
		assertEquals(PaintColor.opaque(0x112233), texels[0]);
		assertEquals(PaintColor.EMPTY, texels[1]);
	}
}
