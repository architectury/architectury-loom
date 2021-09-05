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
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.architectury.mappingslayers.api.mutable.MutableClassDef;
import dev.architectury.mappingslayers.api.mutable.MutableFieldDef;
import dev.architectury.mappingslayers.api.mutable.MutableMethodDef;
import dev.architectury.mappingslayers.api.mutable.MutableParameterDef;
import dev.architectury.mappingslayers.api.mutable.MutableTinyMetadata;
import dev.architectury.mappingslayers.api.mutable.MutableTinyTree;
import dev.architectury.mappingslayers.api.utils.MappingsUtils;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgWriter;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.MethodMapping;

import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.format.TsrgReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class Tsrg2Utils {
	public static void convert(Reader reader, Writer writer) throws IOException {
		writeTsrg(visitor -> {
			try {
				TsrgReader.read(reader, visitor);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, "srg", false, writer);
	}

	public static void writeTsrg(Consumer<MappingVisitor> visitorConsumer, String dstNamespace, boolean applyParameterMappings, Writer writer)
			throws IOException {
		MappingSet set;

		try (MappingsIO2LorenzWriter lorenzWriter = new MappingsIO2LorenzWriter(dstNamespace, applyParameterMappings)) {
			visitorConsumer.accept(lorenzWriter);
			set = lorenzWriter.read();
		}

		try (TSrgWriter w = new TSrgWriter(writer)) {
			w.write(set);
		}
	}

	public static void writeTsrg2(Consumer<MappingVisitor> visitorConsumer, Writer writer)
			throws IOException {
		MutableTinyTree tree;

		try (MappingsIO2MappingsUtils w = new MappingsIO2MappingsUtils()) {
			visitorConsumer.accept(w);
			tree = w.read();
		}

		writer.write(MappingsUtils.serializeToTsrg2(tree));
	}

	// TODO Move this elsewhere
	public abstract static class MappingsIO2Others extends ForwardingMappingVisitor implements MappingWriter {
		public MappingsIO2Others() {
			super(new MemoryMappingTree());
		}

		public MappingTree tree() {
			return (MappingTree) next;
		}

		@Override
		public void close() throws IOException {
			MappingTree tree = tree();
			List<String> names = new ArrayList<>();

			for (MappingTree.ClassMapping aClass : tree.getClasses()) {
				names.add(aClass.getSrcName());
			}

			for (String name : names) {
				tree.removeClass(name);
			}
		}
	}

	public static class MappingsIO2LorenzWriter extends MappingsIO2Others {
		private final Object dstNamespaceUnresolved;
		private int dstNamespace;
		private boolean applyParameterMappings;

		public MappingsIO2LorenzWriter(int dstNamespace, boolean applyParameterMappings) {
			this.dstNamespaceUnresolved = dstNamespace;
			this.applyParameterMappings = applyParameterMappings;
		}

		public MappingsIO2LorenzWriter(String dstNamespace, boolean applyParameterMappings) {
			this.dstNamespaceUnresolved = dstNamespace;
			this.applyParameterMappings = applyParameterMappings;
		}

		@Override
		public void visitNamespaces(String srcNamespace, List<String> dstNamespaces) throws IOException {
			super.visitNamespaces(srcNamespace, dstNamespaces);
			this.dstNamespace = dstNamespaceUnresolved instanceof Integer ? (Integer) dstNamespaceUnresolved : dstNamespaces.indexOf((String) dstNamespaceUnresolved);
		}

		public MappingSet read() throws IOException {
			return this.read(MappingSet.create());
		}

		public MappingSet read(final MappingSet mappings) throws IOException {
			MappingTree tree = tree();

			for (MappingTree.ClassMapping aClass : tree.getClasses()) {
				ClassMapping<?, ?> lClass = mappings.getOrCreateClassMapping(aClass.getSrcName())
						.setDeobfuscatedName(aClass.getDstName(dstNamespace));

				for (MappingTree.FieldMapping aField : aClass.getFields()) {
					String srcDesc = aField.getSrcDesc();

					if (srcDesc == null || srcDesc.isEmpty()) {
						lClass.getOrCreateFieldMapping(aField.getSrcName())
								.setDeobfuscatedName(aField.getDstName(dstNamespace));
					} else {
						lClass.getOrCreateFieldMapping(aField.getSrcName(), srcDesc)
								.setDeobfuscatedName(aField.getDstName(dstNamespace));
					}
				}

				for (MappingTree.MethodMapping aMethod : aClass.getMethods()) {
					MethodMapping lMethod = lClass.getOrCreateMethodMapping(aMethod.getSrcName(), aMethod.getSrcDesc())
							.setDeobfuscatedName(aMethod.getDstName(dstNamespace));

					if (applyParameterMappings) {
						for (MappingTree.MethodArgMapping aArg : aMethod.getArgs()) {
							lMethod.getOrCreateParameterMapping(aArg.getLvIndex())
									.setDeobfuscatedName(aArg.getDstName(dstNamespace));
						}
					}
				}
			}

			return mappings;
		}
	}

	public static class MappingsIO2MappingsUtils extends MappingsIO2Others {
		public MutableTinyTree read() {
			MappingTree tree = tree();
			int dstNamesSize = tree.getDstNamespaces().size();
			List<String> namespaces = new ArrayList<>();
			namespaces.add(tree.getSrcNamespace());
			namespaces.addAll(tree.getDstNamespaces());
			Map<String, String> properties = new HashMap<>();

			for (Map.Entry<String, String> entry : tree.getMetadata()) {
				properties.put(entry.getKey(), entry.getValue());
			}

			MutableTinyTree out = MappingsUtils.create(MutableTinyMetadata.create(2, 0, namespaces, properties));

			for (MappingTree.ClassMapping aClass : tree.getClasses()) {
				MutableClassDef classDef = out.getOrCreateClass(aClass.getSrcName());
				classDef.setComment(aClass.getComment());

				for (int i = 0; i < dstNamesSize; i++) {
					classDef.setName(i + 1, aClass.getDstName(i));
				}

				for (MappingTree.MethodMapping aMethod : aClass.getMethods()) {
					MutableMethodDef methodDef = classDef.getOrCreateMethod(aMethod.getSrcName(), aMethod.getSrcDesc());
					methodDef.setComment(aMethod.getComment());

					for (int i = 0; i < dstNamesSize; i++) {
						methodDef.setName(i + 1, aMethod.getDstName(i));
					}

					for (MappingTree.MethodArgMapping aMethodArg : aMethod.getArgs()) {
						MutableParameterDef parameterDef = methodDef.getOrCreateParameter(aMethodArg.getLvIndex(), aMethodArg.getSrcName());
						parameterDef.setComment(aMethodArg.getComment());

						for (int i = 0; i < dstNamesSize; i++) {
							parameterDef.setName(i + 1, aMethodArg.getDstName(i));
						}
					}
				}

				for (MappingTree.FieldMapping aField : aClass.getFields()) {
					MutableFieldDef fieldDef = classDef.getOrCreateField(aField.getSrcName(), aField.getSrcDesc());
					fieldDef.setComment(aField.getComment());

					for (int i = 0; i < dstNamesSize; i++) {
						fieldDef.setName(i + 1, aField.getDstName(i));
					}
				}
			}

			return out;
		}
	}
}
