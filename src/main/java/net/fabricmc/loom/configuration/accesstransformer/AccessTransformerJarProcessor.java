/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 FabricMC
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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Manifest;

import javax.inject.Inject;

import com.google.common.hash.Hashing;
import com.google.common.io.MoreFiles;
import dev.architectury.loom.util.TempFiles;
import org.cadixdev.at.AccessTransformSet;
import org.cadixdev.at.io.AccessTransformFormats;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.api.processor.MinecraftJarProcessor;
import net.fabricmc.loom.api.processor.ProcessorContext;
import net.fabricmc.loom.api.processor.SpecContext;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyDownloader;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.ForgeToolExecutor;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.lorenztiny.TinyMappingsReader;

public class AccessTransformerJarProcessor implements MinecraftJarProcessor<AccessTransformerJarProcessor.Spec> {
	private static final Logger LOGGER = Logging.getLogger(AccessTransformerJarProcessor.class);
	private final String name;
	private final Project project;
	private final Iterable<File> localAccessTransformers;

	@Inject
	public AccessTransformerJarProcessor(String name, Project project, Iterable<File> localAccessTransformers) {
		this.name = name;
		this.project = project;
		this.localAccessTransformers = localAccessTransformers;
	}

	@Override
	public @Nullable AccessTransformerJarProcessor.Spec buildSpec(SpecContext context) {
		final List<AccessTransformerEntry> entries = new ArrayList<>();

		for (File atFile : localAccessTransformers) {
			final Path atPath = atFile.toPath();
			final String hash;

			try {
				hash = MoreFiles.asByteSource(atPath).hash(Hashing.sha256()).toString();
			} catch (IOException e) {
				throw new UncheckedIOException("Could not compute AT hash", e);
			}

			entries.add(new AccessTransformerEntry.Standalone(atPath, hash));
		}

		for (FabricModJson localMod : context.localMods()) {
			final byte[] bytes;
			final boolean legacyForge = LoomGradleExtension.get(project).isLegacyForge();

			try {
				if (legacyForge) {
					bytes = null; // this is because javac complains
					byte[] manifestBytes = localMod.getSource().read("META-INF/MANIFEST.MF");
					Manifest manifest = new Manifest();
					manifest.read(new ByteArrayInputStream(manifestBytes));
					String fmlAT = manifest.getMainAttributes().getValue(Constants.LegacyForge.FMLAT);
					// in legacy forge, mods can have multiple access transformers, separated by spaces.
					// we will deal with this by adding an entry for every single AT.
					for (String atPath : fmlAT.split(" ")) {
						byte[] accessTransformer = localMod.getSource().read("META-INF/" + atPath);
						final String hash = Hashing.sha256().hashBytes(accessTransformer).toString();
						entries.add(new AccessTransformerEntry.LegacyMod(localMod, hash, atPath));
					}
				} else {
					bytes = localMod.getSource().read(Constants.Forge.ACCESS_TRANSFORMER_PATH);
				}
			} catch (FileNotFoundException | NoSuchFileException e) {
				continue;
			} catch (IOException e) {
				throw ExceptionUtil.createDescriptiveWrapper(UncheckedIOException::new, "Could not read accesstransformer.cfg", e);
			}

			if (!legacyForge) {
				final String hash = Hashing.sha256().hashBytes(bytes).toString();
				entries.add(new AccessTransformerEntry.Mod(localMod, hash));
			}
		}

		return !entries.isEmpty() ? new Spec(entries) : null;
	}

	@Override
	public void processJar(Path jar, Spec spec, ProcessorContext context) throws IOException {
		try (var tempFiles = new TempFiles()) {
			LOGGER.lifecycle(":applying project access transformers");
			final Path tempInput = tempFiles.file("input", ".jar");
			Files.copy(jar, tempInput, StandardCopyOption.REPLACE_EXISTING);
			final Path atPath = mergeAndRemapAccessTransformers(context, spec.accessTransformers(), tempFiles);

			executeAt(project, tempInput, jar, args -> {
				args.add("--atFile");
				args.add(atPath.toAbsolutePath().toString());
			});
		} catch (IOException e) {
			throw ExceptionUtil.createDescriptiveWrapper(UncheckedIOException::new, "Could not access transform " + jar.toAbsolutePath(), e);
		}
	}

	private Path mergeAndRemapAccessTransformers(ProcessorContext context, List<AccessTransformerEntry> accessTransformers, TempFiles tempFiles) throws IOException {
		AccessTransformSet accessTransformSet = AccessTransformSet.create();

		for (AccessTransformerEntry entry : accessTransformers) {
			try (Reader reader = entry.openReader()) {
				accessTransformSet.merge(AccessTransformFormats.FML.read(reader));
			} catch (IOException e) {
				throw new IOException("Could not read access transformer " + entry, e);
			}
		}

		accessTransformSet = accessTransformSet.remap(new TinyMappingsReader(context.getMappings(), MappingsNamespace.SRG.toString(), MappingsNamespace.NAMED.toString()).read());

		final Path accessTransformerPath = tempFiles.file("accesstransformer-merged", ".cfg");

		try {
			AccessTransformFormats.FML.write(accessTransformerPath, accessTransformSet);
		} catch (IOException e) {
			throw new IOException("Could not write access transformers to " + accessTransformerPath, e);
		}

		return accessTransformerPath;
	}

	@Override
	public String getName() {
		return name;
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

	public record Spec(List<AccessTransformerEntry> accessTransformers) implements MinecraftJarProcessor.Spec {
	}
}
