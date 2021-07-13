/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018-2021 FabricMC
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

package net.fabricmc.loom.configuration.mods;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import com.google.common.base.Stopwatch;
import com.google.gson.JsonObject;
import dev.architectury.tinyremapper.InputTag;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;
import org.gradle.api.Project;
import org.objectweb.asm.commons.Remapper;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.StringZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerRemapper;
import net.fabricmc.accesswidener.AccessWidenerWriter;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.RemappedConfigurationEntry;
import net.fabricmc.loom.configuration.processors.dependency.ModDependencyInfo;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProviderImpl;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMappedProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.LoggerFilter;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.loom.util.srg.AtRemapper;
import net.fabricmc.loom.util.srg.CoreModClassRemapper;
import net.fabricmc.mapping.tree.TinyTree;

public class ModProcessor {
	public static void processMods(Project project, List<ModDependencyInfo> processList) throws IOException {
		if (processList.stream().noneMatch(ModDependencyInfo::requiresRemapping)) {
			return;
		}

		ArrayList<ModDependencyInfo> remapList = new ArrayList<>();

		for (ModDependencyInfo info : processList) {
			if (info.requiresRemapping()) {
				if (info.getRemappedOutput().exists()) {
					info.getRemappedOutput().delete();
				}

				remapList.add(info);
			}
		}

		remapJars(project, processList);

		for (ModDependencyInfo info : processList) {
			if (!info.getRemappedOutput().exists()) {
				throw new RuntimeException("Failed to find remapped mod" + info);
			}
		}

		for (ModDependencyInfo info : remapList) {
			stripNestedJars(info.getRemappedOutput());
		}
	}

	private static void stripNestedJars(File file) {
		if (!ZipUtil.containsEntry(file, "fabric.mod.json")) return;
		// Strip out all contained jar info as we dont want loader to try and load the jars contained in dev.
		ZipUtil.transformEntries(file, new ZipEntryTransformerEntry[] {(new ZipEntryTransformerEntry("fabric.mod.json", new StringZipEntryTransformer() {
			@Override
			protected String transform(ZipEntry zipEntry, String input) {
				JsonObject json = LoomGradlePlugin.GSON.fromJson(input, JsonObject.class);
				json.remove("jars");
				return LoomGradlePlugin.GSON.toJson(json);
			}
		}))});
	}

