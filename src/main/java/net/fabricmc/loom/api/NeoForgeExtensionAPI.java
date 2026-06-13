/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023-2026 FabricMC
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

package net.fabricmc.loom.api;

import org.gradle.api.Action;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;
import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.loom.api.aw2at.Aw2AtSettings;
import net.fabricmc.loom.util.Check;

/**
 * This is the NeoForge extension API available to build scripts.
 */
public interface NeoForgeExtensionAPI {
	/**
	 * A collection of all project access transformers.
	 * The collection should only contain AT files, and not directories or other files.
	 *
	 * <p>If this collection is empty, Loom tries to resolve the AT from the default path
	 * ({@code META-INF/accesstransformer.cfg} in the {@code main} source set).
	 *
	 * @return the collection of AT files
	 */
	ConfigurableFileCollection getAccessTransformers();

	/**
	 * Adds a {@linkplain #getAccessTransformers() project access transformer}.
	 *
	 * @param file the file, evaluated as per {@link org.gradle.api.Project#file(Object)}
	 */
	void accessTransformer(Object file);

	/**
	 * Sets up AW → AT conversion for the provided jar task.
	 *
	 * <p>The file paths are relative to the mod jar root, corresponding to {@code resources} directories in
	 * a development environment, <strong>not</strong> the project directory!
	 * For example, {@code "my_mod.accesswidener"} corresponds to the source file {@code src/main/resources/my_mod.accesswidener}.
	 *
	 * <p>The specified files will be converted and removed from the final jar.
	 *
	 * <p>In projects with an obfuscated version of Minecraft, this method must target {@code remapJar} or another
	 * {@link net.fabricmc.loom.task.RemapJarTask} in order for the access transformer to be remapped properly.
	 *
	 * <p>When the provided task is a {@link net.fabricmc.loom.task.RemapJarTask}, the AW paths will simply be added
	 * to the corresponding {@link net.fabricmc.loom.task.RemapJarTask#getAtAccessWideners() atAccessWideners} property.
	 */
	@ApiStatus.Experimental
	void convertAccessWideners(TaskProvider<? extends Jar> jarTask, Action<? super Aw2AtSettings> action);

	/**
	 * Sets up AW → AT conversion for the provided jar task.
	 *
	 * <p>The file paths are relative to the mod jar root, corresponding to {@code resources} directories in
	 * a development environment, <strong>not</strong> the project directory!
	 * For example, {@code "my_mod.accesswidener"} corresponds to the source file {@code src/main/resources/my_mod.accesswidener}.
	 *
	 * <p>The specified files will be converted and removed from the final jar.
	 *
	 * <p>In projects with an obfuscated version of Minecraft, this method must target {@code remapJar} or another
	 * {@link net.fabricmc.loom.task.RemapJarTask} in order for the access transformer to be remapped properly.
	 *
	 * <p>When the provided task is a {@link net.fabricmc.loom.task.RemapJarTask}, the AW paths will simply be added
	 * to the corresponding {@link net.fabricmc.loom.task.RemapJarTask#getAtAccessWideners() atAccessWideners} property.
	 *
	 * <p>Usage example:
	 * {@snippet : lang=kotlin
	 * loom.neoForge.convertAccessWideners(tasks.jar, "my_mod.accesswidener")
	 * }
	 *
	 * @param awPaths the paths of the access wideners relative to the mod jar root, cannot be empty
	 */
	@ApiStatus.Experimental
	default void convertAccessWideners(TaskProvider<? extends Jar> jarTask, String... awPaths) {
		Check.require(awPaths.length >= 1, "At least one access widener path must be provided");
		convertAccessWideners(jarTask, settings -> {
			settings.getAccessWideners().addAll(awPaths);
		});
	}
}
