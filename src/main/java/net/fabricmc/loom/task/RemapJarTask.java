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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.inject.Inject;

import com.google.common.base.Suppliers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;
import org.cadixdev.at.AccessTransformSet;
import org.cadixdev.at.io.AccessTransformFormats;
import org.cadixdev.lorenz.MappingSet;
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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerRemapper;
import net.fabricmc.accesswidener.AccessWidenerWriter;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.build.MixinRefmapHelper;
import net.fabricmc.loom.build.nesting.IncludedJarFactory;
import net.fabricmc.loom.build.nesting.IncludedJarFactory.LazyNestedFile;
import net.fabricmc.loom.build.nesting.IncludedJarFactory.NestedFile;
import net.fabricmc.loom.build.nesting.JarNester;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerFile;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftSourceSets;
import net.fabricmc.loom.extension.MixinExtension;
import net.fabricmc.loom.task.service.MappingsService;
import net.fabricmc.loom.task.service.TinyRemapperService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.LfWriter;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.ModUtils;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.SidedClassVisitor;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.aw2at.Aw2At;
import net.fabricmc.loom.util.service.UnsafeWorkQueueHelper;
import net.fabricmc.lorenztiny.TinyMappingsReader;

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

	private final Supplier<TinyRemapperService> tinyRemapperService = Suppliers.memoize(() -> TinyRemapperService.getOrCreate(this));

	@Inject
	public RemapJarTask() {
		super();

		// Lazy provider because minecraftNamed is not available at this point
		getClasspath().from(getProject().provider(() -> getProject().getConfigurations().getByName("minecraftNamed").copy()));
		getClasspath().from(getProject().getConfigurations().getByName("modCompileClasspath"));

		getAddNestedDependencies().convention(true).finalizeValueOnRead();
		getReadMixinConfigsFromManifest().convention(LoomGradleExtension.get(getProject()).isForge()).finalizeValueOnRead();
		getInjectAccessWidener().convention(false);

		Configuration includeConfiguration = getProject().getConfigurations().getByName(Constants.Configurations.INCLUDE);
		IncludedJarFactory factory = new IncludedJarFactory(getProject());

		if (!LoomGradleExtension.get(getProject()).isForge()) {
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

		setupPreparationTask();
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

		submitWork(RemapAction.class, params -> {
			if (getAddNestedDependencies().get()) {
				params.getNestedJars().from(getNestedJars());

				if (extension.isForge()) {
					params.getForgeNestedJars().set(getForgeNestedJars());
				}
			}

			params.getTinyRemapperBuildServiceUuid().set(UnsafeWorkQueueHelper.create(getProject(), tinyRemapperService.get()));
			params.getRemapClasspath().from(getClasspath());

			final boolean legacyMixin = extension.getMixin().getUseLegacyMixinAp().get();
			params.getUseMixinExtension().set(!legacyMixin);

			if (legacyMixin) {
				setupLegacyMixinRefmapRemapping(params);
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
				params.getMappingBuildServiceUuid().set(UnsafeWorkQueueHelper.create(getProject(), MappingsService.createDefault(getProject(), getSourceNamespace().get(), getTargetNamespace().get())));
			}
		});
	}

	private void setupLegacyMixinRefmapRemapping(RemapParams params) {
		final LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		final MixinExtension mixinExtension = extension.getMixin();
		Collection<String> allMixinConfigs = null;

		final JsonObject fabricModJson = extension.getPlatform().get() == ModPlatform.FABRIC ? ModUtils.getFabricModJson(getInputFile().getAsFile().get().toPath()) : null;

		if (fabricModJson == null) {
			if (extension.getPlatform().get() == ModPlatform.QUILT) {
				try {
					byte[] bytes = ZipUtils.unpackNullable(getInputFile().getAsFile().get().toPath(), "quilt.mod.json");

					if (bytes != null) {
						JsonObject json = LoomGradlePlugin.GSON.fromJson(new InputStreamReader(new ByteArrayInputStream(bytes)), JsonObject.class);
						JsonElement mixins = json.has("mixin") ? json.get("mixin") : json.get("mixins");

						if (mixins != null) {
							if (mixins.isJsonPrimitive()) {
								allMixinConfigs = Collections.singletonList(mixins.getAsString());
							} else if (mixins.isJsonArray()) {
								allMixinConfigs = StreamSupport.stream(mixins.getAsJsonArray().spliterator(), false)
										.map(JsonElement::getAsString)
										.collect(Collectors.toList());
							} else {
								throw new RuntimeException("Unknown mixin type: " + mixins.getClass().getName());
							}
						} else {
							allMixinConfigs = Collections.emptyList();
						}
					}
				} catch (IOException e) {
					throw new RuntimeException("Cannot read file quilt.mod.json in the jar.", e);
				}
			}

			if (allMixinConfigs == null && getReadMixinConfigsFromManifest().get()) {
				allMixinConfigs = readMixinConfigsFromManifest();
			}

			if (allMixinConfigs == null) {
				if (extension.getPlatform().get() == ModPlatform.QUILT) {
					getProject().getLogger().warn("Could not find quilt.mod.json file in: " + getInputFile().getAsFile().get().getName());
					return;
				}

				getProject().getLogger().warn("Could not find fabric.mod.json file in: " + getInputFile().getAsFile().get().getName());
				return;
			}
		} else {
			allMixinConfigs = MixinRefmapHelper.getMixinConfigurationFiles(fabricModJson);
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

	private Collection<String> readMixinConfigsFromManifest() {
		File inputJar = getInputFile().get().getAsFile();

		try (JarFile jar = new JarFile(inputJar)) {
			@Nullable Manifest manifest = jar.getManifest();

			if (manifest != null) {
				Attributes attributes = manifest.getMainAttributes();
				String mixinConfigs = attributes.getValue(Constants.Forge.MIXIN_CONFIGS_MANIFEST_KEY);

				if (mixinConfigs != null) {
					return Set.of(mixinConfigs.split(","));
				}
			}

			return Set.of();
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read mixin configs from input jar", e);
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

		record RefmapData(List<String> mixinConfigs, String refmapName) implements Serializable { }
		ListProperty<RefmapData> getMixinData();

		Property<String> getTinyRemapperBuildServiceUuid();
		Property<String> getMappingBuildServiceUuid();
	}

	public abstract static class RemapAction extends AbstractRemapAction<RemapParams> {
		private static final Logger LOGGER = LoggerFactory.getLogger(RemapAction.class);

		private final TinyRemapperService tinyRemapperService;
		private TinyRemapper tinyRemapper;

		public RemapAction() {
			this.tinyRemapperService = UnsafeWorkQueueHelper.get(getParameters().getTinyRemapperBuildServiceUuid(), TinyRemapperService.class);
		}

		@Override
		public void execute() {
			try {
				LOGGER.info("Remapping {} to {}", inputFile, outputFile);

				tinyRemapper = tinyRemapperService.getTinyRemapperForRemapping();

				remap();

				if (getParameters().getClientOnlyEntries().isPresent()) {
					markClientOnlyClasses();
				}

				if (!injectAccessWidener()) {
					remapAccessWidener();
				}

				addRefmaps();
				addNestedJars();
				convertAwToAt();

				if (getParameters().getPlatform().get() != ModPlatform.FORGE) {
					modifyJarManifest();
				}

				rewriteJar();

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

		private void remap() throws IOException {
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
			final AccessWidenerFile accessWidenerFile = AccessWidenerFile.fromModJar(inputFile);

			if (accessWidenerFile == null) {
				return;
			}

			byte[] remapped = remapAccessWidener(accessWidenerFile.content());

			// Finally, replace the output with the remaped aw
			ZipUtils.replace(outputFile, accessWidenerFile.path(), remapped);
		}

		private void convertAwToAt() throws IOException {
			if (!this.getParameters().getAtAccessWideners().isPresent()) {
				return;
			}

			Set<String> atAccessWideners = this.getParameters().getAtAccessWideners().get();

			if (atAccessWideners.isEmpty()) {
				return;
			}

			AccessTransformSet at = AccessTransformSet.create();
			File jar = outputFile.toFile();

			try (FileSystemUtil.Delegate fileSystem = FileSystemUtil.getJarFileSystem(jar, false)) {
				FileSystem fs = fileSystem.get();
				Path atPath = fs.getPath(Constants.Forge.ACCESS_TRANSFORMER_PATH);

				if (Files.exists(atPath)) {
					throw new FileAlreadyExistsException("Jar " + jar + " already contains an access transformer - cannot convert AWs!");
				}

				for (String aw : atAccessWideners) {
					Path awPath = fs.getPath(aw);

					if (Files.notExists(awPath)) {
						throw new NoSuchFileException("Could not find AW '" + aw + "' to convert into AT!");
					}

					try (BufferedReader reader = Files.newBufferedReader(awPath, StandardCharsets.UTF_8)) {
						at.merge(Aw2At.toAccessTransformSet(reader));
					}

					Files.delete(awPath);
				}

				MappingsService service = UnsafeWorkQueueHelper.get(getParameters().getMappingBuildServiceUuid(), MappingsService.class);

				try (TinyMappingsReader reader = new TinyMappingsReader(service.getMemoryMappingTree(), service.getFromNamespace(), service.getToNamespace())) {
					MappingSet mappingSet = reader.read();
					at = at.remap(mappingSet);
				}

				try (Writer writer = new LfWriter(Files.newBufferedWriter(atPath))) {
					AccessTransformFormats.FML.write(writer, at);
				}
			}
		}

		private byte[] remapAccessWidener(byte[] input) {
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

			Set<File> jars = new HashSet<>(nestedJars.getFiles());
			jars.addAll(forgeNestedJars.get().stream().map(NestedFile::file).toList());
			JarNester.nestJars(jars, forgeNestedJars.getOrElse(List.of()), outputFile.toFile(), getParameters().getPlatform().get(), LOGGER);
		}

		private void addRefmaps() throws IOException {
			if (getParameters().getUseMixinExtension().get()) {
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
	protected List<String> getClientOnlyEntries() {
		final SourceSet clientSourceSet = MinecraftSourceSets.Split.getClientSourceSet(getProject());

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
		return tinyRemapperService.get();
	}
}
