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

import dev.architectury.loom.forge.config.ConfigValue
import spock.lang.Specification

class ConfigValueTest extends Specification {
	def "bare value is constant"() {
		when:
		def value = ConfigValue.of('hello')
		then:
		value instanceof ConfigValue.Constant
		(value as ConfigValue.Constant).value() == 'hello'
	}

	def "value with opening brace is constant"() {
		when:
		def value = ConfigValue.of('{hello')
		then:
		value instanceof ConfigValue.Constant
		(value as ConfigValue.Constant).value() == '{hello'
	}

	def "value with closing brace is constant"() {
		when:
		def value = ConfigValue.of('hello}')
		then:
		value instanceof ConfigValue.Constant
		(value as ConfigValue.Constant).value() == 'hello}'
	}

	def "value with braces is variable"() {
		when:
		def value = ConfigValue.of('{hello}')
		then:
		value instanceof ConfigValue.Variable
		(value as ConfigValue.Variable).name() == 'hello'
	}

	def "resolving a constant should yield the constant"() {
		when:
		def value = ConfigValue.of('constant')
		then:
		value.resolve { throw new AssertionError('should not be called!' as Object) } == 'constant'
	}

	def "resolving a variable"() {
		when:
		def value = ConfigValue.of('{variable}')
		then:
		value.resolve { it.name().toUpperCase(Locale.ROOT) } == 'VARIABLE'
	}
}
