package com.yucareux.tellus.legacy.backend.raster;

public interface IntLikeRaster extends Raster {
    @Override
    RasterType<? extends IntLikeRaster> type();

    @Override
    default void copyFrom(final Raster raster) {
        Raster.checkSameShape(this, raster);
        if (!(raster instanceof final IntLikeRaster intRaster)) {
            throw new IllegalArgumentException("Cannot copy from " + raster + " into IntLikeRaster");
        }

        final RasterShape shape = shape();
        for (int y = 0; y < shape.height(); y++) {
            for (int x = 0; x < shape.width(); x++) {
                putInt(x, y, intRaster.getInt(x, y));
            }
        }
    }

    @Override
    default void copyFromClipped(final Raster raster, final int x0, final int y0) {
        if (!(raster instanceof final IntLikeRaster intRaster)) {
            throw new IllegalArgumentException("Cannot copy from " + raster + " into IntLikeRaster");
        }

        if (x0 == 0 && y0 == 0 && shape().equals(raster.shape())) {
            copyFrom(raster);
            return;
        }

        final int x1 = Math.min(x0 + raster.width(), width());
        final int y1 = Math.min(y0 + raster.height(), height());

        for (int y = Math.max(y0, 0); y < y1; y++) {
            for (int x = Math.max(x0, 0); x < x1; x++) {
                putInt(x, y, intRaster.getInt(x - x0, y - y0));
            }
        }
    }

    void putInt(int x, int y, int value);

    int getInt(int x, int y);
}
