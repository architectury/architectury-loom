package dev.architectury.loom.extensions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.google.gson.JsonObject;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

import net.fabricmc.loom.task.RemapJarTask;
import net.fabricmc.loom.util.Check;
import net.fabricmc.loom.util.ExceptionUtil;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.ZipUtils;

public final class AccessWidenerInjection {
	public static void injectAccessWidener(Path outputFile, Path accessWidener, UnaryOperator<byte[]> awProcessor, ModPlatform platform) throws IOException {
		byte[] remapped = awProcessor.apply(Files.readAllBytes(accessWidener));
		ZipUtils.add(outputFile, accessWidener.getFileName().toString(), remapped);

		if (platform == ModPlatform.QUILT) {
			ZipUtils.transformJson(JsonObject.class, outputFile, Map.of("quilt.mod.json", json -> {
				json.addProperty("access_widener", accessWidener.getFileName().toString());
				return json;
			}));
		} else {
			ZipUtils.transformJson(JsonObject.class, outputFile, Map.of("fabric.mod.json", json -> {
				json.addProperty("accessWidener", accessWidener.getFileName().toString());
				return json;
			}));
		}
	}

	public static void addToTask(TaskProvider<? extends Jar> jarTask, Object accessWidenerFile, ModPlatform platform) {
		jarTask.configure(jar -> {
			final Provider<RegularFile> accessWidener = jar.getProject().getLayout().file(
					jar.getProject().provider(() -> jar.getProject().file(accessWidenerFile))
			);

			if (jar instanceof RemapJarTask rjt) {
				rjt.getInjectAccessWidener().set(true);
				rjt.getInjectedAccessWidenerPath().set(accessWidener);
			} else {
				InjectAccessWidenerAction.addToTask(jar, accessWidener, platform);
			}
		});
	}

	public abstract static class InjectAccessWidenerAction implements Action<Task> {
		@InputFile
		@PathSensitive(PathSensitivity.NAME_ONLY)
		public abstract RegularFileProperty getAccessWidener();

		@Input
		public abstract Property<ModPlatform> getPlatform();

		private static void addToTask(Jar task, Provider<RegularFile> accessWidener, ModPlatform platform) {
			Check.require(!(task instanceof RemapJarTask), "Cannot add InjectAccessWidenerAction to RemapJarTask");

			final InjectAccessWidenerAction action = task.getProject().getObjects().newInstance(InjectAccessWidenerAction.class);
			action.getAccessWidener().set(accessWidener);
			action.getPlatform().set(platform);
			task.getInputs().file(accessWidener);
			task.doLast(action);
		}

		@Override
		public void execute(Task task) {
			final Path modJar = ((Jar) task).getArchiveFile().get().getAsFile().toPath();
			final Path accessWidener = getAccessWidener().get().getAsFile().toPath();

			try {
				injectAccessWidener(modJar, accessWidener, bytes -> bytes, getPlatform().get());
			} catch (IOException e) {
				throw ExceptionUtil.createDescriptiveWrapper(
						UncheckedIOException::new,
						"Could not inject access widener into mod jar %s".formatted(modJar),
						e
				);
			}
		}
	}
}
