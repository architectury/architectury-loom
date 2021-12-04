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

package net.fabricmc.loom.api.decompilers.architectury;

import org.gradle.api.Project;

import net.fabricmc.loom.api.decompilers.LoomDecompiler;

/**
 * A decompiler definition. Differs from {@link LoomDecompiler} by allowing
 * to use a project context for creating the decompiler, which lets you make
 * configurable decompilers.
 *
 * <p>Note that JVM forking is not handled by this interface, and that is
 * the responsibility of the decompiler implementation.
 */
public interface ArchitecturyLoomDecompiler {
	/**
	 * {@return the name of the decompiler}
	 * It is used for naming the source generation task ({@code genSourcesWith[name]}).
	 */
	String name();

	/**
	 * Creates a {@link LoomDecompiler} from a {@link Project} context.
	 *
	 * <p>The name of the created decompiler is not used.
	 *
	 * @param project the project context
	 * @return the created decompiler
	 */
	LoomDecompiler create(Project project);
}
