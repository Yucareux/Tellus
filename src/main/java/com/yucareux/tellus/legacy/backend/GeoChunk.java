package com.yucareux.tellus.legacy.backend;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class GeoChunk {
    public static final Codec<GeoChunk> CODEC = Codec
            .<GeoAttachment<?>, Object>dispatchedMap(GeoAttachment.CODEC, GeoAttachment::codec).xmap(
                    map -> new GeoChunk(new Reference2ObjectOpenHashMap<>(map)),
                    geoChunk -> geoChunk.attachmentMap);

    public static final GeoChunk EMPTY = new GeoChunk(Reference2ObjectMaps.emptyMap());

    private final Reference2ObjectMap<GeoAttachment<?>, Object> attachmentMap;

    private GeoChunk(final Reference2ObjectMap<GeoAttachment<?>, Object> attachmentMap) {
        this.attachmentMap = attachmentMap;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public <V> V get(final GeoAttachment<V> attachment) {
        return (V) attachmentMap.get(attachment);
    }

    public <V> V getOrThrow(final GeoAttachment<V> attachment) {
        final V value = get(attachment);
        if (value == null) {
            throw new IllegalArgumentException("Attachment " + attachment + " was missing on chunk");
        }
        return value;
    }

    public boolean isEmpty() {
        return attachmentMap.isEmpty();
    }

    public Optional<GeoChunk> requireAll(final GeoAttachmentSet attachments) {
        return hasAll(attachments) ? Optional.of(this) : Optional.empty();
    }

    public boolean hasAll(final GeoAttachmentSet attachments) {
        for (final GeoAttachment<?> attachment : attachments) {
            if (!attachmentMap.containsKey(attachment)) {
                return false;
            }
        }
        return true;
    }

    public static class Builder {
        private final Map<GeoAttachment<?>, CompletableFuture<? extends Optional<?>>> attachmentMap = new Reference2ObjectOpenHashMap<>();

        public <V> Builder put(final GeoAttachment<V> attachment, final CompletableFuture<Optional<V>> value) {
            attachmentMap.put(attachment, value);
            return this;
        }

        public CompletableFuture<Optional<GeoChunk>> build() {
            @SuppressWarnings("unchecked")
            final CompletableFuture<Optional<?>>[] futures = attachmentMap.values().toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(futures).thenApply(unused -> {
                final Reference2ObjectMap<GeoAttachment<?>, Object> result = new Reference2ObjectOpenHashMap<>(
                        attachmentMap.size());
                attachmentMap.forEach(
                        (attachment, future) -> future.join().ifPresent(value -> result.put(attachment, value)));
                return Optional.of(new GeoChunk(result));
            });
        }
    }
}
