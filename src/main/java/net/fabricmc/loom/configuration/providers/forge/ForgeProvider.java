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

import java.io.File;

import net.minecraftforge.srgutils.MinecraftVersion;
import org.gradle.api.Project;

import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.util.Constants;

public class ForgeProvider extends DependencyProvider {
	private ForgeVersion version = new ForgeVersion(null);
	private File globalCache;
	private File projectCache;

	public ForgeProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		version = new ForgeVersion(dependency.getResolvedVersion());
		String userdevClassifier;

		if (MinecraftVersion.from(version.getMinecraftVersion()).compareTo(MinecraftVersion.from("1.13")) < 0) {
			userdevClassifier = "userdev3";
		} else {
			userdevClassifier = "userdev";
		}

		addDependency(dependency.getDepString() + ":" + userdevClassifier, Constants.Configurations.FORGE_USERDEV);
		addDependency(dependency.getDepString() + ":installer", Constants.Configurations.FORGE_INSTALLER);
	}

	public ForgeVersion getVersion() {
		return version;
	}

	public File getGlobalCache() {
		if (globalCache == null) {
			globalCache = getMinecraftProvider().dir("forge/" + version.getCombined());
			globalCache.mkdirs();
		}

		return globalCache;
	}

	public File getProjectCache() {
		if (projectCache == null) {
			projectCache = new File(getDirectories().getRootProjectPersistentCache(), getMinecraftProvider().minecraftVersion() + "/forge/" + getExtension().getForgeProvider().getVersion().getCombined() + "/project-" + getProject().getPath().replace(':', '@'));
			projectCache.mkdirs();
		}

		return projectCache;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.FORGE;
	}

	public static final class ForgeVersion {
		private final String combined;
		private final String minecraftVersion;
		private final String forgeVersion;

		public ForgeVersion(String combined) {
			this.combined = combined;

			if (combined == null) {
				this.minecraftVersion = "NO_VERSION";
				this.forgeVersion = "NO_VERSION";
				return;
			}

			int hyphenIndex = combined.indexOf('-');

			if (hyphenIndex != -1) {
				this.minecraftVersion = combined.substring(0, hyphenIndex);
				this.forgeVersion = combined.substring(hyphenIndex + 1);
			} else {
				this.minecraftVersion = "NO_VERSION";
				this.forgeVersion = combined;
			}
		}

		public String getCombined() {
			return combined;
		}

		public String getMinecraftVersion() {
			return minecraftVersion;
		}

		public String getForgeVersion() {
			return forgeVersion;
		}
	}
}
