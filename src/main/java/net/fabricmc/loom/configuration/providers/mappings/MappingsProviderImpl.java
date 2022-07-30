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

package net.fabricmc.loom.configuration.providers.mappings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Supplier;

import com.google.common.base.Stopwatch;
import com.google.common.base.Suppliers;
import com.google.gson.JsonObject;
import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.configuration.providers.forge.FieldMigratedMappingsProvider;
import net.fabricmc.loom.configuration.providers.forge.SrgProvider;
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingsSpec;
import net.fabricmc.loom.configuration.providers.mappings.tiny.MappingsMerger;
import net.fabricmc.loom.configuration.providers.mappings.tiny.TinyJarInfo;
import net.fabricmc.loom.configuration.providers.minecraft.MergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.service.SharedService;
import net.fabricmc.loom.util.service.SharedServiceManager;
import net.fabricmc.loom.util.srg.MCPReader;
import net.fabricmc.loom.util.srg.SrgMerger;
import net.fabricmc.loom.util.srg.SrgNamedWriter;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.stitch.commands.tinyv2.TinyFile;
import net.fabricmc.stitch.commands.tinyv2.TinyV2Writer;

public class MappingsProviderImpl implements MappingsProvider, SharedService {
	private static final Logger LOGGER = LoggerFactory.getLogger(MappingsProviderImpl.class);

	private Supplier<MemoryMappingTree> mappingTree;
	private Supplier<MemoryMappingTree> mappingTreeWithSrg;
	public final String mappingsIdentifier;

	private final Path mappingsWorkingDir;
	// The mappings that gradle gives us
	private final Path baseTinyMappings;
	// The mappings we use in practice
	public Path tinyMappings;
	public final Path tinyMappingsJar;
	public Path tinyMappingsWithSrg;
	public final Map<String, Path> mixinTinyMappings; // The mixin mappings have other names in intermediary.
	public final Path srgToNamedSrg; // FORGE: srg to named in srg file format
	private final Path mcpToSrg;
	private final Path unpickDefinitions;

	private boolean hasUnpickDefinitions;
	private UnpickMetadata unpickMetadata;
	private Map<String, String> signatureFixes;

	private final Supplier<IntermediateMappingsService> intermediaryService;

	protected MappingsProviderImpl(String mappingsIdentifier, Path mappingsWorkingDir, Supplier<IntermediateMappingsService> intermediaryService) {
		this.mappingsIdentifier = mappingsIdentifier;

		this.mappingsWorkingDir = mappingsWorkingDir;
		this.baseTinyMappings = mappingsWorkingDir.resolve("mappings-base.tiny");
		this.tinyMappings = mappingsWorkingDir.resolve("mappings.tiny");
		this.tinyMappingsJar = mappingsWorkingDir.resolve("mappings.jar");
		this.unpickDefinitions = mappingsWorkingDir.resolve("mappings.unpick");
		this.tinyMappingsWithSrg = mappingsWorkingDir.resolve("mappings-srg.tiny");
		this.mixinTinyMappings = new HashMap<>();
		this.srgToNamedSrg = mappingsWorkingDir.resolve("mappings-srg-named.srg");
		this.mcpToSrg = mappingsWorkingDir.resolve("mcp_to_srg.srg");

		this.intermediaryService = intermediaryService;
	}

	public static synchronized MappingsProviderImpl getInstance(Project project, LoomGradleExtension extension, DependencyInfo dependency, MinecraftProvider minecraftProvider) {
		StringJoiner id = new StringJoiner(":").add("MappingsProvider");
		id.add(dependency.getDepString());
		id.add(minecraftProvider.minecraftVersion());

		// Arch: we add :forge since Forge support uses a different MappingsProvider.
		if (extension.isForge()) {
			id.add("forge");
		}

		return SharedServiceManager.get(project).getOrCreateService(id.toString(), () -> {
			Supplier<IntermediateMappingsService> intermediaryService = Suppliers.memoize(() -> IntermediateMappingsService.getInstance(project, minecraftProvider));
			return create(project, dependency, minecraftProvider, intermediaryService);
		});
	}

	public MemoryMappingTree getMappings() throws IOException {
		return Objects.requireNonNull(mappingTree, "Cannot get mappings before they have been read").get();
	}

	public MemoryMappingTree getMappingsWithSrg() throws IOException {
		return Objects.requireNonNull(mappingTreeWithSrg, "Cannot get mappings before they have been read").get();
	}

