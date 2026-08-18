package com.drinfonty.simplegraffiti.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where paint sits on a block's top face.
 *
 * <p>A full cube's top is at 1.0, but plenty of blocks people walk on are not full cubes: snow
 * layers, slabs, carpets. Requiring a full cube meant a snowy biome could not be painted at all,
 * which is a far bigger hole than the slabs the limitation was written for, because snow covers
 * whole landscapes rather than the odd step.
 *
 * <p>A surface qualifies when the block's <em>outline</em> shape is a box spanning the whole block
 * footprint from its floor up to some height. That deliberately accepts snow, slabs and carpets and
 * rejects stairs and fences: those have a full-cube bounding box but are not flat, so paint would
 * float over the gaps. Checking the shape rather than its bounds is what tells the two apart.
 *
 * <p>The outline rather than the collision shape, for two reasons. Snow's collider is one layer
 * shallower than it looks, so collision-based paint on four layers of snow would sit at 0.375 while
 * the snow visibly reaches 0.5 - buried. And a single layer of snow has no collider at all, yet is
 * plainly a surface you can see and want to tag. The outline is what the player sees, which is what
 * paint should sit on.
 */
public final class PaintSurface {
	/** Returned when a block has no flat top to paint on. */
	public static final double NONE = -1.0;

	private PaintSurface() {
	}

	/**
	 * The height of the paintable top surface, in block-local units, or {@link #NONE}.
	 *
	 * <p>1.0 for an ordinary full block, 0.125 per layer of snow, 0.5 for a bottom slab.
	 */
	public static double topOf(BlockGetter level, BlockPos pos, BlockState state) {
		if (state.isAir()) {
			return NONE;
		}

		VoxelShape shape = state.getShape(level, pos);

		if (shape.isEmpty()) {
			return NONE;
		}

		AABB bounds = shape.bounds();

		// Must cover the whole footprint and sit on the block's floor: a shape starting part way
		// up is a thing hanging in the air, not a surface to stand on.
		if (bounds.minX > 0.0 || bounds.minY > 0.0 || bounds.minZ > 0.0
			|| bounds.maxX < 1.0 || bounds.maxZ < 1.0 || bounds.maxY > 1.0) {
			return NONE;
		}

		// ...and must BE that box rather than merely fit inside it. Stairs and fences share a
		// full-cube bounding box while being anything but flat on top.
		if (Shapes.joinIsNotEmpty(shape, Shapes.box(0.0, 0.0, 0.0, 1.0, bounds.maxY, 1.0),
			BooleanOp.NOT_SAME)) {
			return NONE;
		}

		return bounds.maxY;
	}

	/** Whether this face's paint plane sits somewhere other than the block's own cube face. */
	public static boolean isFlatTopped(BlockGetter level, BlockPos pos, BlockState state) {
		return topOf(level, pos, state) != NONE;
	}

	/**
	 * The offset of the paint plane along the face's outward axis, in block-local units.
	 *
	 * <p>Only the top face can sit anywhere unusual. Every other face still belongs to a full cube,
	 * because painting the side of a half-height block would need the canvas to know its 2D bounds
	 * as well, which the storage format has no room for.
	 */
	public static double planeFor(BlockGetter level, BlockPos pos, BlockState state, int face) {
		if (face != com.drinfonty.simplegraffiti.canvas.FaceAxes.UP) {
			return 1.0;
		}

		double top = topOf(level, pos, state);
		return top == NONE ? 1.0 : top;
	}

	/** Convenience for the renderers, which work in floats. */
	public static float planeFor(BlockGetter level, BlockPos pos, BlockState state, Direction face) {
		return (float) planeFor(level, pos, state, face.get3DDataValue());
	}
}
