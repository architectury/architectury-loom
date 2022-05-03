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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import dev.architectury.tinyremapper.IMappingProvider;
import dev.architectury.tinyremapper.TinyRemapper;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Triple;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.srg.InnerClassRemapper;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * Contains shortcuts to create tiny remappers using the mappings accessibly to the project.
 */
public final class TinyRemapperHelper {
	public static final Map<String, String> JSR_TO_JETBRAINS = new ImmutableMap.Builder<String, String>()
			.put("javax/annotation/Nullable", "org/jetbrains/annotations/Nullable")
			.put("javax/annotation/Nonnull", "org/jetbrains/annotations/NotNull")
			.put("javax/annotation/concurrent/Immutable", "org/jetbrains/annotations/Unmodifiable")
			.build();

	/**
	 * Matches the new local variable naming format introduced in 21w37a.
	 */
	private static final Pattern MC_LV_PATTERN = Pattern.compile("\\$\\$\\d+");

	private TinyRemapperHelper() {
	}

	public static TinyRemapper getTinyRemapper(Project project, String fromM, String toM) throws IOException {
		return getTinyRemapper(project, fromM, toM, false, (builder) -> { }, Set.of());
	}

	public static TinyRemapper getTinyRemapper(Project project, String fromM, String toM, boolean fixRecords, Consumer<TinyRemapper.Builder> builderConsumer, Set<String> fromClassNames) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		TinyRemapper remapper = _getTinyRemapper(project, fixRecords, builderConsumer).getLeft();
		ImmutableSet.Builder<IMappingProvider> providers = ImmutableSet.builder();
		providers.add(TinyRemapperHelper.create((fromM.equals("srg") || toM.equals("srg")) && extension.isForge() ? extension.getMappingsProvider().getMappingsWithSrg() : extension.getMappingsProvider().getMappings(), fromM, toM, true));

		if (extension.isForge()) {
			if (!fromClassNames.isEmpty()) {
				providers.add(InnerClassRemapper.of(fromClassNames, extension.getMappingsProvider().getMappingsWithSrg(), fromM, toM));
			}
		} else {
			providers.add(out -> TinyRemapperHelper.JSR_TO_JETBRAINS.forEach(out::acceptClass));
		}

		remapper.replaceMappings(providers.build());
		return remapper;
	}

	public static Triple<TinyRemapper, Mutable<MemoryMappingTree>, List<TinyRemapper.ApplyVisitorProvider>> _getTinyRemapper(Project project, boolean fixRecords, Consumer<TinyRemapper.Builder> builderConsumer) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		Mutable<MemoryMappingTree> mappings = new MutableObject<>();
		List<TinyRemapper.ApplyVisitorProvider> postApply = new ArrayList<>();

		TinyRemapper.Builder builder = TinyRemapper.newRemapper()
				.renameInvalidLocals(true)
				.logUnknownInvokeDynamic(false)
				.ignoreConflicts(extension.isForge())
				.cacheMappings(true)
				.threads(Runtime.getRuntime().availableProcessors())
				.logger(project.getLogger()::lifecycle)
				.rebuildSourceFilenames(true);

		builder.invalidLvNamePattern(MC_LV_PATTERN);
		builder.inferNameFromSameLvIndex(true);
		builder.extraPreApplyVisitor((cls, next) -> {
			if (fixRecords && !cls.isRecord() && "java/lang/Record".equals(cls.getSuperName()) && mappings.getValue() != null) {
				return new RecordComponentFixVisitor(next, mappings.getValue(), mappings.getValue().getNamespaceId(MappingsNamespace.INTERMEDIARY.toString()));
			}

			return next;
		});
		builder.extraPostApplyVisitor((trClass, classVisitor) -> {
			for (TinyRemapper.ApplyVisitorProvider provider : postApply) {
				classVisitor = provider.insertApplyVisitor(trClass, classVisitor);
			}

			return classVisitor;
		});

		builderConsumer.accept(builder);
		return Triple.of(builder.build(), mappings, postApply);
	}

	public static Triple<TinyRemapper, Mutable<MemoryMappingTree>, List<TinyRemapper.ApplyVisitorProvider>> getTinyRemapper(Project project, boolean fixRecords, Consumer<TinyRemapper.Builder> builderConsumer) throws IOException {
		Triple<TinyRemapper, Mutable<MemoryMappingTree>, List<TinyRemapper.ApplyVisitorProvider>> remapper = _getTinyRemapper(project, fixRecords, builderConsumer);
		remapper.getLeft().readClassPath(getMinecraftDependencies(project));
		remapper.getLeft().prepareClasses();
		return remapper;
	}

	public static Path[] getMinecraftDependencies(Project project) {
		return project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_DEPENDENCIES).getFiles()
				.stream().map(File::toPath).toArray(Path[]::new);
	}

	private static IMappingProvider.Member memberOf(String className, String memberName, String descriptor) {
		return new IMappingProvider.Member(className, memberName, descriptor);
	}

	public static IMappingProvider create(Path mappings, String from, String to, boolean remapLocalVariables) throws IOException {
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		MappingReader.read(mappings, mappingTree);
		return create(mappingTree, from, to, remapLocalVariables);
	}

	public static IMappingProvider create(MappingTree mappings, String from, String to, boolean remapLocalVariables) {
		return (acceptor) -> {
			final int fromId = mappings.getNamespaceId(from);
			final int toId = mappings.getNamespaceId(to);

			if (toId == MappingTreeView.NULL_NAMESPACE_ID) {
				System.out.println("Warning: Trying to remap to unknown namespace: " + to);
			}

			for (MappingTree.ClassMapping classDef : mappings.getClasses()) {
				String className = classDef.getName(fromId);
				String dstName = classDef.getName(toId);

				if (dstName == null) {
					// Unsure if this is correct, should be better than crashing tho.
					dstName = className;
				}

				acceptor.acceptClass(className, dstName);

				for (MappingTree.FieldMapping field : classDef.getFields()) {
					acceptor.acceptField(memberOf(className, field.getName(fromId), field.getDesc(fromId)), field.getName(toId));
				}

				for (MappingTree.MethodMapping method : classDef.getMethods()) {
					IMappingProvider.Member methodIdentifier = memberOf(className, method.getName(fromId), method.getDesc(fromId));
					acceptor.acceptMethod(methodIdentifier, method.getName(toId));

					if (remapLocalVariables) {
						for (MappingTree.MethodArgMapping parameter : method.getArgs()) {
							String name = parameter.getName(toId);

							if (name == null) {
								continue;
							}

							acceptor.acceptMethodArg(methodIdentifier, parameter.getLvIndex(), name);
						}

						for (MappingTree.MethodVarMapping localVariable : method.getVars()) {
							acceptor.acceptMethodVar(methodIdentifier, localVariable.getLvIndex(),
									localVariable.getStartOpIdx(), localVariable.getLvtRowIndex(),
									localVariable.getName(toId));
						}
					}
				}
			}
		};
	}
}
