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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import dev.architectury.refmapremapper.utils.DescriptorRemapper;
import org.apache.commons.io.IOUtils;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgReader;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.FieldMapping;
import org.cadixdev.lorenz.model.InnerClassMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.cadixdev.lorenz.model.TopLevelClassMapping;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.stitch.commands.tinyv2.TinyClass;
import net.fabricmc.stitch.commands.tinyv2.TinyField;
import net.fabricmc.stitch.commands.tinyv2.TinyFile;
import net.fabricmc.stitch.commands.tinyv2.TinyMethod;
import net.fabricmc.stitch.commands.tinyv2.TinyMethodParameter;
import net.fabricmc.stitch.commands.tinyv2.TinyV2Reader;

public class MCPReader {
	private final Path intermediaryTinyPath;
	private final Path srgTsrgPath;

	public MCPReader(Path intermediaryTinyPath, Path srgTsrgPath) {
		this.intermediaryTinyPath = intermediaryTinyPath;
		this.srgTsrgPath = srgTsrgPath;
	}

	public TinyFile read(Path mcpJar) throws IOException {
		Map<MemberToken<?>, String> srgTokens = readSrg();
		TinyFile intermediaryTiny = TinyV2Reader.read(intermediaryTinyPath);
		Map<MemberToken<?>, String> intermediaryToMCPMap = createIntermediaryToMCPMap(intermediaryTiny, srgTokens);
		Map<MemberToken<?>, String[]> intermediaryToDocsMap = new HashMap<>();
		Map<MemberToken<?>, Map<Integer, String>> intermediaryToParamsMap = new HashMap<>();

		try {
			injectMcp(mcpJar, intermediaryToMCPMap, intermediaryToDocsMap, intermediaryToParamsMap);
		} catch (CsvValidationException e) {
			throw new RuntimeException(e);
		}

		mergeTokensIntoIntermediary(intermediaryTiny, intermediaryToMCPMap, intermediaryToDocsMap, intermediaryToParamsMap);
		return intermediaryTiny;
	}

	private Map<MemberToken<?>, String> createIntermediaryToMCPMap(TinyFile tiny, Map<MemberToken<?>, String> officialToMCP) {
		Map<MemberToken<?>, String> map = new HashMap<>();
		BiConsumer<MemberToken<?>, MemberToken<?>> adder = (intermediary, obf) -> {
			String mcp = officialToMCP.get(obf);

			if (mcp != null && !intermediary.name.equals(mcp)) {
				map.put(intermediary, mcp);
			}
		};

		for (TinyClass tinyClass : tiny.getClassEntries()) {
			String classObf = tinyClass.getMapping().get(0);
			String classIntermediary = tinyClass.getMapping().get(1);
			MemberToken<TokenType.Class> classTokenIntermediary = MemberToken.ofClass(classIntermediary);
			MemberToken<TokenType.Class> classTokenObf = MemberToken.ofClass(classObf);

			adder.accept(classTokenIntermediary, classTokenObf);

			for (TinyField tinyField : tinyClass.getFields()) {
				String fieldObf = tinyField.getMapping().get(0);
				String fieldIntermediary = tinyField.getMapping().get(1);
				MemberToken<TokenType.Field> fieldTokenObf = MemberToken.ofField(classTokenObf, fieldObf);

				adder.accept(MemberToken.ofField(classTokenIntermediary, fieldIntermediary), fieldTokenObf);
			}

			for (TinyMethod tinyMethod : tinyClass.getMethods()) {
				String methodObf = tinyMethod.getMapping().get(0);
				String methodIntermediary = tinyMethod.getMapping().get(1);
				String methodDescIntermediary = remapDescriptor(tinyMethod.getMethodDescriptorInFirstNamespace(), tiny);
				MemberToken<TokenType.Method> methodTokenObf = MemberToken.ofMethod(classTokenObf, methodObf, tinyMethod.getMethodDescriptorInFirstNamespace());

				adder.accept(MemberToken.ofMethod(classTokenIntermediary, methodIntermediary, methodDescIntermediary), methodTokenObf);
			}
		}

		return map;
	}

