/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.extension;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.SourceSet;

import net.fabricmc.loom.api.ForgeExtensionAPI;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.api.MixinApExtensionAPI;
import net.fabricmc.loom.api.decompilers.LoomDecompiler;
import net.fabricmc.loom.configuration.ide.RunConfig;
import net.fabricmc.loom.configuration.ide.RunConfigSettings;
import net.fabricmc.loom.configuration.launch.LaunchProviderSettings;
import net.fabricmc.loom.configuration.processors.JarProcessor;
import net.fabricmc.loom.configuration.providers.mappings.GradleMappingContext;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingSpec;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingSpecBuilder;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsDependency;
import net.fabricmc.loom.util.DeprecationHelper;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.function.LazyBool;

/**
 * This class implements the public extension api.
 */
public abstract class LoomGradleExtensionApiImpl implements LoomGradleExtensionAPI {
	private static final String FORGE_PROPERTY = "loom.forge";
	private static final String PLATFORM_PROPERTY = "loom.platform";
	private static final String INCLUDE_PROPERTY = "loom.forge.include";

	protected final DeprecationHelper deprecationHelper;
	protected final ListProperty<LoomDecompiler> decompilers;
	protected final ListProperty<JarProcessor> jarProcessors;
	protected final ConfigurableFileCollection log4jConfigs;
	protected final RegularFileProperty accessWidener;
	protected final Property<Boolean> shareCaches;
	protected final Property<Boolean> remapArchives;
	protected final Property<String> customManifest;

	private NamedDomainObjectContainer<RunConfigSettings> runConfigs;

	// ===================
	//  Architectury Loom
	// ===================
	private Property<ModPlatform> platform;
	public List<String> mixinConfigs = new ArrayList<>(); // FORGE: Passed to Minecraft
	public Set<File> accessTransformers = new HashSet<>();
	public boolean useFabricMixin = true; // FORGE: Use Fabric Mixin for better refmap resolutions
	private boolean silentMojangMappingsLicense = false;
	public Boolean generateSrgTiny = null;
	private final LazyBool supportsInclude;
	private List<String> dataGenMods = new ArrayList<>();
	private final List<String> tasksBeforeRun = Collections.synchronizedList(new ArrayList<>());
	public final List<Supplier<SourceSet>> forgeLocalMods = Collections.synchronizedList(new ArrayList<>(Collections.singletonList(() ->
			getProject().getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().getByName("main"))));
	public final List<Consumer<RunConfig>> settingsPostEdit = new ArrayList<>();
	private NamedDomainObjectContainer<LaunchProviderSettings> launchConfigs;

	protected LoomGradleExtensionApiImpl(Project project, LoomFiles directories) {
		this.runConfigs = project.container(RunConfigSettings.class,
				baseName -> new RunConfigSettings(project, baseName));
		this.decompilers = project.getObjects().listProperty(LoomDecompiler.class)
				.empty();
		this.jarProcessors = project.getObjects().listProperty(JarProcessor.class)
				.empty();
		this.log4jConfigs = project.files(directories.getDefaultLog4jConfigFile());
		this.accessWidener = project.getObjects().fileProperty();
		this.shareCaches = project.getObjects().property(Boolean.class)
				.convention(false);
		this.remapArchives = project.getObjects().property(Boolean.class)
				.convention(true);
		this.customManifest = project.getObjects().property(String.class);

		this.deprecationHelper = new DeprecationHelper.ProjectBased(project);
		this.platform = project.getObjects().property(ModPlatform.class).convention(project.provider(Suppliers.memoize(() -> {
			Object platformProperty = project.findProperty(PLATFORM_PROPERTY);

			if (platformProperty != null) {
				return ModPlatform.valueOf(Objects.toString(platformProperty).toUpperCase(Locale.ROOT));
			}

			Object forgeProperty = project.findProperty(FORGE_PROPERTY);

			if (forgeProperty != null) {
				project.getLogger().warn("Project " + project.getPath() + " is using property " + FORGE_PROPERTY + " to enable forge mode. Please use '" + PLATFORM_PROPERTY + " = forge' instead!");
				return Boolean.parseBoolean(Objects.toString(forgeProperty)) ? ModPlatform.FORGE : ModPlatform.FABRIC;
			}

			return ModPlatform.FABRIC;
		})::get));
		this.supportsInclude = new LazyBool(() -> Boolean.parseBoolean(Objects.toString(project.findProperty(INCLUDE_PROPERTY))));
		this.launchConfigs = project.container(LaunchProviderSettings.class,
				baseName -> new LaunchProviderSettings(project, baseName));
	}

	@Override
	public DeprecationHelper getDeprecationHelper() {
		return deprecationHelper;
	}

	@Override
	public RegularFileProperty getAccessWidenerPath() {
		return accessWidener;
	}

	@Override
	public Property<Boolean> getShareRemapCaches() {
		return shareCaches;
	}

	@Override
	public ListProperty<LoomDecompiler> getGameDecompilers() {
		return decompilers;
	}

	@Override
	public ListProperty<JarProcessor> getGameJarProcessors() {
		return jarProcessors;
	}

	@Override
	public Dependency layered(Action<LayeredMappingSpecBuilder> action) {
		LayeredMappingSpecBuilder builder = new LayeredMappingSpecBuilder(this);
		action.execute(builder);
		LayeredMappingSpec builtSpec = builder.build();
		return new LayeredMappingsDependency(new GradleMappingContext(getProject(), builtSpec.getVersion().replace("+", "_").replace(".", "_")), builtSpec, builtSpec.getVersion());
	}

