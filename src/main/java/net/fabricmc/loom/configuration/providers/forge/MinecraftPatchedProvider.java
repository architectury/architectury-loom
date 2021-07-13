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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.gson.JsonParser;
import de.oceanlabs.mcp.mcinjector.adaptors.ParameterAnnotationFixer;
import dev.architectury.tinyremapper.InputTag;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;
import net.minecraftforge.binarypatcher.ConsoleTool;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMappedProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyDownloader;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.loom.util.function.FsPathConsumer;
import net.fabricmc.loom.util.srg.InnerClassRemapper;
import net.fabricmc.loom.util.srg.SpecialSourceExecutor;
import net.fabricmc.mapping.tree.TinyTree;

public class MinecraftPatchedProvider extends DependencyProvider {
	private static final String LOOM_PATCH_VERSION_KEY = "Loom-Patch-Version";
	private static final String CURRENT_LOOM_PATCH_VERSION = "4";
	private static final String NAME_MAPPING_SERVICE_PATH = "/inject/META-INF/services/cpw.mods.modlauncher.api.INameMappingService";

	// Step 1: Remap Minecraft to SRG (global)
	private File minecraftClientSrgJar;
	private File minecraftServerSrgJar;
	// Step 2: Binary Patch (global)
	private File minecraftClientPatchedSrgJar;
	private File minecraftServerPatchedSrgJar;
	// Step 3: Merge (global)
	private File minecraftMergedPatchedSrgJar;
	// Step 4: Access Transform (global or project)
	private File minecraftMergedPatchedSrgAtJar;
	// Step 5: Remap Patched AT & Forge to Official (global or project)
	private File minecraftMergedPatchedJar;
	private File forgeMergedJar;

	private File projectAtHash;
	private Set<File> projectAts = new HashSet<>();
	private boolean atDirty = false;
	private boolean filesDirty = false;

	public MinecraftPatchedProvider(Project project) {
		super(project);
	}

