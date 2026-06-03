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

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.SetProperty;

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
	 * Gets the jar paths to the access wideners or class tweakers that will be converted to ATs for NeoForge runtime.
	 * If you specify multiple files, they will be merged into one.
	 *
	 * <p>The file paths are relative to the mod jar root, corresponding to {@code resources} directories in
	 * a development environment, <strong>not</strong> the project directory!
	 * For example, {@code "my_mod.accesswidener"} corresponds to the source file {@code src/main/resources/my_mod.accesswidener}.
	 *
	 * <p>The specified files will be converted and removed from the final jar.
	 *
	 * <p>In projects on Minecraft versions that are obfuscated, this property is simply added to the corresponding
	 * property in {@link net.fabricmc.loom.task.RemapJarTask}.
	 *
	 * @return the property containing access widener paths in the final jar
	 * @see net.fabricmc.loom.task.RemapJarTask#getAtAccessWideners()
	 */
	SetProperty<String> getAtAccessWideners();
}
