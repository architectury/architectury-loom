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

public final class ForgeSrgToMojangUtil {
	private ForgeSrgToMojangUtil() {

	}

	@SuppressWarnings("unchecked")
	public static void replaceSrgWithMojangMappings(TinyRemapper remapper, MemoryMappingTree mappings) {
		Map<String, ClassInstance> readClasses;
		try {
			Field readClassesField = TinyRemapper.class.getDeclaredField("readClasses");
			readClassesField.setAccessible(true);
			readClasses = (Map<String, ClassInstance>) readClassesField.get(remapper);
		} catch (Throwable t) {throw new RuntimeException(t);}

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
		int methods = 0;
		int fields = 0;
		int fixedMethods = 0;
		int fixedFields = 0;
		for (Map.Entry<String, MemberInstance> entry : copy.entrySet()) {
			TrMember.MemberType type = entry.getValue().getType();
			// String key = entry.getKey();
			MemberInstance memberInstance = entry.getValue();
			// System.out.println("OLD: " + key + " -> " + memberInstance.getId());
			if (type == TrMember.MemberType.FIELD) {
				fields++;
				MappingTree.FieldMapping field = tree.getField(value.getName(), memberInstance.getName(), memberInstance.getDesc(), srg);
				if (field == null) {
					// String name = memberInstance.getOwner().getName();
					// System.out.println(name.substring(name.lastIndexOf("/") + 1) + "#" + memberInstance.getName() + " has no exact match! Trying again without the descriptor...");
					field = tree.getField(value.getName(), memberInstance.getName(), null, srg);
					if (field == null) {
						// System.out.println(name.substring(name.lastIndexOf("/") + 1) + "#" + memberInstance.getName() + " has no exact match! Tried with no descriptor!");
						members.put(memberInstance.getId(), memberInstance);
						continue;
					}
				}
				String name = field.getName(mojang);
				MemberInstance instance = of(type, value, name, memberInstance.getDesc(), memberInstance.getAccess(), memberInstance.getIndex());
				members.put(instance.getId(), instance);
				// memberInstance = instance;
				// key = instance.getId();
				fixedFields++;
			} else if (type == TrMember.MemberType.METHOD) {
				methods++;
				MappingTree.MethodMapping method = tree.getMethod(value.getName(), memberInstance.getName(), memberInstance.getDesc(), srg);
				if (method == null) {
					// String name = memberInstance.getOwner().getName();
					// System.out.println(name.substring(name.lastIndexOf("/") + 1) + "#" + memberInstance.getName() + " has no exact match! Trying again without the descriptor...");
					method = tree.getMethod(value.getName(), memberInstance.getName(), null, srg);
					if (method == null) {
						//System.out.println(name.substring(name.lastIndexOf("/") + 1) + "#" + memberInstance.getName() + " has no exact match! Tried with no descriptor!");
						members.put(memberInstance.getId(), memberInstance);
						continue;
					}
				}
				String name = method.getName(mojang);
				MemberInstance instance = of(type, value, name, memberInstance.getDesc(), memberInstance.getAccess(), memberInstance.getIndex());
				members.put(instance.getId(), instance);
				// memberInstance = instance;
				// key = instance.getId();
				fixedMethods++;
			} else {
				members.put(memberInstance.getId(), memberInstance);
			}
			// System.out.println("NEW: " + key + " -> " + memberInstance.getId());
		}
		System.out.println(value.getName() + ": Fields: " + fixedFields + "/" + fields + ", Methods: " + fixedMethods + "/" + methods);
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
