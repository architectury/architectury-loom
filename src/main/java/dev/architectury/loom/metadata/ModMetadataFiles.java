package dev.architectury.loom.metadata;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonGrammar;
import blue.endless.jankson.api.SyntaxError;
import com.google.common.collect.ImmutableMap;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.SourceSet;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

/**
 * Utilities for reading mod metadata files.
 */
public final class ModMetadataFiles {
	private static final Logger LOGGER = Logging.getLogger(ModMetadataFiles.class);
	private static final Map<String, Function<byte[], ModMetadataFile>> SINGLE_FILE_METADATA_TYPES = ImmutableMap.<String, Function<byte[], ModMetadataFile>>builder()
			.put(QuiltModJson.FILE_NAME, QuiltModJson::of)
			.put(QuiltModJson.JSON5_FILE_NAME, convertJson5ToJson(QuiltModJson::of))
			.put(ArchitecturyCommonJson.FILE_NAME, ArchitecturyCommonJson::of)
			.put(ModsToml.FILE_PATH, onError(ModsToml::of, "Could not load mods.toml", () -> new ErroringModMetadataFile("mods.toml")))
			.build();

	private static <A, B> Function<A, B> onError(Function<A, B> fn, String message, Supplier<B> onError) {
		return a -> {
			try {
				return fn.apply(a);
			} catch (Exception e) {
				LOGGER.info(message, e);
				return onError.get();
			}
		};
	}

	private static <R> Function<byte[], R> convertJson5ToJson(Function<byte[], R> next) {
		return bytes -> {
			var text = new String(bytes, StandardCharsets.UTF_8);
			Jankson jankson = Jankson.builder().build();
			String json;
			try {
				json = jankson.fromJson(text, JsonElement.class).toJson(JsonGrammar.STRICT);
			} catch (SyntaxError e) {
				throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Could not read JSON5 file", e);
			}
			return next.apply(json.getBytes(StandardCharsets.UTF_8));
		};
	}

	/**
	 * Reads the mod metadata file from a jar.
	 *
	 * @param jar the path to the jar file
	 * @return the mod metadata file, or {@code null} if not found
	 */
	public static @Nullable ModMetadataFile fromJar(Path jar) throws IOException {
		for (final String filePath : SINGLE_FILE_METADATA_TYPES.keySet()) {
			final byte @Nullable [] bytes = ZipUtils.unpackNullable(jar, filePath);

			if (bytes != null) {
				return SINGLE_FILE_METADATA_TYPES.get(filePath).apply(bytes);
			}
		}

		return null;
	}

	/**
	 * Reads the mod metadata file from a directory.
	 *
	 * @param directory the path to the directory
	 * @return the mod metadata file, or {@code null} if not found
	 */
	public static @Nullable ModMetadataFile fromDirectory(Path directory) throws IOException {
		for (final String filePath : SINGLE_FILE_METADATA_TYPES.keySet()) {
			final Path metadataPath = directory.resolve(filePath);

			if (Files.exists(metadataPath)) {
				return SINGLE_FILE_METADATA_TYPES.get(filePath).apply(Files.readAllBytes(metadataPath));
			}
		}

		return null;
	}

	/**
	 * Reads the first mod metadata file from source sets.
	 *
	 * @param sourceSets the source sets to read from
	 * @return the mod metadata file, or {@code null} if not found
	 */
	public static @Nullable ModMetadataFile fromSourceSets(SourceSet... sourceSets) throws IOException {
		for (final String filePath : SINGLE_FILE_METADATA_TYPES.keySet()) {
			final @Nullable File file = SourceSetHelper.findFirstFileInResource(filePath, sourceSets);

			if (file != null) {
				return SINGLE_FILE_METADATA_TYPES.get(filePath).apply(Files.readAllBytes(file.toPath()));
			}
		}

		return null;
	}
}
