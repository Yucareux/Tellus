package com.yucareux.tellus.world.data.osm;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.util.TellusDiagnostics;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
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

final class PmTilesRangeReader {
   private static final int HEADER_SIZE = 127;
   private static final int MAX_DIRECTORY_DEPTH = 6;
   private static final int COMPRESSION_NONE = 1;
   private static final int COMPRESSION_GZIP = 2;
   private static final int PMTILES_VERSION = 3;
   private final URI uri;
   private final int connectTimeoutMs;
   private final int readTimeoutMs;
   private final LoadingCache<PmTilesRangeReader.DirectoryKey, PmTilesRangeReader.Directory> directoryCache;
   
   private PmTilesRangeReader.PmTilesHeader header;
   
   private PmTilesRangeReader.Directory rootDirectory;

   PmTilesRangeReader(String url, int connectTimeoutMs, int readTimeoutMs, int directoryCacheEntries) {
      this.uri = URI.create(Objects.requireNonNull(url, "url"));
      this.connectTimeoutMs = Math.max(1, connectTimeoutMs);
      this.readTimeoutMs = Math.max(1, readTimeoutMs);
      this.directoryCache = CacheBuilder.newBuilder()
         .maximumSize(Math.max(1, directoryCacheEntries))
         .build(new CacheLoader<PmTilesRangeReader.DirectoryKey, PmTilesRangeReader.Directory>() {
            public PmTilesRangeReader.Directory load(PmTilesRangeReader.DirectoryKey key) throws Exception {
               return PmTilesRangeReader.this.readDirectory(key.offset, key.length);
            }
         });
   }

   PmTilesRangeReader.PmTilesHeader header() throws IOException {
      if (this.header == null) {
         this.header = this.readHeader();
      }

      return this.header;
   }

   
   byte[] getTileBytes(int z, int x, int y) throws IOException {
      long tileId = zxyToTileId(z, x, y);
      PmTilesRangeReader.PmTilesHeader header = this.header();
      PmTilesRangeReader.Directory directory = this.getRootDirectory();

      for (int depth = 0; depth < MAX_DIRECTORY_DEPTH; depth++) {
         PmTilesRangeReader.Entry entry = findTile(directory.entries, tileId);
         if (entry == null) {
            return null;
         }

         if (entry.runLength != 0L) {
            long dataOffset = header.tileDataOffset + entry.offset;
            if (entry.length > 2147483647L) {
               throw new IOException("PMTiles tile too large");
            }

            byte[] tileBytes = this.readBytes(dataOffset, (int)entry.length);
            return decompress(tileBytes, header.tileCompression);
         }

         long dirOffset = header.leafDirectoryOffset + entry.offset;
         long dirLength = entry.length;
         directory = this.getDirectory(dirOffset, dirLength);
      }

      return null;
   }

   private PmTilesRangeReader.Directory getRootDirectory() throws IOException {
      if (this.rootDirectory == null) {
         PmTilesRangeReader.PmTilesHeader header = this.header();
         this.rootDirectory = this.getDirectory(header.rootOffset, header.rootLength);
      }

      return this.rootDirectory;
   }

   private PmTilesRangeReader.Directory getDirectory(long offset, long length) throws IOException {
      try {
         return (PmTilesRangeReader.Directory)this.directoryCache.get(new PmTilesRangeReader.DirectoryKey(offset, length));
      } catch (Exception var8) {
         if (var8.getCause() instanceof IOException io) {
            throw io;
         } else {
            throw new IOException("Failed to read PMTiles directory", var8);
         }
      }
   }

   private PmTilesRangeReader.PmTilesHeader readHeader() throws IOException {
      byte[] headerBytes = this.readBytes(0L, HEADER_SIZE);
      if (!"PMTiles".equals(new String(headerBytes, 0, 7, StandardCharsets.US_ASCII))) {
         throw new IOException("PMTiles header missing");
      } else {
         int version = headerBytes[7] & 255;
         if (version != PMTILES_VERSION) {
            throw new IOException("Unsupported PMTiles version " + version);
         } else {
            long rootOffset = readUint64(headerBytes, 8);
            long rootLength = readUint64(headerBytes, 16);
            long leafOffset = readUint64(headerBytes, 40);
            long tileOffset = readUint64(headerBytes, 56);
            int internalCompression = headerBytes[97] & 255;
            int tileCompression = headerBytes[98] & 255;
            int tileType = headerBytes[99] & 255;
            int minZoom = headerBytes[100] & 255;
            int maxZoom = headerBytes[101] & 255;
            return new PmTilesRangeReader.PmTilesHeader(
               rootOffset,
               rootLength,
               leafOffset,
               tileOffset,
               internalCompression,
               tileCompression,
               tileType,
               minZoom,
               maxZoom
            );
         }
      }
   }

