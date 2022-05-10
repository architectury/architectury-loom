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

package net.fabricmc.loom.configuration.sources;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.apache.commons.io.output.NullOutputStream;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.remapper.MercuryRemapper;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.configuration.ShowStacktrace;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.ModCompileRemapper;
import net.fabricmc.loom.task.GenerateSourcesTask;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.SourceRemapper;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.lorenztiny.TinyMappingsReader;

public class ForgeSourcesRemapper {
	public static void addBaseForgeSources(Project project, boolean isOfficial) throws IOException {
		Path sourcesJar = GenerateSourcesTask.getMappedJarFileWithSuffix(project, "-sources.jar", !isOfficial).toPath();

		if (!Files.exists(sourcesJar)) {
			addForgeSources(project, sourcesJar);
		}
	}

	public static void addForgeSources(Project project, Path sourcesJar) throws IOException {
		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(sourcesJar, true)) {
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

			provideForgeSources(project, (path, bytes) -> {
				Path fsPath = delegate.get().getPath(path);

				if (fsPath.getParent() != null) {
					try {
						Files.createDirectories(fsPath.getParent());
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}

				taskCompleter.add(() -> {
					Files.write(fsPath, bytes, StandardOpenOption.CREATE);
				});
			});

			taskCompleter.complete();
		}
	}

	public static void provideForgeSources(Project project, BiConsumer<String, byte[]> consumer) throws IOException {
		List<Path> forgeInstallerSources = new ArrayList<>();

		for (ResolvedArtifact artifact : project.getConfigurations().getByName(Constants.Configurations.FORGE_INSTALLER).getResolvedConfiguration().getResolvedArtifacts()) {
			File forgeInstallerSource = ModCompileRemapper.findSources(project.getDependencies(), artifact);

			if (forgeInstallerSource != null) {
				forgeInstallerSources.add(forgeInstallerSource.toPath());
			}
		}

		LoomGradleExtension extension = LoomGradleExtension.get(project);
		Path legacySourcesZip = null;

		if (extension.isLegacyForge()) {
			Path userdevJar = extension.getForgeUserdevProvider().getUserdevJar().toPath();
			byte[] sourcesZip = ZipUtils.unpack(userdevJar, "sources.zip");

			legacySourcesZip = Files.createTempFile("sources", ".zip");
			legacySourcesZip.toFile().deleteOnExit();
			Files.write(legacySourcesZip, sourcesZip, StandardOpenOption.TRUNCATE_EXISTING);
			forgeInstallerSources.add(legacySourcesZip);
		}

		project.getLogger().lifecycle(":found {} forge source jars", forgeInstallerSources.size());
		Map<String, byte[]> forgeSources = extractSources(forgeInstallerSources);
		project.getLogger().lifecycle(":extracted {} forge source classes", forgeSources.size());
		remapSources(project, forgeSources);
		forgeSources.forEach(consumer);

		if (legacySourcesZip != null) {
			Files.delete(legacySourcesZip);
		}
	}

	private static void remapSources(Project project, Map<String, byte[]> sources) throws IOException {
		File tmpInput = File.createTempFile("tmpInputForgeSources", null);
		tmpInput.delete();
		tmpInput.deleteOnExit();
		File tmpOutput = File.createTempFile("tmpOutputForgeSources", null);
		tmpOutput.delete();
		tmpOutput.deleteOnExit();

		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(tmpInput, true)) {
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

			for (Map.Entry<String, byte[]> entry : sources.entrySet()) {
				Path path = delegate.get().getPath(entry.getKey());

				if (path.getParent() != null) {
					Files.createDirectories(path.getParent());
				}

				taskCompleter.add(() -> {
					Files.write(path, entry.getValue(), StandardOpenOption.CREATE);
				});
			}

			taskCompleter.complete();
		}

		PrintStream out = System.out;
		PrintStream err = System.err;

