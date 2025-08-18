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

import java.nio.charset.StandardCharsets
import java.nio.file.Path

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import net.fabricmc.loom.configuration.providers.forge.ForgeRunTemplate
import net.fabricmc.loom.util.ZipUtils
import net.fabricmc.loom.util.download.Download

class ForgeRunTemplateTest extends Specification {
	@TempDir
	Path temp

	private JsonObject downloadForgeConfig(String mc, String forge) {
		def version = "$mc-$forge"
		def url = "https://maven.minecraftforge.net/net/minecraftforge/forge/$version/forge-$version-userdev.jar"
		def targetPath = temp.resolve("forge-$version-userdev.jar")
		Download.create(url).downloadPath(targetPath)
		def text = new String(ZipUtils.unpack(targetPath, 'config.json'), StandardCharsets.UTF_8)
		return new Gson().fromJson(text, JsonObject)
	}

	@Unroll
	def "test #gameVersion #forgeVersion"() {
		setup:
		def json = downloadForgeConfig(gameVersion, forgeVersion)

		when:
		def result = ForgeRunTemplate.CODEC.parse(JsonOps.INSTANCE, json.getAsJsonObject("runs").getAsJsonObject("client"))
		def template = result.getOrThrow(false) { }

		then:
		template.name == template.name() // check that the name gradle sees matches the name read from the json
		template.name() == 'client'
		template.main() == mainClass

		where:
		gameVersion | forgeVersion | mainClass
		'1.14.4'    | '28.2.26'    | 'net.minecraftforge.userdev.LaunchTesting'
		'1.15.2'    | '31.2.57'    | 'net.minecraftforge.userdev.LaunchTesting'
		'1.16.5'    | '36.2.39'    | 'net.minecraftforge.userdev.LaunchTesting'
		'1.17.1'    | '37.1.1'     | 'cpw.mods.bootstraplauncher.BootstrapLauncher'
		'1.18.2'    | '40.1.80'    | 'cpw.mods.bootstraplauncher.BootstrapLauncher'
		'1.19.2'    | '43.1.25'    | 'cpw.mods.bootstraplauncher.BootstrapLauncher'
	}
}
