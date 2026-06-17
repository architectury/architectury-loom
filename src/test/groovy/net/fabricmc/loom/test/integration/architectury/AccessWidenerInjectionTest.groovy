/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
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

package net.fabricmc.loom.test.integration.architectury

import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.STANDARD_TEST_VERSIONS
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class AccessWidenerInjectionTest extends Specification implements GradleProjectTestTrait {
	private static final String INJECTED_AW_FILE_NAME = 'hello_world.accesswidener'
	private static final String INJECTED_AW_CONTENTS_REMAP = """\
		accessWidener v1 named
		accessible field net/minecraft/ChatFormatting code C
		"""
	.stripIndent()
	private static final String INJECTED_AW_CONTENTS_NO_REMAP = """\
		accessWidener v1 official
		accessible field net/minecraft/ChatFormatting code C
		"""
	.stripIndent()

	@Unroll
	def "inject access widener (old api, remap, gradle #version)"() {
		setup:
		def gradle = gradleProject(project: "minimalBase", version: version)
		gradle.buildGradle << """
			dependencies {
				minecraft 'com.mojang:minecraft:1.21.11'
				mappings loom.officialMojangMappings()
			}
			loom.accessWidenerPath = file('$INJECTED_AW_FILE_NAME')
			tasks.named('remapJar') {
				injectAccessWidener = true
			}
			"""
		new File(gradle.projectDir, 'src/main/resources').mkdirs()
		new File(gradle.projectDir, 'src/main/resources/fabric.mod.json').text = """
			{
				"schemaVersion": 1,
				"id": "hello_world",
				"version": "1"
			}
			"""
		new File(gradle.projectDir, INJECTED_AW_FILE_NAME).text = INJECTED_AW_CONTENTS_REMAP

		when:
		def result = gradle.run(task: 'build')

		then:
		result.task(':build').outcome == SUCCESS
		new JsonSlurper().parseText(gradle.getOutputZipEntry('fabric-example-mod-1.0.0.jar', 'fabric.mod.json')).accessWidener == INJECTED_AW_FILE_NAME
		gradle.getOutputZipEntry('fabric-example-mod-1.0.0.jar', INJECTED_AW_FILE_NAME) == 'accessWidener\tv1\tintermediary\naccessible\tfield\tnet/minecraft/class_124\tfield_1059\tC\n'

		where:
		version << STANDARD_TEST_VERSIONS
	}

	@Unroll
	def "inject access widener (new api, remap, gradle #version)"() {
		setup:
		def gradle = gradleProject(project: "minimalBase", version: version)
		gradle.buildGradle << """
			dependencies {
				minecraft 'com.mojang:minecraft:1.21.11'
				mappings loom.officialMojangMappings()
			}
			loom.accessWidenerPath = file('$INJECTED_AW_FILE_NAME')
			loom.injectAccessWidener(tasks.named('remapJar'))
			"""
		new File(gradle.projectDir, 'src/main/resources').mkdirs()
		new File(gradle.projectDir, 'src/main/resources/fabric.mod.json').text = """
			{
				"schemaVersion": 1,
				"id": "hello_world",
				"version": "1"
			}
			"""
		new File(gradle.projectDir, INJECTED_AW_FILE_NAME).text = INJECTED_AW_CONTENTS_REMAP

		when:
		def result = gradle.run(task: 'build')

		then:
		result.task(':build').outcome == SUCCESS
		new JsonSlurper().parseText(gradle.getOutputZipEntry('fabric-example-mod-1.0.0.jar', 'fabric.mod.json')).accessWidener == INJECTED_AW_FILE_NAME
		gradle.getOutputZipEntry('fabric-example-mod-1.0.0.jar', INJECTED_AW_FILE_NAME) == 'accessWidener\tv1\tintermediary\naccessible\tfield\tnet/minecraft/class_124\tfield_1059\tC\n'

		where:
		version << STANDARD_TEST_VERSIONS
	}

	@Unroll
	def "inject access widener (no remap, gradle #version)"() {
		setup:
		def gradle = gradleProject(project: "minimalBaseNoRemap", version: version)
		gradle.buildGradle << """
			dependencies {
				minecraft 'com.mojang:minecraft:26.2'
			}
			loom.accessWidenerPath = file('$INJECTED_AW_FILE_NAME')
			loom.injectAccessWidener(tasks.named('jar'))
			"""
		new File(gradle.projectDir, 'src/main/resources').mkdirs()
		new File(gradle.projectDir, 'src/main/resources/fabric.mod.json').text = """
			{
				"schemaVersion": 1,
				"id": "hello_world",
				"version": "1"
			}
			"""
		new File(gradle.projectDir, INJECTED_AW_FILE_NAME).text = INJECTED_AW_CONTENTS_NO_REMAP

		when:
		def result = gradle.run(task: 'build')

		then:
		result.task(':build').outcome == SUCCESS
		new JsonSlurper().parseText(gradle.getOutputZipEntry('fabric-example-mod-1.0.0.jar', 'fabric.mod.json')).accessWidener == INJECTED_AW_FILE_NAME
		gradle.getOutputZipEntry('fabric-example-mod-1.0.0.jar', INJECTED_AW_FILE_NAME) == INJECTED_AW_CONTENTS_NO_REMAP

		where:
		version << STANDARD_TEST_VERSIONS
	}
}
