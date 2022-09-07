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

package net.fabricmc.loom.test.unit

import net.fabricmc.loom.decompilers.linemap.LineMapClassFilter
import net.fabricmc.loom.decompilers.linemap.LineMapReader
import net.fabricmc.loom.decompilers.linemap.LineMapWriter
import spock.lang.Specification

class LineMapTest extends Specification {
	LineMapReader reader = new LineMapReader(LineMapTest.getResourceAsStream('/linemap/input.lmap').newReader('UTF-8'))

	private String readExpected(String name) {
		return LineMapTest.getResourceAsStream("/linemap/${name}.lmap")
				.getText('UTF-8')
				.replace('\r\n', '\n')
	}

	def "roundtrip"() {
		when:
			def sw = new StringWriter()
			reader.accept(new LineMapWriter(sw))
			reader.close()
		then:
			sw.toString() == readExpected('simpleOutput')
	}

	def "filter"() {
		when:
			def sw = new StringWriter()
			def writer = new LineMapWriter(sw)
			reader.accept(new LineMapClassFilter(writer, { it.startsWith('test/nested/') }))
			reader.close()
		then:
			sw.toString() == readExpected('filteredOutput')
	}
}
