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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.LoomGradleExtension;

public class SpecialSourceExecutor {
	public static Path produceSrgJar(Project project, String side, FileCollection specialSourceCp, Path officialJar, Path srgPath)
			throws Exception {
		Set<String> filter = Files.readAllLines(srgPath, StandardCharsets.UTF_8).stream()
				.filter(s -> !s.startsWith("\t"))
				.map(s -> s.split(" ")[0] + ".class")
				.collect(Collectors.toSet());
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		Path stripped = extension.getProjectBuildCache().toPath().resolve(officialJar.getFileName().toString().substring(0, officialJar.getFileName().toString().length() - 4) + "-filtered.jar");
		Files.deleteIfExists(stripped);

		try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(stripped))) {
			ZipUtil.iterate(officialJar.toFile(), (in, zipEntry) -> {
				if (filter.contains(zipEntry.getName())) {
					output.putNextEntry((ZipEntry) zipEntry.clone());
					IOUtils.write(IOUtils.toByteArray(in), output);
					output.closeEntry();
				}
			});
		}

		Path output = extension.getProjectBuildCache().toPath().resolve(officialJar.getFileName().toString().substring(0, officialJar.getFileName().toString().length() - 4) + "-srg-output.jar");
		Files.deleteIfExists(output);

		String[] args = new String[] {
				"--in-jar",
				stripped.toAbsolutePath().toString(),
				"--out-jar",
				output.toAbsolutePath().toString(),
				"--srg-in",
				srgPath.toAbsolutePath().toString()
		};

		project.getLogger().lifecycle(":remapping minecraft (SpecialSource, " + side + ", official -> srg)");

		Path workingDir = tmpDir();

		project.javaexec(spec -> {
			spec.setArgs(Arrays.asList(args));
			spec.setClasspath(specialSourceCp);
			spec.workingDir(workingDir.toFile());
			spec.setMain("net.md_5.specialsource.SpecialSource");

			// if running with INFO or DEBUG logging
			if (project.getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) < 0) {
				spec.setStandardOutput(System.out);
			} else {
				spec.setStandardOutput(NullOutputStream.NULL_OUTPUT_STREAM);
				spec.setErrorOutput(NullOutputStream.NULL_OUTPUT_STREAM);
			}
		}).rethrowFailure().assertNormalExitValue();

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
