package com.yucareux.tellus.legacy.backend.raster;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class ByteRaster implements IntLikeRaster {
    public static final Codec<ByteRaster> CODEC = RecordCodecBuilder.create(i -> i.group(
            RasterShape.CODEC.forGetter(ByteRaster::shape),
            RasterBufferCodecs.BYTES.fieldOf("data").forGetter(r -> r.buffer)).apply(i, ByteRaster::new));
    public static final RasterType<ByteRaster> TYPE = RasterType.create(ByteRaster::create, CODEC);

    protected final RasterShape shape;
    protected final byte[] buffer;

    protected ByteRaster(final RasterShape shape, final byte[] buffer) {
        this.shape = shape;
        this.buffer = buffer;
    }

    public static ByteRaster create(final RasterShape shape) {
        final byte[] buffer = new byte[shape.size()];
        return new ByteRaster(shape, buffer);
    }

    public static ByteRaster wrap(final RasterShape shape, final byte[] buffer) {
        return new ByteRaster(shape, buffer);
    }

    public void putByte(final int x, final int y, final byte value) {
        buffer[shape.index(x, y)] = value;
    }

    public byte getByte(final int x, final int y) {
        return buffer[shape.index(x, y)];
    }

    public void copyFrom(final ByteRaster raster) {
        Raster.checkSameShape(this, raster);
        System.arraycopy(raster.buffer, 0, buffer, 0, buffer.length);
    }

    @Override
    public RasterType<ByteRaster> type() {
        return TYPE;
    }

    @Override
    public RasterShape shape() {
        return shape;
    }

    @Override
    public void copyFrom(final Raster raster) {
        if (raster instanceof final ByteRaster byteRaster) {
            copyFrom(byteRaster);
        } else {
            IntLikeRaster.super.copyFrom(raster);
        }
    }

    @Override
    public void putInt(final int x, final int y, final int value) {
        putByte(x, y, (byte) (value & 0xff));
    }

    @Override
    public int getInt(final int x, final int y) {
        return getByte(x, y);
    }
}
