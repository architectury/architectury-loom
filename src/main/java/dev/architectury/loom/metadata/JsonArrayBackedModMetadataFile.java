package dev.architectury.loom.metadata;

import com.google.gson.JsonArray;

/**
 * A mod metadata file backed by a JSON object.
 */
public interface JsonArrayBackedModMetadataFile extends ModMetadataFile {
	/**
	 * {@return the backing JSON object of this mod metadata file}.
	 */
	JsonArray getJson();
}
