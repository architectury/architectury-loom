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

package net.fabricmc.loom.extension;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

import net.fabricmc.loom.api.ForgeExtensionAPI;

public class ForgeExtensionImpl implements ForgeExtensionAPI {
	private final Property<Boolean> convertAccessWideners;
	private final SetProperty<String> extraAccessWideners;
	private final ConfigurableFileCollection accessTransformers;
	private final SetProperty<String> mixinConfigs;
	private final Property<Boolean> useFabricMixin;

	@Inject
	public ForgeExtensionImpl(Project project) {
		convertAccessWideners = project.getObjects().property(Boolean.class).convention(false);
		extraAccessWideners = project.getObjects().setProperty(String.class).empty();
		accessTransformers = project.getObjects().fileCollection();
		mixinConfigs = project.getObjects().setProperty(String.class).empty();
		useFabricMixin = project.getObjects().property(Boolean.class).convention(true);
	}

	@Override
	public Property<Boolean> getConvertAccessWideners() {
		return convertAccessWideners;
	}

	@Override
	public SetProperty<String> getExtraAccessWideners() {
		return extraAccessWideners;
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
	public SetProperty<String> getMixinConfigs() {
		return mixinConfigs;
	}

	@Override
	public void mixinConfigs(String... mixinConfigs) {
		this.mixinConfigs.addAll(mixinConfigs);
	}

	@Override
	public Property<Boolean> getUseFabricMixin() {
		return useFabricMixin;
	}
}
