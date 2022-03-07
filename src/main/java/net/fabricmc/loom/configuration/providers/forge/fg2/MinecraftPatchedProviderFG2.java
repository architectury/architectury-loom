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

package net.fabricmc.loom.configuration.providers.forge.fg2;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.base.Stopwatch;
import dev.architectury.tinyremapper.InputTag;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;
import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.forge.MinecraftPatchedProvider;
import net.fabricmc.loom.configuration.providers.forge.PatchProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.srg.SpecialSourceExecutor;

public class MinecraftPatchedProviderFG2 extends MinecraftPatchedProvider {
	// step 0: strip minecraft jars
	private File minecraftClientJar;
	private File minecraftServerJar;

	// Step 1: Binary Patch (global)
	private File minecraftClientPatchedJar;
	private File minecraftServerPatchedJar;

	// Step 2: Merge (global)
	private File minecraftMergedPatchedJar;

	// Step 3: Srg Transform (global)
	// field in super

	// step 4: Access Transform (global or project)
	// field in super

	// Step 5: Remap Patched AT & Forge to Official (global or project)
	// fields in super

	public MinecraftPatchedProviderFG2(Project project) {
		// use AT from forge universal (btw userdev at's are named `forge_at.cfg` if this should be changed)
		super(project, "forge_at.cfg");
	}

	@Override
	public void initFiles() throws IOException {
		super.initFiles();
		File globalCache = getExtension().getForgeProvider().getGlobalCache();
		File projectDir = usesProjectCache() ? getExtension().getForgeProvider().getProjectCache() : globalCache;
		minecraftClientJar = new File(globalCache, "client-stripped.jar");
		minecraftServerJar = new File(globalCache, "server-stripped.jar");
		minecraftClientPatchedJar = new File(globalCache, "client-patched.jar");
		minecraftServerPatchedJar = new File(globalCache, "server-patched.jar");
		minecraftMergedPatchedJar = new File(globalCache, "merged-patched.jar");
	}

	@Override
	protected File[] getGlobalCaches() {
		File[] files = {
				minecraftClientJar,
				minecraftServerJar,
				minecraftClientPatchedJar,
				minecraftServerPatchedJar,
				minecraftMergedPatchedJar,
				minecraftMergedPatchedSrgJar
		};

		if (forgeMergedJar != null) {
			files = Arrays.copyOf(files, files.length + 1);
			files[files.length - 1] = forgeMergedJar;
		}

		return files;
	}

	private boolean dirty;

	@Override
	protected void beginTransform() throws Exception {
		if (atDirty) {
			getProject().getLogger().lifecycle(":found dirty access transformers");
		}

		this.dirty = false;

		// Step 0: strip the client/server jars
		if (!minecraftClientJar.exists() || !minecraftServerJar.exists()) {
			this.dirty = true;
			stripJars(getProject().getLogger());
		}

		// Step 1: Binary Patch (global)
		if (!minecraftClientPatchedJar.exists() || !minecraftServerPatchedJar.exists()) {
			this.dirty = true;
			patchJars(getProject().getLogger());
		}
	}

	@Override
	public void endTransform() throws Exception {
		// Step 2: Merge (global)
		if (dirty || !minecraftMergedPatchedJar.exists()) {
			mergeJars(getProject().getLogger());
		}

		// Step 3: Srg Transform (global)
		if (dirty || !minecraftMergedPatchedSrgJar.exists()) {
			remapPatchedJarToSrg(getProject().getLogger());
		}

		// Step 4: Access Transform (global or project)
		if (atDirty || !minecraftMergedPatchedSrgAtJar.exists()) {
			this.dirty = true;
			accessTransformForge(getProject().getLogger());
		}

		if (forgeMergedJar != null && !forgeMergedJar.exists()) {
			this.dirty = true;
		}

		// Step 5: Remap Patched AT & Forge to Official (global or project)
		if (dirty) {
			remapPatchedJar(getProject().getLogger());
		}

		this.filesDirty = dirty;
		this.dirty = false;
	}

	public enum Environment {
		CLIENT(provider -> provider.minecraftClientJar,
						provider -> provider.minecraftClientPatchedJar
		),
		SERVER(provider -> provider.minecraftServerJar,
						provider -> provider.minecraftServerPatchedJar
		);

