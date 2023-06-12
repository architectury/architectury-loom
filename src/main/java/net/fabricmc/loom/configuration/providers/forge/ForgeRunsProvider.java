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

package net.fabricmc.loom.configuration.providers.forge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.NamedDomainObjectSet;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.ModSettings;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyDownloader;
import net.fabricmc.loom.util.gradle.SourceSetHelper;
import net.fabricmc.loom.util.gradle.SourceSetReference;

public class ForgeRunsProvider implements ConfigValue.Resolver {
	private final Project project;
	private final LoomGradleExtension extension;
	private final JsonObject json;
	private final NamedDomainObjectSet<ForgeRunTemplate> templates;

	public ForgeRunsProvider(Project project, JsonObject json) {
		this.project = project;
		this.extension = LoomGradleExtension.get(project);
		this.json = json;
		this.templates = project.getObjects().namedDomainObjectSet(ForgeRunTemplate.class);
		readTemplates();
	}

	private void readTemplates() {
		if (!extension.isLegacyForge()) {
			configureRuns(json.getAsJsonObject("runs"));
		} else {
			configureRunsForLegacyForge();
		}
	}

	private void configureRuns(JsonObject runs) {
		for (Map.Entry<String, JsonElement> entry : runs.entrySet()) {
			ForgeRunTemplate template = ForgeRunTemplate.fromJson(entry.getValue().getAsJsonObject());
			templates.add(template);
		}
	}

	private void configureRunsForLegacyForge() {
		extension.getRunConfigs().configureEach(config -> {
			//	if (Constants.Forge.LAUNCH_TESTING.equals(config.getDefaultMainClass())) {
			config.setDefaultMainClass(Constants.LegacyForge.LAUNCH_WRAPPER);
			//	}
		});
	}

	public NamedDomainObjectSet<ForgeRunTemplate> getTemplates() {
		return templates;
	}

	public static ForgeRunsProvider create(Project project) {
		JsonObject json = LoomGradleExtension.get(project).getForgeUserdevProvider().getJson();
		return new ForgeRunsProvider(project, json);
	}

	@Override
	public String resolve(ConfigValue.Variable variable) {
		String key = variable.name();
		String string = '{' + key + '}';

		// TODO: Look into ways to not hardcode
		if (key.equals("runtime_classpath")) {
			string = runtimeClasspath().stream()
					.map(File::getAbsolutePath)
					.collect(Collectors.joining(File.pathSeparator));
		} else if (key.equals("minecraft_classpath")) {
			string = minecraftClasspath().stream()
					.map(File::getAbsolutePath)
					.collect(Collectors.joining(File.pathSeparator));
		} else if (key.equals("runtime_classpath_file")) {
			Path path = extension.getFiles().getProjectPersistentCache().toPath().resolve("forge_runtime_classpath.txt");

			try {
				Files.writeString(path, runtimeClasspath().stream()
								.map(File::getAbsolutePath)
								.collect(Collectors.joining("\n")),
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			string = path.toAbsolutePath().toString();
		} else if (key.equals("minecraft_classpath_file")) {
			Path path = extension.getFiles().getProjectPersistentCache().toPath().resolve("forge_minecraft_classpath.txt");

			try {
				Files.writeString(path, minecraftClasspath().stream()
								.map(File::getAbsolutePath)
								.collect(Collectors.joining("\n")),
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			string = path.toAbsolutePath().toString();
		} else if (key.equals("asset_index")) {
			string = extension.getMinecraftProvider().getVersionInfo().assetIndex().fabricId(extension.getMinecraftProvider().minecraftVersion());
		} else if (key.equals("assets_root")) {
			string = new File(extension.getFiles().getUserCache(), "assets").getAbsolutePath();
		} else if (key.equals("natives")) {
			string = extension.getFiles().getNativesDirectory(project).getAbsolutePath();
		} else if (key.equals("source_roots")) {
			// Use a set-valued multimap for deduplicating paths.
			Multimap<String, String> modClasses = MultimapBuilder.hashKeys().linkedHashSetValues().build();

			for (ModSettings mod : extension.getMods()) {
				// Note: In Forge 1.16.5, resources have to come first to find mods.toml
				for (SourceSetReference modSourceSet : mod.getModSourceSets().get()) {
					File resourcesDir = modSourceSet.sourceSet().getOutput().getResourcesDir();
					modClasses.put(mod.getName(), resourcesDir.getAbsolutePath());
				}

				for (File file : SourceSetHelper.getClasspath(mod, project)) {
					modClasses.put(mod.getName(), file.getAbsolutePath());
				}
			}

			string = modClasses.entries().stream()
					.map(entry -> entry.getKey() + "%%" + entry.getValue())
					.collect(Collectors.joining(File.pathSeparator));
		} else if (key.equals("mcp_mappings")) {
			string = "loom.stub";
		} else if (json.has(key)) {
			JsonElement element = json.get(key);

			if (element.isJsonArray()) {
				string = StreamSupport.stream(element.getAsJsonArray().spliterator(), false)
						.map(JsonElement::getAsString)
						.flatMap(str -> {
							if (str.contains(":")) {
								return DependencyDownloader.download(project, str, false, false).getFiles().stream()
										.map(File::getAbsolutePath)
										.filter(dep -> !dep.contains("bootstraplauncher")); // TODO: Hack
							}

							return Stream.of(str);
						})
						.collect(Collectors.joining(File.pathSeparator));
			} else {
				string = element.toString();
			}
		} else {
			project.getLogger().warn("Unrecognized template! " + string);
		}

		return string;
	}

	private Set<File> runtimeClasspath() {
		// Should we actually include the runtime classpath here? Forge doesn't seem to be using this property anyways
		return minecraftClasspath();
	}

	private Set<File> minecraftClasspath() {
		return DependencyDownloader.resolveFiles(project, project.getConfigurations().getByName(Constants.Configurations.FORGE_RUNTIME_LIBRARY), true);
	}
}
