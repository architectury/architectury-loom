/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
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

class ForgeRunConfigTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "verify run configs #mcVersion #forgeVersion"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', mcVersion)
				.replace('@FORGEVERSION@', forgeVersion)
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', javaVersion)
		gradle.buildGradle << """
		tasks.register('verifyRunConfigs') {
			doLast {
				loom.runs.each {
					it.evaluateNow()
					def expected = '$mainClass'
					def found = it.mainClass.get()
					if (expected != found) {
						throw new AssertionError("\$it.name: found main class \$found, expected \$expected")
					}
				}
			}
		}
		""".stripIndent()

		when:
		def result = gradle.run(task: "verifyRunConfigs", configurationCache: false)

		then:
		result.task(":verifyRunConfigs").outcome == SUCCESS

		where:
		mcVersion | forgeVersion | javaVersion | mainClass
		'1.19.4'  | "45.0.43"    | '17'        | 'cpw.mods.bootstraplauncher.BootstrapLauncher'
		'1.18.1'  | "39.0.63"    | '17'        | 'cpw.mods.bootstraplauncher.BootstrapLauncher'
		'1.17.1'  | "37.0.67"    | '16'        | 'cpw.mods.bootstraplauncher.BootstrapLauncher'
		'1.16.5'  | "36.2.4"     | '8'         | 'net.minecraftforge.userdev.LaunchTesting'
		'1.14.4'  | "28.2.23"    | '8'         | 'net.minecraftforge.userdev.LaunchTesting'
	}

	def "verify mod classes"() {
		setup:
		def gradle = gradleProject(project: "forge/simple", version: DEFAULT_GRADLE)
		gradle.buildGradle.text = gradle.buildGradle.text.replace('@MCVERSION@', '1.19.4')
				.replace('@FORGEVERSION@', "45.0.43")
				.replace('@MAPPINGS@', 'loom.officialMojangMappings()')
				.replace('@REPOSITORIES@', '')
				.replace('@PACKAGE@', 'net.minecraftforge:forge')
				.replace('@JAVA_VERSION@', '17')
		gradle.buildGradle << '''
		sourceSets {
			testMod {}
		}

		loom {
			runs {
				testMod {
					client()
					mods {
						main { sourceSet 'main' }
						testMod { sourceSet 'testMod' }
					}
				}
			}
		}

		tasks.register('verifyRunConfigs') {
			doLast {
				def client = loom.runs.client
				client.evaluateNow()
				def clientClasses = client.environmentVariables.get('MOD_CLASSES')
				if (!clientClasses.contains('main%%')) {
					throw new AssertionError("MOD_CLASSES=clientClasses missing main classes")
				} else if (clientClasses.contains('testMod%%')) {
					throw new AssertionError("MOD_CLASSES=$clientClasses containing test mod classes")
				}

				def testMod = loom.runs.testMod
				testMod.evaluateNow()
				def testModClasses = testMod.environmentVariables.get('MOD_CLASSES')
				if (!testModClasses.contains('main%%') || !testModClasses.contains('testMod%%')) {
					throw new AssertionError("MOD_CLASSES=$testModClasses missing required entries")
				}
			}
		}
		'''.stripIndent()

		when:
		def result = gradle.run(task: "verifyRunConfigs", configurationCache: false)

		then:
		result.task(":verifyRunConfigs").outcome == SUCCESS
	}
}