	public Path getMcpToSrg() {
		if (Files.notExists(mcpToSrg)) {
			// Forge outputs a srg file that includes class names, even if they match srg(which they would if using mcp mappings)
			// So we try to get as close to that by adding the class mappings even if they don't change.
			// This can't use SrgWriter either, as that doesn't add unmapped class mappings.
			try (BufferedWriter writer = Files.newBufferedWriter(mcpToSrg)) {
				MappingTreeView mappings = getMappingsWithSrg();

				int sourceNamespace = mappings.getNamespaceId(MappingsNamespace.SRG.toString());
				int targetNamespace = mappings.getNamespaceId(MappingsNamespace.NAMED.toString());

				for (MappingTreeView.ClassMappingView classMapping : mappings.getClasses()) {
					String srg = classMapping.getName(sourceNamespace);
					String named = classMapping.getName(targetNamespace);

					writer.write("CL: ");
					writer.write(srg);
					writer.write(' ');
					writer.write(named);
					writer.write('\n');

					for (MappingTreeView.FieldMappingView fieldMapping : classMapping.getFields()) {
						writer.write("FD: ");

						writer.write(srg);
						writer.write('/');
						writer.write(fieldMapping.getName(sourceNamespace));

						writer.write(' ');

						writer.write(named);
						writer.write('/');
						writer.write(fieldMapping.getName(targetNamespace));

						writer.write('\n');
					}

					for (MappingTreeView.MethodMappingView methodMapping : classMapping.getMethods()) {
						writer.write("MD: ");

						writer.write(srg);
						writer.write('/');
						writer.write(methodMapping.getName(sourceNamespace));
						writer.write(' ');
						writer.write(methodMapping.getDesc(sourceNamespace));

						writer.write(' ');

						writer.write(named);
						writer.write('/');
						writer.write(methodMapping.getName(targetNamespace));
						writer.write(' ');
						writer.write(methodMapping.getDesc(targetNamespace));

						writer.write('\n');
					}
				}
			} catch (IOException exception) {
				throw new UncheckedIOException("Failed to create SRG file for the mcp_to_srg template argument", exception);
			}
		}

		return mcpToSrg;
	}

	private static MappingsProviderImpl create(Project project, DependencyInfo dependency, MinecraftProvider minecraftProvider, Supplier<IntermediateMappingsService> intermediaryService) {
		final String version = dependency.getResolvedVersion();
		final Path inputJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve mappings: " + dependency)).toPath();
		final String mappingsName = StringUtils.removeSuffix(dependency.getDependency().getGroup() + "." + dependency.getDependency().getName(), "-unmerged");

		final TinyJarInfo jarInfo = TinyJarInfo.get(inputJar);
		jarInfo.minecraftVersionId().ifPresent(id -> {
			if (!minecraftProvider.minecraftVersion().equals(id)) {
				LOGGER.warn("The mappings (%s) were not build for minecraft version (%s) produce with caution.".formatted(dependency.getDepString(), minecraftProvider.minecraftVersion()));
			}
		});

		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		String mappingsIdentifier;

		if (extension.isForge()) {
			mappingsIdentifier = FieldMigratedMappingsProvider.createForgeMappingsIdentifier(extension, mappingsName, version, getMappingsClassifier(dependency, jarInfo.v2()), minecraftProvider.minecraftVersion());
		} else {
			mappingsIdentifier = createMappingsIdentifier(mappingsName, version, getMappingsClassifier(dependency, jarInfo.v2()), minecraftProvider.minecraftVersion());
		}

		if (extension.isQuilt()) {
			mappingsIdentifier += "-arch-quilt";
		}

		final Path workingDir = minecraftProvider.dir(mappingsIdentifier).toPath();

		MappingsProviderImpl mappingProvider;

		if (extension.isForge()) {
			mappingProvider = new FieldMigratedMappingsProvider(mappingsIdentifier, workingDir, intermediaryService);
		} else {
			mappingProvider = new MappingsProviderImpl(mappingsIdentifier, workingDir, intermediaryService);
		}

		try {
			mappingProvider.setup(project, minecraftProvider, inputJar);
		} catch (IOException e) {
			cleanWorkingDirectory(workingDir);
			throw new UncheckedIOException("Failed to setup mappings: " + dependency.getDepString(), e);
		}

		return mappingProvider;
	}

