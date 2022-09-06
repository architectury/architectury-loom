/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2022 FabricMC
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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.MappingException;
import net.fabricmc.loom.util.function.CollectionUtil;
import net.fabricmc.mappingio.FlatMappingVisitor;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.adapter.RegularAsFlatMappingVisitor;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.Tiny2Writer;
import net.fabricmc.mappingio.format.TsrgReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * Merges a Tiny file with an SRG file.
 *
 * @author Juuz
 */
public final class SrgMerger {
	private static final List<String> INPUT_NAMESPACES = List.of("official", "intermediary", "named");
	private final MemoryMappingTree srg;
	private final MemoryMappingTree src;
	private final MemoryMappingTree output;
	private final FlatMappingVisitor flatOutput;
	private final boolean lenient;
	private final @Nullable MemoryMappingTree extra;
	private final ListMultimap<SrgMethodKey, MethodData> methodsBySrgName;

	private SrgMerger(Path srg, Path tiny, @Nullable ExtraMappings extraMappings, boolean lenient) throws IOException {
		this.srg = readSrg(srg);
		this.src = new MemoryMappingTree();
		this.output = new MemoryMappingTree();
		this.flatOutput = new RegularAsFlatMappingVisitor(output);
		this.lenient = lenient;
		this.methodsBySrgName = ArrayListMultimap.create();

		if (extraMappings != null) {
			this.extra = new MemoryMappingTree();
			MappingSourceNsSwitch nsSwitch = new MappingSourceNsSwitch(extra, "official");
			MappingVisitor visitor = nsSwitch;

			if (!extraMappings.hasCorrectNamespaces()) {
				Map<String, String> namespaces = Map.of(
						extraMappings.obfuscatedNamespace(), MappingsNamespace.OFFICIAL.toString(),
						extraMappings.deobfuscatedNamespace(), MappingsNamespace.NAMED.toString()
				);
				visitor = new MappingNsRenamer(visitor, namespaces);
			}

			MappingReader.read(extraMappings.path(), extraMappings.format(), visitor);
		} else {
			this.extra = null;
		}

		MappingReader.read(tiny, this.src);
		checkInputNamespaces(tiny);

		this.output.visitNamespaces(this.src.getSrcNamespace(), Stream.concat(Stream.of("srg"), this.src.getDstNamespaces().stream()).collect(Collectors.toList()));
	}

	private void checkInputNamespaces(Path tiny) {
		List<String> inputNamespaces = new ArrayList<>(this.src.getDstNamespaces());
		inputNamespaces.add(0, this.src.getSrcNamespace());

		if (!inputNamespaces.equals(INPUT_NAMESPACES)) {
			throw new MappingException("Mapping file " + tiny + " does not have 'official, intermediary, named' as its namespaces! Found: " + inputNamespaces);
		}
	}

	/**
	 * Creates a destination name array where the first element
	 * will be the srg name of the mapping element.
	 */
	private String[] createDstNameArray(MappingTree.ElementMappingView srg) {
		String[] dstNames = new String[output.getDstNamespaces().size()];
		dstNames[0] = srg.getDstName(0);
		return dstNames;
	}

	/**
	 * Copies all the non-srg destination names from an element.
	 */
	private void copyDstNames(String[] dstNames, MappingTreeView.ElementMappingView from) {
		for (int i = 1; i < dstNames.length; i++) {
			dstNames[i] = from.getDstName(i - 1);
		}
	}

	/**
	 * Fills in an array of destination names with the element's source name.
	 */
	private void fillMappings(String[] names, MappingTree.ElementMappingView srg) {
		for (int i = 1; i < names.length; i++) {
			names[i] = srg.getSrcName();
		}
	}

