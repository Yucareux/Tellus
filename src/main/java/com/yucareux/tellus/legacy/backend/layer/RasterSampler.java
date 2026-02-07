package com.yucareux.tellus.legacy.backend.layer;

import com.yucareux.tellus.legacy.backend.GeoView;
import com.yucareux.tellus.legacy.backend.raster.Raster;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface RasterSampler<V extends Raster> {
    CompletableFuture<Optional<V>> get(GeoView view);

    int width();

    int height();
}
