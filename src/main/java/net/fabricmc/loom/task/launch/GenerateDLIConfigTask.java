/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.task.launch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.gradle.api.logging.configuration.ConsoleOutput;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.configuration.providers.forge.ConfigValue;
import net.fabricmc.loom.configuration.providers.forge.ForgeRunTemplate;
import net.fabricmc.loom.configuration.providers.forge.ForgeRunsProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.MappedMinecraftProvider;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.PropertyUtil;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

public abstract class GenerateDLIConfigTask extends AbstractLoomTask {
	@TaskAction
	public void run() throws IOException {
		final MinecraftVersionMeta versionInfo = getExtension().getMinecraftProvider().getVersionInfo();
		File assetsDirectory = new File(getExtension().getFiles().getUserCache(), "assets");

		if (versionInfo.assets().equals("legacy")) {
			assetsDirectory = new File(assetsDirectory, "/legacy/" + versionInfo.id());
		}

		final LaunchConfig launchConfig = new LaunchConfig()
				.property(!getExtension().isQuilt() ? "fabric.development" : "loader.development", "true")
				.property(!getExtension().isQuilt() ? "fabric.remapClasspathFile" : "loader.remapClasspathFile", getExtension().getFiles().getRemapClasspathFile().getAbsolutePath())
				.property("log4j.configurationFile", getAllLog4JConfigFiles())
				.property("log4j2.formatMsgNoLookups", "true");

		if (versionInfo.hasNativesToExtract()) {
			String nativesPath = getExtension().getFiles().getNativesDirectory(getProject()).getAbsolutePath();

			launchConfig
					.property("client", "java.library.path", nativesPath)
					.property("client", "org.lwjgl.librarypath", nativesPath);
		}

		if (!getExtension().isModernForge()) {
			launchConfig
					.argument("client", "--assetIndex")
					.argument("client", getExtension().getMinecraftProvider().getVersionInfo().assetIndex().fabricId(getExtension().getMinecraftProvider().minecraftVersion()))
					.argument("client", "--assetsDir")
					.argument("client", assetsDirectory.getAbsolutePath());

			if (getExtension().areEnvironmentSourceSetsSplit()) {
				launchConfig.property("client", "fabric.gameJarPath.client", getGameJarPath("client"));
				launchConfig.property("fabric.gameJarPath", getGameJarPath("common"));
			}

			if (!getExtension().getMods().isEmpty()) {
				launchConfig.property("fabric.classPathGroups", getClassPathGroups());
			}
		}

		if (getExtension().isQuilt()) {
			launchConfig
					.argument("client", "--version")
					.argument("client", "Architectury Loom");
		}

		if (getExtension().isModernForge()) {
			// Find the mapping files for Unprotect to use for figuring out
			// which classes are from Minecraft.
			String unprotectMappings = getProject().getConfigurations()
					.getByName(Constants.Configurations.MAPPINGS_FINAL)
					.resolve()
					.stream()
					.map(File::getAbsolutePath)
					.collect(Collectors.joining(File.pathSeparator));

			launchConfig
					// Should match YarnNamingService.PATH_TO_MAPPINGS in forge-runtime
					.property("fabric.yarnWithSrg.path", getExtension().getMappingConfiguration().tinyMappingsWithSrg.toAbsolutePath().toString())
					.property("unprotect.mappings", unprotectMappings)

					.property("mixin.env.remapRefMap", "true");

			final List<String> dataGenMods = getExtension().getForge().getDataGenMods();

			// Only apply the hardcoded data arguments if the deprecated data generator API is being used.
			if (!dataGenMods.isEmpty()) {
				launchConfig
						.argument("data", "--all")
						.argument("data", "--mod")
						.argument("data", String.join(",", getExtension().getForge().getDataGenMods()))
						.argument("data", "--output")
						.argument("data", getProject().file("src/generated/resources").getAbsolutePath());
			}

			if (PropertyUtil.getAndFinalize(getExtension().getForge().getUseCustomMixin())) {
				launchConfig.property("mixin.forgeloom.inject.mappings.srg-named", getExtension().getMappingConfiguration().getReplacedTarget(getExtension(), "srg").toAbsolutePath().toString());
			} else {
				launchConfig.property("net.minecraftforge.gradle.GradleStart.srg.srg-mcp", getExtension().getMappingConfiguration().srgToNamedSrg.toAbsolutePath().toString());
			}

			Set<String> mixinConfigs = PropertyUtil.getAndFinalize(getExtension().getForge().getMixinConfigs());

			if (!mixinConfigs.isEmpty()) {
				for (String config : mixinConfigs) {
					launchConfig.argument("-mixin.config");
					launchConfig.argument(config);
				}
			}

			ForgeRunsProvider forgeRunsProvider = getExtension().getForgeRunsProvider();

			for (ForgeRunTemplate template : forgeRunsProvider.getTemplates()) {
				for (ConfigValue argument : template.args()) {
					launchConfig.argument(template.name(), argument.resolve(forgeRunsProvider));
				}

				for (Map.Entry<String, ConfigValue> property : template.props().entrySet()) {
					launchConfig.property(template.name(), property.getKey(), property.getValue().resolve(forgeRunsProvider));
				}
			}
		}

		if (getExtension().isLegacyForge()) {
			launchConfig
					.argument("client", "--tweakClass")
					.argument("client", Constants.LegacyForge.FML_TWEAKER)
					.argument("server", "--tweakClass")
					.argument("server", Constants.LegacyForge.FML_SERVER_TWEAKER)

					.argument("--accessToken")
					.argument("undefined")

					.property("net.minecraftforge.gradle.GradleStart.srg.srg-mcp", getExtension().getMappingConfiguration().srgToNamedSrg.toAbsolutePath().toString())
					.property("mixin.env.remapRefMap", "true");

			for (String config : PropertyUtil.getAndFinalize(getExtension().getForge().getMixinConfigs())) {
				launchConfig.argument("--mixin").argument(config);
			}
		}

		final boolean plainConsole = getProject().getGradle().getStartParameter().getConsoleOutput() == ConsoleOutput.Plain;
		final boolean ansiSupportedIDE = new File(getProject().getRootDir(), ".vscode").exists()
				|| new File(getProject().getRootDir(), ".idea").exists()
				|| (Arrays.stream(getProject().getRootDir().listFiles()).anyMatch(file -> file.getName().endsWith(".iws")));

		//Enable ansi by default for idea and vscode when gradle is not ran with plain console.
		if (ansiSupportedIDE && !plainConsole) {
			launchConfig.property("fabric.log.disableAnsi", "false");
		}

		FileUtils.writeStringToFile(getExtension().getFiles().getDevLauncherConfig(), launchConfig.asString(), StandardCharsets.UTF_8);
	}

