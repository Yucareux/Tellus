package com.yucareux.tellus.legacy.backend.projection;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.yucareux.tellus.legacy.backend.earth.GeoCoords;
import com.yucareux.tellus.legacy.backend.layer.GeoLayer;
import com.yucareux.tellus.legacy.backend.layer.LeveledRasterSampler;
import com.yucareux.tellus.legacy.backend.projection.cylindrical.Equirectangular;
import com.yucareux.tellus.legacy.backend.projection.cylindrical.Mercator;
import com.yucareux.tellus.legacy.backend.raster.EnumRaster;
import com.yucareux.tellus.legacy.backend.raster.IntLikeRaster;
import com.yucareux.tellus.legacy.backend.util.Util;

import java.util.concurrent.Executor;

public interface Projection {
    Codec<Projection> CODEC = Type.CODEC.dispatch(Projection::type, type -> type.codec);

    Type type();

    float idealMetersPerBlock();

    double blockX(double lat, double lon);

    default double blockX(final GeoCoords coords) {
        return blockX(coords.lat(), coords.lon());
    }

    double blockZ(double lat, double lon);

    default double blockZ(final GeoCoords coords) {
        return blockZ(coords.lat(), coords.lon());
    }

    double lat(double blockX, double blockZ);

    double lon(double blockX, double blockZ);

    <V extends IntLikeRaster> GeoLayer<V> createInterpolatedLayer(LeveledRasterSampler<V> leveledSampler,
            Executor executor);

    <E extends Enum<E>, V extends EnumRaster<E>> GeoLayer<V> createVoronoiLayer(LeveledRasterSampler<V> leveledSampler,
            Executor executor);

    enum Type {
        EQUIRECTANGULAR("equirectangular", Equirectangular.CODEC),
        MERCATOR("mercator", Mercator.CODEC),
        ;

        public static final Codec<Type> CODEC = Util.stringLookupCodec(values(), type -> type.key);

        private final String key;
        private final MapCodec<? extends Projection> codec;

        Type(final String key, final MapCodec<? extends Projection> codec) {
            this.key = key;
            this.codec = codec;
        }
    }
}
