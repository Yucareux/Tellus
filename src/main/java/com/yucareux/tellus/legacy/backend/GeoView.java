package com.yucareux.tellus.legacy.backend;

import com.yucareux.tellus.legacy.backend.raster.RasterShape;

public record GeoView(int x0, int z0, int x1, int z1) {
    public int width() {
        return x1 - x0 + 1;
    }

    public int height() {
        return z1 - z0 + 1;
    }

    public GeoView floorDiv(final RasterShape shape) {
        return new GeoView(
                Math.floorDiv(x0, shape.width()),
                Math.floorDiv(z0, shape.height()),
                Math.floorDiv(x1, shape.width()),
                Math.floorDiv(z1, shape.height()));
    }

    public RasterShape shape() {
        return new RasterShape(width(), height());
    }
}
