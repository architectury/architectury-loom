/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2021 FabricMC
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
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import de.oceanlabs.mcp.mcinjector.adaptors.ParameterAnnotationFixer;
import dev.architectury.tinyremapper.InputTag;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;
import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyDownloader;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.MappingsProviderVerbose;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.function.FsPathConsumer;
import net.fabricmc.loom.util.srg.InnerClassRemapper;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public abstract class MinecraftPatchedProvider extends DependencyProvider {
	private static final String LOOM_PATCH_VERSION_KEY = "Loom-Patch-Version";
	private static final String CURRENT_LOOM_PATCH_VERSION = "5";
	private static final String NAME_MAPPING_SERVICE_PATH = "/inject/META-INF/services/cpw.mods.modlauncher.api.INameMappingService";
	protected final String accessTransformerPath;

	// step 3 - fg2: srg transform, fg3: merge
	protected File minecraftMergedPatchedSrgJar;

	// step 4 - Access transform
	protected File minecraftMergedPatchedSrgAtJar;

	// step 5 - Official Mapped Patched AT & Forge
	protected File minecraftMergedPatchedAtJar;
	protected File forgeMergedJar;
	protected File minecraftClientExtra;
	protected File projectAtHash;

	protected Set<File> projectAts = new HashSet<>();
	protected boolean atDirty = false;
	protected boolean filesDirty = false;
	protected Path mcpConfigMappings;

	protected MinecraftPatchedProvider(Project project, String accessTransformerPath) {
		super(project);
		this.accessTransformerPath = accessTransformerPath;
	}

	public void initFiles() throws IOException {
		filesDirty = false;
		projectAtHash = new File(getDirectories().getProjectPersistentCache(), "at.sha256");
		ConfigurableFileCollection accessTransformers = getExtension().getForge().getAccessTransformers();
		accessTransformers.finalizeValue();
		projectAts = accessTransformers.getFiles();

		if (projectAts.isEmpty()) {
			SourceSet main = getProject().getConvention().findPlugin(JavaPluginConvention.class).getSourceSets().getByName("main");

			for (File srcDir : main.getResources().getSrcDirs()) {
				File projectAt = new File(srcDir, Constants.Forge.ACCESS_TRANSFORMER_PATH);

				if (projectAt.exists()) {
					this.projectAts.add(projectAt);
					break;
				}
			}
		}

		if (isRefreshDeps() || !projectAtHash.exists()) {
			writeAtHash();
			atDirty = !projectAts.isEmpty();
		} else {
			byte[] expected = com.google.common.io.Files.asByteSource(projectAtHash).read();
			byte[] current = getProjectAtsHash();
			boolean mismatched = !Arrays.equals(current, expected);

			if (mismatched) {
				writeAtHash();
			}

			atDirty = mismatched;
		}

		MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
		PatchProvider patchProvider = getExtension().getPatchProvider();
		String minecraftVersion = minecraftProvider.minecraftVersion();
		String patchId = "forge-" + getExtension().getForgeProvider().getVersion().getCombined() + "-";

		if (getExtension().isForgeAndOfficial()) {
			minecraftProvider.setJarPrefix(patchId);
		}

		File globalCache = getExtension().getForgeProvider().getGlobalCache();
		File projectDir = usesProjectCache() ? getExtension().getForgeProvider().getProjectCache() : globalCache;
		projectDir.mkdirs();

		forgeMergedJar = getExtension().isForgeAndOfficial() ? null : new File(globalCache, "forge-official.jar");
		minecraftMergedPatchedAtJar = new File(projectDir, "merged-at-patched.jar");
		minecraftClientExtra = new File(globalCache, "forge-client-extra.jar");
		minecraftMergedPatchedSrgJar = new File(globalCache, "merged-srg-patched.jar");
		minecraftMergedPatchedSrgAtJar = new File(projectDir, "merged-srg-at-patched.jar");
	}

	private byte[] getProjectAtsHash() throws IOException {
		if (projectAts.isEmpty()) return ByteSource.empty().hash(Hashing.sha256()).asBytes();
		List<ByteSource> currentBytes = new ArrayList<>();

		for (File projectAt : projectAts) {
			currentBytes.add(com.google.common.io.Files.asByteSource(projectAt));
		}

		return ByteSource.concat(currentBytes).hash(Hashing.sha256()).asBytes();
	}

	public void cleanAllCache() {
		for (File file : getGlobalCaches()) {
			file.delete();
		}

		cleanProjectCache();
	}

	protected File[] getGlobalCaches() {
		File[] files = {
				minecraftClientExtra
		};

		if (forgeMergedJar != null) {
			files = Arrays.copyOf(files, files.length + 1);
			files[files.length - 1] = forgeMergedJar;
		}

		return files;
	}

	public void cleanProjectCache() {
		for (File file : getProjectCache()) {
			file.delete();
		}
	}

	protected File[] getProjectCache() {
		return new File[] {
				minecraftMergedPatchedSrgAtJar,
				minecraftMergedPatchedAtJar
		};
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		initFiles();
		testCleanAllCaches();
		beginTransform();
	}

	protected abstract void beginTransform() throws Exception;

	public void testCleanAllCaches() throws IOException {
		if (isRefreshDeps() || Stream.of(getGlobalCaches()).anyMatch(((Predicate<File>) File::exists).negate()) || !isPatchedJarUpToDate(minecraftMergedPatchedAtJar)) {
			cleanAllCache();
		} else if (atDirty || Stream.of(getProjectCache()).anyMatch(((Predicate<File>) File::exists).negate())) {
			cleanProjectCache();
		}
	}

	public abstract void endTransform() throws Exception;

	protected void fillClientExtraJar() throws IOException {
		Files.deleteIfExists(minecraftClientExtra.toPath());
		FileSystemUtil.getJarFileSystem(minecraftClientExtra, true).close();

		copyNonClassFiles(getExtension().getMinecraftProvider().minecraftClientJar, minecraftClientExtra);
	}

	private void writeAtHash() throws IOException {
		try (FileOutputStream out = new FileOutputStream(projectAtHash)) {
			out.write(getProjectAtsHash());
		}
	}

	protected TinyRemapper buildRemapper(Path input, String from, String to) throws IOException {
		Path[] libraries = TinyRemapperHelper.getMinecraftDependencies(getProject());
		MemoryMappingTree mappingsWithSrg = getExtension().getMappingsProvider().getMappingsWithSrg();

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.logger(getProject().getLogger()::lifecycle)
				.logUnknownInvokeDynamic(false)
				.withMappings(TinyRemapperHelper.create(mappingsWithSrg, from, to, true))
				.withMappings(InnerClassRemapper.of(InnerClassRemapper.readClassNames(input), mappingsWithSrg, from, to))
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true)
				.fixPackageAccess(true)
				.build();

		if (getProject().getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) < 0) {
			MappingsProviderVerbose.saveFile(remapper);
		}

		remapper.readClassPath(libraries);
		remapper.prepareClasses();
		return remapper;
	}

	protected void fixParameterAnnotation(File jarFile) throws Exception {
		getProject().getLogger().info(":fixing parameter annotations for " + jarFile.getAbsolutePath());
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + jarFile.toURI()), ImmutableMap.of("create", false))) {
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

		getProject().getLogger().info(":fixing parameter annotations for " + jarFile.getAbsolutePath() + " in " + stopwatch);
	}

	protected void deleteParameterNames(File jarFile) throws Exception {
		getProject().getLogger().info(":deleting parameter names for " + jarFile.getAbsolutePath());
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + jarFile.toURI()), ImmutableMap.of("create", false))) {
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

		getProject().getLogger().info(":deleting parameter names for " + jarFile.getAbsolutePath() + " in " + stopwatch);
	}

	protected File getForgeJar() {
		return getExtension().getForgeUniversalProvider().getForge();
	}

	protected File getForgeUserdevJar() {
		return getExtension().getForgeUserdevProvider().getUserdevJar();
	}

	protected boolean isPatchedJarUpToDate(File jar) throws IOException {
		if (!jar.exists()) return false;

		byte[] manifestBytes = ZipUtils.unpackNullable(jar.toPath(), "META-INF/MANIFEST.MF");

		if (manifestBytes == null) {
			return false;
		}

		Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
		Attributes attributes = manifest.getMainAttributes();
		String value = attributes.getValue(LOOM_PATCH_VERSION_KEY);

		if (Objects.equals(value, CURRENT_LOOM_PATCH_VERSION)) {
			return true;
		} else {
			getProject().getLogger().lifecycle(":forge patched jars not up to date. current version: " + value);
			return false;
		}
	}

	protected void accessTransformForge(Logger logger) throws Exception {
		MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
		List<File> toDelete = new ArrayList<>();
		String atDependency = Constants.Dependencies.ACCESS_TRANSFORMERS + (minecraftProvider.isNewerThan21w39a() ? Constants.Dependencies.Versions.ACCESS_TRANSFORMERS_NEW : Constants.Dependencies.Versions.ACCESS_TRANSFORMERS);
		FileCollection classpath = DependencyDownloader.download(getProject(), atDependency);
		Stopwatch stopwatch = Stopwatch.createStarted();

		logger.lifecycle(":access transforming minecraft");

		File input = minecraftMergedPatchedSrgJar;
		File target = minecraftMergedPatchedSrgAtJar;
		Files.deleteIfExists(target.toPath());

		List<String> args = new ArrayList<>();
		args.add("--inJar");
		args.add(input.getAbsolutePath());
		args.add("--outJar");
		args.add(target.getAbsolutePath());

		for (File jar : ImmutableList.of(getForgeJar(), getForgeUserdevJar(), minecraftMergedPatchedSrgJar)) {
			byte[] atBytes = ZipUtils.unpackNullable(jar.toPath(), accessTransformerPath);

			if (atBytes != null) {
				File tmpFile = File.createTempFile("at-conf", ".cfg");
				toDelete.add(tmpFile);
				Files.write(tmpFile.toPath(), atBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				args.add("--atFile");
				args.add(tmpFile.getAbsolutePath());
			}
		}

		if (usesProjectCache()) {
			for (File projectAt : projectAts) {
				args.add("--atFile");
				args.add(projectAt.getAbsolutePath());
			}
		}

		getProject().javaexec(spec -> {
			spec.getMainClass().set("net.minecraftforge.accesstransformer.TransformerProcessor");
			spec.setArgs(args);
			spec.setClasspath(classpath);

			// if running with INFO or DEBUG logging
			if (getProject().getGradle().getStartParameter().getShowStacktrace() != ShowStacktrace.INTERNAL_EXCEPTIONS || getProject().getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) < 0) {
				spec.setStandardOutput(System.out);
				spec.setErrorOutput(System.err);
			} else {
				spec.setStandardOutput(NullOutputStream.NULL_OUTPUT_STREAM);
				spec.setErrorOutput(NullOutputStream.NULL_OUTPUT_STREAM);
			}
		}).rethrowFailure().assertNormalExitValue();

		for (File file : toDelete) {
			file.delete();
		}

		logger.lifecycle(":access transformed minecraft in " + stopwatch.stop());
	}

	protected void remapPatchedJar(Logger logger) throws Exception {
		getProject().getLogger().lifecycle(":remapping minecraft (TinyRemapper, srg -> official)");
		Path mcInput = minecraftMergedPatchedSrgAtJar.toPath();
		Path mcOutput = minecraftMergedPatchedAtJar.toPath();
		Path forgeJar = getForgeJar().toPath();
		Path forgeUserdevJar = getForgeUserdevJar().toPath();
		Path forgeOutput = null;
		Files.deleteIfExists(mcOutput);
		boolean splitJars = forgeMergedJar != null;

		if (splitJars) {
			forgeOutput = forgeMergedJar.toPath();
			Files.deleteIfExists(forgeOutput);
		}

		TinyRemapper remapper = buildRemapper(mcInput, "srg", "official");

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(mcOutput).build(); Closeable outputConsumerForge = !splitJars ? () -> {
		} : new OutputConsumerPath.Builder(forgeOutput).build()) {
			outputConsumer.addNonClassFiles(mcInput);

			InputTag mcTag = remapper.createInputTag();
			InputTag forgeTag = remapper.createInputTag();
			List<CompletableFuture<?>> futures = new ArrayList<>();
			futures.add(remapper.readInputsAsync(mcTag, mcInput));
			futures.add(remapper.readInputsAsync(forgeTag, forgeJar, forgeUserdevJar));
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
			remapper.apply(outputConsumer, mcTag);
			remapper.apply(splitJars ? (OutputConsumerPath) outputConsumerForge : outputConsumer, forgeTag);
		} finally {
			remapper.finish();
		}

		copyNonClassFiles(forgeJar.toFile(), splitJars ? forgeMergedJar : minecraftMergedPatchedAtJar);
		copyUserdevFiles(forgeUserdevJar.toFile(), splitJars ? forgeMergedJar : minecraftMergedPatchedAtJar);
		applyLoomPatchVersion(mcOutput);
	}

	protected abstract void patchJars(File clean, File output, Path patches, String side) throws IOException;

	protected abstract void mergeJars(Logger logger) throws Exception;

	private void walkFileSystems(File source, File target, Predicate<Path> filter, Function<FileSystem, Iterable<Path>> toWalk, FsPathConsumer action)
					throws IOException {
		try (FileSystemUtil.Delegate sourceFs = FileSystemUtil.getJarFileSystem(source, false); FileSystemUtil.Delegate targetFs = FileSystemUtil.getJarFileSystem(target, false)) {
			for (Path sourceDir : toWalk.apply(sourceFs.get())) {
				Path dir = sourceDir.toAbsolutePath();
				if (!Files.exists(dir)) continue;
				Files.walk(dir).filter(Files::isRegularFile).filter(filter).forEach(it -> {
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

	private void walkFileSystems(File source, File target, Predicate<Path> filter, FsPathConsumer action) throws IOException {
		walkFileSystems(source, target, filter, FileSystem::getRootDirectories, action);
	}

	private void copyAll(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> true, this::copyReplacing);
	}

	protected void copyMissingClasses(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> it.toString().endsWith(".class"), (sourceFs, targetFs, sourcePath, targetPath) -> {
			if (Files.exists(targetPath)) return;
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	protected void copyNonClassFiles(File source, File target) throws IOException {
		Predicate<Path> filter = getExtension().isForgeAndOfficial() ? file -> {
			String s = file.toString();
			return !s.endsWith(".class");
		} : file -> {
			String s = file.toString();
			return !s.endsWith(".class") || (s.startsWith("META-INF") && !s.startsWith("META-INF/services"));
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

	protected void copyUserdevFiles(File source, File target) throws IOException {
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

	public File getMergedJar() {
		return minecraftMergedPatchedAtJar;
	}

	public File getForgeMergedJar() {
		return forgeMergedJar;
	}

	public boolean usesProjectCache() {
		return !projectAts.isEmpty();
	}

	public boolean isAtDirty() {
		return atDirty || filesDirty;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MINECRAFT;
	}
}
