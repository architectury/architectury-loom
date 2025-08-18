/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2024 FabricMC
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

package net.fabricmc.loom.test.unit.forge

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.util.srg.ForgeMappingsMerger
import net.fabricmc.mappingio.MappingUtil
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter

class SrgMergerTest extends Specification {
	@TempDir
	Path mappingsDir

	def "test with proguard extras"() {
		def output = mappingsDir.resolve("output.tiny")
		def expected = readTestData("expectedOutput.tiny")
		def proguardInput = extractTempFile("proguard.txt")
		def extraMappings = new ForgeMappingsMerger.ExtraMappings(proguardInput, MappingFormat.PROGUARD_FILE, MappingUtil.NS_TARGET_FALLBACK, MappingUtil.NS_SOURCE_FALLBACK)

		when:
		merge(extraMappings, output)

		then:
		Files.readAllLines(output) == expected
	}

	def "test with srg extras"() {
		def output = mappingsDir.resolve("output.tiny")
		def expected = readTestData("expectedOutput.tiny")
		def extraInput = extractTempFile("extraInput.tsrg")
		def extraMappings = ForgeMappingsMerger.ExtraMappings.ofMojmapTsrg(extraInput)

		when:
		merge(extraMappings, output)

		then:
		Files.readAllLines(output) == expected
	}

	private def merge(ForgeMappingsMerger.ExtraMappings extraMappings, Path output) {
		def srgInput = extractTempFile("srgInput.tsrg")
		def tinyInput = extractTempFile("tinyInput.tiny")

		new Tiny2FileWriter(Files.newBufferedWriter(output), false).withCloseable { writer ->
			ForgeMappingsMerger.mergeSrg(srgInput, tinyInput, extraMappings, true).accept(writer)
		}
	}

	private InputStream openTestDataStream(String path) {
		return getClass().getResourceAsStream("/forge/testSrg/$path")
	}

	private List<String> readTestData(String path) {
		openTestDataStream(path).withCloseable { input ->
			assert input != null
			return input.readLines()
		}
	}

	private Path extractTempFile(String path) {
		def output = mappingsDir.resolve(path)

		openTestDataStream(path).withCloseable { input ->
			assert input != null
			Files.copy(input, output)
		}

		return output
	}
}
