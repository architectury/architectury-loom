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
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Nullable;

/**
 * A visitor for line mapping files that can be read with {@link net.fabricmc.loom.decompilers.LineNumberRemapper}.
 *
 * <p>Delegates by default to a {@code next} visitor if it's not null, like ASM's {@code ClassVisitor}.
 *
 * @author Juuz
 */
public abstract class LineMapVisitor {
	private final LineMapVisitor next;

	public LineMapVisitor(@Nullable LineMapVisitor next) {
		this.next = next;
	}

	/**
	 * Visits a class declaration.
	 *
	 * @param name    the internal class name (e.g. {@code a/b/C$Nested})
	 * @param max     the original maximum line number in the class
	 * @param maxDest the new maximum line number in the class
	 */
	public void visitClass(String name, int max, int maxDest) throws IOException {
		if (next != null) {
			next.visitClass(name, max, maxDest);
		}
	}

	/**
	 * Visits a line in the {@linkplain #visitClass previously visited class}.
	 *
	 * @param src  the original line number
	 * @param dest the mapped line number
	 */
	public void visitLine(int src, int dest) throws IOException {
		if (next != null) {
			next.visitLine(src, dest);
		}
	}

	/**
	 * Processes a line mapping file using an arbitrary visitor.
	 *
	 * @param path           the path to the line mapping file
	 * @param visitorFactory a factory that creates a "filtering" line map visitor
	 */
	public static void process(Path path, UnaryOperator<LineMapVisitor> visitorFactory) throws IOException {
		StringWriter sw = new StringWriter();
		LineMapWriter writer = new LineMapWriter(sw);
		LineMapVisitor visitor = visitorFactory.apply(writer);

		try (LineMapReader reader = new LineMapReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
			reader.accept(visitor);
		}

		Files.writeString(path, sw.toString(), StandardCharsets.UTF_8);
	}
}
