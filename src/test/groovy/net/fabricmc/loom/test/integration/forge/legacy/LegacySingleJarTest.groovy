package net.fabricmc.loom.test.integration.forge.legacy

import spock.lang.Specification
import spock.lang.Unroll

import net.fabricmc.loom.test.util.CartesianProduct
import net.fabricmc.loom.test.util.GradleProjectTestTrait

import static net.fabricmc.loom.test.LoomTestConstants.STANDARD_TEST_VERSIONS
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class LegacySingleJarTest extends Specification implements GradleProjectTestTrait {
	@Unroll
	def "build single jar mc (mc #mc, forge #forge, env #env, gradle #version)"() {
		setup:
		def gradle = gradleProject(project: 'forge/legacy/singleJar', version: version)
		gradle.buildGradle.text = gradle.buildGradle.text
				.replace('@MCVERSION@', mc)
				.replace('@FORGEVERSION@', forge)
				.replace('@ENV@', env)
				.replace('@MAPPINGS@', mappings)

		when:
		def result = gradle.run(task: 'build')

		then:
		result.task(':build').outcome == SUCCESS

		where:
		[mc, forge, mappings, env, version] << CartesianProduct.addValuesToEach(
				[
			['1.12.2', "14.23.0.2486", '"de.oceanlabs.mcp:mcp_snapshot:20170615-1.12"'],
		],
		['client', 'server'],
		STANDARD_TEST_VERSIONS
		)
	}
}
