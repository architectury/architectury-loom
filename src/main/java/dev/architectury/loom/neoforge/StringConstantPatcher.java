package dev.architectury.loom.neoforge;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.mappingio.tree.MappingTreeView;

/**
 * A class visitor that replaces a set of string constants in the processed class file(s).
 */
public final class StringConstantPatcher extends ClassVisitor {
	private final Map<String, String> constantChanges;

	private static final String LAUNCH_HANDLER_INPUT_CLASS_FILE = "net/minecraft/client/Minecraft.class";
	private static final String LAUNCH_HANDLER_OUTPUT_CLASS_FILE = "net/minecraft/client/main/Main.class";

	private static final RemapKey DETECTED_VERSION_KEY = new RemapKey("net/minecraft/DetectedVersion.class", "net/minecraft/class_3797");
	private static final RemapKey MINECRAFT_KEY = new RemapKey("net/minecraft/client/Minecraft.class", "net/minecraft/class_310");

	private StringConstantPatcher(ClassVisitor next, Map<String, String> constantChanges) {
		super(Constants.ASM_VERSION, next);
		this.constantChanges = constantChanges;
	}

	private StringConstantPatcher(ClassVisitor next, String from, String to) {
		this(next, Map.of(from, to));
	}

	/**
	 * Patches the Minecraft.class check in FML's CommonUserdevLaunchHandler
	 * to refer to a class that is found in any mapping set (Main.class).
	 *
	 * <p>See <a href="https://github.com/architectury/architectury-loom/issues/212">issue #212</a>
	 */
	public static ClassVisitor forUserdevLaunchHandler(ClassVisitor next) {
		return new StringConstantPatcher(next, LAUNCH_HANDLER_INPUT_CLASS_FILE, LAUNCH_HANDLER_OUTPUT_CLASS_FILE);
	}

	private static ClassVisitor forRemapping(ClassVisitor next, MappingTreeView mappings, RemapKey... keys) {
		final Map<String, String> constantChanges = new HashMap<>();

		for (RemapKey key : keys) {
			final @Nullable String target = getNamedClassName(mappings, key.intermediary);

			if (target == null || key.constantWithClassSuffix.equals(target + ".class")) {
				continue;
			}

			constantChanges.put(key.constantWithClassSuffix, target + ".class");
		}

		if (!constantChanges.isEmpty()) {
			return new StringConstantPatcher(next, constantChanges);
		} else {
			return next;
		}
	}

	private static @Nullable String getNamedClassName(MappingTreeView mappings, String intermediary) {
		final int intermediaryNsId = mappings.getNamespaceId(MappingsNamespace.INTERMEDIARY.toString());
		final MappingTreeView.@Nullable ClassMappingView c = mappings.getClass(intermediary, intermediaryNsId);
		return c != null ? c.getName(MappingsNamespace.NAMED.toString()) : null;
	}

	/**
	 * Patches the DetectedVersion.class check in FML's FMLLoader
	 * to remap that reference to the current deobfuscated ns.
	 *
	 * <p>See <a href="https://github.com/architectury/architectury-loom/issues/299">issue #299</a>
	 */
	public static ClassVisitor forFmlLoader(ClassVisitor next, MappingTreeView mappings) {
		return forRemapping(next, mappings, DETECTED_VERSION_KEY);
	}

	/**
	 * Patches the Minecraft.class check in FML's GameLocator
	 * to remap that reference to the current deobfuscated ns.
	 *
	 * <p>See <a href="https://github.com/architectury/architectury-loom/issues/299">issue #299</a>
	 */
	public static ClassVisitor forGameLocator(ClassVisitor next, MappingTreeView mappings) {
		return forRemapping(next, mappings, MINECRAFT_KEY);
	}

	/**
	 * Patches the class checks in FML's RequiredSystemFiles
	 * to remap that reference to the current deobfuscated ns.
	 *
	 * <p>See <a href="https://github.com/architectury/architectury-loom/issues/299">issue #299</a>
	 */
	public static ClassVisitor forRequiredSystemFiles(ClassVisitor next, MappingTreeView mappings) {
		return forRemapping(next, mappings, DETECTED_VERSION_KEY, MINECRAFT_KEY);
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		return new MethodPatcher(super.visitMethod(access, name, descriptor, signature, exceptions));
	}

	private final class MethodPatcher extends MethodVisitor {
		MethodPatcher(MethodVisitor next) {
			super(Constants.ASM_VERSION, next);
		}

		@Override
		public void visitLdcInsn(Object value) {
			if (value instanceof String key) {
				final @Nullable String target = constantChanges.get(key);

				if (target != null) {
					value = target;
				}
			}

			super.visitLdcInsn(value);
		}
	}

	private record RemapKey(String constantWithClassSuffix, String intermediary) {
	}
}
