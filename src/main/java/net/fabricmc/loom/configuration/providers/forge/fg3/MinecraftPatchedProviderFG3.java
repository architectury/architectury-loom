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

package net.fabricmc.loom.configuration.providers.forge.fg3;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import com.google.common.base.Stopwatch;
import net.minecraftforge.binarypatcher.ConsoleTool;
import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.forge.MinecraftPatchedProvider;
import net.fabricmc.loom.configuration.providers.forge.PatchProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.srg.SpecialSourceExecutor;

public class MinecraftPatchedProviderFG3 extends MinecraftPatchedProvider {
	// Step 1: Remap Minecraft to SRG (global)
	private File minecraftClientSrgJar;
	private File minecraftServerSrgJar;

	// Step 2: Binary Patch (global)
	private File minecraftClientPatchedSrgJar;
	private File minecraftServerPatchedSrgJar;

	// Step 3: Merge (global)
	// field in super

	// Step 4: Access Transform (global or project)
	// field in super

	// Step 5: Remap Patched AT & Forge to Official (global or project)
	// fields in super

	private Path[] mergedMojangTsrg2Files;

	public MinecraftPatchedProviderFG3(Project project) {
		super(project, Constants.Forge.ACCESS_TRANSFORMER_PATH);
	}

	@Override
	public void initFiles() throws IOException {
		super.initFiles();
		File globalCache = getExtension().getForgeProvider().getGlobalCache();
		minecraftClientSrgJar = new File(globalCache, "minecraft-client-srg.jar");
		minecraftServerSrgJar = new File(globalCache, "minecraft-server-srg.jar");
		minecraftClientPatchedSrgJar = new File(globalCache, "client-srg-patched.jar");
		minecraftServerPatchedSrgJar = new File(globalCache, "server-srg-patched.jar");
	}

	@Override
	protected File[] getGlobalCaches() {
		File[] files = {
				minecraftClientSrgJar,
				minecraftServerSrgJar,
				minecraftClientPatchedSrgJar,
				minecraftServerPatchedSrgJar,
				minecraftMergedPatchedSrgJar,
				minecraftClientExtra,
		};

		if (forgeMergedJar != null) {
			files = Arrays.copyOf(files, files.length + 1);
			files[files.length - 1] = forgeMergedJar;
		}

		return files;
	}

	private boolean dirty;

	@Override
	public void beginTransform() throws Exception {
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

	@Override
	public void endTransform() throws Exception {
		if (dirty || !minecraftMergedPatchedSrgJar.exists()) {
			mergeJars(getProject().getLogger());
		}

		if (atDirty || !minecraftMergedPatchedSrgAtJar.exists()) {
			this.dirty = true;
			accessTransformForge(getProject().getLogger());
		}

		if (forgeMergedJar != null && !forgeMergedJar.exists()) {
			this.dirty = true;
		}

		if (dirty) {
			remapPatchedJar(getProject().getLogger());

			if (getExtension().isForgeAndOfficial()) {
				fillClientExtraJar();
			}
		}

		this.filesDirty = dirty;
		this.dirty = false;

		if (getExtension().isForgeAndOfficial()) {
			addDependency(minecraftClientExtra, Constants.Configurations.FORGE_EXTRA);
		}
	}

	private void createSrgJars(Logger logger) throws Exception {
		MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
		produceSrgJar(getExtension().isForgeAndOfficial(), minecraftProvider.minecraftClientJar.toPath(), minecraftProvider.getMinecraftServerJar().toPath());
	}

	private void produceSrgJar(boolean official, Path clientJar, Path serverJar) throws IOException {
		Path tmpSrg = getToSrgMappings();
		Set<File> mcLibs = getProject().getConfigurations().getByName(Constants.Configurations.MINECRAFT_DEPENDENCIES).resolve();

		ThreadingUtils.run(() -> {
			Files.copy(SpecialSourceExecutor.produceSrgJar(getExtension().getMcpConfigProvider().getRemapAction(), getProject(), "client", mcLibs, clientJar, tmpSrg), minecraftClientSrgJar.toPath());
		}, () -> {
			Files.copy(SpecialSourceExecutor.produceSrgJar(getExtension().getMcpConfigProvider().getRemapAction(), getProject(), "server", mcLibs, serverJar, tmpSrg), minecraftServerSrgJar.toPath());
		});
	}

	private Path getToSrgMappings() throws IOException {
		if (getExtension().getSrgProvider().isTsrgV2()) {
			return getExtension().getSrgProvider().getMergedMojangRaw();
		} else {
			return getExtension().getMcpConfigProvider().getMappings();
		}
	}

	public enum Environment {
		CLIENT(provider -> provider.minecraftClientSrgJar,
						provider -> provider.minecraftClientPatchedSrgJar
		),
		SERVER(provider -> provider.minecraftServerSrgJar,
						provider -> provider.minecraftServerPatchedSrgJar
		);

		final Function<MinecraftPatchedProviderFG3, File> srgJar;
		public final Function<MinecraftPatchedProviderFG3, File> patchedSrgJar;

		Environment(Function<MinecraftPatchedProviderFG3, File> srgJar,
						Function<MinecraftPatchedProviderFG3, File> patchedSrgJar) {
			this.srgJar = srgJar;
			this.patchedSrgJar = patchedSrgJar;
		}

		public String side() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private void patchJars(Logger logger) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");

		PatchProvider patchProvider = getExtension().getPatchProvider();
		patchJars(minecraftClientSrgJar, minecraftClientPatchedSrgJar, patchProvider.clientPatches, "client");
		patchJars(minecraftServerSrgJar, minecraftServerPatchedSrgJar, patchProvider.serverPatches, "server");

		ThreadingUtils.run(Environment.values(), environment -> {
			copyMissingClasses(environment.srgJar.apply(this), environment.patchedSrgJar.apply(this));
			deleteParameterNames(environment.patchedSrgJar.apply(this));

			if (getExtension().isForgeAndNotOfficial()) {
				fixParameterAnnotation(environment.patchedSrgJar.apply(this));
			}
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

	@Override
	protected void mergeJars(Logger logger) throws Exception {
		// FIXME: Hack here: There are no server-only classes so we can just copy the client JAR.
		//   This will change if upstream Loom adds the possibility for separate projects/source sets per environment.

		Files.copy(minecraftClientPatchedSrgJar.toPath(), minecraftMergedPatchedSrgJar.toPath());

		logger.lifecycle(":copying resources");

		// Copy resources
		if (getExtension().isForgeAndNotOfficial()) {
			// Copy resources
			MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
			copyNonClassFiles(minecraftProvider.minecraftClientJar, minecraftMergedPatchedSrgJar);
			copyNonClassFiles(minecraftProvider.getMinecraftServerJar(), minecraftMergedPatchedSrgJar);
		}
	}
}
