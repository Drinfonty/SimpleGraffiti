package com.drinfonty.simplegraffiti.client.render;

/**
 * One merged rectangle of a single colour on one face, in texel coordinates.
 *
 * <p>Deliberately holds no Minecraft types. The geometry - which rectangles, in which texel bounds,
 * in which colour - is produced once in {@code :common} and unit-tested there; only the final
 * hand-off differs per loader, where Fabric turns it into {@code QuadEmitter} calls and NeoForge
 * into a {@code BakedQuad}.
 *
 * @param face the vanilla 3D data value of the painted face
 * @param minU inclusive texel column, 0..15
 * @param minV inclusive texel row, 0..15
 * @param maxU exclusive texel column, 1..16
 * @param maxV exclusive texel row, 1..16
 * @param argb the colour, always opaque in v1.0
 */
public record PaintQuad(int face, int minU, int minV, int maxU, int maxV, int argb) {
	public int widthTexels() {
		return maxU - minU;
	}

	public int heightTexels() {
		return maxV - minV;
	}

	public int areaTexels() {
		return widthTexels() * heightTexels();
	}
}
