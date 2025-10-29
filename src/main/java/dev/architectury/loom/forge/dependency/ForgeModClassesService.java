package dev.architectury.loom.forge.dependency;

import java.io.File;
import java.util.stream.Collectors;

import dev.architectury.loom.util.Version;
import dev.architectury.loom.util.collection.Multimap;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.ModSettings;
import net.fabricmc.loom.configuration.classpathgroups.ClasspathGroup;
import net.fabricmc.loom.configuration.ide.RunConfigSettings;
import net.fabricmc.loom.task.service.ClasspathGroupService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

public final class ForgeModClassesService extends Service<ForgeModClassesService.Options> {
	public static final ServiceType<ForgeModClassesService.Options, ForgeModClassesService> TYPE = new ServiceType<>(ForgeModClassesService.Options.class, ForgeModClassesService.class);
	private static final Logger LOGGER = LoggerFactory.getLogger(ForgeModClassesService.class);

	public static final String ENVIRONMENT_VARIABLE = "MOD_CLASSES";
	public static final String VARIABLE_KEY = "{source_roots}";

	public interface Options extends Service.Options {
		@Nested
		MapProperty<String, ClasspathGroupService.Options> getClasspathGroupOptions();

		@Input
		Property<String> getSourceRootsSeparator();
	}

	public static Provider<Options> createOptions(Project project) {
		return TYPE.maybeCreate(project, options -> {
			LoomGradleExtension extension = LoomGradleExtension.get(project);

			if (!extension.isForgeLike()) {
				return false;
			}

			for (RunConfigSettings runConfigSettings : extension.getRunConfigs()) {
				NamedDomainObjectContainer<ModSettings> modOverrides = runConfigSettings.getMods();
				NamedDomainObjectContainer<ModSettings> modSettings = !modOverrides.isEmpty() ? modOverrides : extension.getMods();
				options.getClasspathGroupOptions().put(runConfigSettings.getName(), ClasspathGroupService.create(project, modSettings));
			}

			options.getSourceRootsSeparator().set(getSourceRootsSeparator(project));
			return true;
		});
	}

	private static String getSourceRootsSeparator(Project project) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);

		// Some versions of Forge 49+ requires a different separator
		if (!extension.isForge() || extension.getForgeProvider().getVersion().getMajorVersion() < Constants.Forge.MIN_BOOTSTRAP_DEV_VERSION) {
			return File.pathSeparator;
		}

		for (Dependency dependency : project.getConfigurations().getByName(Constants.Configurations.FORGE_DEPENDENCIES).getDependencies()) {
			if (dependency.getGroup().equals("net.minecraftforge") && dependency.getName().equals("bootstrap-dev")) {
				Version version = Version.parse(dependency.getVersion());
				return version.compareTo(Version.parse("2.1.4")) >= 0 ? File.pathSeparator : ";";
			}
		}

		LOGGER.warn("Failed to find bootstrap-dev in forge dependencies, using File.pathSeparator as separator");
		return File.pathSeparator;
	}

	public ForgeModClassesService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	public String getModClasses(String runConfig) {
		// Use a set-valued multimap for deduplicating paths.
		Multimap<String, String> modClasses = Multimap.setMultimap();
		String separator = getOptions().getSourceRootsSeparator().get();
		ClasspathGroupService classpathGroupService = getServiceFactory().get(getOptions().getClasspathGroupOptions().getting(runConfig));

		for (ClasspathGroup group : classpathGroupService.getClasspathGroups()) {
			// Note: In Forge 1.16.5, resources have to come first to find mods.toml
			if (group.resourceDir() != null) {
				modClasses.put(group.name(), group.resourceDir());
			}

			for (File file : classpathGroupService.getClasspath(group)) {
				modClasses.put(group.name(), file.getAbsolutePath());
			}
		}

		return modClasses.streamEntries()
				.map(entry -> entry.left() + "%%" + entry.right())
				.collect(Collectors.joining(separator));
	}
}
