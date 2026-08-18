package com.drinfonty.simplegraffiti.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Which blocks offer a paintable top, and at what height.
 *
 * <p>From a bug report that spraying did not work on snow. Snow layers are not full cubes, so the
 * "full blocks only" rule refused them - which in a snowy biome means the ground cannot be painted
 * at all. This is the one test in the suite that boots the game registries, because the answer
 * depends on real block shapes rather than on anything the mod defines.
 */
class PaintSurfaceTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static double top(BlockState state) {
		return PaintSurface.topOf(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state);
	}

	@Test
	void fullCubesArePaintableAtTheTopOfTheBlock() {
		assertEquals(1.0, top(Blocks.STONE.defaultBlockState()));
		assertEquals(1.0, top(Blocks.SNOW_BLOCK.defaultBlockState()));
		assertEquals(1.0, top(Blocks.GLASS.defaultBlockState()));
	}

	@Test
	void everySnowDepthIsPaintableAtItsVisibleSurface() {
		// The reported bug. Note the height tracks what the snow *looks* like: its collider is a
		// layer shallower, and using that would bury the paint inside the snow.
		for (int layers = 1; layers <= 8; layers++) {
			BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
			assertEquals(layers * 0.125, top(snow), "snow with " + layers + " layers");
		}
	}

	@Test
	void slabsArePaintableOnTop() {
		assertEquals(0.5, top(Blocks.STONE_SLAB.defaultBlockState()));
	}

	@Test
	void unevenBlocksAreRefused() {
		// Stairs share a full-cube bounding box with stone, so anything testing bounds rather than
		// the shape itself would wrongly accept them and float paint over the steps.
		assertEquals(PaintSurface.NONE, top(Blocks.OAK_STAIRS.defaultBlockState()));
		assertEquals(PaintSurface.NONE, top(Blocks.OAK_FENCE.defaultBlockState()));
	}

	@Test
	void decorationIsRefused() {
		// Grass and torches are what the crosshair hits when dragging across a field; they must
		// not become canvases, or a line would climb onto every tuft it passed.
		assertEquals(PaintSurface.NONE, top(Blocks.SHORT_GRASS.defaultBlockState()));
		assertEquals(PaintSurface.NONE, top(Blocks.TORCH.defaultBlockState()));
		assertEquals(PaintSurface.NONE, top(Blocks.AIR.defaultBlockState()));
	}
}
