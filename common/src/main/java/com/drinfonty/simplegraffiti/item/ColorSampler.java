package com.drinfonty.simplegraffiti.item;

import com.drinfonty.simplegraffiti.canvas.PaintColor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The eyedropper (SPEC 5.3): what colour is that block, as a 24-bit RGB value?
 *
 * <p>Blocks that carry a {@link DyeColor} - wool, carpet, concrete, terracotta, stained glass,
 * shulker boxes, candles, beds, banners - report it through vanilla's {@link BeaconBeamBlock}
 * interface, and those use {@link DyeColor#getTextureDiffuseColor()}. That matters more than it
 * looks: it is what makes sampling blue wool give <em>exactly</em> the blue that dyeing the can
 * blue gives, so the two ways of reaching a colour agree instead of being one shade apart.
 *
 * <p>Everything else falls back to the block's map colour, which is the closest thing vanilla has
 * to "what colour is this block" without reading pixels off an atlas the server does not have.
 */
public final class ColorSampler {
	private ColorSampler() {
	}

	public static int sample(BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);

		if (state.getBlock() instanceof BeaconBeamBlock beam) {
			return beam.getColor().getTextureDiffuseColor() & 0xFFFFFF;
		}

		MapColor mapColor = state.getMapColor(level, pos);

		if (mapColor == MapColor.NONE) {
			return PaintColor.DEFAULT_RGB;
		}

		return mapColor.col & 0xFFFFFF;
	}
}
