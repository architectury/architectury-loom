/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2025 FabricMC
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import codechicken.diffpatch.cli.CliOperation;
import codechicken.diffpatch.cli.PatchOperation;
import codechicken.diffpatch.util.LoggingOutputStream;
import codechicken.diffpatch.util.PatchMode;
import dev.architectury.loom.accesstransformer.AccessTransformerService;
import dev.architectury.loom.forge.ForgeSourcesService;
import dev.architectury.loom.forge.minecraft.MinecraftPatchedProvider;
import dev.architectury.loom.forge.tool.ForgeToolService;
import dev.architectury.loom.forge.tool.ForgeTools;
import dev.architectury.loom.mcpconfig.McpExecutor;
import dev.architectury.loom.mcpconfig.McpExecutorBuilder;
import dev.architectury.loom.mcpconfig.steplogic.ConstantLogic;
import dev.architectury.loom.util.DependencyDownloader;
import dev.architectury.loom.util.Stopwatch;
import dev.architectury.loom.util.TempFiles;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.task.service.MappingsService;
import net.fabricmc.loom.task.service.SourceRemapperService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.service.ScopedServiceFactory;
import net.fabricmc.loom.util.service.ServiceFactory;

// TODO: NeoForge support
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

	@OutputFile
	protected abstract RegularFileProperty getSideAnnotationStrippedMinecraftJar();

	@Nested
	protected abstract Property<ForgeSourcesService.Options> getForgeSourcesOptions();

	@Nested
	protected abstract Property<McpExecutor.Options> getMcpExecutorOptions();

	@Nested
	protected abstract Property<AccessTransformerService.Options> getAccessTransformerOptions();

	@Nested
	protected abstract Property<ForgeToolService.Options> getToolServiceOptions();

	@Nested
	protected abstract Property<SourceRemapperService.Options> getSourceRemapperOptions();

	@Nested
	protected abstract Property<SasOptions> getSasOptions();

	@Input
	protected abstract Property<String> getPatchPathInZip();

	@Input
	protected abstract Property<String> getPatchesOriginalPrefix();

	@Input
	protected abstract Property<String> getPatchesModifiedPrefix();

	@Internal
	protected abstract Property<TempFiles> getTempFiles();

	public GenerateForgePatchedSourcesTask() {
		getOutputs().upToDateWhen((o) -> false);
		getOutputJar().fileProvider(getProject().provider(() -> GenerateSourcesTask.getJarFileWithSuffix(getRuntimeJar(), "-sources.jar")));
		getForgeSourcesOptions().convention(ForgeSourcesService.createOptions(getProject()));

		final TempFiles tempFiles = new TempFiles();
		getTempFiles().value(tempFiles).finalizeValue();
		final Path cache;

		try {
			cache = tempFiles.directory("mcp-cache");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		getSideAnnotationStrippedMinecraftJar().set(cache.resolve("side-annotation-stripped.jar").toFile());
		getMcpExecutorOptions().convention(getProject().provider(() -> {
			MinecraftPatchedProvider patchedProvider = MinecraftPatchedProvider.get(getProject());
			McpExecutorBuilder mcp = patchedProvider.createMcpExecutor(cache);
			mcp.setStepLogicProvider((setupContext, name, type) -> {
				if (name.equals("rename")) {
					return ConstantLogic.createOptions(setupContext, () -> getSideAnnotationStrippedMinecraftJar().get().getAsFile().toPath());
				}

				return null;
			});
			mcp.enqueue("decompile");
			mcp.enqueue("patch");

			try {
				return mcp.build();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}).flatMap(o -> o));
		getAccessTransformerOptions().convention(AccessTransformerService.createOptionsForLoaderAts(getProject(), tempFiles));
		getToolServiceOptions().convention(ForgeToolService.createOptions(getProject()));

		final SasOptions sasOptions = getProject().getObjects().newInstance(SasOptions.class);
		sasOptions.getUserdevJar().set(getExtension().getForgeUserdevProvider().getUserdevJar());
		sasOptions.getSass().set(getExtension().getForgeUserdevProvider().getConfig().sass());
		sasOptions.getClasspath().from(DependencyDownloader.download(getProject(), ForgeTools.SIDE_STRIPPER, false, false));
		getSasOptions().set(sasOptions);

		getPatchPathInZip().set(getExtension().getForgeUserdevProvider().getConfig().patches());
		getPatchesOriginalPrefix().set(getExtension().getForgeUserdevProvider().getConfig().patchesOriginalPrefix().orElseThrow());
		getPatchesModifiedPrefix().set(getExtension().getForgeUserdevProvider().getConfig().patchesModifiedPrefix().orElseThrow());

		getSourceRemapperOptions().set(SourceRemapperService.TYPE.create(getProject(), sro -> {
			sro.getMappings().set(MappingsService.createOptionsWithProjectMappings(
					getProject(),
					getProject().provider(() -> "srg"),
					getProject().provider(() -> "named")
			));
			sro.getJavaCompileRelease().set(SourceRemapperService.getJavaCompileRelease(getProject()));
			sro.getClasspath().from(getProject().getConfigurations().getByName(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES));
		}));
	}

	@TaskAction
	public void run() throws IOException {
		try (var tempFiles = getTempFiles().get(); var serviceFactory = new ScopedServiceFactory()) {
			Path cache = tempFiles.directory("loom-decompilation");

			// Transform game jar before decompiling
			Path accessTransformed = cache.resolve("access-transformed.jar");
			AccessTransformerService atService = serviceFactory.get(getAccessTransformerOptions());
			atService.execute(getInputJar().get().getAsFile().toPath(), accessTransformed);
			Path sideAnnotationStripped = getSideAnnotationStrippedMinecraftJar().get().getAsFile().toPath();
			stripSideAnnotations(accessTransformed, sideAnnotationStripped, serviceFactory);

			// Step 1: decompile and patch with MCP patches
			Path rawDecompiled = decompileAndPatch(serviceFactory);
			// Step 2: patch with Forge patches
			getLogger().lifecycle(":applying Forge patches");
			Path patched = sourcePatch(cache, rawDecompiled);
			// Step 3: remap
			remap(patched, serviceFactory);
			// Step 4: add Forge's own sources
			final ForgeSourcesService sourcesService = serviceFactory.get(getForgeSourcesOptions());
			sourcesService.addForgeSources(null, getOutputJar().get().getAsFile().toPath());
		}
	}

	private Path decompileAndPatch(ScopedServiceFactory serviceFactory) throws IOException {
		final McpExecutor executor = serviceFactory.get(getMcpExecutorOptions());
		return executor.execute();
	}

	private Path sourcePatch(Path cache, Path rawDecompiled) throws IOException {
		String patchPathInZip = getPatchPathInZip().get();
		Path output = cache.resolve("patched.jar");
		Path rejects = cache.resolve("rejects");

		CliOperation.Result<PatchOperation.PatchesSummary> result = PatchOperation.builder()
				.logTo(new LoggingOutputStream(getLogger(), LogLevel.INFO))
				.basePath(rawDecompiled)
				.patchesPath(getSasOptions().get().getUserdevJar().get().getAsFile().toPath())
				.patchesPrefix(patchPathInZip)
				.outputPath(output)
				.mode(PatchMode.ACCESS)
				.rejectsPath(rejects)
				.aPrefix(getPatchesOriginalPrefix().get())
				.bPrefix(getPatchesModifiedPrefix().get())
				.build()
				.operate();

		if (result.exit != 0) {
			throw new RuntimeException("Could not patch " + rawDecompiled + "; rejects saved to " + rejects.toAbsolutePath());
		}

		return output;
	}

	private void remap(Path input, ServiceFactory serviceFactory) throws IOException {
		final SourceRemapperService remapperService = serviceFactory.get(getSourceRemapperOptions());
		remapperService.remapSourcesJar(input, getOutputJar().get().getAsFile().toPath());
	}

	private void stripSideAnnotations(Path input, Path output, ServiceFactory serviceFactory) throws IOException {
		final Stopwatch stopwatch = Stopwatch.createStarted();
		getLogger().lifecycle(":stripping side annotations");

		try (var tempFiles = new TempFiles()) {
			final List<String> sass = getSasOptions().get().getSass().get();
			final List<Path> sasPaths = new ArrayList<>();

			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(getSasOptions().get().getUserdevJar().get().getAsFile(), false)) {
				for (String sasPath : sass) {
					try {
						final Path from = fs.getPath(sasPath);
						final Path to = tempFiles.file(null, ".sas");
						Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
						sasPaths.add(to);
					} catch (IOException e) {
						throw new IOException("Could not extract SAS " + sasPath);
					}
				}
			}

			final ForgeToolService toolService = serviceFactory.get(getToolServiceOptions());
			toolService.exec(spec -> {
				spec.setClasspath(getSasOptions().get().getClasspath());
				spec.args(
						"--strip",
						"--input", input.toAbsolutePath().toString(),
						"--output", output.toAbsolutePath().toString()
				);

				for (Path sasPath : sasPaths) {
					spec.args("--data", sasPath.toAbsolutePath().toString());
				}
			});
		}

		getLogger().lifecycle(":side annotations stripped in " + stopwatch.stop());
	}

	public interface SasOptions {
		@InputFile
		RegularFileProperty getUserdevJar();

		@Input
		ListProperty<String> getSass();

		@Classpath
		ConfigurableFileCollection getClasspath();
	}
}
