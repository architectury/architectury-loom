package net.fabricmc.loom.util.legacyforge;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFNONNULL;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Transforms Forge's CoreModManager class to search the classpath for coremods.
 * For motivation, see comments at usage site.
 */
public class CoreModManagerTransformer extends ClassVisitor {
	private static final String CLASS = "net/minecraftforge/fml/relauncher/CoreModManager";
	public static final String FILE = CLASS + ".class";

	private static final String TARGET_METHOD = "discoverCoreMods";
	private static final String OUR_METHOD_NAME = "loom$injectCoremodsFromClasspath";
	private static final String OUR_METHOD_DESCRIPTOR = "(Lnet/minecraft/launchwrapper/LaunchClassLoader;)V";

	public CoreModManagerTransformer(ClassVisitor classVisitor) {
		super(Opcodes.ASM9, classVisitor);
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);

		// We inject a call to our method, which will discover and load coremods from the classpath, at the very start of the
		// regular discovery method.
		if (name.equals(TARGET_METHOD)) {
			methodVisitor = new InjectCallAtHead(methodVisitor);
		}

		return methodVisitor;
	}

	@Override
	public void visitEnd() {
		// We add the following method, which will find all coremods on the classpath, and load them.
		//
		//     private static void loom$injectCoremodsFromClasspath(LaunchClassLoader classLoader) throws Exception {
		//         Enumeration<URL> urls = classLoader.getResources("META-INF/MANIFEST.MF");
		//         while (urls.hasMoreElements()) {
		//             URL url = urls.nextElement();
		//             InputStream stream = url.openStream();
		//             Manifest manifest = new Manifest(stream);
		//             stream.close();
		//             String coreModClass = manifest.getMainAttributes().getValue("FMLCorePlugin");
		//             if (coreModClass == null) continue;
		//             File file;
		//             if ("jar".equals(url.getProtocol())) {
		//                 file = new File(new URL(url.getPath()).toURI());
		//             } else if ("file".equals(url.getProtocol())) {
		//                 file = new File(url.toURI()).getParentFile().getParentFile();
		//             } else {
		//                 continue;
		//             }
		//             loadCoreMod(classLoader, coreModClass, file);
		//         }
		//     }
		//
		// Converted to ASM via the "ASM Bytecode Viewer" IntelliJ plugin:
		{
			MethodVisitor methodVisitor = super.visitMethod(ACC_PRIVATE | ACC_STATIC, OUR_METHOD_NAME, OUR_METHOD_DESCRIPTOR, null, null);
			methodVisitor.visitCode();
			Label label0 = new Label();
			methodVisitor.visitLabel(label0);
			methodVisitor.visitLineNumber(25, label0);
			methodVisitor.visitVarInsn(ALOAD, 0);
			methodVisitor.visitLdcInsn("META-INF/MANIFEST.MF");
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/launchwrapper/LaunchClassLoader", "getResources", "(Ljava/lang/String;)Ljava/util/Enumeration;", false);
			methodVisitor.visitVarInsn(ASTORE, 1);
			Label label1 = new Label();
			methodVisitor.visitLabel(label1);
			methodVisitor.visitLineNumber(26, label1);
			methodVisitor.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/util/Enumeration"}, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 1);
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Enumeration", "hasMoreElements", "()Z", true);
			Label label2 = new Label();
			methodVisitor.visitJumpInsn(IFEQ, label2);
			Label label3 = new Label();
			methodVisitor.visitLabel(label3);
			methodVisitor.visitLineNumber(27, label3);
			methodVisitor.visitVarInsn(ALOAD, 1);
			methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Enumeration", "nextElement", "()Ljava/lang/Object;", true);
			methodVisitor.visitTypeInsn(CHECKCAST, "java/net/URL");
			methodVisitor.visitVarInsn(ASTORE, 2);
			Label label4 = new Label();
			methodVisitor.visitLabel(label4);
			methodVisitor.visitLineNumber(28, label4);
			methodVisitor.visitVarInsn(ALOAD, 2);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false);
			methodVisitor.visitVarInsn(ASTORE, 3);
			Label label5 = new Label();
			methodVisitor.visitLabel(label5);
			methodVisitor.visitLineNumber(29, label5);
			methodVisitor.visitTypeInsn(NEW, "java/util/jar/Manifest");
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitVarInsn(ALOAD, 3);
			methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/jar/Manifest", "<init>", "(Ljava/io/InputStream;)V", false);
			methodVisitor.visitVarInsn(ASTORE, 4);
			Label label6 = new Label();
			methodVisitor.visitLabel(label6);
			methodVisitor.visitLineNumber(30, label6);
			methodVisitor.visitVarInsn(ALOAD, 3);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/io/InputStream", "close", "()V", false);
			Label label7 = new Label();
			methodVisitor.visitLabel(label7);
			methodVisitor.visitLineNumber(31, label7);
			methodVisitor.visitVarInsn(ALOAD, 4);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/Manifest", "getMainAttributes", "()Ljava/util/jar/Attributes;", false);
			methodVisitor.visitLdcInsn("FMLCorePlugin");
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/jar/Attributes", "getValue", "(Ljava/lang/String;)Ljava/lang/String;", false);
			methodVisitor.visitVarInsn(ASTORE, 5);
			Label label8 = new Label();
			methodVisitor.visitLabel(label8);
			methodVisitor.visitLineNumber(32, label8);
			methodVisitor.visitVarInsn(ALOAD, 5);
			Label label9 = new Label();
			methodVisitor.visitJumpInsn(IFNONNULL, label9);
			methodVisitor.visitJumpInsn(GOTO, label1);
			methodVisitor.visitLabel(label9);
			methodVisitor.visitLineNumber(34, label9);
			methodVisitor.visitFrame(Opcodes.F_FULL, 6, new Object[]{"net/minecraft/launchwrapper/LaunchClassLoader", "java/util/Enumeration", "java/net/URL", "java/io/InputStream", "java/util/jar/Manifest", "java/lang/String"}, 0, new Object[]{});
			methodVisitor.visitLdcInsn("jar");
			methodVisitor.visitVarInsn(ALOAD, 2);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/net/URL", "getProtocol", "()Ljava/lang/String;", false);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
			Label label10 = new Label();
			methodVisitor.visitJumpInsn(IFEQ, label10);
			Label label11 = new Label();
			methodVisitor.visitLabel(label11);
			methodVisitor.visitLineNumber(35, label11);
			methodVisitor.visitTypeInsn(NEW, "java/io/File");
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitTypeInsn(NEW, "java/net/URL");
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitVarInsn(ALOAD, 2);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/net/URL", "getPath", "()Ljava/lang/String;", false);
			methodVisitor.visitInsn(ICONST_0);
			methodVisitor.visitVarInsn(ALOAD, 2);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/net/URL", "getPath", "()Ljava/lang/String;", false);
			methodVisitor.visitIntInsn(BIPUSH, 33);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
			methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;)V", false);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/net/URL", "toURI", "()Ljava/net/URI;", false);
			methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/net/URI;)V", false);
			methodVisitor.visitVarInsn(ASTORE, 6);
			Label label12 = new Label();
			methodVisitor.visitLabel(label12);
			Label label13 = new Label();
			methodVisitor.visitJumpInsn(GOTO, label13);
			methodVisitor.visitLabel(label10);
			methodVisitor.visitLineNumber(36, label10);
			methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			methodVisitor.visitLdcInsn("file");
			methodVisitor.visitVarInsn(ALOAD, 2);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/net/URL", "getProtocol", "()Ljava/lang/String;", false);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
			methodVisitor.visitJumpInsn(IFEQ, label1);
			Label label14 = new Label();
			methodVisitor.visitLabel(label14);
			methodVisitor.visitLineNumber(37, label14);
			methodVisitor.visitTypeInsn(NEW, "java/io/File");
			methodVisitor.visitInsn(DUP);
			methodVisitor.visitVarInsn(ALOAD, 2);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/net/URL", "toURI", "()Ljava/net/URI;", false);
			methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/net/URI;)V", false);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/io/File", "getParentFile", "()Ljava/io/File;", false);
			methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/io/File", "getParentFile", "()Ljava/io/File;", false);
			methodVisitor.visitVarInsn(ASTORE, 6);
			methodVisitor.visitLabel(label13);
			methodVisitor.visitLineNumber(41, label13);
			methodVisitor.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/io/File"}, 0, null);
			methodVisitor.visitVarInsn(ALOAD, 0);
			methodVisitor.visitVarInsn(ALOAD, 5);
			methodVisitor.visitVarInsn(ALOAD, 6);
			methodVisitor.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/fml/relauncher/CoreModManager", "loadCoreMod", "(Lnet/minecraft/launchwrapper/LaunchClassLoader;Ljava/lang/String;Ljava/io/File;)Lnet/minecraftforge/fml/relauncher/CoreModManager$FMLPluginWrapper;", false);
			methodVisitor.visitInsn(POP);
			Label label15 = new Label();
			methodVisitor.visitLabel(label15);
			methodVisitor.visitLineNumber(42, label15);
			methodVisitor.visitJumpInsn(GOTO, label1);
			methodVisitor.visitLabel(label2);
			methodVisitor.visitLineNumber(43, label2);
			methodVisitor.visitFrame(Opcodes.F_FULL, 2, new Object[]{"net/minecraft/launchwrapper/LaunchClassLoader", "java/util/Enumeration"}, 0, new Object[]{});
			methodVisitor.visitInsn(RETURN);
			Label label16 = new Label();
			methodVisitor.visitLabel(label16);
			methodVisitor.visitLocalVariable("file", "Ljava/io/File;", null, label12, label10, 6);
			methodVisitor.visitLocalVariable("url", "Ljava/net/URL;", null, label4, label15, 2);
			methodVisitor.visitLocalVariable("stream", "Ljava/io/InputStream;", null, label5, label15, 3);
			methodVisitor.visitLocalVariable("manifest", "Ljava/util/jar/Manifest;", null, label6, label15, 4);
			methodVisitor.visitLocalVariable("coreModClass", "Ljava/lang/String;", null, label8, label15, 5);
			methodVisitor.visitLocalVariable("file", "Ljava/io/File;", null, label13, label15, 6);
			methodVisitor.visitLocalVariable("classLoader", "Lnet/minecraft/launchwrapper/LaunchClassLoader;", null, label0, label16, 0);
			methodVisitor.visitLocalVariable("urls", "Ljava/util/Enumeration;", "Ljava/util/Enumeration<Ljava/net/URL;>;", label1, label16, 1);
			methodVisitor.visitMaxs(8, 7);
			methodVisitor.visitEnd();
		}

		super.visitEnd();
	}

	private static class InjectCallAtHead extends MethodVisitor {
		private InjectCallAtHead(MethodVisitor methodVisitor) {
			super(Opcodes.ASM9, methodVisitor);
		}

		@Override
		public void visitCode() {
			super.visitCode();

			super.visitVarInsn(Opcodes.ALOAD, 1);
			super.visitMethodInsn(Opcodes.INVOKESTATIC, CLASS, OUR_METHOD_NAME, OUR_METHOD_DESCRIPTOR, false);
		}
	}
}
