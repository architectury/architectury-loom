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

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;

public class McpConfigProvider extends DependencyProvider {
	private File mcp;
	private Path configJson;
	private Path mappings;
	private Boolean official;
	private String mappingsPath;

	public McpConfigProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		init(dependency.getDependency().getVersion());

		Path mcpZip = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve MCPConfig")).toPath();

		if (!mcp.exists() || !Files.exists(configJson) || isRefreshDeps()) {
			Files.copy(mcpZip, mcp.toPath(), StandardCopyOption.REPLACE_EXISTING);

			try (FileSystemUtil.FileSystemDelegate fs = FileSystemUtil.getJarFileSystem(mcp, false)) {
				Files.copy(fs.get().getPath("config.json"), configJson, StandardCopyOption.REPLACE_EXISTING);
			}
		}

		JsonObject json;

		try (Reader reader = Files.newBufferedReader(configJson)) {
			json = new Gson().fromJson(reader, JsonObject.class);
		}

		official = json.has("official") && json.getAsJsonPrimitive("official").getAsBoolean();
		mappingsPath = json.get("data").getAsJsonObject().get("mappings").getAsString();
	}

	private void init(String version) throws IOException {
		File dir = getMinecraftProvider().dir("mcp/" + version);
		mcp = new File(dir, "mcp.zip");
		configJson = dir.toPath().resolve("mcp-config.json");
		mappings = dir.toPath().resolve("mcp-config-mappings.txt");

		if (isRefreshDeps()) {
			Files.deleteIfExists(mappings);
		}
	}

	public Path getMappings() {
		if (Files.notExists(mappings)) {
			if (!ZipUtil.handle(getMcp(), getMappingsPath(), (in, zipEntry) -> {
				try (BufferedWriter writer = Files.newBufferedWriter(mappings, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
					IOUtils.copy(in, writer, StandardCharsets.UTF_8);
				}
			})) {
				throw new IllegalStateException("Failed to find mappings '" + getMappingsPath() + "' in " + getMcp().getAbsolutePath() + "!");
			}
		}

		return mappings;
	}

	public File getMcp() {
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
}
