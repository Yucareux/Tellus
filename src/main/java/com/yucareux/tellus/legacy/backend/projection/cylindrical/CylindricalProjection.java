package com.yucareux.tellus.legacy.backend.projection.cylindrical;

import com.yucareux.tellus.legacy.backend.GeoView;
import com.yucareux.tellus.legacy.backend.layer.GeoLayer;
import com.yucareux.tellus.legacy.backend.layer.LeveledRasterSampler;
import com.yucareux.tellus.legacy.backend.layer.RasterSampler;
import com.yucareux.tellus.legacy.backend.projection.Projection;
import com.yucareux.tellus.legacy.backend.raster.EnumRaster;
import com.yucareux.tellus.legacy.backend.raster.IntLikeRaster;
import com.yucareux.tellus.legacy.backend.raster.Raster;

import java.util.concurrent.Executor;

public interface CylindricalProjection extends Projection {
    double blockX(double lon);

    double blockZ(double lat);

    double lon(double blockX);

    double lat(double blockZ);

    default <V extends Raster> GeoLayer<V> createResamplingLayer(final ResamplerFactory<V> resamplerFactory,
            final LeveledRasterSampler<V> leveledSampler, final Executor executor) {
        return (sourceView, outputShape) -> {
            final double lon0 = lon(sourceView.x0());
            final double lon1 = lon(sourceView.x1() + 0.5);
            final double lat0 = lat(sourceView.z1() + 0.5);
            final double lat1 = lat(sourceView.z0());

            final double outputPixelsPerDegreeX = outputShape.width() / (lon1 - lon0);
            final double outputPixelsPerDegreeY = outputShape.height() / (lat1 - lat0);
            final RasterSampler<V> sampler = leveledSampler.choose(outputPixelsPerDegreeX, outputPixelsPerDegreeY);

            final double x0 = (lon0 + 180.0) / 360.0 * sampler.width();
            final double z0 = (90.0 - lat1) / 180.0 * sampler.height();
            final double x1 = (lon1 + 180.0) / 360.0 * sampler.width();
            final double z1 = (90.0 - lat0) / 180.0 * sampler.height();

            // Big approximation: the scale won't necessarily be the same over the full
            // sampled area, but it's good enough for now
            // TODO: This means we can't sample big areas at once, so it might become
            // relevant in the future
            final double outputToMapScaleX = (x1 - x0) / outputShape.width();
            final double outputToMapScaleZ = (z1 - z0) / outputShape.height();

            final Resampler<? super V> resampler = resamplerFactory
                    .create(Math.min(outputToMapScaleX, outputToMapScaleZ));

            final GeoView mapView = resampler.extend(new GeoView(
                    (int) Math.floor(x0),
                    (int) Math.floor(z0),
                    (int) Math.floor(x1),
                    (int) Math.floor(z1)));
            final float offsetX = (float) (x0 - mapView.x0());
            final float offsetZ = (float) (z0 - mapView.z0());

            return sampler.get(mapView).thenApplyAsync(
                    r -> r.map(source -> {
                        final V output = Raster.type(source).create(outputShape);
                        resampler.resample(source, output, (float) outputToMapScaleX, (float) outputToMapScaleZ,
                                offsetX, offsetZ, mapView.x0(), mapView.z0());
                        return output;
                    }),
                    executor);
        };
    }

    @Override
    default double blockX(final double lat, final double lon) {
        return blockX(lon);
    }

    @Override
    default double blockZ(final double lat, final double lon) {
        return blockZ(lat);
    }

    @Override
    default double lat(final double blockX, final double blockZ) {
        return lat(blockZ);
    }

    @Override
    default double lon(final double blockX, final double blockZ) {
        return lon(blockX);
    }

    @Override
    default <V extends IntLikeRaster> GeoLayer<V> createInterpolatedLayer(final LeveledRasterSampler<V> leveledSampler,
            final Executor executor) {
        return createResamplingLayer(
                InterpolationMode::choose,
                leveledSampler,
                executor);
    }

    @Override
    default <E extends Enum<E>, V extends EnumRaster<E>> GeoLayer<V> createVoronoiLayer(
            final LeveledRasterSampler<V> leveledSampler, final Executor executor) {
        final Voronoi voronoi = new Voronoi(0.45f, 2016969737595986194L);
        return createResamplingLayer(
                scale -> voronoi,
                leveledSampler,
                executor);
    }

    interface ResamplerFactory<V extends Raster> {
        Resampler<? super V> create(double scale);
    }
}
