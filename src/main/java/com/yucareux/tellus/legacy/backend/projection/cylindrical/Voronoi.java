package com.yucareux.tellus.legacy.backend.projection.cylindrical;

import com.yucareux.tellus.legacy.backend.GeoView;
import com.yucareux.tellus.legacy.backend.raster.EnumRaster;
import com.yucareux.tellus.legacy.backend.util.Util;

import java.util.Random;

public class Voronoi implements Resampler<EnumRaster<?>> {
    private static final int JITTER_SIZE = 32;
    private static final int JITTER_MASK = JITTER_SIZE - 1;

    private final float[] jitterTable;

    public Voronoi(final float jitterRadius, final long seed) {
        if (jitterRadius >= 1.0f) {
            throw new IllegalArgumentException("Jitter radius must be in range [0, 1)");
        }
        jitterTable = buildJitterTable(new Random(seed), jitterRadius);
    }

    private static float[] buildJitterTable(final Random random, final float radius) {
        final float[] table = new float[JITTER_SIZE * JITTER_SIZE * 2];
        for (int y = 0; y < JITTER_SIZE; y++) {
            for (int x = 0; x < JITTER_SIZE; x++) {
                final int idx = (x + y * JITTER_SIZE) * 2;
                table[idx] = random.nextFloat() * 2.0f * radius;
                table[idx + 1] = random.nextFloat() * 2.0f * radius;
            }
        }
        return table;
    }

    @Override
    public <V extends EnumRaster<?>> void resample(final V source, final V target, final float scaleX,
            final float scaleY, final float offsetX, final float offsetY, final int seedX, final int seedY) {
        resampleCap(source, target, scaleX, scaleY, offsetX, offsetY, seedX, seedY);
    }

    @SuppressWarnings("unchecked")
    private <T extends Enum<T>> void resampleCap(final EnumRaster<?> source, final EnumRaster<?> target,
            final float scaleX, final float scaleY, final float offsetX, final float offsetY, final int seedX,
            final int seedY) {
        resample0((EnumRaster<T>) source, (EnumRaster<T>) target, scaleX, scaleY, offsetX, offsetY, seedX, seedY);
    }

    private <T extends Enum<T>> void resample0(final EnumRaster<T> source, final EnumRaster<T> target,
            final float scaleX, final float scaleY, final float offsetX, final float offsetY, final int seedX,
            final int seedY) {
        if (target.width() <= source.width() && target.height() <= source.height()) {
            resampleNearest(source, target, scaleX, scaleY, offsetX, offsetY);
            return;
        }

        for (int targetY = 0; targetY < target.height(); targetY++) {
            for (int targetX = 0; targetX < target.width(); targetX++) {
                final float sourceX = targetX * scaleX + offsetX;
                final float sourceY = targetY * scaleY + offsetY;
                target.put(targetX, targetY, sample(source, sourceX, sourceY, seedX, seedY));
            }
        }
    }

    private static <T extends Enum<T>> void resampleNearest(final EnumRaster<T> source, final EnumRaster<T> target,
            final float scaleX, final float scaleY, final float offsetX, final float offsetY) {
        for (int targetY = 0; targetY < target.height(); targetY++) {
            for (int targetX = 0; targetX < target.width(); targetX++) {
                final float sourceX = targetX * scaleX + offsetX;
                final float sourceY = targetY * scaleY + offsetY;
                target.put(targetX, targetY, source.get(Util.floorInt(sourceX), Util.floorInt(sourceY)));
            }
        }
    }

    private <T extends Enum<T>> T sample(final EnumRaster<T> raster, final float x, final float y, final int seedX,
            final int seedY) {
        final int originX = Util.floorInt(x);
        final int originY = Util.floorInt(y);

        final int x0 = originX - 1;
        final int y0 = originY - 1;
        final int x1 = originX + 1;
        final int y1 = originY + 1;

        int pickedX = originX;
        int pickedY = originY;
        float pickedDistance = Float.MAX_VALUE;

        for (int cellY = y0; cellY <= y1; cellY++) {
            for (int cellX = x0; cellX <= x1; cellX++) {
                final float distance = getDistanceToCell(x, y, cellX, cellY, seedX, seedY);
                if (distance < pickedDistance) {
                    pickedDistance = distance;
                    pickedX = cellX;
                    pickedY = cellY;
                }
            }
        }

        return raster.get(pickedX, pickedY);
    }

    private float getDistanceToCell(final float x, final float y, final int cellX, final int cellY, final int seedX,
            final int seedY) {
        final int jitterIdx = (cellX + seedX & JITTER_MASK) + (cellY + seedY & JITTER_MASK) * JITTER_SIZE;
        final float cellOriginX = cellX + jitterTable[jitterIdx];
        final float cellOriginY = cellY + jitterTable[jitterIdx + 1];

        final float deltaX = cellOriginX - x;
        final float deltaY = cellOriginY - y;
        return deltaX * deltaX + deltaY * deltaY;
    }

    @Override
    public GeoView extend(final GeoView view) {
        return new GeoView(
                view.x0() - 1,
                view.z0() - 1,
                view.x1() + 1,
                view.z1() + 1);
    }
}