	public void initFiles() throws IOException {
		filesDirty = false;
		projectAtHash = new File(getExtension().getProjectPersistentCache(), "at.sha256");
		projectAts = getExtension().accessTransformers;

		if (projectAts.isEmpty()) {
			SourceSet main = getProject().getConvention().findPlugin(JavaPluginConvention.class).getSourceSets().getByName("main");

			for (File srcDir : main.getResources().getSrcDirs()) {
				File projectAt = new File(srcDir, "META-INF/accesstransformer.cfg");

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
		String patchId = "forge-" + patchProvider.forgeVersion;

		if (getExtension().useFabricMixin) {
			patchId += "-fabric-mixin";
		}

		minecraftProvider.setJarSuffix(patchId);

		File globalCache = getExtension().getUserCache();
		File cache = usesProjectCache() ? getExtension().getProjectPersistentCache() : globalCache;
		File globalDir = new File(globalCache, patchId);
		File projectDir = new File(cache, patchId);
		globalDir.mkdirs();
		projectDir.mkdirs();

		minecraftClientSrgJar = new File(globalCache, "minecraft-" + minecraftVersion + "-client-srg.jar");
		minecraftServerSrgJar = new File(globalCache, "minecraft-" + minecraftVersion + "-server-srg.jar");
		minecraftClientPatchedSrgJar = new File(globalDir, "client-srg-patched.jar");
		minecraftServerPatchedSrgJar = new File(globalDir, "server-srg-patched.jar");
		minecraftMergedPatchedSrgJar = new File(globalDir, "merged-srg-patched.jar");
		forgeMergedJar = new File(globalDir, "forge-official.jar");
		minecraftMergedPatchedSrgAtJar = new File(projectDir, "merged-srg-at-patched.jar");
		minecraftMergedPatchedJar = new File(projectDir, "merged-patched.jar");

		if (isRefreshDeps() || Stream.of(getGlobalCaches()).anyMatch(Predicates.not(File::exists))
						|| !isPatchedJarUpToDate(minecraftMergedPatchedJar)) {
			cleanAllCache();
		} else if (atDirty || Stream.of(getProjectCache()).anyMatch(Predicates.not(File::exists))) {
			cleanProjectCache();
		}
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

	private File[] getGlobalCaches() {
		return new File[] {
				minecraftClientSrgJar,
				minecraftServerSrgJar,
				minecraftClientPatchedSrgJar,
				minecraftServerPatchedSrgJar,
				minecraftMergedPatchedSrgJar,
				forgeMergedJar,
		};
	}

	public void cleanProjectCache() {
		for (File file : getProjectCache()) {
			file.delete();
		}
	}

	private File[] getProjectCache() {
		return new File[] {
				minecraftMergedPatchedSrgAtJar,
				minecraftMergedPatchedJar
		};
	}

	private boolean dirty;

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		initFiles();

		if (atDirty) {
			getProject().getLogger().lifecycle(":found dirty access transformers");
		}

		this.dirty = false;

		if (!minecraftClientSrgJar.exists() || !minecraftServerSrgJar.exists()) {
			this.dirty = true;
			// Remap official jars to MCPConfig remapped srg jars
			createSrgJars(getProject().getLogger());
		}

		if (!minecraftClientPatchedSrgJar.exists() || !minecraftServerPatchedSrgJar.exists()) {
			this.dirty = true;
			patchJars(getProject().getLogger());
		}
	}

	public void finishProvide() throws Exception {
		if (dirty || !minecraftMergedPatchedSrgJar.exists()) {
			mergeJars(getProject().getLogger());
		}

		if (atDirty || !minecraftMergedPatchedSrgAtJar.exists()) {
			this.dirty = true;
			accessTransformForge(getProject().getLogger());
		}

		if (!forgeMergedJar.exists()) {
			this.dirty = true;
		}

		Path input = minecraftMergedPatchedSrgAtJar.toPath();
		if (dirty) {
			getProject().getLogger().lifecycle(":remapping minecraft (TinyRemapper, srg -> official)");
			remapPatchedJar(input, getProject().getLogger());
		}

		this.filesDirty = dirty;
		this.dirty = false;
	}

	private TinyRemapper buildRemapper(Path input) throws IOException {
		Path[] libraries = MinecraftMappedProvider.getRemapClasspath(getProject());
		TinyTree mappingsWithSrg = getExtension().getMappingsProvider().getMappingsWithSrg();
		TinyRemapper remapper = TinyRemapper.newRemapper()
				.logger(getProject().getLogger()::lifecycle)
				.logUnknownInvokeDynamic(false)
				.withMappings(TinyRemapperMappingsHelper.create(mappingsWithSrg, "srg", "official", true))
				.withMappings(InnerClassRemapper.of(input, mappingsWithSrg, "srg", "official"))
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true)
				.fixPackageAccess(true)
				.build();

		remapper.readClassPath(libraries);
		remapper.prepareClasses();
		return remapper;
	}

	private void writeAtHash() throws IOException {
		try (FileOutputStream out = new FileOutputStream(projectAtHash)) {
			out.write(getProjectAtsHash());
		}
	}

	private void createSrgJars(Logger logger) throws Exception {
		McpConfigProvider mcpProvider = getExtension().getMcpConfigProvider();

		MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();

		String[] mappingsPath = {null};

		if (!ZipUtil.handle(mcpProvider.getMcp(), "config.json", (in, zipEntry) -> {
			mappingsPath[0] = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().get("data").getAsJsonObject().get("mappings").getAsString();
		})) {
			throw new IllegalStateException("Failed to find 'config.json' in " + mcpProvider.getMcp().getAbsolutePath() + "!");
		}

		Path[] tmpSrg = {null};

		if (!ZipUtil.handle(mcpProvider.getMcp(), mappingsPath[0], (in, zipEntry) -> {
			tmpSrg[0] = Files.createTempFile(null, null);

			try (BufferedWriter writer = Files.newBufferedWriter(tmpSrg[0])) {
				IOUtils.copy(in, writer, StandardCharsets.UTF_8);
			}
		})) {
			throw new IllegalStateException("Failed to find mappings '" + mappingsPath[0] + "' in " + mcpProvider.getMcp().getAbsolutePath() + "!");
		}

		String atDependency = Constants.Dependencies.SPECIAL_SOURCE + Constants.Dependencies.Versions.SPECIAL_SOURCE + ":shaded";
		// Do getFiles() to resolve it before multithreading it
		FileCollection classpath = getProject().files(DependencyDownloader.download(getProject(), atDependency).getFiles());

		ThreadingUtils.run(() -> {
			Files.copy(SpecialSourceExecutor.produceSrgJar(getProject(), "client", classpath, minecraftProvider.minecraftClientJar.toPath(), tmpSrg[0]), minecraftClientSrgJar.toPath());
		}, () -> {
				Files.copy(SpecialSourceExecutor.produceSrgJar(getProject(), "server", classpath, minecraftProvider.minecraftServerJar.toPath(), tmpSrg[0]), minecraftServerSrgJar.toPath());
			});
	}

	private void fixParameterAnnotation(File jarFile) throws Exception {
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

	private File getForgeJar() {
		return getExtension().getForgeUniversalProvider().getForge();
	}

	private File getForgeUserdevJar() {
		return getExtension().getForgeUserdevProvider().getUserdevJar();
	}

	private boolean isPatchedJarUpToDate(File jar) {
		if (!jar.exists()) return false;

		boolean[] upToDate = {false};

		if (!ZipUtil.handle(jar, "META-INF/MANIFEST.MF", (in, zipEntry) -> {
			Manifest manifest = new Manifest(in);
			Attributes attributes = manifest.getMainAttributes();
			String value = attributes.getValue(LOOM_PATCH_VERSION_KEY);

			if (Objects.equals(value, CURRENT_LOOM_PATCH_VERSION)) {
				upToDate[0] = true;
			} else {
				getProject().getLogger().lifecycle(":forge patched jars not up to date. current version: " + value);
			}
		})) {
			return false;
		}

		return upToDate[0];
	}

	private void accessTransformForge(Logger logger) throws Exception {
		List<File> toDelete = new ArrayList<>();
		var atDependency = Constants.Dependencies.ACCESS_TRANSFORMERS + Constants.Dependencies.Versions.ACCESS_TRANSFORMERS;
		var classpath = DependencyDownloader.download(getProject(), atDependency);

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
			try (FileSystemUtil.FileSystemDelegate fs = FileSystemUtil.getJarFileSystem(jar, false)) {
				Path atPath = fs.get().getPath("META-INF/accesstransformer.cfg");

				if (Files.exists(atPath)) {
					File tmpFile = File.createTempFile("at-conf", ".cfg");
					tmpFile.delete();
					toDelete.add(tmpFile);
					Files.copy(atPath, tmpFile.toPath());
					args.add("--atFile");
					args.add(tmpFile.getAbsolutePath());
				}
			}
		}

		if (usesProjectCache()) {
			for (File projectAt : projectAts) {
				args.add("--atFile");
				args.add(projectAt.getAbsolutePath());
			}
		}

		getProject().javaexec(spec -> {
			spec.setMain("net.minecraftforge.accesstransformer.TransformerProcessor");
			spec.setArgs(args);
			spec.setClasspath(classpath);

			// if running with INFO or DEBUG logging
			if (getProject().getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) < 0) {
				spec.setStandardOutput(System.out);
			} else {
				spec.setStandardOutput(NullOutputStream.NULL_OUTPUT_STREAM);
				spec.setErrorOutput(NullOutputStream.NULL_OUTPUT_STREAM);
			}
		}).rethrowFailure().assertNormalExitValue();

		for (File file : toDelete) {
			file.delete();
		}
	}

