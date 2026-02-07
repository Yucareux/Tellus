package com.yucareux.tellus.legacy.backend.projection.cylindrical;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.legacy.backend.earth.EarthConstants;

public class Equirectangular implements CylindricalProjection {
    public static final MapCodec<Equirectangular> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.DOUBLE.fieldOf("meters_per_block").forGetter(e -> e.metersPerBlock)).apply(i, Equirectangular::new));

    private final double metersPerBlock;
    private final int blocksX;
    private final int blocksZ;

    public Equirectangular(final double metersPerBlock) {
        this.metersPerBlock = metersPerBlock;
        blocksX = (int) Math.floor(EarthConstants.CIRCUMFERENCE_EQUATOR / metersPerBlock);
        blocksZ = blocksX / 2;
    }

    @Override
    public Type type() {
        return Type.EQUIRECTANGULAR;
    }

    @Override
    public float idealMetersPerBlock() {
        return (float) metersPerBlock;
    }

    @Override
    public double blockX(final double lon) {
        return lon / 360.0 * blocksX;
    }

    @Override
    public double blockZ(final double lat) {
        return -lat / 180.0 * blocksZ;
    }

    @Override
    public double lon(final double blockX) {
        return blockX / blocksX * 360.0;
    }

    @Override
    public double lat(final double blockZ) {
        return -blockZ / blocksZ * 180.0;
    }
}
