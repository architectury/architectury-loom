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

package net.fabricmc.loom.configuration.providers.forge;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.Named;

import net.fabricmc.loom.configuration.ide.RunConfigSettings;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.function.CollectionUtil;

public record ForgeRunTemplate(
		String name,
		String main,
		List<ConfigValue> args,
		List<ConfigValue> jvmArgs,
		Map<String, ConfigValue> env,
		Map<String, ConfigValue> props
) implements Named {
	@Override
	public String getName() {
		return name;
	}

	public void applyTo(RunConfigSettings settings, ConfigValue.Resolver configValueResolver) {
		if (settings.getDefaultMainClass().equals(Constants.Forge.UNDETERMINED_MAIN_CLASS)) {
			settings.defaultMainClass(main);
		}

		settings.vmArgs(CollectionUtil.map(jvmArgs, value -> value.resolve(configValueResolver)));

		env.forEach((key, value) -> {
			String resolved = value.resolve(configValueResolver);
			settings.getEnvironmentVariables().putIfAbsent(key, resolved);
		});

		// Add MOD_CLASSES, this is something that ForgeGradle does
		settings.getEnvironmentVariables().computeIfAbsent("MOD_CLASSES", $ -> ConfigValue.of("{source_roots}").resolve(configValueResolver));
	}

	public static ForgeRunTemplate fromJson(JsonObject json) {
		if (json.has("parents") && !json.getAsJsonArray("parents").isEmpty()) {
			throw new IllegalArgumentException("Non-empty parents for run config template not supported!");
		}

		String name = json.getAsJsonPrimitive("name").getAsString();
		String main = json.getAsJsonPrimitive("main").getAsString();
		List<ConfigValue> args = json.has("args") ? fromJson(json.getAsJsonArray("args")) : List.of();
		List<ConfigValue> jvmArgs = json.has("jvmArgs") ? fromJson(json.getAsJsonArray("jvmArgs")) : List.of();
		Map<String, ConfigValue> env = json.has("env") ? fromJson(json.getAsJsonObject("env"), ConfigValue::of) : Map.of();
		Map<String, ConfigValue> props = json.has("props") ? fromJson(json.getAsJsonObject("props"), ConfigValue::of) : Map.of();
		return new ForgeRunTemplate(name, main, args, jvmArgs, env, props);
	}

	private static List<ConfigValue> fromJson(JsonArray json) {
		return CollectionUtil.map(json, child -> ConfigValue.of(child.getAsJsonPrimitive().getAsString()));
	}

	private static <R> Map<String, R> fromJson(JsonObject json, Function<String, R> converter) {
		return json.entrySet().stream().map(entry -> {
			String value = entry.getValue().getAsJsonPrimitive().getAsString();
			return new Pair<>(entry.getKey(), converter.apply(value));
		}).collect(Collectors.toMap(Pair::left, Pair::right));
	}
}
