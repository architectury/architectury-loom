/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2022 FabricMC
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

package net.fabricmc.loom.task;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import com.google.gson.JsonObject;
import dev.architectury.loom.extensions.ModBuildExtensions;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskDependency;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerRemapper;
import net.fabricmc.accesswidener.AccessWidenerWriter;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.nesting.IncludedJarFactory;
import net.fabricmc.loom.build.nesting.IncludedJarFactory.LazyNestedFile;
import net.fabricmc.loom.build.nesting.IncludedJarFactory.NestedFile;
import net.fabricmc.loom.build.nesting.JarNester;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerFile;
import net.fabricmc.loom.configuration.mods.ArtifactMetadata;
import net.fabricmc.loom.extension.MixinExtension;
import net.fabricmc.loom.task.service.MappingsService;
import net.fabricmc.loom.task.service.TinyRemapperService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.SidedClassVisitor;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;
import net.fabricmc.loom.util.service.BuildSharedServiceManager;
import net.fabricmc.loom.util.service.UnsafeWorkQueueHelper;

public abstract class RemapJarTask extends AbstractRemapJarTask {
	@InputFiles
	public abstract ConfigurableFileCollection getNestedJars();

	@Input
	public abstract ListProperty<NestedFile> getForgeNestedJars();

	@Input
	public abstract Property<Boolean> getAddNestedDependencies();

	/**
	 * Gets the jar paths to the access wideners that will be converted to ATs for Forge runtime.
	 * If you specify multiple files, they will be merged into one.
	 *
	 * <p>The specified files will be converted and removed from the final jar.
	 *
	 * @return the property containing access widener paths in the final jar
	 */
	@Input
	public abstract SetProperty<String> getAtAccessWideners();

	/**
	 * Configures whether to read mixin configs from jar manifest
	 * if a fabric.mod.json cannot be found.
	 *
	 * <p>This is enabled by default on Forge, but not on other platforms.
	 *
	 * @return the property
	 */
	@Input
	public abstract Property<Boolean> getReadMixinConfigsFromManifest();

	/**
	 * Sets the "accessWidener" property in the fabric.mod.json, if the project is
	 * using access wideners.
	 *
	 * @return the property
	 */
	@Input
	public abstract Property<Boolean> getInjectAccessWidener();

	@Input
	@ApiStatus.Internal
	public abstract Property<Boolean> getUseMixinAP();

	private final Provider<BuildSharedServiceManager> serviceManagerProvider;

	@Inject
	public RemapJarTask() {
		super();
		serviceManagerProvider = BuildSharedServiceManager.createForTask(this, getBuildEventsListenerRegistry());

		final Configuration compileClasspath = getProject().getConfigurations().getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
		final Configuration minecraftCompileLibraries = getProject().getConfigurations().getByName(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES);
		// Filter out minecraft libraries from the classpath as we are 100% sure they don't play a part in the obfuscation
		final FileCollection remapClasspath = compileClasspath.filter(file -> !minecraftCompileLibraries.getFiles().contains(file));

		getClasspath().from(remapClasspath);
		getAddNestedDependencies().convention(true).finalizeValueOnRead();
		getReadMixinConfigsFromManifest().convention(LoomGradleExtension.get(getProject()).isForgeLike()).finalizeValueOnRead();
		getInjectAccessWidener().convention(false);

		Configuration includeConfiguration = getProject().getConfigurations().getByName(Constants.Configurations.INCLUDE);
		IncludedJarFactory factory = new IncludedJarFactory(getProject());

		if (!LoomGradleExtension.get(getProject()).isForgeLike()) {
			getNestedJars().from(factory.getNestedJars(includeConfiguration));
		} else {
			Provider<Pair<List<LazyNestedFile>, TaskDependency>> forgeNestedJars = factory.getForgeNestedJars(includeConfiguration);
			getForgeNestedJars().value(forgeNestedJars.map(Pair::left).map(pairs -> {
				return pairs.stream()
						.map(LazyNestedFile::resolve)
						.toList();
			}));
			getNestedJars().builtBy(forgeNestedJars.map(Pair::right));
		}

		getUseMixinAP().set(LoomGradleExtension.get(getProject()).getMixin().getUseLegacyMixinAp());

		if (getLoomExtension().multiProjectOptimisation()) {
			setupPreparationTask();
		}

		// Make outputs reproducible by default
		setReproducibleFileOrder(true);
		setPreserveFileTimestamps(false);

		getJarType().set("classes");
	}

