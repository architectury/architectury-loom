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

package net.fabricmc.loom.util;

import java.io.PrintStream;

import org.apache.commons.io.output.NullOutputStream;
import org.jetbrains.annotations.NotNull;

public class LoggerFilter {
	public static void replaceSystemOut() {
		try {
			PrintStream previous = System.out;
			System.setOut(new PrintStream(previous) {
				@Override
				public PrintStream printf(@NotNull String format, Object... args) {
					if (format.equals("unknown invokedynamic bsm: %s/%s%s (tag=%d iif=%b)%n")) {
						return this;
					}

					return super.printf(format, args);
				}
			});
		} catch (SecurityException ignored) {
			// Failed to replace logger filter, just ignore
		}
	}

	public static <T extends Throwable> void withSystemOutAndErrSuppressed(CheckedRunnable<T> block) throws T {
		PrintStream previousOut = System.out;
		PrintStream previousErr = System.err;

		try {
			System.setOut(new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM));
			System.setErr(new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM));
		} catch (SecurityException ignored) {
			// Failed to replace logger, just ignore
		}

		try {
			block.run();
		} finally {
			try {
				System.setOut(previousOut);
				System.setErr(previousErr);
			} catch (SecurityException ignored) {
				// Failed to replace logger, just ignore
			}
		}
	}

	public interface CheckedRunnable<T extends Throwable> {
		void run() throws T;
	}
}
