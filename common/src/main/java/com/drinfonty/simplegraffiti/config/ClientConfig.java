package com.drinfonty.simplegraffiti.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.drinfonty.simplegraffiti.SimpleGraffiti;
import com.drinfonty.simplegraffiti.canvas.Brush;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/**
 * {@code config/simple_graffiti/client.json} (SPEC 9.3): rendering and UX preferences only.
 *
 * <p>No client setting may affect what the server accepts. Brush size is the closest call - it is
 * stored here and sent with each paint request - but the server clamps it to its own
 * {@code maxBrushSize} regardless, so this is a preference, not an authority.
 */
public final class ClientConfig {
	public static final int SCHEMA_VERSION = 1;
	public static final int MAX_RECENT_COLORS = 8;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public int schemaVersion = SCHEMA_VERSION;
	public boolean renderGraffiti = true;
	public int brushSize = Brush.SIZE_MEDIUM;
	public List<String> recentColors = new ArrayList<>();
	public boolean showPaintParticles = true;
	public boolean paletteKeyOpensOnHoldOnly = false;

	public boolean repair() {
		ClientConfig defaults = new ClientConfig();
		boolean repaired = false;

		if (schemaVersion != SCHEMA_VERSION) {
			schemaVersion = SCHEMA_VERSION;
			repaired = true;
		}

		if (!Brush.isValidSize(brushSize)) {
			brushSize = defaults.brushSize;
			repaired = true;
		}

		if (recentColors == null) {
			recentColors = new ArrayList<>();
			repaired = true;
		} else if (recentColors.size() > MAX_RECENT_COLORS) {
			recentColors = new ArrayList<>(recentColors.subList(0, MAX_RECENT_COLORS));
			repaired = true;
		}

		return repaired;
	}

	public static ClientConfig load(Path file) {
		ClientConfig config = read(file);

		if (config.repair()) {
			save(config, file);
		}

		return config;
	}

	private static ClientConfig read(Path file) {
		if (!Files.isRegularFile(file)) {
			ClientConfig config = new ClientConfig();
			save(config, file);
			return config;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			ClientConfig config = GSON.fromJson(reader, ClientConfig.class);
			return config == null ? new ClientConfig() : config;
		} catch (IOException | JsonSyntaxException e) {
			SimpleGraffiti.LOGGER.error("Could not read {}, using defaults: {}", file, e.toString());
			return new ClientConfig();
		}
	}

	public static void save(ClientConfig config, Path file) {
		try {
			Files.createDirectories(file.getParent());

			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			SimpleGraffiti.LOGGER.error("Could not write {}: {}", file, e.toString());
		}
	}
}
