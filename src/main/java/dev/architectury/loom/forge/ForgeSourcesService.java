package dev.architectury.loom.forge;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import dev.architectury.loom.forge.tool.ForgeToolExecutor;
import dev.architectury.loom.util.DependencyDownloader;
import dev.architectury.loom.util.NullOutputStream;
import dev.architectury.loom.util.TempFiles;
import dev.architectury.loom.util.ThreadingUtils;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.build.IntermediaryNamespaces;
import net.fabricmc.loom.task.GenerateSourcesTask;
import net.fabricmc.loom.task.service.MappingsService;
import net.fabricmc.loom.task.service.SourceRemapperService;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.LoomVersions;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

public final class ForgeSourcesService extends Service<ForgeSourcesService.Options> {
	public static ServiceType<Options, ForgeSourcesService> TYPE = new ServiceType<>(Options.class, ForgeSourcesService.class);

	private static final Logger LOGGER = Logging.getLogger(ForgeSourcesService.class);

	public interface Options extends Service.Options {
		@InputFiles
		ConfigurableFileCollection getForgeSourceJars();

		@Nested
		Property<SourceRemapperService.Options> getSourceRemapperService();

		@Input
		Property<Boolean> getShouldShowVerboseStderr();
	}

	public static Provider<Options> createOptions(Project project) {
		return TYPE.maybeCreate(project, options -> {
			final LoomGradleExtension extension = LoomGradleExtension.get(project);

			// Don't apply on other platforms
			if (!extension.isForgeLike()) {
				return false;
			}

			final String sourceDependency = extension.getForgeUserdevProvider().getConfig().sources();
			options.getForgeSourceJars().from(DependencyDownloader.download(project, sourceDependency));

			options.getSourceRemapperService().set(SourceRemapperService.TYPE.create(project, sro -> {
				final MappingsNamespace sourceNamespace = IntermediaryNamespaces.intermediaryNamespace(project);
				final String targetNamespace = MappingsNamespace.NAMED.toString();

				sro.getMappings().set(MappingsService.createOptionsWithProjectMappings(
						project,
						project.provider(sourceNamespace::toString),
						project.provider(() -> targetNamespace)
				));
				sro.getJavaCompileRelease().set(SourceRemapperService.getJavaCompileRelease(project));
				sro.getClasspath().from(DependencyDownloader.download(project, LoomVersions.JETBRAINS_ANNOTATIONS.mavenNotation()));
				sro.getClasspath().from(extension.getMinecraftJars(sourceNamespace));
				sro.getClasspath().from(project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES));

				TinyRemapperHelper.JSR_TO_JETBRAINS.forEach((from, to) -> {
					Pair<String, String> mapping = new Pair<>(from, to);
					sro.getAdditionalClassMappings().add(mapping);
				});
			}));

			options.getShouldShowVerboseStderr().set(ForgeToolExecutor.shouldShowVerboseStderr(project));

			return true;
		});
	}

	public ForgeSourcesService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	public static void addForgeSourcesDuringProjectConfiguration(Project project, ServiceFactory serviceFactory) throws IOException {
		List<Path> minecraftJars = LoomGradleExtension.get(project).getMinecraftJars(MappingsNamespace.NAMED);
		Path minecraftJar;

		if (minecraftJars.isEmpty()) {
			// ???
			throw new IllegalStateException("Could not find Minecraft jar for Forge sources");
		} else if (minecraftJars.size() > 1) {
			// Cannot add Forge sources to split jars
			return;
		} else {
			minecraftJar = minecraftJars.getFirst();
		}

		Path sourcesJar = GenerateSourcesTask.getJarFileWithSuffix("-sources.jar", minecraftJar).toPath();

		if (!Files.exists(sourcesJar)) {
			final ForgeSourcesService service = serviceFactory.get(createOptions(project));
			service.addForgeSources(minecraftJar, sourcesJar);
		}
	}

	public void addForgeSources(@Nullable Path minecraftJar, Path sourcesJar) throws IOException {
		try (FileSystemUtil.Delegate inputFs = minecraftJar == null ? null : FileSystemUtil.getJarFileSystem(minecraftJar, true);
				FileSystemUtil.Delegate outputFs = FileSystemUtil.getJarFileSystem(sourcesJar, true)) {
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

			provideForgeSources(path -> {
				Path inputPath = inputFs == null ? null : inputFs.get().getPath(path.replace(".java", ".class"));

				if (inputPath != null && Files.notExists(inputPath)) {
					LOGGER.info("Discarding forge source file {} as it does not exist in the input jar", path);
					return false;
				}

				return !path.contains("$");
			}, (path, bytes) -> {
				Path fsPath = outputFs.get().getPath(path);

				if (fsPath.getParent() != null) {
					try {
						Files.createDirectories(fsPath.getParent());
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}

				taskCompleter.add(() -> {
					LOGGER.info("Added forge source file {}", path);
					Files.write(fsPath, bytes, StandardOpenOption.CREATE);
				});
			});

			taskCompleter.complete();
		}
	}

	private void provideForgeSources(Predicate<String> classFilter, BiConsumer<String, byte[]> consumer) throws IOException {
		List<Path> forgeInstallerSources = new ArrayList<>();

		for (File file : getOptions().getForgeSourceJars()) {
			forgeInstallerSources.add(file.toPath());
			LOGGER.info("Found forge source jar: {}", file);
		}

		LOGGER.lifecycle(":found {} forge source jars", forgeInstallerSources.size());
		Map<String, byte[]> forgeSources = extractSources(forgeInstallerSources);
		forgeSources.keySet().removeIf(classFilter.negate());
		LOGGER.lifecycle(":extracted {} forge source classes", forgeSources.size());

		try (var tempFiles = new TempFiles()) {
			remapSources(tempFiles, forgeSources);
		}

		forgeSources.forEach(consumer);
	}

	private void remapSources(TempFiles tempFiles, Map<String, byte[]> sources) throws IOException {
		Path tmpInput = tempFiles.file("tmpInputForgeSources", ".jar");
		Files.delete(tmpInput);
		Path tmpOutput = tempFiles.file("tmpInputForgeSources", ".jar");
		Files.delete(tmpOutput);

		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(tmpInput, true)) {
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

			for (Map.Entry<String, byte[]> entry : sources.entrySet()) {
				Path path = delegate.get().getPath(entry.getKey());

				if (path.getParent() != null) {
					Files.createDirectories(path.getParent());
				}

				taskCompleter.add(() -> {
					Files.write(path, entry.getValue(), StandardOpenOption.CREATE);
				});
			}

			taskCompleter.complete();
		}

		PrintStream out = System.out;
		PrintStream err = System.err;

		if (!getOptions().getShouldShowVerboseStderr().get()) {
			System.setOut(new PrintStream(NullOutputStream.INSTANCE));
			System.setErr(new PrintStream(NullOutputStream.INSTANCE));
		}

		final SourceRemapperService remapperService = getServiceFactory().get(getOptions().getSourceRemapperService());
		remapperService.remapSourcesJar(tmpInput, tmpOutput);

		if (!getOptions().getShouldShowVerboseStderr().get()) {
			System.setOut(out);
			System.setErr(err);
		}

		int[] failedToRemap = {0};

		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(tmpOutput, false)) {
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

			for (Map.Entry<String, byte[]> entry : new HashSet<>(sources.entrySet())) {
				taskCompleter.add(() -> {
					Path path = delegate.get().getPath(entry.getKey());

					if (Files.exists(path)) {
						sources.put(entry.getKey(), Files.readAllBytes(path));
					} else {
						sources.remove(entry.getKey());
						LOGGER.error("Failed to remap sources for " + entry.getKey());
						failedToRemap[0]++;
					}
				});
			}

			taskCompleter.complete();
		}

		if (failedToRemap[0] > 0) {
			LOGGER.error("Failed to remap {} forge sources", failedToRemap[0]);
		}
	}

	private static Map<String, byte[]> extractSources(List<Path> forgeInstallerSources) throws IOException {
		Map<String, byte[]> sources = new ConcurrentHashMap<>();
		ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

		for (Path path : forgeInstallerSources) {
			FileSystemUtil.Delegate system = FileSystemUtil.getJarFileSystem(path, false);
			taskCompleter.onComplete(stopwatch -> system.close());

			for (Path filePath : (Iterable<? extends Path>) Files.walk(system.get().getPath("/"))::iterator) {
				if (Files.isRegularFile(filePath) && filePath.getFileName().toString().endsWith(".java")) {
					taskCompleter.add(() -> sources.put(filePath.toString(), Files.readAllBytes(filePath)));
				}
			}
		}

		taskCompleter.complete();
		return sources;
	}
}
