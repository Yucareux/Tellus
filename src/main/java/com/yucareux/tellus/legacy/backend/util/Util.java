package com.yucareux.tellus.legacy.backend.util;

import com.google.common.base.Suppliers;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public class Util {
    public static int floorInt(final float value) {
        return (int) Math.floor(value);
    }

    public static int floorInt(final double value) {
        return (int) Math.floor(value);
    }

    public static float lerp(final float a, final float b, final float x) {
        return a + (b - a) * x;
    }

    public static float inverseLerp(final float a, final float b, final float x) {
        return (x - a) / (b - a);
    }

    public static int clamp(final int value, final int min, final int max) {
        return Math.min(Math.max(value, min), max);
    }

    public static int ceilDiv(final int value, final int divisor) {
        return (value + divisor - 1) / divisor;
    }

    public static void readFully(final ReadableByteChannel channel, final ByteBuffer buffer) throws IOException {
        if (!tryReadFully(channel, buffer)) {
            throw new EOFException("Expected " + buffer.capacity() + " bytes, got " + buffer.position());
        }
    }

    public static boolean tryReadFully(final ReadableByteChannel channel, final ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return false;
            }
        }
        buffer.flip();
        return true;
    }

    public static ReadableByteChannel asChannel(final byte[] src) {
        return new ReadableByteChannel() {
            private int index;
            private boolean open = true;

            @Override
            public int read(final ByteBuffer dst) throws IOException {
                checkOpen();
                if (!dst.hasRemaining()) {
                    return 0;
                }
                if (index < src.length) {
                    final int count = Math.min(src.length - index, dst.remaining());
                    dst.put(src, index, count);
                    index += count;
                    return count;
                } else {
                    return -1;
                }
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() throws IOException {
                checkOpen();
                open = false;
            }

            private void checkOpen() throws ClosedChannelException {
                if (!open) {
                    throw new ClosedChannelException();
                }
            }
        };
    }

    public static InputStream asInputStream(final ByteBuffer buffer) {
        return new InputStream() {
            @Override
            public int read() {
                if (!buffer.hasRemaining()) {
                    return -1;
                }
                return buffer.get() & 0xff;
            }

            @Override
            public int read(final byte[] b, final int off, final int len) {
                if (!buffer.hasRemaining()) {
                    return -1;
                }
                final int count = Math.min(len, buffer.remaining());
                buffer.get(b, off, count);
                return count;
            }

            @Override
            public int available() {
                return buffer.remaining();
            }
        };
    }

    public static <T> Codec<T> stringLookupCodec(final T[] values, final Function<T, String> keyGetter) {
        final Map<String, T> lookup = new Object2ObjectOpenHashMap<>();
        for (final T value : values) {
            lookup.put(keyGetter.apply(value), value);
        }
        return Codec.STRING.comapFlatMap(key -> {
            final T value = lookup.get(key);
            return value != null ? DataResult.success(value)
                    : DataResult.error(() -> "Unknown variant with key: '" + key + "'");
        }, keyGetter);
    }

    public static <A> Codec<A> lazyCodec(final Supplier<Codec<A>> factory) {
        return recursiveCodec(codec -> factory.get());
    }

    public static <A> Codec<A> recursiveCodec(final UnaryOperator<Codec<A>> factory) {
        return new Codec<>() {
            private final Supplier<Codec<A>> inner = Suppliers.memoize(() -> factory.apply(this));

            @Override
            public <T> DataResult<Pair<A, T>> decode(final DynamicOps<T> ops, final T input) {
                return inner.get().decode(ops, input);
            }

            @Override
            public <T> DataResult<T> encode(final A input, final DynamicOps<T> ops, final T prefix) {
                return inner.get().encode(input, ops, prefix);
            }
        };
    }

    public static <A> MapCodec<A> unsupportedMapCodec(final String name) {
        return new MapCodec<>() {
            @Override
            public <T> Stream<T> keys(final DynamicOps<T> ops) {
                return Stream.empty();
            }

            @Override
            public <T> DataResult<A> decode(final DynamicOps<T> ops, final MapLike<T> input) {
                return DataResult.error(() -> "Decoding not supported for " + name);
            }

            @Override
            public <T> RecordBuilder<T> encode(final A input, final DynamicOps<T> ops, final RecordBuilder<T> prefix) {
                return prefix.withErrorsFrom(DataResult.error(() -> "Encoding not supported for " + name));
            }
        };
    }

    public static <T> HttpResponse.BodyHandler<T> jsonBodyHandler(final Codec<T> codec) {
        final HttpResponse.BodyHandler<String> stringHandler = HttpResponse.BodyHandlers.ofString();
        // noinspection NullableProblems
        return responseInfo -> HttpResponse.BodySubscribers.mapping(stringHandler.apply(responseInfo), string -> {
            final JsonElement json = JsonParser.parseString(string);
            return codec.parse(JsonOps.INSTANCE, json).getOrThrow(JsonSyntaxException::new);
        });
    }
}
