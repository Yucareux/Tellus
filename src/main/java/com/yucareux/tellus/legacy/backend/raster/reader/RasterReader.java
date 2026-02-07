package com.yucareux.tellus.legacy.backend.raster.reader;

import com.mojang.logging.LogUtils;
import com.yucareux.tellus.legacy.backend.loader.Loader;
import com.yucareux.tellus.legacy.backend.raster.EnumRaster;
import com.yucareux.tellus.legacy.backend.raster.IntLikeRaster;
import com.yucareux.tellus.legacy.backend.raster.RasterShape;
import com.yucareux.tellus.legacy.backend.raster.RasterType;
import com.yucareux.tellus.legacy.backend.raster.UnsignedByteRaster;
import com.yucareux.tellus.legacy.backend.util.Util;
import org.slf4j.Logger;
import org.tukaani.xz.SingleXZInputStream;

import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;

public final class RasterReader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final byte[] SIGNATURE = "TERRARIUM/RASTER".getBytes(StandardCharsets.UTF_8);
    private static final int HEADER_LENGTH = SIGNATURE.length + 1;

    public static <T extends IntLikeRaster> Loader<byte[], T> loader(final RasterFormat<T> format,
            final Executor executor) {
        return bytes -> CompletableFuture.supplyAsync(() -> {
            try {
                return Optional.of(read(bytes, format));
            } catch (final IOException e) {
                LOGGER.error("Failed to read raster of format {}", format, e);
                return Optional.empty();
            }
        }, executor);
    }

    public static <T extends Enum<T>> Loader<byte[], EnumRaster<T>> loader(final RasterType<EnumRaster<T>> type,
            final IntFunction<T> lookup, final Executor executor) {
        final Loader<UnsignedByteRaster, EnumRaster<T>> converter = Loader
                .from(raster -> raster.mapToEnum(type, lookup));
        return converter.compose(loader(RasterFormat.UNSIGNED_BYTE, executor));
    }

    public static <T extends IntLikeRaster> T read(final byte[] bytes, final RasterFormat<T> format)
            throws IOException {
        try (final ReadableByteChannel channel = Util.asChannel(bytes)) {
            return read(channel, format);
        }
    }

    public static <T extends IntLikeRaster> T read(final ReadableByteChannel channel, final RasterFormat<T> format)
            throws IOException {
        final int version = parseHeader(channel);
        if (version != 0) {
            throw new IOException("Unrecognized raster version: " + version);
        }

        final ByteBuffer dataHeader = ByteBuffer.allocate(Integer.BYTES * 2 + Byte.BYTES);
        Util.readFully(channel, dataHeader);

        final int width = dataHeader.getInt();
        final int height = dataHeader.getInt();

        final RasterFormat<?> dataFormat = RasterFormat.byId(dataHeader.get() & 0xff);
        if (dataFormat != format) {
            throw new IOException("Expected raster of type: " + format + ", but got " + dataFormat);
        }

        final RasterShape shape = new RasterShape(width, height);
        T raster = null;

        final ByteBuffer chunkHeader = ByteBuffer.allocate(Integer.BYTES);
        while (Util.tryReadFully(channel, chunkHeader.rewind())) {
            final int chunkLength = chunkHeader.getInt();
            final ByteBuffer chunkBody = ByteBuffer.allocate(chunkLength);
            Util.readFully(channel, chunkBody);
            raster = readChunk(chunkBody, raster, shape, format);
        }

        if (raster == null) {
            return format.create(shape);
        }

        return raster;
    }

    private static int parseHeader(final ReadableByteChannel channel) throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH);
        Util.readFully(channel, header);

        final byte[] signature = new byte[SIGNATURE.length];
        header.get(signature);
        if (!Arrays.equals(signature, SIGNATURE)) {
            throw new IOException("Invalid signature: " + new String(signature, StandardCharsets.UTF_8));
        }

        return header.get() & 0xff;
    }

    private static <T extends IntLikeRaster> T readChunk(final ByteBuffer buffer, @Nullable T output,
            final RasterShape outputShape, final RasterFormat<T> format) throws IOException {
        final int x = buffer.getInt();
        final int y = buffer.getInt();
        final RasterShape shape = new RasterShape(buffer.getInt(), buffer.getInt());
        final RasterFilter filter = RasterFilter.byId(buffer.get() & 0xff);

        final T raw;
        try (final SingleXZInputStream input = new SingleXZInputStream(Util.asInputStream(buffer))) {
            raw = format.read(ByteBuffer.wrap(input.readAllBytes()), shape);
        }
        filter.evaluateInPlace(raw);

        if (x == 0 && y == 0 && shape.equals(outputShape)) {
            return raw;
        }

        if (output == null) {
            output = format.create(outputShape);
        }
        output.copyFrom(raw, x, y);
        return output;
    }
}