	public MemoryMappingTree merge() throws IOException {
		for (MappingTree.ClassMapping srgClass : srg.getClasses()) {
			String[] dstNames = createDstNameArray(srgClass);
			MappingTree.ClassMapping tinyClass = src.getClass(srgClass.getSrcName());
			String comment = null;

			if (tinyClass != null) {
				copyDstNames(dstNames, tinyClass);
				comment = tinyClass.getComment();
			} else if (lenient) {
				// Tiny class not found, we'll just use srg names
				fillMappings(dstNames, srgClass);
			} else {
				throw new MappingException("Could not find class " + srgClass.getSrcName() + "|" + srgClass.getDstName(0));
			}

			flatOutput.visitClass(srgClass.getSrcName(), dstNames);
			if (comment != null) flatOutput.visitClassComment(srgClass.getSrcName(), comment);

			for (MappingTree.FieldMapping field : srgClass.getFields()) {
				mergeField(srgClass, field, tinyClass);
			}

			for (MappingTree.MethodMapping method : srgClass.getMethods()) {
				mergeMethod(srgClass, method, tinyClass);
			}
		}

		resolveConflicts();

		return output;
	}

	private void mergeField(MappingTree.ClassMapping srgClass, MappingTree.FieldMapping srgField, @Nullable MappingTree.ClassMapping tinyClass) throws IOException {
		String[] dstNames = createDstNameArray(srgField);
		MappingTree.FieldMapping tinyField = null;
		String srcDesc = srgField.getSrcDesc();
		String comment = null;

		if (tinyClass != null) {
			if (srcDesc != null) {
				tinyField = tinyClass.getField(srgField.getSrcName(), srgField.getSrcDesc());
			} else {
				tinyField = CollectionUtil.find(tinyClass.getFields(), field -> field.getSrcName().equals(srgField.getSrcName())).orElse(null);
			}
		} else if (!lenient) {
			throw new MappingException("Could not find field " + srgClass.getDstName(0) + '.' + srgField.getDstName(0) + ' ' + srgField.getDstDesc(0));
		}

		if (tinyField != null) {
			copyDstNames(dstNames, tinyField);
			srcDesc = tinyField.getSrcDesc();
			comment = tinyField.getComment();
		} else {
			fillMappings(dstNames, srgField);
		}

		if (srcDesc != null) {
			flatOutput.visitField(srgClass.getSrcName(), srgField.getSrcName(), srcDesc, dstNames);
			if (comment != null) flatOutput.visitFieldComment(srgClass.getSrcName(), srgField.getSrcName(), srcDesc, comment);
		} else if (!lenient) {
			throw new MappingException("Could not find descriptor for field " + srgClass.getDstName(0) + '.' + srgField.getDstName(0));
		}
	}

