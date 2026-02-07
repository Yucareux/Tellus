package com.yucareux.tellus.legacy.backend.loader;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public interface Loader<K, V> {
    static <K, V> Loader<K, V> from(final Function<K, V> function) {
        return key -> CompletableFuture.completedFuture(Optional.of(function.apply(key)));
    }

    CompletableFuture<Optional<V>> load(K key);

    default <K1> Loader<K1, V> mapKey(final Function<K1, K> function) {
        return key -> load(function.apply(key));
    }

    default <K1> Loader<K1, V> compose(final Loader<K1, K> other) {
        return key -> other.load(key).thenCompose(result -> {
            if (result.isPresent()) {
                return load(result.get());
            }
            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    default Loader<K, V> cached(final Cacher<K, V> cacher) {
        return key -> cacher.getOrLoad(key, () -> load(key));
    }
}
