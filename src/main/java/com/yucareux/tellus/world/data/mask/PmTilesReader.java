package com.yucareux.tellus.world.data.mask;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.util.TellusDiagnostics;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

final class PmTilesReader {
   private static final int HEADER_SIZE = 127;
   private static final int MAX_DIRECTORY_DEPTH = 4;
   private static final int MAX_DIRECTORY_CACHE = intProperty("tellus.landmask.dirCache", 256);
   private static final int READ_TIMEOUT_MS = 15000;
   private static final int CONNECT_TIMEOUT_MS = 10000;
   private final String url;
   private final LoadingCache<PmTilesReader.DirectoryKey, PmTilesReader.Directory> directoryCache;
   
   private PmTilesReader.PmTilesHeader header;
   
   private PmTilesReader.Directory rootDirectory;
   private URI uri;

   PmTilesReader(String url) {
      this.url = Objects.requireNonNull(url, "url");
      this.directoryCache = CacheBuilder.newBuilder()
         .maximumSize(MAX_DIRECTORY_CACHE)
         .build(new CacheLoader<PmTilesReader.DirectoryKey, PmTilesReader.Directory>() {
            public PmTilesReader.Directory load(PmTilesReader.DirectoryKey key) throws Exception {
               return PmTilesReader.this.readDirectory(key.offset, key.length);
            }
         });
   }

   PmTilesReader.PmTilesHeader header() throws IOException {
      if (this.header == null) {
         this.header = this.readHeader();
      }

      return this.header;
   }

   
   byte[] getTileBytes(int z, int x, int y) throws IOException {
      long tileId = zxyToTileId(z, x, y);
      PmTilesReader.PmTilesHeader header = this.header();
      PmTilesReader.Directory directory = this.getRootDirectory();

      for (int depth = 0; depth < MAX_DIRECTORY_DEPTH; depth++) {
         PmTilesReader.Entry entry = findTile(directory.entries, tileId);
         if (entry == null) {
            return null;
         }

         if (entry.runLength != 0L) {
            long dataOffset = header.tileDataOffset + entry.offset;
            if (entry.length > 2147483647L) {
               throw new IOException("Tile too large");
            }

            return this.readBytes(dataOffset, (int)entry.length);
         }

         long dirOffset = header.leafDirectoryOffset + entry.offset;
         long dirLength = entry.length;
         directory = this.getDirectory(dirOffset, dirLength);
      }

      return null;
   }

   private PmTilesReader.Directory getRootDirectory() throws IOException {
      if (this.rootDirectory == null) {
         PmTilesReader.PmTilesHeader header = this.header();
         this.rootDirectory = this.getDirectory(header.rootOffset, header.rootLength);
      }

      return this.rootDirectory;
   }

   private PmTilesReader.Directory getDirectory(long offset, long length) throws IOException {
      try {
         return (PmTilesReader.Directory)this.directoryCache.get(new PmTilesReader.DirectoryKey(offset, length));
      } catch (Exception var8) {
         if (var8.getCause() instanceof IOException io) {
            throw io;
         } else {
            throw new IOException("Failed to read PMTiles directory", var8);
         }
      }
   }

   private PmTilesReader.PmTilesHeader readHeader() throws IOException {
      byte[] headerBytes = this.readBytes(0L, HEADER_SIZE);
      if (!"PMTiles".equals(new String(headerBytes, 0, 7, StandardCharsets.US_ASCII))) {
         throw new IOException("PMTiles header missing");
      } else {
         int version = headerBytes[7] & 255;
         if (version != 3) {
            throw new IOException("Unsupported PMTiles version " + version);
         } else {
            long rootOffset = readUint64(headerBytes, 8);
            long rootLength = readUint64(headerBytes, 16);
            long metadataOffset = readUint64(headerBytes, 24);
            long metadataLength = readUint64(headerBytes, 32);
            long leafOffset = readUint64(headerBytes, 40);
            long leafLength = readUint64(headerBytes, 48);
            long tileOffset = readUint64(headerBytes, 56);
            long tileLength = readUint64(headerBytes, 64);
            int internalCompression = headerBytes[97] & 255;
            int tileCompression = headerBytes[98] & 255;
            int tileType = headerBytes[99] & 255;
            int minZoom = headerBytes[100] & 255;
            int maxZoom = headerBytes[101] & 255;
            if (internalCompression != 2) {
               Tellus.LOGGER.warn("Unexpected PMTiles directory compression {}", internalCompression);
            }

            if (tileCompression != 1) {
               Tellus.LOGGER.warn("Unexpected PMTiles tile compression {}", tileCompression);
            }

            if (tileType != 2) {
               Tellus.LOGGER.warn("Unexpected PMTiles tile type {}", tileType);
            }

            return new PmTilesReader.PmTilesHeader(
               rootOffset, rootLength, metadataOffset, metadataLength, leafOffset, leafLength, tileOffset, tileLength, minZoom, maxZoom
            );
         }
      }
   }

