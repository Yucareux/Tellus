package com.yucareux.tellus.legacy.backend.raster;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record RasterShape(int width, int height) {
    public static final MapCodec<RasterShape> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.fieldOf("width").forGetter(RasterShape::width),
            Codec.INT.fieldOf("height").forGetter(RasterShape::height)).apply(i, RasterShape::new));

    public int index(final int x, final int y) {
        if (!contains(x, y)) {
            throw new IllegalArgumentException("Point (" + x + "; " + y + ") out of bounds for " + this);
        }
        return indexUnchecked(x, y);
    }

    public int indexUnchecked(final int x, final int y) {
        return x + y * width;
    }

    public boolean contains(final int x, final int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    public int size() {
        return width * height;
    }
}
