/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2026 FabricMC
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

package net.fabricmc.loom.test.integration.forge

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.util.Checksum

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static net.fabricmc.loom.test.LoomTestConstants.STANDARD_TEST_VERSIONS
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class Aw2AtTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "build (#apiVariant)"() {
		// 1.17+ uses a new srg naming pattern
		setup:
		def gradle = gradleProject(project: "forge/aw2At", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('AW2AT_CODE', code)

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome == SUCCESS
		gradle.getOutputZipEntry("fabric-example-mod-1.0.0.jar", "META-INF/accesstransformer.cfg") == expected(gradle).replaceAll('\r', '')
		hash(gradle.getOutputFile("fabric-example-mod-1.0.0.jar")) == '2c35db7629e0bda5e79001615751e99c'

		where:
		apiVariant | code
		'legacy'   | 'convertAccessWideners = true'
		'new'      | 'convertAccessWideners(tasks.named("remapJar"), "my.accesswidener")'
	}

	@Unroll
	def "legacy build (mojmap, gradle #version)"() {
		// old 1.16 srg names
		setup:
		def gradle = gradleProject(project: "forge/legacyAw2AtMojmap", version: DEFAULT_GRADLE)

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome == SUCCESS
		gradle.getOutputZipEntry("fabric-example-mod-1.0.0.jar", "META-INF/accesstransformer.cfg") == expected(gradle).replaceAll('\r', '')
		hash(gradle.getOutputFile("fabric-example-mod-1.0.0.jar")) == '714f7823923fc90d06bb601f32955458'

		where:
		version << STANDARD_TEST_VERSIONS
	}

	@Unroll
	def "legacy build (yarn, gradle #version)"() {
		// old 1.16 srg names
		setup:
		def gradle = gradleProject(project: "forge/legacyAw2AtYarn", version: DEFAULT_GRADLE)

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome == SUCCESS
		gradle.getOutputZipEntry("fabric-example-mod-1.0.0.jar", "META-INF/accesstransformer.cfg") == expected(gradle).replaceAll('\r', '')
		hash(gradle.getOutputFile("fabric-example-mod-1.0.0.jar")) == '714f7823923fc90d06bb601f32955458'

		where:
		version << STANDARD_TEST_VERSIONS
	}

	private static String expected(GradleProject gradle) {
		return new File(gradle.projectDir, "expected.accesstransformer.cfg").text
	}

	private static String hash(File file) {
		return Checksum.of(file).md5().hex()
	}
}