	public enum Environment {
		CLIENT(provider -> provider.minecraftClientSrgJar,
				provider -> provider.minecraftClientPatchedSrgJar
		),
		SERVER(provider -> provider.minecraftServerSrgJar,
				provider -> provider.minecraftServerPatchedSrgJar
		);

		final Function<MinecraftPatchedProvider, File> srgJar;
		final Function<MinecraftPatchedProvider, File> patchedSrgJar;

		Environment(Function<MinecraftPatchedProvider, File> srgJar,
				Function<MinecraftPatchedProvider, File> patchedSrgJar) {
			this.srgJar = srgJar;
			this.patchedSrgJar = patchedSrgJar;
		}

		public String side() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private void remapPatchedJar(Path input, Logger logger) throws Exception {
		Path mcOutput = minecraftMergedPatchedJar.toPath();
		Path forgeOutput = forgeMergedJar.toPath();
		Path forgeJar = getForgeJar().toPath();
		Path forgeUserdevJar = getForgeUserdevJar().toPath();
		Files.deleteIfExists(mcOutput);
		Files.deleteIfExists(forgeOutput);
		TinyRemapper remapper = buildRemapper(input);

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(mcOutput).build();
					OutputConsumerPath outputConsumerForge = new OutputConsumerPath.Builder(forgeOutput).build()) {
			outputConsumer.addNonClassFiles(input);

			InputTag mcTag = remapper.createInputTag();
			InputTag forgeTag = remapper.createInputTag();
			remapper.readInputs(mcTag, input);
			remapper.readInputs(forgeTag, forgeJar, forgeUserdevJar);
			remapper.apply(outputConsumer, mcTag);
			remapper.apply(outputConsumerForge, forgeTag);
		} finally {
			remapper.finish();
		}

