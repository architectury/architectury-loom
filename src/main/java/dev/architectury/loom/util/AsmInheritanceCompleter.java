package dev.architectury.loom.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.mappingio.FlatMappingVisitor;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.RegularAsFlatMappingVisitor;
import net.fabricmc.mappingio.tree.MappingTree;

public final class AsmInheritanceCompleter {
	public static <T extends MappingTree & MappingVisitor> void complete(T tree, List<Path> classpath) throws IOException {
		Map<String, ClassEntry> entries = new HashMap<>();
		Set<String> classesToCheck = tree.getClasses()
				.stream()
				.map(MappingTree.ClassMapping::getSrcName)
				.collect(Collectors.toSet());
		ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9) {
			private @Nullable ClassEntry getEntry(String name) {
				return classesToCheck.contains(name) ? entries.computeIfAbsent(name, ClassEntry::new) : null;
			}

			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				ClassEntry entry = getEntry(name);
				assert entry != null;
				entry.addParent(getEntry(superName));

				if (interfaces != null) {
					for (String itf : interfaces) {
						entry.addParent(getEntry(itf));
					}
				}
			}
		};

		try (TreeView view = TreeView.of(classpath)) {
			for (String className : classesToCheck) {
				Path path = view.getPath(className + ".class");
				ClassReader cr = new ClassReader(Files.readAllBytes(path));
				cr.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			}
		}

		FlatMappingVisitor flat = new RegularAsFlatMappingVisitor(tree);

		for (ClassEntry entry : entries.values()) {
			MappingTree.ClassMapping c = tree.getClass(entry.name);

			for (MappingTree.MethodMapping method : c.getMethods()) {
				String name = method.getSrcName();
				String desc = method.getSrcDesc();
				String[] dstNames = new String[tree.getDstNamespaces().size()];

				for (int i = 0; i < dstNames.length; i++) {
					dstNames[i] = method.getDstName(i);
				}

				Queue<ClassEntry> childQueue = new ArrayDeque<>();
				Set<String> usedEntries = new HashSet<>();

				for (ClassEntry child : entry.children) {
					childQueue.offer(child);
					usedEntries.add(child.name);
				}

				ClassEntry child;

				while ((child = childQueue.poll()) != null) {
					flat.visitMethod(child.name, name, desc, dstNames);

					for (ClassEntry grandchild : child.children) {
						if (usedEntries.add(grandchild.name)) {
							childQueue.offer(grandchild);
						}
					}
				}
			}
		}
	}

	private static final class ClassEntry {
		final String name;
		final List<ClassEntry> children = new ArrayList<>();

		ClassEntry(String name) {
			this.name = name;
		}

		void addParent(@Nullable ClassEntry entry) {
			if (entry != null) {
				entry.children.add(this);
			}
		}
	}

	private sealed interface TreeView extends Closeable {
		Path getPath(String name);

		static TreeView of(Path path) throws IOException {
			if (Files.isDirectory(path)) {
				return new RootView(path);
			}

			return new FileSystemView(FileSystemUtil.getJarFileSystem(path, false));
		}

		static TreeView of(List<Path> paths) throws IOException {
			List<TreeView> views = new ArrayList<>(paths.size());

			for (Path path : paths) {
				views.add(of(path));
			}

			return new CompositeView(views);
		}

		record FileSystemView(FileSystemUtil.Delegate fs) implements TreeView {
			@Override
			public Path getPath(String name) {
				return fs.getPath(name);
			}

			@Override
			public void close() throws IOException {
				fs.close();
			}
		}

		record RootView(Path root) implements TreeView {
			@Override
			public Path getPath(String name) {
				return root.resolve(name);
			}

			@Override
			public void close() throws IOException {
			}
		}

		record CompositeView(List<TreeView> views) implements TreeView {
			@Override
			public Path getPath(String name) {
				int i = 0;
				Path path;

				do {
					path = views.get(i).getPath(name);
				} while (i < views.size() && Files.notExists(path));

				return path;
			}

			@Override
			public void close() throws IOException {
				for (TreeView view : views) {
					view.close();
				}
			}
		}
	}
}