	private String remapDescriptor(String descriptor, TinyFile file) {
		return DescriptorRemapper.remapDescriptor(descriptor, s -> {
			TinyClass tinyClass = file.mapClassesByFirstNamespace().get(s);
			return tinyClass == null ? s : tinyClass.getMapping().get(1);
		});
	}

	private void mergeTokensIntoIntermediary(TinyFile tiny, Map<MemberToken<?>, String> intermediaryToMCPMap, Map<MemberToken<?>, String[]> intermediaryToDocsMap,
			Map<MemberToken<?>, Map<Integer, String>> intermediaryToParamsMap) {
		stripTinyWithParametersAndLocal(tiny);

		// We will be adding the "named" namespace with MCP
		tiny.getHeader().getNamespaces().add("named");

		for (TinyClass tinyClass : tiny.getClassEntries()) {
			String classIntermediary = tinyClass.getMapping().get(1);
			MemberToken<TokenType.Class> classMemberToken = MemberToken.ofClass(classIntermediary);
			tinyClass.getMapping().add(intermediaryToMCPMap.getOrDefault(classMemberToken, classIntermediary));

			for (TinyField tinyField : tinyClass.getFields()) {
				String fieldIntermediary = tinyField.getMapping().get(1);
				MemberToken<TokenType.Field> fieldMemberToken = MemberToken.ofField(classMemberToken, fieldIntermediary);
				String[] docs = intermediaryToDocsMap.get(fieldMemberToken);
				tinyField.getMapping().add(intermediaryToMCPMap.getOrDefault(fieldMemberToken, fieldIntermediary));

				if (docs != null) {
					tinyField.getComments().clear();
					tinyField.getComments().addAll(Arrays.asList(docs));
				}
			}

			for (TinyMethod tinyMethod : tinyClass.getMethods()) {
				String methodIntermediary = tinyMethod.getMapping().get(1);
				String methodDescIntermediary = remapDescriptor(tinyMethod.getMethodDescriptorInFirstNamespace(), tiny);
				MemberToken<TokenType.Method> methodMemberToken = MemberToken.ofMethod(classMemberToken, methodIntermediary, methodDescIntermediary);
				String[] docs = intermediaryToDocsMap.get(methodMemberToken);
				tinyMethod.getMapping().add(intermediaryToMCPMap.getOrDefault(methodMemberToken, methodIntermediary));

				if (docs != null) {
					tinyMethod.getComments().clear();
					tinyMethod.getComments().addAll(Arrays.asList(docs));
				}

				Map<Integer, String> params = intermediaryToParamsMap.get(methodMemberToken);

				if (params != null) {
					for (Map.Entry<Integer, String> entry : params.entrySet()) {
						int lvIndex = entry.getKey();
						String paramName = entry.getValue();

						ArrayList<String> mappings = new ArrayList<>();
						mappings.add("");
						mappings.add("");
						mappings.add(paramName);
						tinyMethod.getParameters().add(new TinyMethodParameter(lvIndex, mappings, new ArrayList<>()));
					}
				}
			}
		}
	}

	private void stripTinyWithParametersAndLocal(TinyFile tiny) {
		for (TinyClass tinyClass : tiny.getClassEntries()) {
			for (TinyMethod tinyMethod : tinyClass.getMethods()) {
				tinyMethod.getParameters().clear();
				tinyMethod.getLocalVariables().clear();
			}
		}
	}

	private Map<MemberToken<?>, String> readSrg() throws IOException {
		Map<MemberToken<?>, String> tokens = new HashMap<>();

		try (BufferedReader reader = Files.newBufferedReader(srgTsrgPath, StandardCharsets.UTF_8)) {
			String content = IOUtils.toString(reader);

			if (content.startsWith("tsrg2")) {
				readTsrg2(tokens, content);
			} else {
				MappingSet mappingSet = new TSrgReader(new StringReader(content)).read();

				for (TopLevelClassMapping classMapping : mappingSet.getTopLevelClassMappings()) {
					appendClass(tokens, classMapping);
				}
			}
		}

		return tokens;
	}

	private void readTsrg2(Map<MemberToken<?>, String> tokens, String content) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingReader.read(new StringReader(content), tree);
		int obfIndex = tree.getNamespaceId("obf");
		int srgIndex = tree.getNamespaceId("srg");

