package com.yucareux.tellus.legacy.backend.earth;

import com.yucareux.tellus.legacy.backend.GeoChunk;
import com.yucareux.tellus.legacy.backend.GeoView;
import com.yucareux.tellus.legacy.backend.earth.cover.LegacyCover;
import com.yucareux.tellus.legacy.backend.layer.GeoLayer;
import com.yucareux.tellus.legacy.backend.projection.Projection;
import com.yucareux.tellus.legacy.backend.raster.EnumRaster;
import com.yucareux.tellus.legacy.backend.raster.RasterShape;
import com.yucareux.tellus.legacy.backend.raster.ShortRaster;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public record EarthLayers(
        GeoLayer<ShortRaster> elevation,
        GeoLayer<EnumRaster<LegacyCover>> landCover,
        Executor executor) implements GeoLayer<GeoChunk> {
    public static EarthLayers create(final EarthTiles tiles, final Projection projection, final Executor executor) {
        return new EarthLayers(
                projection.createInterpolatedLayer(tiles.elevation(), executor),
                projection.createVoronoiLayer(tiles.landCover(), executor),
                executor);
    }

    @Override
    public CompletableFuture<Optional<GeoChunk>> get(final GeoView sourceView, final RasterShape outputShape) {
        final GeoChunk.Builder builder = new GeoChunk.Builder()
                .put(EarthAttachments.ELEVATION, elevation.get(sourceView, outputShape))
                .put(EarthAttachments.LAND_COVER, landCover.get(sourceView, outputShape));

        return builder.build();
    }
}
