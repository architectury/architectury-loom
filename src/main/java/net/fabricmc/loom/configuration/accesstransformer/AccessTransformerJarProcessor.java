/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.configuration.accesstransformer;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import org.cadixdev.at.AccessTransformSet;
import org.cadixdev.at.io.AccessTransformFormats;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.processors.JarProcessor;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyDownloader;
import net.fabricmc.loom.util.ForgeToolExecutor;
import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public final class AccessTransformerJarProcessor implements JarProcessor {
	private final Project project;
	private byte[] atHash;
	private final Set<File> atFiles;

	public AccessTransformerJarProcessor(Project project, Set<File> atFiles) {
		this.project = project;
		this.atFiles = atFiles;
	}

	public static Set<File> getAccessTransformerFiles(Project project) {
		final Set<File> atFiles = new HashSet<>();
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		ConfigurableFileCollection accessTransformers = extension.getForge().getAccessTransformers();
		accessTransformers.finalizeValue();
		atFiles.addAll(accessTransformers.getFiles());

		if (atFiles.isEmpty()) {
			SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName("main");

			for (File srcDir : main.getResources().getSrcDirs()) {
				File projectAt = new File(srcDir, Constants.Forge.ACCESS_TRANSFORMER_PATH);

				if (projectAt.exists()) {
					atFiles.add(projectAt);
					break;
				}
			}
		}

		return atFiles;
	}

	@Override
	public String getId() {
		return "architectury:access_transformer:" + Checksum.toHex(atHash);
	}

	@Override
	public void setup() {
		atHash = getProjectAtsHash();
	}

	private byte[] getProjectAtsHash() {
		try {
			if (atFiles.isEmpty()) return ByteSource.empty().hash(Hashing.sha256()).asBytes();
			List<ByteSource> currentBytes = new ArrayList<>();

			for (File projectAt : atFiles) {
				currentBytes.add(com.google.common.io.Files.asByteSource(projectAt));
			}

			return ByteSource.concat(currentBytes).hash(Hashing.sha256()).asBytes();
		} catch (IOException e) {
			throw new UncheckedIOException("Could not compute project AT hash", e);
		}
	}

	@Override
	public void process(File file) {
		try {
			project.getLogger().lifecycle(":applying project access transformers");
			Path tempDir = Files.createTempDirectory("loom-access-transforming");
			Path tempInput = tempDir.resolve("input.jar");
			Files.copy(file.toPath(), tempInput);
			Path atPath = mergeAndRemapAccessTransformers(tempDir);

			executeAt(project, tempInput, file.toPath(), args -> {
				args.add("--atFile");
				args.add(atPath.toAbsolutePath().toString());
			});

			Files.delete(atPath);
			Files.delete(tempInput);
			Files.delete(tempDir);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not access transform " + file.getAbsolutePath(), e);
		}
	}

	private Path mergeAndRemapAccessTransformers(Path tempDir) {
		AccessTransformSet accessTransformSet = AccessTransformSet.create();

		for (File atFile : atFiles) {
			try {
				accessTransformSet.merge(AccessTransformFormats.FML.read(atFile.toPath()));
			} catch (IOException e) {
				throw new UncheckedIOException("Could not read access transformer " + atFile, e);
			}
		}

		try {
			MemoryMappingTree mappings = LoomGradleExtension.get(project).getMappingsProvider().getMappingsWithSrg();
			accessTransformSet = accessTransformSet.remap(new TinyMappingsReader(mappings, MappingsNamespace.SRG.toString(), MappingsNamespace.NAMED.toString()).read());
		} catch (IOException e) {
			throw new UncheckedIOException("Could not remap access transformers from srg to named", e);
		}

		Path accessTransformerPath = tempDir.resolve("accesstransformer.cfg");

		try {
			AccessTransformFormats.FML.write(accessTransformerPath, accessTransformSet);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write access transformers to " + accessTransformerPath, e);
		}

		return accessTransformerPath;
	}

	public static void executeAt(Project project, Path input, Path output, AccessTransformerConfiguration configuration) throws IOException {
		boolean serverBundleMetadataPresent = LoomGradleExtension.get(project).getMinecraftProvider().getServerBundleMetadata() != null;
		FileCollection classpath = new DependencyDownloader(project)
				.add(Constants.Dependencies.ACCESS_TRANSFORMERS + (serverBundleMetadataPresent ? Constants.Dependencies.Versions.ACCESS_TRANSFORMERS_NEW : Constants.Dependencies.Versions.ACCESS_TRANSFORMERS))
				.add(Constants.Dependencies.ASM + Constants.Dependencies.Versions.ASM)
				.download();
		List<String> args = new ArrayList<>();
		args.add("--inJar");
		args.add(input.toAbsolutePath().toString());
		args.add("--outJar");
		args.add(output.toAbsolutePath().toString());

		configuration.apply(args);

		ForgeToolExecutor.exec(project, spec -> {
			spec.getMainClass().set("net.minecraftforge.accesstransformer.TransformerProcessor");
			spec.setArgs(args);
			spec.setClasspath(classpath);
		}).rethrowFailure().assertNormalExitValue();
	}

	@FunctionalInterface
	public interface AccessTransformerConfiguration {
		void apply(List<String> args) throws IOException;
	}
}
