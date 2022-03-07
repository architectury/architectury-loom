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
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.google.common.base.Stopwatch;
import com.google.common.net.UrlEscapers;
import com.google.gson.JsonObject;
import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.accesswidener.TransitiveAccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.processors.JarProcessorManager;
import net.fabricmc.loom.configuration.processors.MinecraftProcessedProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.forge.MinecraftPatchedProvider;
import net.fabricmc.loom.configuration.providers.forge.fg2.MinecraftPatchedProviderFG2;
import net.fabricmc.loom.configuration.providers.forge.fg3.MinecraftPatchedProviderFG3;
import net.fabricmc.loom.configuration.providers.forge.SrgProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMappedProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DeletingFileVisitor;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.srg.MCPReader;
import net.fabricmc.loom.util.srg.SrgMerger;
import net.fabricmc.loom.util.srg.SrgNamedWriter;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingNsCompleter;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.Tiny2Reader;
import net.fabricmc.mappingio.format.Tiny2Writer;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.stitch.commands.tinyv2.TinyFile;
import net.fabricmc.stitch.commands.tinyv2.TinyV2Writer;
import net.fabricmc.stitch.representation.JarClassEntry;
import net.fabricmc.stitch.representation.JarFieldEntry;
import net.fabricmc.stitch.representation.JarMethodEntry;
import net.fabricmc.stitch.representation.JarReader;
import net.fabricmc.stitch.representation.JarRootEntry;

public class MappingsProviderImpl extends DependencyProvider implements MappingsProvider {
	public MinecraftMappedProvider mappedProvider;
	public MinecraftPatchedProvider patchedProvider;

	public String mappingsIdentifier;

	private Path mappingsWorkingDir;
	private Path intermediaryTiny;
	private boolean hasRefreshed = false;
	// The mappings that gradle gives us
	private Path baseTinyMappings;
	// The mappings we use in practice
	public Path tinyMappings;
	public Path tinyMappingsJar;
	public Path tinyMappingsWithSrg;
	public Path mixinTinyMappingsWithSrg; // FORGE: The mixin mappings have srg names in intermediary.
	public Path srgToNamedSrg; // FORGE: srg to named in srg file format
	private Path unpickDefinitions;
	private boolean hasUnpickDefinitions;
	private UnpickMetadata unpickMetadata;
	private MemoryMappingTree mappingTree;
	private MemoryMappingTree mappingTreeWithSrg;
	private Map<String, String> signatureFixes;

	public MappingsProviderImpl(Project project) {
		super(project);
	}

	public MemoryMappingTree getMappings() throws IOException {
		return Objects.requireNonNull(mappingTree, "Cannot get mappings before they have been read");
	}

