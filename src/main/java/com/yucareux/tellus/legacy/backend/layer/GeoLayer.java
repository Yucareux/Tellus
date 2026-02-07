package com.yucareux.tellus.legacy.backend.layer;

import com.yucareux.tellus.legacy.backend.GeoView;
import com.yucareux.tellus.legacy.backend.raster.RasterShape;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface GeoLayer<V> {
    CompletableFuture<Optional<V>> get(GeoView sourceView, RasterShape outputShape);

    default CompletableFuture<Optional<V>> getExact(final GeoView view) {
        return get(view, view.shape());
    }
}
