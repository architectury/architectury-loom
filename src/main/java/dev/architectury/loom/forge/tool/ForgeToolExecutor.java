package dev.architectury.loom.forge.tool;

import java.util.Collection;
import java.util.List;

import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.process.JavaExecSpec;
import org.jetbrains.annotations.Nullable;

/**
 * Contains helpers for executing Forge's command line tools
 * with suppressed output streams to prevent annoying log spam.
 *
 * <p>To execute Forge tools during project config, use {@link ForgeToolValueSource};
 * to execute them during config or tasks, use {@link ForgeToolService}.
 */
public final class ForgeToolExecutor {
	private ForgeToolExecutor() {
	}

	public static boolean shouldShowVerboseStdout(Project project) {
		// if running with INFO or DEBUG logging
		return project.getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) < 0;
	}

	public static boolean shouldShowVerboseStderr(Project project) {
		// if stdout is shown or stacktraces are visible so that errors printed to stderr show up
		return shouldShowVerboseStdout(project) || project.getGradle().getStartParameter().getShowStacktrace() != ShowStacktrace.INTERNAL_EXCEPTIONS;
	}

	public static Settings getDefaultSettings(Project project) {
		final Settings settings = project.getObjects().newInstance(Settings.class);
		settings.getExecutable().set(JavaExecutableFetcher.getJavaToolchainExecutable(project));
		settings.getShowVerboseStdout().set(shouldShowVerboseStdout(project));
		settings.getShowVerboseStderr().set(shouldShowVerboseStderr(project));

		// call this to ensure the fields aren't null when used in services
		// (otherwise, the JSON serialization crashes)
		settings.getProgramArgs();
		settings.getJvmArgs();
		settings.getMainClass();
		settings.getExecClasspath();

		return settings;
	}

	static ExecResult exec(ExecOperations execOperations, Settings settings) {
		return execOperations.javaexec(spec -> applyToSpec(settings, spec));
	}

	private static void applyToSpec(Settings settings, JavaExecSpec spec) {
		final @Nullable String executable = settings.getExecutable().getOrNull();
		if (executable != null) spec.setExecutable(executable);
		final @Nullable String mainClass = settings.getMainClass().getOrNull();
		if (mainClass != null) spec.getMainClass().set(mainClass);
		spec.setArgs(settings.getProgramArgs().get());
		spec.setJvmArgs(settings.getJvmArgs().get());
		spec.setClasspath(settings.getExecClasspath());

		if (settings.getShowVerboseStdout().get()) {
			spec.setStandardOutput(System.out);
		} else {
			spec.setStandardOutput(NullOutputStream.INSTANCE);
		}

		if (settings.getShowVerboseStderr().get()) {
			spec.setErrorOutput(System.err);
		} else {
			spec.setErrorOutput(NullOutputStream.INSTANCE);
		}
	}

	static void copySettings(Settings source, Settings target) {
		target.getExecutable().set(source.getExecutable());
		target.getMainClass().set(source.getMainClass());
		target.getProgramArgs().set(source.getProgramArgs());
		target.getJvmArgs().set(source.getJvmArgs());
		target.getExecClasspath().setFrom(source.getExecClasspath());
		target.getShowVerboseStdout().set(source.getShowVerboseStdout());
		target.getShowVerboseStderr().set(source.getShowVerboseStderr());
	}

	public interface Settings {
		@Input
		@Optional
		Property<String> getExecutable();

		@Input
		@Optional
		ListProperty<String> getProgramArgs();

		@Input
		@Optional
		ListProperty<String> getJvmArgs();

		@Input
		@Optional
		Property<String> getMainClass();

		@Classpath
		ConfigurableFileCollection getExecClasspath();

		@Input
		Property<Boolean> getShowVerboseStdout();

		@Input
		Property<Boolean> getShowVerboseStderr();

		default void classpath(Object... paths) {
			getExecClasspath().from(paths);
		}

		default void setClasspath(Object... paths) {
			getExecClasspath().setFrom(paths);
		}

		default void args(String... args) {
			getProgramArgs().addAll(args);
		}

		default void args(Collection<String> args) {
			getProgramArgs().addAll(args);
		}

		default void setArgs(List<String> args) {
			getProgramArgs().set(args);
		}

		default void jvmArgs(String... args) {
			getJvmArgs().addAll(args);
		}

		default void jvmArgs(Collection<String> args) {
			getJvmArgs().addAll(args);
		}
	}
}
