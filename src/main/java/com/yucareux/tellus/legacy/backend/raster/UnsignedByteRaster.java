package com.yucareux.tellus.legacy.backend.raster;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.function.IntFunction;

public class UnsignedByteRaster implements IntLikeRaster {
    public static final Codec<UnsignedByteRaster> CODEC = RecordCodecBuilder.create(i -> i.group(
            RasterShape.CODEC.forGetter(UnsignedByteRaster::shape),
            RasterBufferCodecs.BYTES.fieldOf("data").forGetter(r -> r.buffer)).apply(i, UnsignedByteRaster::new));
    public static final RasterType<UnsignedByteRaster> TYPE = RasterType.create(UnsignedByteRaster::create, CODEC);

    protected final RasterShape shape;
    protected final byte[] buffer;

    protected UnsignedByteRaster(final RasterShape shape, final byte[] buffer) {
        this.shape = shape;
        this.buffer = buffer;
    }

    public static UnsignedByteRaster create(final RasterShape shape) {
        final byte[] buffer = new byte[shape.size()];
        return new UnsignedByteRaster(shape, buffer);
    }

    public static UnsignedByteRaster wrap(final RasterShape shape, final byte[] buffer) {
        return new UnsignedByteRaster(shape, buffer);
    }

    public static UnsignedByteRaster copyOf(final IntLikeRaster raster) {
        final UnsignedByteRaster result = create(raster.shape());
        result.copyFrom(raster);
        return result;
    }

    public void putByte(final int x, final int y, final int value) {
        buffer[shape.index(x, y)] = (byte) (value & 0xff);
    }

    public int getByte(final int x, final int y) {
        return buffer[shape.index(x, y)] & 0xff;
    }

    public void copyFrom(final UnsignedByteRaster raster) {
        Raster.checkSameShape(this, raster);
        System.arraycopy(raster.buffer, 0, buffer, 0, buffer.length);
    }

    @Override
    public RasterType<? extends UnsignedByteRaster> type() {
        return TYPE;
    }

    @Override
    public RasterShape shape() {
        return shape;
    }

    @Override
    public void copyFrom(final Raster raster) {
        if (raster instanceof final UnsignedByteRaster byteRaster) {
            copyFrom(byteRaster);
        } else {
            IntLikeRaster.super.copyFrom(raster);
        }
    }

    @Override
    public void putInt(final int x, final int y, final int value) {
        putByte(x, y, value);
    }

    @Override
    public int getInt(final int x, final int y) {
        return getByte(x, y);
    }

    public <T extends Enum<T>> EnumRaster<T> mapToEnum(final RasterType<EnumRaster<T>> type,
            final IntFunction<T> function) {
        final EnumRaster<T> result = type.create(shape);
        for (int y = 0; y < shape.height(); y++) {
            for (int x = 0; x < shape.width(); x++) {
                result.put(x, y, function.apply(getByte(x, y)));
            }
        }
        return result;
    }
}