	private void setupPreparationTask() {
		PrepareJarRemapTask prepareJarTask = getProject().getTasks().create("prepare" + getName().substring(0, 1).toUpperCase() + getName().substring(1), PrepareJarRemapTask.class, this);

		dependsOn(prepareJarTask);
		mustRunAfter(prepareJarTask);

		getProject().getGradle().allprojects(project -> {
			project.getTasks().configureEach(task -> {
				if (task instanceof PrepareJarRemapTask otherTask) {
					// Ensure that all remap jars run after all prepare tasks
					mustRunAfter(otherTask);
				}
			});
		});
	}

	@TaskAction
	public void run() {
		final LoomGradleExtension extension = LoomGradleExtension.get(getProject());

		if (shouldSkipRemap(extension)) {
			getSourceNamespace().convention(getTargetNamespace());
		}

		submitWork(RemapAction.class, params -> {
			if (getAddNestedDependencies().get()) {
				params.getNestedJars().from(getNestedJars());

				if (extension.isForgeLike()) {
					params.getForgeNestedJars().set(getForgeNestedJars());
				}
			}

			if (!params.namespacesMatch()) {
				params.getTinyRemapperBuildServiceUuid().set(UnsafeWorkQueueHelper.create(getTinyRemapperService()));
				params.getRemapClasspath().from(getClasspath());

				params.getMultiProjectOptimisation().set(getLoomExtension().multiProjectOptimisation());

				final boolean mixinAp = getUseMixinAP().get();
				params.getUseMixinExtension().set(!mixinAp);

				if (mixinAp) {
					setupLegacyMixinRefmapRemapping(params);
				}

				// Add the mixin refmap remap type to the manifest
				// This is used by the mod dependency remapper to determine if it should remap the refmap
				// or if the refmap should be remapped by mixin at runtime.
				final var refmapRemapType = mixinAp ? ArtifactMetadata.MixinRemapType.MIXIN : ArtifactMetadata.MixinRemapType.STATIC;
				params.getManifestAttributes().put(Constants.Manifest.MIXIN_REMAP_TYPE, refmapRemapType.manifestValue());
			} else if (extension.isForge()) {
				throw new RuntimeException("Forge must have useLegacyMixinAp enabled");
			}

			params.getPlatform().set(extension.getPlatform());

			if (getInjectAccessWidener().get() && extension.getAccessWidenerPath().isPresent()) {
				params.getInjectAccessWidener().set(extension.getAccessWidenerPath());
			}

			params.getMappingBuildServiceUuid().convention("this should be unavailable!");
			params.getAtAccessWideners().set(getAtAccessWideners());

			if (!getAtAccessWideners().get().isEmpty()) {
				params.getMappingBuildServiceUuid().set(UnsafeWorkQueueHelper.create(MappingsService.createDefault(getProject(), serviceManagerProvider.get().get(), getSourceNamespace().get(), getTargetNamespace().get())));
			}
		});
	}

	private void setupLegacyMixinRefmapRemapping(RemapParams params) {
		final LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		final MixinExtension mixinExtension = extension.getMixin();

		final Collection<String> allMixinConfigs = new LinkedHashSet<>();
		final FabricModJson fabricModJson = FabricModJsonFactory.createFromZipNullable(getInputFile().getAsFile().get().toPath());

		if (fabricModJson != null) {
			allMixinConfigs.addAll(fabricModJson.getMixinConfigurations());
		}

		if (getReadMixinConfigsFromManifest().get()) {
			allMixinConfigs.addAll(ModBuildExtensions.readMixinConfigsFromManifest(getInputFile().get().getAsFile()));
		}

		if (allMixinConfigs.isEmpty()) {
			return;
		}

		for (SourceSet sourceSet : mixinExtension.getMixinSourceSets()) {
			MixinExtension.MixinInformationContainer container = Objects.requireNonNull(
					MixinExtension.getMixinInformationContainer(sourceSet)
			);

			final List<String> rootPaths = getRootPaths(sourceSet.getResources().getSrcDirs());

			final String refmapName = container.refmapNameProvider().get();
			final List<String> mixinConfigs = container.sourceSet().getResources()
					.matching(container.mixinConfigPattern())
					.getFiles()
					.stream()
					.map(relativePath(rootPaths))
					.filter(allMixinConfigs::contains)
					.toList();

			params.getMixinData().add(new RemapParams.RefmapData(mixinConfigs, refmapName));
		}
	}

