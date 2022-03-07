/*
 * A Gradle plugin for the creation of Minecraft mods and MinecraftForge plugins.
 * Copyright (C) 2013 Minecraft Forge
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package net.fabricmc.loom.configuration.providers.forge.fg2;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.nothome.delta.GDiffPatcher;
import lzma.sdk.lzma.Decoder;
import lzma.streams.LzmaInputStream;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;

public class FG2TaskApplyBinPatches {
	private final HashMap<String, ClassPatch> patches = Maps.newHashMap();
	private final GDiffPatcher patcher = new GDiffPatcher();

	private Project project;

	public FG2TaskApplyBinPatches(Project project) {
		this.project = project;
	}

	public void doTask(File input, File patches, File output, String side) throws IOException {
		setup(patches, side);
		output.delete();

		final HashSet<String> entries = new HashSet<>();

		try (ZipFile in = new ZipFile(input);
		     ZipInputStream classesIn = new ZipInputStream(new FileInputStream(input));
		     ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
			// DO PATCHES
			log("Patching Class:");

			for (ZipEntry e : Collections.list(in.entries())) {
				if (e.getName().contains("META-INF")) {
					continue;
				}

				if (e.isDirectory()) {
					out.putNextEntry(e);
				} else {
					ZipEntry n = new ZipEntry(e.getName());
					n.setTime(e.getTime());
					out.putNextEntry(n);

					byte[] data = ByteStreams.toByteArray(in.getInputStream(e));
					ClassPatch patch = this.patches.get(e.getName().replace('\\', '/'));

					if (patch != null) {
						log("\t%s (%s) (input size %d)", patch.targetClassName, patch.sourceClassName, data.length);
						int inputChecksum = adlerHash(data);

						if (patch.inputChecksum != inputChecksum) {
							throw new RuntimeException(String.format("There is a binary discrepancy between the expected input class %s (%s) and the actual class. Checksum on disk is %x, in patch %x. Things are probably about to go very wrong. Did you put something into the jar file?", patch.targetClassName, patch.sourceClassName, inputChecksum, patch.inputChecksum));
						}

						synchronized (patcher) {
							data = patcher.patch(data, patch.patch);
						}
					}

					out.write(data);
				}

				// add the names to the hashset
				entries.add(e.getName());
			}

			// COPY DATA
			ZipEntry entry;

			while ((entry = classesIn.getNextEntry()) != null) {
				if (entries.contains(entry.getName())) {
					continue;
				}

				out.putNextEntry(entry);
				out.write(ByteStreams.toByteArray(classesIn));
				entries.add(entry.getName());
			}
		}
	}

	private static int adlerHash(byte[] input) {
		Adler32 hasher = new Adler32();
		hasher.update(input);
		return (int) hasher.getValue();
	}

	public void setup(File patches, String side) {
		Pattern matcher = Pattern.compile(String.format("binpatch/%s/.*.binpatch", side));

		JarInputStream jis;

		try {
			LzmaInputStream binpatchesDecompressed = new LzmaInputStream(new FileInputStream(patches), new Decoder());
			ByteArrayOutputStream jarBytes = new ByteArrayOutputStream();
			JarOutputStream jos = new JarOutputStream(jarBytes);
			Pack200Provider provider = LoomGradleExtension.get(project).getForge().getPack200Provider().get();

			if (provider == null) {
				throw new IllegalStateException("No provider for Pack200 has been found. Did you declare a provider?");
			}

			provider.unpack(binpatchesDecompressed, jos);
			jis = new JarInputStream(new ByteArrayInputStream(jarBytes.toByteArray()));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		log("Reading Patches:");

		do {
			try {
				JarEntry entry = jis.getNextJarEntry();

				if (entry == null) {
					break;
				}

				if (matcher.matcher(entry.getName()).matches()) {
					ClassPatch cp = readPatch(entry, jis);
					this.patches.put(cp.sourceClassName.replace('.', '/') + ".class", cp);
				} else {
					log("skipping entry: %s", entry.getName());
					jis.closeEntry();
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} while (true);
		log("Read %d binary patches", this.patches.size());
		log("Patch list :\n\t%s", Joiner.on("\n\t").join(this.patches.entrySet()));
	}

	private ClassPatch readPatch(JarEntry patchEntry, JarInputStream jis) throws IOException {
		log("\t%s", patchEntry.getName());
		ByteArrayDataInput input = ByteStreams.newDataInput(ByteStreams.toByteArray(jis));

		String name = input.readUTF();
		String sourceClassName = input.readUTF();
		String targetClassName = input.readUTF();
		boolean exists = input.readBoolean();
		int inputChecksum = 0;

		if (exists) {
			inputChecksum = input.readInt();
		}

		int patchLength = input.readInt();
		byte[] patchBytes = new byte[patchLength];
		input.readFully(patchBytes);

		return new ClassPatch(name, sourceClassName, targetClassName, exists, inputChecksum, patchBytes);
	}

	private void log(String format, Object... args) {
		project.getLogger().info(String.format(format, args));
	}

	public record ClassPatch(String name, String sourceClassName, String targetClassName, boolean existsAtTarget, int inputChecksum,
	                         byte[] patch) {

		@Override
		public String toString() {
			return String.format("%s : %s => %s (%b) size %d", name, sourceClassName, targetClassName, existsAtTarget, patch.length);
		}
	}
}