   private PmTilesReader.Directory readDirectory(long offset, long length) throws IOException {
      if (length <= 0L) {
         return new PmTilesReader.Directory(List.of());
      } else if (length > Integer.MAX_VALUE) {
         throw new IOException("PMTiles directory too large");
      } else {
         byte[] compressed = this.readBytes(offset, (int)length);
         byte[] decompressed = gunzip(compressed);
         ByteArrayInputStream input = new ByteArrayInputStream(decompressed);
         int numEntries = (int)readVarint(input);
         List<PmTilesReader.Entry> entries = new ArrayList<>(numEntries);
         long lastId = 0L;

         for (int i = 0; i < numEntries; i++) {
            long delta = readVarint(input);
            long tileId = lastId + delta;
            entries.add(new PmTilesReader.Entry(tileId, 0L, 0L, 0L));
            lastId = tileId;
         }

         for (int i = 0; i < numEntries; i++) {
            entries.get(i).runLength = readVarint(input);
         }

         for (int i = 0; i < numEntries; i++) {
            entries.get(i).length = readVarint(input);
         }

         for (int i = 0; i < numEntries; i++) {
            long tmp = readVarint(input);
            if (i > 0 && tmp == 0L) {
               PmTilesReader.Entry prev = entries.get(i - 1);
               entries.get(i).offset = prev.offset + prev.length;
            } else {
               entries.get(i).offset = tmp - 1L;
            }
         }

         return new PmTilesReader.Directory(entries);
      }
   }

