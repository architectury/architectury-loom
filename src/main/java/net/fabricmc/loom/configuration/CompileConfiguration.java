/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2022 FabricMC
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

package net.fabricmc.loom.configuration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.AbstractCopyTask;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.mixin.GroovyApInvoker;
import net.fabricmc.loom.build.mixin.JavaApInvoker;
import net.fabricmc.loom.build.mixin.KaptApInvoker;
import net.fabricmc.loom.build.mixin.ScalaApInvoker;
import net.fabricmc.loom.configuration.accesstransformer.AccessTransformerJarProcessor;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.accesswidener.TransitiveAccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.ifaceinject.InterfaceInjectionProcessor;
import net.fabricmc.loom.configuration.processors.JarProcessorManager;
import net.fabricmc.loom.configuration.processors.ModJavadocProcessor;
import net.fabricmc.loom.configuration.providers.forge.DependencyProviders;
import net.fabricmc.loom.configuration.providers.forge.ForgeLibrariesProvider;
import net.fabricmc.loom.configuration.providers.forge.ForgeProvider;
import net.fabricmc.loom.configuration.providers.forge.ForgeRunsProvider;
import net.fabricmc.loom.configuration.providers.forge.ForgeUniversalProvider;
import net.fabricmc.loom.configuration.providers.forge.ForgeUserdevProvider;
import net.fabricmc.loom.configuration.providers.forge.PatchProvider;
import net.fabricmc.loom.configuration.providers.forge.SrgProvider;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpConfigProvider;
import net.fabricmc.loom.configuration.providers.forge.minecraft.ForgeMinecraftProvider;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProviderImpl;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarConfiguration;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.IntermediaryMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.NamedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.SrgMinecraftProvider;
import net.fabricmc.loom.configuration.sources.ForgeSourcesRemapper;
import net.fabricmc.loom.extension.MixinExtension;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.OperatingSystem;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

public final class CompileConfiguration {
	private CompileConfiguration() {
	}

