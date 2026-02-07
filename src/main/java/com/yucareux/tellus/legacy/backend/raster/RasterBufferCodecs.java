package com.yucareux.tellus.legacy.backend.raster;

import com.mojang.serialization.Codec;

import java.nio.ByteBuffer;
import java.util.BitSet;

public class RasterBufferCodecs {
    public static final Codec<byte[]> BYTES = Codec.BYTE_BUFFER.xmap(
            buffer -> {
                final byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                return bytes;
            },
            ByteBuffer::wrap);

    public static final Codec<short[]> SHORTS = Codec.BYTE_BUFFER.xmap(
            bytes -> {
                final short[] shorts = new short[bytes.capacity() / Short.BYTES];
                bytes.asShortBuffer().get(shorts);
                return shorts;
            },
            shorts -> {
                final ByteBuffer bytes = ByteBuffer.allocate(shorts.length * Short.BYTES);
                bytes.asShortBuffer().put(shorts);
                return bytes;
            });

    public static final Codec<BitSet> BIT_SET = Codec.BYTE_BUFFER.xmap(
            BitSet::valueOf,
            bitSet -> ByteBuffer.wrap(bitSet.toByteArray()));
}
