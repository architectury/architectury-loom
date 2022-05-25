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

package net.fabricmc.loom.configuration.providers.forge.minecraft;

import java.nio.file.Path;
import java.util.List;

import org.gradle.api.Project;

import net.fabricmc.loom.configuration.providers.forge.MinecraftPatchedProvider;
import net.fabricmc.loom.configuration.providers.minecraft.SingleJarMinecraftProvider;

public final class SingleJarForgeMinecraftProvider extends SingleJarMinecraftProvider implements ForgeMinecraftProvider {
	private final MinecraftPatchedProvider patchedProvider;

	private SingleJarForgeMinecraftProvider(Project project, SingleJarMinecraftProvider.Environment environment) {
		super(project, environment);
		this.patchedProvider = new MinecraftPatchedProvider(project, this, provideServer() ? MinecraftPatchedProvider.Type.SERVER_ONLY : MinecraftPatchedProvider.Type.CLIENT_ONLY);
	}

	public static SingleJarForgeMinecraftProvider server(Project project) {
		return new SingleJarForgeMinecraftProvider(project, new Server());
	}

	public static SingleJarForgeMinecraftProvider client(Project project) {
		return new SingleJarForgeMinecraftProvider(project, new Client());
	}

	@Override
	protected boolean provideClient() {
		// the client jar is needed for client-extra which the Forge userdev launch thing always checks for
		return true;
	}

	@Override
	protected void processJar() throws Exception {
		patchedProvider.provide();
	}

	@Override
	public MinecraftPatchedProvider getPatchedProvider() {
		return patchedProvider;
	}

	@Override
	public boolean requiresPatchProvider() {
		return true;
	}

	@Override
	public Path getMinecraftEnvOnlyJar() {
		return patchedProvider.getMinecraftPatchedJar();
	}

	@Override
	public List<Path> getMinecraftJars() {
		return List.of(patchedProvider.getMinecraftPatchedJar());
	}
}
