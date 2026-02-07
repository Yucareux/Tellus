package com.yucareux.tellus.legacy.backend.raster;

public interface Raster {
    RasterType<? extends Raster> type();

    RasterShape shape();

    default int width() {
        return shape().width();
    }

    default int height() {
        return shape().height();
    }

    default void copyFrom(final Raster raster) {
        Raster.checkSameShape(this, raster);
        copyFrom(raster, 0, 0);
    }

    default void copyFrom(final Raster raster, final int x0, final int y0) {
        if (x0 < 0 || y0 < 0) {
            throw new IllegalArgumentException(x0 + ", " + y0 + " is out of bounds for raster");
        }

        final int x1 = x0 + raster.width();
        final int y1 = y0 + raster.height();
        if (x1 > width() || y1 > height()) {
            throw new IllegalArgumentException(
                    "Cannot fit " + raster.shape() + " into " + shape() + " at " + x0 + ", " + y0);
        }

        copyFromClipped(raster, x0, y0);
    }

    void copyFromClipped(Raster raster, int x0, int y0);

    static void checkSameShape(final Raster left, final Raster right) {
        if (!left.shape().equals(right.shape())) {
            throw new IllegalArgumentException(
                    "Mismatched shape between rasters: " + left.shape() + " is not " + right.shape());
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Raster> RasterType<T> type(final T raster) {
        return (RasterType<T>) raster.type();
    }
}
