/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2022 FabricMC
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

import org.objectweb.asm.Opcodes;

public class Constants {
	public static final String PLUGIN_ID = "dev.architectury.loom";
	public static final String LIBRARIES_BASE = "https://libraries.minecraft.net/";
	public static final String RESOURCES_BASE = "https://resources.download.minecraft.net/";
	public static final String VERSION_MANIFESTS = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
	public static final String EXPERIMENTAL_VERSIONS = "https://maven.fabricmc.net/net/minecraft/experimental_versions.json";
	public static final String FABRIC_REPOSITORY = "https://maven.fabricmc.net/";

	public static final int ASM_VERSION = Opcodes.ASM9;

	private Constants() {
	}

	/**
	 * Constants related to configurations.
	 */
	public static final class Configurations {
		public static final String MOD_COMPILE_CLASSPATH = "modCompileClasspath";
		public static final String MOD_COMPILE_CLASSPATH_MAPPED = "modCompileClasspathMapped";
		public static final String INCLUDE = "include";
		public static final String MINECRAFT = "minecraft";

		public static final String MINECRAFT_COMPILE_LIBRARIES = "minecraftLibraries";
		public static final String MINECRAFT_RUNTIME_LIBRARIES = "minecraftRuntimeLibraries";

		/**
		 * These configurations contain the minecraft client libraries.
		 */
		public static final String MINECRAFT_CLIENT_COMPILE_LIBRARIES = "minecraftClientLibraries";
		public static final String MINECRAFT_CLIENT_RUNTIME_LIBRARIES = "minecraftClientRuntimeLibraries";

		/**
		 * The server specific configurations will be empty when using a legacy (pre 21w38a server jar)
		 * find the client only dependencies on the "minecraftLibraries" config.
		 */
		public static final String MINECRAFT_SERVER_COMPILE_LIBRARIES = "minecraftServerLibraries";
		public static final String MINECRAFT_SERVER_RUNTIME_LIBRARIES = "minecraftServerRuntimeLibraries";
		/**
		 * Before Minecraft 1.19-pre1 this contains libraries that need to be extracted otherwise this goes on the runtime classpath.
		 */
		public static final String MINECRAFT_NATIVES = "minecraftNatives";
		public static final String MAPPINGS = "mappings";
		public static final String MAPPINGS_FINAL = "mappingsFinal";
		public static final String LOADER_DEPENDENCIES = "loaderLibraries";
		public static final String LOOM_DEVELOPMENT_DEPENDENCIES = "loomDevelopmentDependencies";
		public static final String SRG = "srg";
		public static final String MCP_CONFIG = "mcp";
		public static final String FORGE = "forge";
		public static final String FORGE_USERDEV = "forgeUserdev";
		public static final String FORGE_INSTALLER = "forgeInstaller";
		public static final String FORGE_UNIVERSAL = "forgeUniversal";
		/**
		 * Forge's own dependencies. Not intended to be used by users,
		 * {@link #FORGE_RUNTIME_LIBRARY forgeRuntimeLibrary} is for that instead.
		 */
		public static final String FORGE_DEPENDENCIES = "forgeDependencies";
		/**
		 * "Extra" runtime dependencies on Forge. Contains the Minecraft resources
		 * and {@linkplain Dependencies#FORGE_RUNTIME the Architectury Loom runtime}.
		 */
		public static final String FORGE_EXTRA = "forgeExtra";
		/**
		 * The configuration used to create the Forge runtime classpath file list.
		 * Users can also directly add files to this config.
		 *
		 * @see net.fabricmc.loom.configuration.providers.forge.ForgeUserdevProvider
		 */
		public static final String FORGE_RUNTIME_LIBRARY = "forgeRuntimeLibrary";
		public static final String MAPPING_CONSTANTS = "mappingsConstants";
		public static final String UNPICK_CLASSPATH = "unpick";
		/**
		 * A configuration that behaves like {@code runtimeOnly} but is not
		 * exposed in {@code runtimeElements} to dependents. A bit like
		 * {@code testRuntimeOnly}, but for mods.
		 */
		public static final String LOCAL_RUNTIME = "localRuntime";
		public static final String NAMED_ELEMENTS = "namedElements";

		private Configurations() {
		}
	}

