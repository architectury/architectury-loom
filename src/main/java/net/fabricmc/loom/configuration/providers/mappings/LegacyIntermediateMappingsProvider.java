/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
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

package net.fabricmc.loom.configuration.providers.mappings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.common.base.Stopwatch;
import dev.architectury.loom.util.TempFiles;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.intermediate.IntermediateMappingsProvider;
import net.fabricmc.loom.configuration.providers.forge.minecraft.ForgeMinecraftProvider;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.stitch.commands.CommandGenerateIntermediary;

public abstract class LegacyIntermediateMappingsProvider extends IntermediateMappingsProvider {
	@Override
	public void provide(Path tinyMappings, Project project) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (Files.exists(tinyMappings)) {
			return;
		}

		if (!extension.isLegacyForge()) throw new IllegalStateException("Legacy Intermediates are only for legacy forge!");
		if (extension.getMinecraftProvider().provideClient() && extension.getMinecraftProvider().provideServer() && ((ForgeMinecraftProvider) extension.getMinecraftProvider()).getPatchedProvider().getMinecraftSrgJar() == null) throw new IllegalStateException("Merged jar not created!");
		Stopwatch stopwatch = Stopwatch.createStarted();
		project.getLogger().lifecycle(":generating dummy intermediary");

		Path minecraftJar = extension.getMinecraftProvider().provideClient() && extension.getMinecraftProvider().provideServer() ? ((ForgeMinecraftProvider) extension.getMinecraftProvider()).getPatchedProvider().getMinecraftSrgJar() : (extension.getMinecraftProvider().provideClient() ? extension.getMinecraftProvider().getMinecraftClientJar() : extension.getMinecraftProvider().getMinecraftServerJar()).toPath();

		// create a temporary folder into which stitch will output the v1 file
		// we cannot just create a temporary file directly, cause stitch will try to read it if it exists
		TempFiles tmpFolder = new TempFiles();
		Path tinyV1 = tmpFolder.file("intermediary-v1", "tiny");
		tinyV1.toFile().delete(); // delete the file so the command doesn't fail

		CommandGenerateIntermediary command = new CommandGenerateIntermediary();

		try {
			command.run(new String[]{ minecraftJar.toAbsolutePath().toString(), tinyV1.toAbsolutePath().toString() });
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to generate intermediary", e);
		}

		try (MappingWriter writer = MappingWriter.create(tinyMappings, MappingFormat.TINY_2)) {
			MappingReader.read(tinyV1, writer);
		}

		tmpFolder.close();

		project.getLogger().lifecycle(":generated dummy intermediary in " + stopwatch.stop());
	}

	@Override
	public String getName() {
		return "legacy-intermediate";
	}
}
