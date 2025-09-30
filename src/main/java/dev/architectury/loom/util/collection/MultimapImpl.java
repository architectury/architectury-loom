package dev.architectury.loom.util.collection;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class MultimapImpl<K, V, C extends Collection<V>> implements Multimap.Specialized<K, V, C> {
	private final Map<K, C> backing;
	private final Supplier<C> collectionBuilder;
	private final C empty;

	MultimapImpl(Map<K, C> backing, Supplier<C> collectionBuilder, C empty) {
		this.backing = backing;
		this.collectionBuilder = collectionBuilder;
		this.empty = empty;
	}

	@Override
	public C get(K key) {
		return Objects.requireNonNullElse(backing.get(key), empty);
	}

	private C getContainer(K key) {
		return backing.computeIfAbsent(key, unused -> collectionBuilder.get());
	}

	@Override
	public void put(K key, V value) {
		getContainer(key).add(value);
	}

	@Override
	public void putAll(K key, Collection<? extends V> values) {
		getContainer(key).addAll(values);
	}

	@Override
	public Set<K> keySet() {
		return backing.keySet();
	}

	@Override
	public Map<K, ? extends C> asMap() {
		return backing;
	}

	@Override
	public Set<Map.Entry<K, C>> entrySet() {
		return backing.entrySet();
	}
}
