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

package dev.architectury.loom.mappings;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.srg.TsrgFileReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class MCPReader {
	private final Path intermediaryTinyPath;
	private final Path srgTsrgPath;

	public MCPReader(Path intermediaryTinyPath, Path srgTsrgPath) {
		this.intermediaryTinyPath = intermediaryTinyPath;
		this.srgTsrgPath = srgTsrgPath;
	}

	public MappingTree read(Path mcpJar) throws IOException {
		Map<MemberToken, String> srgTokens = readSrg();
		MemoryMappingTree intermediaryTiny = new MemoryMappingTree();
		MappingReader.read(intermediaryTinyPath, MappingFormat.TINY_2_FILE, intermediaryTiny);
		Map<String, String> intermediaryToMCPMap = createIntermediaryToMCPMap(intermediaryTiny, srgTokens);
		Map<String, String> intermediaryToDocsMap = new HashMap<>();
		Map<String, Map<Integer, String>> intermediaryToParamsMap = new HashMap<>();

		try {
			injectMcp(mcpJar, intermediaryToMCPMap, intermediaryToDocsMap, intermediaryToParamsMap);
		} catch (CsvValidationException e) {
			throw new RuntimeException(e);
		}

		mergeTokensIntoIntermediary(intermediaryTiny, intermediaryToMCPMap, intermediaryToDocsMap, intermediaryToParamsMap);
		return intermediaryTiny;
	}

	private Map<String, String> createIntermediaryToMCPMap(MappingTree tiny, Map<MemberToken, String> officialToMCP) {
		Map<String, String> map = new HashMap<>();

		for (MappingTree.ClassMapping tinyClass : tiny.getClasses()) {
			String classObf = tinyClass.getSrcName();
			String classIntermediary = tinyClass.getDstName(0);
			MemberToken classTokenObf = MemberToken.ofClass(classObf);

			if (officialToMCP.containsKey(classTokenObf)) {
				map.put(classIntermediary, officialToMCP.get(classTokenObf));
			}

			for (MappingTree.FieldMapping tinyField : tinyClass.getFields()) {
				String fieldObf = tinyField.getSrcName();
				String fieldIntermediary = tinyField.getDstName(0);
				MemberToken fieldTokenObf = MemberToken.ofField(classTokenObf, fieldObf);

				if (officialToMCP.containsKey(fieldTokenObf)) {
					map.put(fieldIntermediary, officialToMCP.get(fieldTokenObf));
				}
			}

			for (MappingTree.MethodMapping tinyMethod : tinyClass.getMethods()) {
				String methodObf = tinyMethod.getSrcName();
				String methodIntermediary = tinyMethod.getDstName(0);
				MemberToken methodTokenObf = MemberToken.ofMethod(classTokenObf, methodObf, tinyMethod.getSrcDesc());

				if (officialToMCP.containsKey(methodTokenObf)) {
					map.put(methodIntermediary, officialToMCP.get(methodTokenObf));
				}
			}
		}

		return map;
	}

	private void mergeTokensIntoIntermediary(MappingTree tiny, Map<String, String> intermediaryToMCPMap, Map<String, String> intermediaryToDocsMap, Map<String, Map<Integer, String>> intermediaryToParamsMap) {
		// We will be adding the "named" namespace with MCP
		tiny.setDstNamespaces(List.of("intermediary", "named"));

		for (MappingTree.ClassMapping tinyClass : tiny.getClasses()) {
			String classIntermediary = tinyClass.getDstName(0);
			tinyClass.setDstName(intermediaryToMCPMap.getOrDefault(classIntermediary, classIntermediary), 1);

			for (MappingTree.FieldMapping tinyField : tinyClass.getFields()) {
				String fieldIntermediary = tinyField.getDstName(0);
				String docs = intermediaryToDocsMap.get(fieldIntermediary);
				tinyField.setDstName(intermediaryToMCPMap.getOrDefault(fieldIntermediary, fieldIntermediary), 1);

				if (docs != null) {
					tinyField.setComment(docs);
				}
			}

			for (MappingTree.MethodMapping tinyMethod : tinyClass.getMethods()) {
				String methodIntermediary = tinyMethod.getDstName(0);
				String docs = intermediaryToDocsMap.get(methodIntermediary);
				tinyMethod.setDstName(intermediaryToMCPMap.getOrDefault(methodIntermediary, methodIntermediary), 1);

				if (docs != null) {
					tinyMethod.setComment(docs);
				}

				Map<Integer, String> params = intermediaryToParamsMap.get(methodIntermediary);

				if (params != null) {
					for (Map.Entry<Integer, String> entry : params.entrySet()) {
						int lvIndex = entry.getKey();
						String paramName = entry.getValue();
						tinyMethod.addArg(new BasicMethodArg(tinyMethod, lvIndex, paramName));
					}
				}
			}
		}
	}

	private Map<MemberToken, String> readSrg() throws IOException {
		Map<MemberToken, String> tokens = new HashMap<>();
		MemoryMappingTree tree = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(srgTsrgPath, StandardCharsets.UTF_8)) {
			TsrgFileReader.read(reader, "obf", "srg", tree);
		}

		int obfIndex = tree.getNamespaceId("obf");
		int srgIndex = tree.getNamespaceId("srg");

		for (MappingTree.ClassMapping classDef : tree.getClasses()) {
			MemberToken ofClass = MemberToken.ofClass(classDef.getName(obfIndex));
			tokens.put(ofClass, classDef.getName(srgIndex));

			for (MappingTree.FieldMapping fieldDef : classDef.getFields()) {
				tokens.put(MemberToken.ofField(ofClass, fieldDef.getName(obfIndex)), fieldDef.getName(srgIndex));
			}

			for (MappingTree.MethodMapping methodDef : classDef.getMethods()) {
				tokens.put(MemberToken.ofMethod(ofClass, methodDef.getName(obfIndex), methodDef.getDesc(obfIndex)),
						methodDef.getName(srgIndex));
			}
		}

		return tokens;
	}

	private void injectMcp(Path mcpJar, Map<String, String> intermediaryToSrgMap, Map<String, String> intermediaryToDocsMap, Map<String, Map<Integer, String>> intermediaryToParamsMap)
			throws IOException, CsvValidationException {
		Map<String, List<String>> srgToIntermediary = inverseMap(intermediaryToSrgMap);
		Map<String, List<String>> simpleSrgToIntermediary = new HashMap<>();
		Pattern methodPattern = Pattern.compile("(func_\\d*)_.*");

		for (Map.Entry<String, List<String>> entry : srgToIntermediary.entrySet()) {
			Matcher matcher = methodPattern.matcher(entry.getKey());

			if (matcher.matches()) {
				simpleSrgToIntermediary.put(matcher.group(1), entry.getValue());
			}
		}

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mcpJar, false)) {
			Path fields = fs.getPath("fields.csv");
			Path methods = fs.getPath("methods.csv");
			Path params = fs.getPath("params.csv");
			Pattern paramsPattern = Pattern.compile("p_[^\\d]*(\\d+)_(\\d)+_?");

			try (CSVReader reader = new CSVReader(Files.newBufferedReader(fields, StandardCharsets.UTF_8))) {
				reader.readNext();
				String[] line;

				while ((line = reader.readNext()) != null) {
					List<String> intermediaryField = srgToIntermediary.get(line[0]);
					String docs = line[3];

					if (intermediaryField != null) {
						for (String s : intermediaryField) {
							intermediaryToSrgMap.put(s, line[1]);

							if (!line[3].isBlank()) {
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
					List<String> intermediaryMethod = srgToIntermediary.get(line[0]);
					String docs = line[3];

					if (intermediaryMethod != null) {
						for (String s : intermediaryMethod) {
							intermediaryToSrgMap.put(s, line[1]);

							if (!line[3].isBlank()) {
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
							List<String> intermediaryMethod = simpleSrgToIntermediary.get(srgMethodStartWith);

							if (intermediaryMethod != null) {
								for (String s : intermediaryMethod) {
									intermediaryToParamsMap.computeIfAbsent(s, s1 -> new HashMap<>()).put(lvIndex, named);
								}
							}
						}
					}
				}
			}
		}
	}

	private Map<String, List<String>> inverseMap(Map<String, String> intermediaryToMCPMap) {
		Map<String, List<String>> map = new HashMap<>();

		for (Map.Entry<String, String> token : intermediaryToMCPMap.entrySet()) {
			map.computeIfAbsent(token.getValue(), s -> new ArrayList<>()).add(token.getKey());
		}

		return map;
	}

	private record MemberToken(
			TokenType type,
			@Nullable MCPReader.MemberToken owner,
			String name,
			@Nullable String descriptor
	) {
		static MemberToken ofClass(String name) {
			return new MemberToken(TokenType.CLASS, null, name, null);
		}

		static MemberToken ofField(MemberToken owner, String name) {
			return new MemberToken(TokenType.FIELD, owner, name, null);
		}

		static MemberToken ofMethod(MemberToken owner, String name, String descriptor) {
			return new MemberToken(TokenType.METHOD, owner, name, descriptor);
		}
	}

	private enum TokenType {
		CLASS,
		METHOD,
		FIELD
	}

	// Used for MethodMapping.addArg
	private record BasicMethodArg(MappingTree.MethodMapping parent, int lvIndex, String name) implements MappingTree.MethodArgMapping {
		@Override
		public MappingTree.MethodMapping getMethod() {
			return parent;
		}

		@Override
		public MappingTree getTree() {
			return parent.getTree();
		}

		@Override
		public int getArgPosition() {
			return -1;
		}

		@Override
		public int getLvIndex() {
			return lvIndex;
		}

		@Override
		public String getSrcName() {
			return null;
		}

		@Override
		public @Nullable String getDstName(int namespace) {
			return namespace == 1 ? name : null;
		}

		@Override
		public @Nullable String getComment() {
			return null;
		}

		@Override
		public void setArgPosition(int position) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void setLvIndex(int index) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void setDstName(String name, int namespace) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void setComment(String comment) {
			throw new UnsupportedOperationException();
		}
	}
}
