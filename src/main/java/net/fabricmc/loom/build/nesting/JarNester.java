/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018-2021 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.build.nesting;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Comparator;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.VisibleForTesting;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.util.Check;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.ZipReprocessorUtil;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;

public class JarNester {
	private static final Logger LOGGER = LoggerFactory.getLogger(JarNester.class);
	private static final Gson GSON = new Gson();

	@VisibleForTesting
	public static void nestJars(Collection<File> jars, File modJar) {
		nestJars(jars, modJar, ModPlatform.FABRIC);
	}

	public static void nestJars(Collection<File> jars, File modJar, ModPlatform platform) {
		if (jars.isEmpty()) {
			LOGGER.debug("Nothing to nest into {}", modJar.getName());
			return;
		}

		Check.require(FabricModJsonFactory.isNestableModJar(modJar, platform), "Cannot nest jars into none mod jar " + modJar.getName());

		// Ensure deterministic ordering of entries in fabric.mod.json
		Collection<File> sortedJars = jars.stream().sorted(Comparator.comparing(File::getName)).toList();

		try {
			for (File file : sortedJars) {
				String nestedJarPath = "META-INF/jars/" + file.getName();
				Check.require(FabricModJsonFactory.isNestableModJar(file, platform), "Cannot nest none mod jar: " + file.getName());

				try (var is = Files.newInputStream(file.toPath())) {
					ZipReprocessorUtil.appendZipEntry(modJar.toPath(), nestedJarPath, is);
				}

				LOGGER.debug("Nested {} into {}", nestedJarPath, modJar.getName());
			}

			if (platform.isForgeLike()) {
				handleForgeJarJar(jars, modJar);
				return;
			} else if (platform == ModPlatform.QUILT) {
				ZipReprocessorUtil.transformZipEntry(modJar.toPath(), "quilt.mod.json", bytes -> {
					JsonObject json = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
					JsonObject loader;

					if (json.has("quilt_loader")) {
						loader = json.getAsJsonObject("quilt_loader");
					} else {
						json.add("quilt_loader", loader = new JsonObject());
					}

					JsonArray nestedJars = loader.has("jars") ? loader.getAsJsonArray("jars") : new JsonArray();

					for (File file : sortedJars) {
						String nestedJarPath = "META-INF/jars/" + file.getName();
						nestedJars.add(nestedJarPath);
					}

					loader.add("jars", nestedJars);
					return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
				});
				return;
			}

			ZipReprocessorUtil.transformZipEntry(modJar.toPath(), "fabric.mod.json", bytes -> {
				JsonObject json = GSON.fromJson(new String(bytes), JsonObject.class);
				JsonArray nestedJars = json.has("jars") ? json.getAsJsonArray("jars") : new JsonArray();

				for (File file : sortedJars) {
					String nestedJarPath = "META-INF/jars/" + file.getName();
					JsonObject entry = new JsonObject();
					entry.addProperty("file", nestedJarPath);
					nestedJars.add(entry);
				}

				json.add("jars", nestedJars);
				return GSON.toJson(json).getBytes();
			});
		} catch (IOException e) {
			throw new java.io.UncheckedIOException("Failed to nest jars into " + modJar.getName(), e);
		}
	}

	private static NestableJarGenerationTask.@Nullable Metadata readNestedFile(File file) {
		try {
			return ZipUtils.unpackGsonNullable(file.toPath(), NestableJarGenerationTask.NESTING_METADATA_PATH, NestableJarGenerationTask.Metadata.class);
		} catch (IOException e) {
			LOGGER.error("Could not read {}", file.getAbsolutePath(), e);
			return null;
		}
	}

	private static void handleForgeJarJar(Collection<File> jars, File modJar) throws IOException {
		JsonObject json = new JsonObject();
		JsonArray nestedJars = new JsonArray();

		for (File file : jars) {
			NestableJarGenerationTask.Metadata metadata = readNestedFile(file);

			if (metadata == null) {
				LOGGER.error("Jar {} does not contain Loom nesting metadata", file.getAbsolutePath());
				continue;
			}

			String nestedJarPath = "META-INF/jars/" + file.getName();

			for (JsonElement nestedJar : nestedJars) {
				JsonObject jsonObject = nestedJar.getAsJsonObject();

				if (jsonObject.has("path") && jsonObject.get("path").getAsString().equals(nestedJarPath)) {
					throw new IllegalStateException("Cannot nest 2 jars at the same path: " + nestedJarPath);
				}
			}

			JsonObject jsonObject = new JsonObject();
			JsonObject identifierObject = new JsonObject();
			JsonObject versionObject = new JsonObject();
			identifierObject.addProperty("group", metadata.group());
			identifierObject.addProperty("artifact", metadata.name());
			versionObject.addProperty("range", "[" + metadata.version() + ",)");
			versionObject.addProperty("artifactVersion", metadata.version());
			jsonObject.add("identifier", identifierObject);
			jsonObject.add("version", versionObject);
			jsonObject.addProperty("path", nestedJarPath);
			nestedJars.add(jsonObject);
		}

		json.add("jars", nestedJars);

		ZipReprocessorUtil.appendZipEntry(modJar.toPath(), "META-INF/jarjar/metadata.json", LoomGradlePlugin.GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
	}
}