	private void mergeMethod(MappingTree.ClassMapping srgClass, MappingTree.MethodMapping srgMethod, @Nullable MappingTree.ClassMapping tinyClass) throws IOException {
		String[] dstNames = createDstNameArray(srgMethod);
		MappingTree.MethodMapping tinyMethod = null;
		String intermediaryName, namedName;
		String comment = null;

		if (tinyClass != null) {
			tinyMethod = tinyClass.getMethod(srgMethod.getSrcName(), srgMethod.getSrcDesc());
		} else if (!lenient) {
			throw new MappingException("Could not find method " + srgClass.getDstName(0) + '.' + srgMethod.getDstName(0) + ' ' + srgMethod.getDstDesc(0));
		}

		if (tinyMethod != null) {
			copyDstNames(dstNames, tinyMethod);
			intermediaryName = tinyMethod.getName("intermediary");
			namedName = tinyMethod.getName("named");
			comment = tinyMethod.getComment();
		} else {
			if (srgMethod.getSrcName().equals(srgMethod.getDstName(0))) {
				// These are only methods like <init> or toString which have the same name in every NS.
				// We can safely ignore those.
				return;
			}

			@Nullable MappingTree.MethodMapping fillMethod = null;

			if (extra != null) {
				MappingTree.MethodMapping extraMethod = extra.getMethod(srgClass.getSrcName(), srgMethod.getSrcName(), srgMethod.getSrcDesc());

				if (extraMethod != null && extraMethod.getSrcName().equals(extraMethod.getDstName(0))) {
					fillMethod = extraMethod;
				}
			}

			if (fillMethod != null) {
				fillMappings(dstNames, fillMethod);
				intermediaryName = namedName = fillMethod.getSrcName();
			} else {
				// Do not allow missing methods as these are typically subclass methods and cause issues where
				// class B extends A, and overrides a method from the superclass. Then the subclass method
				// DOES get a srg name but not a yarn/intermediary name.
				return;
			}
		}

		if (!srgMethod.getSrcName().equals(dstNames[0])) { // ignore <init> and the likes
			methodsBySrgName.put(
					new SrgMethodKey(dstNames[0], srgMethod.getSrcDesc()),
					new MethodData(srgClass.getSrcName(), srgMethod.getSrcName(), srgMethod.getSrcDesc(), tinyMethod != null, intermediaryName, namedName)
			);
		}

		flatOutput.visitMethod(srgClass.getSrcName(), srgMethod.getSrcName(), srgMethod.getSrcDesc(), dstNames);
		if (comment != null) flatOutput.visitMethodComment(srgClass.getSrcName(), srgMethod.getSrcName(), srgMethod.getSrcDesc(), comment);

		if (tinyMethod != null) {
			for (MappingTree.MethodArgMapping arg : tinyMethod.getArgs()) {
				String[] argDstNames = new String[output.getDstNamespaces().size()];
				copyDstNames(argDstNames, arg);
				flatOutput.visitMethodArg(
						srgClass.getSrcName(), srgMethod.getSrcName(), srgMethod.getSrcDesc(),
						arg.getArgPosition(), arg.getLvIndex(), arg.getSrcName(), argDstNames
				);

				if (arg.getComment() != null) {
					flatOutput.visitMethodArgComment(
							srgClass.getSrcName(), srgMethod.getSrcName(), srgMethod.getSrcDesc(),
							arg.getArgPosition(), arg.getLvIndex(), arg.getSrcName(),
							arg.getComment()
					);
				}
			}
		}
	}

	/**
	 * Resolves conflicts where multiple methods map to an SRG method.
	 * We will prefer the ones with the Tiny mappings.
	 */
	private void resolveConflicts() {
		List<String> conflicts = new ArrayList<>();

		for (SrgMethodKey srg : methodsBySrgName.keySet()) {
			List<MethodData> methods = methodsBySrgName.get(srg);
			if (methods.size() == 1) continue;

			// Determine whether the names conflict
			Set<String> foundNamedNames = new HashSet<>();

			for (MethodData method : methods) {
				foundNamedNames.add(method.namedName());
			}

			if (foundNamedNames.size() == 1) {
				// No conflict, go on
				continue;
			}

			// Find preferred method
			@Nullable MethodData preferred = findPreferredMethod(methods, conflicts::add);
			if (preferred == null) continue;

			// Remove non-preferred methods
			for (MethodData method : methods) {
				if (method != preferred) {
					MappingTree.ClassMapping clazz = output.getClass(method.obfOwner());
					clazz.getMethods().removeIf(m -> m.getSrcName().equals(method.obfName()) && m.getSrcDesc().equals(method.obfDesc()));
				}
			}
		}

		if (!conflicts.isEmpty()) {
			throw new MappingException("Unfixable conflicts:\n" + String.join("\n", conflicts));
		}
	}

