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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Stopwatch;
import dev.architectury.loom.forge.tool.ForgeToolExecutor;
import dev.architectury.loom.forge.tool.ForgeToolService;
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
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.configuration.providers.forge.ConfigValue;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic.StepLogic;
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

	// The initial config set before executing
	private final Map<String, String> config;

	private final ConcurrentMap<String, String> outputsByStep = new ConcurrentHashMap<>();

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

		/**
		 * Each step's dependencies.
		 */
		@Input
		MapProperty<String, Set<String>> getDependenciesByStep();

		// Config data

		/**
		 * Mappings extracted from {@code data.mappings} in the MCPConfig JSON.
		 */
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
		this.config = Map.copyOf(options.getInitialConfig().get());
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
			} else if (name.equals(ConfigValue.OUTPUT)) {
				return outputsByStep.get(step.name());
			} else if (name.endsWith(ConfigValue.PREVIOUS_OUTPUT_SUFFIX)) {
				return outputsByStep.get(name.substring(0, name.length() - ConfigValue.PREVIOUS_OUTPUT_SUFFIX.length()));
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

		LOGGER.log(STEP_LOG_LEVEL, ":executing {} MCP steps", totalSteps);

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			AtomicInteger currentStepIndex = new AtomicInteger(1); // used for progress counter
			SequencedMap<String, CompletableFuture<?>> stepFutures = new LinkedHashMap<>();

			for (McpConfigStep currentStep : steps) {
				StepLogic<?> stepLogic = getStepLogic(currentStep.name());

				// Resolve all futures that need to complete before this one.
				Set<String> dependencyNames = getOptions().getDependenciesByStep()
						.getting(currentStep.name())
						.getOrElse(Set.of());
				CompletableFuture<?>[] dependencies = dependencyNames.stream()
						.map(stepFutures::get)
						.toArray(CompletableFuture[]::new);

				// Create this step's future and store it.
				CompletableFuture<?> future = CompletableFuture.allOf(dependencies)
						.thenRunAsync(() -> {
							try {
								int index = currentStepIndex.getAndIncrement();
								String displayName = stepLogic.getDisplayName(currentStep.name());
								LOGGER.log(STEP_LOG_LEVEL, ":step {}/{} - {}", index, totalSteps, displayName);

								Stopwatch stopwatch = Stopwatch.createStarted();
								stepLogic.execute(new ExecutionContextImpl(currentStep));
								LOGGER.log(STEP_LOG_LEVEL, ":{} done in {}", currentStep.name(), stopwatch.stop());
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						}, executor);
				stepFutures.put(currentStep.name(), future);
			}

			// Wait for all the futures to complete. Closing the executor isn't enough
			// since the unstarted ones haven't even reached the executor yet.
			stepFutures.sequencedValues().reversed().forEach(CompletableFuture::join);
		}

		return Path.of(outputsByStep.get(steps.getLast().name()));
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
			outputsByStep.put(step.name(), absolutePath);
			return output;
		}

		@Override
		public Path cache() throws IOException {
			return createStepCache(step.name());
		}

		@Override
		public Path mappings() {
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
