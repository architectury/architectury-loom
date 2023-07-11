package net.fabricmc.loom.configuration.providers.forge.fg2;

import org.apache.commons.compress.java.util.jar.Pack200;

import java.io.IOException;
import java.io.InputStream;
import java.util.jar.JarOutputStream;

public class ApachePack200Provider implements Pack200Provider {
	@Override
	public void unpack(InputStream inputStream, JarOutputStream outputStream) {
		try {
			Pack200.newUnpacker().unpack(inputStream, outputStream);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
