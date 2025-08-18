package dev.architectury.loom.forge.tool;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Nested;
import org.gradle.process.ExecResult;

import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/**
 * A service that can execute Forge tools in tasks and during project configuration.
 */
public final class ForgeToolService extends Service<ForgeToolService.Options> {
	public static final ServiceType<Options, ForgeToolService> TYPE = new ServiceType<>(Options.class, ForgeToolService.class);

	public interface Options extends Service.Options {
		/**
		 * The default settings from {@link ForgeToolExecutor}.
		 * It contains the verbosity and JVM toolchain options that are dependent on the project state.
		 */
		@Nested
		Property<ForgeToolExecutor.Settings> getBaseSettings();

		@Inject
		ObjectFactory getObjects();

		@Inject
		ProviderFactory getProviders();
	}

	public static Provider<Options> createOptions(Project project) {
		return TYPE.create(project, options -> {
			options.getBaseSettings().set(ForgeToolExecutor.getDefaultSettings(project));
		});
	}

	public ForgeToolService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	/**
	 * Executes the tool specified in the spec.
	 *
	 * @param configurator an action that configures the spec
	 * @return the execution result
	 */
	public ExecResult exec(Action<? super ForgeToolExecutor.Settings> configurator) {
		return getOptions().getProviders().of(ForgeToolValueSource.class, spec -> {
			final ForgeToolExecutor.Settings settings = getOptions().getObjects().newInstance(ForgeToolExecutor.Settings.class);
			ForgeToolExecutor.copySettings(getOptions().getBaseSettings().get(), settings);
			configurator.execute(settings);
			spec.getParameters().getSettings().set(settings);
		}).get();
	}
}
