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

package net.fabricmc.loom.api;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;

/**
 * Data for a mod built from project files in a dev environment.
 * This data is only used for run config generation (FML needs the paths to mod files).
 */
public class ForgeLocalMod implements Named {
	private final Project project;
	private final String name;
	private final List<Supplier<SourceSet>> sourceSets;

	/**
	 * Constructs a local mod.
	 *
	 * @param project    the project using this mod
	 * @param name       the unique name of this local mod (does not have to correspond to a mod ID)
	 * @param sourceSets the list of source set suppliers corresponding to this mod; must be mutable
	 */
	public ForgeLocalMod(Project project, String name, List<Supplier<SourceSet>> sourceSets) {
		this.project = project;
		this.name = name;
		this.sourceSets = sourceSets;
	}

	/**
	 * Adds source sets to this local mod.
	 *
	 * <p>The source sets are resolved like this:
	 * <ul>
	 * <li>a {@link SourceSet} is used as is</li>
	 * <li>all other objects will be converted to source set names with {@link String#valueOf(Object)} and
	 * fetched with {@code sourceSets.findByName(name)}</li>
	 * </ul>
	 *
	 * @param sourceSets the source sets
	 */
	public void add(Object... sourceSets) {
		for (Object sourceSet : sourceSets) {
			if (sourceSet instanceof SourceSet) {
				this.sourceSets.add(() -> (SourceSet) sourceSet);
			} else {
				this.sourceSets.add(() -> project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().findByName(String.valueOf(sourceSet)));
			}
		}
	}

	@Override
	public String getName() {
		return name;
	}

	public Stream<SourceSet> getSourceSets() {
		return sourceSets.stream().map(Supplier::get);
	}
}
