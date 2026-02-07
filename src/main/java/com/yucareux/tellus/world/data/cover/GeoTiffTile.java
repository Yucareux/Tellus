package com.yucareux.tellus.world.data.cover;

import com.yucareux.tellus.Tellus;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.InflaterInputStream;

final class GeoTiffTile {
    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_HEIGHT = 257;
    private static final int TAG_TILE_WIDTH = 322;
    private static final int TAG_TILE_HEIGHT = 323;
    private static final int TAG_TILE_OFFSETS = 324;
    private static final int TAG_TILE_BYTE_COUNTS = 325;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_MODEL_PIXEL_SCALE = 33550;
    private static final int TAG_MODEL_TIEPOINT = 33922;

    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;

    private static final int COMPRESSION_DEFLATE = 8;
    static final GeoTiffTile MISSING = new GeoTiffTile();

    private static final int TILE_CACHE_ENTRIES = 32;

    private final Path path;
    private final SeekableByteChannel channel;
    private final int width;
    private final int height;
    private final int tileWidth;
    private final int tileHeight;
    private final int tilesPerRow;
    private final long[] tileOffsets;
    private final int[] tileByteCounts;
    private final double pixelScaleX;
    private final double pixelScaleY;
    private final double tieLon;
    private final double tieLat;
    private final Map<Integer, byte[]> tileCache;

    private GeoTiffTile() {
        this.path = null;
        this.channel = null;
        this.width = 0;
        this.height = 0;
        this.tileWidth = 0;
        this.tileHeight = 0;
        this.tilesPerRow = 0;
        this.tileOffsets = null;
        this.tileByteCounts = null;
        this.pixelScaleX = 0.0;
        this.pixelScaleY = 0.0;
        this.tieLon = 0.0;
        this.tieLat = 0.0;
        this.tileCache = Map.of();
    }

