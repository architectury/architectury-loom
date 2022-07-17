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

package net.fabricmc.loom.build.nesting;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.task.RemapTaskConfiguration;
import net.fabricmc.loom.util.ModUtils;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.ZipUtils;

public final class IncludedJarFactory {
	private final Project project;

	public IncludedJarFactory(Project project) {
		this.project = project;
	}

	public Provider<ConfigurableFileCollection> getNestedJars(final Configuration configuration) {
		return project.provider(() -> {
			final ConfigurableFileCollection files = project.files();
			final Set<String> visited = Sets.newHashSet();
			final Set<Task> builtBy = Sets.newHashSet();

			files.from(getProjectDeps(configuration, visited, builtBy).stream().map(LazyNestedFile::file).toArray());
			files.from(getFileDeps(configuration, visited, builtBy).stream().map(LazyNestedFile::file).toArray());
			files.builtBy(configuration.getBuildDependencies());
			return files;
		});
	}

	public Provider<Pair<List<LazyNestedFile>, TaskDependency>> getForgeNestedJars(final Configuration configuration) {
		return project.provider(() -> {
			final List<LazyNestedFile> files = new ArrayList<>();
			final Set<String> visited = Sets.newHashSet();
			final Set<Task> builtBy = Sets.newHashSet();

			files.addAll(getProjectDeps(configuration, visited, builtBy));
			files.addAll(getFileDeps(configuration, visited, builtBy));
			return new Pair<>(files, task -> {
				TaskDependency dependencies = configuration.getBuildDependencies();
				Set<Task> tasks = new HashSet<>(dependencies.getDependencies(task));
				tasks.addAll(builtBy);
				return tasks;
			});
		});
	}

	private List<LazyNestedFile> getFileDeps(Configuration configuration, Set<String> visited, Set<Task> builtBy) {
		final List<LazyNestedFile> files = new ArrayList<>();

		final ResolvedConfiguration resolvedConfiguration = configuration.getResolvedConfiguration();
		final Set<ResolvedDependency> dependencies = resolvedConfiguration.getFirstLevelModuleDependencies();

		for (ResolvedDependency dependency : dependencies) {
			if (!visited.add(dependency.getModuleGroup() + ":" + dependency.getModuleName() + ":" + dependency.getModuleVersion())) {
				continue;
			}

			for (ResolvedArtifact artifact : dependency.getModuleArtifacts()) {
				Metadata metadata = new Metadata(
						dependency.getModuleGroup(),
						dependency.getModuleName(),
						dependency.getModuleVersion(),
						artifact.getClassifier()
				);

				files.add(new LazyNestedFile(project, metadata, () -> getNestableJar(artifact.getFile(), metadata)));
			}
		}

		return files;
	}

	private List<LazyNestedFile> getProjectDeps(Configuration configuration, Set<String> visited, Set<Task> builtBy) {
		final List<LazyNestedFile> files = new ArrayList<>();

		for (Dependency dependency : configuration.getDependencies()) {
			if (dependency instanceof ProjectDependency projectDependency) {
				if (!visited.add(dependency.getGroup() + ":" + dependency.getName() + ":" + dependency.getVersion())) {
					continue;
				}

				// Get the outputs of the project
				final Project dependentProject = projectDependency.getDependencyProject();

				Collection<Task> remapJarTasks = dependentProject.getTasksByName(RemapTaskConfiguration.REMAP_JAR_TASK_NAME, false);
				Collection<Task> jarTasks = dependentProject.getTasksByName(JavaPlugin.JAR_TASK_NAME, false);

				if (remapJarTasks.isEmpty() && jarTasks.isEmpty()) {
					throw new UnsupportedOperationException("%s does not have a remapJar or jar task, cannot nest it".formatted(dependentProject.getName()));
				}

				for (Task task : remapJarTasks.isEmpty() ? jarTasks : remapJarTasks) {
					if (task instanceof AbstractArchiveTask archiveTask) {
						final Metadata metadata = new Metadata(
								projectDependency.getGroup(),
								projectDependency.getName(),
								projectDependency.getVersion(),
								archiveTask.getArchiveClassifier().getOrNull()
						);

						Provider<File> provider = archiveTask.getArchiveFile().map(regularFile -> getNestableJar(regularFile.getAsFile(), metadata));
						files.add(new LazyNestedFile(metadata, provider));
						builtBy.add(task);
					} else {
						throw new UnsupportedOperationException("Cannot nest none AbstractArchiveTask task: " + task.getName());
					}
				}
			}
		}

		return files;
	}

	private File getNestableJar(final File input, final Metadata metadata) {
		if (ModUtils.isMod(input, LoomGradleExtension.get(project).getPlatform().get())) {
			// Input is a mod, nothing needs to be done.
			return input;
		}

		LoomGradleExtension extension = LoomGradleExtension.get(project);
		File tempDir = new File(extension.getFiles().getUserCache(), "temp/modprocessing");

		if (!tempDir.exists()) {
			tempDir.mkdirs();
		}

		File tempFile = new File(tempDir, input.getName());

		if (tempFile.exists()) {
			tempFile.delete();
		}

		try {
			FileUtils.copyFile(input, tempFile);

			// TODO generate Quilt qmjs natively
			ZipUtils.add(tempFile.toPath(), "fabric.mod.json", generateModForDependency(metadata).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to add dummy mod while including %s".formatted(input), e);
		}

		return tempFile;
	}

	// Generates a barebones mod for a dependency
	private static String generateModForDependency(Metadata metadata) {
		String modId = (metadata.group() + "_" + metadata.name() + metadata.classifier())
				.replaceAll("\\.", "_")
				.toLowerCase(Locale.ENGLISH);

		// Fabric Loader can't handle modIds longer than 64 characters
		if (modId.length() > 64) {
			String hash = Hashing.sha256()
					.hashString(modId, StandardCharsets.UTF_8)
					.toString();
			modId = modId.substring(0, 50) + hash.substring(0, 14);
		}

		final JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty("schemaVersion", 1);

		jsonObject.addProperty("id", modId);
		jsonObject.addProperty("version", metadata.version());
		jsonObject.addProperty("name", metadata.name());

		JsonObject custom = new JsonObject();
		custom.addProperty("fabric-loom:generated", true);
		jsonObject.add("custom", custom);

		return LoomGradlePlugin.GSON.toJson(jsonObject);
	}

	public record Metadata(String group, String name, String version, @Nullable String classifier) implements Serializable {
		@Override
		public String classifier() {
			if (classifier == null) {
				return "";
			} else {
				return "_" + classifier;
			}
		}
	}

	public record NestedFile(Metadata metadata, File file) implements Serializable { }

	public record LazyNestedFile(Metadata metadata, Provider<File> file) implements Serializable {
		public LazyNestedFile(Project project, Metadata metadata, Supplier<File> file) {
			this(metadata, project.provider(file::get));
		}

		public NestedFile resolve() {
			return new NestedFile(metadata, file.get());
		}
	}
}
