package net.fabricmc.loom.configuration.providers.forge.fg2;

import java.io.InputStream;
import java.util.jar.JarOutputStream;

public interface Pack200Provider {
	void unpack(InputStream inputStream, JarOutputStream outputStream);
}
