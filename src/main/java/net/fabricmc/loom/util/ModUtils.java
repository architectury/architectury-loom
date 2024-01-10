/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2022 FabricMC
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

package net.fabricmc.loom.util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import com.google.gson.JsonObject;
import org.gradle.api.logging.Logger;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradlePlugin;

public final class ModUtils {
	private ModUtils() {
	}

	public static boolean isMod(File file, ModPlatform platform) {
		return isMod(file.toPath(), platform);
	}

	public static boolean isMod(Path input, ModPlatform platform) {
		if (platform == ModPlatform.FORGE) {
			return ZipUtils.contains(input, "META-INF/mods.toml");
		} else if (platform == ModPlatform.QUILT) {
			return ZipUtils.contains(input, "quilt.mod.json") || isMod(input, ModPlatform.FABRIC);
		}

		return ZipUtils.contains(input, "fabric.mod.json");
	}

	@Nullable
	public static JsonObject getFabricModJson(Path path) {
		final byte[] modJsonBytes;

		try {
			modJsonBytes = ZipUtils.unpackNullable(path, "fabric.mod.json");
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to extract fabric.mod.json from " + path, e);
		}

		if (modJsonBytes == null) {
			return null;
		}

		return LoomGradlePlugin.GSON.fromJson(new String(modJsonBytes, StandardCharsets.UTF_8), JsonObject.class);
	}

	public static boolean shouldRemapMod(Logger logger, File input, Object id, ModPlatform platform, String config) {
		if (ZipUtils.contains(input.toPath(), "architectury.common.marker")) return true;
		if (isMod(input, platform)) return true;

		if (platform == ModPlatform.FORGE) {
			logger.lifecycle(":could not find forge mod in " + config + " but forcing: {}", id);
			return true;
		}

		return false;
	}

	public static void validateJarManifest(Path path) throws IOException {
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(path)) {
			final Path manifestPath = fs.get().getPath("META-INF/MANIFEST.MF");

			if (Files.exists(manifestPath)) {
				final var manifest = new Manifest(new ByteArrayInputStream(Files.readAllBytes(manifestPath)));
				final Attributes mainAttributes = manifest.getMainAttributes();

				// Check to see if the jar was built with a newer version of loom.
				// This version of loom does not support the remap type value so throw an exception.
				if (mainAttributes.getValue("Fabric-Loom-Mixin-Remap-Type") != null) {
					throw new IllegalStateException("This version of loom does not support the mixin remap type value. Please update to the latest version of loom.");
				}
			}
		}
	}
}