		if (project.getGradle().getStartParameter().getShowStacktrace() == ShowStacktrace.INTERNAL_EXCEPTIONS && project.getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) >= 0) {
			System.setOut(new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM));
			System.setErr(new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM));
		}

		remapForgeSourcesInner(project, tmpInput.toPath(), tmpOutput.toPath());

		if (project.getGradle().getStartParameter().getShowStacktrace() == ShowStacktrace.INTERNAL_EXCEPTIONS && project.getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) >= 0) {
			System.setOut(out);
			System.setErr(err);
		}

		tmpInput.delete();
		int[] failedToRemap = {0};

		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(tmpOutput, false)) {
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

			for (Map.Entry<String, byte[]> entry : new HashSet<>(sources.entrySet())) {
				taskCompleter.add(() -> {
					Path path = delegate.get().getPath(entry.getKey());

					if (Files.exists(path)) {
						sources.put(entry.getKey(), Files.readAllBytes(path));
					} else {
						sources.remove(entry.getKey());
						project.getLogger().error("Failed to remap sources for " + entry.getKey());
						failedToRemap[0]++;
					}
				});
			}

			taskCompleter.complete();
		}

		tmpOutput.delete();

		if (failedToRemap[0] > 0) {
			project.getLogger().error("Failed to remap {} forge sources", failedToRemap[0]);
		}
	}

	private static void remapForgeSourcesInner(Project project, Path tmpInput, Path tmpOutput) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		Mercury mercury = SourceRemapper.createMercuryWithClassPath(project, false);

		MappingSet mappings = new TinyMappingsReader(extension.getMappingsProvider().getMappingsWithSrg(), "srg", "named").read();

		for (Map.Entry<String, String> entry : TinyRemapperHelper.JSR_TO_JETBRAINS.entrySet()) {
			mappings.getOrCreateClassMapping(entry.getKey()).setDeobfuscatedName(entry.getValue());
		}

		Set<File> files = project.getConfigurations()
				.detachedConfiguration(project.getDependencies().create(Constants.Dependencies.JETBRAINS_ANNOTATIONS + Constants.Dependencies.Versions.JETBRAINS_ANNOTATIONS))
				.resolve();

		for (File file : files) {
			mercury.getClassPath().add(file.toPath());
		}

		// Distinct and add the srg jar at the top, so it gets prioritized
		mercury.getClassPath().add(0, extension.getMinecraftMappedProvider().getSrgJar().toPath());

		if (extension.isForgeAndNotOfficial()) {
			mercury.getClassPath().add(0, extension.getMinecraftMappedProvider().getForgeSrgJar().toPath());
		}

		List<Path> newClassPath = mercury.getClassPath().stream()
				.distinct()
				.filter(Files::isRegularFile)
				.collect(Collectors.toList());
		mercury.getClassPath().clear();
		mercury.getClassPath().addAll(newClassPath);

		mercury.getProcessors().add(MercuryRemapper.create(mappings));
		boolean isSrcTmp = false;

		if (!Files.isDirectory(tmpInput)) {
			Path tmpInput1 = tmpInput;
			// create tmp directory
			isSrcTmp = true;
			tmpInput = Files.createTempDirectory("fabric-loom-src");
			ZipUtils.unpackAll(tmpInput1, tmpInput);
		}

		try (FileSystemUtil.Delegate outputFs = FileSystemUtil.getJarFileSystem(tmpOutput, true)) {
			Path outputFsRoot = outputFs.get().getPath("/");
			mercury.rewrite(tmpInput, outputFsRoot);
		} catch (Exception e) {
			project.getLogger().warn("Could not remap " + tmpInput + " fully!", e);
		}

		if (isSrcTmp) {
			Files.walkFileTree(tmpInput, new DeletingFileVisitor());
		}
	}

	private static Map<String, byte[]> extractSources(List<Path> forgeInstallerSources) throws IOException {
		Map<String, byte[]> sources = new ConcurrentHashMap<>();
		ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

		for (Path path : forgeInstallerSources) {
			FileSystemUtil.Delegate system = FileSystemUtil.getJarFileSystem(path, false);
			taskCompleter.onComplete(stopwatch -> system.close());

			for (Path filePath : (Iterable<? extends Path>) Files.walk(system.get().getPath("/"))::iterator) {
				if (Files.isRegularFile(filePath) && filePath.getFileName().toString().endsWith(".java")) {
					taskCompleter.add(() -> sources.put(filePath.toString(), Files.readAllBytes(filePath)));
				}
			}
		}

		taskCompleter.complete();
		return sources;
	}
}
