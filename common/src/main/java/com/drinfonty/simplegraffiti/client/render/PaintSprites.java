package com.drinfonty.simplegraffiti.client.render;

import com.drinfonty.simplegraffiti.SimpleGraffiti;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * The single sprite all paint is drawn with.
 *
 * <p>Paint lives in the <em>block</em> atlas (shipped via {@code assets/minecraft/atlases/blocks.json}),
 * so drawing it needs no extra texture bind and costs the cutout layer nothing beyond the quads
 * themselves. The sprite is a subtle grain rather than a flat white square: sampled across the face
 * it gives spray paint some tooth, and being one sprite means 16 million colours need exactly one
 * texture, with the colour coming from the vertex tint.
 *
 * <p>Looked up lazily and re-resolved after a resource reload, because a sprite reference cached
 * across a reload points into a texture that no longer exists.
 */
public final class PaintSprites {
	private static TextureAtlasSprite cached;

	private PaintSprites() {
	}

	public static TextureAtlasSprite paint() {
		TextureAtlasSprite sprite = cached;

		if (sprite == null) {
			sprite = Minecraft.getInstance().getAtlasManager()
				.get(Sheets.BLOCKS_MAPPER.apply(SimpleGraffiti.id("paint/spray")));
			cached = sprite;
		}

		return sprite;
	}

	/** Called on resource reload; the next lookup re-resolves against the new atlas. */
	public static void invalidate() {
		cached = null;
	}
}
