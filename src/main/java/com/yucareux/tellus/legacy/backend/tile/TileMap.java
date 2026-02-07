package com.yucareux.tellus.legacy.backend.tile;

import com.yucareux.tellus.legacy.backend.loader.Cacher;
import com.yucareux.tellus.legacy.backend.loader.Loader;
import com.yucareux.tellus.legacy.backend.raster.RasterShape;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class TileMap<V> implements Loader<TileKey, V> {
    private final int countX;
    private final int countY;
    private final RasterShape tileShape;
    private final Loader<TileKey, V> loader;

    public TileMap(final int countX, final int countY, final RasterShape tileShape, final Loader<TileKey, V> loader) {
        this.countX = countX;
        this.countY = countY;
        this.tileShape = tileShape;
        this.loader = loader;
    }

    public int width() {
        return countX * tileShape.width();
    }

    public int height() {
        return countY * tileShape.height();
    }

    public RasterShape tileShape() {
        return tileShape;
    }

    public boolean contains(final int x, final int y) {
        return contains(x, y, x, y);
    }

    public boolean contains(final int x0, final int y0, final int x1, final int y1) {
        return x0 >= 0 && y0 >= 0 && x1 < countX && y1 < countY;
    }

    @Override
    public CompletableFuture<Optional<V>> load(final TileKey key) {
        if (contains(key.x(), key.y())) {
            return loader.load(key);
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public TileMap<V> cached(final Cacher<TileKey, V> cacher) {
        return new TileMap<>(countX, countY, tileShape, Loader.super.cached(cacher));
    }
}
