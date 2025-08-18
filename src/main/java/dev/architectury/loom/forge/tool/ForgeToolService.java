package dev.architectury.loom.forge.tool;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Nested;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.process.JavaExecSpec;

import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/**
 * A service that can execute Forge tools in tasks.
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
		ExecOperations getExecOperations();
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
	public ExecResult exec(Action<? super JavaExecSpec> configurator) {
		return getOptions().getExecOperations().javaexec(spec -> {
			ForgeToolExecutor.applyToSpec(getOptions().getBaseSettings().get(), spec);
			configurator.execute(spec);
		});
	}
}
