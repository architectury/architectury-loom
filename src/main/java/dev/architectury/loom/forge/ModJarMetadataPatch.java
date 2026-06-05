package dev.architectury.loom.forge;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches {@code net.minecraftforge.fml.loading.moddiscovery.ModJarMetadata} in Forge 1.21.1-52.0.26+ and 1.21.3-53.0.8+
 * to prevent "minecraft" mod ID overridden by "Automatic-Module-Name" after setting {@code ModJarMetadata.name}.
 *
 * <p>
 * The remap JAR manifest has Automatic-Module-Name set to "net.minecraftforge.forge", so the game crashes without this patch.
 *
 * @see <a href="https://forums.minecraftforge.net/topic/153333-proper-java-module-support-in-forge-mods/">Forum announcement</a>
 * @see <a href="https://github.com/MinecraftForge/MinecraftForge/pull/10125">Forge PR</a>
 */
public final class ModJarMetadataPatch extends ClassVisitor {
	private boolean hasTarget;
	private boolean modified;

	public ModJarMetadataPatch(ClassVisitor classVisitor) {
		super(Opcodes.ASM9, classVisitor);
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);

		if (name.equals("setModFile") && descriptor.equals("(Lnet/minecraftforge/forgespi/locating/IModFile;)V")) {
			return new MethodVisitor(Opcodes.ASM9, next) {
				@Override
				public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
					if (!hasTarget
							&& opcode == Opcodes.INVOKEINTERFACE
							&& owner.equals("net/minecraftforge/forgespi/locating/IModFile")
							&& name.equals("getSecureJar")
							&& descriptor.equals("()Lcpw/mods/jarhandling/SecureJar;")) {
						hasTarget = true;
					}

					super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
				}

				@Override
				public void visitVarInsn(int opcode, int varIndex) {
					super.visitVarInsn(opcode, varIndex);

					if (!modified && hasTarget && opcode == Opcodes.ASTORE && varIndex == 3) {
						modified = true;
						/*
						 * SecureJar jar = file.getSecureJar();
						 * ALOAD 1
						 * INVOKEINTERFACE net/minecraftforge/forgespi/locating/IModFile.getSecureJar ()Lcpw/mods/jarhandling/SecureJar; (itf)
						 * ASTORE 3
						 * // start inject
						 * if ("minecraft".equals(this.name)) {
						 *     return;
						 * }
						 */
						var after = new Label();
						visitLdcInsn("minecraft");
						super.visitVarInsn(Opcodes.ALOAD, 0);
						visitFieldInsn(Opcodes.GETFIELD, "net/minecraftforge/fml/loading/moddiscovery/ModJarMetadata", "name", "Ljava/lang/String;");
						visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
						visitJumpInsn(Opcodes.IFEQ, after);
						visitInsn(Opcodes.RETURN);
						visitLabel(after);
					}
				}
			};
		}

		return next;
	}
}
