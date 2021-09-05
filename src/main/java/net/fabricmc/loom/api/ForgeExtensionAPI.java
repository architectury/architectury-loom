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

package net.fabricmc.loom.api;

import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

/**
 * This is the forge extension api available exposed to build scripts.
 */
// TODO: Move other forge-related configuration here
public interface ForgeExtensionAPI {
	/**
	 * If true, {@linkplain LoomGradleExtensionAPI#getAccessWidenerPath() the project access widener file}
	 * will be remapped to an access transformer file if set.
	 *
	 * @return the property
	 */
	Property<Boolean> getConvertAccessWideners();

	/**
	 * A set of additional access widener files that will be converted to access transformers
	 * {@linkplain #getConvertAccessWideners() if enabled}. The files are specified as paths in jar files
	 * (e.g. {@code path/to/my_aw.accesswidener}).
	 *
	 * @return the property
	 */
	SetProperty<String> getExtraAccessWideners();
}
