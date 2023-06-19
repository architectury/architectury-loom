package dev.architectury.loom.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.List;

import net.fabricmc.mappingio.FlatMappingVisitor;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.RegularAsFlatMappingVisitor;

// mapping-io's tsrg reader is awful and doesn't support the flat format that mixin uses
public final class BetterTsrg1Reader {
	public static void read(Reader reader, MappingVisitor visitor, String from, String to) throws IOException {
		final FlatMappingVisitor flat = new RegularAsFlatMappingVisitor(visitor);
		final BufferedReader br = new BufferedReader(reader);
		String currentClass = null;
		String line;

		if (flat.visitHeader()) {
			flat.visitNamespaces(from, List.of(to));
		}

		if (flat.visitContent()) {
			while ((line = br.readLine()) != null) {
				if (line.isEmpty() || line.startsWith("#")) continue;

				if (line.startsWith("\t")) {
					if (line.startsWith("\t\t")) continue;
					final String[] parts = line.split(" ");

					if (parts.length == 3) {
						flat.visitMethod(currentClass, parts[0], parts[1], parts[2]);
					} else if (parts.length == 2) {
						flat.visitField(currentClass, parts[0], null, parts[1]);
					}
				}

				final String[] parts = line.split(" ");

				if (parts.length == 2) {
					flat.visitClass(parts[0], parts[1]);
					currentClass = parts[0];
				} else if (parts.length == 3) {
					flat.visitField(parts[0], parts[1], null, parts[2]);
				} else if (parts.length == 4) {
					flat.visitMethod(parts[0], parts[1], parts[3], parts[2]);
				}
			}
		}
	}
}
