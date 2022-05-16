/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2021 FabricMC
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
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;

import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyDownloader;
import net.fabricmc.loom.util.ForgeToolExecutor;
import net.fabricmc.loom.util.ZipUtils;

public class McpConfigProvider extends DependencyProvider {
	private Path mcp;
	private Path configJson;
	private Path mappings;
	private Boolean official;
	private String mappingsPath;
	private MergeAction mergeAction;
	private RemapAction remapAction;

	public McpConfigProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		init(dependency.getDependency().getVersion());

		Path mcpZip = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve MCPConfig")).toPath();

		if (!Files.exists(mcp) || !Files.exists(configJson) || isRefreshDeps()) {
			Files.copy(mcpZip, mcp, StandardCopyOption.REPLACE_EXISTING);
			Files.write(configJson, ZipUtils.unpack(mcp, "config.json"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		}

		JsonObject json;

		try (Reader reader = Files.newBufferedReader(configJson)) {
			json = new Gson().fromJson(reader, JsonObject.class);
		}

		official = json.has("official") && json.getAsJsonPrimitive("official").getAsBoolean();
		mappingsPath = json.get("data").getAsJsonObject().get("mappings").getAsString();

		if (json.has("functions")) {
			JsonObject functions = json.getAsJsonObject("functions");

			mergeAction = new MergeAction(getProject(), functions);
			remapAction = new RemapAction(getProject(), functions);
		}

		if (remapAction == null) {
			throw new RuntimeException("Could not find remap action, this is probably a version Architectury Loom does not support!");
		}
	}

	public MergeAction getMergeAction() {
		return mergeAction;
	}

	public RemapAction getRemapAction() {
		return remapAction;
	}

	private void init(String version) throws IOException {
		Path dir = getMinecraftProvider().dir("mcp/" + version).toPath();
		mcp = dir.resolve("mcp.zip");
		configJson = dir.resolve("mcp-config.json");
		mappings = dir.resolve("mcp-config-mappings.txt");

		if (isRefreshDeps()) {
			Files.deleteIfExists(mappings);
		}
	}

	public Path getMappings() {
		if (Files.notExists(mappings)) {
			try {
				Files.write(mappings, ZipUtils.unpack(getMcp(), getMappingsPath()), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to find mappings '" + getMappingsPath() + "' in " + getMcp() + "!");
			}
		}

		return mappings;
	}

	public Path getMcp() {
		return mcp;
	}

	public boolean isOfficial() {
		return official;
	}

	public String getMappingsPath() {
		return mappingsPath;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MCP_CONFIG;
	}

	public abstract static class McpStepAction {
		private final Project project;
		private final String name;
		private final File mainClasspath;
		private final FileCollection classpath;
		private final List<String> args;

		protected Map<String, String> argumentTemplates;

		public McpStepAction(Project project, JsonObject json) {
			this.project = project;
			this.name = json.get("version").getAsString();
			this.mainClasspath = DependencyDownloader.download(project, this.name, false, true)
					.getSingleFile();
			this.classpath = DependencyDownloader.download(project, this.name, true, true);
			this.args = StreamSupport.stream(json.getAsJsonArray("args").spliterator(), false)
					.map(JsonElement::getAsString)
					.collect(Collectors.toList());
		}

		protected void execute() throws IOException {
			String mainClass;
			try (var jar = new JarFile(mainClasspath)) {
				mainClass = jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
			}
			ForgeToolExecutor.exec(project, spec -> {
				spec.setArgs(args.stream().map(arg -> {
					if (argumentTemplates != null) {
						final var replacement = argumentTemplates.get(arg);
						if (replacement != null) {
							return replacement;
						}
					}
					return arg;
				}).collect(Collectors.toList()));
				spec.getMainClass().set(mainClass);
				spec.setClasspath(classpath);
			});
		}

		@Override
		public String toString() {
			return this.name;
		}
	}

	public static class MergeAction extends McpStepAction {
		public MergeAction(Project project, JsonObject json) {
			super(project, json.getAsJsonObject("merge"));
		}

		public void execute(Path client, Path server, String version, Path output) throws IOException {
			argumentTemplates = new HashMap<>();
			argumentTemplates.put("{client}", client.toAbsolutePath().toString());
			argumentTemplates.put("{server}", server.toAbsolutePath().toString());
			argumentTemplates.put("{version}", version);
			argumentTemplates.put("{output}", output.toAbsolutePath().toString());
			execute();
		}
	}

	public static class RemapAction extends McpStepAction {
		public RemapAction(Project project, JsonObject json) {
			super(project, json.getAsJsonObject("rename"));
		}

		public void execute(Path input, Path output, Path mappings) throws IOException {
			argumentTemplates = new HashMap<>();
			argumentTemplates.put("{input}", input.toAbsolutePath().toString());
			argumentTemplates.put("{output}", output.toAbsolutePath().toString());
			argumentTemplates.put("{mappings}", mappings.toAbsolutePath().toString());
			execute();
		}
	}
}
