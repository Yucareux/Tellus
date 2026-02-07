package com.yucareux.tellus.legacy.backend.raster.reader;

import com.yucareux.tellus.legacy.backend.raster.ByteRaster;
import com.yucareux.tellus.legacy.backend.raster.IntLikeRaster;
import com.yucareux.tellus.legacy.backend.raster.RasterShape;
import com.yucareux.tellus.legacy.backend.raster.ShortRaster;
import com.yucareux.tellus.legacy.backend.raster.UnsignedByteRaster;

import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;

public final class RasterFormat<T extends IntLikeRaster> {
    public static final RasterFormat<UnsignedByteRaster> UNSIGNED_BYTE = new RasterFormat<>(UnsignedByteRaster::create,
            (buffer, shape) -> {
                final byte[] bytes = new byte[shape.size()];
                buffer.get(bytes);
                return UnsignedByteRaster.wrap(shape, bytes);
            });

    public static final RasterFormat<ByteRaster> BYTE = new RasterFormat<>(ByteRaster::create, (buffer, shape) -> {
        final byte[] bytes = new byte[shape.size()];
        buffer.get(bytes);
        return ByteRaster.wrap(shape, bytes);
    });

    public static final RasterFormat<ShortRaster> SHORT = new RasterFormat<>(ShortRaster::create, (buffer, shape) -> {
        final short[] values = new short[shape.size()];
        buffer.asShortBuffer().get(values);
        return ShortRaster.wrap(shape, values);
    });

    private final Function<RasterShape, T> factory;
    private final Reader<T> reader;

    private RasterFormat(final Function<RasterShape, T> factory, final Reader<T> reader) {
        this.factory = factory;
        this.reader = reader;
    }

    public T create(final RasterShape shape) {
        return factory.apply(shape);
    }

    public T read(final ByteBuffer buffer, final RasterShape shape) throws IOException {
        return reader.read(buffer, shape);
    }

    @Nullable
    public static RasterFormat<?> byId(final int id) {
        return switch (id) {
            case 0 -> RasterFormat.UNSIGNED_BYTE;
            case 1 -> RasterFormat.BYTE;
            case 2 -> RasterFormat.SHORT;
            default -> null;
        };
    }

    interface Reader<T extends IntLikeRaster> {
        T read(ByteBuffer buffer, RasterShape shape) throws IOException;
    }
}
