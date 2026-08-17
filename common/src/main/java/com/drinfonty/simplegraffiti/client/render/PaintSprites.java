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
 * themselves. The sprite is deliberately <strong>solid white</strong>, so a quad's vertex colour
 * passes through untouched and paint renders as exactly the colour that was sprayed. An earlier
 * version used a faint noise grain to give the paint some tooth; multiplied against the vertex
 * colour it read as mottled and dirty rather than textured. One sprite still covers all 16 million
 * colours, because the colour comes entirely from the tint.
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