	protected void setup(Project project, MinecraftProvider minecraftProvider, Path inputJar) throws IOException {
		if (isRefreshDeps()) {
			cleanWorkingDirectory(mappingsWorkingDir);
		}

		if (Files.notExists(tinyMappings) || isRefreshDeps()) {
			storeMappings(project, minecraftProvider, inputJar);
		} else {
			try (FileSystem fileSystem = FileSystems.newFileSystem(inputJar, (ClassLoader) null)) {
				extractExtras(fileSystem);
			}
		}

		if (Files.notExists(tinyMappingsJar) || isRefreshDeps()) {
			Files.deleteIfExists(tinyMappingsJar);
			ZipUtils.add(tinyMappingsJar, "mappings/mappings.tiny", Files.readAllBytes(tinyMappings));
		}

		mappingTree = Suppliers.memoize(() -> readMappings(tinyMappings));
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		manipulateMappings(project, tinyMappingsJar);

		if (extension.shouldGenerateSrgTiny()) {
			if (Files.notExists(tinyMappingsWithSrg) || isRefreshDeps()) {
				Stopwatch stopwatch = Stopwatch.createStarted();
				mergeSrg(project, tinyMappings, tinyMappingsWithSrg);
				project.getLogger().info(":merged srg mappings in " + stopwatch.stop());
			}

			mappingTreeWithSrg = Suppliers.memoize(() -> readMappings(tinyMappingsWithSrg));
		}
	}

	protected void mergeSrg(Project project, Path tinyMappings, Path tinyMappingsWithSrg) throws IOException {
		MinecraftVersionMeta versionInfo = LoomGradleExtension.get(project).getMinecraftProvider().getVersionInfo();
		SrgMerger.ExtraMappings extraMappings;

		if (versionInfo.download(MojangMappingsSpec.MANIFEST_CLIENT_MAPPINGS) == null) {
			extraMappings = null;
		} else {
			extraMappings = SrgMerger.ExtraMappings.ofMojmapTsrg(getMojmapSrgFileIfPossible(project));
		}

		// Merge tiny mappings with srg
		SrgMerger.mergeSrg(getRawSrgFile(project), tinyMappings, tinyMappingsWithSrg, extraMappings, true);
	}

	public void applyToProject(Project project, DependencyInfo dependency) throws IOException {
		if (hasUnpickDefinitions()) {
			String notation = String.format("%s:%s:%s:constants",
					dependency.getDependency().getGroup(),
					dependency.getDependency().getName(),
					dependency.getDependency().getVersion()
			);

			project.getDependencies().add(Constants.Configurations.MAPPING_CONSTANTS, notation);
			populateUnpickClasspath(project);
		}

		LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (extension.isForge()) {
			if (!extension.shouldGenerateSrgTiny()) {
				throw new IllegalStateException("We have to generate srg tiny in a forge environment!");
			}

			if (Files.notExists(srgToNamedSrg) || isRefreshDeps()) {
				SrgNamedWriter.writeTo(project.getLogger(), srgToNamedSrg, getMappingsWithSrg(), "srg", "named");
			}
		}

		project.getDependencies().add(Constants.Configurations.MAPPINGS_FINAL, project.getDependencies().module("loom.resolved:mappings:" + extension.getMinecraftProvider().minecraftVersion() + "/" + mappingsIdentifier()));
	}

	protected Path getRawSrgFile(Project project) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		if (extension.getSrgProvider().isTsrgV2()) {
			return extension.getSrgProvider().getMergedMojangTrimmed();
		}

