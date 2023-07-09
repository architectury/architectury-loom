package dev.architectury.loom.metadata;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.ifaceinject.InterfaceInjectionProcessor;

public final class McModInfo implements JsonArrayBackedModMetadataFile, ModMetadataFile {
	public static final String FILE_PATH = "mcmod.info";
	private final JsonArray json;

	private McModInfo(JsonArray json) {
		this.json = Objects.requireNonNull(json, "json");
	}

	public static McModInfo of(byte[] utf8) {
		return of(new String(utf8, StandardCharsets.UTF_8));
	}

	public static McModInfo of(String text) {
		return of(LoomGradlePlugin.GSON.fromJson(text, JsonArray.class));
	}

	public static McModInfo of(Path path) throws IOException {
		return of(Files.readString(path, StandardCharsets.UTF_8));
	}

	public static McModInfo of(File file) throws IOException {
		return of(file.toPath());
	}

	public static McModInfo of(JsonArray json) {
		return new McModInfo(json);
	}

	@Override
	public JsonArray getJson() {
		return json;
	}

	@Override
	public Set<String> getIds() {
		return json.asList().stream().map(elem -> elem.getAsJsonObject().getAsJsonPrimitive("modid").getAsString()).collect(Collectors.toUnmodifiableSet());
	}

	@Override
	public Set<String> getAccessWideners() {
		return Set.of();
	}

	@Override
	public List<InterfaceInjectionProcessor.InjectedInterface> getInjectedInterfaces(@Nullable String modId) {
		return List.of();
	}

	@Override
	public String getFileName() {
		return FILE_PATH;
	}

	@Override
	public List<String> getMixinConfigs() {
		return List.of();
	}
}