		final Function<MinecraftPatchedProviderFG2, File> srgJar;
		final Function<MinecraftPatchedProviderFG2, File> patchedSrgJar;

		Environment(Function<MinecraftPatchedProviderFG2, File> srgJar,
						Function<MinecraftPatchedProviderFG2, File> patchedSrgJar) {
			this.srgJar = srgJar;
			this.patchedSrgJar = patchedSrgJar;
		}

		public String side() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private void stripJars(Logger logger) throws IOException {
		logger.lifecycle(":stripping jars");
		Set<String> filter = Files.readAllLines(getExtension().getMcpConfigProvider().getMappings(), StandardCharsets.UTF_8).stream()
						.filter(s -> s.startsWith("CL:"))
						.map(s -> s.split(" ")[1] + ".class")
						.collect(Collectors.toSet());
		MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
		SpecialSourceExecutor.stripJar(getProject(), minecraftProvider.minecraftClientJar.toPath(), minecraftClientJar.toPath(), filter);
		SpecialSourceExecutor.stripJar(getProject(), minecraftProvider.getMinecraftServerJar().toPath(), minecraftServerJar.toPath(), filter);
	}

	private void patchJars(Logger logger) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");

		PatchProvider patchProvider = getExtension().getPatchProvider();
		patchJars(minecraftClientJar, minecraftClientPatchedJar, patchProvider.clientPatches, "client");
		patchJars(minecraftServerJar, minecraftServerPatchedJar, patchProvider.serverPatches, "server");

		ThreadingUtils.run(Environment.values(), environment -> {
			copyMissingClasses(environment.srgJar.apply(this), environment.patchedSrgJar.apply(this));
			deleteParameterNames(environment.patchedSrgJar.apply(this));

			fixParameterAnnotation(environment.patchedSrgJar.apply(this));
		});

		logger.lifecycle(":patched jars in " + stopwatch.stop());
	}

	@Override
	protected void patchJars(File clean, File output, Path patches, String side) throws IOException {
		PrintStream previous = System.out;

		try {
			System.setOut(new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM));
		} catch (SecurityException ignored) {
			// Failed to replace logger filter, just ignore
		}

		new FG2TaskApplyBinPatches(getProject()).doTask(clean.getAbsoluteFile(), patches.toFile().getAbsoluteFile(), output.getAbsoluteFile(), side);

		try {
			System.setOut(previous);
		} catch (SecurityException ignored) {
			// Failed to replace logger filter, just ignore
		}
	}

	@Override
	protected void mergeJars(Logger logger) throws Exception {
		// FIXME: Hack here: There are no server-only classes so we can just copy the client JAR.
		//  This will change if upstream Loom adds the possibility for separate projects/source sets per environment.

		logger.lifecycle(":merging jars");
		Set<File> mcLibs = getProject().getConfigurations().getByName(Constants.Configurations.MINECRAFT_DEPENDENCIES).resolve();
		Files.copy(minecraftClientPatchedJar.toPath(), minecraftMergedPatchedJar.toPath());

		logger.lifecycle(":copying resources");

		// Copy resources
		MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
		copyNonClassFiles(minecraftProvider.minecraftClientJar, minecraftMergedPatchedJar);
		copyNonClassFiles(minecraftProvider.getMinecraftServerJar(), minecraftMergedPatchedJar);
	}

	protected void remapPatchedJarToSrg(Logger logger) throws Exception {
		getProject().getLogger().lifecycle(":remapping minecraft (TinyRemapper, official -> srg)");
		Path mcInput = minecraftMergedPatchedJar.toPath();
		Path mcOutput = minecraftMergedPatchedSrgJar.toPath();

		TinyRemapper remapper = buildRemapper(mcInput, "official", "srg");

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(mcOutput).build()) {
			InputTag mcTag = remapper.createInputTag();
			remapper.readInputsAsync(mcTag, mcInput).join();
			remapper.apply(outputConsumer, mcTag);
		} finally {
			remapper.finish();
		}

		logger.lifecycle(":copying resources");
		MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
		copyNonClassFiles(minecraftProvider.minecraftClientJar, mcOutput.toFile());
		copyNonClassFiles(minecraftProvider.getMinecraftServerJar(), mcOutput.toFile());
	}
}