	public static void setupConfigurations(Project project) {
		final ConfigurationContainer configurations = project.getConfigurations();
		final LoomGradleExtension extension = LoomGradleExtension.get(project);

		project.afterEvaluate(project1 -> {
			if (extension.shouldGenerateSrgTiny()) {
				configurations.register(Constants.Configurations.SRG, configuration -> configuration.setTransitive(false));
			}

			if (extension.isDataGenEnabled()) {
				project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName("main").resources(files -> {
					files.srcDir(project.file("src/generated/resources"));
				});
			}
		});

		configurations.register(Constants.Configurations.MOD_COMPILE_CLASSPATH, configuration -> configuration.setTransitive(true));
		configurations.register(Constants.Configurations.MOD_COMPILE_CLASSPATH_MAPPED, configuration -> configuration.setTransitive(false));
		NamedDomainObjectProvider<Configuration> serverDeps = configurations.register(Constants.Configurations.MINECRAFT_SERVER_DEPENDENCIES, configuration -> configuration.setTransitive(false));
		configurations.register(Constants.Configurations.MINECRAFT_RUNTIME_DEPENDENCIES, configuration -> configuration.setTransitive(false));
		configurations.register(Constants.Configurations.MINECRAFT_DEPENDENCIES, configuration -> {
			configuration.extendsFrom(serverDeps.get());
			configuration.setTransitive(false);
		});
		configurations.register(Constants.Configurations.LOADER_DEPENDENCIES, configuration -> configuration.setTransitive(false));
		configurations.register(Constants.Configurations.MINECRAFT, configuration -> configuration.setTransitive(false));

		if (extension.isForge()) {
			configurations.register(Constants.Configurations.FORGE).configure(configuration -> configuration.setTransitive(false));
			configurations.register(Constants.Configurations.FORGE_USERDEV).configure(configuration -> configuration.setTransitive(false));
			configurations.register(Constants.Configurations.FORGE_INSTALLER).configure(configuration -> configuration.setTransitive(false));
			configurations.register(Constants.Configurations.FORGE_UNIVERSAL).configure(configuration -> configuration.setTransitive(false));
			configurations.register(Constants.Configurations.FORGE_DEPENDENCIES);
			configurations.register(Constants.Configurations.FORGE_NAMED).configure(configuration -> configuration.setTransitive(false));
			configurations.register(Constants.Configurations.FORGE_EXTRA).configure(configuration -> configuration.setTransitive(false));
			configurations.register(Constants.Configurations.MCP_CONFIG).configure(configuration -> configuration.setTransitive(false));
			configurations.register(Constants.Configurations.FORGE_RUNTIME_LIBRARY);

			extendsFrom(Constants.Configurations.MINECRAFT_SERVER_DEPENDENCIES, Constants.Configurations.FORGE_DEPENDENCIES, project);

			extendsFrom(Constants.Configurations.FORGE_RUNTIME_LIBRARY, Constants.Configurations.FORGE_DEPENDENCIES, project);
			extendsFrom(Constants.Configurations.FORGE_RUNTIME_LIBRARY, Constants.Configurations.MINECRAFT_DEPENDENCIES, project);
			extendsFrom(Constants.Configurations.FORGE_RUNTIME_LIBRARY, Constants.Configurations.FORGE_EXTRA, project);
			extendsFrom(Constants.Configurations.FORGE_RUNTIME_LIBRARY, Constants.Configurations.FORGE_NAMED, project);
			// Include any user-defined libraries on the runtime CP.
			// (All the other superconfigurations are already on there.)
			extendsFrom(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.FORGE_RUNTIME_LIBRARY, project);

			extendsFrom(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.FORGE_NAMED, project);
			extendsFrom(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.FORGE_NAMED, project);
			extendsFrom(JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.FORGE_NAMED, project);
			extendsFrom(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.FORGE_NAMED, project);
			extendsFrom(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.FORGE_EXTRA, project);
			extendsFrom(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.FORGE_EXTRA, project);
			extendsFrom(JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.FORGE_EXTRA, project);
			extendsFrom(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.FORGE_EXTRA, project);
		}

		configurations.register(Constants.Configurations.INCLUDE, configuration -> configuration.setTransitive(false)); // Dont get transitive deps
		configurations.register(Constants.Configurations.MAPPING_CONSTANTS);
		configurations.register(Constants.Configurations.NAMED_ELEMENTS, configuration -> {
			configuration.setCanBeConsumed(true);
			configuration.setCanBeResolved(false);
			configuration.extendsFrom(configurations.getByName(JavaPlugin.API_CONFIGURATION_NAME));
		});

		extendsFrom(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, Constants.Configurations.MAPPING_CONSTANTS, project);

		configurations.register(Constants.Configurations.MAPPINGS);
		configurations.register(Constants.Configurations.MAPPINGS_FINAL);
		configurations.register(Constants.Configurations.LOOM_DEVELOPMENT_DEPENDENCIES);
		configurations.register(Constants.Configurations.UNPICK_CLASSPATH);
		configurations.register(Constants.Configurations.LOCAL_RUNTIME);
		extendsFrom(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.LOCAL_RUNTIME, project);

		extension.createRemapConfigurations(SourceSetHelper.getMainSourceSet(project));

		extendsFrom(Constants.Configurations.LOADER_DEPENDENCIES, Constants.Configurations.MINECRAFT_DEPENDENCIES, project);

		extendsFrom(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.MAPPINGS_FINAL, project);
		extendsFrom(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.MAPPINGS_FINAL, project);

		extendsFrom(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.LOOM_DEVELOPMENT_DEPENDENCIES, project);
		extendsFrom(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.LOOM_DEVELOPMENT_DEPENDENCIES, project);

		extendsFrom(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.MINECRAFT_RUNTIME_DEPENDENCIES, project);

		// Add the dev time dependencies
		project.getDependencies().add(Constants.Configurations.LOOM_DEVELOPMENT_DEPENDENCIES, Constants.Dependencies.DEV_LAUNCH_INJECTOR + Constants.Dependencies.Versions.DEV_LAUNCH_INJECTOR);
		project.getDependencies().add(Constants.Configurations.LOOM_DEVELOPMENT_DEPENDENCIES, Constants.Dependencies.TERMINAL_CONSOLE_APPENDER + Constants.Dependencies.Versions.TERMINAL_CONSOLE_APPENDER);
		project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, Constants.Dependencies.JETBRAINS_ANNOTATIONS + Constants.Dependencies.Versions.JETBRAINS_ANNOTATIONS);
		project.getDependencies().add(JavaPlugin.TEST_COMPILE_ONLY_CONFIGURATION_NAME, Constants.Dependencies.JETBRAINS_ANNOTATIONS + Constants.Dependencies.Versions.JETBRAINS_ANNOTATIONS);

