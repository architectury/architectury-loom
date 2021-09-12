/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

import net.fabricmc.loom.test.util.GradleProjectTestTrait
import spock.lang.Specification

import static net.fabricmc.loom.test.LoomTestConstants.*
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class Aw2AtTest extends Specification implements GradleProjectTestTrait {
    def "build"() { // 1.17+ uses a new srg naming pattern
		setup:
			def gradle = gradleProject(project: "forge/aw2At", version: DEFAULT_GRADLE)

		when:
			def result = gradle.run(task: "build")

		then:
			result.task(":build").outcome == SUCCESS
			gradle.getOutputZipEntry("fabric-example-mod-1.0.0.jar", "META-INF/accesstransformer.cfg") == expected(gradle).replaceAll('\r', '')
    }

	def "legacy build (mojmap)"() { // old 1.16 srg names
		setup:
			def gradle = gradleProject(project: "forge/legacyAw2AtMojmap", version: DEFAULT_GRADLE)

		when:
			def result = gradle.run(task: "build")

		then:
			result.task(":build").outcome == SUCCESS
			gradle.getOutputZipEntry("fabric-example-mod-1.0.0.jar", "META-INF/accesstransformer.cfg") == expected(gradle).replaceAll('\r', '')
	}

	def "legacy build (yarn)"() { // old 1.16 srg names
		setup:
			def gradle = gradleProject(project: "forge/legacyAw2AtYarn", version: DEFAULT_GRADLE)

		when:
			def result = gradle.run(task: "build")

		then:
			result.task(":build").outcome == SUCCESS
			gradle.getOutputZipEntry("fabric-example-mod-1.0.0.jar", "META-INF/accesstransformer.cfg") == expected(gradle).replaceAll('\r', '')
	}

	private static String expected(GradleProject gradle) {
		return new File(gradle.projectDir, "expected.accesstransformer.cfg").text
	}
}