    private GeoTiffTile(
            Path path,
            SeekableByteChannel channel,
            int width,
            int height,
            int tileWidth,
            int tileHeight,
            long[] tileOffsets,
            int[] tileByteCounts,
            double pixelScaleX,
            double pixelScaleY,
            double tieLon,
            double tieLat) {
        this.path = path;
        this.channel = channel;
        this.width = width;
        this.height = height;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.tilesPerRow = (int) Math.ceil(width / (double) tileWidth);
        this.tileOffsets = tileOffsets;
        this.tileByteCounts = tileByteCounts;
        this.pixelScaleX = pixelScaleX;
        this.pixelScaleY = pixelScaleY;
        this.tieLon = tieLon;
        this.tieLat = tieLat;
        this.tileCache = new LinkedHashMap<>(TILE_CACHE_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
                return size() > TILE_CACHE_ENTRIES;
            }
        };
    }

    static GeoTiffTile open(Path path) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            return readFromChannel(path, channel);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    static GeoTiffTile open(SeekableByteChannel channel) throws IOException {
        return readFromChannel(null, channel);
    }

    int sample(double lon, double lat) {
        Pixel pixel = toPixel(lon, lat);
        if (pixel == null) {
            return 0;
        }
        return sampleValue(pixel.x, pixel.y);
    }

    Pixel toPixel(double lon, double lat) {
        if (this == MISSING) {
            return null;
        }
        int pixelX = (int) Math.floor((lon - this.tieLon) / this.pixelScaleX);
        int pixelY = (int) Math.floor((this.tieLat - lat) / this.pixelScaleY);
        if (pixelX < 0 || pixelY < 0 || pixelX >= this.width || pixelY >= this.height) {
            return null;
        }
        return new Pixel(pixelX, pixelY);
    }

    int sampleValue(int pixelX, int pixelY) {
        if (this == MISSING || pixelX < 0 || pixelY < 0 || pixelX >= this.width || pixelY >= this.height) {
            return 0;
        }
        int tileX = pixelX / this.tileWidth;
        int tileY = pixelY / this.tileHeight;
        int tileIndex = tileY * this.tilesPerRow + tileX;

        byte[] tile;
        try {
            tile = getTile(tileIndex);
        } catch (ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (IOException e) {
            Tellus.LOGGER.warn("Failed to read land cover tile {} in {}", tileIndex, this.path, e);
            return 0;
        }

        int localX = pixelX - tileX * this.tileWidth;
        int localY = pixelY - tileY * this.tileHeight;
        return Byte.toUnsignedInt(tile[localX + localY * this.tileWidth]);
    }

    boolean isInside(int pixelX, int pixelY) {
        return pixelX >= 0 && pixelY >= 0 && pixelX < this.width && pixelY < this.height;
    }

    boolean isNeighborhoodInBounds(int pixelX, int pixelY, int radius) {
        return pixelX - radius >= 0
                && pixelY - radius >= 0
                && pixelX + radius < this.width
                && pixelY + radius < this.height;
    }

    double lonForPixel(int pixelX) {
        return this.tieLon + (pixelX + 0.5) * this.pixelScaleX;
    }

    double latForPixel(int pixelY) {
        return this.tieLat - (pixelY + 0.5) * this.pixelScaleY;
    }

    void close() {
        if (this.channel == null) {
            return;
        }
        try {
            this.channel.close();
        } catch (IOException e) {
            Tellus.LOGGER.warn("Failed to close land cover tile {}", this.path, e);
        }
    }

    private byte[] getTile(int tileIndex) throws IOException {
        synchronized (this.tileCache) {
            byte[] cached = this.tileCache.get(tileIndex);
            if (cached != null) {
                return cached;
            }
        }

        byte[] tile = readTile(tileIndex);
        synchronized (this.tileCache) {
            this.tileCache.put(tileIndex, tile);
        }
        return tile;
    }

    private byte[] readTile(int tileIndex) throws IOException {
        long offset = this.tileOffsets[tileIndex];
        int length = this.tileByteCounts[tileIndex];
        byte[] compressed = new byte[length];
        try {
            readFully(this.channel, compressed, offset);
        } catch (ClosedChannelException e) {
            if (this.path == null) {
                throw e;
            }
            try (FileChannel reopened = FileChannel.open(this.path, StandardOpenOption.READ)) {
                readFully(reopened, compressed, offset);
            }
        }
        return inflate(compressed, this.tileWidth * this.tileHeight);
    }

    private static GeoTiffTile readFromChannel(Path path, SeekableByteChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8);
        readFully(channel, header, 0);
        header.flip();

        short order = header.getShort();
        ByteOrder byteOrder = switch (order) {
            case 0x4949 -> ByteOrder.LITTLE_ENDIAN;
            case 0x4D4D -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Invalid TIFF byte order");
        };
        header.order(byteOrder);

        short magic = header.getShort();
        if (magic != 42) {
            throw new IOException("Invalid TIFF magic");
        }

        int ifdOffset = header.getInt();
        ByteBuffer countBuffer = ByteBuffer.allocate(2).order(byteOrder);
        readFully(channel, countBuffer, ifdOffset);
        countBuffer.flip();
        int entryCount = Short.toUnsignedInt(countBuffer.getShort());

        ByteBuffer entries = ByteBuffer.allocate(entryCount * 12).order(byteOrder);
        readFully(channel, entries, ifdOffset + 2L);
        entries.flip();

        int width = -1;
        int height = -1;
        int tileWidth = -1;
        int tileHeight = -1;
        int compression = -1;
        long[] tileOffsets = null;
        int[] tileByteCounts = null;
        double[] pixelScale = null;
        double[] tiepoint = null;

        for (int i = 0; i < entryCount; i++) {
            int tag = Short.toUnsignedInt(entries.getShort());
            int type = Short.toUnsignedInt(entries.getShort());
            int count = entries.getInt();
            int value = entries.getInt();
            switch (tag) {
                case TAG_IMAGE_WIDTH -> width = readIntValue(type, count, value, byteOrder);
                case TAG_IMAGE_HEIGHT -> height = readIntValue(type, count, value, byteOrder);
                case TAG_TILE_WIDTH -> tileWidth = readIntValue(type, count, value, byteOrder);
                case TAG_TILE_HEIGHT -> tileHeight = readIntValue(type, count, value, byteOrder);
                case TAG_COMPRESSION -> compression = readIntValue(type, count, value, byteOrder);
                case TAG_TILE_OFFSETS -> tileOffsets = readLongArray(channel, value, count, byteOrder);
                case TAG_TILE_BYTE_COUNTS -> tileByteCounts = readIntArray(channel, value, count, byteOrder);
                case TAG_MODEL_PIXEL_SCALE -> pixelScale = readDoubleArray(channel, value, count, byteOrder);
                case TAG_MODEL_TIEPOINT -> tiepoint = readDoubleArray(channel, value, count, byteOrder);
                default -> {
                }
            }
        }

        if (compression != COMPRESSION_DEFLATE) {
            throw new IOException("Unsupported TIFF compression " + compression);
        }
        if (width <= 0 || height <= 0 || tileWidth <= 0 || tileHeight <= 0) {
            throw new IOException("Missing TIFF size tags");
        }
        if (tileOffsets == null || tileByteCounts == null) {
            throw new IOException("Missing TIFF tile offsets");
        }
        if (pixelScale == null || pixelScale.length < 2 || tiepoint == null || tiepoint.length < 5) {
            throw new IOException("Missing TIFF georeference tags");
        }

        return new GeoTiffTile(
                path,
                channel,
                width,
                height,
                tileWidth,
                tileHeight,
                tileOffsets,
                tileByteCounts,
                pixelScale[0],
                pixelScale[1],
                tiepoint[3],
                tiepoint[4]);
    }

    private static int readIntValue(int type, int count, int value, ByteOrder order) throws IOException {
        if (count != 1) {
            throw new IOException("Expected single TIFF value");
        }
        ByteBuffer buffer = ByteBuffer.allocate(4).order(order);
        buffer.putInt(value);
        buffer.flip();
        if (type == TYPE_SHORT) {
            return Short.toUnsignedInt(buffer.getShort());
        }
        if (type == TYPE_LONG) {
            return buffer.getInt();
        }
        throw new IOException("Unsupported TIFF value type " + type);
    }

    private static long[] readLongArray(SeekableByteChannel channel, long offset, int count, ByteOrder order)
            throws IOException {
        if (count <= 0) {
            return new long[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(count * 4).order(order);
        readFully(channel, buffer, offset);
        buffer.flip();
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            values[i] = Integer.toUnsignedLong(buffer.getInt());
        }
        return values;
    }

    private static int[] readIntArray(SeekableByteChannel channel, long offset, int count, ByteOrder order)
            throws IOException {
        if (count <= 0) {
            return new int[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(count * 4).order(order);
        readFully(channel, buffer, offset);
        buffer.flip();
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = buffer.getInt();
        }
        return values;
    }

    private static double[] readDoubleArray(SeekableByteChannel channel, long offset, int count, ByteOrder order)
            throws IOException {
        if (count <= 0) {
            return new double[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(count * 8).order(order);
        readFully(channel, buffer, offset);
        buffer.flip();
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = buffer.getDouble();
        }
        return values;
    }

    private static void readFully(SeekableByteChannel channel, ByteBuffer buffer, long offset) throws IOException {
        synchronized (channel) {
            channel.position(offset);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    throw new EOFException("Unexpected end of file");
                }
            }
        }
    }

    private static void readFully(SeekableByteChannel channel, byte[] dest, long offset) throws IOException {
        readFully(channel, ByteBuffer.wrap(dest), offset);
    }

    private static byte[] inflate(byte[] compressed, int expectedSize) throws IOException {
        byte[] output = new byte[expectedSize];
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            int offset = 0;
            while (offset < expectedSize) {
                int read = inflater.read(output, offset, expectedSize - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset != expectedSize) {
                throw new IOException("Unexpected inflated data length");
            }
            return output;
        }
    }

    record Pixel(int x, int y) {
    }
}
