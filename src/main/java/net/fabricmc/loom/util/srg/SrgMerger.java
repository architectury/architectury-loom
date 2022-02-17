/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2021 FabricMC
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
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import org.apache.commons.io.IOUtils;
import org.gradle.api.logging.Logger;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.util.function.CollectionUtil;
import net.fabricmc.mappingio.FlatMappingVisitor;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.RegularAsFlatMappingVisitor;
import net.fabricmc.mappingio.format.Tiny2Writer;
import net.fabricmc.mappingio.format.TsrgReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * Utilities for merging SRG mappings.
 *
 * @author Juuz
 */
public final class SrgMerger {
	private final Logger logger;
	private final Map<String, List<MappingTreeView.MemberMappingView>> addRegardlessSrg = new HashMap<>();
	private final MemoryMappingTree srg;
	private final MemoryMappingTree src;
	private final MemoryMappingTree output;
	private final FlatMappingVisitor flatOutput;
	private final List<Runnable> postProcesses = new ArrayList<>();
	private final boolean lenient;
	private final boolean legacy;
	private final Set<String> methodSrgNames = new HashSet<>();

	public SrgMerger(Logger logger, Path srg, @Nullable Supplier<Path> mojmap, Path tiny, boolean lenient, boolean legacy) throws IOException {
		this.logger = logger;
		this.srg = readSrg(srg, mojmap);
		this.src = new MemoryMappingTree();
		this.output = new MemoryMappingTree();
		this.flatOutput = new RegularAsFlatMappingVisitor(output);
		this.lenient = lenient;
		this.legacy = legacy;

		MappingReader.read(tiny, this.src);

		if (!"official".equals(this.src.getSrcNamespace())) {
			throw new MappingException("Mapping file " + tiny + " does not have the 'official' namespace as the default!");
		}

		this.output.visitNamespaces(this.src.getSrcNamespace(), Stream.concat(Stream.of("srg"), this.src.getDstNamespaces().stream()).collect(Collectors.toList()));
	}

	public MemoryMappingTree merge() throws IOException {
		for (MappingTree.ClassMapping klass : this.srg.getClasses()) {
			classToTiny(klass);
		}

		try {
			for (Runnable process : postProcesses) {
				process.run();
			}
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}

		return output;
	}

	/**
	 * Merges SRG mappings with a tiny mappings tree through the obf names.
	 *
	 * @param srg     the SRG file in .tsrg format
	 * @param mojmap  the path to the mojmap file used for generating mojmap+srg names, may be null
	 * @param tiny    the tiny file
	 * @param out     the output file, will be in tiny v2
	 * @param lenient whether to ignore missing tiny mapping
	 * @param legacy  treat any method as mapped, even when it is lacking the 'func_' prefix
	 * @throws IOException      if an IO error occurs while reading or writing the mappings
	 * @throws MappingException if the input tiny tree's default namespace is not 'official'
	 *                          or if an element mentioned in the SRG file does not have tiny mappings
	 */
	public static void mergeSrg(Logger logger, @Nullable Supplier<Path> mojmap, Path srg, Path tiny, Path out, boolean lenient, boolean legacy)
			throws IOException, MappingException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		MemoryMappingTree tree = mergeSrg(logger, mojmap, srg, tiny, lenient, legacy);

		try (Tiny2Writer writer = new Tiny2Writer(Files.newBufferedWriter(out), false)) {
			tree.accept(writer);
		} catch (IOException e) {
			e.printStackTrace();
		}

