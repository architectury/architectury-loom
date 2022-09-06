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

import java.io.IOException;
import java.nio.file.Path;

import codechicken.diffpatch.cli.CliOperation;
import codechicken.diffpatch.cli.PatchOperation;
import codechicken.diffpatch.util.LoggingOutputStream;
import codechicken.diffpatch.util.PatchMode;
import org.gradle.api.logging.LogLevel;

public final class PatchLogic implements StepLogic {
	@Override
	public void execute(ExecutionContext context) throws IOException {
		Path input = Path.of(context.resolve(new ConfigValue.Variable("input")));
		Path patches = Path.of(context.resolve(new ConfigValue.Variable("patches")));
		Path output = context.setOutput("output.jar");
		Path rejects = context.cache().resolve("rejects");

		CliOperation.Result<PatchOperation.PatchesSummary> result = PatchOperation.builder()
				.logTo(new LoggingOutputStream(context.logger(), LogLevel.INFO))
				.basePath(input)
				.patchesPath(patches)
				.outputPath(output)
				.mode(PatchMode.OFFSET)
				.rejectsPath(rejects)
				.build()
				.operate();

		if (result.exit != 0) {
			throw new RuntimeException("Could not patch " + input + "; rejects saved to " + rejects.toAbsolutePath());
		}
	}
}
