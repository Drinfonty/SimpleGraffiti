package com.drinfonty.simplegraffiti.neoforge.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.drinfonty.simplegraffiti.GraffitiClient;
import com.drinfonty.simplegraffiti.canvas.Canvas;
import com.drinfonty.simplegraffiti.canvas.FaceAxes;
import com.drinfonty.simplegraffiti.client.render.CanvasMesher;
import com.drinfonty.simplegraffiti.client.render.PaintGeometry;
import com.drinfonty.simplegraffiti.client.render.PaintQuad;
import com.drinfonty.simplegraffiti.client.render.PaintSprites;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.client.model.quad.MutableQuad;

/**
 * The NeoForge half of the renderer: the same idea as the Fabric wrapper, in NeoForge's shape.
 *
 * <p>Every baked block-state model is wrapped at bake time; when asked for the geometry of a block
 * <em>at a position</em>, the wrapper delegates to the real model and then appends one extra part
 * carrying the graffiti quads. Because that goes through the standard model pipeline, the section
 * mesher bakes paint into chunk geometry and lighting, AO, culling and fog come out right for free.
 *
 * <p>Colour rides on the quad's vertex colours via {@link MutableQuad}, not on a tint index. That
 * matters: a tint index resolves through a per-block {@code BlockTintSource}, which knows nothing
 * about which face or which texel it is colouring, and so cannot express 256 arbitrary colours on
 * one block. Vertex colour can.
 */
public class GraffitiDynamicModel extends DelegateBlockStateModel implements DynamicBlockStateModel {
	/**
	 * Built quads cached per canvas.
	 *
	 * <p>Keyed by canvas identity, which is exactly right because a canvas is replaced rather than
	 * mutated: the same reference means byte-identical paint. Fabric gets this for free through
	 * {@code createGeometryKey}; on NeoForge the caching is ours to do.
	 */
	private static final Map<Canvas, List<BakedQuad>> CACHE = new ConcurrentHashMap<>();

	/** Roughly a full render distance of heavily painted chunks before the cache is dropped. */
	private static final int MAX_CACHED_CANVASES = 8192;

	public GraffitiDynamicModel(BlockStateModel wrapped) {
		super(wrapped);
	}

	/** Dropped on resource reload and on world unload, so stale sprites cannot be drawn. */
	public static void clearCache() {
		CACHE.clear();
	}

	@Override
	public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state,
		RandomSource random, List<BlockStateModelPart> parts) {
		// Delegate unconditionally and first: another model-modifying mod may be underneath, and
		// a wrapper that fails to delegate drops the block itself, not just its paint.
		// DelegateBlockStateModel already forwards the position-aware call correctly, including
		// to a wrapped model that is itself dynamic.
		super.collectParts(level, pos, state, random, parts);

		GraffitiClient client = GraffitiClient.get();

		if (client == null || !client.shouldRender() || !client.canvases().isPainted(pos)) {
			return;
		}

		List<BakedQuad> quads = null;

		for (int face = 0; face < FaceAxes.FACE_COUNT; face++) {
			Canvas canvas = client.canvases().get(pos, face);

			if (canvas == null) {
				continue;
			}

			int currentFace = face;
			List<BakedQuad> faceQuads = CACHE.computeIfAbsent(canvas, key -> build(key, currentFace));

			if (quads == null) {
				quads = new ArrayList<>(faceQuads.size() * 2);
			}

			quads.addAll(faceQuads);
		}

		if (quads != null && !quads.isEmpty()) {
			parts.add(new PaintPart(quads));
		}

		// A canvas that has been replaced is unreachable from the store, so its entry would
		// otherwise live until the world unloads. Dropping the whole cache when it grows past
		// the bound costs one rebuild's worth of work and keeps the ceiling flat.
		if (CACHE.size() > MAX_CACHED_CANVASES) {
			CACHE.clear();
		}
	}

	private static List<BakedQuad> build(Canvas canvas, int face) {
		Direction direction = Direction.from3DDataValue(face);
		List<PaintQuad> rectangles = CanvasMesher.mesh(canvas.texels(), face);
		List<BakedQuad> quads = new ArrayList<>(rectangles.size());
		float[] corners = new float[12];

		for (PaintQuad rectangle : rectangles) {
			PaintGeometry.corners(rectangle, corners);

			MutableQuad quad = new MutableQuad();

			for (int vertex = 0; vertex < 4; vertex++) {
				quad.setPosition(vertex,
					corners[vertex * 3], corners[vertex * 3 + 1], corners[vertex * 3 + 2]);
				quad.setUvFromSprite(vertex, uOf(vertex), vOf(vertex));
			}

			quad.setSprite(PaintSprites.paint(), ChunkSectionLayer.CUTOUT, null);
			quad.setDirection(direction);
			quad.setColor(rectangle.argb());
			quad.setTintIndex(-1);
			quad.setShade(true);

			quads.add(quad.toBakedQuad());
		}

		return List.copyOf(quads);
	}

	private static float uOf(int vertex) {
		return vertex == 0 || vertex == 3 ? 0.0F : 1.0F;
	}

	private static float vOf(int vertex) {
		return vertex < 2 ? 1.0F : 0.0F;
	}

	/**
	 * One model part holding every paint quad on this block.
	 *
	 * <p>All quads are returned for the {@code null} direction - the unculled bucket - because a
	 * paint quad floats {@code DECAL_OFFSET} off the surface. Filing it under its own face would
	 * make it cull with that face, so paint would vanish exactly when the block is up against
	 * another one, which is most of a wall.
	 */
	private record PaintPart(List<BakedQuad> quads) implements BlockStateModelPart {
		@Override
		public List<BakedQuad> getQuads(Direction direction) {
			return direction == null ? quads : List.of();
		}

		@Override
		public boolean useAmbientOcclusion() {
			return true;
		}

		@Override
		public Material.Baked particleMaterial() {
			return new Material.Baked(PaintSprites.paint(), false);
		}

		@Override
		public int materialFlags() {
			// Paint is opaque cutout and its sprite is not animated, so it contributes neither
			// the translucent nor the animated flag.
			return 0;
		}
	}
}
