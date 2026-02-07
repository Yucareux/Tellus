package com.yucareux.tellus.legacy.backend.projection.cylindrical;

import com.yucareux.tellus.legacy.backend.GeoView;
import com.yucareux.tellus.legacy.backend.raster.Raster;

public interface Resampler<R extends Raster> {
    <V extends R> void resample(V source, V target, float scaleX, float scaleY, float offsetX, float offsetY, int seedX,
            int seedY);

    GeoView extend(GeoView view);
}
