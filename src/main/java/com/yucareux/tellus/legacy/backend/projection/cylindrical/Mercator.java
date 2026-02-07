package com.yucareux.tellus.legacy.backend.projection.cylindrical;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.legacy.backend.earth.EarthConstants;

public class Mercator implements CylindricalProjection {
    public static final MapCodec<Mercator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.DOUBLE.fieldOf("meters_per_block").forGetter(e -> e.metersPerBlock)).apply(i, Mercator::new));

    private final double metersPerBlock;
    private final int blocksX;

    public Mercator(final double metersPerBlock) {
        this.metersPerBlock = metersPerBlock;
        blocksX = (int) Math.floor(EarthConstants.CIRCUMFERENCE_EQUATOR / metersPerBlock);
    }

    @Override
    public Type type() {
        return Type.MERCATOR;
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
        return projectY(Math.toRadians(lat)) / (2.0 * Math.PI) * blocksX;
    }

    @Override
    public double lon(final double blockX) {
        return blockX / blocksX * 360.0;
    }

    @Override
    public double lat(final double blockZ) {
        return Math.toDegrees(unprojectY(blockZ / blocksX * (2.0 * Math.PI)));
    }

    private static double projectY(final double lat) {
        return Math.log(Math.tan(Math.PI / 4.0 - lat / 2.0));
    }

    private static double unprojectY(final double y) {
        return (Math.PI / 4.0 - Math.atan(Math.exp(y))) * 2.0;
    }
}