	public MemoryMappingTree getMappingsWithSrg() throws IOException {
		return Objects.requireNonNull(mappingTreeWithSrg, "Cannot get mappings before they have been read");
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProviderImpl minecraftProvider = getDependencyManager().getProvider(MinecraftProviderImpl.class);

		getProject().getLogger().info(":setting up mappings (" + dependency.getDependency().getName() + " " + dependency.getResolvedVersion() + ")");

		String version = dependency.getResolvedVersion();
		File mappingsJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find yarn mappings: " + dependency));

		String mappingsName = StringUtils.removeSuffix(dependency.getDependency().getGroup() + "." + dependency.getDependency().getName(), "-unmerged");
		boolean isV2 = isV2(dependency, mappingsJar);
		this.mappingsIdentifier = createMappingsIdentifier(mappingsName, version, getMappingsClassifier(dependency, isV2));

		initFiles();

		if (Files.notExists(tinyMappings) || isRefreshDeps()) {
			storeMappings(getProject(), minecraftProvider, mappingsJar.toPath(), postPopulationScheduler);
		} else {
			try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar.toPath(), (ClassLoader) null)) {
				extractExtras(fileSystem);
			}
		}

		if (getExtension().isForge()) {
			if (getExtension().getForgeProvider().isFG2()) {
				patchedProvider = new MinecraftPatchedProviderFG2(getProject());
				patchedProvider.provide(dependency, postPopulationScheduler);
			} else {
				patchedProvider = new MinecraftPatchedProviderFG3(getProject());
				patchedProvider.provide(dependency, postPopulationScheduler);
			}
		}

		mappingTree = readMappings(tinyMappings);
		manipulateMappings(mappingsJar.toPath());

		if (getExtension().shouldGenerateSrgTiny()) {
			if (Files.notExists(tinyMappingsWithSrg) || isRefreshDeps()) {
				// Merge tiny mappings with srg
				SrgMerger.mergeSrg(getProject().getLogger(), getExtension().getMappingsProvider()::getMojmapSrgFileIfPossible, getRawSrgFile(), tinyMappings, tinyMappingsWithSrg, true, getExtension().getSrgProvider().isLegacy());
			}

			mappingTreeWithSrg = readMappings(tinyMappingsWithSrg);
		}

		if (Files.notExists(tinyMappingsJar) || isRefreshDeps()) {
			Files.deleteIfExists(tinyMappingsJar);
			ZipUtils.add(tinyMappingsJar, "mappings/mappings.tiny", Files.readAllBytes(tinyMappings));
		}

		if (hasUnpickDefinitions()) {
			String notation = String.format("%s:%s:%s:constants",
					dependency.getDependency().getGroup(),
					dependency.getDependency().getName(),
					dependency.getDependency().getVersion()
			);

			getProject().getDependencies().add(Constants.Configurations.MAPPING_CONSTANTS, notation);
			populateUnpickClasspath();
		}

		if (getExtension().isForge()) {
			if (!getExtension().shouldGenerateSrgTiny()) {
				throw new IllegalStateException("We have to generate srg tiny in a forge environment!");
			}

			if (Files.notExists(mixinTinyMappingsWithSrg) || isRefreshDeps()) {
				List<String> lines = new ArrayList<>(Files.readAllLines(tinyMappingsWithSrg));
				lines.set(0, lines.get(0).replace("intermediary", "yraidemretni").replace("srg", "intermediary"));
				Files.deleteIfExists(mixinTinyMappingsWithSrg);
				Files.write(mixinTinyMappingsWithSrg, lines);
			}

			if (Files.notExists(srgToNamedSrg) || isRefreshDeps()) {
				SrgNamedWriter.writeTo(getProject().getLogger(), srgToNamedSrg, getMappingsWithSrg(), "srg", "named");
			}
		}

		addDependency(getProject().getDependencies().module("loom.resolved:mappings:" + getMinecraftProvider().minecraftVersion() + "/" + getExtension().getMappingsProvider().mappingsIdentifier()), Constants.Configurations.MAPPINGS_FINAL);

		LoomGradleExtension extension = getExtension();

		if (extension.getAccessWidenerPath().isPresent()) {
			extension.getGameJarProcessors().add(new AccessWidenerJarProcessor(getProject()));
		}

		if (extension.getEnableTransitiveAccessWideners().get()) {
			TransitiveAccessWidenerJarProcessor transitiveAccessWidenerJarProcessor = new TransitiveAccessWidenerJarProcessor(getProject());

			if (!transitiveAccessWidenerJarProcessor.isEmpty()) {
				extension.getGameJarProcessors().add(transitiveAccessWidenerJarProcessor);
			}
		}

		extension.getAccessWidenerPath().finalizeValue();
		extension.getGameJarProcessors().finalizeValue();
		JarProcessorManager processorManager = new JarProcessorManager(extension.getGameJarProcessors().get());
		extension.setJarProcessorManager(processorManager);
		processorManager.setupProcessors();

		if (extension.isForge()) {
			patchedProvider.endTransform();
		}

		if (processorManager.active() || (extension.isForge() && patchedProvider.usesProjectCache())) {
			mappedProvider = new MinecraftProcessedProvider(getProject(), processorManager);
			getProject().getLogger().info("Using project based jar storage");
		} else {
			mappedProvider = new MinecraftMappedProvider(getProject());
		}

		mappedProvider.initFiles(minecraftProvider, this);
		mappedProvider.provide(dependency, postPopulationScheduler);
	}

	protected Path getRawSrgFile() throws IOException {
		LoomGradleExtension extension = getExtension();

		if (extension.getSrgProvider().isTsrgV2()) {
			return extension.getSrgProvider().getMergedMojangTrimmed();
		}

		return extension.getSrgProvider().getSrg();
	}

	public Path getMojmapSrgFileIfPossible() {
		try {
			LoomGradleExtension extension = getExtension();
			return SrgProvider.getMojmapTsrg2(getProject(), extension);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void manipulateMappings(Path mappingsJar) throws IOException {
	}

	private String getMappingsClassifier(DependencyInfo dependency, boolean isV2) {
		String[] depStringSplit = dependency.getDepString().split(":");

		if (depStringSplit.length >= 4) {
			return "-" + depStringSplit[3] + (isV2 ? "-v2" : "");
		}

		return isV2 ? "-v2" : "";
	}

	private boolean isV2(DependencyInfo dependency, File mappingsJar) throws IOException {
		String minecraftVersion = getMinecraftProvider().minecraftVersion();

		// Only do this for official yarn, there isn't really a way we can get the mc version for all mappings
		if (dependency.getDependency().getGroup() != null && dependency.getDependency().getGroup().equals("net.fabricmc") && dependency.getDependency().getName().equals("yarn") && dependency.getDependency().getVersion() != null) {
			String yarnVersion = dependency.getDependency().getVersion();
			char separator = yarnVersion.contains("+build.") ? '+' : yarnVersion.contains("-") ? '-' : '.';
			String yarnMinecraftVersion = yarnVersion.substring(0, yarnVersion.lastIndexOf(separator));

			if (!yarnMinecraftVersion.equalsIgnoreCase(minecraftVersion)) {
				getProject().getLogger().warn("Minecraft Version ({}) does not match yarn's minecraft version ({})", minecraftVersion, yarnMinecraftVersion);
			}

			// We can save reading the zip file + header by checking the file name
			return mappingsJar.getName().endsWith("-v2.jar") || mappingsJar.getName().endsWith("-mergedv2.jar");
		} else {
			return doesJarContainV2Mappings(mappingsJar.toPath());
		}
	}

	private void storeMappings(Project project, MinecraftProviderImpl minecraftProvider, Path yarnJar, Consumer<Runnable> postPopulationScheduler)
			throws IOException {
		project.getLogger().info(":extracting " + yarnJar.getFileName());

		if (isMCP(yarnJar)) {
			try {
				readAndMergeMCP(yarnJar, postPopulationScheduler);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}

			return;
		}

		try (FileSystem fileSystem = FileSystems.newFileSystem(yarnJar, (ClassLoader) null)) {
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
			mergeAndSaveMappings(project, baseTinyMappings, tinyMappings);
		} else {
			// These are merged v1 mappings
			Files.deleteIfExists(tinyMappings);
			project.getLogger().lifecycle(":populating field names");
			suggestFieldNames(minecraftProvider, baseTinyMappings, tinyMappings);
		}
	}

	private static MemoryMappingTree readMappings(Path file) throws IOException {
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		MappingReader.read(file, mappingTree);
		return mappingTree;
	}

	private void readAndMergeMCP(Path mcpJar, Consumer<Runnable> postPopulationScheduler) throws Exception {
		Path intermediaryTinyPath = getIntermediaryTiny();
		SrgProvider provider = getExtension().getSrgProvider();

		if (provider == null) {
			if (!getExtension().shouldGenerateSrgTiny()) {
				Configuration srg = getProject().getConfigurations().maybeCreate(Constants.Configurations.SRG);
				srg.setTransitive(false);
			}

			provider = new SrgProvider(getProject());
			getProject().getDependencies().add(provider.getTargetConfig(), "de.oceanlabs.mcp:mcp_config:" + getMinecraftProvider().minecraftVersion());
			Configuration configuration = getProject().getConfigurations().getByName(provider.getTargetConfig());
			provider.provide(DependencyInfo.create(getProject(), configuration.getDependencies().iterator().next(), configuration), postPopulationScheduler);
		}

		Path srgPath = getRawSrgFile();
		TinyFile file = new MCPReader(intermediaryTinyPath, srgPath, getExtension().getSrgProvider().isLegacy()).read(mcpJar);
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
			return MappingReader.detectFormat(reader) == MappingFormat.TINY_2 && MappingReader.getNamespaces(reader).containsAll(Arrays.asList("named", "intermediary", "official"));
		} catch (NoSuchFileException e) {
			return false;
		}
	}

	private static boolean doesJarContainV2Mappings(Path path) throws IOException {
		try (FileSystem fs = FileSystems.newFileSystem(path, (ClassLoader) null)) {
			try (BufferedReader reader = Files.newBufferedReader(fs.getPath("mappings", "mappings.tiny"))) {
				return MappingReader.detectFormat(reader) == MappingFormat.TINY_2;
			}
		} catch (NoSuchFileException e) {
			return false;
		}
	}

	private static void extractMappings(Path jar, Path extractTo) throws IOException {
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

	private void populateUnpickClasspath() {
		String unpickCliName = "unpick-cli";
		getProject().getDependencies().add(Constants.Configurations.UNPICK_CLASSPATH,
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
			getProject().getDependencies().add(Constants.Configurations.UNPICK_CLASSPATH,
					asm.formatted(Opcodes.class.getPackage().getImplementationVersion())
			);
		}
	}

	private void mergeAndSaveMappings(Project project, Path from, Path out) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		project.getLogger().info(":merging mappings");

		MemoryMappingTree tree = new MemoryMappingTree();
		MappingSourceNsSwitch sourceNsSwitch = new MappingSourceNsSwitch(tree, MappingsNamespace.OFFICIAL.toString());
		readIntermediaryTree().accept(sourceNsSwitch);

		try (BufferedReader reader = Files.newBufferedReader(from, StandardCharsets.UTF_8)) {
			Tiny2Reader.read(reader, tree);
		}

		inheritMappedNamesOfEnclosingClasses(tree);

		try (Tiny2Writer writer = new Tiny2Writer(Files.newBufferedWriter(out, StandardCharsets.UTF_8), false)) {
			tree.accept(writer);
		}

		project.getLogger().info(":merged mappings in " + stopwatch.stop());
	}

	/**
	 * Searches the mapping tree for inner classes with no mapped name, whose enclosing classes have mapped names.
	 * Currently, Yarn does not export mappings for these inner classes.
	 */
	private void inheritMappedNamesOfEnclosingClasses(MemoryMappingTree tree) {
		int intermediaryIdx = tree.getNamespaceId("intermediary");
		int namedIdx = tree.getNamespaceId("named");

		// The tree does not have an index by intermediary names by default
		tree.setIndexByDstNames(true);

		for (MappingTree.ClassMapping classEntry : tree.getClasses()) {
			String intermediaryName = classEntry.getDstName(intermediaryIdx);
			String namedName = classEntry.getDstName(namedIdx);

			if (intermediaryName.equals(namedName) && intermediaryName.contains("$")) {
				String[] path = intermediaryName.split(Pattern.quote("$"));
				int parts = path.length;

				for (int i = parts - 2; i >= 0; i--) {
					String currentPath = String.join("$", Arrays.copyOfRange(path, 0, i + 1));
					String namedParentClass = tree.mapClassName(currentPath, intermediaryIdx, namedIdx);

					if (!namedParentClass.equals(currentPath)) {
						classEntry.setDstName(namedParentClass
										+ "$" + String.join("$", Arrays.copyOfRange(path, i + 1, path.length)),
								namedIdx);
						break;
					}
				}
			}
		}
	}

	private MemoryMappingTree readIntermediaryTree() throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingNsCompleter nsCompleter = new MappingNsCompleter(tree, Collections.singletonMap(MappingsNamespace.NAMED.toString(), MappingsNamespace.INTERMEDIARY.toString()), true);

		try (BufferedReader reader = Files.newBufferedReader(getIntermediaryTiny(), StandardCharsets.UTF_8)) {
			Tiny2Reader.read(reader, nsCompleter);
		}

		return tree;
	}

	private void suggestFieldNames(MinecraftProviderImpl minecraftProvider, Path oldMappings, Path newMappings) {
		Command command = new CommandProposeFieldNames();
		runCommand(command, minecraftProvider.getMergedJar().getAbsolutePath(),
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

	private void initFiles() {
		mappingsWorkingDir = getMinecraftProvider().dir(mappingsIdentifier).toPath();
		baseTinyMappings = mappingsWorkingDir.resolve("mappings-base.tiny");
		tinyMappings = mappingsWorkingDir.resolve("mappings.tiny");
		tinyMappingsJar = mappingsWorkingDir.resolve("mappings.jar");
		unpickDefinitions = mappingsWorkingDir.resolve("mappings.unpick");
		tinyMappingsWithSrg = mappingsWorkingDir.resolve("mappings-srg.tiny");
		mixinTinyMappingsWithSrg = mappingsWorkingDir.resolve("mappings-mixin-srg.tiny");
		srgToNamedSrg = mappingsWorkingDir.resolve("mappings-srg-named.srg");

		if (isRefreshDeps()) {
			cleanFiles();
		}
	}

	public void cleanFiles() {
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
	public String getTargetConfig() {
		return Constants.Configurations.MAPPINGS;
	}

	public Path getIntermediaryTiny() throws IOException {
		if (intermediaryTiny == null) {
			intermediaryTiny = getMinecraftProvider().file("intermediary-v2.tiny").toPath();

			if (getExtension().getStubIntermediaries().get()) {
				intermediaryTiny = getMinecraftProvider().file("stub-intermediary-v2.tiny").toPath();

				if (isRefreshDeps() && !hasRefreshed) {
					Files.deleteIfExists(intermediaryTiny);
				}

				if (Files.exists(intermediaryTiny)) {
					return intermediaryTiny;
				}

				hasRefreshed = true;
				generateIntermediary(intermediaryTiny);
				return intermediaryTiny;
			}

			if (!Files.exists(intermediaryTiny) || (isRefreshDeps() && !hasRefreshed)) {
				hasRefreshed = true;

				// Download and extract intermediary
				String encodedMinecraftVersion = UrlEscapers.urlFragmentEscaper().escape(getMinecraftProvider().minecraftVersion());
				String intermediaryArtifactUrl = getExtension().getIntermediaryUrl(encodedMinecraftVersion);
				File intermediaryJar = getMinecraftProvider().file("intermediary-v2.jar");
				DownloadUtil.downloadIfChanged(new URL(intermediaryArtifactUrl), intermediaryJar, getProject().getLogger());
				extractMappings(intermediaryJar.toPath(), intermediaryTiny);
			}
		}

		return intermediaryTiny;
	}

	private void generateIntermediary(Path output) throws IOException {
		MinecraftProviderImpl provider = getExtension().getMinecraftProvider();
		JarRootEntry entry = new JarRootEntry(provider.getMergedJar());
		MemoryMappingTree tree = new MemoryMappingTree();

		MappingNsCompleter visitor = new MappingNsCompleter(tree, Collections.singletonMap(MappingsNamespace.INTERMEDIARY.toString(), MappingsNamespace.OFFICIAL.toString()), true);

		if (visitor.visitHeader()) {
			visitor.visitNamespaces(MappingsNamespace.OFFICIAL.toString(), Collections.emptyList());
		}

		if (visitor.visitContent()) {
			try {
				JarReader reader = new JarReader(entry);
				reader.apply();
			} catch (IOException e) {
				e.printStackTrace();
			}

			for (JarClassEntry classEntry : entry.getAllClasses()) {
				if (visitor.visitClass(classEntry.getFullyQualifiedName())) {
					if (!visitor.visitElementContent(MappedElementKind.CLASS)) return;

					for (JarFieldEntry field : classEntry.getFields()) {
						if (visitor.visitField(field.getName(), field.getDescriptor())) {
							if (!visitor.visitElementContent(MappedElementKind.FIELD)) return;
						}
					}

					for (JarMethodEntry method : classEntry.getMethods()) {
						if (method.getName().startsWith("<")) continue;

						if (visitor.visitMethod(method.getName(), method.getDescriptor())) {
							if (!visitor.visitElementContent(MappedElementKind.METHOD)) return;
						}
					}
				}
			}
		}

		visitor.visitEnd();

		try (Tiny2Writer writer = new Tiny2Writer(Files.newBufferedWriter(output, StandardOpenOption.CREATE), false)) {
			tree.accept(writer);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public Path mappingsWorkingDir() {
		return mappingsWorkingDir;
	}

	protected String createMappingsIdentifier(String mappingsName, String version, String classifier) {
		//          mappingsName      . mcVersion . version        classifier
		// Example: net.fabricmc.yarn . 1_16_5    . 1.16.5+build.5 -v2
		return mappingsName + "." + getMinecraftProvider().minecraftVersion().replace(' ', '_').replace('.', '_').replace('-', '_') + "." + version + classifier;
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

	@Override
	public File intermediaryTinyFile() {
		try {
			return getIntermediaryTiny().toFile();
		} catch (IOException e) {
			throw new RuntimeException("Failed to get intermediary", e);
		}
	}

	public record UnpickMetadata(String unpickGroup, String unpickVersion) {
	}
}
