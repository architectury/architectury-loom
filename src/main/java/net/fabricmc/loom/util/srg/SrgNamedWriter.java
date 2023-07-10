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

package net.fabricmc.loom.util.srg;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.srg.SrgWriter;
import org.gradle.api.logging.Logger;

import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mappingio.tree.MappingTree;

public class SrgNamedWriter {
	public static void writeTo(Logger logger, Path srgFile, MappingTree mappings, String from, String to, boolean includeIdentityMappings) throws IOException {
		Files.deleteIfExists(srgFile);

		try (SrgWriter writer = newSrgWriter(Files.newBufferedWriter(srgFile), includeIdentityMappings)) {
			try (TinyMappingsReader reader = new TinyMappingsReader(mappings, from, to)) {
				writer.write(reader.read());
			}
		}
	}

	private static SrgWriter newSrgWriter(Writer writer, boolean includeIdentityMappings) {
		return includeIdentityMappings ? new SrgWithIdentitiesWriter(writer) : new SrgWriter(writer);
	}

	/**
	 * Legacy Forge's FMLDeobfuscatingRemapper requires class mappings, even if they are identity maps, but such
	 * mappings are filtered out by the SrgWriter. To get around that, this SrgWriter manually emits identity mappings
	 * before emitting all regular mappings.
	 */
	private static class SrgWithIdentitiesWriter extends SrgWriter {
		private SrgWithIdentitiesWriter(Writer writer) {
			super(writer);
		}

		@Override
		public void write(MappingSet mappings) {
			mappings.getTopLevelClassMappings().stream()
					.filter(cls -> !cls.hasDeobfuscatedName())
					.sorted(getConfig().getClassMappingComparator())
					.forEach(cls -> writer.format("CL: %s %s%n", cls.getFullObfuscatedName(), cls.getFullDeobfuscatedName()));

			super.write(mappings);
		}
	}
}
