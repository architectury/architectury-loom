package dev.architectury.loom.util;

import java.util.Map;

public final class SrgMixinArguments {
	public static final Map<String, String> DEFAULT_ARGUMENTS = Map.of(
			// Enable the relevant obfuscation service
			"mappingTypes", "tsrg"
	);
	public static final String REOBF_TSRG_FILE = "reobfTsrgFile";
	public static final String OUT_TSRG_FILE = "outTsrgFile";
}
