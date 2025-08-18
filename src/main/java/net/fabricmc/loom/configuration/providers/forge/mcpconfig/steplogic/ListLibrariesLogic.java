/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2025 FabricMC
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

package net.fabricmc.loom.configuration.providers.forge.mcpconfig.steplogic;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFiles;

import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/**
 * Lists the Minecraft libraries into the output file.
 */
public final class ListLibrariesLogic extends StepLogic<ListLibrariesLogic.Options> {
	public static final ServiceType<Options, ListLibrariesLogic> TYPE = new ServiceType<>(Options.class, ListLibrariesLogic.class);

	public interface Options extends Service.Options {
		@InputFiles
		ConfigurableFileCollection getMinecraftLibraries();
	}

	public static Provider<Options> createOptions(SetupContext context) {
		return TYPE.create(context.project(), options -> {
			options.getMinecraftLibraries().from(context.getMinecraftLibraries());
		});
	}

	public ListLibrariesLogic(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	@Override
	public void execute(ExecutionContext context) throws IOException {
		try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(context.setOutput("libraries.txt")))) {
			for (File lib : getOptions().getMinecraftLibraries()) {
				writer.println("-e=" + lib.getAbsolutePath());
			}
		}
	}
}
