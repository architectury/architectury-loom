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

package net.fabricmc.loom.test.unit.forge

import java.nio.file.Files
import java.nio.file.Path

import org.gradle.api.Project
import spock.lang.Specification
import spock.lang.TempDir

import net.fabricmc.loom.api.processor.SpecContext
import net.fabricmc.loom.configuration.accesstransformer.AccessTransformerJarProcessor
import net.fabricmc.loom.util.ZipUtils
import net.fabricmc.loom.util.fmj.FabricModJsonFactory

class AccessTransformerJarProcessorTest extends Specification {
	private static final String TEST_ACCESS_TRANSFORMER = 'public-f net.minecraft.world.level.block.IronBarsBlock m_54217_(Lnet/minecraft/world/level/block/state/BlockState;Z)Z'

	@TempDir
	Path tempDir

	def "consistent spec hash"() {
		given:
		// Set up mods.toml and access transformer
		def modDir = tempDir.resolve("mod")
		def jarPath = tempDir.resolve("mod.jar")
		def metaInf = modDir.resolve('META-INF')
		Files.createDirectories(metaInf)
		metaInf.resolve('accesstransformer.cfg').text = TEST_ACCESS_TRANSFORMER
		metaInf.resolve('mods.toml').text = '[[mods]]\nmodId="hello"'
		ZipUtils.pack(modDir, jarPath)

		// Create processor and context
		def processor = new AccessTransformerJarProcessor('at', Mock(Project), [])
		def modJson = FabricModJsonFactory.createFromZip(jarPath)
		def context = Mock(SpecContext)
		context.localMods() >> [modJson]
		when:
		def spec = processor.buildSpec(context)
		then:
		spec.hashCode() == 1575235360
	}
}
