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

package net.fabricmc.loom.test.unit.forge

import dev.architectury.at.AccessChange
import dev.architectury.at.ModifierChange
import spock.lang.Specification

import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.loom.util.aw2at.Aw2At

class Aw2AtTest extends Specification {
	def "test accessible"() {
		when:
		def at = Aw2At.toAt(AccessWidenerReader.AccessType.ACCESSIBLE)

		then:
		at.access == AccessChange.PUBLIC
		// AW makes previously private methods also final, but this cannot be replicated
		// using Forge AT.
		at.final == ModifierChange.NONE
	}

	def "test extendable"() {
		when:
		def at = Aw2At.toAt(AccessWidenerReader.AccessType.EXTENDABLE)

		then:
		// AW makes previously private methods protected and does not change the visibility
		// of protected methods, but this cannot be replicated using Forge AT.
		at.access == AccessChange.PUBLIC
		at.final == ModifierChange.REMOVE
	}

	def "test mutable"() {
		when:
		def at = Aw2At.toAt(AccessWidenerReader.AccessType.MUTABLE)

		then:
		// The access change to public is needed because the Forge AT format cannot
		// have a bare "-f" modifier. Reusing the previous visibility needs bytecode analysis,
		// so this is good enough.
		at.access == AccessChange.PUBLIC
		at.final == ModifierChange.REMOVE
	}
}
