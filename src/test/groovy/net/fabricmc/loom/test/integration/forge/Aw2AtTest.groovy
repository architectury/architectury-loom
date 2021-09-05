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

import net.fabricmc.loom.test.util.ArchiveAssertionsTrait
import net.fabricmc.loom.test.util.ProjectTestTrait
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class Aw2AtTest extends Specification implements ProjectTestTrait, ArchiveAssertionsTrait {
    @Override
    String name() {
        "forge/aw2At"
    }

    def build() {
        when:
        def result = create("build", DEFAULT_GRADLE)
        then:
        result.task(":build").outcome == SUCCESS
        getArchiveEntry("fabric-example-mod-1.0.0.jar", "META-INF/accesstransformer.cfg") == expected().replaceAll('\r', '')
    }

    String expected() {
        return new File(testProjectDir, "expected.accesstransformer.cfg").text
    }
}
