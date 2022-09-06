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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.google.common.base.Stopwatch;
import com.google.common.hash.Hashing;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.process.JavaExecSpec;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ForgeToolExecutor;
import net.fabricmc.loom.util.download.DownloadBuilder;
import net.fabricmc.loom.util.function.CollectionUtil;

public final class McpExecutor {
	private static final LogLevel STEP_LOG_LEVEL = LogLevel.LIFECYCLE;
	private final Project project;
	private final MinecraftProvider minecraftProvider;
	private final Path cache;
	private final List<McpConfigStep> steps;
	private final Map<String, McpConfigFunction> functions;
	private final Map<String, String> config = new HashMap<>();
	private final Map<String, String> extraConfig = new HashMap<>();

	public McpExecutor(Project project, MinecraftProvider minecraftProvider, Path cache, McpConfigProvider provider, String environment) {
		this.project = project;
		this.minecraftProvider = minecraftProvider;
		this.cache = cache;
		this.steps = provider.getData().steps().get(environment);
		this.functions = provider.getData().functions();

		addDefaultFiles(provider, environment);
	}

	private void addDefaultFiles(McpConfigProvider provider, String environment) {
		for (Map.Entry<String, JsonElement> entry : provider.getData().data().entrySet()) {
			if (entry.getValue().isJsonPrimitive()) {
				addDefaultFile(provider, entry.getKey(), entry.getValue().getAsString());
			} else if (entry.getValue().isJsonObject()) {
				JsonObject json = entry.getValue().getAsJsonObject();

				if (json.has(environment) && json.get(environment).isJsonPrimitive()) {
					addDefaultFile(provider, entry.getKey(), json.getAsJsonPrimitive(environment).getAsString());
				}
			}
		}
	}

	private void addDefaultFile(McpConfigProvider provider, String key, String value) {
		Path path = provider.getUnpackedZip().resolve(value).toAbsolutePath();

		if (!path.startsWith(provider.getUnpackedZip().toAbsolutePath())) {
			// This is probably not what we're looking for since it falls outside the directory.
			return;
		} else if (Files.notExists(path)) {
			// Not a real file, let's continue.
			return;
		}

		addConfig(key, path.toString());
	}

	public void addConfig(String key, String value) {
		config.put(key, value);
	}

	private Path getDownloadCache() throws IOException {
		Path downloadCache = cache.resolve("downloads");
		Files.createDirectories(downloadCache);
		return downloadCache;
	}

	private Path getStepCache(String step) {
		return cache.resolve(step);
	}

	private Path createStepCache(String step) throws IOException {
		Path stepCache = getStepCache(step);
		Files.createDirectories(stepCache);
		return stepCache;
	}

	private String resolve(McpConfigStep step, ConfigValue value) {
		return value.fold(ConfigValue.Constant::value, variable -> {
			String name = variable.name();
			@Nullable ConfigValue valueFromStep = step.config().get(name);

			// If the variable isn't defined in the step's config map, skip it.
			// Also skip if it would recurse with the same variable.
			if (valueFromStep != null && !valueFromStep.equals(variable)) {
				// Otherwise, resolve the nested variable.
				return resolve(step, valueFromStep);
			}

			if (config.containsKey(name)) {
				return config.get(name);
			} else if (extraConfig.containsKey(name)) {
				return extraConfig.get(name);
			} else if (name.equals(ConfigValue.LOG)) {
				return cache.resolve("log.log").toAbsolutePath().toString();
			}

			throw new IllegalArgumentException("Unknown MCP config variable: " + name);
		});
	}

	private static Predicate<McpConfigStep> byName(String name) {
		return s -> s.name().equals(name);
	}

	public Optional<McpConfigStep> getStep(String name) {
		return CollectionUtil.find(steps, byName(name));
	}

	public McpConfigStep getStepOrThrow(String name) {
		return getStep(name).orElseThrow(() -> new NoSuchElementException("Missing MCP config step '" + name + "'"));
	}

	public List<McpConfigStep> getStepRange(String start, String end) {
		int startIndex = CollectionUtil.indexOf(steps, byName(start));
		if (startIndex < 0) missingStep(start);
		int endIndex = CollectionUtil.indexOf(steps, byName(end));
		if (endIndex < 0) missingStep(end);
		if (startIndex > endIndex) throw new IndexOutOfBoundsException("Start step '" + start + "' is after end step '" + end + "'");
		return steps.subList(startIndex, endIndex + 1);
	}

	public List<McpConfigStep> getStepsUpTo(String step) {
		int endIndex = CollectionUtil.indexOf(steps, byName(step));
		if (endIndex < 0) missingStep(step);
		return steps.subList(0, endIndex + 1);
	}