	protected abstract String getMinecraftVersion();

	@Override
	public Property<Boolean> getRemapArchives() {
		return remapArchives;
	}

	@Override
	public void runs(Action<NamedDomainObjectContainer<RunConfigSettings>> action) {
		action.execute(runConfigs);
	}

	@Override
	public NamedDomainObjectContainer<RunConfigSettings> getRunConfigs() {
		return runConfigs;
	}

	@Override
	public ConfigurableFileCollection getLog4jConfigs() {
		return log4jConfigs;
	}

	@Override
	public void mixin(Action<MixinApExtensionAPI> action) {
		action.execute(getMixin());
	}

	@Override
	public Property<String> getCustomMinecraftManifest() {
		return customManifest;
	}

	protected abstract Project getProject();

	protected abstract LoomFiles getFiles();

	@Override
	public void silentMojangMappingsLicense() {
		this.silentMojangMappingsLicense = true;
	}

	@Override
	public boolean isSilentMojangMappingsLicenseEnabled() {
		return silentMojangMappingsLicense;
	}

	@Override
	public Property<ModPlatform> getPlatform() {
		return platform;
	}

	@Override
	public boolean supportsInclude() {
		return !isForge() || supportsInclude.getAsBoolean();
	}

	@Override
	public void setGenerateSrgTiny(Boolean generateSrgTiny) {
		this.generateSrgTiny = generateSrgTiny;
	}

	@Override
	public boolean shouldGenerateSrgTiny() {
		if (generateSrgTiny != null) {
			return generateSrgTiny;
		}

		return isForge();
	}

	@Override
	public void launches(Action<NamedDomainObjectContainer<LaunchProviderSettings>> action) {
		action.execute(launchConfigs);
	}

	@Override
	public NamedDomainObjectContainer<LaunchProviderSettings> getLaunchConfigs() {
		return launchConfigs;
	}

	@Override
	public List<String> getDataGenMods() {
		return dataGenMods;
	}

	@SuppressWarnings("Convert2Lambda")
	@Override
	public void localMods(Action<SourceSetConsumer> action) {
		ModPlatform.assertPlatform(this, ModPlatform.FORGE);
		action.execute(new SourceSetConsumer() {
			@Override
			public void add(Object... sourceSets) {
				for (Object sourceSet : sourceSets) {
					if (sourceSet instanceof SourceSet) {
						forgeLocalMods.add(() -> (SourceSet) sourceSet);
					} else {
						forgeLocalMods.add(() -> getProject().getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().findByName(String.valueOf(forgeLocalMods)));
					}
				}
			}
		});
	}

	@Override
	public List<Supplier<SourceSet>> getForgeLocalMods() {
		return forgeLocalMods;
	}

	@SuppressWarnings("Convert2Lambda")
	@Override
	public void dataGen(Action<DataGenConsumer> action) {
		ModPlatform.assertPlatform(this, ModPlatform.FORGE);
		action.execute(new DataGenConsumer() {
			@Override
			public void mod(String... modIds) {
				dataGenMods.addAll(Arrays.asList(modIds));

				if (modIds.length > 0 && getRunConfigs().findByName("data") == null) {
					getRunConfigs().create("data", RunConfigSettings::data);
				}
			}
		});
	}

	@Override
	public List<String> getTasksBeforeRun() {
		return tasksBeforeRun;
	}

	@Override
	public void mixinConfig(String... config) {
		mixinConfigs.addAll(Arrays.asList(config));
	}

	@Override
	public List<String> getMixinConfigs() {
		return mixinConfigs;
	}

	@Override
	public void accessTransformer(Object file) {
		this.accessTransformers.add(getProject().file(file));
	}

	@Override
	public Set<File> getAccessTransformers() {
		return accessTransformers;
	}

	@Override
	public boolean isUseFabricMixin() {
		return useFabricMixin;
	}

	@Override
	public void setUseFabricMixin(boolean useFabricMixin) {
		this.useFabricMixin = useFabricMixin;
	}

	@Override
	public List<Consumer<RunConfig>> getSettingsPostEdit() {
		return settingsPostEdit;
	}

	@Override
	public void forge(Action<ForgeExtensionAPI> action) {
		action.execute(getForge());
	}

	// This is here to ensure that LoomGradleExtensionApiImpl compiles without any unimplemented methods
	private final class EnsureCompile extends LoomGradleExtensionApiImpl {
		private EnsureCompile() {
			super(null, null);
			throw new RuntimeException();
		}

		@Override
		public DeprecationHelper getDeprecationHelper() {
			throw new RuntimeException("Yeah... something is really wrong");
		}

		@Override
		protected Project getProject() {
			throw new RuntimeException("Yeah... something is really wrong");
		}

		@Override
		protected LoomFiles getFiles() {
			throw new RuntimeException("Yeah... something is really wrong");
		}

		@Override
		public MixinApExtension getMixin() {
			throw new RuntimeException("Yeah... something is really wrong");
		}

		@Override
		public boolean isForgeAndOfficial() {
			throw new RuntimeException("Yeah... something is really wrong");
		}

		@Override
		public boolean isForgeAndNotOfficial() {
			throw new RuntimeException("Yeah... something is really wrong");
		}

		@Override
		protected String getMinecraftVersion() {
			throw new RuntimeException("Yeah... something is really wrong");
		}

		@Override
		public ForgeExtensionAPI getForge() {
			throw new RuntimeException("Yeah... something is really wrong");
		}
	}
}
