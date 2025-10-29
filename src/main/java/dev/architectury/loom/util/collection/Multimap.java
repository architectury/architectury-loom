package dev.architectury.loom.util.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.stream.Stream;

import net.fabricmc.loom.util.Pair;

public interface Multimap<K, V> {
	static <K, V> Specialized<K, V, SequencedSet<V>> setMultimap() {
		return new MultimapImpl<>(new HashMap<>(), LinkedHashSet::new, Collections.emptySortedSet());
	}

	static <K, V> Specialized<K, V, List<V>> listMultimap() {
		return new MultimapImpl<>(new HashMap<>(), ArrayList::new, List.of());
	}

	Collection<V> get(K key);
	void put(K key, V value);
	void putAll(K key, Collection<? extends V> values);
	Set<K> keySet();
	Map<K, ? extends Collection<V>> asMap();
	Set<? extends Map.Entry<K, ? extends Collection<V>>> entrySet();

	default Stream<Pair<K, V>> streamEntries() {
		return entrySet().stream()
				.flatMap(entry -> entry.getValue().stream().map(value -> new Pair<>(entry.getKey(), value)));
	}

	interface Specialized<K, V, C extends Collection<V>> extends Multimap<K, V> {
		@Override
		C get(K key);

		@Override
		Map<K, ? extends C> asMap();

		@Override
		Set<Map.Entry<K, C>> entrySet();
	}
}
