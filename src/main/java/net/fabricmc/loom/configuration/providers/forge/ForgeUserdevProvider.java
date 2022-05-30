/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2022 FabricMC
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
import java.io.Reader;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.ForgeLocalMod;
import net.fabricmc.loom.api.ModSettings;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.configuration.ide.RunConfigSettings;
import net.fabricmc.loom.configuration.launch.LaunchProviderSettings;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyDownloader;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.PropertyUtil;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

public class ForgeUserdevProvider extends DependencyProvider {
	private List<Pattern> filters;
	private File userdevJar;
	private JsonObject json;
	private Path accessTransformerConfig;
	private boolean notchObfPatches;
	Path joinedPatches;
	BinaryPatcherConfig binaryPatcherConfig;

	public ForgeUserdevProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		Attribute<Boolean> transformed = Attribute.of("architectury-loom-forge-dependencies-transformed-2", Boolean.class);

		getProject().getDependencies().registerTransform(RemoveNameProvider.class, spec -> {
			spec.getFrom().attribute(transformed, false);
			spec.getTo().attribute(transformed, true);
		});

		for (ArtifactTypeDefinition type : getProject().getDependencies().getArtifactTypes()) {
			type.getAttributes().attribute(transformed, false);
		}

		userdevJar = new File(getExtension().getForgeProvider().getGlobalCache(), "forge-userdev.jar");
		joinedPatches = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("patches-joined.lzma");
		Path configJson = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("forge-config.json");

