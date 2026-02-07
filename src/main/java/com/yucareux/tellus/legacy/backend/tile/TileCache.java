package com.yucareux.tellus.legacy.backend.tile;

import com.yucareux.tellus.legacy.backend.loader.Cacher;

public interface TileCache {
    <V> Cacher<TileKey, V> createCacher(TileMap<V> map);
}
