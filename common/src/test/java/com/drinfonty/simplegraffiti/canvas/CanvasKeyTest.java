package com.drinfonty.simplegraffiti.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CanvasKeyTest {
	@Test
	void roundTripsEveryAddressInAChunkColumn() {
		Set<Long> seen = new HashSet<>();

		// The full build range of a vanilla overworld, plus room either side, against every
		// local column and face. Collisions here would silently merge two players' canvases.
		for (int y = -128; y <= 384; y++) {
			for (int localX = 0; localX < 16; localX++) {
				for (int localZ = 0; localZ < 16; localZ++) {
					for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
						long key = CanvasKey.pack(localX, y, localZ, face);

						assertEquals(localX, CanvasKey.localX(key));
						assertEquals(localZ, CanvasKey.localZ(key));
						assertEquals(y, CanvasKey.y(key));
						assertEquals(face, CanvasKey.face(key));
						assertEquals(true, seen.add(key), "key collision at " + localX + "," + y + "," + localZ);
					}
				}
			}
		}
	}

	@Test
	void handlesExtremeY() {
		// Y is stored as a full signed int so no build-height assumption is baked into the key.
		for (int y : new int[] { Integer.MIN_VALUE, -30_000_000, -1, 0, 1, 30_000_000, Integer.MAX_VALUE }) {
			long key = CanvasKey.pack(9, y, 4, FaceAxes.EAST);
			assertEquals(y, CanvasKey.y(key));
			assertEquals(9, CanvasKey.localX(key));
			assertEquals(4, CanvasKey.localZ(key));
			assertEquals(FaceAxes.EAST, CanvasKey.face(key));
		}
	}

	@Test
	void withFaceChangesOnlyTheFace() {
		long key = CanvasKey.pack(3, 71, 12, FaceAxes.NORTH);
		long flipped = CanvasKey.withFace(key, FaceAxes.SOUTH);

		assertNotEquals(key, flipped);
		assertEquals(FaceAxes.SOUTH, CanvasKey.face(flipped));
		assertEquals(CanvasKey.localX(key), CanvasKey.localX(flipped));
		assertEquals(CanvasKey.localZ(key), CanvasKey.localZ(flipped));
		assertEquals(CanvasKey.y(key), CanvasKey.y(flipped));
	}

	@Test
	void rejectsOutOfRangeInput() {
		assertThrows(IllegalArgumentException.class, () -> CanvasKey.pack(16, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> CanvasKey.pack(-1, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> CanvasKey.pack(0, 0, 16, 0));
		assertThrows(IllegalArgumentException.class, () -> CanvasKey.pack(0, 0, 0, 6));
		assertThrows(IllegalArgumentException.class, () -> CanvasKey.pack(0, 0, 0, -1));
	}
}
