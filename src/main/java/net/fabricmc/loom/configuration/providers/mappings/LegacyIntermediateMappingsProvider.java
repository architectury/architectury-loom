package net.fabricmc.loom.configuration.providers.mappings;

import com.google.common.base.Stopwatch;

import dev.architectury.loom.util.TempFiles;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.intermediate.IntermediateMappingsProvider;

import net.fabricmc.loom.configuration.providers.forge.fg2.LegacyPatchedProvider;
import net.fabricmc.loom.configuration.providers.forge.minecraft.ForgeMinecraftProvider;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.stitch.commands.CommandGenerateIntermediary;

import org.gradle.api.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public abstract class LegacyIntermediateMappingsProvider extends IntermediateMappingsProvider {

	@Override
	public void provide(Path tinyMappings, Project project) throws IOException {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		if (!extension.isLegacyForge()) throw new IllegalStateException("Legacy Intermediates are only for legacy forge!");
		if (((ForgeMinecraftProvider)extension.getMinecraftProvider()).getPatchedProvider().getMinecraftSrgJar() == null) throw new IllegalStateException("Merged jar not created!");
		Stopwatch stopwatch = Stopwatch.createStarted();
		project.getLogger().lifecycle(":generating dummy intermediary");

		Path minecraftJar = ((ForgeMinecraftProvider) extension.getMinecraftProvider()).getPatchedProvider().getMinecraftSrgJar();

		// create a temporary folder into which stitch will output the v1 file
		// we cannot just create a temporary file directly, cause stitch will try to read it if it exists
		TempFiles tmpFolder = new TempFiles();
		Path tinyV1 = tmpFolder.file("intermediary-v1", "tiny");
		tinyV1.toFile().delete(); // delete the file so the command doesn't fail

		CommandGenerateIntermediary command = new CommandGenerateIntermediary();

		try {
			command.run(new String[]{ minecraftJar.toAbsolutePath().toString(), tinyV1.toAbsolutePath().toString() });
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to generate intermediary", e);
		}

		try (MappingWriter writer = MappingWriter.create(tinyMappings, MappingFormat.TINY_2)) {
			MappingReader.read(tinyV1, writer);
		}

		tmpFolder.close();

		project.getLogger().lifecycle(":generated dummy intermediary in " + stopwatch.stop());
	}

	@Override
	public String getName() {
		return "legacy-intermediate";
	}
}