	private static byte[] remapAccessWidener(byte[] input, Remapper remapper) {
		try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(input), StandardCharsets.UTF_8))) {
			AccessWidener accessWidener = new AccessWidener();
			AccessWidenerReader accessWidenerReader = new AccessWidenerReader(accessWidener);
			accessWidenerReader.read(bufferedReader);

			AccessWidenerRemapper accessWidenerRemapper = new AccessWidenerRemapper(accessWidener, remapper, "named");
			AccessWidener remapped = accessWidenerRemapper.remap();
			AccessWidenerWriter accessWidenerWriter = new AccessWidenerWriter(remapped);

			try (StringWriter writer = new StringWriter()) {
				accessWidenerWriter.write(writer);
				return writer.toString().getBytes(StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void remapJars(Project project, List<ModDependencyInfo> processList) throws IOException {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		String fromM = extension.isForge() ? "srg" : "intermediary";
		String toM = "named";

		MinecraftMappedProvider mappedProvider = extension.getMinecraftMappedProvider();
		MappingsProviderImpl mappingsProvider = extension.getMappingsProvider();

		Path mc = extension.isForge() ? mappedProvider.getSrgJar().toPath() : mappedProvider.getIntermediaryJar().toPath();
		Path[] mcDeps = project.getConfigurations().getByName(Constants.Configurations.LOADER_DEPENDENCIES).getFiles()
				.stream().map(File::toPath).toArray(Path[]::new);

		List<ModDependencyInfo> remapList = processList.stream().filter(ModDependencyInfo::requiresRemapping).collect(Collectors.toList());

		Stopwatch stopwatch = Stopwatch.createStarted();
		project.getLogger().lifecycle(":remapping " + remapList.size() + " mods (TinyRemapper, " + fromM + " -> " + toM + ")");

		TinyTree mappings = extension.isForge() ? mappingsProvider.getMappingsWithSrg() : mappingsProvider.getMappings();
		LoggerFilter.replaceSystemOut();
		TinyRemapper remapper = TinyRemapper.newRemapper()
				.logger(project.getLogger()::lifecycle)
				.logUnknownInvokeDynamic(false)
				.withMappings(TinyRemapperMappingsHelper.create(mappings, fromM, toM, false))
				.renameInvalidLocals(false)
				.build();

		remapper.readClassPathAsync(mc);

		if (extension.isForge()) {
			remapper.readClassPathAsync(mappedProvider.getForgeSrgJar().toPath());
		}

		remapper.readClassPathAsync(mcDeps);

		final Map<ModDependencyInfo, InputTag> tagMap = new HashMap<>();
		final Map<ModDependencyInfo, OutputConsumerPath> outputConsumerMap = new HashMap<>();
		final Map<ModDependencyInfo, byte[]> accessWidenerMap = new HashMap<>();

		for (RemappedConfigurationEntry entry : Constants.MOD_COMPILE_ENTRIES) {
			for (File inputFile : project.getConfigurations().getByName(entry.sourceConfiguration()).getFiles()) {
				if (remapList.stream().noneMatch(info -> info.getInputFile().equals(inputFile))) {
					project.getLogger().debug("Adding " + inputFile + " onto the remap classpath");

					remapper.readClassPathAsync(inputFile.toPath());
				}
			}
		}

		for (ModDependencyInfo info : remapList) {
			InputTag tag = remapper.createInputTag();

			project.getLogger().debug("Adding " + info.getInputFile() + " as a remap input");

			remapper.readInputsAsync(tag, info.getInputFile().toPath());
			tagMap.put(info, tag);
		}

		// Apply this in a second loop as we need to ensure all the inputs are on the classpath before remapping.
		for (ModDependencyInfo info : remapList) {
			OutputConsumerPath outputConsumer;

			try {
				outputConsumer = new OutputConsumerPath.Builder(info.getRemappedOutput().toPath()).build();
			} catch (Exception e) {
				throw new IOException("Could not create output consumer for " + info.getRemappedOutput().getAbsolutePath());
			}

			outputConsumer.addNonClassFiles(info.getInputFile().toPath());
			outputConsumerMap.put(info, outputConsumer);
			String accessWidener = info.getAccessWidener();

			if (accessWidener != null) {
				accessWidenerMap.put(info, remapAccessWidener(ZipUtil.unpackEntry(info.inputFile, accessWidener), remapper.getRemapper()));
			}

			remapper.apply(outputConsumer, tagMap.get(info));
		}

		remapper.finish();
		project.getLogger().lifecycle(":remapped " + remapList.size() + " mods (TinyRemapper, " + fromM + " -> " + toM + ") in " + stopwatch.stop());

		for (ModDependencyInfo info : remapList) {
			outputConsumerMap.get(info).close();
			byte[] accessWidener = accessWidenerMap.get(info);

			if (accessWidener != null) {
				ZipUtil.replaceEntry(info.getRemappedOutput(), info.getAccessWidener(), accessWidener);
			}

			if (extension.isForge()) {
				AtRemapper.remap(project.getLogger(), info.getRemappedOutput().toPath(), mappings);
				CoreModClassRemapper.remapJar(info.getRemappedOutput().toPath(), mappings, project.getLogger());

				if (ZipUtil.containsEntry(info.getRemappedOutput(), "META-INF/MANIFEST.MF")) {
					ZipUtil.transformEntry(info.getRemappedOutput(), "META-INF/MANIFEST.MF", (in, zipEntry, out) -> {
						Manifest manifest = new Manifest(in);
						fixManifest(manifest);
						out.putNextEntry(new ZipEntry(zipEntry.getName()));
						manifest.write(out);
						out.closeEntry();
					});
				}

				List<String> filesToRemove = new ArrayList<>();
				ZipUtil.iterate(info.getRemappedOutput(), (in, zipEntry) -> {
					if (zipEntry.getName().toLowerCase(Locale.ROOT).endsWith(".rsa") || zipEntry.getName().toLowerCase(Locale.ROOT).endsWith(".sf")) {
						if (zipEntry.getName().startsWith("META-INF")) {
							filesToRemove.add(zipEntry.getName());
						}
					}
				});
				ZipUtil.removeEntries(info.getRemappedOutput(), filesToRemove.toArray(new String[0]));
			}

			info.finaliseRemapping();
		}
	}

	private static void fixManifest(Manifest manifest) {
		Attributes mainAttrs = manifest.getMainAttributes();

		mainAttrs.remove(Attributes.Name.SIGNATURE_VERSION);

		for (Iterator<Attributes> it = manifest.getEntries().values().iterator(); it.hasNext(); ) {
			Attributes attrs = it.next();

			for (Iterator<Object> it2 = attrs.keySet().iterator(); it2.hasNext(); ) {
				Attributes.Name attrName = (Attributes.Name) it2.next();
				String name = attrName.toString();

				if (name.endsWith("-Digest") || name.contains("-Digest-") || name.equals("Magic")) {
					it2.remove();
				}
			}

			if (attrs.isEmpty()) it.remove();
		}
	}

	public static JsonObject readInstallerJson(File file, Project project) {
		try {
			LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
			String launchMethod = extension.getLoaderLaunchMethod();

			String jsonStr;

			try (JarFile jarFile = new JarFile(file)) {
				ZipEntry entry = null;

				if (!launchMethod.isEmpty()) {
					entry = jarFile.getEntry("fabric-installer." + launchMethod + ".json");

					if (entry == null) {
						project.getLogger().warn("Could not find loader launch method '" + launchMethod + "', falling back");
					}
				}

				if (entry == null) {
					entry = jarFile.getEntry("fabric-installer.json");

					if (entry == null) {
						return null;
					}
				}

				try (InputStream inputstream = jarFile.getInputStream(entry)) {
					jsonStr = new String(inputstream.readAllBytes(), StandardCharsets.UTF_8);
				}
			}

			return LoomGradlePlugin.GSON.fromJson(jsonStr, JsonObject.class);
		} catch (IOException e) {
			e.printStackTrace();
		}

		return null;
	}
}
