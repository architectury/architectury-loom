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

package net.fabricmc.loom.util.srg;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.base.Stopwatch;
import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.configuration.ShowStacktrace;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.forge.McpConfigProvider.RemapAction;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.ThreadingUtils;

public class SpecialSourceExecutor {
	private static String trimLeadingSlash(String string) {
		if (string.startsWith(File.separator)) {
			return string.substring(File.separator.length());
		} else if (string.startsWith("/")) {
			return string.substring(1);
		}

		return string;
	}

	public static void stripJar(Project project, Path inJar, Path outJar, Set<String> filter) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();

		int count = 0;

		try (FileSystemUtil.Delegate output = FileSystemUtil.getJarFileSystem(outJar, true)) {
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(inJar, false)) {
				ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter();

				for (Path path : (Iterable<? extends Path>) Files.walk(fs.get().getPath("/"))::iterator) {
					String trimLeadingSlash = trimLeadingSlash(path.toString());
					if (!trimLeadingSlash.endsWith(".class")) continue;
					boolean has = filter.contains(trimLeadingSlash);
					String s = trimLeadingSlash;

					while (s.contains("$") && !has) {
						s = s.substring(0, s.lastIndexOf("$")) + ".class";
						has = filter.contains(s);
					}

					if (!has) continue;
					Path to = output.get().getPath(trimLeadingSlash);
					Path parent = to.getParent();
					if (parent != null) Files.createDirectories(parent);

					completer.add(() -> {
						Files.copy(path, to, StandardCopyOption.COPY_ATTRIBUTES);
					});
					count++;
				}

				completer.complete();
			}
		} finally {
			project.getLogger().info("Copied " + count + " class files in " + stopwatch.stop());
		}
	}

	public static Path produceSrgJar(RemapAction remapAction, Project project, String side, Set<File> mcLibs, Path officialJar, Path mappings)
					throws Exception {
		Set<String> filter = Files.readAllLines(mappings, StandardCharsets.UTF_8).stream()
				.filter(s -> !s.startsWith("\t"))
				.map(s -> s.split(" ")[0] + ".class")
				.collect(Collectors.toSet());

		LoomGradleExtension extension = LoomGradleExtension.get(project.getProject());
		Path stripped = extension.getFiles().getProjectBuildCache().toPath().resolve(officialJar.getFileName().toString().substring(0, officialJar.getFileName().toString().length() - 4) + "-filtered.jar");
		Files.deleteIfExists(stripped);

		stripJar(project, officialJar, stripped, filter);

		Path output = extension.getFiles().getProjectBuildCache().toPath().resolve(officialJar.getFileName().toString().substring(0, officialJar.getFileName().toString().length() - 4) + "-srg-output.jar");
		Files.deleteIfExists(output);
		Stopwatch stopwatch = Stopwatch.createStarted();

		List<String> args = remapAction.getArgs(stripped, output, mappings, project.files(mcLibs));

		project.getLogger().lifecycle(":remapping minecraft (" + remapAction + ", " + side + ", official -> mojang)");

		Path workingDir = tmpDir();

		project.javaexec(spec -> {
			spec.setArgs(args);
			spec.setClasspath(remapAction.getClasspath());
			spec.workingDir(workingDir.toFile());
			spec.getMainClass().set(remapAction.getMainClass());

			// if running with INFO or DEBUG logging
			if (project.getGradle().getStartParameter().getShowStacktrace() != ShowStacktrace.INTERNAL_EXCEPTIONS
					|| project.getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) < 0) {
				spec.setStandardOutput(System.out);
				spec.setErrorOutput(System.err);
			} else {
				spec.setStandardOutput(NullOutputStream.NULL_OUTPUT_STREAM);
				spec.setErrorOutput(NullOutputStream.NULL_OUTPUT_STREAM);
			}
		}).rethrowFailure().assertNormalExitValue();

		project.getLogger().lifecycle(":remapped minecraft (" + remapAction + ", " + side + ", official -> mojang) in " + stopwatch.stop());

		Files.deleteIfExists(stripped);

		Path tmp = tmpFile();
		Files.deleteIfExists(tmp);
		Files.copy(output, tmp);

		Files.deleteIfExists(output);
		return tmp;
	}

	private static Path tmpFile() throws IOException {
		return Files.createTempFile(null, null);
	}

	private static Path tmpDir() throws IOException {
		return Files.createTempDirectory(null);
	}
}