	/**
	 * Constants related to dependencies.
	 */
	public static final class Dependencies {
		public static final String MIXIN_COMPILE_EXTENSIONS = "net.fabricmc:fabric-mixin-compile-extensions:";
		public static final String DEV_LAUNCH_INJECTOR = "net.fabricmc:dev-launch-injector:";
		public static final String TERMINAL_CONSOLE_APPENDER = "net.minecrell:terminalconsoleappender:";
		public static final String JETBRAINS_ANNOTATIONS = "org.jetbrains:annotations:";
		public static final String NATIVE_SUPPORT = "net.fabricmc:fabric-loom-native-support:";
		public static final String JAVAX_ANNOTATIONS = "com.google.code.findbugs:jsr305:"; // I hate that I have to add these.
		public static final String FORGE_RUNTIME = "dev.architectury:architectury-loom-runtime:";
		public static final String ACCESS_TRANSFORMERS = "net.minecraftforge:accesstransformers:";
		public static final String UNPROTECT = "io.github.juuxel:unprotect:";
		// Used to upgrade the ASM version for the AT tool.
		public static final String ASM = "org.ow2.asm:asm:";

		private Dependencies() {
		}

		/**
		 * Constants for versions of dependencies.
		 */
		public static final class Versions {
			public static final String MIXIN_COMPILE_EXTENSIONS = "0.6.0";
			public static final String DEV_LAUNCH_INJECTOR = "0.2.1+build.8";
			public static final String TERMINAL_CONSOLE_APPENDER = "1.2.0";
			public static final String JETBRAINS_ANNOTATIONS = "24.0.1";
			public static final String NATIVE_SUPPORT_VERSION = "1.0.1";
			public static final String JAVAX_ANNOTATIONS = "3.0.2";
			public static final String FORGE_RUNTIME = "1.1.8";
			public static final String ACCESS_TRANSFORMERS = "3.0.1";
			public static final String ACCESS_TRANSFORMERS_NEW = "8.0.5";
			public static final String UNPROTECT = "1.2.0";
			public static final String ASM = "9.3";

			private Versions() {
			}
		}
	}

	public static final class MixinArguments {
		public static final String IN_MAP_FILE_NAMED_INTERMEDIARY = "inMapFileNamedIntermediary";
		public static final String OUT_MAP_FILE_NAMED_INTERMEDIARY = "outMapFileNamedIntermediary";
		public static final String OUT_REFMAP_FILE = "outRefMapFile";
		public static final String DEFAULT_OBFUSCATION_ENV = "defaultObfuscationEnv";
		public static final String QUIET = "quiet";
		public static final String SHOW_MESSAGE_TYPES = "showMessageTypes";

		private MixinArguments() {
		}
	}

	public static final class Knot {
		public static final String KNOT_CLIENT = "net.fabricmc.loader.launch.knot.KnotClient";
		public static final String KNOT_SERVER = "net.fabricmc.loader.launch.knot.KnotServer";

		private Knot() {
		}
	}

	public static final class TaskGroup {
		public static final String FABRIC = "loom";
		public static final String IDE = "ide";

		private TaskGroup() {
		}
	}

	public static final class CustomModJsonKeys {
		public static final String INJECTED_INTERFACE = "loom:injected_interfaces";
		public static final String PROVIDED_JAVADOC = "loom:provided_javadoc";
	}

	public static final class Properties {
		public static final String MULTI_PROJECT_OPTIMISATION = "fabric.loom.multiProjectOptimisation";
		public static final String DONT_REMAP = "fabric.loom.dontRemap";
		public static final String DISABLE_REMAPPED_VARIANTS = "fabric.loom.disableRemappedVariants";
		public static final String DISABLE_PROJECT_DEPENDENT_MODS = "fabric.loom.disableProjectDependentMods";
		public static final String LIBRARY_PROCESSORS = "fabric.loom.libraryProcessors";
	}

	public static final class Forge {
		public static final String UNDETERMINED_MAIN_CLASS = "[Forge] Main class has not been determined yet!";
		public static final String ACCESS_TRANSFORMER_PATH = "META-INF/accesstransformer.cfg";
		public static final String MIXIN_CONFIGS_MANIFEST_KEY = "MixinConfigs";

		private Forge() {
		}
	}

	public static final class LegacyForge {
		public static final String LAUNCH_WRAPPER = "net.minecraft.launchwrapper.Launch";
		public static final String FML_TWEAKER = "net.minecraftforge.fml.common.launcher.FMLTweaker";
		public static final String FML_SERVER_TWEAKER = "net.minecraftforge.fml.common.launcher.FMLServerTweaker";
	}
}
