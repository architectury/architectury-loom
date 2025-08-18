/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2025 FabricMC
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedSet;

import com.google.common.base.Suppliers;
import com.google.common.hash.Hashing;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.loom.forge.tool.ForgeToolService;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.forge.ForgeProvider;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.ConstantLogic;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.DownloadManifestFileLogic;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.FunctionLogic;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.InjectLogic;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.ListLibrariesLogic;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.NoOpLogic;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.PatchLogic;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.StepLogic;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.StripLogic;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.function.CollectionUtil;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.service.Service;

/**
 * Builds an {@link McpExecutor}'s {@linkplain McpExecutor.Options options} from the project state
 * and enqueued steps.
 */
public final class McpExecutorBuilder {
	private final Project project;
	private final MinecraftProvider minecraftProvider;
	private final Path cache;
	private final List<McpConfigStep> steps;
	private final DependencySet dependencySet;
	private final Map<String, McpConfigFunction> functions;
	private final Map<String, String> config = new HashMap<>();
	private final StepLogic.SetupContext setupContext = new SetupContextImpl();
	private @Nullable StepLogic.StepLogicProvider stepLogicProvider = null;

	public McpExecutorBuilder(Project project, MinecraftProvider minecraftProvider, Path cache, McpConfigProvider provider, String environment) {
		this.project = project;
		this.minecraftProvider = minecraftProvider;
		this.cache = cache;
		this.steps = provider.getData().steps().get(environment);
		this.functions = provider.getData().functions();
		this.dependencySet = new DependencySet(this.steps);
		this.dependencySet.skip(step -> isNoOp(step.type()));

		checkMinecraftVersion(provider);
		addDefaultFiles(provider, environment);
	}

	private void checkMinecraftVersion(McpConfigProvider provider) {
		final String expected = provider.getData().version();
		final String actual = minecraftProvider.minecraftVersion();

		if (!expected.equals(actual)) {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);
			final ForgeProvider forgeProvider = extension.getForgeProvider();
			final String message = "%s %s is not for Minecraft %s (expected: %s)."
					.formatted(
							extension.getPlatform().get().displayName(),
							forgeProvider.getVersion().getCombined(),
							actual,
							expected
					);

			if (GradleUtils.getBooleanProperty(project, Constants.Properties.ALLOW_MISMATCHED_PLATFORM_VERSION)) {
				project.getLogger().warn(message);
			} else {
				final String fullMessage = "%s\nYou can suppress this error by adding '%s = true' to gradle.properties."
						.formatted(message, Constants.Properties.ALLOW_MISMATCHED_PLATFORM_VERSION);
				throw new UnsupportedOperationException(fullMessage);
			}
		}
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

	/**
	 * Enqueues a step and its dependencies to be executed.
	 *
	 * @param step the name of the step
	 * @return this builder
	 */
	public McpExecutorBuilder enqueue(String step) {
		dependencySet.add(step);
		return this;
	}

	/**
	 * Builds options for an executor that runs all queued steps and their dependencies.
	 *
	 * @return the options
	 */
	public Provider<McpExecutor.Options> build() throws IOException {
		SortedSet<String> stepNames = dependencySet.buildExecutionSet();
		dependencySet.clear();
		List<McpConfigStep> toExecute = new ArrayList<>();

		for (String stepName : stepNames) {
			McpConfigStep step = CollectionUtil.find(steps, s -> s.name().equals(stepName))
					.orElseThrow(() -> new NoSuchElementException("Step '" + stepName + "' not found in MCP config"));
			toExecute.add(step);
		}

		return McpExecutor.TYPE.create(project, options -> {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);

			for (McpConfigStep step : toExecute) {
				options.getStepLogicOptions().put(step.name(), getStepLogic(step.name(), step.type()));
			}

			options.getStepsToExecute().set(toExecute);
			options.getMappings().set(extension.getMcpConfigProvider().getMappings().toFile());
			options.getInitialConfig().set(config);
			options.getOffline().set(project.getGradle().getStartParameter().isOffline());
			options.getManualRefreshDeps().set(extension.manualRefreshDeps());
			options.getToolServiceOptions().set(ForgeToolService.createOptions(project));
			options.getCache().set(cache.toFile());
		});
	}

	/**
	 * Sets the custom step logic provider of this executor.
	 *
	 * @param stepLogicProvider the provider, or null to disable
	 */
	public void setStepLogicProvider(@Nullable StepLogic.StepLogicProvider stepLogicProvider) {
		this.stepLogicProvider = stepLogicProvider;
	}

	private boolean isNoOp(String stepType) {
		return "downloadManifest".equals(stepType) || "downloadJson".equals(stepType);
	}

	private Provider<? extends Service.Options> getStepLogic(String name, String type) {
		if (stepLogicProvider != null) {
			final @Nullable Provider<? extends Service.Options> custom = stepLogicProvider.getStepLogic(setupContext, name, type);
			if (custom != null) return custom;
		}

		return switch (type) {
		case "downloadManifest", "downloadJson" -> NoOpLogic.createOptions(setupContext);
		case "downloadClient" -> ConstantLogic.createOptions(setupContext, () -> minecraftProvider.getMinecraftClientJar().toPath());
		case "downloadServer" -> ConstantLogic.createOptions(setupContext, () -> minecraftProvider.getMinecraftServerJar().toPath());
		case "strip" -> StripLogic.createOptions(setupContext);
		case "listLibraries" -> ListLibrariesLogic.createOptions(setupContext);
		case "downloadClientMappings" -> DownloadManifestFileLogic.createOptions(setupContext, minecraftProvider.getVersionInfo().download("client_mappings"));
		case "downloadServerMappings" -> DownloadManifestFileLogic.createOptions(setupContext, minecraftProvider.getVersionInfo().download("server_mappings"));
		case "inject" -> InjectLogic.createOptions(setupContext);
		case "patch" -> PatchLogic.createOptions(setupContext);
		default -> {
			if (functions.containsKey(type)) {
				yield FunctionLogic.createOptions(setupContext, functions.get(type));
			}

			throw new UnsupportedOperationException("MCP config step type: " + type);
		}
		};
	}

	private class SetupContextImpl implements StepLogic.SetupContext {
		@Override
		public Project project() {
			return project;
		}

		@Override
		public Path downloadFile(String url) throws IOException {
			Path path = getDownloadCache().resolve(Hashing.sha256().hashString(url, StandardCharsets.UTF_8).toString().substring(0, 24));

			// If the file is already downloaded, we don't need to do anything.
			if (Files.exists(path)) return path;

			redirectAwareDownload(url, path);
			return path;
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
		public Path downloadDependency(String notation) {
			final Dependency dependency = project.getDependencies().create(notation);
			final Configuration configuration = project.getConfigurations().detachedConfiguration(dependency);
			configuration.setTransitive(false);
			return configuration.getSingleFile().toPath();
		}

		@Override
		public Provider<FileCollection> getMinecraftLibraries() {
			return project().provider(Suppliers.memoize(() -> {
				project.getLogger().lifecycle(":downloading minecraft libraries, this may take a while...");
				// (1.2) minecraftRuntimeLibraries contains the compile-time libraries as well.
				final Set<File> files = project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_RUNTIME_LIBRARIES).resolve();
				return project.files(files);
			})::get);
		}
	}
}
