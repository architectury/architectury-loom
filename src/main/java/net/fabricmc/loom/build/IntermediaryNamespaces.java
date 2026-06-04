/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2026 FabricMC
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

package net.fabricmc.loom.build;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.ModPlatform;

// TODO: Check if some of the usages of this class should be replaced with the prod namespace

/**
 * Contains methods for checking the (fallback/platform default) intermediary namespace of a project.
 *
 * <p>The namespace returned by methods in this class is partially wrong for obfuscated versions of Forge that use
 * Mojang names at runtime.
 * SRG names are still used in the toolchain, so the methods in this class are useful for those versions.
 * The actual production namespace is available from the extension using {@link net.fabricmc.loom.api.LoomGradleExtensionAPI#getProductionNamespace()}.
 */
public final class IntermediaryNamespaces {
	/**
	 * Returns the intermediary namespace of the project.
	 */
	public static String intermediary(Project project) {
		return intermediaryNamespace(project).toString();
	}

	/**
	 * Returns the intermediary namespace of the project.
	 */
	public static MappingsNamespace intermediaryNamespace(Project project) {
		return intermediaryNamespace(LoomGradleExtension.get(project).getPlatform().get());
	}

	/**
	 * Returns the intermediary namespace of the platform.
	 * This is the fallback used before the extension property is set.
	 */
	public static MappingsNamespace intermediaryNamespace(ModPlatform platform) {
		return switch (platform) {
		case FABRIC, QUILT -> MappingsNamespace.INTERMEDIARY;
		case FORGE -> MappingsNamespace.SRG;
		case NEOFORGE -> MappingsNamespace.MOJANG;
		};
	}

	/**
	 * Potentially replaces the remapping target namespace for mixin refmaps.
	 *
	 * <p>{@code srg} and {@code mojang} {@linkplain net.fabricmc.loom.api.LoomGradleExtensionAPI#getProductionNamespace() production namespaces} are replaced
	 * by {@code intermediary} since fabric-mixin-compile-extensions only supports {@code intermediary} and {@code official}.
	 * We transform the namespaces in the input mappings, e.g. {@code intermediary} -> {@code yraidemretni} and
	 * {@code srg} -> {@code intermediary}.
	 *
	 * @param project   the project
	 * @param namespace the original namespace
	 * @return the correct namespace to use
	 */
	public static String replaceMixinIntermediaryNamespace(Project project, String namespace) {
		final MappingsNamespace prodNamespace = LoomGradleExtension.get(project).getProductionNamespaceEnum().get();

		return switch (prodNamespace) {
			case SRG, MOJANG -> prodNamespace.toString().equals(namespace) ? MappingsNamespace.INTERMEDIARY.toString() : namespace;
			default -> namespace;
		};
	}
}
