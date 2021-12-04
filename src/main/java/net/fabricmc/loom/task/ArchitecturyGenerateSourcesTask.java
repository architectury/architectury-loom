/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.inject.Inject;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.api.decompilers.DecompilationMetadata;
import net.fabricmc.loom.api.decompilers.architectury.ArchitecturyLoomDecompiler;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.OperatingSystem;

public abstract class ArchitecturyGenerateSourcesTask extends AbstractLoomTask {
	private final ArchitecturyLoomDecompiler decompiler;

	@InputFile
	public abstract RegularFileProperty getInputJar();

	@Input
	public abstract MapProperty<String, String> getOptions();

	@Inject
	public ArchitecturyGenerateSourcesTask(ArchitecturyLoomDecompiler decompiler) {
		this.decompiler = decompiler;
		getOutputs().upToDateWhen((o) -> false);
		getOptions().finalizeValueOnRead();
	}

	@TaskAction
	public void run() throws IOException {
		if (!OperatingSystem.is64Bit()) {
			throw new UnsupportedOperationException("GenSources task requires a 64bit JVM to run due to the memory requirements.");
		}

		// TODO: Need a good way to not keep a duplicated code for this
		Path compiledJar = getInputJar().get().getAsFile().toPath();
		Path runtimeJar = getExtension().getMappingsProvider().mappedProvider.getMappedJar().toPath();
		Path sourcesDestination = GenerateSourcesTask.getMappedJarFileWithSuffix(getProject(), "-sources.jar").toPath();
		Path linemapDestination = GenerateSourcesTask.getMappedJarFileWithSuffix(getProject(), "-sources.lmap").toPath();
		DecompilationMetadata metadata = new DecompilationMetadata(
				Runtime.getRuntime().availableProcessors(),
				GenerateSourcesTask.getMappings(getProject(), getExtension()),
				GenerateSourcesTask.DecompileAction.toPaths(getProject().getConfigurations().getByName(Constants.Configurations.MINECRAFT_DEPENDENCIES)),
				getLogger()::info,
				getOptions().get()
		);

		decompiler.create(getProject()).decompile(compiledJar, sourcesDestination, linemapDestination, metadata);

		// Apply linemap
		if (Files.exists(linemapDestination)) {
			Path linemapJar = GenerateSourcesTask.getMappedJarFileWithSuffix(getProject(), "-linemapped.jar").toPath();

			try {
				GenerateSourcesTask.DecompileAction.remapLineNumbers(getLogger()::info, runtimeJar, linemapDestination, linemapJar);
				Files.copy(linemapJar, runtimeJar, StandardCopyOption.REPLACE_EXISTING);
				Files.delete(linemapJar);
			} catch (Exception e) {
				throw new RuntimeException("Could not remap line numbers", e);
			}
		}
	}
}
