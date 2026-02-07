package com.yucareux.tellus.legacy.backend.raster;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class ShortRaster implements IntLikeRaster {
    public static final Codec<ShortRaster> CODEC = RecordCodecBuilder.create(i -> i.group(
            RasterShape.CODEC.forGetter(ShortRaster::shape),
            RasterBufferCodecs.SHORTS.fieldOf("data").forGetter(r -> r.buffer)).apply(i, ShortRaster::new));
    public static final RasterType<ShortRaster> TYPE = RasterType.create(ShortRaster::create, CODEC);

    protected final RasterShape shape;
    protected final short[] buffer;

    protected ShortRaster(final RasterShape shape, final short[] buffer) {
        this.shape = shape;
        this.buffer = buffer;
    }

    public static ShortRaster create(final RasterShape shape) {
        final short[] buffer = new short[shape.size()];
        return new ShortRaster(shape, buffer);
    }

    public static ShortRaster wrap(final RasterShape shape, final short[] buffer) {
        return new ShortRaster(shape, buffer);
    }

    public void putShort(final int x, final int y, final short value) {
        buffer[shape.index(x, y)] = value;
    }

    public short getShort(final int x, final int y) {
        return buffer[shape.index(x, y)];
    }

    public void copyFrom(final ShortRaster raster) {
        Raster.checkSameShape(this, raster);
        System.arraycopy(raster.buffer, 0, buffer, 0, buffer.length);
    }

    @Override
    public RasterType<ShortRaster> type() {
        return TYPE;
    }

    @Override
    public RasterShape shape() {
        return shape;
    }

    @Override
    public void copyFrom(final Raster raster) {
        if (raster instanceof final ShortRaster shortRaster) {
            copyFrom(shortRaster);
        } else {
            IntLikeRaster.super.copyFrom(raster);
        }
    }

    @Override
    public void putInt(final int x, final int y, final int value) {
        putShort(x, y, (short) (value & 0xffff));
    }

    @Override
    public int getInt(final int x, final int y) {
        return getShort(x, y);
    }
}
