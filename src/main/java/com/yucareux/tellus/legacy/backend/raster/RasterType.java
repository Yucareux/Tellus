package com.yucareux.tellus.legacy.backend.raster;

import com.mojang.serialization.Codec;

import java.util.function.Function;

public interface RasterType<T extends Raster> {
    static <T extends Raster> RasterType<T> create(final Function<RasterShape, T> factory, final Codec<T> codec) {
        return new RasterType<>() {
            @Override
            public T create(final RasterShape shape) {
                return factory.apply(shape);
            }

            @Override
            public Codec<T> codec() {
                return codec;
            }
        };
    }

    T create(RasterShape shape);

    Codec<T> codec();
}
