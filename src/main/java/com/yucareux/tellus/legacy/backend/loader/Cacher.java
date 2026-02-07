package com.yucareux.tellus.legacy.backend.loader;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public interface Cacher<K, V> {
    CompletableFuture<Optional<V>> getOrLoad(K key, Supplier<CompletableFuture<Optional<V>>> loader);

    default <K1> Cacher<K1, V> mapKey(final Function<K1, K> function) {
        return (key, loader) -> getOrLoad(function.apply(key), loader);
    }
}
