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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ZipUtils;

import org.gradle.api.artifacts.repositories.IvyArtifactRepository;

public class ForgeUserdevProvider extends DependencyProvider {
	private File userdevJar;
	private JsonObject json;
	Path joinedPatches;
	BinaryPatcherConfig binaryPatcherConfig;
	private boolean isLegacyForge;

	public ForgeUserdevProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		userdevJar = new File(getExtension().getForgeProvider().getGlobalCache(), "forge-userdev.jar");
		joinedPatches = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("patches-joined.lzma");
		Path configJson = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("forge-config.json");

		if (!userdevJar.exists() || Files.notExists(configJson) || refreshDeps()) {
			File resolved = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve Forge userdev"));
			Files.copy(resolved.toPath(), userdevJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.write(configJson, ZipUtils.unpack(resolved.toPath(), "config.json"));
			} catch (IOException e) {
				Files.write(configJson, ZipUtils.unpack(resolved.toPath(), "dev.json"));
			}
		}

		try (Reader reader = Files.newBufferedReader(configJson)) {
			json = new Gson().fromJson(reader, JsonObject.class);
		}

		isLegacyForge = !json.has("mcp");

		if (!isLegacyForge) {
			addDependency(json.get("mcp").getAsString(), Constants.Configurations.MCP_CONFIG);
			addDependency(json.get("mcp").getAsString(), Constants.Configurations.SRG);
			addDependency(json.get("universal").getAsString(), Constants.Configurations.FORGE_UNIVERSAL);

			binaryPatcherConfig = BinaryPatcherConfig.fromJson(json.getAsJsonObject("binpatcher"));

			if (Files.notExists(joinedPatches)) {
				Files.write(joinedPatches, ZipUtils.unpack(userdevJar.toPath(), json.get("binpatches").getAsString()));
			}
		} else {
			Map<String, String> mcpDep = Map.of(
					"group", "de.oceanlabs.mcp",
					"name", "mcp",
					"version", json.get("inheritsFrom").getAsString(),
					"classifier", "srg",
					"ext", "zip"
			);
			addDependency(mcpDep, Constants.Configurations.MCP_CONFIG);
			addDependency(mcpDep, Constants.Configurations.SRG);
			addDependency(dependency.getDepString() + ":universal", Constants.Configurations.FORGE_UNIVERSAL);
			addLegacyMCPRepo();

			binaryPatcherConfig = BinaryPatcherConfig.fromJson(new Gson().fromJson("""
					{
					  "version": "net.minecraftforge:binarypatcher:1.0.12:fatjar",
					  "args": [
					    "--clean",
					    "{clean}",
					    "--output",
					    "{output}",
					    "--apply",
					    "{patch}"
					  ]
					}""", JsonObject.class));

			if (Files.notExists(joinedPatches)) {
				Files.write(joinedPatches, ZipUtils.unpack(userdevJar.toPath(), "devbinpatches.pack.lzma"));
			}
		}
	}

	private void addLegacyMCPRepo() {
		getProject().getRepositories().ivy(repo -> {
			// Old MCP data does not have POMs
			repo.setName("LegacyMCP");
			repo.setUrl("https://maven.minecraftforge.net/");
			repo.patternLayout(layout -> {
				layout.artifact("[orgPath]/[artifact]/[revision]/[artifact]-[revision](-[classifier])(.[ext])");
				// also check the zip so people do not have to explicitly specify the extension for older versions
				layout.artifact("[orgPath]/[artifact]/[revision]/[artifact]-[revision](-[classifier]).zip");
			});
			repo.content(descriptor -> {
				descriptor.includeGroup("de.oceanlabs.mcp");
			});
			repo.metadataSources(IvyArtifactRepository.MetadataSources::artifact);
		});
	}

	public boolean isLegacyForge() {
		return isLegacyForge;
	}

	public File getUserdevJar() {
		return userdevJar;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.FORGE_USERDEV;
	}

	public JsonObject getJson() {
		return json;
	}

	public record BinaryPatcherConfig(String dependency, List<String> args) {
		public static BinaryPatcherConfig fromJson(JsonObject json) {
			String dependency = json.get("version").getAsString();
			List<String> args = List.of(LoomGradlePlugin.GSON.fromJson(json.get("args"), String[].class));
			return new BinaryPatcherConfig(dependency, args);
		}
	}
}
