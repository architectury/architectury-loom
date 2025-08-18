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

package net.fabricmc.loom.test.unit

import spock.lang.Specification

import net.fabricmc.loom.util.Version

class VersionTest extends Specification {
	def "version comparison"() {
		when:
		def compare = Version.parse(s1) <=> Version.parse(s2)
		// turns compare into 1 or 0 or -1
		compare = compare <=> 0

		then:
		compare == expected

		where:
		s1             | s2              | expected
		"1.1.1"        | "1.1.1"         | 0
		"1.1.1"        | "1.1.0"         | 1
		"1.1.0"        | "1.1"           | 0
		"1.0.0"        | "1"             | 0
		"1-"           | "1"             | -1
		"1.1.1"        | "1.1.2"         | -1
		"1.1.1"        | "1.1.1-"        | 1
		"1.1.1-beta"   | "1.1.1-alpha"   | 1
		"1.1.1-alpha"  | "1.1.1-beta"    | -1
		"1.1.1-beta.1" | "1.1.1-beta.2"  | -1
		"1.1.1-beta.1" | "1.1.1-beta.10" | -1
		"1.1.1+123"    | "1.1.1+567"     | 0
	}
}
