/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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
import java.util.Optional;

import codechicken.diffpatch.cli.CliOperation;
import codechicken.diffpatch.cli.PatchOperation;
import codechicken.diffpatch.util.LoggingOutputStream;
import codechicken.diffpatch.util.PatchMode;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.configuration.providers.forge.ForgeUserdevProvider;
import net.fabricmc.loom.configuration.providers.forge.MinecraftPatchedProvider;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpExecutor;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.ConstantLogic;
import net.fabricmc.loom.configuration.sources.ForgeSourcesRemapper;
import net.fabricmc.loom.util.SourceRemapper;

public abstract class GenerateForgePatchedSourcesTask extends AbstractLoomTask {
	/**
	 * The SRG Minecraft file produced by the MCP executor.
	 */
	@InputFile
	public abstract RegularFileProperty getInputJar();

	/**
	 * The runtime Minecraft file.
	 */
	@InputFile
	public abstract RegularFileProperty getRuntimeJar();

	/**
	 * The source jar.
	 */
	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	public GenerateForgePatchedSourcesTask() {
		getOutputs().upToDateWhen((o) -> false);
		getOutputJar().fileProvider(getProject().provider(() -> GenerateSourcesTask.getMappedJarFileWithSuffix(getRuntimeJar(), "-sources.jar")));
	}

	@TaskAction
	public void run() throws IOException {
		Path cache = Files.createTempDirectory("loom-decompilation");
		// Step 1: decompile and patch with MCP patches
		Path rawDecompiled = decompileAndPatch(cache);
		// Step 2: patch with Forge patches
		getLogger().lifecycle(":applying Forge patches");
		Path patched = sourcePatch(cache, rawDecompiled);
		// Step 3: remap
		remap(patched);
		// Step 4: add Forge's own sources
		ForgeSourcesRemapper.addForgeSources(getProject(), getOutputJar().get().getAsFile().toPath());
	}

	private Path decompileAndPatch(Path cache) throws IOException {
		Path mcpCache = cache.resolve("mcp");
		Files.createDirectory(mcpCache);

		MinecraftPatchedProvider patchedProvider = MinecraftPatchedProvider.get(getProject());
		McpExecutor mcp = patchedProvider.createMcpExecutor(mcpCache);
		mcp.setStepLogicProvider((name, type) -> {
			if (name.equals("rename")) {
				return Optional.of(new ConstantLogic(() -> getInputJar().get().getAsFile().toPath()));
			}

			return Optional.empty();
		});
		mcp.enqueue("decompile");
		mcp.enqueue("patch");
		return mcp.execute();
	}

	private Path sourcePatch(Path cache, Path rawDecompiled) throws IOException {
		ForgeUserdevProvider userdev = getExtension().getForgeUserdevProvider();
		String patchPathInZip = userdev.getJson().getAsJsonPrimitive("patches").getAsString();
		Path output = cache.resolve("patched.jar");
		Path rejects = cache.resolve("rejects");

		CliOperation.Result<PatchOperation.PatchesSummary> result = PatchOperation.builder()
				.logTo(new LoggingOutputStream(getLogger(), LogLevel.INFO))
				.basePath(rawDecompiled)
				.patchesPath(userdev.getUserdevJar().toPath())
				.patchesPrefix(patchPathInZip)
				.outputPath(output)
				.mode(PatchMode.ACCESS)
				.rejectsPath(rejects)
				.aPrefix(userdev.getJson().getAsJsonPrimitive("patchesOriginalPrefix").getAsString())
				.bPrefix(userdev.getJson().getAsJsonPrimitive("patchesModifiedPrefix").getAsString())
				.build()
				.operate();

		if (result.exit != 0) {
			throw new RuntimeException("Could not patch " + rawDecompiled + "; rejects saved to " + rejects.toAbsolutePath());
		}

		return output;
	}

	private void remap(Path input) {
		SourceRemapper remapper = new SourceRemapper(getProject(), "srg", "named");
		remapper.scheduleRemapSources(input.toFile(), getOutputJar().get().getAsFile(), false, true, () -> {
		});
		remapper.remapAll();
	}
}
