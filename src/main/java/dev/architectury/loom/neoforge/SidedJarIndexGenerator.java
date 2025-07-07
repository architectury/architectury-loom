package dev.architectury.loom.neoforge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarSplitter;
import net.fabricmc.mappingio.tree.MappingTreeView;

public final class SidedJarIndexGenerator {
	private final Path clientJar;
	private final Path serverJar;
	private final MappingTreeView mappings;

	public SidedJarIndexGenerator(Path clientJar, Path serverJar, MappingTreeView mappings) {
		this.clientJar = clientJar;
		this.serverJar = serverJar;
		this.mappings = mappings;
	}

	public void split(FileDistConsumer consumer) throws IOException {
		Set<String> clientEntries = MinecraftJarSplitter.getJarEntries(clientJar);
		Set<String> serverEntries = MinecraftJarSplitter.getJarEntries(serverJar);

		consumeAll(consumer, "client", clientEntries, serverEntries);
		consumeAll(consumer, "server", serverEntries, clientEntries);
	}

	private void consumeAll(FileDistConsumer consumer, String dist, Set<String> distEntries, Set<String> otherEntries) {
		for (String entry : distEntries) {
			if (!otherEntries.contains(entry)) {
				consumer.acceptFile(processFilePath(entry), dist);
			}
		}
	}

	private String processFilePath(String filePath) {
		if (filePath.endsWith(".class")) {
			String className = filePath.substring(0, filePath.length() - ".class".length());
			MappingTreeView.ClassMappingView mapping = mappings.getClass(className);

			if (mapping != null) {
				String remappedName = mapping.getName(MappingsNamespace.NAMED.toString());

				if (remappedName != null) {
					return remappedName + ".class";
				}
			}
		}

		return filePath;
	}

	@FunctionalInterface
	public interface FileDistConsumer {
		void acceptFile(String filePath, String dist);
	}
}
