/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
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

package net.fabricmc.loom.configuration.providers.minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.google.common.base.Stopwatch;
import dev.architectury.tinyremapper.IMappingProvider;
import dev.architectury.tinyremapper.InputTag;
import dev.architectury.tinyremapper.NonClassCopyMode;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;
import dev.architectury.tinyremapper.api.TrClass;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.tuple.Triple;
import org.gradle.api.Project;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.commons.Remapper;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProviderImpl;
import net.fabricmc.loom.configuration.providers.minecraft.tr.OutputRemappingHandler;
import net.fabricmc.loom.configuration.sources.ForgeSourcesRemapper;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.OperatingSystem;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.srg.AtRemapper;
import net.fabricmc.loom.util.srg.CoreModClassRemapper;
import net.fabricmc.loom.util.srg.InnerClassRemapper;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class MinecraftMappedProvider extends DependencyProvider {
	private File inputJar;
	private File inputForgeJar;
	private File minecraftMappedJar;
	private File minecraftIntermediaryJar;
	private File minecraftSrgJar;
	private File forgeMappedJar;
	private File forgeIntermediaryJar;
	private File forgeSrgJar;

	protected MinecraftProviderImpl minecraftProvider;

	public MinecraftMappedProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		if (Files.notExists(getExtension().getMappingsProvider().tinyMappings)) {
			throw new RuntimeException("mappings file not found");
		}

		if (!inputJar.exists()) {
			throw new RuntimeException("input merged jar not found");
		}

		boolean isForgeAtDirty = getExtension().isForge() && getExtension().getMappingsProvider().patchedProvider.isAtDirty();
		boolean needToRemap = false;

		if (!minecraftMappedJar.exists() || !getIntermediaryJar().exists() || (getExtension().isForge() && !getSrgJar().exists()) || isRefreshDeps() || isForgeAtDirty) {
			needToRemap = true;
		}

		if (getExtension().isForgeAndNotOfficial() && (!getForgeMappedJar().exists() || !getForgeIntermediaryJar().exists() || !getForgeSrgJar().exists() || isRefreshDeps() || isForgeAtDirty)) {
			needToRemap = true;
		}

		if (needToRemap) {
			if (minecraftMappedJar.exists()) {
				minecraftMappedJar.delete();
			}

			minecraftMappedJar.getParentFile().mkdirs();

			if (minecraftIntermediaryJar.exists()) {
				minecraftIntermediaryJar.delete();
			}

			if (getExtension().isForge() && minecraftSrgJar.exists()) {
				minecraftSrgJar.delete();
			}

			if (getExtension().isForgeAndNotOfficial()) {
				if (getForgeMappedJar().exists()) {
					getForgeMappedJar().delete();
				}

				getForgeMappedJar().getParentFile().mkdirs();
				getForgeIntermediaryJar().delete();
				getForgeSrgJar().delete();
			}

			try {
				TinyRemapper[] remapperArray = new TinyRemapper[] {null};
				mapMinecraftJar(remapperArray);
				remapperArray[0].finish();
			} catch (Throwable t) {
				// Cleanup some some things that may be in a bad state now
				DownloadUtil.delete(minecraftMappedJar);
				DownloadUtil.delete(minecraftIntermediaryJar);
				getExtension().getMinecraftProvider().deleteFiles();

				if (getExtension().isForge()) {
					DownloadUtil.delete(minecraftSrgJar);
					DownloadUtil.delete(forgeMappedJar);
					DownloadUtil.delete(forgeSrgJar);
					DownloadUtil.delete(forgeIntermediaryJar);
				}

				getExtension().getMappingsProvider().cleanFiles();
				throw new RuntimeException("Failed to remap minecraft", t);
			}
		}

		if (!minecraftMappedJar.exists()) {
			throw new RuntimeException("mapped jar not found");
		}

		addDependencies(dependency, postPopulationScheduler);

		if (getExtension().isForgeAndNotOfficial()) {
			getProject().getDependencies().add(Constants.Configurations.FORGE_NAMED,
					getProject().getDependencies().module("net.minecraftforge-loom:forge-mapped:" + getMinecraftProvider().minecraftVersion() + "/" + getExtension().getMappingsProvider().mappingsIdentifier() + "/forge"));
		}

		if (getExtension().isForge()) {
			getProject().afterEvaluate(project -> {
				if (!OperatingSystem.isCIBuild()) {
					try {
						ForgeSourcesRemapper.addBaseForgeSources(project, getExtension().isForgeAndOfficial());
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			});
		}
	}

	private byte[][] inputBytes(Path input) throws IOException {
		List<byte[]> inputByteList = new ArrayList<>();

		try (FileSystemUtil.Delegate inputFs = FileSystemUtil.getJarFileSystem(input, false)) {
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

			for (Path path : (Iterable<? extends Path>) Files.walk(inputFs.get().getPath("/"))::iterator) {
				if (Files.isRegularFile(path)) {
					if (path.getFileName().toString().endsWith(".class")) {
						taskCompleter.add(() -> {
							byte[] bytes = Files.readAllBytes(path);

							synchronized (inputByteList) {
								inputByteList.add(bytes);
							}
						});
					}
				}
			}

			taskCompleter.complete();
		}

		return inputByteList.toArray(new byte[0][0]);
	}

	private void assetsOut(Path input, @Nullable Path assetsOut) throws IOException {
		if (assetsOut != null) {
			try (OutputConsumerPath tmpAssetsPath = new OutputConsumerPath.Builder(assetsOut).assumeArchive(true).build()) {
				if (getExtension().isForge()) {
					tmpAssetsPath.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, null);
				} else {
					tmpAssetsPath.addNonClassFiles(input);
				}
			}
		}
	}

	private void mapMinecraftJar(TinyRemapper[] remapperArray) throws Exception {
		Path input = inputJar.toPath();
		Path inputForge = inputForgeJar == null ? null : inputForgeJar.toPath();
		Path outputMapped = minecraftMappedJar.toPath();
		Path outputIntermediary = minecraftIntermediaryJar.toPath();
		Path outputSrg = minecraftSrgJar == null ? null : minecraftSrgJar.toPath();

		Path forgeOutputMapped = forgeMappedJar == null ? null : forgeMappedJar.toPath();
		Path forgeOutputIntermediary = forgeIntermediaryJar == null ? null : forgeIntermediaryJar.toPath();
		Path forgeOutputSrg = forgeSrgJar == null ? null : forgeSrgJar.toPath();

		Path vanillaAssets = Files.createTempFile("assets", null);
		Files.deleteIfExists(vanillaAssets);
		vanillaAssets.toFile().deleteOnExit();
		Path forgeAssets = Files.createTempFile("assets", null);
		Files.deleteIfExists(forgeAssets);
		forgeAssets.toFile().deleteOnExit();

		Info vanilla = new Info(vanillaAssets, input, outputMapped, outputIntermediary, outputSrg);
		Info forge = getExtension().isForgeAndNotOfficial() ? new Info(forgeAssets, inputForge, forgeOutputMapped, forgeOutputIntermediary, forgeOutputSrg) : null;

		Triple<TinyRemapper, Mutable<MemoryMappingTree>, List<TinyRemapper.ApplyVisitorProvider>> pair = TinyRemapperHelper.getTinyRemapper(getProject(), true, builder -> { });
		TinyRemapper remapper = remapperArray[0] = pair.getLeft();

		assetsOut(input, vanillaAssets);

		if (getExtension().isForgeAndNotOfficial()) {
			assetsOut(inputForge, forgeAssets);
		}

		remap(remapper, pair.getMiddle(), pair.getRight(), vanilla, forge, MappingsNamespace.OFFICIAL.toString());
	}

	public static class Info {
		Path assets;
		Path input;
		Path outputMapped;
		Path outputIntermediary;
		Path outputSrg;

		public Info(Path assets, Path input, Path outputMapped, Path outputIntermediary, Path outputSrg) {
			this.assets = assets;
			this.input = input;
			this.outputMapped = outputMapped;
			this.outputIntermediary = outputIntermediary;
			this.outputSrg = outputSrg;
		}
	}

	public void remap(TinyRemapper remapper, Mutable<MemoryMappingTree> mappings, List<TinyRemapper.ApplyVisitorProvider> postApply, Info vanilla, @Nullable Info forge, String fromM) throws IOException {
		Set<String> classNames = getExtension().isForge() ? InnerClassRemapper.readClassNames(vanilla.input) : null;

		for (String toM : getExtension().isForge() ? Arrays.asList(MappingsNamespace.INTERMEDIARY.toString(), MappingsNamespace.SRG.toString(), MappingsNamespace.NAMED.toString()) : Arrays.asList(MappingsNamespace.INTERMEDIARY.toString(), MappingsNamespace.NAMED.toString())) {
			Path output = MappingsNamespace.NAMED.toString().equals(toM) ? vanilla.outputMapped : MappingsNamespace.SRG.toString().equals(toM) ? vanilla.outputSrg : vanilla.outputIntermediary;
			Path outputForge = forge == null ? null : MappingsNamespace.NAMED.toString().equals(toM) ? forge.outputMapped : MappingsNamespace.SRG.toString().equals(toM) ? forge.outputSrg : forge.outputIntermediary;
			InputTag vanillaTag = remapper.createInputTag();
			InputTag forgeTag = remapper.createInputTag();
			Stopwatch stopwatch = Stopwatch.createStarted();
			final boolean fixSignatures = getExtension().getMappingsProvider().getSignatureFixes() != null;
			getProject().getLogger().lifecycle(":remapping minecraft (TinyRemapper, " + fromM + " -> " + toM + ")");

			remapper.readInputs(vanillaTag, vanilla.input);

			if (forge != null) {
				remapper.readInputs(forgeTag, forge.input);
			}

			remapper.replaceMappings(getMappings(classNames, fromM, toM, mappings));
			if (!MappingsNamespace.INTERMEDIARY.toString().equals(toM)) mappings.setValue(null);
			postApply.clear();

			// Bit ugly but whatever, the whole issue is a bit ugly :)
			AtomicReference<Map<String, String>> remappedSignatures = new AtomicReference<>();

			if (fixSignatures) {
				postApply.add(new TinyRemapper.ApplyVisitorProvider() {
					@Override
					public ClassVisitor insertApplyVisitor(TrClass cls, ClassVisitor next) {
						return new ClassVisitor(Constants.ASM_VERSION, next) {
							@Override
							public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
								Map<String, String> signatureFixes = Objects.requireNonNull(remappedSignatures.get(), "Could not get remapped signatures");

								if (signature == null) {
									signature = signatureFixes.getOrDefault(name, null);

									if (signature != null) {
										getProject().getLogger().info("Replaced signature for {} with {}", name, signature);
									}
								}

								super.visit(version, access, name, signature, superName, interfaces);
							}
						};
					}
				});

				if (MappingsNamespace.INTERMEDIARY.toString().equals(toM)) {
					// No need to remap, as these are already intermediary
					remappedSignatures.set(getExtension().getMappingsProvider().getSignatureFixes());
				} else {
					// Remap the sig fixes from intermediary to the target namespace
					final Map<String, String> remapped = new HashMap<>();
					final TinyRemapper sigTinyRemapper = TinyRemapperHelper.getTinyRemapper(getProject(), fromM, toM);
					final Remapper sigAsmRemapper = sigTinyRemapper.getRemapper();

					// Remap the class names and the signatures using a new tiny remapper instance.
					for (Map.Entry<String, String> entry : getExtension().getMappingsProvider().getSignatureFixes().entrySet()) {
						remapped.put(
								sigAsmRemapper.map(entry.getKey()),
								sigAsmRemapper.mapSignature(entry.getValue(), false)
						);
					}

					sigTinyRemapper.finish();
					remappedSignatures.set(remapped);
				}
			}

			OutputRemappingHandler.remap(remapper, vanilla.assets, output, null, vanillaTag);

			if (forge != null) {
				OutputRemappingHandler.remap(remapper, forge.assets, outputForge, null, forgeTag);

				if (getExtension().getForgeProvider().isFG2()) {
					//FG2 - remove binpatches for the dev environment to work
					try (FileSystem fs = FileSystems.newFileSystem(outputForge, Map.of("create", "false"))) {
						Files.deleteIfExists(fs.getPath("binpatches.pack.lzma"));

						//TODO: FIXME, hack. remove forge trying to transform class names for fg2 dev launch
						Files.deleteIfExists(fs.getPath("net/minecraftforge/fml/common/asm/transformers/DeobfuscationTransformer.class"));
					}
				}
			}

			getProject().getLogger().lifecycle(":remapped minecraft (TinyRemapper, " + fromM + " -> " + toM + ") in " + stopwatch);
			remapper.removeInput();
			mappings.setValue(null);

			if (getExtension().isForge() && !"srg".equals(toM)) {
				getProject().getLogger().info(":running minecraft finalising tasks");

				MemoryMappingTree yarnWithSrg = getExtension().getMappingsProvider().getMappingsWithSrg();
				AtRemapper.remap(getProject().getLogger(), output, yarnWithSrg);
				CoreModClassRemapper.remapJar(output, yarnWithSrg, getProject().getLogger());
			}
		}
	}

	public Set<IMappingProvider> getMappings(@Nullable Set<String> fromClassNames, String fromM, String toM, Mutable<MemoryMappingTree> mappings) throws IOException {
		Set<IMappingProvider> providers = new HashSet<>();
		mappings.setValue(getExtension().isForge() ? getExtension().getMappingsProvider().getMappingsWithSrg() : getExtension().getMappingsProvider().getMappings());
		providers.add(TinyRemapperHelper.create(mappings.getValue(), fromM, toM, true));

		if (getExtension().isForge()) {
			if (fromClassNames != null) {
				providers.add(InnerClassRemapper.of(fromClassNames, getExtension().getMappingsProvider().getMappingsWithSrg(), fromM, toM));
			}
		} else {
			providers.add(out -> TinyRemapperHelper.JSR_TO_JETBRAINS.forEach(out::acceptClass));
		}

		return providers;
	}

	protected void addDependencies(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) {
		getProject().getDependencies().add(Constants.Configurations.MINECRAFT_NAMED,
				getProject().getDependencies().module("net.minecraft:" + minecraftProvider.getJarPrefix() + "minecraft-mapped:" + getMinecraftProvider().minecraftVersion() + "/" + getExtension().getMappingsProvider().mappingsIdentifier()));
	}

	public void initFiles(MinecraftProviderImpl minecraftProvider, MappingsProviderImpl mappingsProvider) {
		this.minecraftProvider = minecraftProvider;
		minecraftIntermediaryJar = new File(getExtension().getMappingsProvider().mappingsWorkingDir().toFile(), "minecraft-intermediary.jar");
		minecraftSrgJar = !getExtension().isForge() ? null : new File(getExtension().getMappingsProvider().mappingsWorkingDir().toFile(), "minecraft-srg.jar");
		minecraftMappedJar = new File(getExtension().getMappingsProvider().mappingsWorkingDir().toFile(), minecraftProvider.getJarPrefix() + "minecraft-mapped.jar");
		inputJar = getExtension().isForge() ? mappingsProvider.patchedProvider.getMergedJar() : minecraftProvider.getMergedJar();

		if (getExtension().isForgeAndNotOfficial()) {
			inputForgeJar = mappingsProvider.patchedProvider.getForgeMergedJar();
			forgeIntermediaryJar = new File(getExtension().getMappingsProvider().mappingsWorkingDir().toFile(), "forge/forge-intermediary.jar");
			forgeSrgJar = new File(getExtension().getMappingsProvider().mappingsWorkingDir().toFile(), "forge/forge-srg.jar");
			forgeMappedJar = new File(getExtension().getMappingsProvider().mappingsWorkingDir().toFile(), "forge/forge-mapped.jar");
		} else {
			inputForgeJar = null;
			forgeIntermediaryJar = null;
			forgeSrgJar = null;
			forgeMappedJar = null;
		}
	}

	protected File getJarDirectory(File parentDirectory, String type) {
		return new File(parentDirectory, getJarVersionString(type));
	}

	protected String getJarVersionString(String type) {
		return String.format("%s-%s%s", type, getExtension().getMappingsProvider().mappingsIdentifier(), minecraftProvider.getJarPrefix());
	}

	public File getIntermediaryJar() {
		return minecraftIntermediaryJar;
	}

	public File getSrgJar() {
		return minecraftSrgJar;
	}

	public File getMappedJar() {
		return minecraftMappedJar;
	}

	public final File getBaseMappedJar() {
		return minecraftMappedJar;
	}

	public File getForgeIntermediaryJar() {
		return forgeIntermediaryJar;
	}

	public File getForgeSrgJar() {
		return forgeSrgJar;
	}

	public File getForgeMappedJar() {
		return forgeMappedJar;
	}

	public File getUnpickedJar() {
		return new File(getExtension().getMappingsProvider().mappingsWorkingDir().toFile(), "minecraft-unpicked.jar");
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MINECRAFT_NAMED;
	}
}
