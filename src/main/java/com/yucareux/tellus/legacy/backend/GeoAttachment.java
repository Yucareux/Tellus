package com.yucareux.tellus.legacy.backend;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.yucareux.tellus.legacy.backend.raster.Raster;
import com.yucareux.tellus.legacy.backend.raster.RasterType;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Map;

public class GeoAttachment<V> {
    private static final Map<String, GeoAttachment<?>> REGISTRY = new Object2ObjectOpenHashMap<>();

    public static final Codec<GeoAttachment<?>> CODEC = Codec.STRING.comapFlatMap(
            id -> {
                final GeoAttachment<?> attachment = REGISTRY.get(id);
                return attachment != null ? DataResult.success(attachment)
                        : DataResult.error(() -> "Unrecognized attachment with id: " + id);
            },
            attachment -> attachment.id);

    private final String id;
    private final Codec<V> codec;

    private GeoAttachment(final String id, final Codec<V> codec) {
        this.id = id;
        this.codec = codec;
    }

    public static <V extends Raster> GeoAttachment<V> register(final String id, final RasterType<V> rasterType) {
        return register(id, rasterType.codec());
    }

    public static <V> GeoAttachment<V> register(final String id, final Codec<V> codec) {
        final GeoAttachment<V> attachment = new GeoAttachment<>(id, codec);
        if (REGISTRY.putIfAbsent(id, attachment) != null) {
            throw new IllegalArgumentException("Attachment is already registered with id: " + id);
        }
        return attachment;
    }

    public Codec<V> codec() {
        return codec;
    }

    @Override
    public String toString() {
        return id;
    }
}
