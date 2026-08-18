package com.drinfonty.simplegraffiti.mixin;

import com.drinfonty.simplegraffiti.GraffitiServer;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;
import com.drinfonty.simplegraffiti.world.CanvasStore;
import com.drinfonty.simplegraffiti.world.ChunkCanvases;
import com.drinfonty.simplegraffiti.world.PaintService;
import com.drinfonty.simplegraffiti.world.PaintSurface;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clears paint when the block under it changes.
 *
 * <p>Two cases matter and neither loader exposes an event for the second. A block being replaced or
 * removed takes its paint with it - including by melting or a piston, not just a player breaking
 * it. And a block whose <em>surface moves</em> keeps existing but is no longer the same canvas:
 * snow falling on snow lifts the surface a layer, so the paint that was on it would either sink
 * inside the new snow or hang above the old.
 *
 * <p>This runs on every block change in the world, so it is written to leave almost immediately.
 * The first thing it does is ask whether the chunk holds any paint at all, which for the
 * overwhelming majority of blocks is two map lookups and a return.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Inject(method = "setBlockState", at = @At("HEAD"))
	private void simpleGraffiti$clearPaintOnChange(BlockPos pos, BlockState after, int flags,
		CallbackInfoReturnable<BlockState> callback) {
		GraffitiServer server = GraffitiServer.get();

		if (server == null) {
			return;
		}

		LevelChunk self = (LevelChunk) (Object) this;

		if (!(self.getLevel() instanceof ServerLevel level)) {
			return;
		}

		CanvasStore store = server.storeIfPresent(level);

		if (store == null) {
			return;
		}

		ChunkCanvases canvases = store.chunk(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));

		if (canvases == null || canvases.isEmpty()) {
			return;
		}

		BlockState before = self.getBlockState(pos);

		if (before == after) {
			return;
		}

		if (before.getBlock() != after.getBlock()) {
			if (server.config().clearOnBlockBreak) {
				PaintService.clearBlock(server, level, pos.immutable());
			}

			return;
		}

		// Same block, different state: only the top face can move, and only it needs clearing.
		if (PaintSurface.topOf(level, pos, before) != PaintSurface.topOf(level, pos, after)) {
			PaintService.clearFace(server, level, pos.immutable(), FaceAxes.UP);
		}
	}
}