		applyLoomPatchVersion(mcOutput);
		copyNonClassFiles(forgeJar.toFile(), forgeMergedJar);
		copyUserdevFiles(forgeUserdevJar.toFile(), forgeMergedJar);
	}

	private void patchJars(Logger logger) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");

		PatchProvider patchProvider = getExtension().getPatchProvider();
		patchJars(minecraftClientSrgJar, minecraftClientPatchedSrgJar, patchProvider.clientPatches);
		patchJars(minecraftServerSrgJar, minecraftServerPatchedSrgJar, patchProvider.serverPatches);

		ThreadingUtils.run(Environment.values(), environment -> {
			copyMissingClasses(environment.srgJar.apply(this), environment.patchedSrgJar.apply(this));
			fixParameterAnnotation(environment.patchedSrgJar.apply(this));
		});

		logger.lifecycle(":patched jars in " + stopwatch.stop());
	}

	private void patchJars(File clean, File output, Path patches) throws IOException {
		PrintStream previous = System.out;

		try {
			System.setOut(new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM));
		} catch (SecurityException ignored) {
			// Failed to replace logger filter, just ignore
		}

		ConsoleTool.main(new String[] {
				"--clean", clean.getAbsolutePath(),
				"--output", output.getAbsolutePath(),
				"--apply", patches.toAbsolutePath().toString()
		});

		try {
			System.setOut(previous);
		} catch (SecurityException ignored) {
			// Failed to replace logger filter, just ignore
		}
	}

	private void mergeJars(Logger logger) throws IOException {
		// FIXME: Hack here: There are no server-only classes so we can just copy the client JAR.
		//   This will change if upstream Loom adds the possibility for separate projects/source sets per environment.
		Files.copy(minecraftClientPatchedSrgJar.toPath(), minecraftMergedPatchedSrgJar.toPath());

		logger.lifecycle(":copying resources");

		// Copy resources
		MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
		copyNonClassFiles(minecraftProvider.minecraftClientJar, minecraftMergedPatchedSrgJar);
		copyNonClassFiles(minecraftProvider.minecraftServerJar, minecraftMergedPatchedSrgJar);
	}

	private void walkFileSystems(File source, File target, Predicate<Path> filter, Function<FileSystem, Iterable<Path>> toWalk, FsPathConsumer action)
			throws IOException {
		try (FileSystemUtil.FileSystemDelegate sourceFs = FileSystemUtil.getJarFileSystem(source, false);
					FileSystemUtil.FileSystemDelegate targetFs = FileSystemUtil.getJarFileSystem(target, false)) {
			for (Path sourceDir : toWalk.apply(sourceFs.get())) {
				Path dir = sourceDir.toAbsolutePath();
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

	private void walkFileSystems(File source, File target, Predicate<Path> filter, FsPathConsumer action) throws IOException {
		walkFileSystems(source, target, filter, FileSystem::getRootDirectories, action);
	}

	private void copyAll(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> true, this::copyReplacing);
	}

	private void copyMissingClasses(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> it.toString().endsWith(".class"), (sourceFs, targetFs, sourcePath, targetPath) -> {
			if (Files.exists(targetPath)) return;
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	private void copyNonClassFiles(File source, File target) throws IOException {
		Predicate<Path> filter = file -> {
			String s = file.toString();
			return !s.endsWith(".class");
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

	private void copyUserdevFiles(File source, File target) throws IOException {
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
		try (FileSystemUtil.FileSystemDelegate delegate = FileSystemUtil.getJarFileSystem(target, false)) {
			Path manifestPath = delegate.get().getPath("META-INF/MANIFEST.MF");

			Preconditions.checkArgument(Files.exists(manifestPath), "META-INF/MANIFEST.MF does not exist in patched srg jar!");
			Manifest manifest = new Manifest();

			if (Files.exists(manifestPath)) {
				try (InputStream stream = Files.newInputStream(manifestPath)) {
					manifest.read(stream);
					manifest.getMainAttributes().putValue(LOOM_PATCH_VERSION_KEY, CURRENT_LOOM_PATCH_VERSION);
				}
			}

			try (OutputStream stream = Files.newOutputStream(manifestPath)) {
				manifest.write(stream);
			}
		}
	}

	public File getMergedJar() {
		return minecraftMergedPatchedJar;
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
