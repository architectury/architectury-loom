/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.mappingio.tree.MappingTree;

import org.objectweb.asm.Type;

public class RemapForgeGameTestHooksVisitor extends ClassVisitor {
	private final MappingTree mappings;
	private final int from;
	private final int to;

	public RemapForgeGameTestHooksVisitor(int api, ClassVisitor classVisitor, MappingTree mappings, String from, String to) {
		super(api, classVisitor);
		this.mappings = mappings;
		this.from = this.mappings.getNamespaceId(from);
		this.to = this.mappings.getNamespaceId(to);
	}

	public static void remapForgeGameTestHooks(Path jar, String className, MappingTree mappings, String from, String to) throws IOException {
		System.out.println("Remapping Forge GameTest hooks");
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jar, false)) {
			Path classPath = fs.get().getPath(className.replace('.', '/') + ".class");

			if (Files.exists(classPath)) {
				ClassReader reader = new ClassReader(Files.readAllBytes(classPath));
				ClassWriter writer = new ClassWriter(0);
				ClassVisitor classVisitor = new RemapForgeGameTestHooksVisitor(Constants.ASM_VERSION, writer, mappings, from, to);
				reader.accept(classVisitor, 0);
				Files.write(classPath, writer.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING);
			}
		}
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
		if ("getDefaultBatch".equals(name) && "()Ljava/lang/String;".equals(descriptor) && from != MappingTree.NULL_NAMESPACE_ID && to != MappingTree.NULL_NAMESPACE_ID) {
			return new MethodVisitor(api, methodVisitor) {
				Type type;
				@Override
				public void visitLdcInsn(Object value) {
					if (value instanceof Type type && type.getClassName().startsWith("net.minecraft.")) {
						this.type = type;
					}
					if (value instanceof String str && str.startsWith("m_")) {
						// This should not fail
						value = mappings.getClass(type.getInternalName(), from).getMethod(str, null, from).getName(to);
					}

					super.visitLdcInsn(value);
				}
			};
		}

		return methodVisitor;
	}
}