	public interface RemapParams extends AbstractRemapParams {
		ConfigurableFileCollection getNestedJars();

		ListProperty<NestedFile> getForgeNestedJars();

		ConfigurableFileCollection getRemapClasspath();

		Property<ModPlatform> getPlatform();

		RegularFileProperty getInjectAccessWidener();

		SetProperty<String> getAtAccessWideners();

		Property<Boolean> getUseMixinExtension();
		Property<Boolean> getMultiProjectOptimisation();

		record RefmapData(List<String> mixinConfigs, String refmapName) implements Serializable { }
		ListProperty<RefmapData> getMixinData();

		Property<String> getTinyRemapperBuildServiceUuid();
		Property<String> getMappingBuildServiceUuid();
	}

	public abstract static class RemapAction extends AbstractRemapAction<RemapParams> {
		private static final Logger LOGGER = LoggerFactory.getLogger(RemapAction.class);

		private final @Nullable TinyRemapperService tinyRemapperService;
		private @Nullable TinyRemapper tinyRemapper;

		public RemapAction() {
			this.tinyRemapperService = getParameters().getTinyRemapperBuildServiceUuid().isPresent()
					? UnsafeWorkQueueHelper.get(getParameters().getTinyRemapperBuildServiceUuid(), TinyRemapperService.class)
					: null;
		}

		@Override
		public void execute() {
			try {
				LOGGER.info("Remapping {} to {}", inputFile, outputFile);

				if (!getParameters().getMultiProjectOptimisation().getOrElse(false)) {
					prepare();
				}

				if (tinyRemapperService != null) {
					tinyRemapper = tinyRemapperService.getTinyRemapperForRemapping();

					remap();
				} else {
					Files.copy(inputFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
				}

				if (getParameters().getClientOnlyEntries().isPresent()) {
					markClientOnlyClasses();
				}

				if (!injectAccessWidener()) {
					remapAccessWidener();
				}

				addRefmaps();
				addNestedJars();
				ModBuildExtensions.convertAwToAt(getParameters().getAtAccessWideners(), outputFile, getParameters().getMappingBuildServiceUuid());

				if (!getParameters().getPlatform().get().isForgeLike()) {
					modifyJarManifest();
				}

				rewriteJar();

				if (tinyRemapperService != null && !getParameters().getMultiProjectOptimisation().get()) {
					tinyRemapperService.close();
				}

				LOGGER.debug("Finished remapping {}", inputFile);
			} catch (Exception e) {
				try {
					Files.deleteIfExists(outputFile);
				} catch (IOException ex) {
					LOGGER.error("Failed to delete output file", ex);
				}

				throw ExceptionUtil.createDescriptiveWrapper(RuntimeException::new, "Failed to remap", e);
			}
		}

		private void prepare() {
			final Path inputFile = getParameters().getInputFile().getAsFile().get().toPath();

			if (tinyRemapperService != null) {
				PrepareJarRemapTask.prepare(tinyRemapperService, inputFile);
			}
		}

		private void remap() throws IOException {
			Objects.requireNonNull(tinyRemapperService, "tinyRemapperService");
			Objects.requireNonNull(tinyRemapper, "tinyRemapper");

			try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputFile).build()) {
				outputConsumer.addNonClassFiles(inputFile);
				tinyRemapper.apply(outputConsumer, tinyRemapperService.getOrCreateTag(inputFile));
			}
		}

		private void markClientOnlyClasses() throws IOException {
			final Stream<Pair<String, ZipUtils.UnsafeUnaryOperator<byte[]>>> tranformers = getParameters().getClientOnlyEntries().get().stream()
					.map(s -> new Pair<>(s,
							(ZipUtils.AsmClassOperator) classVisitor -> SidedClassVisitor.CLIENT.insertApplyVisitor(null, classVisitor)
					));

			ZipUtils.transform(outputFile, tranformers);
		}

