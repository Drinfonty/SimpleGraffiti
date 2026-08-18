package com.drinfonty.simplegraffiti.fabric.render;

import java.util.List;
import java.util.function.Predicate;

import com.drinfonty.simplegraffiti.GraffitiClient;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;
import com.drinfonty.simplegraffiti.client.render.CanvasMesher;
import com.drinfonty.simplegraffiti.client.render.PaintGeometry;
import com.drinfonty.simplegraffiti.client.render.PaintQuad;
import com.drinfonty.simplegraffiti.client.render.PaintSprites;

import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Emits graffiti as part of the block's own model (SPEC 11).
 *
 * <p>This is the whole rendering strategy in one class. Because paint goes through the standard
 * model pipeline rather than around it, the section mesher bakes it into chunk geometry, which
 * means lighting, ambient occlusion, culling, fog and sorting are all somebody else's problem, and
 * chunk-meshing replacement mods (the Sodium class) consume it like any other block geometry.
 *
 * <p>Per-frame cost is zero. The per-<em>rebuild</em> cost is one map lookup per block, which
 * early-outs for the unpainted 99.9% before anything is allocated.
 */
public class GraffitiWrapperModel extends WrapperBlockStateModel {
	public GraffitiWrapperModel(BlockStateModel wrapped) {
		super(wrapped);
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
		RandomSource random, Predicate<Direction> cullTest) {
		// Delegate unconditionally and first: another model-modifying mod may be underneath us,
		// and a wrapper that fails to delegate drops the block itself, not just its paint.
		super.emitQuads(emitter, level, pos, state, random, cullTest);

		GraffitiClient client = GraffitiClient.get();

		if (client == null || !client.shouldRender()) {
			return;
		}

		if (!client.canvases().isPainted(pos)) {
			return;
		}

		TextureAtlasSprite sprite = PaintSprites.paint();
		float[] corners = new float[12];

		// Snow layers, slabs and carpets are paintable on top and their surface is not at the top
		// of the cube; paint has to sit on what the player can see.
		float surfaceY = com.drinfonty.simplegraffiti.world.PaintSurface.planeFor(level, pos, state, Direction.UP);

		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			Canvas canvas = client.canvases().get(pos, face);

			if (canvas == null) {
				continue;
			}

			Direction direction = Direction.from3DDataValue(face);

			for (PaintQuad quad : CanvasMesher.mesh(canvas.texels(), face)) {
				emit(emitter, sprite, direction,
					PaintGeometry.corners(quad, corners, surfaceY), quad.argb());
			}
		}
	}

	private static void emit(QuadEmitter emitter, TextureAtlasSprite sprite, Direction direction,
		float[] corners, int argb) {
		for (int vertex = 0; vertex < 4; vertex++) {
			emitter.pos(vertex, corners[vertex * 3], corners[vertex * 3 + 1], corners[vertex * 3 + 2]);
			emitter.color(vertex, argb);

			// The sprite is sampled continuously across the face rather than repeated per texel,
			// so the grain reads as one surface instead of a tiled pattern.
			emitter.uv(vertex, sprite.getU(uOf(vertex)), sprite.getV(vOf(vertex)));
		}

		emitter.nominalFace(direction);

		// Deliberately no cull face. The quad floats DECAL_OFFSET off the surface, so culling it
		// with the face it sits on would make paint vanish exactly when the block is against
		// another one - which is most of a wall.
		emitter.cullFace(null);
		emitter.chunkLayer(ChunkSectionLayer.CUTOUT);
		emitter.emit();
	}

	private static float uOf(int vertex) {
		return vertex == 0 || vertex == 3 ? 0.0F : 1.0F;
	}

	private static float vOf(int vertex) {
		return vertex < 2 ? 1.0F : 0.0F;
	}

	@Override
	public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		GraffitiClient client = GraffitiClient.get();

		if (client == null || !client.shouldRender()) {
			return super.createGeometryKey(level, pos, state, random);
		}

		// The key is canvas *identity*, which is exactly right because a canvas is replaced rather
		// than mutated: same references means byte-identical paint, so FRAPI (and Sodium's
		// implementation of it) may reuse the cached geometry.
		Object[] key = new Object[FaceAxes.FACE_COUNT + 1];
		key[0] = super.createGeometryKey(level, pos, state, random);
		boolean painted = false;

		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			Canvas canvas = client.canvases().get(pos, face);
			key[face + 1] = canvas;
			painted |= canvas != null;
		}

		return painted ? List.of(key) : key[0];
	}

}
