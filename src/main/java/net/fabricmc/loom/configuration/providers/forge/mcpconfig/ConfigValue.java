/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.configuration.providers.forge.mcpconfig;

import java.util.function.Function;

/**
 * A string or a variable in an MCPConfig step or function.
 *
 * <p>The special config value variable {@value #OUTPUT} is treated
 * as the current step's output path.
 *
 * <p>The suffix {@value #PREVIOUS_OUTPUT_SUFFIX} can be used to suffix step names
 * to get their output paths.
 */
public sealed interface ConfigValue {
	String OUTPUT = "output";
	String PREVIOUS_OUTPUT_SUFFIX = "Output";
	String SRG_MAPPINGS_NAME = "mappings";

	<R> R fold(Function<? super Constant, ? extends R> constant, Function<? super Variable, ? extends R> variable);

	static ConfigValue of(String str) {
		if (str.startsWith("{") && str.endsWith("}")) {
			return new Variable(str.substring(1, str.length() - 1));
		}

		return new Constant(str);
	}

	record Constant(String value) implements ConfigValue {
		@Override
		public <R> R fold(Function<? super Constant, ? extends R> constant, Function<? super Variable, ? extends R> variable) {
			return constant.apply(this);
		}
	}

	record Variable(String name) implements ConfigValue {
		@Override
		public <R> R fold(Function<? super Constant, ? extends R> constant, Function<? super Variable, ? extends R> variable) {
			return variable.apply(this);
		}
	}
}