		private boolean injectAccessWidener() throws IOException {
			if (!getParameters().getInjectAccessWidener().isPresent()) return false;

			Path path = getParameters().getInjectAccessWidener().getAsFile().get().toPath();

			byte[] remapped = remapAccessWidener(Files.readAllBytes(path));

			ZipUtils.add(outputFile, path.getFileName().toString(), remapped);

			if (getParameters().getPlatform().get() == ModPlatform.QUILT) {
				ZipUtils.transformJson(JsonObject.class, outputFile, Map.of("quilt.mod.json", json -> {
					json.addProperty("access_widener", path.getFileName().toString());
					return json;
				}));
				return true;
			}

			ZipUtils.transformJson(JsonObject.class, outputFile, Map.of("fabric.mod.json", json -> {
				json.addProperty("accessWidener", path.getFileName().toString());
				return json;
			}));

			return true;
		}

		private void remapAccessWidener() throws IOException {
			if (getParameters().namespacesMatch()) {
				return;
			}

			final AccessWidenerFile accessWidenerFile = AccessWidenerFile.fromModJar(inputFile);

			if (accessWidenerFile == null) {
				return;
			}

			byte[] remapped = remapAccessWidener(accessWidenerFile.content());

			// Finally, replace the output with the remaped aw
			ZipUtils.replace(outputFile, accessWidenerFile.path(), remapped);
		}

		private byte[] remapAccessWidener(byte[] input) {
			Objects.requireNonNull(tinyRemapper, "tinyRemapper");

			int version = AccessWidenerReader.readVersion(input);

			AccessWidenerWriter writer = new AccessWidenerWriter(version);
			AccessWidenerRemapper remapper = new AccessWidenerRemapper(
					writer,
					tinyRemapper.getEnvironment().getRemapper(),
					getParameters().getSourceNamespace().get(),
					getParameters().getTargetNamespace().get()
			);
			AccessWidenerReader reader = new AccessWidenerReader(remapper);
			reader.read(input);

			return writer.write();
		}

		private void addNestedJars() {
			FileCollection nestedJars = getParameters().getNestedJars();
			ListProperty<NestedFile> forgeNestedJars = getParameters().getForgeNestedJars();

			if (nestedJars.isEmpty() && (!forgeNestedJars.isPresent() || forgeNestedJars.get().isEmpty())) {
				LOGGER.info("No jars to nest");
				return;
			}

			Set<File> jars = new LinkedHashSet<>(nestedJars.getFiles());
			jars.addAll(forgeNestedJars.get().stream().map(NestedFile::file).toList());
			JarNester.nestJars(jars, forgeNestedJars.getOrElse(List.of()), outputFile.toFile(), getParameters().getPlatform().get(), LOGGER);
		}

		private void addRefmaps() throws IOException {
			if (getParameters().getUseMixinExtension().getOrElse(false)) {
				return;
			}

			for (RemapParams.RefmapData refmapData : getParameters().getMixinData().get()) {
				int transformed = ZipUtils.transformJson(JsonObject.class, outputFile, refmapData.mixinConfigs().stream().collect(Collectors.toMap(s -> s, s -> json -> {
					if (!json.has("refmap")) {
						json.addProperty("refmap", refmapData.refmapName());
					}

					return json;
				})));
			}
		}
	}

	@Override
	protected List<String> getClientOnlyEntries(SourceSet clientSourceSet) {
		final ConfigurableFileCollection output = getProject().getObjects().fileCollection();
		output.from(clientSourceSet.getOutput().getClassesDirs());
		output.from(clientSourceSet.getOutput().getResourcesDir());

		final List<String> rootPaths = new ArrayList<>();

		rootPaths.addAll(getRootPaths(clientSourceSet.getOutput().getClassesDirs().getFiles()));
		rootPaths.addAll(getRootPaths(Set.of(Objects.requireNonNull(clientSourceSet.getOutput().getResourcesDir()))));

		return output.getAsFileTree().getFiles().stream()
				.map(relativePath(rootPaths))
				.toList();
	}

	@Internal
	public TinyRemapperService getTinyRemapperService() {
		return TinyRemapperService.getOrCreate(serviceManagerProvider.get().get(), this);
	}
}