		if (!userdevJar.exists() || Files.notExists(configJson) || isRefreshDeps()) {
			File resolved = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve Forge userdev"));
			Files.copy(resolved.toPath(), userdevJar.toPath(), StandardCopyOption.REPLACE_EXISTING);

			try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + resolved.toURI()), ImmutableMap.of("create", false))) {
				Files.copy(fs.getPath("config.json"), configJson, StandardCopyOption.REPLACE_EXISTING);
			}
		}

		try (Reader reader = Files.newBufferedReader(configJson)) {
			json = new Gson().fromJson(reader, JsonObject.class);
		}

		addDependency(json.get("mcp").getAsString(), Constants.Configurations.MCP_CONFIG);
		addDependency(json.get("mcp").getAsString(), Constants.Configurations.SRG);
		addDependency(json.get("universal").getAsString(), Constants.Configurations.FORGE_UNIVERSAL);

		for (JsonElement lib : json.get("libraries").getAsJsonArray()) {
			Dependency dep = null;

			if (lib.getAsString().startsWith("org.spongepowered:mixin:")) {
				if (PropertyUtil.getAndFinalize(getExtension().getForge().getUseCustomMixin())) {
					if (lib.getAsString().contains("0.8.2")) {
						dep = addDependency("net.fabricmc:sponge-mixin:0.8.2+build.24", Constants.Configurations.FORGE_DEPENDENCIES);
					} else {
						dep = addDependency("dev.architectury:mixin-patched" + lib.getAsString().substring(lib.getAsString().lastIndexOf(":")) + ".+", Constants.Configurations.FORGE_DEPENDENCIES);
					}
				}
			}

			if (dep == null) {
				dep = addDependency(lib.getAsString(), Constants.Configurations.FORGE_DEPENDENCIES);
			}

			if (lib.getAsString().split(":").length < 4) {
				((ModuleDependency) dep).attributes(attributes -> {
					attributes.attribute(transformed, true);
				});
			}
		}

		if (json.has("universalFilters")) {
			filters = new ArrayList<>();

			for (JsonElement element : json.getAsJsonArray("universalFilters")) {
				filters.add(Pattern.compile(element.getAsString()));
			}
		}

		accessTransformerConfig = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("at-conf.cfg");

		if (Files.notExists(accessTransformerConfig)) {
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(userdevJar.toPath(), false)) {
				for (JsonElement element : json.getAsJsonArray("ats")) {
					Files.write(accessTransformerConfig, fs.readAllBytes(element.getAsString()), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
				}
			}
		}

		notchObfPatches = json.has("notchObf") && json.getAsJsonPrimitive("notchObf").getAsBoolean();

		if (Files.notExists(joinedPatches)) {
			Files.write(joinedPatches, ZipUtils.unpack(userdevJar.toPath(), json.get("binpatches").getAsString()));
		}

		binaryPatcherConfig = BinaryPatcherConfig.fromJson(json.getAsJsonObject("binpatcher"));

		for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("runs").entrySet()) {
			LaunchProviderSettings launchSettings = getExtension().getLaunchConfigs().findByName(entry.getKey());
			RunConfigSettings settings = getExtension().getRunConfigs().findByName(entry.getKey());
			JsonObject value = entry.getValue().getAsJsonObject();

			if (launchSettings != null) {
				launchSettings.evaluateLater(() -> {
					if (value.has("args")) {
						launchSettings.arg(StreamSupport.stream(value.getAsJsonArray("args").spliterator(), false)
								.map(JsonElement::getAsString)
								.map(this::processTemplates)
								.collect(Collectors.toList()));
					}

					if (value.has("props")) {
						for (Map.Entry<String, JsonElement> props : value.getAsJsonObject("props").entrySet()) {
							String string = processTemplates(props.getValue().getAsString());

							launchSettings.property(props.getKey(), string);
						}
					}
				});
			}

			if (settings != null) {
				settings.evaluateLater(() -> {
					settings.defaultMainClass(value.getAsJsonPrimitive("main").getAsString());

					if (value.has("jvmArgs")) {
						settings.vmArgs(StreamSupport.stream(value.getAsJsonArray("jvmArgs").spliterator(), false)
								.map(JsonElement::getAsString)
								.map(this::processTemplates)
								.collect(Collectors.toList()));
					}

					if (value.has("env")) {
						for (Map.Entry<String, JsonElement> env : value.getAsJsonObject("env").entrySet()) {
							String string = processTemplates(env.getValue().getAsString());

							settings.envVariables.put(env.getKey(), string);
						}
					}
				});
			}
		}
	}

	public Path getAccessTransformerConfig() {
		return accessTransformerConfig;
	}

	public boolean isNotchObfPatches() {
		return notchObfPatches;
	}

	public List<Pattern> getUniversalFilters() {
		return filters == null ? Collections.emptyList() : filters;
	}

	public abstract static class RemoveNameProvider implements TransformAction<TransformParameters.None> {
		@InputArtifact
		public abstract Provider<FileSystemLocation> getInput();

		@Override
		public void transform(TransformOutputs outputs) {
			try {
				File input = getInput().get().getAsFile();
				//architectury-loom-forge-dependencies-transformed
				File output = outputs.file("alfd-transformed/" + input.getName());
				Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);

				try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(output, false)) {
					Path path = fs.get().getPath("META-INF/services/cpw.mods.modlauncher.api.INameMappingService");
					Files.deleteIfExists(path);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public String processTemplates(String string) {
		if (string.startsWith("{")) {
			String key = string.substring(1, string.length() - 1);

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
				Path path = getDirectories().getProjectPersistentCache().toPath().resolve("forge_runtime_classpath.txt");

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
				Path path = getDirectories().getProjectPersistentCache().toPath().resolve("forge_minecraft_classpath.txt");

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
				string = getExtension().getMinecraftProvider().getVersionInfo().assetIndex().fabricId(getExtension().getMinecraftProvider().minecraftVersion());
			} else if (key.equals("assets_root")) {
				string = new File(getDirectories().getUserCache(), "assets").getAbsolutePath();
			} else if (key.equals("natives")) {
				string = getExtension().getFiles().getNativesDirectory(getProject()).getAbsolutePath();
			} else if (key.equals("source_roots")) {
				// Use a set-valued multimap for deduplicating paths.
				// It could be done using Stream.distinct before but that doesn't work if
				// you have *both* a ModSettings and a ForgeLocalMod with the same name.
				Multimap<String, String> modClasses = MultimapBuilder.hashKeys().linkedHashSetValues().build();

				for (ModSettings mod : getExtension().getMods()) {
					for (File file : SourceSetHelper.getClasspath(mod, getProject())) {
						modClasses.put(mod.getName(), file.getAbsolutePath());
					}
				}

				for (ForgeLocalMod localMod : getExtension().getForge().getLocalMods()) {
					String sourceSetName = localMod.getName();

					localMod.getSourceSets().flatMap(sourceSet -> Stream.concat(
							Stream.of(sourceSet.getOutput().getResourcesDir()),
							sourceSet.getOutput().getClassesDirs().getFiles().stream())
					).map(File::getAbsolutePath).forEach(path -> modClasses.put(sourceSetName, path));
				}

				string = modClasses.entries().stream()
						.map(entry -> entry.getKey() + "%%" + entry.getValue())
						.collect(Collectors.joining(File.pathSeparator));
			} else if (key.equals("mcp_mappings")) {
				string = "loom.stub";
			} else if (key.equals("mcp_to_srg")) {
				string = getExtension().getMappingsProvider().getMcpToSrg().toAbsolutePath().toString();
			} else if (json.has(key)) {
				JsonElement element = json.get(key);

				if (element.isJsonArray()) {
					string = StreamSupport.stream(element.getAsJsonArray().spliterator(), false)
							.map(JsonElement::getAsString)
							.flatMap(str -> {
								if (str.contains(":")) {
									return DependencyDownloader.download(getProject(), str, false, false).getFiles().stream()
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
				getProject().getLogger().warn("Unrecognized template! " + string);
			}
		}

		return string;
	}

	private Set<File> runtimeClasspath() {
		// Should we actually include the runtime classpath here? Forge doesn't seem to be using this property anyways
		return minecraftClasspath();
	}

	private Set<File> minecraftClasspath() {
		return DependencyDownloader.resolveFiles(getProject(), getProject().getConfigurations().getByName(Constants.Configurations.FORGE_RUNTIME_LIBRARY), true);
	}

	public File getUserdevJar() {
		return userdevJar;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.FORGE_USERDEV;
	}

	public record BinaryPatcherConfig(String dependency, List<String> args) {
		public static BinaryPatcherConfig fromJson(JsonObject json) {
			String dependency = json.get("version").getAsString();
			List<String> args = List.of(LoomGradlePlugin.GSON.fromJson(json.get("args"), String[].class));
			return new BinaryPatcherConfig(dependency, args);
		}
	}
}
