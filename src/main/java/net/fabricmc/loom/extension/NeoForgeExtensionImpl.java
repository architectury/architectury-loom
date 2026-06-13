/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023-2026 FabricMC
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

package net.fabricmc.loom.extension;

import javax.inject.Inject;

import dev.architectury.loom.accesstransformer.Aw2At;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

import net.fabricmc.loom.api.NeoForgeExtensionAPI;
import net.fabricmc.loom.api.aw2at.Aw2AtSettings;

public abstract class NeoForgeExtensionImpl implements NeoForgeExtensionAPI {
	private final Project project;
	private final ConfigurableFileCollection accessTransformers;

	@Inject
	public NeoForgeExtensionImpl(Project project) {
		accessTransformers = project.getObjects().fileCollection();
		this.project = project;
	}

	@Override
	public ConfigurableFileCollection getAccessTransformers() {
		return accessTransformers;
	}

	@Override
	public void accessTransformer(Object file) {
		accessTransformers.from(file);
	}

	@Override
	public void convertAccessWideners(TaskProvider<? extends Jar> jarTask, Action<? super Aw2AtSettings> action) {
		Aw2At.addToTask(project, jarTask, action);
	}
}
