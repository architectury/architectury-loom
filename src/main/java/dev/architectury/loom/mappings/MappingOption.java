package dev.architectury.loom.mappings;

import net.fabricmc.loom.api.LoomGradleExtensionAPI;

public enum MappingOption {
DEFAULT,
WITH_SRG,
WITH_MOJANG;

public static MappingOption forPlatform(LoomGradleExtensionAPI extension) {
// Use detected status instead of platform enum for Architectury support
if (extension.isNeoForge()) {
return WITH_MOJANG;
} else if (extension.isForge()) {
return WITH_SRG;
}
return DEFAULT;
}
}