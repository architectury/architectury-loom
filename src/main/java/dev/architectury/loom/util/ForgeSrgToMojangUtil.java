package dev.architectury.loom.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.ClassInstance;
import net.fabricmc.tinyremapper.MemberInstance;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.api.TrMember;

// FIXME: There has to be a better way to fix the mappings
public final class ForgeSrgToMojangUtil {
	private ForgeSrgToMojangUtil() { }

	@SuppressWarnings("unchecked")
	public static void replaceSrgWithMojangMappings(TinyRemapper remapper, MemoryMappingTree mappings) {
		Map<String, ClassInstance> readClasses;

		try {
			Field readClassesField = TinyRemapper.class.getDeclaredField("readClasses");
			readClassesField.setAccessible(true);
			readClasses = (Map<String, ClassInstance>) readClassesField.get(remapper);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}

		for (ClassInstance value : readClasses.values()) {
			replaceSrgWithMojangMappings(value, mappings);
		}
	}

	@SuppressWarnings("unchecked")
	public static void replaceSrgWithMojangMappings(ClassInstance value, MemoryMappingTree tree) {
		int srg = tree.getNamespaceId("srg");
		int mojang = tree.getNamespaceId("mojang");
		HashMap<String, MemberInstance> members;

		try {
			Field membersField = ClassInstance.class.getDeclaredField("members");
			membersField.setAccessible(true);
			members = (HashMap<String, MemberInstance>) membersField.get(value);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}

		Map<String, MemberInstance> copy = (Map<String, MemberInstance>) members.clone();
		members.clear();

		for (Map.Entry<String, MemberInstance> entry : copy.entrySet()) {
			TrMember.MemberType type = entry.getValue().getType();
			MemberInstance memberInstance = entry.getValue();
			String name;

			if (type == TrMember.MemberType.FIELD) {
				MappingTree.FieldMapping field = tree.getField(value.getName(), memberInstance.getName(), memberInstance.getDesc(), srg);

				if (field == null) {
					field = tree.getField(value.getName(), memberInstance.getName(), null, srg);

					if (field == null) {
						members.put(memberInstance.getId(), memberInstance);
						continue;
					}
				}

				name = field.getName(mojang);
			} else if (type == TrMember.MemberType.METHOD) {
				MappingTree.MethodMapping method = tree.getMethod(value.getName(), memberInstance.getName(), memberInstance.getDesc(), srg);

				if (method == null) {
					method = tree.getMethod(value.getName(), memberInstance.getName(), null, srg);

					if (method == null) {
						members.put(memberInstance.getId(), memberInstance);
						continue;
					}
				}

				name = method.getName(mojang);
			} else {
				throw new AssertionError();
			}

			MemberInstance instance = of(type, value, name, memberInstance.getDesc(), memberInstance.getAccess(), memberInstance.getIndex());
			members.put(instance.getId(), instance);
		}
	}

	private static MemberInstance of(TrMember.MemberType type, ClassInstance cls, String name, String desc, int access, int index) {
		try {
			Constructor<MemberInstance> constructor = MemberInstance.class.getDeclaredConstructor(TrMember.MemberType.class, ClassInstance.class, String.class, String.class, int.class, int.class);
			constructor.setAccessible(true);
			return constructor.newInstance(type, cls, name, desc, access, index);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}
}