		for (MappingTree.ClassMapping classDef : tree.getClasses()) {
			MemberToken<TokenType.Class> ofClass = MemberToken.ofClass(classDef.getName(obfIndex));
			tokens.put(ofClass, classDef.getName(srgIndex));

			for (MappingTree.FieldMapping fieldDef : classDef.getFields()) {
				tokens.put(MemberToken.ofField(ofClass, fieldDef.getName(obfIndex)), fieldDef.getName(srgIndex));
			}

			for (MappingTree.MethodMapping methodDef : classDef.getMethods()) {
				tokens.put(MemberToken.ofMethod(ofClass, methodDef.getName(obfIndex), methodDef.getDesc(obfIndex)),
						methodDef.getName(srgIndex));
			}
		}
	}

	private void injectMcp(Path mcpJar, Map<MemberToken<?>, String> intermediaryToSrgMap, Map<MemberToken<?>, String[]> intermediaryToDocsMap,
			Map<MemberToken<?>, Map<Integer, String>> intermediaryToParamsMap)
			throws IOException, CsvValidationException {
		Map<String, List<MemberToken<?>>> srgToIntermediary = inverseMap(intermediaryToSrgMap);
		Map<String, List<MemberToken<TokenType.Method>>> simpleSrgToIntermediary = new HashMap<>();
		Pattern methodPattern = Pattern.compile("(func_\\d*)_.*");

		for (Map.Entry<String, List<MemberToken<?>>> entry : srgToIntermediary.entrySet()) {
			Matcher matcher = methodPattern.matcher(entry.getKey());

			if (matcher.matches()) {
				simpleSrgToIntermediary.put(matcher.group(1),
						(List<MemberToken<TokenType.Method>>) (List<? extends MemberToken<?>>) entry.getValue());
			}
		}

		try (FileSystem fs = FileSystems.newFileSystem(mcpJar, (ClassLoader) null)) {
			Path fields = fs.getPath("fields.csv");
			Path methods = fs.getPath("methods.csv");
			Path params = fs.getPath("params.csv");
			Pattern paramsPattern = Pattern.compile("p_[^\\d]*(\\d+)_(\\d)+_?");

			try (CSVReader reader = new CSVReader(Files.newBufferedReader(fields, StandardCharsets.UTF_8))) {
				reader.readNext();
				String[] line;

				while ((line = reader.readNext()) != null) {
					List<MemberToken<TokenType.Field>> intermediaryField = (List<MemberToken<TokenType.Field>>) (List<? extends MemberToken<?>>) srgToIntermediary.get(line[0]);
					String[] docs = line[3].split("\n");

					if (intermediaryField != null) {
						for (MemberToken<TokenType.Field> s : intermediaryField) {
							intermediaryToSrgMap.put(s, line[1]);

							if (!line[3].trim().isEmpty() && docs.length > 0) {
								intermediaryToDocsMap.put(s, docs);
							}
						}
					}
				}
			}

			try (CSVReader reader = new CSVReader(Files.newBufferedReader(methods, StandardCharsets.UTF_8))) {
				reader.readNext();
				String[] line;

				while ((line = reader.readNext()) != null) {
					List<MemberToken<TokenType.Method>> intermediaryMethod = (List<MemberToken<TokenType.Method>>) (List<? extends MemberToken<?>>) srgToIntermediary.get(line[0]);
					String[] docs = line[3].split("\n");

					if (intermediaryMethod != null) {
						for (MemberToken<TokenType.Method> s : intermediaryMethod) {
							intermediaryToSrgMap.put(s, line[1]);

							if (!line[3].trim().isEmpty() && docs.length > 0) {
								intermediaryToDocsMap.put(s, docs);
							}
						}
					}
				}
			}

			if (Files.exists(params)) {
				try (CSVReader reader = new CSVReader(Files.newBufferedReader(params, StandardCharsets.UTF_8))) {
					reader.readNext();
					String[] line;

					while ((line = reader.readNext()) != null) {
						Matcher param = paramsPattern.matcher(line[0]);

						if (param.matches()) {
							String named = line[1];
							String srgMethodStartWith = "func_" + param.group(1);
							int lvIndex = Integer.parseInt(param.group(2));
							List<MemberToken<TokenType.Method>> intermediaryMethod = simpleSrgToIntermediary.get(srgMethodStartWith);

							if (intermediaryMethod != null) {
								for (MemberToken<TokenType.Method> s : intermediaryMethod) {
									intermediaryToParamsMap.computeIfAbsent(s, s1 -> new HashMap<>()).put(lvIndex, named);
								}
							}
						}
					}
				}
			}
		}
	}

	private <T, A> Map<A, List<T>> inverseMap(Map<T, A> intermediaryToMCPMap) {
		Map<A, List<T>> map = new HashMap<>();

		for (Map.Entry<T, A> token : intermediaryToMCPMap.entrySet()) {
			map.computeIfAbsent(token.getValue(), s -> new ArrayList<>()).add(token.getKey());
		}

		return map;
	}

	private void appendClass(Map<MemberToken<?>, String> tokens, ClassMapping<?, ?> classMapping) {
		MemberToken<TokenType.Class> ofClass = MemberToken.ofClass(classMapping.getFullObfuscatedName());
		tokens.put(ofClass, classMapping.getFullDeobfuscatedName());

		for (FieldMapping fieldMapping : classMapping.getFieldMappings()) {
			tokens.put(MemberToken.ofField(ofClass, fieldMapping.getObfuscatedName()), fieldMapping.getDeobfuscatedName());
		}

		for (MethodMapping methodMapping : classMapping.getMethodMappings()) {
			tokens.put(MemberToken.ofMethod(ofClass, methodMapping.getObfuscatedName(), methodMapping.getObfuscatedDescriptor()), methodMapping.getDeobfuscatedName());
		}

		for (InnerClassMapping mapping : classMapping.getInnerClassMappings()) {
			appendClass(tokens, mapping);
		}
	}

	private interface TokenType {
		enum Class implements TokenType {
		}

		enum Method implements TokenType {
		}

		enum Field implements TokenType {
		}
	}

	private static class MemberToken<T extends TokenType> {
		@Nullable
		private MemberToken<TokenType.Class> owner;
		private String name;
		@Nullable private String descriptor;

		public MemberToken(@Nullable MemberToken<TokenType.Class> owner, String name, @Nullable String descriptor) {
			this.owner = owner;
			this.name = name;
			this.descriptor = descriptor;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof MemberToken<?> that)) return false;
			return Objects.equals(owner, that.owner) && Objects.equals(name, that.name) && Objects.equals(descriptor, that.descriptor);
		}

		@Override
		public int hashCode() {
			return Objects.hash(owner, name, descriptor);
		}

		static class ClassToken extends MemberToken<TokenType.Class> {
			ClassToken(String name) {
				super(null, name, null);
			}

			@Override
			public boolean equals(Object o) {
				if (!(o instanceof ClassToken)) return false;
				return super.equals(o);
			}

			@Override
			public int hashCode() {
				return (1 + super.hashCode()) * 31 + 1;
			}
		}

		static class FieldToken extends MemberToken<TokenType.Field> {
			FieldToken(@Nullable MemberToken<TokenType.Class> owner, String name) {
				super(owner, name, null);
			}

			@Override
			public boolean equals(Object o) {
				if (!(o instanceof FieldToken)) return false;
				return super.equals(o);
			}

			@Override
			public int hashCode() {
				return (1 + super.hashCode()) * 31 + 2;
			}
		}

		static class MethodToken extends MemberToken<TokenType.Method> {
			MethodToken(@Nullable MemberToken<TokenType.Class> owner, String name, @Nullable String descriptor) {
				super(owner, name, descriptor);
			}

			@Override
			public boolean equals(Object o) {
				if (!(o instanceof MethodToken)) return false;
				return super.equals(o);
			}

			@Override
			public int hashCode() {
				return (1 + super.hashCode()) * 31 + 3;
			}
		}

		static MemberToken<TokenType.Class> ofClass(String name) {
			return new MemberToken.ClassToken(name);
		}

		static MemberToken<TokenType.Field> ofField(MemberToken<TokenType.Class> owner, String name) {
			return new MemberToken.FieldToken(owner, name);
		}

		static MemberToken<TokenType.Method> ofMethod(MemberToken<TokenType.Class> owner, String name, String descriptor) {
			return new MemberToken.MethodToken(owner, name, descriptor);
		}
	}
}
