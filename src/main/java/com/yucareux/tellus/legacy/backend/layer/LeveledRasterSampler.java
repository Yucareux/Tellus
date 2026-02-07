package com.yucareux.tellus.legacy.backend.layer;

import com.yucareux.tellus.legacy.backend.raster.Raster;

import java.util.Comparator;
import java.util.List;

public class LeveledRasterSampler<V extends Raster> {
    // Interpolation filling in every second block is okay, but try to not upsample
    // more than that
    private static final int MAX_UPSAMPLE_FACTOR = 2;
    // It's okay to load data at a higher resolution than we need and downsample,
    // but we need some practical limit
    private static final int MAX_DOWNSAMPLE_FACTOR = 4;

    private final List<RasterSampler<V>> levels;

    public LeveledRasterSampler(final List<RasterSampler<V>> levels) {
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("Cannot create LeveledRasterSampler with no levels");
        }
        this.levels = levels.stream()
                .sorted(Comparator.comparingLong(value -> (long) value.width() * value.height()))
                .toList();
    }

    @SafeVarargs
    public LeveledRasterSampler(final RasterSampler<V>... levels) {
        this(List.of(levels));
    }

    public RasterSampler<V> choose(final double blocksPerDegreeX, final double blocksPerDegreeY) {
        for (final RasterSampler<V> level : levels) {
            if (wouldOversample(level, blocksPerDegreeX, blocksPerDegreeY)) {
                return level;
            }
            if (wouldUndersample(level, blocksPerDegreeX, blocksPerDegreeY)) {
                continue;
            }
            return level;
        }
        return levels.getLast();
    }

    public RasterSampler<V> maxLevel() {
        return levels.getLast();
    }

    private boolean wouldUndersample(final RasterSampler<V> level, final double blocksPerDegreeX,
            final double blocksPerDegreeY) {
        final double maxBlocksPerDegreeX = level.width() / 360.0 * MAX_UPSAMPLE_FACTOR;
        final double maxBlocksPerDegreeY = level.height() / 180.0 * MAX_UPSAMPLE_FACTOR;
        return blocksPerDegreeX > maxBlocksPerDegreeX || blocksPerDegreeY > maxBlocksPerDegreeY;
    }

    private boolean wouldOversample(final RasterSampler<V> level, final double blocksPerDegreeX,
            final double blocksPerDegreeY) {
        final double minBlocksPerDegreeX = level.width() / 360.0 / MAX_DOWNSAMPLE_FACTOR;
        final double minBlocksPerDegreeY = level.height() / 180.0 / MAX_DOWNSAMPLE_FACTOR;
        return blocksPerDegreeX <= minBlocksPerDegreeX || blocksPerDegreeY <= minBlocksPerDegreeY;
    }
}
