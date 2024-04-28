/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2024 FabricMC
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

package net.fabricmc.loom.test.integration.neoforge

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class NeoForge1205Test extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "build #mcVersion #neoforgeVersion #mappings"() {
		if (Integer.valueOf(System.getProperty("java.version").split("\\.")[0]) < 21) return // Do not run this test with Java 17

		setup:
		def gradle = gradleProject(project: "neoforge/1205", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', mcVersion)
				.replace('@NEOFORGEVERSION@', neoforgeVersion)
				.replace('MAPPINGS', mappings) // Spotless doesn't like the @'s
				.replace('PATCHES', patches)

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome == SUCCESS

		where:
		mcVersion | neoforgeVersion | mappings | patches
		'1.20.5-rc1'  | '20.5.0-alpha.1.20.5-rc1.20240422.033338' | 'loom.officialMojangMappings()' | ''
		'1.20.5-rc1'  | '20.5.0-alpha.1.20.5-rc1.20240422.033338' | "'net.fabricmc:yarn:1.20.5-rc1+build.3:v2'" | "'dev.architectury:yarn-mappings-patch-neoforge:1.20.5+build.3'"
	}
}
