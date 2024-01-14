/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 FabricMC
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

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class PatchedDecompileTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "decompile #mcVersion #forgeVersion"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.getGradleProperties().text = gradle.getGradleProperties().text.replace("@PLATFORM@", mcVersion == "1.12.2" || mcVersion == "1.8.9" ? "legacy_forge" : "forge")
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', mcVersion)
				.replace('@FORGEVERSION@', forgeVersion)
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')

		when:
		def result = gradle.run(task: "genForgePatchedSources")

		then:
		result.task(":genForgePatchedSources").outcome == SUCCESS

		where:
		mcVersion | forgeVersion
		'1.19.2'  | "43.1.1"
		'1.18.1'  | "39.0.63"
		'1.17.1'  | "37.0.67"
		'1.12.2'  | "14.23.0.2486"
		'1.8.9'   | "11.14.4.1563"
	}
}
