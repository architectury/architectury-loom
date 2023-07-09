package net.fabricmc.loom.configuration.providers.forge.fg2;

import com.google.common.base.Stopwatch;
import dev.architectury.loom.util.TempFiles;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.forge.ForgeProvider;
import net.fabricmc.loom.configuration.providers.forge.MinecraftPatchedProvider;
import net.fabricmc.loom.configuration.providers.forge.PatchProvider;
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpExecutor;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.utils.AccessTransformSetMapper;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarMerger;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;

import net.fabricmc.loom.util.FileSystemUtil;

import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.TinyRemapperHelper;

import net.fabricmc.loom.util.ZipUtils;

import net.fabricmc.loom.util.legacyforge.CoreModManagerTransformer;
import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import org.cadixdev.at.AccessTransformSet;
import org.cadixdev.at.io.AccessTransformFormats;
import org.cadixdev.lorenz.MappingSet;
import org.gradle.api.Project;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class LegacyPatchedProvider extends MinecraftPatchedProvider {
	private MappingConfiguration mappingConfiguration;
	// Step 1: Merge Minecraft jars, will be important for generating intermediary.
	private Path minecraftMergedJar;
	// Step 2: Binary Patch
	private Path minecraftClientPatchedJar;
	private Path minecraftServerPatchedJar;
	// Step 3: Merged patched jars
	public Path minecraftMergedPatchedJar;
	// Step 4: Access Transform
	public Path minecraftMergedPatchedAtJar;
	public LegacyPatchedProvider(Project project, MinecraftProvider minecraftProvider, Type type) {
		super(project, minecraftProvider, type);
	}

	public void setMappingConfiguration(MappingConfiguration configuration) {
		mappingConfiguration = configuration;
	}
	protected void initPatchedFiles() {
		String forgeVersion = getExtension().getForgeProvider().getVersion().getCombined();
		Path forgeWorkingDir = ForgeProvider.getForgeCache(project);
		String patchId = "forge-" + forgeVersion + "-";

		minecraftProvider.setJarPrefix(patchId);

		minecraftMergedJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-merged.jar");
		minecraftMergedPatchedJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-merged-patched.jar");
		minecraftMergedPatchedAtJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-merged-at-patched.jar");
		minecraftClientPatchedJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-client-patched.jar");
		minecraftServerPatchedJar = forgeWorkingDir.resolve("minecraft-" + type.id + "-server-patched.jar");
		minecraftClientExtra = forgeWorkingDir.resolve("forge-client-extra.jar");
	}

	/**
	 * so, at this point, minecraft is using official (obfuscated) mappings, but the access transformers are in SRG, we need to fix that.
	 * @param forgeAt the bytes of the unmapped AT
	 */
	public static byte[] remapAt(LoomGradleExtension extension, byte[] forgeAt) throws IOException {
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
		return remappedOut.toByteArray();
	}

	@Override
	protected Path[] getGlobalCaches() {
		Path[] files = {
				minecraftMergedJar,
				minecraftMergedPatchedJar,
				minecraftMergedPatchedAtJar,
				minecraftClientPatchedJar,
				minecraftServerPatchedJar,
				minecraftClientExtra,
		};

		return files;
	}

	protected void mergeJars() throws IOException {
		project.getLogger().info(":merging jars");

		File jarToMerge = minecraftProvider.getMinecraftServerJar();

		if (minecraftProvider.getServerBundleMetadata() != null) {
			minecraftProvider.extractBundledServerJar();
			jarToMerge = minecraftProvider.getMinecraftExtractedServerJar();
		}

		Objects.requireNonNull(jarToMerge, "Cannot merge null input jar?");

		try (var jarMerger = new MinecraftJarMerger(minecraftProvider.getMinecraftClientJar(), jarToMerge, minecraftMergedJar.toFile())) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}
	}

	@Override
	public void provide() throws Exception {
		initPatchedFiles();
		checkCache();

		this.dirty = false;

		if (Files.notExists(minecraftMergedJar)) {
			this.dirty = true;

			try (var tempFiles = new TempFiles()) {
				mergeJars();
			}
		}

		if (dirty || Files.notExists(minecraftMergedJar)) {
			this.dirty = true;
			patchJars();
		}

		mappingConfiguration.setupPost(project);

		if (dirty || Files.notExists(minecraftMergedPatchedAtJar)) {
			this.dirty = true;
			accessTransformForge();
		}
	}

	@Override
	protected void patchJars() throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		project.getLogger().info(":patching jars");
		MinecraftProvider minecraftProvider = getExtension().getMinecraftProvider();
		PatchProvider patchProvider = getExtension().getPatchProvider();
		patchJars(minecraftProvider.getMinecraftServerJar().toPath(), minecraftServerPatchedJar, patchProvider.serverPatches);
		patchJars(minecraftProvider.getMinecraftClientJar().toPath(), minecraftClientPatchedJar, patchProvider.clientPatches);

		try (var jarMerger = new MinecraftJarMerger(minecraftClientPatchedJar.toFile(), minecraftServerPatchedJar.toFile(), minecraftMergedPatchedJar.toFile())) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}

		copyMissingClasses(minecraftMergedJar, minecraftMergedPatchedJar);
		deleteParameterNames(minecraftMergedPatchedJar);

		if (getExtension().isForgeAndNotOfficial()) {
			fixParameterAnnotation(minecraftMergedPatchedJar);
		}


		project.getLogger().info(":patched jars in " + stopwatch.stop());
	}

	public void patchForge(File input) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		project.getLogger().info(":patching forge");

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

		project.getLogger().info(":patched forge in " + stopwatch.stop());
	}

	@Override
	public Path getMinecraftSrgJar() {
		return minecraftMergedJar;
	}

	@Override
	public Path getMinecraftPatchedSrgJar() {
		return minecraftMergedPatchedJar; // legacy forge is too good for SRG
	}

	@Override
	public Path getMinecraftPatchedJar() {
		return minecraftMergedPatchedJar;
	}

	protected void accessTransformForge() throws IOException {
		Path input = minecraftMergedPatchedJar;
		Path target = minecraftMergedPatchedAtJar;
		accessTransform(project, input, target);
	}
}