		if (extension.isForge()) {
			project.getDependencies().add(Constants.Configurations.FORGE_EXTRA, Constants.Dependencies.FORGE_RUNTIME + Constants.Dependencies.Versions.FORGE_RUNTIME);
			project.getDependencies().add(Constants.Configurations.FORGE_EXTRA, Constants.Dependencies.UNPROTECT + Constants.Dependencies.Versions.UNPROTECT);
			project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, Constants.Dependencies.JAVAX_ANNOTATIONS + Constants.Dependencies.Versions.JAVAX_ANNOTATIONS);
		}
	}

	public static void configureCompile(Project project) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		project.getTasks().named(JavaPlugin.JAVADOC_TASK_NAME, Javadoc.class).configure(javadoc -> {
			final SourceSet main = SourceSetHelper.getMainSourceSet(project);
			javadoc.setClasspath(main.getOutput().plus(main.getCompileClasspath()));
		});

		GradleUtils.afterSuccessfulEvaluation(project, () -> {
			MinecraftSourceSets.get(project).afterEvaluate(project);

			final boolean previousRefreshDeps = extension.refreshDeps();

			if (getAndLock(project)) {
				project.getLogger().lifecycle("Found existing cache lock file, rebuilding loom cache. This may have been caused by a failed or canceled build.");
				extension.setRefreshDeps(true);
			}

			try {
				setupMinecraft(project);
			} catch (Exception e) {
				throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Failed to setup Minecraft", e);
			}

			LoomDependencyManager dependencyManager = new LoomDependencyManager();
			extension.setDependencyManager(dependencyManager);
			dependencyManager.handleDependencies(project);

			releaseLock(project);
			extension.setRefreshDeps(previousRefreshDeps);

			MixinExtension mixin = LoomGradleExtension.get(project).getMixin();

			if (mixin.getUseLegacyMixinAp().get()) {
				setupMixinAp(project, mixin);
			}

			configureDecompileTasks(project);

			if (extension.isForge()) {
				// (As of 0.12.0) Needs to be extended here since the source set is only created in aE.
				extendsFrom(Constants.Configurations.FORGE_RUNTIME_LIBRARY, MinecraftSourceSets.get(project).getCombinedSourceSetName(), project);

				// TODO: Find a better place for this?
				//   This has to be after dependencyManager.handleDependencies() above
				//   because of https://github.com/architectury/architectury-loom/issues/72.
				if (!OperatingSystem.isCIBuild()) {
					try {
						ForgeSourcesRemapper.addBaseForgeSources(project);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		});

		finalizedBy(project, "idea", "genIdeaWorkspace");
		finalizedBy(project, "eclipse", "genEclipseRuns");
		finalizedBy(project, "cleanEclipse", "cleanEclipseRuns");

		// Add the "dev" jar to the "namedElements" configuration
		project.artifacts(artifactHandler -> artifactHandler.add(Constants.Configurations.NAMED_ELEMENTS, project.getTasks().named("jar")));

		// Ensure that the encoding is set to UTF-8, no matter what the system default is
		// this fixes some edge cases with special characters not displaying correctly
		// see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
		project.getTasks().withType(AbstractCopyTask.class).configureEach(abstractCopyTask -> abstractCopyTask.setFilteringCharset(StandardCharsets.UTF_8.name()));
		project.getTasks().withType(JavaCompile.class).configureEach(javaCompile -> javaCompile.getOptions().setEncoding(StandardCharsets.UTF_8.name()));

		if (extension.isForge()) {
			// Create default mod from main source set
			extension.mods(mods -> {
				final SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
				mods.create("main").sourceSet(main);
			});
		}

		if (project.getPluginManager().hasPlugin("org.jetbrains.kotlin.kapt")) {
			// If loom is applied after kapt, then kapt will use the AP arguments too early for loom to pass the arguments we need for mixin.
			throw new IllegalArgumentException("fabric-loom must be applied BEFORE kapt in the plugins { } block.");
		}
	}

	// This is not thread safe across projects synchronize it here just to be sure, might be possible to move this further down, but for now this will do.
	private static synchronized void setupMinecraft(Project project) throws Exception {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final MinecraftJarConfiguration jarConfiguration = extension.getMinecraftJarConfiguration().get();

		// Provide the vanilla mc jars -- TODO share across projects.
		final MinecraftProvider minecraftProvider = jarConfiguration.getMinecraftProviderFunction().apply(project);

		if (extension.isForge() && !(minecraftProvider instanceof ForgeMinecraftProvider)) {
			throw new UnsupportedOperationException("Using Forge with split jars is not supported!");
		}

		extension.setMinecraftProvider(minecraftProvider);
		minecraftProvider.provideFirst();

		if (!extension.isForge()) {
			minecraftProvider.provide();
		}

		final DependencyInfo mappingsDep = DependencyInfo.create(project, Constants.Configurations.MAPPINGS);
		final MappingsProviderImpl mappingsProvider = MappingsProviderImpl.getInstance(project, extension, mappingsDep, minecraftProvider);
		extension.setMappingsProvider(mappingsProvider);

		if (extension.isForge()) {
			ForgeLibrariesProvider.provide(mappingsProvider, project);
			minecraftProvider.provide();
		}

		mappingsProvider.setupPost(project);
		mappingsProvider.applyToProject(project, mappingsDep);

		if (extension.isForge()) {
			ForgeRunsProvider.provide(project);
		}

		if (minecraftProvider instanceof ForgeMinecraftProvider patched) {
			patched.getPatchedProvider().remapJar();
		}

		// Provide the remapped mc jars
		final IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider = jarConfiguration.getIntermediaryMinecraftProviderBiFunction().apply(project, minecraftProvider);
		NamedMinecraftProvider<?> namedMinecraftProvider = jarConfiguration.getNamedMinecraftProviderBiFunction().apply(project, minecraftProvider);

		final JarProcessorManager jarProcessorManager = createJarProcessorManager(project);

		if (jarProcessorManager.active()) {
			// Wrap the named MC provider for one that will provide the processed jars
			namedMinecraftProvider = jarConfiguration.getProcessedNamedMinecraftProviderBiFunction().apply(namedMinecraftProvider, jarProcessorManager);
		}

		extension.setIntermediaryMinecraftProvider(intermediaryMinecraftProvider);
		intermediaryMinecraftProvider.provide(true);

		extension.setNamedMinecraftProvider(namedMinecraftProvider);
		namedMinecraftProvider.provide(true);

		if (extension.isForge()) {
			final SrgMinecraftProvider<?> srgMinecraftProvider = jarConfiguration.getSrgMinecraftProviderBiFunction().apply(project, minecraftProvider);
			extension.setSrgMinecraftProvider(srgMinecraftProvider);
			srgMinecraftProvider.provide(true);
		}
	}

	private static JarProcessorManager createJarProcessorManager(Project project) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (extension.getAccessWidenerPath().isPresent()) {
			extension.getGameJarProcessors().add(new AccessWidenerJarProcessor(project));
		}

		if (extension.getEnableTransitiveAccessWideners().get()) {
			TransitiveAccessWidenerJarProcessor transitiveAccessWidenerJarProcessor = new TransitiveAccessWidenerJarProcessor(project);

			if (!transitiveAccessWidenerJarProcessor.isEmpty()) {
				extension.getGameJarProcessors().add(transitiveAccessWidenerJarProcessor);
			}
		}

		if (extension.getInterfaceInjection().isEnabled()) {
			InterfaceInjectionProcessor jarProcessor = new InterfaceInjectionProcessor(project);

			if (!jarProcessor.isEmpty()) {
				extension.getGameJarProcessors().add(jarProcessor);
			}
		}

		if (extension.getEnableModProvidedJavadoc().get()) {
			// This doesn't do any processing on the compiled jar, but it does have an effect on the generated sources.
			final ModJavadocProcessor javadocProcessor = ModJavadocProcessor.create(project);

			if (javadocProcessor != null) {
				extension.getGameJarProcessors().add(javadocProcessor);
			}
		}

		if (extension.isForge()) {
			Set<File> atFiles = AccessTransformerJarProcessor.getAccessTransformerFiles(project);

			if (!atFiles.isEmpty()) {
				extension.getGameJarProcessors().add(new AccessTransformerJarProcessor(project, atFiles));
			}
		}

		JarProcessorManager processorManager = new JarProcessorManager(extension.getGameJarProcessors().get());
		extension.setJarProcessorManager(processorManager);
		processorManager.setupProcessors();

		return processorManager;
	}

	private static void setupMixinAp(Project project, MixinExtension mixin) {
		mixin.init();

		// Disable some things used by log4j via the mixin AP that prevent it from being garbage collected
		System.setProperty("log4j2.disable.jmx", "true");
		System.setProperty("log4j.shutdownHookEnabled", "false");
		System.setProperty("log4j.skipJansi", "true");

		project.getLogger().info("Configuring compiler arguments for Java");

		new JavaApInvoker(project).configureMixin();

		if (project.getPluginManager().hasPlugin("scala")) {
			project.getLogger().info("Configuring compiler arguments for Scala");
			new ScalaApInvoker(project).configureMixin();
		}

		if (project.getPluginManager().hasPlugin("org.jetbrains.kotlin.kapt")) {
			project.getLogger().info("Configuring compiler arguments for Kapt plugin");
			new KaptApInvoker(project).configureMixin();
		}

		if (project.getPluginManager().hasPlugin("groovy")) {
			project.getLogger().info("Configuring compiler arguments for Groovy");
			new GroovyApInvoker(project).configureMixin();
		}
	}

	private static void configureDecompileTasks(Project project) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);

		extension.getMinecraftJarConfiguration().get().getDecompileConfigurationBiFunction()
				.apply(project, extension.getNamedMinecraftProvider()).afterEvaluation();
	}

	private static Path getLockFile(Project project) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final Path cacheDirectory = extension.getFiles().getUserCache().toPath();
		final String pathHash = Checksum.toHex(project.getProjectDir().getAbsolutePath().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
		return cacheDirectory.resolve("." + pathHash + ".lock");
	}

	private static boolean getAndLock(Project project) {
		final Path lock = getLockFile(project);

		if (Files.exists(lock)) {
			return true;
		}

		try {
			Files.createFile(lock);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to acquire project configuration lock", e);
		}

		return false;
	}

	private static void releaseLock(Project project) {
		final Path lock = getLockFile(project);

		if (!Files.exists(lock)) {
			return;
		}

		try {
			Files.delete(lock);
		} catch (IOException e1) {
			try {
				// If we failed to delete the lock file, moving it before trying to delete it may help.
				final Path del = lock.resolveSibling(lock.getFileName() + ".del");
				Files.move(lock, del);
				Files.delete(del);
			} catch (IOException e2) {
				var exception = new UncheckedIOException("Failed to release project configuration lock", e2);
				exception.addSuppressed(e1);
				throw exception;
			}
		}
	}

	public static void extendsFrom(List<String> parents, String b, Project project) {
		for (String parent : parents) {
			extendsFrom(parent, b, project);
		}
	}

	public static void extendsFrom(String a, String b, Project project) {
		project.getConfigurations().getByName(a, configuration -> configuration.extendsFrom(project.getConfigurations().getByName(b)));
	}

	private static void finalizedBy(Project project, String a, String b) {
		project.getTasks().named(a).configure(task -> task.finalizedBy(project.getTasks().named(b)));
	}

	public static void setupDependencyProviders(Project project, LoomGradleExtension extension) {
		DependencyProviders dependencyProviders = new DependencyProviders();
		extension.setDependencyProviders(dependencyProviders);

		if (extension.isForge()) {
			dependencyProviders.addProvider(new ForgeProvider(project));
			dependencyProviders.addProvider(new ForgeUserdevProvider(project));
		}

		if (extension.shouldGenerateSrgTiny()) {
			dependencyProviders.addProvider(new SrgProvider(project));
		}

		if (extension.isForge()) {
			dependencyProviders.addProvider(new McpConfigProvider(project));
			dependencyProviders.addProvider(new PatchProvider(project));
			dependencyProviders.addProvider(new ForgeUniversalProvider(project));
		}

		dependencyProviders.handleDependencies(project);
	}
}
