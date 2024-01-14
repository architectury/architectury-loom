/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2023 FabricMC
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import de.oceanlabs.mcp.mcinjector.adaptors.ParameterAnnotationFixer;
import dev.architectury.loom.util.TempFiles;
import dev.architectury.tinyremapper.InputTag;
import dev.architectury.tinyremapper.NonClassCopyMode;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;

import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.utils.AccessTransformSetMapper;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarMerger;

import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.legacyforge.CoreModManagerTransformer;
import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;

import org.cadixdev.at.AccessTransformSet;
import org.cadixdev.at.io.AccessTransformFormats;
import org.cadixdev.lorenz.MappingSet;
import org.gradle.api.Project;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.accesstransformer.AccessTransformerJarProcessor;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpConfigProvider;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpExecutor;
import net.fabricmc.loom.configuration.providers.forge.minecraft.ForgeMinecraftProvider;
import net.fabricmc.loom.configuration.providers.mappings.TinyMappingsService;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyDownloader;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.ForgeToolExecutor;
import net.fabricmc.loom.util.MappingsProviderVerbose;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.function.FsPathConsumer;
import net.fabricmc.loom.util.service.ScopedSharedServiceManager;
import net.fabricmc.loom.util.service.SharedServiceManager;
import net.fabricmc.loom.util.srg.InnerClassRemapper;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class MinecraftPatchedProvider {
	private static final String LOOM_PATCH_VERSION_KEY = "Loom-Patch-Version";
	private static final String CURRENT_LOOM_PATCH_VERSION = "8";
	private static final String NAME_MAPPING_SERVICE_PATH = "/inject/META-INF/services/cpw.mods.modlauncher.api.INameMappingService";

	private final Project project;
	private final Logger logger;
	private final MinecraftProvider minecraftProvider;
	private final Type type;

	// Step 1: Remap Minecraft to SRG, merge if needed
	private Path minecraftSrgJar;
	// Step 2: Binary Patch
	private Path minecraftPatchedSrgJar;
	// Step 3: Access Transform
	private Path minecraftPatchedSrgAtJar;
	// Step 4: Remap Patched AT & Forge to official
	private Path minecraftPatchedJar;
	private Path minecraftClientExtra;

	private boolean dirty = false;
	private MappingConfiguration mappingConfiguration;

	public static MinecraftPatchedProvider get(Project project) {
		MinecraftProvider provider = LoomGradleExtension.get(project).getMinecraftProvider();

		if (provider instanceof ForgeMinecraftProvider patched) {
			return patched.getPatchedProvider();
		} else {
			throw new UnsupportedOperationException("Project " + project.getPath() + " does not use MinecraftPatchedProvider!");
		}
	}

	public MinecraftPatchedProvider(Project project, MinecraftProvider minecraftProvider, Type type) {
		this.project = project;
		this.logger = project.getLogger();
		this.minecraftProvider = minecraftProvider;
		this.type = type;
	}

	private LoomGradleExtension getExtension() {
		return LoomGradleExtension.get(project);
	}

	private void initPatchedFiles() {
		String forgeVersion = getExtension().getForgeProvider().getVersion().getCombined();
		Path forgeWorkingDir = ForgeProvider.getForgeCache(project);
		String patchId = "forge-" + forgeVersion + "-";

		minecraftProvider.setJarPrefix(patchId);

		minecraftSrgJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-srg.jar");
		minecraftPatchedSrgJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-srg-patched.jar");
		minecraftPatchedSrgAtJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-srg-at-patched.jar");
		minecraftPatchedJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-patched.jar");
		minecraftClientExtra = forgeWorkingDir.resolve("forge-client-extra.jar");
	}

	private void cleanAllCache() throws IOException {
		for (Path path : getGlobalCaches()) {
			Files.deleteIfExists(path);
		}
	}

	private Path[] getGlobalCaches() {
		Path[] files = {
				minecraftSrgJar,
				minecraftPatchedSrgJar,
				minecraftPatchedSrgAtJar,
				minecraftPatchedJar,
				minecraftClientExtra,
		};

		return files;
	}

	private void checkCache() throws IOException {
		if (getExtension().refreshDeps() || Stream.of(getGlobalCaches()).anyMatch(Files::notExists)
				|| !isPatchedJarUpToDate(minecraftPatchedJar)) {
			cleanAllCache();
		}
	}

	protected void mergeJars() throws IOException {
		project.getLogger().info(":merging jars");

		File jarToMerge = minecraftProvider.getMinecraftServerJar();

		if (minecraftProvider.getServerBundleMetadata() != null) {
			minecraftProvider.extractBundledServerJar();
			jarToMerge = minecraftProvider.getMinecraftExtractedServerJar();
		}

		Objects.requireNonNull(jarToMerge, "Cannot merge null input jar?");

		try (var jarMerger = new MinecraftJarMerger(minecraftProvider.getMinecraftClientJar(), jarToMerge, minecraftSrgJar.toFile())) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}
	}

	public void provide() throws Exception {
		initPatchedFiles();
		checkCache();

		this.dirty = false;
		if (Files.notExists(minecraftSrgJar)) {
			this.dirty = true;

			try (var tempFiles = new TempFiles()) {
				if (getExtension().isLegacyForge()) {
					mergeJars();
				} else {
					McpExecutor executor = createMcpExecutor(tempFiles.directory("loom-mcp"));
					Path output = executor.enqueue("rename").execute();
					Files.copy(output, minecraftSrgJar);
				}
			}
		}

		if (dirty || Files.notExists(minecraftPatchedSrgJar)) {
			this.dirty = true;
			patchJars();
		}

		if (mappingConfiguration != null) {
			mappingConfiguration.setupPost(project);
		}

		if (dirty || Files.notExists(minecraftPatchedSrgAtJar)) {
			this.dirty = true;
			accessTransformForge();
		}
	}

	public void remapJar() throws Exception {
		if (dirty) {
			try (var serviceManager = new ScopedSharedServiceManager()) {
				remapPatchedJar(serviceManager);
			}

			fillClientExtraJar();
		}

		this.dirty = false;
		DependencyProvider.addDependency(project, minecraftClientExtra, Constants.Configurations.FORGE_EXTRA);
	}

	private void fillClientExtraJar() throws IOException {
		Files.deleteIfExists(minecraftClientExtra);
		FileSystemUtil.getJarFileSystem(minecraftClientExtra, true).close();

		copyNonClassFiles(minecraftProvider.getMinecraftClientJar().toPath(), minecraftClientExtra);
	}

	private TinyRemapper buildRemapper(SharedServiceManager serviceManager, Path input) throws IOException {
		Path[] libraries = TinyRemapperHelper.getMinecraftCompileLibraries(project);
		TinyMappingsService mappingsService = getExtension().getMappingConfiguration().getMappingsService(serviceManager, true);
		MemoryMappingTree mappingsWithSrg = mappingsService.getMappingTree();

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.logger(logger::lifecycle)
				.logUnknownInvokeDynamic(false)
				.withMappings(TinyRemapperHelper.create(mappingsWithSrg, "srg", "official", true))
				.withMappings(InnerClassRemapper.of(InnerClassRemapper.readClassNames(input), mappingsWithSrg, "srg", "official"))
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true)
				.build();

		if (project.getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) < 0) {
			MappingsProviderVerbose.saveFile(remapper);
		}

		remapper.readClassPath(libraries);
		remapper.prepareClasses();
		return remapper;
	}

	private void fixParameterAnnotation(Path jarFile) throws Exception {
		logger.info(":fixing parameter annotations for " + jarFile.toAbsolutePath());
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jarFile, false)) {
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter();

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] bytes = Files.readAllBytes(file);
					ClassReader reader = new ClassReader(bytes);
					ClassNode node = new ClassNode();
					ClassVisitor visitor = new ParameterAnnotationFixer(node, null);
					reader.accept(visitor, 0);

					ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					node.accept(writer);
					byte[] out = writer.toByteArray();

					if (!Arrays.equals(bytes, out)) {
						Files.delete(file);
						Files.write(file, out);
					}
				});
			}

			completer.complete();
		}

		logger.info(":fixed parameter annotations for " + jarFile.toAbsolutePath() + " in " + stopwatch);
	}

	private void deleteParameterNames(Path jarFile) throws Exception {
		logger.info(":deleting parameter names for " + jarFile.toAbsolutePath());
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jarFile, false)) {
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter();
			Pattern vignetteParameters = Pattern.compile("p_[0-9a-zA-Z]+_(?:[0-9a-zA-Z]+_)?");

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] bytes = Files.readAllBytes(file);
					ClassReader reader = new ClassReader(bytes);
					ClassWriter writer = new ClassWriter(0);

					reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
						@Override
						public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
							return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
								@Override
								public void visitParameter(String name, int access) {
									if (vignetteParameters.matcher(name).matches()) {
										super.visitParameter(null, access);
									} else {
										super.visitParameter(name, access);
									}
								}

								@Override
								public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
									if (!vignetteParameters.matcher(name).matches()) {
										super.visitLocalVariable(name, descriptor, signature, start, end, index);
									}
								}
							};
						}
					}, 0);

					byte[] out = writer.toByteArray();

					if (!Arrays.equals(bytes, out)) {
						Files.delete(file);
						Files.write(file, out);
					}
				});
			}

			completer.complete();
		}

		logger.info(":deleted parameter names for " + jarFile.toAbsolutePath() + " in " + stopwatch);
	}

	private File getForgeJar() {
		return getExtension().getForgeUniversalProvider().getForge();
	}

	private File getForgeUserdevJar() {
		return getExtension().getForgeUserdevProvider().getUserdevJar();
	}

	private boolean isPatchedJarUpToDate(Path jar) throws IOException {
		if (Files.notExists(jar)) return false;

		byte[] manifestBytes = ZipUtils.unpackNullable(jar, "META-INF/MANIFEST.MF");

		if (manifestBytes == null) {
			return false;
		}

		Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
		Attributes attributes = manifest.getMainAttributes();
		String value = attributes.getValue(LOOM_PATCH_VERSION_KEY);

		if (Objects.equals(value, CURRENT_LOOM_PATCH_VERSION)) {
			return true;
		} else {
			logger.lifecycle(":forge patched jars not up to date. current version: " + value);
			return false;
		}
	}

	private void accessTransformForge() throws IOException {
		Path input = minecraftPatchedSrgJar;
		Path target = minecraftPatchedSrgAtJar;
		accessTransform(project, input, target);
	}

	public static void accessTransform(Project project, Path input, Path target) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();

		project.getLogger().lifecycle(":access transforming minecraft");

		LoomGradleExtension extension = LoomGradleExtension.get(project);
		List<Path> atSources = List.of(
				extension.getForgeUniversalProvider().getForge().toPath(),
				extension.getForgeUserdevProvider().getUserdevJar().toPath(),
				((ForgeMinecraftProvider) extension.getMinecraftProvider())
						.getPatchedProvider()
						.getMinecraftPatchedSrgJar()
		);

		Files.deleteIfExists(target);

		try (var tempFiles = new TempFiles()) {
			AccessTransformerJarProcessor.executeAt(project, input, target, args -> {
				for (Path jar : atSources) {
					byte[] atBytes = ZipUtils.unpackNullable(jar, Constants.Forge.ACCESS_TRANSFORMER_PATH);
					if (atBytes == null) {
						atBytes = ZipUtils.unpackNullable(jar, "forge_at.cfg");
						if (atBytes != null) {
							atBytes = remapAt(extension, atBytes);
							project.getLogger().info("remapped an AT!");
						}
					}
					if (atBytes != null) {
						Path tmpFile = tempFiles.file("at-conf", ".cfg");
						Files.write(tmpFile, atBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
						args.add("--atFile");
						args.add(tmpFile.toAbsolutePath().toString());
					}
				}
			});
		}

		project.getLogger().lifecycle(":access transformed minecraft in " + stopwatch.stop());
	}

	/**
	 * so, at this point, minecraft is using official (obfuscated) mappings, but the access transformers are in SRG, we need to fix that.
	 * @param forgeAt
	 */
	private static byte[] remapAt(LoomGradleExtension extension, byte[] forgeAt) throws IOException {
		AccessTransformSet accessTransformSet = AccessTransformSet.create();
		AccessTransformFormats.FML.read(new InputStreamReader(new ByteArrayInputStream(forgeAt)), accessTransformSet);
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		MappingReader.read(extension.getMappingConfiguration().tinyMappingsWithSrg, mappingTree);
		MappingSet mappingSet = new TinyMappingsReader(mappingTree, "srg", "official").read();
		accessTransformSet = AccessTransformSetMapper.remap(accessTransformSet, mappingSet);
		ByteArrayOutputStream remappedOut = new ByteArrayOutputStream();
		// TODO the extra BufferedWriter wrapper and closing can be removed once https://github.com/CadixDev/at/issues/6 is fixed
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(remappedOut));
		AccessTransformFormats.FML.write(writer, accessTransformSet);
		writer.close();
		var bytes = remappedOut.toByteArray();
		var g = Files.createTempFile("remapped-at", ".cfg");
		Files.write(g, bytes);
		return bytes;
	}

	private void patchForge(Logger logger, File input) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching forge");

		Files.copy(input.toPath(), input.toPath(), StandardCopyOption.REPLACE_EXISTING);

		// For the development environment, we need to remove the binpatches, otherwise forge will try to re-apply them
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(input, false)) {
			Files.delete(fs.get().getPath("binpatches.pack.lzma"));
		}

		// Older versions of Forge rely on utility classes from log4j-core 2.0-beta9 but we'll upgrade the runtime to a
		// release version (so we can use the TerminalConsoleAppender) where some of those classes have been moved from
		// a `helpers` to a `utils` package.
		// To allow Forge to work regardless, we'll re-package those helper classes into the forge jar.
		Path log4jBeta9 = Arrays.stream(TinyRemapperHelper.getMinecraftCompileLibraries(project))
				.filter(it -> it.getFileName().toString().equals("log4j-core-2.0-beta9.jar"))
				.findAny()
				.orElse(null);
		if (log4jBeta9 != null) {
			Predicate<Path> isHelper = path -> path.startsWith("/org/apache/logging/log4j/core/helpers");
			walkFileSystems(log4jBeta9, input.toPath(), isHelper, this::copyReplacing);
		}

		// While Forge will discover mods on the classpath, it won't do the same for ATs, coremods or tweakers.
		// ForgeGradle "solves" this problem using a huge, gross hack (GradleForgeHacks and related classes), and only
		// for ATs and coremods, not tweakers.
		// No clue why FG went the hack route when it's the same project and they could have just added first-party
		// support for loading both from the classpath right into Forge (it's even really simply to do).
		// We'll have none of those hacks and instead patch first-party support into Forge.
		ZipUtils.transform(getForgeJar().toPath(), Stream.of(new Pair<>(CoreModManagerTransformer.FILE, original -> {
			ClassReader reader = new ClassReader(original);
			ClassWriter writer = new ClassWriter(reader, 0);
			reader.accept(new CoreModManagerTransformer(writer), 0);
			return writer.toByteArray();
		})));

		logger.lifecycle(":patched forge in " + stopwatch.stop());
	}

	private void remapPatchedJar(SharedServiceManager serviceManager) throws Exception {
		logger.lifecycle(":remapping minecraft (TinyRemapper, srg -> official)");
		Path mcInput = minecraftPatchedSrgAtJar;
		Path mcOutput = minecraftPatchedJar;
		Path forgeJar = getForgeJar().toPath();
		Path forgeUserdevJar = getForgeUserdevJar().toPath();
		Files.deleteIfExists(mcOutput);

		TinyRemapper remapper = buildRemapper(serviceManager, mcInput);

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(mcOutput).build()) {
			outputConsumer.addNonClassFiles(mcInput);
			outputConsumer.addNonClassFiles(forgeJar, NonClassCopyMode.FIX_META_INF, remapper);

			InputTag mcTag = remapper.createInputTag();
			InputTag forgeTag = remapper.createInputTag();
			List<CompletableFuture<?>> futures = new ArrayList<>();
			futures.add(remapper.readInputsAsync(mcTag, mcInput));
			futures.add(remapper.readInputsAsync(forgeTag, forgeJar, forgeUserdevJar));
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
			remapper.apply(outputConsumer, mcTag);
			remapper.apply(outputConsumer, forgeTag);
		} finally {
			remapper.finish();
		}
		copyUserdevFiles(forgeUserdevJar, mcOutput);
		if (getExtension().isLegacyForge()) {
			patchForge(logger, mcOutput.toFile());
		}
		applyLoomPatchVersion(mcOutput);
	}

	private void patchLegacyJars() throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");
		var minecraftServerPatchedJar = Files.createTempFile("server", ".jar");
		var minecraftClientPatchedJar = Files.createTempFile("client", ".jar");
		MinecraftProvider minecraftProvider = getExtension().getMinecraftProvider();
		PatchProvider patchProvider = getExtension().getPatchProvider();
		patchJars(minecraftProvider.getMinecraftServerJar().toPath(), minecraftServerPatchedJar, patchProvider.serverPatches);
		patchJars(minecraftProvider.getMinecraftClientJar().toPath(), minecraftClientPatchedJar, patchProvider.clientPatches);

		try (var jarMerger = new MinecraftJarMerger(minecraftClientPatchedJar.toFile(), minecraftServerPatchedJar.toFile(), minecraftPatchedSrgJar.toFile())) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}

		copyMissingClasses(minecraftSrgJar, minecraftPatchedSrgJar);
		deleteParameterNames(minecraftPatchedSrgJar);

		if (getExtension().isForgeAndNotOfficial()) {
			fixParameterAnnotation(minecraftPatchedSrgJar);
		}


		logger.lifecycle(":patched jars in " + stopwatch.stop());
	}

	private void patchJars() throws Exception {
		if (getExtension().isLegacyForge()) {
			patchLegacyJars();
			return;
		}
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");
		patchJars(minecraftSrgJar, minecraftPatchedSrgJar, type.patches.apply(getExtension().getPatchProvider(), getExtension().getForgeUserdevProvider()));
		copyMissingClasses(minecraftSrgJar, minecraftPatchedSrgJar);
		deleteParameterNames(minecraftPatchedSrgJar);

		if (getExtension().isForgeAndNotOfficial()) {
			fixParameterAnnotation(minecraftPatchedSrgJar);
		}

		logger.lifecycle(":patched jars in " + stopwatch.stop());
	}

	private void patchJars(Path clean, Path output, Path patches) {
		ForgeToolExecutor.exec(project, spec -> {
			ForgeUserdevProvider.BinaryPatcherConfig config = getExtension().getForgeUserdevProvider().binaryPatcherConfig;
			spec.classpath(DependencyDownloader.download(project, config.dependency()));
			spec.getMainClass().set("net.minecraftforge.binarypatcher.ConsoleTool");

			for (String arg : config.args()) {
				String actual = switch (arg) {
				case "{clean}" -> clean.toAbsolutePath().toString();
				case "{output}" -> output.toAbsolutePath().toString();
				case "{patch}" -> patches.toAbsolutePath().toString();
				default -> arg;
				};
				spec.args(actual);
			}
		});
	}

	private void walkFileSystems(Path source, Path target, Predicate<Path> filter, Function<FileSystem, Iterable<Path>> toWalk, FsPathConsumer action)
			throws IOException {
		try (FileSystemUtil.Delegate sourceFs = FileSystemUtil.getJarFileSystem(source, false);
				FileSystemUtil.Delegate targetFs = FileSystemUtil.getJarFileSystem(target, false)) {
			for (Path sourceDir : toWalk.apply(sourceFs.get())) {
				Path dir = sourceDir.toAbsolutePath();
				if (!Files.exists(dir)) continue;
				Files.walk(dir)
						.filter(Files::isRegularFile)
						.filter(filter)
						.forEach(it -> {
							boolean root = dir.getParent() == null;

							try {
								Path relativeSource = root ? it : dir.relativize(it);
								Path targetPath = targetFs.get().getPath(relativeSource.toString());
								action.accept(sourceFs.get(), targetFs.get(), it, targetPath);
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						});
			}
		}
	}

	private void walkFileSystems(Path source, Path target, Predicate<Path> filter, FsPathConsumer action) throws IOException {
		walkFileSystems(source, target, filter, FileSystem::getRootDirectories, action);
	}

	private void copyMissingClasses(Path source, Path target) throws IOException {
		walkFileSystems(source, target, it -> it.toString().endsWith(".class"), (sourceFs, targetFs, sourcePath, targetPath) -> {
			if (Files.exists(targetPath)) return;
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	private void copyNonClassFiles(Path source, Path target) throws IOException {
		Predicate<Path> filter = file -> {
			String s = file.toString();
			return !s.endsWith(".class") && !s.startsWith("/META-INF");
		};

		walkFileSystems(source, target, filter, this::copyReplacing);
	}

	private void copyReplacing(FileSystem sourceFs, FileSystem targetFs, Path sourcePath, Path targetPath) throws IOException {
		Path parent = targetPath.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
	}

	private void copyUserdevFiles(Path source, Path target) throws IOException {
		// Removes the Forge name mapping service definition so that our own is used.
		// If there are multiple name mapping services with the same "understanding" pair
		// (source -> target namespace pair), modlauncher throws a fit and will crash.
		// To use our YarnNamingService instead of MCPNamingService, we have to remove this file.
		Predicate<Path> filter = file -> !file.toString().endsWith(".class") && !file.toString().equals(NAME_MAPPING_SERVICE_PATH);

		walkFileSystems(source, target, filter, fs -> Collections.singleton(fs.getPath("inject")), (sourceFs, targetFs, sourcePath, targetPath) -> {
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	public void applyLoomPatchVersion(Path target) throws IOException {
		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(target, false)) {
			Path manifestPath = delegate.get().getPath("META-INF/MANIFEST.MF");

			Preconditions.checkArgument(Files.exists(manifestPath), "META-INF/MANIFEST.MF does not exist in patched srg jar!");
			Manifest manifest = new Manifest();

			if (Files.exists(manifestPath)) {
				try (InputStream stream = Files.newInputStream(manifestPath)) {
					manifest.read(stream);
					manifest.getMainAttributes().putValue(LOOM_PATCH_VERSION_KEY, CURRENT_LOOM_PATCH_VERSION);
				}
			}

			try (OutputStream stream = Files.newOutputStream(manifestPath, StandardOpenOption.CREATE)) {
				manifest.write(stream);
			}
		}
	}

	public McpExecutor createMcpExecutor(Path cache) {
		McpConfigProvider provider = getExtension().getMcpConfigProvider();
		return new McpExecutor(project, minecraftProvider, cache, provider, type.mcpId);
	}

	public Path getMinecraftSrgJar() {
		return minecraftSrgJar;
	}

	public Path getMinecraftPatchedSrgJar() {
		return minecraftPatchedSrgJar;
	}

	public Path getMinecraftPatchedJar() {
		return minecraftPatchedJar;
	}

	public void setMappingConfiguration(MappingConfiguration mappingConfiguration) {
		this.mappingConfiguration = mappingConfiguration;
	}

	public enum Type {
		CLIENT_ONLY("client", "client", (patch, userdev) -> patch.clientPatches),
		SERVER_ONLY("server", "server", (patch, userdev) -> patch.serverPatches),
		MERGED("merged", "joined", (patch, userdev) -> userdev.joinedPatches);

		private final String id;
		private final String mcpId;
		private final BiFunction<PatchProvider, ForgeUserdevProvider, Path> patches;

		Type(String id, String mcpId, BiFunction<PatchProvider, ForgeUserdevProvider, Path> patches) {
			this.id = id;
			this.mcpId = mcpId;
			this.patches = patches;
		}
	}
}