	private @Nullable MethodData findPreferredMethod(List<MethodData> methods, Consumer<String> conflictReporter) {
		List<MethodData> hasTiny = CollectionUtil.filter(methods, MethodData::hasTiny);

		// Record conflicts if needed
		if (hasTiny.size() > 1) { // Multiple methods map to this SRG name
			// Sometimes unrelated methods share an SRG name. Probably overrides from a past version?
			Set<String> intermediaryNames = new HashSet<>();

			for (MethodData method : methods) {
				intermediaryNames.add(method.intermediaryName());
			}

			// Only record a conflict if we map one intermediary name with multiple named names
			if (intermediaryNames.size() == 1) {
				StringBuilder message = new StringBuilder();
				message.append("- multiple preferred methods for ").append(srg).append(':');

				for (MethodData preferred : hasTiny) {
					message.append("\n\t> ").append(preferred);
				}

				conflictReporter.accept(message.toString());
			}

			return null;
		} else if (hasTiny.isEmpty()) { // No methods map to this SRG name
			conflictReporter.accept("- no preferred methods found for " + srg + ", available: " + methods);
			return null;
		}

		return hasTiny.get(0);
	}

	/**
	 * Merges SRG mappings with a tiny mappings tree through the obf names.
	 *
	 * <p>The namespaces in the tiny file should be {@code official, intermediary, named}.
	 * The SRG names will add a new namespace called {@code srg} so that the final namespaces
	 * are {@code official, srg, intermediary, named}.
	 *
	 * <p>This method does not include local variables in the output.
	 * It can, however, be used for remapping the game jar since it has all
	 * classes, methods, fields, parameters and javadoc comments.
	 *
	 * <p>If {@code lenient} is true, the merger will not error when encountering names not present
	 * in the tiny mappings. Instead, the names will be filled from the {@code official} namespace.
	 *
	 * @param srg           the SRG file in .tsrg format
	 * @param tiny          the tiny file
	 * @param out           the output file, will be in tiny v2
	 * @param extraMappings an extra mappings file that will be used to determine
	 *                      whether an unobfuscated name is needed in the result file
	 * @param lenient       whether lenient mode is enabled
	 * @throws IOException      if an IO error occurs while reading or writing the mappings
	 * @throws MappingException if the input tiny mappings' namespaces are incorrect
	 *                          or if an element mentioned in the SRG file does not have tiny mappings in non-lenient mode
	 */
	public static void mergeSrg(Path srg, Path tiny, Path out, @Nullable ExtraMappings extraMappings, boolean lenient)
			throws IOException, MappingException {
		MemoryMappingTree tree = new SrgMerger(srg, tiny, extraMappings, lenient).merge();

		try (Tiny2Writer writer = new Tiny2Writer(Files.newBufferedWriter(out), false)) {
			tree.accept(writer);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private MemoryMappingTree readSrg(Path srg) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(srg)) {
			MemoryMappingTree tsrg = new MemoryMappingTree();
			MemoryMappingTree temp = new MemoryMappingTree();
			TsrgReader.read(reader, temp);
			Map<String, String> namespaces = Map.of(
					temp.getSrcNamespace(), MappingsNamespace.OFFICIAL.toString(),
					temp.getDstNamespaces().get(0), MappingsNamespace.SRG.toString()
			);
			temp.accept(new MappingNsRenamer(tsrg, namespaces));
			return tsrg;
		}
	}

	public record ExtraMappings(Path path, MappingFormat format, String obfuscatedNamespace, String deobfuscatedNamespace) {
		boolean hasCorrectNamespaces() {
			return obfuscatedNamespace.equals(MappingsNamespace.OFFICIAL.toString()) && deobfuscatedNamespace.equals(MappingsNamespace.NAMED.toString());
		}

		public static ExtraMappings ofMojmapTsrg(Path path) {
			return new ExtraMappings(path, MappingFormat.TSRG2, MappingsNamespace.OFFICIAL.toString(), MappingsNamespace.NAMED.toString());
		}
	}

	private record SrgMethodKey(String name, String desc) {
		@Override
		public String toString() {
			return name + desc;
		}
	}

	private record MethodData(String obfOwner, String obfName, String obfDesc, boolean hasTiny, String intermediaryName, String namedName) {
		@Override
		public String toString() {
			return "%s.%s%s => %s/%s (%s)".formatted(obfOwner, obfName, obfDesc, intermediaryName, namedName, hasTiny ? "tiny" : "filled");
		}
	}
}
