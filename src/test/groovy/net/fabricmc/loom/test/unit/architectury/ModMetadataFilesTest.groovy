/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023-2024 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.test.unit.architectury

import java.nio.file.Files
import java.nio.file.Path

import dev.architectury.loom.metadata.ArchitecturyCommonJson
import dev.architectury.loom.metadata.ErroringModMetadataFile
import dev.architectury.loom.metadata.ModMetadataFiles
import dev.architectury.loom.metadata.QuiltModJson
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.test.unit.forge.ModsTomlTest
import net.fabricmc.loom.util.ZipUtils
import net.fabricmc.loom.util.fmj.FabricModJsonFactory
import net.fabricmc.loom.util.fmj.ModMetadataFabricModJson

class ModMetadataFilesTest extends Specification {
	@TempDir
	Path zipContents

	@TempDir
	Path workingDir

	def "read nothing from jar"() {
		given:
		def jar = workingDir.resolve("my_mod.jar")
		zipContents.resolve('foo.txt').text = 'hello'
		ZipUtils.pack(zipContents, jar)
		when:
		def modMetadata = ModMetadataFiles.fromJar(jar)
		then:
		modMetadata == null
	}

	def "read nothing from directory"() {
		given:
		// unrelated file
		workingDir.resolve('foo.txt').text = 'hello'
		when:
		def modMetadata = ModMetadataFiles.fromDirectory(workingDir)
		then:
		modMetadata == null
	}

	def "read quilt.mod.json from jar"() {
		given:
		def jar = workingDir.resolve("my_mod.jar")
		zipContents.resolve('quilt.mod.json').text = '{}'
		ZipUtils.pack(zipContents, jar)
		when:
		def modMetadata = ModMetadataFiles.fromJar(jar)
		then:
		modMetadata instanceof QuiltModJson
	}

	def "read quilt.mod.json from directory"() {
		given:
		workingDir.resolve('quilt.mod.json').text = '{}'
		when:
		def modMetadata = ModMetadataFiles.fromDirectory(workingDir)
		then:
		modMetadata instanceof QuiltModJson
	}

	def "read architectury.common.json from jar"() {
		given:
		def jar = workingDir.resolve("my_mod.jar")
		zipContents.resolve('architectury.common.json').text = '{}'
		ZipUtils.pack(zipContents, jar)
		when:
		def modMetadata = ModMetadataFiles.fromJar(jar)
		then:
		modMetadata instanceof ArchitecturyCommonJson
	}

	def "read architectury.common.json from directory"() {
		given:
		workingDir.resolve('architectury.common.json').text = '{}'
		when:
		def modMetadata = ModMetadataFiles.fromDirectory(workingDir)
		then:
		modMetadata instanceof ArchitecturyCommonJson
	}

	def "read broken mods.toml from directory"() {
		given:
		Files.createDirectories(workingDir.resolve('META-INF'))
		workingDir.resolve('META-INF/mods.toml').text = ModsTomlTest.BROKEN_INPUT
		when:
		def modMetadata = ModMetadataFiles.fromDirectory(workingDir)
		then:
		modMetadata instanceof ErroringModMetadataFile
		modMetadata.fileName == 'mods.toml [erroring]'
	}

	def "read broken neoforge.mods.toml from directory"() {
		given:
		Files.createDirectories(workingDir.resolve('META-INF'))
		workingDir.resolve('META-INF/neoforge.mods.toml').text = ModsTomlTest.BROKEN_INPUT
		when:
		def modMetadata = ModMetadataFiles.fromDirectory(workingDir)
		then:
		modMetadata instanceof ErroringModMetadataFile
		modMetadata.fileName == 'neoforge.mods.toml [erroring]'
	}

	def "read fabric.mod.json from zip"() {
		given:
		def jar = workingDir.resolve("my_mod.jar")
		zipContents.resolve('fabric.mod.json').text = '''
			{
				"schemaVersion": 1,
				"id": "test",
				"version": 1
			}
			'''.stripIndent()
		zipContents.resolve('architectury.common.json').text = '{}'
		ZipUtils.pack(zipContents, jar)
		when:
		def fmj = FabricModJsonFactory.createFromZip(jar)
		then:
		!(fmj instanceof ModMetadataFabricModJson)
		fmj.id == 'test'
	}

	def "read fabric.mod.json from zip (nullable)"() {
		given:
		def jar = workingDir.resolve("my_mod.jar")
		zipContents.resolve('fabric.mod.json').text = '''
			{
				"schemaVersion": 1,
				"id": "test",
				"version": 1
			}
			'''.stripIndent()
		zipContents.resolve('architectury.common.json').text = '{}'
		ZipUtils.pack(zipContents, jar)
		when:
		def fmj = FabricModJsonFactory.createFromZipNullable(jar)
		then:
		fmj != null
		!(fmj instanceof ModMetadataFabricModJson)
		fmj.id == 'test'
	}
}
