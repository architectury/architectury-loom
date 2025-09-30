package dev.architectury.loom.accesstransformer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import dev.architectury.loom.forge.config.UserdevConfig;
import dev.architectury.loom.forge.tool.ForgeToolService;
import dev.architectury.loom.util.DependencyDownloader;
import dev.architectury.loom.util.TempFiles;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.LoomVersions;
import net.fabricmc.loom.util.service.Service;
import net.fabricmc.loom.util.service.ServiceFactory;
import net.fabricmc.loom.util.service.ServiceType;

/**
 * A service that executes the access transformer tool.
 * The tool information and the AT files are specified in the options.
 */
public final class AccessTransformerService extends Service<AccessTransformerService.Options> {
	public static final ServiceType<Options, AccessTransformerService> TYPE = new ServiceType<>(Options.class, AccessTransformerService.class);

	public interface Options extends Service.Options {
		@InputFiles
		ConfigurableFileCollection getAccessTransformers();

		@Input
		Property<String> getMainClass();

		@Classpath
		ConfigurableFileCollection getClasspath();

		@Nested
		Property<ForgeToolService.Options> getToolServiceOptions();
	}

	public static Provider<Options> createOptions(Project project, Object atFiles) {
		return TYPE.create(project, options -> {
			LoomVersions accessTransformer = chooseAccessTransformer(project);
			String mainClass = accessTransformer.equals(LoomVersions.ACCESS_TRANSFORMERS_NEO)
					? "net.neoforged.accesstransformer.cli.TransformerProcessor"
					: "net.minecraftforge.accesstransformer.TransformerProcessor";
			FileCollection classpath = new DependencyDownloader(project)
					.add(accessTransformer.mavenNotation())
					.add(LoomVersions.ASM.mavenNotation())
					.platform(LoomVersions.ACCESS_TRANSFORMERS_LOG4J_BOM.mavenNotation())
					.download();

			options.getMainClass().set(mainClass);
			options.getAccessTransformers().from(atFiles);
			options.getClasspath().from(classpath);
			options.getToolServiceOptions().set(ForgeToolService.createOptions(project));
		});
	}

	public static Provider<Options> createOptionsForLoaderAts(Project project, TempFiles tempFiles) {
		final Provider<List<String>> atFiles = project.provider(() -> {
			LoomGradleExtension extension = LoomGradleExtension.get(project);
			Path userdevJar = extension.getForgeUserdevProvider().getUserdevJar().toPath();
			return extractAccessTransformers(userdevJar, extension.getForgeUserdevProvider().getConfig().ats(), tempFiles);
		});
		return createOptions(project, atFiles);
	}

	private static List<String> extractAccessTransformers(Path jar, UserdevConfig.AccessTransformerLocation location, TempFiles tempFiles) throws IOException {
		final List<String> extracted = new ArrayList<>();

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jar)) {
			for (Path atFile : getAccessTransformerPaths(fs, location)) {
				byte[] atBytes;

				try {
					atBytes = Files.readAllBytes(atFile);
				} catch (NoSuchFileException e) {
					continue;
				}

				Path tmpFile = tempFiles.file("at-conf", ".cfg");
				Files.write(tmpFile, atBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				extracted.add(tmpFile.toAbsolutePath().toString());
			}
		}

		return extracted;
	}

	private static List<Path> getAccessTransformerPaths(FileSystemUtil.Delegate fs, UserdevConfig.AccessTransformerLocation location) throws IOException {
		return location.visitIo(directory -> {
			Path dirPath = fs.getPath(directory);

			try (Stream<Path> paths = Files.list(dirPath)) {
				return paths.toList();
			}
		}, paths -> paths.stream().map(fs::getPath).toList());
	}

	public AccessTransformerService(Options options, ServiceFactory serviceFactory) {
		super(options, serviceFactory);
	}

	private static LoomVersions chooseAccessTransformer(Project project) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		boolean serverBundleMetadataPresent = extension.getMinecraftProvider().getServerBundleMetadata() != null;

		if (!serverBundleMetadataPresent) {
			return LoomVersions.ACCESS_TRANSFORMERS;
		} else if (extension.isNeoForge()) {
			MinecraftVersionMeta.JavaVersion javaVersion = extension.getMinecraftProvider().getVersionInfo().javaVersion();

			if (javaVersion != null && javaVersion.majorVersion() >= 21) {
				return LoomVersions.ACCESS_TRANSFORMERS_NEO;
			}
		}

		return LoomVersions.ACCESS_TRANSFORMERS_NEW;
	}

	public void execute(Path input, Path output) throws IOException {
		final List<String> args = new ArrayList<>();
		args.add("--inJar");
		args.add(input.toAbsolutePath().toString());
		args.add("--outJar");
		args.add(output.toAbsolutePath().toString());

		for (File atFile : getOptions().getAccessTransformers().getFiles()) {
			args.add("--atFile");
			args.add(atFile.getAbsolutePath());
		}

		final ForgeToolService toolService = getServiceFactory().get(getOptions().getToolServiceOptions());
		toolService.exec(spec -> {
			spec.getMainClass().set(getOptions().getMainClass());
			spec.setArgs(args);
			spec.setClasspath(getOptions().getClasspath());
		});
	}
}