   private PmTilesRangeReader.Directory readDirectory(long offset, long length) throws IOException {
      if (length <= 0L) {
         return new PmTilesRangeReader.Directory(List.of());
      } else if (length > 2147483647L) {
         throw new IOException("PMTiles directory too large");
      } else {
         PmTilesRangeReader.PmTilesHeader header = this.header();
         byte[] compressed = this.readBytes(offset, (int)length);
         byte[] decompressed = decompress(compressed, header.internalCompression);
         ByteArrayInputStream input = new ByteArrayInputStream(decompressed);
         int numEntries = (int)readVarint(input);
         List<PmTilesRangeReader.Entry> entries = new ArrayList<>(numEntries);
         long lastId = 0L;

         for (int i = 0; i < numEntries; i++) {
            long delta = readVarint(input);
            long tileId = lastId + delta;
            entries.add(new PmTilesRangeReader.Entry(tileId, 0L, 0L, 0L));
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
               PmTilesRangeReader.Entry previous = entries.get(i - 1);
               entries.get(i).offset = previous.offset + previous.length;
            } else {
               entries.get(i).offset = tmp - 1L;
            }
         }

         return new PmTilesRangeReader.Directory(entries);
      }
   }

   private byte[] readBytes(long offset, int length) throws IOException {
      if (length <= 0) {
         return new byte[0];
      } else {
         long startMs = System.currentTimeMillis();
         HttpURLConnection connection = (HttpURLConnection)this.uri.toURL().openConnection();
         connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1L));
         connection.setInstanceFollowRedirects(true);
         connection.setConnectTimeout(this.connectTimeoutMs);
         connection.setReadTimeout(this.readTimeoutMs);
         int code = connection.getResponseCode();
         long expectedBytes = connection.getContentLengthLong();
         DownloadProgressReporter.requestStarted(expectedBytes);

         byte[] data;
         try (InputStream input = openStream(connection, code)) {
            if (code == 200) {
               skipFully(input, offset);
               data = readFully(input, length);
            } else {
               if (code != 206) {
                  throw new IOException("PMTiles HTTP error " + code);
               }

               data = readFully(input, length);
            }
         } catch (IOException | RuntimeException error) {
            TellusDiagnostics.traffic(
               "PMTiles range failed uri=%s status=%d offset=%d requestedBytes=%d elapsedMs=%d error=%s",
               this.uri,
               code,
               offset,
               length,
               System.currentTimeMillis() - startMs,
               error.getMessage()
            );
            throw error;
         } finally {
            DownloadProgressReporter.requestFinished();
            connection.disconnect();
         }

         TellusDiagnostics.traffic(
            "PMTiles range ok uri=%s status=%d offset=%d requestedBytes=%d bytes=%d elapsedMs=%d",
            this.uri,
            code,
            offset,
            length,
            data.length,
            System.currentTimeMillis() - startMs
         );
         return data;
      }
   }

   private static InputStream openStream(HttpURLConnection connection, int code) throws IOException {
      if (code >= 400) {
         InputStream error = connection.getErrorStream();

         try {
            if (error == null) {
               throw new IOException("PMTiles HTTP error " + code);
            } else {
               byte[] message = error.readNBytes(512);
               throw new IOException("PMTiles HTTP error " + code + ": " + new String(message, StandardCharsets.UTF_8).trim());
            }
         } catch (Throwable var6) {
            if (error != null) {
               try {
                  error.close();
               } catch (Throwable var5) {
                  var6.addSuppressed(var5);
               }
            }

            throw var6;
         }
      } else {
         InputStream stream = connection.getInputStream();
         if (stream == null) {
            throw new IOException("PMTiles HTTP error " + code);
         } else {
            return stream;
         }
      }
   }

   private static byte[] decompress(byte[] payload, int compression) throws IOException {
      return switch (compression) {
         case COMPRESSION_NONE -> payload;
         case COMPRESSION_GZIP -> gunzip(payload);
         default -> throw new IOException("Unsupported PMTiles compression type " + compression);
      };
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
               throw new EOFException("Unexpected EOF while skipping bytes");
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
            throw new EOFException("Unexpected EOF while reading bytes");
         }

         if (read != 0) {
            offset += read;
            DownloadProgressReporter.bytesRead(read);
         }
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

   private static PmTilesRangeReader.Entry findTile(List<PmTilesRangeReader.Entry> entries, long tileId) {
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
         PmTilesRangeReader.Entry entry = entries.get(high);
         if (entry.runLength == 0L) {
            return entry;
         }

         if (tileId - entry.tileId < entry.runLength) {
            return entry;
         }
      }

      return null;
   }

   private static final class Directory {
      private final List<PmTilesRangeReader.Entry> entries;

      private Directory(List<PmTilesRangeReader.Entry> entries) {
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
      private final long leafDirectoryOffset;
      private final long tileDataOffset;
      private final int internalCompression;
      private final int tileCompression;
      private final int tileType;
      private final int minZoom;
      private final int maxZoom;

      private PmTilesHeader(
         long rootOffset,
         long rootLength,
         long leafDirectoryOffset,
         long tileDataOffset,
         int internalCompression,
         int tileCompression,
         int tileType,
         int minZoom,
         int maxZoom
      ) {
         this.rootOffset = rootOffset;
         this.rootLength = rootLength;
         this.leafDirectoryOffset = leafDirectoryOffset;
         this.tileDataOffset = tileDataOffset;
         this.internalCompression = internalCompression;
         this.tileCompression = tileCompression;
         this.tileType = tileType;
         this.minZoom = minZoom;
         this.maxZoom = maxZoom;
      }

      public int minZoom() {
         return this.minZoom;
      }

      public int maxZoom() {
         return this.maxZoom;
      }

      public int tileType() {
         return this.tileType;
      }
   }
}
