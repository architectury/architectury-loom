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

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;

import dev.architectury.mappingslayers.api.mutable.MutableTinyMetadata;
import dev.architectury.mappingslayers.api.mutable.MutableTinyTree;
import dev.architectury.mappingslayers.api.utils.MappingsUtils;
import dev.architectury.tinyremapper.IMappingProvider;
import dev.architectury.tinyremapper.TinyRemapper;

public class MappingsProviderVerbose {
	public static void saveFile(TinyRemapper providers) throws IOException {
		try {
			Field field = TinyRemapper.class.getDeclaredField("mappingProviders");
			field.setAccessible(true);
			Set<IMappingProvider> mappingProviders = (Set<IMappingProvider>) field.get(providers);
			saveFile(mappingProviders);
		} catch (IllegalAccessException | NoSuchFieldException e) {
			e.printStackTrace();
		}
	}

	public static void saveFile(Iterable<IMappingProvider> providers) throws IOException {
		MutableTinyTree tree = MappingsUtils.create(MutableTinyMetadata.create(2, 0, Arrays.asList("from", "to"), new HashMap<>()));

		for (IMappingProvider provider : providers) {
			provider.load(new IMappingProvider.MappingAcceptor() {
				@Override
				public void acceptClass(String from, String to) {
					tree.getOrCreateClass(from).setName(1, to);
				}

				@Override
				public void acceptMethod(IMappingProvider.Member from, String to) {
					tree.getOrCreateClass(from.owner).getOrCreateMethod(from.name, from.desc)
							.setName(1, to);
				}

				@Override
				public void acceptMethodArg(IMappingProvider.Member from, int lvIndex, String to) {
					tree.getOrCreateClass(from.owner).getOrCreateMethod(from.name, from.desc)
							.getOrCreateParameter(lvIndex, "")
							.setName(1, to);
				}

				@Override
				public void acceptMethodVar(IMappingProvider.Member from, int i, int i1, int i2, String s) {
					// NO-OP
				}

				@Override
				public void acceptField(IMappingProvider.Member from, String to) {
					tree.getOrCreateClass(from.owner).getOrCreateField(from.name, from.desc)
							.setName(1, to);
				}
			});
		}

		Path check = Files.createTempFile("CHECK", null);
		Files.writeString(check, MappingsUtils.serializeToString(tree), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		System.out.println("Saved debug check mappings to " + check);
	}
}