	public Path executeUpTo(String step) throws IOException {
		return executeSteps(getStepsUpTo(step));
	}

	public Path executeSteps(List<McpConfigStep> steps) throws IOException {
		extraConfig.clear();

		int totalSteps = steps.size();
		int currentStepIndex = 0;

		project.getLogger().log(STEP_LOG_LEVEL, ":executing {} MCP steps", totalSteps);

		for (McpConfigStep currentStep : steps) {
			currentStepIndex++;
			StepLogic stepLogic = getStepLogic(currentStep.type());
			project.getLogger().log(STEP_LOG_LEVEL, ":step {}/{} - {}", currentStepIndex, totalSteps, stepLogic.getDisplayName(currentStep.name()));

			Stopwatch stopwatch = Stopwatch.createStarted();
			stepLogic.execute(new ExecutionContextImpl(currentStep));
			project.getLogger().log(STEP_LOG_LEVEL, ":{} done in {}", currentStep.name(), stopwatch.stop());
		}

		return Path.of(extraConfig.get(ConfigValue.OUTPUT));
	}

	private static void missingStep(String stepName) {
		throw new NoSuchElementException("Step '" + stepName + "' not found in MCP config");
	}

	private StepLogic getStepLogic(String type) {
		return switch (type) {
		case "downloadManifest", "downloadJson" -> new StepLogic.NoOp();
		case "downloadClient" -> new StepLogic.NoOpWithFile(() -> minecraftProvider.getMinecraftClientJar().toPath());
		case "downloadServer" -> new StepLogic.NoOpWithFile(() -> minecraftProvider.getMinecraftServerJar().toPath());
		case "strip" -> new StepLogic.Strip();
		case "listLibraries" -> new StepLogic.ListLibraries();
		case "downloadClientMappings" -> new StepLogic.DownloadManifestFile(minecraftProvider.getVersionInfo().download("client_mappings"));
		case "downloadServerMappings" -> new StepLogic.DownloadManifestFile(minecraftProvider.getVersionInfo().download("server_mappings"));
		case "inject" -> new InjectLogic();
		case "patch" -> new PatchLogic();
		default -> {
			if (functions.containsKey(type)) {
				yield new StepLogic.OfFunction(functions.get(type));
			}

			throw new UnsupportedOperationException("MCP config step type: " + type);
		}
		};
	}

	private class ExecutionContextImpl implements StepLogic.ExecutionContext {
		private final McpConfigStep step;

		ExecutionContextImpl(McpConfigStep step) {
			this.step = step;
		}

		@Override
		public Logger logger() {
			return project.getLogger();
		}

		@Override
		public Path setOutput(String fileName) throws IOException {
			return setOutput(cache().resolve(fileName));
		}

		@Override
		public Path setOutput(Path output) {
			String absolutePath = output.toAbsolutePath().toString();
			extraConfig.put(ConfigValue.OUTPUT, absolutePath);
			extraConfig.put(step.name() + ConfigValue.PREVIOUS_OUTPUT_SUFFIX, absolutePath);
			return output;
		}

		@Override
		public Path cache() throws IOException {
			return createStepCache(step.name());
		}

		@Override
		public Path mappings() {
			return LoomGradleExtension.get(project).getMcpConfigProvider().getMappings();
		}

		@Override
		public String resolve(ConfigValue value) {
			return McpExecutor.this.resolve(step, value);
		}

		@Override
		public Path download(String url) throws IOException {
			Path path = getDownloadCache().resolve(Hashing.sha256().hashString(url, StandardCharsets.UTF_8).toString().substring(0, 24));
			redirectAwareDownload(url, path);
			return path;
		}

		@Override
		public DownloadBuilder downloadBuilder(String url) {
			return LoomGradleExtension.get(project).download(url);
		}

		// Some of these files linked to the old Forge maven, let's follow the redirects to the new one.
		private static void redirectAwareDownload(String urlString, Path path) throws IOException {
			URL url = new URL(urlString);

			if (url.getProtocol().equals("http")) {
				url = new URL("https", url.getHost(), url.getPort(), url.getFile());
			}

			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.connect();

			if (connection.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM || connection.getResponseCode() == HttpURLConnection.HTTP_MOVED_TEMP) {
				redirectAwareDownload(connection.getHeaderField("Location"), path);
			} else {
				try (InputStream in = connection.getInputStream()) {
					Files.copy(in, path);
				}
			}
		}

		@Override
		public void javaexec(Action<? super JavaExecSpec> configurator) {
			ForgeToolExecutor.exec(project, configurator).rethrowFailure().assertNormalExitValue();
		}

		@Override
		public Set<File> getMinecraftLibraries() {
			return project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_DEPENDENCIES).resolve();
		}
	}
}
