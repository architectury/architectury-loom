/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Minecrell (https://github.com/Minecrell)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.fabricmc.loom.util.srg;

import java.util.Objects;
import java.util.Optional;

import org.cadixdev.at.AccessTransformSet;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.Mapping;
import org.cadixdev.lorenz.model.MethodMapping;

// TODO from https://github.com/CadixDev/at/blob/c2b92fc26cf26e64d3ca3b35abf3364d4b95e6c3/src/main/java/org/cadixdev/at/impl/AccessTransformSetMapper.java
//      remove once https://github.com/CadixDev/at/issues/7 is fixed
public final class AccessTransformSetMapper {
	private AccessTransformSetMapper() {
	}

	public static AccessTransformSet remap(AccessTransformSet set, MappingSet mappings) {
		Objects.requireNonNull(set, "set");
		Objects.requireNonNull(mappings, "mappings");

		AccessTransformSet remapped = AccessTransformSet.create();
		set.getClasses().forEach((className, classSet) -> {
			Optional<? extends ClassMapping<?, ?>> mapping = mappings.getClassMapping(className);
			remap(mappings, mapping, classSet, remapped.getOrCreateClass(mapping.map(Mapping::getFullDeobfuscatedName).orElse(className)));
		});
		return remapped;
	}

	private static void remap(MappingSet mappings, Optional<? extends ClassMapping<?, ?>> mapping, AccessTransformSet.Class set, AccessTransformSet.Class remapped) {
		remapped.merge(set.get());
		remapped.mergeAllFields(set.allFields());
		remapped.mergeAllMethods(set.allMethods());

		set.getFields().forEach((name, transform) ->
				remapped.mergeField(mapping.flatMap(m -> m.getFieldMapping(name))
						.map(FieldMapping::getDeobfuscatedName).orElse(name), transform));

		set.getMethods().forEach((signature, transform) ->
				remapped.mergeMethod(mapping.flatMap(m -> m.getMethodMapping(signature))
						.map(MethodMapping::getDeobfuscatedSignature)
						.orElseGet(() -> new MethodSignature(signature.getName(), mappings.deobfuscate(signature.getDescriptor()))), transform));
	}
}
