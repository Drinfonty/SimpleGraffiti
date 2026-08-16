package com.drinfonty.simplegraffiti.world;

import com.drinfonty.simplegraffiti.SimpleGraffiti;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The paintable-surface test (SPEC 5.1), run identically on the client (to decide whether to
 * predict) and on the server (to decide whether to accept).
 *
 * <p>It is deliberately expressed over block *properties* rather than a block list: a face is
 * paintable when it is a sturdy full face that is actually visible. That makes the mod work with
 * modded blocks nobody has heard of, and it is why glass panes, slabs, stairs and fences are
 * excluded without naming any of them.
 */
public final class Paintability {
	public static final TagKey<Block> PAINTABLE =
		TagKey.create(net.minecraft.core.registries.Registries.BLOCK, SimpleGraffiti.id("paintable"));
	public static final TagKey<Block> NOT_PAINTABLE =
		TagKey.create(net.minecraft.core.registries.Registries.BLOCK, SimpleGraffiti.id("not_paintable"));

	private Paintability() {
	}

	public static boolean isPaintable(BlockGetter level, BlockPos pos, Direction face, boolean restrictToTag) {
		BlockState state = level.getBlockState(pos);

		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return false;
		}

		if (!state.isFaceSturdy(level, pos, face) || !state.isCollisionShapeFullBlock(level, pos)) {
			return false;
		}

		if (state.is(NOT_PAINTABLE)) {
			return false;
		}

		if (restrictToTag && !state.is(PAINTABLE)) {
			return false;
		}

		// A block entity with an interactive right-click - chest, furnace, sign, bed, door -
		// must keep its interaction: spraying must never pre-empt vanilla behaviour. Sneak-use
		// suppresses that interaction anyway, and with the can sneak-use is the eyedropper, so
		// there is no gesture that both opens a chest and paints it.
		if (state.hasBlockEntity()) {
			return false;
		}

		// The face must be exposed. A face flush against another block cannot be seen, and paint
		// hidden inside a wall would be storage spent on nothing.
		BlockPos neighbour = pos.relative(face);
		BlockState neighbourState = level.getBlockState(neighbour);

		return !neighbourState.isFaceSturdy(level, neighbour, face.getOpposite());
	}

	/**
	 * Whether a block change should wipe that block's paint (SPEC 5.4). Kept next to the
	 * paintability test so the two cannot drift apart into "paintable but never cleaned up".
	 *
	 * <p>Any change of block clears, and so does a state change that stops the block being a full
	 * cube - a slab being un-doubled, say - because paint on a face that no longer exists would
	 * otherwise float in the air.
	 */
	public static boolean shouldClearOnChange(BlockState before, BlockState after) {
		if (before.getBlock() != after.getBlock()) {
			return true;
		}

		return !before.equals(after) && !after.isCollisionShapeFullBlock(EmptyGetter.INSTANCE, BlockPos.ZERO);
	}

	/**
	 * A stand-in {@link BlockGetter} for shape questions that do not depend on neighbours. Vanilla
	 * shape caches ignore the level for full-cube tests, but the signature demands one.
	 */
	private enum EmptyGetter implements BlockGetter {
		INSTANCE;

		@Override
		public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		}

		@Override
		public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
			return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
		}

		@Override
		public int getHeight() {
			return 0;
		}

		@Override
		public int getMinY() {
			return 0;
		}
	}
}
