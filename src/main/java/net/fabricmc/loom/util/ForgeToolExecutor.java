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

package net.fabricmc.loom.util;

import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.process.ExecResult;
import org.gradle.process.JavaExecSpec;

/**
 * Contains helpers for executing Forge's command line tools
 * with suppressed output streams to prevent annoying log spam.
 */
public final class ForgeToolExecutor {
	public static boolean shouldShowVerboseStdout(Project project) {
		// if running with INFO or DEBUG logging
		return project.getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) < 0;
	}

	public static boolean shouldShowVerboseStderr(Project project) {
		// if stdout is shown or stacktraces are visible so that errors printed to stderr show up
		return shouldShowVerboseStdout(project) || project.getGradle().getStartParameter().getShowStacktrace() != ShowStacktrace.INTERNAL_EXCEPTIONS;
	}

	/**
	 * Executes a {@link Project#javaexec(Action) javaexec} action with suppressed output.
	 *
	 * @param project      the project
	 * @param configurator the {@code javaexec} configuration action
	 * @return the execution result
	 */
	public static ExecResult exec(Project project, Action<? super JavaExecSpec> configurator) {
		return project.javaexec(spec -> {
			configurator.execute(spec);

			if (shouldShowVerboseStdout(project)) {
				spec.setStandardOutput(System.out);
			} else {
				spec.setStandardOutput(NullOutputStream.NULL_OUTPUT_STREAM);
			}

			if (shouldShowVerboseStderr(project)) {
				spec.setErrorOutput(System.err);
			} else {
				spec.setErrorOutput(NullOutputStream.NULL_OUTPUT_STREAM);
			}
		});
	}
}