	private String getAllLog4JConfigFiles() {
		return getExtension().getLog4jConfigs().getFiles().stream()
				.map(File::getAbsolutePath)
				.collect(Collectors.joining(","));
	}

	private String getGameJarPath(String env) {
		MappedMinecraftProvider.Split split = (MappedMinecraftProvider.Split) getExtension().getNamedMinecraftProvider();

		return switch (env) {
		case "client" -> split.getClientOnlyJar().getPath().toAbsolutePath().toString();
		case "common" -> split.getCommonJar().getPath().toAbsolutePath().toString();
		default -> throw new UnsupportedOperationException();
		};
	}

	/**
	 * See: https://github.com/FabricMC/fabric-loader/pull/585.
	 */
	private String getClassPathGroups() {
		return getExtension().getMods().stream()
				.map(modSettings ->
						SourceSetHelper.getClasspath(modSettings, getProject()).stream()
							.map(File::getAbsolutePath)
							.collect(Collectors.joining(File.pathSeparator))
				)
				.collect(Collectors.joining(File.pathSeparator+File.pathSeparator));
	}

	public static class LaunchConfig {
		private final Map<String, List<String>> values = new HashMap<>();

		public LaunchConfig property(String key, String value) {
			return property("common", key, value);
		}

		public LaunchConfig property(String side, String key, String value) {
			values.computeIfAbsent(side + "Properties", (s -> new ArrayList<>()))
					.add(String.format("%s=%s", key, value));
			return this;
		}

		public LaunchConfig argument(String value) {
			return argument("common", value);
		}

		public LaunchConfig argument(String side, String value) {
			values.computeIfAbsent(side + "Args", (s -> new ArrayList<>()))
					.add(value);
			return this;
		}

		public String asString() {
			StringJoiner stringJoiner = new StringJoiner("\n");

			for (Map.Entry<String, List<String>> entry : values.entrySet()) {
				stringJoiner.add(entry.getKey());

				for (String s : entry.getValue()) {
					stringJoiner.add("\t" + s);
				}
			}

			return stringJoiner.toString();
		}
	}
}
