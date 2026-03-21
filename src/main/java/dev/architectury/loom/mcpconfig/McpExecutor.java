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

package dev.architectury.loom.mcpconfig;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.architectury.loom.forge.config.ConfigValue;
import dev.architectury.loom.forge.tool.ForgeToolExecutor;
import dev.architectury.loom.forge.tool.ForgeToolService;
import dev.architectury.loom.mcpconfig.steplogic.StepLogic;
import dev.architectury.loom.util.Stopwatch;
import org.gradle.api.Action;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.jspecify.annotations.Nullable;

import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/**
 * Executes MCPConfig and NeoForm configs to build Minecraft jars on those platforms.
 */
public final class McpExecutor extends Service<McpExecutor.Options> {
	public static final ServiceType<Options, McpExecutor> TYPE = new ServiceType<>(Options.class, McpExecutor.class);

	private static final Logger LOGGER = Logging.getLogger(McpExecutor.class);
	private static final LogLevel STEP_LOG_LEVEL = LogLevel.LIFECYCLE;
	private final Path cache;
	private final Map<String, String> config;
	private final Map<String, String> extraConfig = new HashMap<>();

	public interface Options extends Service.Options {
		// Steps

		/**
		 * The service options for the step logics of the requested steps.
		 */
		@Nested
		MapProperty<String, Service.Options> getStepLogicOptions();

		/**
		 * The requested steps.
		 */
		@Input
		ListProperty<McpConfigStep> getStepsToExecute();

		// Config data

		/**
		 * Mappings extracted from {@code data.mappings} in the MCPConfig JSON.
		 * Optional for spec 6+ where mappings are absent.
		 */
		@Optional
		@InputFile
		RegularFileProperty getMappings();

		/**
		 * The initial config from the data files.
		 */
		@Input
		MapProperty<String, String> getInitialConfig();

		// Download settings
		@Input
		Property<Boolean> getOffline();

		@Input
		Property<Boolean> getManualRefreshDeps();

		// Services
		@Nested
		Property<ForgeToolService.Options> getToolServiceOptions();

		@Internal
		DirectoryProperty getCache();
	}

	public McpExecutor(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
		this.config = new HashMap<>(options.getInitialConfig().get());
		this.cache = options.getCache().get().getAsFile().toPath();
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
		return value.resolve(variable -> {
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

	/**
	 * Executes all queued steps and their dependencies.
	 *
	 * @return the output file of the last executed step
	 */
	public Path execute() throws IOException {
		List<McpConfigStep> steps = getOptions().getStepsToExecute().get();
		int totalSteps = steps.size();
		int currentStepIndex = 0;

		LOGGER.log(STEP_LOG_LEVEL, ":executing {} MCP steps", totalSteps);

		for (McpConfigStep currentStep : steps) {
			currentStepIndex++;
			StepLogic<?> stepLogic = getStepLogic(currentStep.name());
			LOGGER.log(STEP_LOG_LEVEL, ":step {}/{} - {}", currentStepIndex, totalSteps, stepLogic.getDisplayName(currentStep.name()));

			Stopwatch stopwatch = Stopwatch.createStarted();
			stepLogic.execute(new ExecutionContextImpl(currentStep));
			LOGGER.log(STEP_LOG_LEVEL, ":{} done in {}", currentStep.name(), stopwatch.stop());
		}

		return Path.of(extraConfig.get(ConfigValue.OUTPUT));
	}

	private StepLogic<?> getStepLogic(String name) {
		final Provider<Service.Options> options = getOptions().getStepLogicOptions().getting(name);
		return (StepLogic<?>) getServiceFactory().get(options);
	}

	private class ExecutionContextImpl implements StepLogic.ExecutionContext {
		private final McpConfigStep step;

		ExecutionContextImpl(McpConfigStep step) {
			this.step = step;
		}

		@Override
		public Logger logger() {
			return LOGGER;
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
			if (!getOptions().getMappings().isPresent()) {
				throw new UnsupportedOperationException("Mappings are not available (spec 6+ unobfuscated)");
			}

			return getOptions().getMappings().get().getAsFile().toPath();
		}

		@Override
		public String resolve(ConfigValue value) {
			return McpExecutor.this.resolve(step, value);
		}

		@Override
		public DownloadBuilder downloadBuilder(String url) {
			DownloadBuilder builder;

			try {
				builder = Download.create(url);
			} catch (URISyntaxException e) {
				throw new RuntimeException("Failed to create downloader for: " + e);
			}

			if (getOptions().getOffline().get()) {
				builder.offline();
			}

			if (getOptions().getManualRefreshDeps().get()) {
				builder.forceDownload();
			}

			return builder;
		}

		@Override
		public void javaexec(Action<? super ForgeToolExecutor.Settings> configurator) {
			final ForgeToolService toolService = getServiceFactory().get(getOptions().getToolServiceOptions());
			toolService.exec(configurator);
		}
	}
}
