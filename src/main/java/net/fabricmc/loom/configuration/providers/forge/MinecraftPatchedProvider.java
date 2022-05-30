/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2022 FabricMC
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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
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
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import de.oceanlabs.mcp.mcinjector.adaptors.ParameterAnnotationFixer;
import dev.architectury.tinyremapper.InputTag;
import dev.architectury.tinyremapper.MetaInfFixer;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;
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
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.accesstransformer.AccessTransformerJarProcessor;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpConfigData;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpConfigStep;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpExecutor;
import net.fabricmc.loom.configuration.providers.forge.minecraft.ForgeMinecraftProvider;
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
import net.fabricmc.loom.util.srg.InnerClassRemapper;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class MinecraftPatchedProvider {
	private static final String LOOM_PATCH_VERSION_KEY = "Loom-Patch-Version";
	private static final String CURRENT_LOOM_PATCH_VERSION = "7";
	private static final String NAME_MAPPING_SERVICE_PATH = "/inject/META-INF/services/cpw.mods.modlauncher.api.INameMappingService";

	private final Project project;
	private final Logger logger;
	private final MinecraftProvider minecraftProvider;
	private final Type type;

	// Step 1: Remap Minecraft to SRG, merge if needed
	private Path minecraftSrgJar;
	// OR Step 1: Merge without remapping
	private Path minecraftMergedJar;
	// Step 2: Binary Patch, remap if needed
	private Path minecraftPatchedJar;
	private Path minecraftPatchedSrgJar;
	// Step 3: Access Transform
	private Path minecraftPatchedSrgAtJar;
	// Step 4: Remap Patched AT & Forge to official
	private Path minecraftPatchedAtJar;
	private Path minecraftClientExtra;

	private boolean dirty = false;
	private boolean isNotchObf = false;

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
		ForgeProvider forgeProvider = getExtension().getForgeProvider();
		String forgeVersion = forgeProvider.getVersion().getCombined();
		Path forgeWorkingDir = forgeProvider.getGlobalCache().toPath();
		String patchId = "forge-" + forgeVersion + "-";

		minecraftProvider.setJarPrefix(patchId);

		isNotchObf = getExtension().getForgeUserdevProvider().isNotchObfPatches();

		if (isNotchObf) {
			if (type != Type.MERGED) {
				throw new UnsupportedOperationException("Split Jars are not supported with legacy FG3 versions.");
			}

			minecraftMergedJar = forgeWorkingDir.resolve("minecraft-" + Type.MERGED.id + ".jar");
			minecraftPatchedJar = forgeWorkingDir.resolve("minecraft-" + Type.MERGED.id + "-patched.jar");
		} else {
			minecraftSrgJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-srg.jar");
		}

		minecraftPatchedSrgJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-srg-patched.jar");
		minecraftPatchedSrgAtJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-srg-at-patched.jar");
		minecraftPatchedAtJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-at-patched.jar");
		minecraftClientExtra = forgeWorkingDir.resolve("forge-client-extra.jar");
	}

	private void cleanAllCache() throws IOException {
		for (Path path : getGlobalCaches()) {
			Files.deleteIfExists(path);
		}
	}

	private Path[] getGlobalCaches() {
		if (isNotchObf) {
			return new Path[] {
					minecraftMergedJar,
					minecraftPatchedJar,
					minecraftPatchedSrgJar,
					minecraftPatchedSrgAtJar,
					minecraftPatchedAtJar,
					minecraftClientExtra,
			};
		}

		return new Path[] {
				minecraftSrgJar,
				minecraftPatchedSrgJar,
				minecraftPatchedSrgAtJar,
				minecraftPatchedAtJar,
				minecraftClientExtra,
		};
	}

	private void checkCache() throws IOException {
		if (LoomGradlePlugin.refreshDeps || Stream.of(getGlobalCaches()).anyMatch(Files::notExists)
				|| !isPatchedJarUpToDate(minecraftPatchedAtJar)) {
			cleanAllCache();
		}
	}

	private void executeMcp(String step, Path outputPath) throws IOException {
		McpConfigData data = getExtension().getMcpConfigProvider().getData();
		List<McpConfigStep> steps = data.steps().get(type.mcpId);
		McpExecutor executor = new McpExecutor(project, minecraftProvider, Files.createTempDirectory("loom-mcp"), steps, data.functions());
		Path output = executor.executeUpTo(step);
		Files.copy(output, outputPath);
	}

	public void provide() throws Exception {
		initPatchedFiles();
		checkCache();

		this.dirty = false;

		Path patchInput;
		Path patchOutput;

		if (isNotchObf) {
			if (Files.notExists(minecraftMergedJar)) {
				this.dirty = true;
				executeMcp("merge", minecraftMergedJar);
			}

			patchInput = minecraftMergedJar;
			patchOutput = minecraftPatchedJar;
		} else {
			if (Files.notExists(minecraftSrgJar)) {
				this.dirty = true;
				executeMcp("rename", minecraftSrgJar);
			}

			patchInput = minecraftSrgJar;
			patchOutput = minecraftPatchedSrgJar;
		}

		if (dirty || Files.notExists(patchOutput)) {
			this.dirty = true;
			patchJars(patchInput, patchOutput);
		}

		if (isNotchObf) {
			if (dirty || Files.notExists(minecraftPatchedSrgJar)) {
				File specialSource = DependencyDownloader.download(project, Constants.Dependencies.SPECIAL_SOURCE, false, false).getSingleFile();
				String mainClass;

				try (JarFile jarFile = new JarFile(specialSource)) {
					mainClass = jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
				} catch (IOException e) {
					throw new IOException("Could not determine main class for SpecialSource", e);
				}

				ForgeToolExecutor.exec(project, spec -> {
					spec.classpath(specialSource);
					spec.getMainClass().set(mainClass);
					spec.args(
							"--in-jar", minecraftPatchedJar.toAbsolutePath().toString(),
							"--out-jar", minecraftPatchedSrgJar.toAbsolutePath().toString(),
							"--srg-in", getExtension().getMcpConfigProvider().getMappings().toAbsolutePath().toString()
					);
				});
			}
		}

		if (dirty || Files.notExists(minecraftPatchedSrgAtJar)) {
			this.dirty = true;
			accessTransformForge();
		}
	}

	public void remapJar() throws Exception {
		if (dirty) {
			remapPatchedJar();
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

	private TinyRemapper buildRemapper(Path input) throws IOException {
		return buildRemapper(input, getExtension().getMappingsProvider().getMappingsWithSrg(), "srg", "official");
	}

	private TinyRemapper buildRemapper(Path input, MemoryMappingTree mappingsWithSrg, String from, String to) throws IOException {
		Path[] libraries = TinyRemapperHelper.getMinecraftDependencies(project);

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.logger(logger::lifecycle)
				.logUnknownInvokeDynamic(false)
				.withMappings(TinyRemapperHelper.create(mappingsWithSrg, from, to, true))
				.withMappings(InnerClassRemapper.of(InnerClassRemapper.readClassNames(input), mappingsWithSrg, from, to))
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

		try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + jarFile.toUri()), ImmutableMap.of("create", false))) {
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

		try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + jarFile.toUri()), ImmutableMap.of("create", false))) {
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
		Stopwatch stopwatch = Stopwatch.createStarted();

		logger.lifecycle(":access transforming minecraft");

		Path input = minecraftPatchedSrgJar;
		Path target = minecraftPatchedSrgAtJar;
		Files.deleteIfExists(target);

		AccessTransformerJarProcessor.executeAt(project, input, target, args -> {
			args.add("--atFile");
			args.add(getExtension().getForgeUserdevProvider().getAccessTransformerConfig().toAbsolutePath().toString());
		});

		logger.lifecycle(":access transformed minecraft in " + stopwatch.stop());
	}

	private void mergeForge(Path input) throws IOException {
		Path output = Files.createTempFile("forge-merged", ".tmp.jar");

		Path forgeJar = getForgeJar().toPath();
		Path forgeUserdevJar = getForgeUserdevJar().toPath();
		Files.deleteIfExists(output);

		TinyRemapper remapper = TinyRemapper.newRemapper().build();

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build()) {
			outputConsumer.addNonClassFiles(input);
			outputConsumer.addNonClassFiles(forgeJar, remapper, List.of(MetaInfFixer.INSTANCE, new UserdevFilter()));

			InputTag mcTag = remapper.createInputTag();
			InputTag forgeTag = remapper.createInputTag();
			List<CompletableFuture<?>> futures = new ArrayList<>();
			futures.add(remapper.readInputsAsync(mcTag, input));
			futures.add(remapper.readInputsAsync(forgeTag, forgeJar, forgeUserdevJar));
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
			remapper.apply(outputConsumer, mcTag);
			remapper.apply(outputConsumer, forgeTag);
		} finally {
			remapper.finish();
		}

		copyUserdevFiles(forgeUserdevJar, output);

		Files.copy(output, input, StandardCopyOption.REPLACE_EXISTING);
	}

	private void remapPatchedJar() throws Exception {
		logger.lifecycle(":remapping minecraft (TinyRemapper, srg -> official)");
		Path mcInput = minecraftPatchedSrgAtJar;
		Path mcOutput = minecraftPatchedAtJar;
		Files.deleteIfExists(mcOutput);

		TinyRemapper remapper = buildRemapper(mcInput);

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(mcOutput).build()) {
			outputConsumer.addNonClassFiles(mcInput);
			remapper.readInputs(mcInput);
			remapper.apply(outputConsumer);
		} finally {
			remapper.finish();
		}

		applyLoomPatchVersion(mcOutput);
	}

	private void patchJars(Path input, Path output) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");
		patchJars(input, output, type.patches.apply(getExtension().getPatchProvider(), getExtension().getForgeUserdevProvider()));

		mergeForge(output);

		copyMissingClasses(input, output);
		deleteParameterNames(output);

		if (getExtension().isForgeAndNotOfficial()) {
			fixParameterAnnotation(output);
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

	public Path getMinecraftPatchedSrgJar() {
		return minecraftPatchedSrgJar;
	}

	public Path getMinecraftPatchedJar() {
		return minecraftPatchedAtJar;
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

	private final class UserdevFilter implements OutputConsumerPath.ResourceRemapper {
		@Override
		public boolean canTransform(TinyRemapper tinyRemapper, Path path) {
			List<Pattern> patterns = getExtension().getForgeUserdevProvider().getUniversalFilters();
			return !patterns.isEmpty() && patterns.stream().noneMatch(pattern -> pattern.matcher(path.toString()).matches());
		}

		@Override
		public void transform(Path path, Path path1, InputStream inputStream, TinyRemapper tinyRemapper) throws IOException {
			// Remove anything that userdev wants to be filtered
		}
	}
}
