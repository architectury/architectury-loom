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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Stopwatch;
import com.google.common.hash.Hashing;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.process.JavaExecSpec;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.forge.MinecraftPatchedProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.ForgeToolExecutor;
import net.fabricmc.loom.util.function.CollectionUtil;

public final class McpExecutor {
	private static final LogLevel STEP_LOG_LEVEL = LogLevel.LIFECYCLE;
	private final Project project;
	private final MinecraftPatchedProvider.MinecraftProviderBridge minecraftProvider;
	private final Path cache;
	private final List<McpConfigStep> steps;
	private final Map<String, McpConfigFunction> functions;

	public McpExecutor(Project project, MinecraftPatchedProvider.MinecraftProviderBridge minecraftProvider, Path cache, List<McpConfigStep> steps, Map<String, McpConfigFunction> functions) {
		this.project = project;
		this.minecraftProvider = minecraftProvider;
		this.cache = cache;
		this.steps = steps;
		this.functions = functions;
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

	private Path getStepOutput(String step) {
		return getStepCache(step).resolve("output");
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

			if (name.equals(ConfigValue.OUTPUT)) {
				return getStepOutput(step.name()).toAbsolutePath().toString();
			} else if (name.endsWith(ConfigValue.PREVIOUS_OUTPUT_SUFFIX)) {
				String previousStep = name.substring(0, name.length() - ConfigValue.PREVIOUS_OUTPUT_SUFFIX.length());
				return getStepOutput(previousStep).toAbsolutePath().toString();
			} else if (name.equals(ConfigValue.SRG_MAPPINGS_NAME)) {
				return LoomGradleExtension.get(project).getSrgProvider().getSrg().toAbsolutePath().toString();
			}

			throw new IllegalArgumentException("Unknown MCP config variable: " + name);
		});
	}

	public Path executeUpTo(String step) throws IOException {
		Path currentOutput = null;

		// Find the total number of steps we need to execute.
		int totalSteps = CollectionUtil.find(steps, s -> s.name().equals(step))
				.map(s -> steps.indexOf(s) + 1)
				.orElse(steps.size());
		int currentStepIndex = 0;

		project.getLogger().log(STEP_LOG_LEVEL, ":executing {} MCP steps", totalSteps);

		for (McpConfigStep currentStep : steps) {
			currentStepIndex++;
			StepLogic stepLogic = getStepLogic(currentStep.type());
			project.getLogger().log(STEP_LOG_LEVEL, ":step {}/{} - {}", currentStepIndex, totalSteps, stepLogic.getDisplayName(currentStep.name()));
			Stopwatch stopwatch = Stopwatch.createStarted();

			createStepCache(currentStep.name());
			currentOutput = getStepOutput(step);
			stepLogic.execute(new ExecutionContextImpl(currentStep));

			project.getLogger().log(STEP_LOG_LEVEL, ":{} done in {}", currentStep.name(), stopwatch.stop());

			if (currentStep.name().equals(step)) {
				break;
			}
		}

		return currentOutput;
	}

	private StepLogic getStepLogic(String type) {
		return switch (type) {
		case "downloadManifest", "downloadJson" -> new StepLogic.NoOp();
		case "downloadClient" -> StepLogic.CopyFile.of(minecraftProvider::getClientJar);
		case "downloadServer" -> StepLogic.CopyFile.of(minecraftProvider::getRawServerJar);
		case "strip" -> new StepLogic.Strip();
		case "listLibraries" -> new StepLogic.ListLibraries();
		case "downloadClientMappings" -> new StepLogic.DownloadManifestFile(minecraftProvider.getVersionInfo().download("client_mappings"));
		case "downloadServerMappings" -> new StepLogic.DownloadManifestFile(minecraftProvider.getVersionInfo().download("server_mappings"));
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
		public Path output() {
			return getStepOutput(step.name());
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
			DownloadUtil.downloadIfChanged(new URL(url), path.toFile(), project.getLogger(), true);
			return path;
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
