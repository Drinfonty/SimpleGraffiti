package com.drinfonty.simplegraffiti.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.drinfonty.simplegraffiti.SimpleGraffiti;
import com.drinfonty.simplegraffiti.canvas.Brush;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/**
 * {@code config/simple_graffiti/server.json} (SPEC 9.1).
 *
 * <p>Loading tolerates a missing file, an empty file, malformed JSON, unknown fields and
 * out-of-range values: every invalid field falls back to its default and the file is rewritten. A
 * corrupt config must never stop a server starting - an operator who typos a number should get a
 * running server and a log line, not a boot failure at 3am.
 */
public final class ServerConfig {
	/**
	 * Bumped to 2 when the client moved to sampling every tick. A file written by schema 1 caps
	 * sprays at 6/s, which would silently drop most of a 20/s stroke and bring the blob-gaps back,
	 * so those two fields are migrated rather than left to look correct and behave wrongly.
	 */
	public static final int SCHEMA_VERSION = 2;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public int schemaVersion = SCHEMA_VERSION;
	public boolean enabled = true;
	public String permissionMode = PermissionMode.ANYONE.name();
	// Headroom over the client's 20 samples a second. This is a flood bound, not the paint
	// economy - charge drains on its own timer (SPEC 5.2), so a faster limit is not cheaper paint.
	public int spraysPerSecond = 25;
	public int burstSprays = 40;
	public int maxCanvasesPerChunk = 1024;
	public int maxBrushSize = Brush.MAX_SIZE;
	public boolean restrictToTag = false;
	public int chargesPerCan = 64;
	public int spongeDurability = 128;
	public boolean allowErase = true;
	public boolean clearOnBlockBreak = true;

	public enum PermissionMode {
		ANYONE,
		OPS_ONLY,
		/**
		 * Delegates to the server's own "may this player modify this block" check, so
		 * land-protection mods that hook block placement govern painting for free, with no
		 * integration API on either side.
		 */
		BUILD_PERMISSION,
	}

	public PermissionMode permissionMode() {
		try {
			return PermissionMode.valueOf(permissionMode);
		} catch (IllegalArgumentException e) {
			return PermissionMode.ANYONE;
		}
	}

	/**
	 * Clamps every field into range, returning whether anything had to be repaired - in which case
	 * the caller rewrites the file so the operator can see what the server actually believes.
	 */
	public boolean repair() {
		ServerConfig defaults = new ServerConfig();
		boolean repaired = false;

		if (schemaVersion < 2) {
			// Schema 1 predates per-tick sampling; its rate cap would throttle a normal stroke.
			spraysPerSecond = defaults.spraysPerSecond;
			burstSprays = defaults.burstSprays;
			SimpleGraffiti.LOGGER.info("Raised spray rate limits to {}/s for per-tick painting",
				spraysPerSecond);
		}

		if (schemaVersion != SCHEMA_VERSION) {
			schemaVersion = SCHEMA_VERSION;
			repaired = true;
		}

		try {
			PermissionMode.valueOf(permissionMode);
		} catch (RuntimeException e) {
			permissionMode = defaults.permissionMode;
			repaired = true;
		}

		if (spraysPerSecond < 1 || spraysPerSecond > 200) {
			spraysPerSecond = defaults.spraysPerSecond;
			repaired = true;
		}

		if (burstSprays < 1 || burstSprays > 400) {
			burstSprays = defaults.burstSprays;
			repaired = true;
		}

		if (maxCanvasesPerChunk < 1 || maxCanvasesPerChunk > 65536) {
			maxCanvasesPerChunk = defaults.maxCanvasesPerChunk;
			repaired = true;
		}

		if (!Brush.isValidSize(maxBrushSize)) {
			maxBrushSize = defaults.maxBrushSize;
			repaired = true;
		}

		if (chargesPerCan < 1 || chargesPerCan > 32767) {
			chargesPerCan = defaults.chargesPerCan;
			repaired = true;
		}

		if (spongeDurability < 1 || spongeDurability > 32767) {
			spongeDurability = defaults.spongeDurability;
			repaired = true;
		}

		return repaired;
	}

	public static ServerConfig load(Path file) {
		ServerConfig config = read(file);

		if (config.repair()) {
			SimpleGraffiti.LOGGER.warn("Repaired out-of-range values in {}", file);
			save(config, file);
		}

		return config;
	}

	private static ServerConfig read(Path file) {
		if (!Files.isRegularFile(file)) {
			ServerConfig config = new ServerConfig();
			save(config, file);
			return config;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			ServerConfig config = GSON.fromJson(reader, ServerConfig.class);

			// Gson returns null for an empty file rather than throwing, which is exactly the
			// case an operator hits after a disk-full truncation.
			return config == null ? new ServerConfig() : config;
		} catch (IOException | JsonSyntaxException e) {
			SimpleGraffiti.LOGGER.error("Could not read {}, using defaults: {}", file, e.toString());
			return new ServerConfig();
		}
	}

	public static void save(ServerConfig config, Path file) {
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
