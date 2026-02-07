package com.yucareux.tellus.legacy.backend.raster;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class EnumRaster<T extends Enum<T>> implements Raster {
    private final EnumRasterType<T> type;
    private final RasterShape shape;
    private final byte[] buffer;

    private EnumRaster(final EnumRasterType<T> type, final RasterShape shape, final byte[] buffer) {
        this.type = type;
        this.shape = shape;
        this.buffer = buffer;
    }

    public static <T extends Enum<T>> RasterType<EnumRaster<T>> type(final T defaultValue, final Codec<T> valueCodec) {
        return new EnumRasterType<>(defaultValue, valueCodec);
    }

    public void put(final int x, final int y, final T value) {
        buffer[shape.index(x, y)] = (byte) (value.ordinal() & 0xff);
    }

    public T get(final int x, final int y) {
        return type.variants[buffer[shape.index(x, y)] & 0xff];
    }

    private void checkSameType(final EnumRaster<T> raster) {
        if (!type.equals(raster.type)) {
            throw new IllegalArgumentException(
                    "Enum rasters have different types: got " + raster.type + ", but expected" + type);
        }
    }

    public void copyFrom(final EnumRaster<T> raster) {
        Raster.checkSameShape(this, raster);
        checkSameType(raster);
        System.arraycopy(raster.buffer, 0, buffer, 0, buffer.length);
    }

    public void copyFromClipped(final EnumRaster<T> raster, final int x0, final int y0) {
        if (x0 == 0 && y0 == 0 && shape().equals(raster.shape())) {
            copyFrom(raster);
            return;
        }

        checkSameType(raster);

        final int x1 = Math.min(x0 + raster.width(), width());
        final int y1 = Math.min(y0 + raster.height(), height());

        for (int y = Math.max(y0, 0); y < y1; y++) {
            for (int x = Math.max(x0, 0); x < x1; x++) {
                buffer[shape.index(x, y)] = raster.buffer[raster.shape.index(x - x0, y - y0)];
            }
        }
    }

    @Override
    public RasterType<EnumRaster<T>> type() {
        return type;
    }

    @Override
    public RasterShape shape() {
        return shape;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void copyFrom(final Raster raster) {
        if (raster instanceof final EnumRaster<?> enumRaster && type.equals(enumRaster.type)) {
            copyFrom((EnumRaster<T>) enumRaster);
        } else {
            throw new IllegalArgumentException("Cannot copy from " + raster + " into EnumRaster of type " + type);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void copyFromClipped(final Raster raster, final int x0, final int y0) {
        if (raster instanceof final EnumRaster<?> enumRaster && type.equals(enumRaster.type)) {
            copyFromClipped((EnumRaster<T>) enumRaster, x0, y0);
        } else {
            throw new IllegalArgumentException("Cannot copy from " + raster + " into EnumRaster of type " + type);
        }
    }

    // Fairly crude, could pack values further, but mostly the intent is to remove
    // on-disk dependency on enum ordinals
    private record PalettedBuffer<T extends Enum<T>>(
            List<T> palette,
            Optional<byte[]> buffer) {
        private static final int UNASSIGNED = -1;

        public static <T extends Enum<T>> MapCodec<PalettedBuffer<T>> codec(final Codec<T> valueCodec) {
            return RecordCodecBuilder.mapCodec(i -> i.group(
                    valueCodec.listOf().fieldOf("palette").forGetter(PalettedBuffer::palette),
                    RasterBufferCodecs.BYTES.optionalFieldOf("data").forGetter(PalettedBuffer::buffer))
                    .apply(i, PalettedBuffer::new));
        }

        public static <T extends Enum<T>> PalettedBuffer<T> pack(final Class<T> enumClass, final byte[] buffer) {
            final T[] variants = enumClass.getEnumConstants();

            final List<T> valueById = new ArrayList<>();
            final int[] idByValue = new int[variants.length];
            Arrays.fill(idByValue, UNASSIGNED);

            final byte[] packedBuffer = new byte[buffer.length];
            for (int i = 0; i < buffer.length; i++) {
                final T value = variants[buffer[i]];
                int packedId = idByValue[value.ordinal()];
                if (packedId == UNASSIGNED) {
                    packedId = valueById.size();
                    idByValue[value.ordinal()] = packedId;
                    valueById.add(value);
                }
                packedBuffer[i] = (byte) (packedId & 0xff);
            }

            if (valueById.size() == 1) {
                return new PalettedBuffer<>(valueById, Optional.empty());
            }

            return new PalettedBuffer<>(valueById, Optional.of(packedBuffer));
        }

        public DataResult<byte[]> unpack(final int expectedSize) {
            if (palette.size() == 1) {
                final byte[] unpackedBuffer = new byte[expectedSize];
                Arrays.fill(unpackedBuffer, (byte) palette.getFirst().ordinal());
                return DataResult.success(unpackedBuffer);
            }
            if (buffer.isEmpty()) {
                return DataResult.error(
                        () -> "Expected data field for palette with size " + palette.size() + ", but none was present");
            }
            final byte[] packedBuffer = buffer.get();
            final byte[] unpackedBuffer = new byte[packedBuffer.length];
            for (int i = 0; i < packedBuffer.length; i++) {
                final int paletteIndex = packedBuffer[i] & 0xff;
                if (paletteIndex >= palette.size()) {
                    return DataResult.error(
                            () -> "Value " + paletteIndex + " does not exist in palette of size " + palette.size());
                }
                final T value = palette.get(paletteIndex);
                unpackedBuffer[i] = (byte) value.ordinal();
            }
            return DataResult.success(unpackedBuffer);
        }
    }

    private static final class EnumRasterType<T extends Enum<T>> implements RasterType<EnumRaster<T>> {
        private final T defaultValue;
        private final T[] variants;
        private final Codec<EnumRaster<T>> codec;

        public EnumRasterType(final T defaultValue, final Codec<T> valueCodec) {
            this.defaultValue = defaultValue;
            final Class<T> enumClass = defaultValue.getDeclaringClass();
            variants = enumClass.getEnumConstants();
            if (variants.length > 255) {
                throw new IllegalArgumentException(
                        "Cannot construct EnumRaster for " + enumClass + " with " + variants.length + " variants");
            }
            codec = Codec.mapPair(RasterShape.CODEC, PalettedBuffer.codec(valueCodec)).codec().comapFlatMap(
                    pair -> {
                        final RasterShape shape = pair.getFirst();
                        return pair.getSecond().unpack(shape.size())
                                .map(buffer -> new EnumRaster<>(this, shape, buffer));
                    },
                    raster -> Pair.of(raster.shape, PalettedBuffer.pack(enumClass, raster.buffer)));
        }

        @Override
        public EnumRaster<T> create(final RasterShape shape) {
            final byte[] buffer = new byte[shape.size()];
            if (defaultValue.ordinal() != 0) {
                Arrays.fill(buffer, (byte) defaultValue.ordinal());
            }
            return new EnumRaster<>(this, shape, buffer);
        }

        @Override
        public Codec<EnumRaster<T>> codec() {
            return codec;
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj instanceof final EnumRasterType<?> type) {
                return defaultValue.equals(type.defaultValue);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return defaultValue.hashCode();
        }

        @Override
        public String toString() {
            return "EnumRasterType" + Arrays.toString(variants);
        }
    }
}