   private byte[] readBytes(long offset, int length) throws IOException {
      if (length <= 0) {
         return new byte[0];
      } else {
         long startMs = System.currentTimeMillis();
         HttpURLConnection connection = (HttpURLConnection)this.uri().toURL().openConnection();
         connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1L));
         connection.setInstanceFollowRedirects(true);
         connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
         connection.setReadTimeout(READ_TIMEOUT_MS);
         int code = connection.getResponseCode();
         if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect();
            TellusDiagnostics.traffic("Landmask PMTiles range failed uri=%s status=%d offset=%d requestedBytes=%d elapsedMs=%d", this.uri(), code, offset, length, System.currentTimeMillis() - startMs);
            throw new IOException("PMTiles HTTP error " + code);
         }

         byte[] data;
         try (InputStream input = connection.getInputStream()) {
            if (code == HttpURLConnection.HTTP_OK) {
               skipFully(input, offset);
               data = readFully(input, length);
            } else {
               data = readFully(input, length);
            }
         } finally {
            connection.disconnect();
         }

         TellusDiagnostics.traffic(
            "Landmask PMTiles range ok uri=%s status=%d offset=%d requestedBytes=%d bytes=%d elapsedMs=%d",
            this.uri(),
            code,
            offset,
            length,
            data.length,
            System.currentTimeMillis() - startMs
         );
         return data;
      }
   }

   private URI uri() {
      if (this.uri == null) {
         this.uri = URI.create(this.url);
      }

      return this.uri;
   }

   private static byte[] gunzip(byte[] input) throws IOException {
      byte[] var5;
      try (
         GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input));
         ByteArrayOutputStream output = new ByteArrayOutputStream();
      ) {
         byte[] buffer = new byte[8192];

         int read;
         while ((read = gzip.read(buffer)) != -1) {
            output.write(buffer, 0, read);
         }

         var5 = output.toByteArray();
      }

      return var5;
   }

   private static void skipFully(InputStream input, long bytes) throws IOException {
      long remaining = bytes;

      while (remaining > 0L) {
         long skipped = input.skip(remaining);
         if (skipped <= 0L) {
            if (input.read() == -1) {
               throw new EOFException("Unexpected EOF while skipping");
            }

            remaining--;
         } else {
            remaining -= skipped;
         }
      }
   }

   private static byte[] readFully(InputStream input, int length) throws IOException {
      byte[] buffer = new byte[length];
      int offset = 0;

      while (offset < length) {
         int read = input.read(buffer, offset, length - offset);
         if (read == -1) {
            throw new EOFException("Unexpected EOF while reading");
         }

         offset += read;
      }

      return buffer;
   }

   private static long readVarint(InputStream input) throws IOException {
      long result = 0L;
      int shift = 0;

      while (true) {
         int raw = input.read();
         if (raw == -1) {
            throw new EOFException("Unexpected EOF in varint");
         }

         result |= (long)(raw & 127) << shift;
         if ((raw & 128) == 0) {
            return result;
         }

         shift += 7;
      }
   }

   private static long readUint64(byte[] buffer, int pos) {
      return buffer[pos] & 255L
         | (buffer[pos + 1] & 255L) << 8
         | (buffer[pos + 2] & 255L) << 16
         | (buffer[pos + 3] & 255L) << 24
         | (buffer[pos + 4] & 255L) << 32
         | (buffer[pos + 5] & 255L) << 40
         | (buffer[pos + 6] & 255L) << 48
         | (buffer[pos + 7] & 255L) << 56;
   }

   private static long zxyToTileId(int z, int x, int y) {
      if (z > 31) {
         throw new IllegalArgumentException("Tile zoom exceeds 64-bit limit");
      } else {
         int max = (1 << z) - 1;
         if (x >= 0 && y >= 0 && x <= max && y <= max) {
            long acc = ((1L << z * 2) - 1L) / 3L;

            for (int level = z - 1; level >= 0; level--) {
               int scale = 1 << level;
               int rx = scale & x;
               int ry = scale & y;
               acc += (long)(3 * rx ^ ry) << level;
               if (ry == 0) {
                  if (rx != 0) {
                     x = scale - 1 - x;
                     y = scale - 1 - y;
                  }

                  int swapped = x;
                  x = y;
                  y = swapped;
               }
            }

            return acc;
         } else {
            throw new IllegalArgumentException("Tile x/y outside zoom bounds");
         }
      }
   }

   private static PmTilesReader.Entry findTile(List<PmTilesReader.Entry> entries, long tileId) {
      int low = 0;
      int high = entries.size() - 1;

      while (low <= high) {
         int mid = low + high >>> 1;
         long diff = tileId - entries.get(mid).tileId;
         if (diff > 0L) {
            low = mid + 1;
         } else {
            if (diff >= 0L) {
               return entries.get(mid);
            }

            high = mid - 1;
         }
      }

      if (high >= 0) {
         PmTilesReader.Entry entry = entries.get(high);
         if (entry.runLength == 0L) {
            return entry;
         }

         if (tileId - entry.tileId < entry.runLength) {
            return entry;
         }
      }

      return null;
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(1, Integer.parseInt(value));
         } catch (NumberFormatException var4) {
            return defaultValue;
         }
      }
   }

   private static final class Directory {
      private final List<PmTilesReader.Entry> entries;

      private Directory(List<PmTilesReader.Entry> entries) {
         this.entries = entries;
      }
   }

   private record DirectoryKey(long offset, long length) {
   }

   private static final class Entry {
      private final long tileId;
      private long offset;
      private long length;
      private long runLength;

      private Entry(long tileId, long offset, long length, long runLength) {
         this.tileId = tileId;
         this.offset = offset;
         this.length = length;
         this.runLength = runLength;
      }
   }

   static final class PmTilesHeader {
      private final long rootOffset;
      private final long rootLength;
      private final long metadataOffset;
      private final long metadataLength;
      private final long leafDirectoryOffset;
      private final long leafDirectoryLength;
      private final long tileDataOffset;
      private final long tileDataLength;
      private final int minZoom;
      private final int maxZoom;

      private PmTilesHeader(
         long rootOffset,
         long rootLength,
         long metadataOffset,
         long metadataLength,
         long leafDirectoryOffset,
         long leafDirectoryLength,
         long tileDataOffset,
         long tileDataLength,
         int minZoom,
         int maxZoom
      ) {
         this.rootOffset = rootOffset;
         this.rootLength = rootLength;
         this.metadataOffset = metadataOffset;
         this.metadataLength = metadataLength;
         this.leafDirectoryOffset = leafDirectoryOffset;
         this.leafDirectoryLength = leafDirectoryLength;
         this.tileDataOffset = tileDataOffset;
         this.tileDataLength = tileDataLength;
         this.minZoom = minZoom;
         this.maxZoom = maxZoom;
      }

      public long rootOffset() {
         return this.rootOffset;
      }

      public long rootLength() {
         return this.rootLength;
      }

      public long metadataOffset() {
         return this.metadataOffset;
      }

      public long metadataLength() {
         return this.metadataLength;
      }

      public long leafDirectoryOffset() {
         return this.leafDirectoryOffset;
      }

      public long leafDirectoryLength() {
         return this.leafDirectoryLength;
      }

      public long tileDataOffset() {
         return this.tileDataOffset;
      }

      public long tileDataLength() {
         return this.tileDataLength;
      }

      public int minZoom() {
         return this.minZoom;
      }

      public int maxZoom() {
         return this.maxZoom;
      }
   }
}