		return extension.getSrgProvider().getSrg();
	}

	public Path getMojmapSrgFileIfPossible(Project project) {
		try {
			LoomGradleExtension extension = LoomGradleExtension.get(project);
			return SrgProvider.getMojmapTsrg2(project, extension);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void manipulateMappings(Project project, Path mappingsJar) throws IOException {
	}

	private static String getMappingsClassifier(DependencyInfo dependency, boolean isV2) {
		String[] depStringSplit = dependency.getDepString().split(":");

		if (depStringSplit.length >= 4) {
			return "-" + depStringSplit[3] + (isV2 ? "-v2" : "");
		}

		return isV2 ? "-v2" : "";
	}

	private void storeMappings(Project project, MinecraftProvider minecraftProvider, Path inputJar) throws IOException {
		LOGGER.info(":extracting " + inputJar.getFileName());

		if (isMCP(inputJar)) {
			try {
				readAndMergeMCP(project, inputJar);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}

			return;
		}

		try (FileSystem fileSystem = FileSystems.newFileSystem(inputJar, (ClassLoader) null)) {
			extractMappings(fileSystem, baseTinyMappings);
			extractExtras(fileSystem);
		}

		if (areMappingsMergedV2(baseTinyMappings)) {
			// Architectury Loom Patch
			// If a merged tiny v2 mappings file is provided
			// Skip merging, should save a lot of time
			Files.copy(baseTinyMappings, tinyMappings, StandardCopyOption.REPLACE_EXISTING);
		} else if (areMappingsV2(baseTinyMappings)) {
			// These are unmerged v2 mappings
			MappingsMerger.mergeAndSaveMappings(baseTinyMappings, tinyMappings, intermediaryService.get());
		} else {
			if (minecraftProvider instanceof MergedMinecraftProvider mergedMinecraftProvider) {
				// These are merged v1 mappings
				Files.deleteIfExists(tinyMappings);
				LOGGER.info(":populating field names");
				suggestFieldNames(mergedMinecraftProvider, baseTinyMappings, tinyMappings);
			} else {
				throw new UnsupportedOperationException("V1 mappings only support merged minecraft");
			}
		}
	}

	private static MemoryMappingTree readMappings(Path file) {
		try {
			MemoryMappingTree mappingTree = new MemoryMappingTree();
			MappingReader.read(file, mappingTree);
			return mappingTree;
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read mappings", e);
		}
	}

	private void readAndMergeMCP(Project project, Path mcpJar) throws Exception {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		Path intermediaryTinyPath = intermediaryTinyFile().toPath();
		SrgProvider provider = extension.getSrgProvider();

		if (provider == null) {
			if (!extension.shouldGenerateSrgTiny()) {
				Configuration srg = project.getConfigurations().maybeCreate(Constants.Configurations.SRG);
				srg.setTransitive(false);
			}

			provider = new SrgProvider(project);
			project.getDependencies().add(provider.getTargetConfig(), "de.oceanlabs.mcp:mcp_config:" + extension.getMinecraftProvider().minecraftVersion());
			Configuration configuration = project.getConfigurations().getByName(provider.getTargetConfig());
			provider.provide(DependencyInfo.create(project, configuration.getDependencies().iterator().next(), configuration));
		}

		Path srgPath = getRawSrgFile(project);
		TinyFile file = new MCPReader(intermediaryTinyPath, srgPath).read(mcpJar);
		TinyV2Writer.write(file, tinyMappings);
	}

	private boolean isMCP(Path path) throws IOException {
		try (FileSystem fs = FileSystems.newFileSystem(path, (ClassLoader) null)) {
			return Files.exists(fs.getPath("fields.csv")) && Files.exists(fs.getPath("methods.csv"));
		}
	}

	private static boolean areMappingsV2(Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path)) {
			return MappingReader.detectFormat(reader) == MappingFormat.TINY_2;
		} catch (NoSuchFileException e) {
			// TODO: just check the mappings version when Parser supports V1 in readMetadata()
			return false;
		}
	}

	private static boolean areMappingsMergedV2(Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path)) {
			reader.mark(4096); // == DETECT_HEADER_LEN
			boolean isTinyV2 = MappingReader.detectFormat(reader) == MappingFormat.TINY_2;
			reader.reset();
			return isTinyV2 && MappingReader.getNamespaces(reader, MappingFormat.TINY_2).containsAll(Arrays.asList("named", "intermediary", "official"));
		} catch (NoSuchFileException e) {
			return false;
		}
	}

	public static void extractMappings(Path jar, Path extractTo) throws IOException {
		try (FileSystem unmergedIntermediaryFs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			extractMappings(unmergedIntermediaryFs, extractTo);
		}
	}

	public static void extractMappings(FileSystem jar, Path extractTo) throws IOException {
		Files.copy(jar.getPath("mappings/mappings.tiny"), extractTo, StandardCopyOption.REPLACE_EXISTING);
	}

	private void extractExtras(FileSystem jar) throws IOException {
		extractUnpickDefinitions(jar);
		extractSignatureFixes(jar);
	}

	private void extractUnpickDefinitions(FileSystem jar) throws IOException {
		Path unpickPath = jar.getPath("extras/definitions.unpick");
		Path unpickMetadataPath = jar.getPath("extras/unpick.json");

		if (!Files.exists(unpickPath) || !Files.exists(unpickMetadataPath)) {
			return;
		}

		Files.copy(unpickPath, unpickDefinitions, StandardCopyOption.REPLACE_EXISTING);

		unpickMetadata = parseUnpickMetadata(unpickMetadataPath);
		hasUnpickDefinitions = true;
	}

	private void extractSignatureFixes(FileSystem jar) throws IOException {
		Path recordSignaturesJsonPath = jar.getPath("extras/record_signatures.json");

		if (!Files.exists(recordSignaturesJsonPath)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(recordSignaturesJsonPath, StandardCharsets.UTF_8)) {
			//noinspection unchecked
			signatureFixes = LoomGradlePlugin.OBJECT_MAPPER.readValue(reader, Map.class);
		}
	}

	private UnpickMetadata parseUnpickMetadata(Path input) throws IOException {
		JsonObject jsonObject = LoomGradlePlugin.GSON.fromJson(Files.readString(input), JsonObject.class);

		if (!jsonObject.has("version") || jsonObject.get("version").getAsInt() != 1) {
			throw new UnsupportedOperationException("Unsupported unpick version");
		}

		return new UnpickMetadata(
				jsonObject.get("unpickGroup").getAsString(),
				jsonObject.get("unpickVersion").getAsString()
		);
	}

	private void populateUnpickClasspath(Project project) {
		String unpickCliName = "unpick-cli";
		project.getDependencies().add(Constants.Configurations.UNPICK_CLASSPATH,
				String.format("%s:%s:%s", unpickMetadata.unpickGroup, unpickCliName, unpickMetadata.unpickVersion)
		);

		// Unpick ships with a slightly older version of asm, ensure it runs with at least the same version as loom.
		String[] asmDeps = new String[] {
				"org.ow2.asm:asm:%s",
				"org.ow2.asm:asm-tree:%s",
				"org.ow2.asm:asm-commons:%s",
				"org.ow2.asm:asm-util:%s"
		};

		for (String asm : asmDeps) {
			project.getDependencies().add(Constants.Configurations.UNPICK_CLASSPATH,
					asm.formatted(Opcodes.class.getPackage().getImplementationVersion())
			);
		}
	}

	private void suggestFieldNames(MergedMinecraftProvider minecraftProvider, Path oldMappings, Path newMappings) {
		Command command = new CommandProposeFieldNames();
		runCommand(command, minecraftProvider.getMergedJar().toFile().getAbsolutePath(),
				oldMappings.toAbsolutePath().toString(),
				newMappings.toAbsolutePath().toString());
	}

	private void runCommand(Command command, String... args) {
		try {
			command.run(args);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void cleanWorkingDirectory(Path mappingsWorkingDir) {
		try {
			if (Files.exists(mappingsWorkingDir)) {
				Files.walkFileTree(mappingsWorkingDir, new DeletingFileVisitor());
			}

			Files.createDirectories(mappingsWorkingDir);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public Path mappingsWorkingDir() {
		return mappingsWorkingDir;
	}

	@Override
	public File intermediaryTinyFile() {
		return intermediaryService.get().getIntermediaryTiny().toFile();
	}

	protected static String createMappingsIdentifier(String mappingsName, String version, String classifier, String minecraftVersion) {
		//          mappingsName      . mcVersion . version        classifier
		// Example: net.fabricmc.yarn . 1_16_5    . 1.16.5+build.5 -v2
		return mappingsName + "." + minecraftVersion.replace(' ', '_').replace('.', '_').replace('-', '_') + "." + version + classifier;
	}

	public String mappingsIdentifier() {
		return mappingsIdentifier;
	}

	public File getUnpickDefinitionsFile() {
		return unpickDefinitions.toFile();
	}

	public boolean hasUnpickDefinitions() {
		return hasUnpickDefinitions;
	}

	@Nullable
	public Map<String, String> getSignatureFixes() {
		return signatureFixes;
	}

	public String getBuildServiceName(String name, String from, String to) {
		return "%s:%s:%s>%S".formatted(name, mappingsIdentifier(), from, to);
	}

	public Path getReplacedTarget(LoomGradleExtension loom, String namespace) {
		if (namespace.equals("intermediary")) return loom.shouldGenerateSrgTiny() ? tinyMappingsWithSrg : tinyMappings;

		return mixinTinyMappings.computeIfAbsent(namespace, k -> {
			Path path = mappingsWorkingDir.resolve("mappings-mixin-" + namespace + ".tiny");

			try {
				if (Files.notExists(path) || isRefreshDeps()) {
					List<String> lines = new ArrayList<>(Files.readAllLines(loom.shouldGenerateSrgTiny() ? tinyMappingsWithSrg : tinyMappings));
					lines.set(0, lines.get(0).replace("intermediary", "yraidemretni").replace(namespace, "intermediary"));
					Files.deleteIfExists(path);
					Files.write(path, lines);
				}

				return path;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	public record UnpickMetadata(String unpickGroup, String unpickVersion) {
	}

	protected static boolean isRefreshDeps() {
		return LoomGradlePlugin.refreshDeps;
	}

	@Override
	public void close() throws IOException {
		mappingTree = null;
		mappingTreeWithSrg = null;
	}
}
