package dev.architectury.loom.accesstransformer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;

import dev.architectury.loom.extensions.ModBuildExtensions;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.jvm.tasks.Jar;

import net.fabricmc.loom.task.RemapJarTask;
import net.fabricmc.loom.task.service.MappingsService;
import net.fabricmc.loom.util.service.ScopedServiceFactory;

public abstract class Aw2AtAction implements Action<Task> {
	@Input
	public abstract SetProperty<String> getAccessWidenerPaths();

	// This property is left empty here on purpose.
	// This is a form of future-proofing this action class if it needs to be used with RemapJarTask.
	// Note that we currently want RJT to process this separately as the reproducibility rewrites
	// need to come after this action.
	@Nested
	@Optional
	public abstract Property<MappingsService.Options> getMappingOptions();

	static void addToTask(Jar task, Provider<Set<String>> awPaths) {
		if (task instanceof RemapJarTask) {
			throw new IllegalArgumentException("Jar task must not be a RemapJarTask");
		}

		final Aw2AtAction action = task.getProject().getObjects().newInstance(Aw2AtAction.class);
		action.getAccessWidenerPaths().set(awPaths);
		// RemapJarTask already sets up the inputs on its own, but we have to add them manually to other jar tasks.
		task.getInputs().property("atAccessWideners", awPaths);
		task.doLast(action);
	}

	@Override
	public void execute(Task task) {
		try (var serviceFactory = new ScopedServiceFactory()) {
			final Jar jarTask = (Jar) task;
			final Path jarPath = jarTask.getArchiveFile().get().getAsFile().toPath();
			ModBuildExtensions.convertAwToAt(serviceFactory, getAccessWidenerPaths().get(), jarPath, getMappingOptions());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
