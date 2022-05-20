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

package net.fabricmc.loom.configuration.providers.forge.mcpconfig;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.gradle.api.Action;
import org.gradle.api.logging.Logger;
import org.gradle.process.JavaExecSpec;

import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.HashedDownloadUtil;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.function.CollectionUtil;

/**
 * The logic for executing a step. This corresponds to the {@code type} key in the step JSON format.
 */
public interface StepLogic {
	void execute(ExecutionContext context) throws IOException;

	default String getDisplayName(String stepName) {
		return stepName;
	}

	interface ExecutionContext {
		Logger logger();
		Path setOutput(String fileName) throws IOException;
		Path setOutput(Path output);
		/** Mappings extracted from {@code data.mappings} in the MCPConfig JSON. */
		Path mappings();
		String resolve(ConfigValue value);
		Path download(String url) throws IOException;
		void javaexec(Action<? super JavaExecSpec> configurator);
		Set<File> getMinecraftLibraries();

		default List<String> resolve(List<ConfigValue> configValues) {
			return CollectionUtil.map(configValues, this::resolve);
		}
	}

	/**
	 * Runs a Forge tool configured by a {@linkplain McpConfigFunction function}.
	 */
	final class OfFunction implements StepLogic {
		private final McpConfigFunction function;

		public OfFunction(McpConfigFunction function) {
			this.function = function;
		}

		@Override
		public void execute(ExecutionContext context) throws IOException {
			context.setOutput("output");
			Path jar = context.download(function.getDownloadUrl());
			String mainClass;

			try (JarFile jarFile = new JarFile(jar.toFile())) {
				mainClass = jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
			} catch (IOException e) {
				throw new IOException("Could not determine main class for " + jar.toAbsolutePath(), e);
			}

			context.javaexec(spec -> {
				spec.classpath(jar);
				spec.getMainClass().set(mainClass);
				spec.args(context.resolve(function.args()));
				spec.jvmArgs(context.resolve(function.jvmArgs()));
			});
		}

		@Override
		public String getDisplayName(String stepName) {
			return stepName + " with " + function.version();
		}
	}

	/**
	 * Strips certain classes from the jar.
	 */
	final class Strip implements StepLogic {
		@Override
		public void execute(ExecutionContext context) throws IOException {
			Set<String> filter = Files.readAllLines(context.mappings(), StandardCharsets.UTF_8).stream()
					.filter(s -> !s.startsWith("\t"))
					.map(s -> s.split(" ")[0] + ".class")
					.collect(Collectors.toSet());

			Path input = Path.of(context.resolve(new ConfigValue.Variable("input")));

			try (FileSystemUtil.Delegate output = FileSystemUtil.getJarFileSystem(context.setOutput("stripped.jar"), true)) {
				try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(input, false)) {
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
					}

					completer.complete();
				}
			}
		}

		private static String trimLeadingSlash(String string) {
			if (string.startsWith(File.separator)) {
				return string.substring(File.separator.length());
			} else if (string.startsWith("/")) {
				return string.substring(1);
			}

			return string;
		}
	}

	/**
	 * Lists the Minecraft libraries into the output file.
	 */
	final class ListLibraries implements StepLogic {
		@Override
		public void execute(ExecutionContext context) throws IOException {
			try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(context.setOutput("libraries.txt")))) {
				for (File lib : context.getMinecraftLibraries()) {
					writer.println("-e=" + lib.getAbsolutePath());
				}
			}
		}
	}

	/**
	 * Downloads a file from the Minecraft version metadata.
	 */
	final class DownloadManifestFile implements StepLogic {
		private final MinecraftVersionMeta.Download download;

		public DownloadManifestFile(MinecraftVersionMeta.Download download) {
			this.download = download;
		}

		@Override
		public void execute(ExecutionContext context) throws IOException {
			HashedDownloadUtil.downloadIfInvalid(new URL(download.url()), context.setOutput("output").toFile(), download.sha1(), context.logger(), false);
		}
	}

	/**
	 * A no-op step logic that is used for steps automatically executed by Loom earlier.
	 */
	final class NoOp implements StepLogic {
		@Override
		public void execute(ExecutionContext context) throws IOException {
		}
	}

	/**
	 * A no-op step logic that is used for steps automatically executed by Loom earlier.
	 * This one returns a file.
	 */
	final class NoOpWithFile implements StepLogic {
		private final Supplier<Path> path;

		public NoOpWithFile(Supplier<Path> path) {
			this.path = path;
		}

		@Override
		public void execute(ExecutionContext context) throws IOException {
			context.setOutput(path.get());
		}
	}
}
