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

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;

/**
 * A writer for line maps in the format of {@link net.fabricmc.loom.decompilers.LineNumberRemapper}.
 *
 * @author Juuz
 */
public final class LineMapWriter extends LineMapVisitor implements Closeable {
	private final Writer writer;

	public LineMapWriter(Writer writer) {
		super(null);
		this.writer = writer;
	}

	@Override
	public void visitClass(String name, int max, int maxDest) throws IOException {
		writer.append(name)
				.append('\t').append(Integer.toString(max))
				.append('\t').append(Integer.toString(maxDest))
				.append('\n');
	}

	@Override
	public void visitLine(int src, int dest) throws IOException {
		writer.append('\t').append(Integer.toString(src))
				.append('\t').append(Integer.toString(dest))
				.append('\n');
	}

	@Override
	public void close() throws IOException {
		writer.close();
	}
}
