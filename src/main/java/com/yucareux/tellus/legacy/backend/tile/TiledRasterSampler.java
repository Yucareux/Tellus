package com.yucareux.tellus.legacy.backend.tile;

import com.yucareux.tellus.legacy.backend.GeoView;
import com.yucareux.tellus.legacy.backend.layer.RasterSampler;
import com.yucareux.tellus.legacy.backend.raster.Raster;
import com.yucareux.tellus.legacy.backend.raster.RasterShape;
import com.yucareux.tellus.legacy.backend.raster.RasterType;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class TiledRasterSampler<V extends Raster> implements RasterSampler<V> {
    private final TileMap<V> map;
    private final RasterType<V> rasterType;
    private final Executor executor;

    public TiledRasterSampler(final TileMap<V> map, final RasterType<V> rasterType, final Executor executor) {
        this.map = map;
        this.rasterType = rasterType;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Optional<V>> get(final GeoView view) {
        final RasterShape tileShape = map.tileShape();
        final GeoView tileView = view.floorDiv(tileShape);
        if (!map.contains(tileView.x0(), tileView.z0(), tileView.x1(), tileView.z1())) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        final int tileCountX = tileView.width();
        final int tileCountZ = tileView.height();
        @SuppressWarnings("unchecked")
        final CompletableFuture<Optional<V>>[] futures = new CompletableFuture[tileCountX * tileCountZ];
        for (int tileZ = 0; tileZ < tileCountZ; tileZ++) {
            for (int tileX = 0; tileX < tileCountX; tileX++) {
                final TileKey key = new TileKey(tileX + tileView.x0(), tileZ + tileView.z0());
                futures[tileX + tileZ * tileCountX] = map.load(key);
            }
        }

        return CompletableFuture.allOf(futures).thenApplyAsync(unused -> {
            final V result = rasterType.create(view.shape());
            for (int tileZ = 0; tileZ < tileCountZ; tileZ++) {
                for (int tileX = 0; tileX < tileCountX; tileX++) {
                    final Optional<V> raster = futures[tileX + tileZ * tileCountX].join();
                    if (raster.isEmpty()) {
                        return Optional.empty();
                    }
                    final int absoluteTileX = (tileX + tileView.x0()) * tileShape.width();
                    final int absoluteTileZ = (tileZ + tileView.z0()) * tileShape.height();
                    result.copyFromClipped(raster.get(), absoluteTileX - view.x0(), absoluteTileZ - view.z0());
                }
            }
            return Optional.of(result);
        }, executor);
    }

    @Override
    public int width() {
        return map.width();
    }

    @Override
    public int height() {
        return map.height();
    }
}
