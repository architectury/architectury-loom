package dev.architectury.loom.metadata;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.ifaceinject.InterfaceInjectionProcessor;

public final class ArchitecturyCommonJson implements ModMetadataFile {
	private static final String ACCESS_WIDENER_KEY = "accessWidener";

	private final JsonObject json;

	private ArchitecturyCommonJson(JsonObject json) {
		this.json = Objects.requireNonNull(json, "json");
	}

	public static ArchitecturyCommonJson of(byte[] utf8) {
		return of(new String(utf8, StandardCharsets.UTF_8));
	}

	public static ArchitecturyCommonJson of(String text) {
		return of(LoomGradlePlugin.GSON.fromJson(text, JsonObject.class));
	}

	public static ArchitecturyCommonJson of(Path path) throws IOException {
		return of(Files.readString(path, StandardCharsets.UTF_8));
	}

	public static ArchitecturyCommonJson of(File file) throws IOException {
		return of(file.toPath());
	}

	public static ArchitecturyCommonJson of(JsonObject json) {
		return new ArchitecturyCommonJson(json);
	}

	@Override
	public @Nullable String getAccessWidener() {
		if (json.has(ACCESS_WIDENER_KEY)) {
			return json.get(ACCESS_WIDENER_KEY).getAsString();
		} else {
			return null;
		}
	}

	@Override
	public List<InterfaceInjectionProcessor.InjectedInterface> getInjectedInterfaces(@Nullable String modId) {
		if (modId == null) {
			throw new IllegalArgumentException("visitInjectedInterfaces: mod ID has to be provided for architectury.common.json");
		}

		return getInjectedInterfaces(json, modId);
	}

	static List<InterfaceInjectionProcessor.InjectedInterface> getInjectedInterfaces(JsonObject json, String modId) {
		Objects.requireNonNull(modId, "mod ID");

		if (json.has("injected_interfaces")) {
			JsonObject addedIfaces = json.getAsJsonObject("injected_interfaces");

			final List<InterfaceInjectionProcessor.InjectedInterface> result = new ArrayList<>();

			for (String className : addedIfaces.keySet()) {
				final JsonArray ifaceNames = addedIfaces.getAsJsonArray(className);

				for (JsonElement ifaceName : ifaceNames) {
					result.add(new InterfaceInjectionProcessor.InjectedInterface(modId, className, ifaceName.getAsString()));
				}
			}

			return result;
		}

		return Collections.emptyList();
	}
}
