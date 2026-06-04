/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
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

package net.fabricmc.loom.api.aw2at;

import org.gradle.api.provider.SetProperty;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Experimental
public interface Aw2AtSettings {
	/**
	 * Gets the jar paths to the access wideners or class tweakers that will be converted to ATs for Forge or NeoForge runtime.
	 * If you specify multiple files, they will be merged into one.
	 *
	 * <p>The file paths are relative to the mod jar root, corresponding to {@code resources} directories in
	 * a development environment, <strong>not</strong> the project directory!
	 * For example, {@code "my_mod.accesswidener"} corresponds to the source file {@code src/main/resources/my_mod.accesswidener}.
	 *
	 * <p>The specified files will be converted and removed from the final jar.
	 *
	 * @return the property containing access widener paths in the final jar
	 */
	SetProperty<String> getAccessWideners();
}
