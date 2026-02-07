package com.yucareux.tellus.legacy.backend.tile;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.legacy.backend.loader.Cacher;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class GuavaTileCache implements TileCache {
    private final Cache<Key<?>, CompletableFuture<? extends Optional<?>>> cache;

    public GuavaTileCache(final Duration expiryTime, final int maximumSize) {
        cache = CacheBuilder.newBuilder()
                .expireAfterAccess(expiryTime)
                .maximumSize(maximumSize)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> Cacher<TileKey, V> createCacher(final TileMap<V> map) {
        return (key, loader) -> {
            try {
                return (CompletableFuture<Optional<V>>) cache.get(new Key<>(map, key), loader::get);
            } catch (final ExecutionException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private record Key<V>(TileMap<V> map, TileKey tile) {
    }
}
