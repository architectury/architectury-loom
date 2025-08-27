/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

import spock.lang.Shared
import spock.lang.Specification

import net.fabricmc.loom.configuration.providers.forge.ConfigValue
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.DependencySet
import net.fabricmc.loom.configuration.providers.forge.mcpconfig.McpConfigStep

class DependencySetTest extends Specification {
	/*
	 orphanA
	 orphanB
	 root
	 -> childA1 -> childA2 --> childAB
	 -> childB             /
	 */
	@Shared List<McpConfigStep> allSteps = [
		new McpConfigStep('foo', 'orphanA', [:]),
		new McpConfigStep('foo', 'orphanB', [:]),
		new McpConfigStep('bar', 'root', [:]),
		new McpConfigStep('bar', 'childA1', [input: ConfigValue.of('{rootOutput}')]),
		new McpConfigStep('bar', 'childA2', [input: ConfigValue.of('{childA1Output}')]),
		new McpConfigStep('bar', 'childB', [input: ConfigValue.of('{rootOutput}')]),
		new McpConfigStep(
		'bar', 'childAB',
		[inputA: ConfigValue.of('{childA2Output}'), inputB: ConfigValue.of("{childBOutput}")]
		),
	]

	DependencySet dependencySet = new DependencySet(allSteps)

	def "single child"() {
		when:
		dependencySet.add('childAB')
		def executedSteps = dependencySet.buildExecutionSet()
		then:
		executedSteps.toList() == [
			'root',
			'childA1',
			'childA2',
			'childB',
			'childAB'
		]
	}

	def "multiple children"() {
		when:
		dependencySet.add('childA1')
		dependencySet.add('orphanB')
		def executedSteps = dependencySet.buildExecutionSet()
		then:
		executedSteps.toList() == ['orphanB', 'root', 'childA1']
	}

	def "skip rule"() {
		when:
		dependencySet.add('childAB')
		dependencySet.skip('childA2')
		def executedSteps = dependencySet.buildExecutionSet()
		then:
		executedSteps.toList() == ['root', 'childB', 'childAB']
	}

	def "ignore dependencies filter"() {
		when:
		dependencySet.add('childAB')
		dependencySet.ignoreDependenciesFilter = { it.name() == 'childA2' }
		def executedSteps = dependencySet.buildExecutionSet()
		then:
		executedSteps.toList() == [
			'root',
			'childA2',
			'childB',
			'childAB'
		]
	}
}
