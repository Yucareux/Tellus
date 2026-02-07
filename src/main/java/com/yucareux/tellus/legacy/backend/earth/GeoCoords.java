package com.yucareux.tellus.legacy.backend.earth;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record GeoCoords(double lat, double lon) {
    public static final Codec<GeoCoords> GOOGLE_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("lat").forGetter(GeoCoords::lat),
            Codec.DOUBLE.fieldOf("lng").forGetter(GeoCoords::lon)).apply(i, GeoCoords::new));
}