		logger.info(":merged srg mappings in " + stopwatch.stop());
	}

	/**
	 * Merges SRG mappings with a tiny mappings tree through the obf names.
	 *
	 * @param srg     the SRG file in .tsrg format
	 * @param mojmap  the path to the mojmap file used for generating mojmap+srg names, may be null
	 * @param tiny    the tiny file
	 * @param lenient whether to ignore missing tiny mapping
	 * @param legacy  treat any method as mapped, even when it is lacking the 'func_' prefix
	 * @return the created mapping tree
	 * @throws IOException      if an IO error occurs while reading or writing the mappings
	 * @throws MappingException if the input tiny tree's default namespace is not 'official'
	 *                          or if an element mentioned in the SRG file does not have tiny mappings
	 */
	public static MemoryMappingTree mergeSrg(Logger logger, @Nullable Supplier<Path> mojmap, Path srg, Path tiny, boolean lenient, boolean legacy)
			throws IOException, MappingException {
		return new SrgMerger(logger, srg, mojmap, tiny, lenient, legacy).merge();
	}

	private MemoryMappingTree readSrg(Path srg, @Nullable Supplier<Path> mojmap) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(srg)) {
			String content = IOUtils.toString(reader);

			if (content.startsWith("tsrg2") && mojmap != null) {
				addRegardlessSrgs(mojmap);
			}

			MemoryMappingTree tsrg = new MemoryMappingTree();
			TsrgReader.read(new StringReader(content), tsrg);
			return tsrg;
		}
	}

	private void addRegardlessSrgs(Supplier<Path> mojmap) throws IOException {
		MemoryMappingTree mojmapTree = readTsrg2ToTinyTree(mojmap.get());

		for (MappingTree.ClassMapping classDef : mojmapTree.getClasses()) {
			for (MappingTree.MethodMapping methodDef : classDef.getMethods()) {
				String name = methodDef.getSrcName();

				if (name.indexOf('<') != 0 && name.equals(methodDef.getDstName(0))) {
					addRegardlessSrg.computeIfAbsent(classDef.getSrcName(), $ -> new ArrayList<>()).add(methodDef);
				}
			}

			for (MappingTree.FieldMapping fieldDef : classDef.getFields()) {
				if (fieldDef.getSrcName().equals(fieldDef.getDstName(0))) {
					addRegardlessSrg.computeIfAbsent(classDef.getSrcName(), $ -> new ArrayList<>()).add(fieldDef);
				}
			}
		}
	}

	private static MemoryMappingTree readTsrg2ToTinyTree(Path path) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			MappingReader.read(reader, tree);
		}

		return tree;
	}

	private void classToTiny(MappingTree.ClassMapping klass) throws IOException {
		String obf = klass.getSrcName();
		String srg = klass.getDstName(0);
		MappingTree.ClassMapping classDef = this.src.getClass(obf);

		if (classDef == null) {
			if (lenient) {
				return;
			} else {
				throw new MappingException("Missing class: " + obf + " (srg: " + srg + ")");
			}
		}

		List<String> classNames = CollectionUtil.map(
				output.getDstNamespaces(),
				namespace -> "srg".equals(namespace) ? srg : classDef.getName(namespace)
		);

		flatOutput.visitClass(obf, classNames.toArray(new String[0]));

		if (classDef.getComment() != null) {
			flatOutput.visitClassComment(obf, classDef.getComment());
		}

		for (MappingTree.MethodMapping method : klass.getMethods()) {
			MappingTree.MethodMapping def = CollectionUtil.find(
					classDef.getMethods(),
					m -> m.getName("official").equals(method.getSrcName()) && m.getDesc("official").equals(method.getSrcDesc())
			).orElse(null);

			String methodSrgName = method.getDstName(0);

			if (def == null) {
				if (lenient) {
					// TODO Big Hack!
					// We are checking if there are methods with the same srg name that are already added, if it is, then we would not fill in these names
					// This is especially troublesome with methods annotated with @DontObfuscate (e.g. m_129629_)
					// with environments like yarn where methods with the same srg name may not inherit the same names due to parameter mappings and inheritance
					// This requires further testing!
					postProcesses.add(() -> {
						if (!methodSrgNames.contains(methodSrgName)) {
							List<String> methodNames = CollectionUtil.map(
									output.getDstNamespaces(),
									namespace -> "srg".equals(namespace) ? methodSrgName : method.getSrcName()
							);

							try {
								flatOutput.visitMethod(obf, method.getSrcName(), method.getSrcDesc(), methodNames.toArray(new String[0]));
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						}
					});
				} else {
					throw new MappingException("Missing method: " + method.getSrcName() + " (srg: " + methodSrgName + ")");
				}

				continue;
			}

			methodToTiny(obf, method, methodSrgName, def);

			if (methodSrgName.startsWith("func_") || methodSrgName.startsWith("m_") || legacy) {
				methodSrgNames.add(methodSrgName);
			}
		}

		postProcesses.add(() -> {
			// TODO: This second iteration seems a bit wasteful.
			//  Is it possible to just iterate this and leave SRG out?
			for (MappingTree.MethodMapping def : classDef.getMethods()) {
				// If obf = some other name: some special name that srg might not contain.
				// This includes constructors and overridden JDK methods.
				if (!def.getSrcName().equals(def.getDstName(0))) {
					continue;
				}

				MappingTree.MethodMapping method = CollectionUtil.find(
						klass.getMethods(),
						m -> m.getSrcName().equals(def.getName("official")) && m.getSrcDesc().equals(def.getDesc("official"))
				).orElse(null);

				if (method == null) {
					try {
						methodToTiny(obf, null, def.getSrcName(), def);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}
			}
		});

		for (MappingTree.FieldMapping field : klass.getFields()) {
			MappingTree.FieldMapping def = CollectionUtil.find(
					classDef.getFields(),
					f -> f.getName("official").equals(field.getSrcName())
			).orElse(nullOrThrow(() -> new MappingException("Missing field: " + field.getSrcName() + " (srg: " + field.getDstName(0) + ")")));

			if (def == null) {
				if (tryMatchRegardlessSrgsField(obf, field)) {
					List<String> fieldNames = CollectionUtil.map(
							output.getDstNamespaces(),
							namespace -> "srg".equals(namespace) ? field.getDstName(0) : field.getSrcName()
					);

					flatOutput.visitField(obf, field.getSrcName(), field.getSrcDesc(), fieldNames.toArray(new String[0]));
				}

				continue;
			}

			List<String> fieldNames = CollectionUtil.map(
					output.getDstNamespaces(),
					namespace -> "srg".equals(namespace) ? field.getDstName(0) : def.getName(namespace)
			);

			flatOutput.visitField(obf, def.getName("official"), def.getDesc("official"), fieldNames.toArray(new String[0]));

			if (def.getComment() != null) {
				flatOutput.visitFieldComment(obf, def.getName("official"), def.getDesc("official"), def.getComment());
			}
		}
	}

	private void methodToTiny(String obfClassName, @Nullable MappingTree.MethodMapping srgMethod, @Nullable String srgMethodName, MappingTree.MethodMapping actualMethod)
			throws IOException {
		if (srgMethod != null && srgMethodName != null) {
			srgMethodName = srgMethod.getDstName(0);
		}

		String finalSrgMethodName = srgMethodName;
		List<String> methodNames = CollectionUtil.map(
				output.getDstNamespaces(),
				namespace -> "srg".equals(namespace) ? finalSrgMethodName : actualMethod.getName(namespace)
		);

		flatOutput.visitMethod(obfClassName, actualMethod.getName("official"), actualMethod.getDesc("official"), methodNames.toArray(new String[0]));

		if (actualMethod.getComment() != null) {
			flatOutput.visitMethodComment(obfClassName, actualMethod.getName("official"), actualMethod.getDesc("official"), actualMethod.getComment());
		}

		for (MappingTree.MethodArgMapping arg : actualMethod.getArgs()) {
			MappingTree.MethodArgMapping srgArg = srgMethod != null ? srgMethod.getArg(arg.getArgPosition(), arg.getLvIndex(), arg.getName("official")) : null;
			String srgName = srgArg != null ? srgArg.getDstName(0) : null;
			List<String> argNames = CollectionUtil.map(
					output.getDstNamespaces(),
					namespace -> "srg".equals(namespace) ? srgName : arg.getName(namespace)
			);

			flatOutput.visitMethodArg(obfClassName, actualMethod.getName("official"), actualMethod.getDesc("official"), arg.getArgPosition(), arg.getLvIndex(), arg.getName("official"), argNames.toArray(new String[0]));

			if (arg.getComment() != null) {
				flatOutput.visitMethodArgComment(obfClassName, actualMethod.getName("official"), actualMethod.getDesc("official"), arg.getArgPosition(), arg.getLvIndex(), arg.getName("official"), arg.getComment());
			}
		}

		for (MappingTree.MethodVarMapping var : actualMethod.getVars()) {
			MappingTree.MethodVarMapping srgVar = srgMethod != null ? srgMethod.getVar(var.getLvtRowIndex(), var.getLvIndex(), var.getStartOpIdx(), var.getName("official")) : null;
			String srgName = srgVar != null ? srgVar.getDstName(0) : null;
			List<String> varNames = CollectionUtil.map(
					output.getDstNamespaces(),
					namespace -> "srg".equals(namespace) ? srgName : var.getName(namespace)
			);

			flatOutput.visitMethodVar(obfClassName, actualMethod.getName("official"), actualMethod.getDesc("official"), var.getLvtRowIndex(), var.getLvIndex(), var.getStartOpIdx(), var.getName("official"), varNames.toArray(new String[0]));

			if (var.getComment() != null) {
				flatOutput.visitMethodVarComment(obfClassName, actualMethod.getName("official"), actualMethod.getDesc("official"), var.getLvtRowIndex(), var.getLvIndex(), var.getStartOpIdx(), var.getName("official"), var.getComment());
			}
		}
	}

	private boolean tryMatchRegardlessSrgsMethod(String obf, MappingTree.MethodMapping method) {
		List<MappingTreeView.MemberMappingView> mutableDescriptoredList = addRegardlessSrg.get(obf);

		if (!method.getDstName(0).equals(method.getSrcName())) {
			for (MappingTreeView.MemberMappingView descriptored : MoreObjects.firstNonNull(mutableDescriptoredList, Collections.<MappingTreeView.MemberMappingView>emptyList())) {
				if (descriptored instanceof MappingTree.MethodMapping && descriptored.getSrcName().equals(method.getSrcName()) && descriptored.getSrcDesc().equals(method.getSrcDesc())) {
					return true;
				}
			}
		}

		return false;
	}

	private boolean tryMatchRegardlessSrgsField(String obf, MappingTree.FieldMapping field) {
		List<MappingTreeView.MemberMappingView> mutableDescriptoredList = addRegardlessSrg.get(obf);

		if (!field.getDstName(0).equals(field.getSrcName())) {
			for (MappingTreeView.MemberMappingView descriptored : MoreObjects.firstNonNull(mutableDescriptoredList, Collections.<MappingTreeView.MemberMappingView>emptyList())) {
				if (descriptored instanceof MappingTree.FieldMapping && descriptored.getSrcName().equals(field.getSrcName())) {
					return true;
				}
			}
		}

		return false;
	}

	@Nullable
	private <T, X extends Exception> T nullOrThrow(Supplier<X> exception) throws X {
		if (lenient) {
			return null;
		} else {
			throw exception.get();
		}
	}
}
