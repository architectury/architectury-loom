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

package net.fabricmc.loom.decompilers.linemap;

import java.io.IOException;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

/**
 * A line map visitor that filters entries based on the class name.
 *
 * <p>If the {@code filter} returns false for class, its declaration and
 * all contained line entries will be skipped.
 *
 * @author Juuz
 */
public final class LineMapClassFilter extends LineMapVisitor {
	private final Predicate<String> filter;
	private boolean active = true;

	public LineMapClassFilter(@Nullable LineMapVisitor next, Predicate<String> filter) {
		super(next);
		this.filter = filter;
	}

	@Override
	public void visitClass(String name, int max, int maxDest) throws IOException {
		active = filter.test(name);

		if (active) {
			super.visitClass(name, max, maxDest);
		}
	}

	@Override
	public void visitLine(int src, int dest) throws IOException {
		if (active) {
			super.visitLine(src, dest);
		}
	}
}
