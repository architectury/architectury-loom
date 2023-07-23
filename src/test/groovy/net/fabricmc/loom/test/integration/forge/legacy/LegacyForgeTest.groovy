/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2023 FabricMC
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

package net.fabricmc.loom.test.integration.forge.legacy;

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.DEFAULT_GRADLE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class LegacyForgeTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "build #mcVersion #forgeVersion #mappings"() {
		setup:
		def gradle = gradleProject(project: "forge/legacy/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', mcVersion)
				.replace('@FORGEVERSION@', forgeVersion)
				.replace('@MAPPINGS@', mappings)

		when:
		def result = gradle.run(task: "build")

		then:
		result.task(":build").outcome == SUCCESS

		where:
		mcVersion | forgeVersion | mappings
		'1.12.2'  | '14.23.5.2816'        | '"de.oceanlabs.mcp:mcp_snapshot:20170615-1.12"'
		'1.12.2'  | '14.23.0.2486'        | '"de.oceanlabs.mcp:mcp_snapshot:20170615-1.12"'
		'1.11.2'  | '13.20.1.2588'        | '"de.oceanlabs.mcp:mcp_snapshot:20170612-1.11"'
		'1.10.2'  | '12.18.3.2511'        | '"de.oceanlabs.mcp:mcp_snapshot:20161117-1.10.2"'
		'1.9.4'   | '12.17.0.2317-1.9.4'  | '"de.oceanlabs.mcp:mcp_snapshot:20160627-1.9.4"'
		'1.8.9'   | '11.15.1.2318-1.8.9'  | '"de.oceanlabs.mcp:mcp_snapshot:20160301-1.8.9"'
//		'1.7.10'  | '10.13.4.1614-1.7.10' | '"de.oceanlabs.mcp:mcp_snapshot:20140925-1.7.10"' // TODO: there is no POM in 1.7.10
	}
}
