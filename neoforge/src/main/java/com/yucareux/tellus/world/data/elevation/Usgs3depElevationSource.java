package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;

public final class Usgs3depElevationSource implements TellusCacheHandle {
   private static final String ENDPOINT = "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/exportImage";
   private static final int HTTP_CONNECT_TIMEOUT = 8000;
   private static final int HTTP_READ_TIMEOUT = 30000;
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private static final double MERCATOR_LIMIT = 20037508.342789244;
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final float FLOAT_NODATA_SENTINEL = -3.0E37F;
   private static final int TILE_PIXELS = 512;
   private static final int TAG_IMAGE_WIDTH = 256;
   private static final int TAG_IMAGE_HEIGHT = 257;
   private static final int TAG_BITS_PER_SAMPLE = 258;
   private static final int TAG_COMPRESSION = 259;
   private static final int TAG_STRIP_OFFSETS = 273;
   private static final int TAG_SAMPLES_PER_PIXEL = 277;
   private static final int TAG_ROWS_PER_STRIP = 278;
   private static final int TAG_STRIP_BYTE_COUNTS = 279;
   private static final int TAG_TILE_WIDTH = 322;
   private static final int TAG_TILE_HEIGHT = 323;
   private static final int TAG_TILE_OFFSETS = 324;
   private static final int TAG_TILE_BYTE_COUNTS = 325;
   private static final int TAG_SAMPLE_FORMAT = 339;
   private static final int TAG_GDAL_NODATA = 42113;
   private static final int TYPE_ASCII = 2;
   private static final int TYPE_SHORT = 3;
   private static final int TYPE_LONG = 4;
   private static final int TYPE_RATIONAL = 5;
   private static final int TYPE_FLOAT = 11;
   private static final int COMPRESSION_NONE = 1;
   private static final int SAMPLE_FORMAT_IEEE_FLOAT = 3;
   private static final int MAX_FILE_CACHE = intProperty("tellus.usgs.cacheFiles", 24);
   private final Path cacheRoot;
   private final LoadingCache<Usgs3depElevationSource.TileKey, Usgs3depElevationSource.TileRaster> fileCache;

   public Usgs3depElevationSource() {
      this.cacheRoot = FMLPaths.GAMEDIR.get().resolve("tellus/cache/elevation-usgs3dep");
      this.fileCache = CacheBuilder.newBuilder().maximumSize(MAX_FILE_CACHE).build(new CacheLoader<Usgs3depElevationSource.TileKey, Usgs3depElevationSource.TileRaster>() {
         public Usgs3depElevationSource.TileRaster load(Usgs3depElevationSource.TileKey key) throws Exception {
            return Usgs3depElevationSource.this.loadTile(key);
         }
      });
      TellusCacheRegistry.register(this);
   }

   public boolean isLikelyInCoverage(double lat, double lon) {
      return lat >= 14.0 && lat <= 72.6 && lon >= -170.0 && lon <= -60.0;
   }

   public double sampleElevationMeters(double blockX, double blockZ, double worldScale) {
      if (worldScale <= 0.0) {
         return Double.NaN;
      } else {
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = blockX / blocksPerDegree;
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return this.sampleLatLonElevationMeters(lat, lon, worldScale);
      }
   }

   public double sampleElevationMetersLocalOnly(double blockX, double blockZ, double worldScale) {
      if (worldScale <= 0.0) {
         return Double.NaN;
      } else {
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = blockX / blocksPerDegree;
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return this.sampleLatLonElevationMetersLocalOnly(lat, lon, worldScale);
      }
   }

   public double sampleLatLonElevationMeters(double lat, double lon, double worldScale) {
      if (!(lat >= MIN_LAT) || !(lat <= MAX_LAT) || !(lon >= MIN_LON) || !(lon <= MAX_LON)) {
         return Double.NaN;
      } else {
         Usgs3depElevationSource.ResolutionLevel level = Usgs3depElevationSource.ResolutionLevel.forWorldScale(worldScale);
         double clampedLat = Mth.clamp(lat, MIN_LAT, MAX_LAT);
         double clampedLon = Mth.clamp(lon, MIN_LON, MAX_LON);
         double mercatorX = lonToMercatorX(clampedLon);
         double mercatorY = latToMercatorY(clampedLat);
         Usgs3depElevationSource.TileKey key = Usgs3depElevationSource.TileKey.forMercator(level, mercatorX, mercatorY);
         Usgs3depElevationSource.TileRaster tile = this.getTile(key);
         return tile == null ? Double.NaN : sampleBilinear(tile, mercatorX, mercatorY, key);
      }
   }

   public double sampleLatLonElevationMetersLocalOnly(double lat, double lon, double worldScale) {
      if (!(lat >= MIN_LAT) || !(lat <= MAX_LAT) || !(lon >= MIN_LON) || !(lon <= MAX_LON)) {
         return Double.NaN;
      } else {
         Usgs3depElevationSource.ResolutionLevel level = Usgs3depElevationSource.ResolutionLevel.forWorldScale(worldScale);
         double clampedLat = Mth.clamp(lat, MIN_LAT, MAX_LAT);
         double clampedLon = Mth.clamp(lon, MIN_LON, MAX_LON);
         double mercatorX = lonToMercatorX(clampedLon);
         double mercatorY = latToMercatorY(clampedLat);
         Usgs3depElevationSource.TileKey key = Usgs3depElevationSource.TileKey.forMercator(level, mercatorX, mercatorY);
         Usgs3depElevationSource.TileRaster tile = this.getTileLocalOnly(key);
         return tile == null ? Double.NaN : sampleBilinear(tile, mercatorX, mercatorY, key);
      }
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      if (!(worldScale <= 0.0) && radius > 0) {
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = blockX / blocksPerDegree;
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         if (lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON) {
            Usgs3depElevationSource.ResolutionLevel level = Usgs3depElevationSource.ResolutionLevel.forWorldScale(worldScale);
            Usgs3depElevationSource.TileKey center = Usgs3depElevationSource.TileKey.forLatLon(level, lat, lon);
            this.prefetchNeighborhood(center, Math.max(1, radius));
         }
      }
   }

   private void prefetchNeighborhood(Usgs3depElevationSource.TileKey center, int tileRadius) {
      for (int dy = -tileRadius; dy <= tileRadius; dy++) {
         for (int dx = -tileRadius; dx <= tileRadius; dx++) {
            this.prefetchTile(new Usgs3depElevationSource.TileKey(center.level(), center.tileX() + dx, center.tileY() + dy));
         }
      }
   }

   private void prefetchTile(Usgs3depElevationSource.TileKey key) {
      if (this.fileCache.getIfPresent(key) == null) {
         try {
            this.fileCache.get(key);
         } catch (ExecutionException error) {
            Tellus.LOGGER.debug("Failed to prefetch USGS 3DEP tile {}", key, error);
         }
      }
   }

   private Usgs3depElevationSource.TileRaster getTile(Usgs3depElevationSource.TileKey key) {
      try {
         return this.fileCache.get(key);
      } catch (ExecutionException error) {
         Tellus.LOGGER.debug("Failed to load USGS 3DEP tile {}", key, error);
         return null;
      }
   }

   private Usgs3depElevationSource.TileRaster getTileLocalOnly(Usgs3depElevationSource.TileKey key) {
      Usgs3depElevationSource.TileRaster cached = this.fileCache.getIfPresent(key);
      if (cached != null) {
         return cached;
      } else {
         Path cachePath = key.cachePath(this.cacheRoot);
         if (!Files.exists(cachePath)) {
            return null;
         } else {
            try {
               Usgs3depElevationSource.TileRaster tile = readTiffRaster(Files.readAllBytes(cachePath));
               Usgs3depElevationSource.TileRaster raced = this.fileCache.asMap().putIfAbsent(key, tile);
               return raced != null ? raced : tile;
            } catch (IOException error) {
               Tellus.LOGGER.debug("Failed to load cached USGS 3DEP tile {}", key, error);
               return null;
            }
         }
      }
   }

   private Usgs3depElevationSource.TileRaster loadTile(Usgs3depElevationSource.TileKey key) throws Exception {
      Path cachePath = key.cachePath(this.cacheRoot);
      byte[] data;
      if (Files.exists(cachePath)) {
         try {
            data = Files.readAllBytes(cachePath);
            return readTiffRaster(data);
         } catch (IOException error) {
            Files.deleteIfExists(cachePath);
         }
      }

      data = this.downloadTile(key);
      this.writeCacheFile(cachePath, data);
      return readTiffRaster(data);
   }

   private byte[] downloadTile(Usgs3depElevationSource.TileKey key) throws IOException {
      URI uri = URI.create(key.url());
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
      connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
      connection.setReadTimeout(HTTP_READ_TIMEOUT);
      connection.setRequestProperty("User-Agent", HTTP_USER_AGENT);

      try {
         int status = connection.getResponseCode();
         if (status != 200) {
            throw new IOException("Unexpected USGS 3DEP status " + status + " for " + key.url());
         } else {
            String contentType = connection.getContentType();
            String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
            if (!normalizedContentType.contains("tif")) {
               byte[] errorBody;
               try (InputStream input = connection.getInputStream()) {
                  errorBody = input.readNBytes(512);
               }

               throw new IOException("Unexpected USGS 3DEP content type " + contentType + ": " + new String(errorBody, StandardCharsets.UTF_8));
            } else {
               DownloadProgressReporter.requestStarted(Math.max(0L, connection.getContentLengthLong()));
               try (InputStream input = connection.getInputStream()) {
                  return DownloadProgressReporter.readAllBytesWithProgress(input);
               } finally {
                  DownloadProgressReporter.requestFinished();
               }
            }
         }
      } finally {
         connection.disconnect();
      }
   }

   private void writeCacheFile(Path cachePath, byte[] data) throws IOException {
      Files.createDirectories(cachePath.getParent());
      Path tempPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
      Files.write(tempPath, data);

      try {
         Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException error) {
         Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private static Usgs3depElevationSource.TileRaster readTiffRaster(byte[] data) throws IOException {
      if (data.length < 8) {
         throw new IOException("Invalid TIFF header");
      } else {
         ByteOrder order = switch (data[0]) {
            case 73 -> ByteOrder.LITTLE_ENDIAN;
            case 77 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Invalid TIFF byte order");
         };
         ByteBuffer header = ByteBuffer.wrap(data).order(order);
         header.position(2);
         short magic = header.getShort();
         if (magic != 42) {
            throw new IOException("Expected standard TIFF magic");
         } else {
            int ifdOffset = header.getInt();
            if (ifdOffset < 0 || ifdOffset + 2 > data.length) {
               throw new IOException("Invalid TIFF IFD offset");
            } else {
               ByteBuffer ifdHeader = ByteBuffer.wrap(data, ifdOffset, data.length - ifdOffset).order(order);
               int entryCount = Short.toUnsignedInt(ifdHeader.getShort());
               int entriesOffset = ifdOffset + 2;
               if (entriesOffset + entryCount * 12 > data.length) {
                  throw new IOException("Invalid TIFF entry table");
               } else {
                  ByteBuffer entries = ByteBuffer.wrap(data, entriesOffset, entryCount * 12).order(order);
                  int width = -1;
                  int height = -1;
                  int tileWidth = -1;
                  int tileHeight = -1;
                  int compression = COMPRESSION_NONE;
                  int bitsPerSample = -1;
                  int sampleFormat = SAMPLE_FORMAT_IEEE_FLOAT;
                  int samplesPerPixel = 1;
                  int rowsPerStrip = -1;
                  long[] tileOffsets = null;
                  int[] tileByteCounts = null;
                  long[] stripOffsets = null;
                  int[] stripByteCounts = null;
                  float noData = Float.NaN;
                  boolean hasNoData = false;

                  for (int i = 0; i < entryCount; i++) {
                     int tag = Short.toUnsignedInt(entries.getShort());
                     int type = Short.toUnsignedInt(entries.getShort());
                     long count = Integer.toUnsignedLong(entries.getInt());
                     int valueOffset = entries.getInt();
                     switch (tag) {
                        case TAG_IMAGE_WIDTH -> width = readIntValue(data, order, type, count, valueOffset);
                        case TAG_IMAGE_HEIGHT -> height = readIntValue(data, order, type, count, valueOffset);
                        case TAG_BITS_PER_SAMPLE -> bitsPerSample = readIntValue(data, order, type, count, valueOffset);
                        case TAG_COMPRESSION -> compression = readIntValue(data, order, type, count, valueOffset);
                        case TAG_STRIP_OFFSETS -> stripOffsets = readLongArray(data, order, type, count, valueOffset);
                        case TAG_SAMPLES_PER_PIXEL -> samplesPerPixel = readIntValue(data, order, type, count, valueOffset);
                        case TAG_ROWS_PER_STRIP -> rowsPerStrip = readIntValue(data, order, type, count, valueOffset);
                        case TAG_STRIP_BYTE_COUNTS -> stripByteCounts = readIntArray(data, order, type, count, valueOffset);
                        case TAG_TILE_WIDTH -> tileWidth = readIntValue(data, order, type, count, valueOffset);
                        case TAG_TILE_HEIGHT -> tileHeight = readIntValue(data, order, type, count, valueOffset);
                        case TAG_TILE_OFFSETS -> tileOffsets = readLongArray(data, order, type, count, valueOffset);
                        case TAG_TILE_BYTE_COUNTS -> tileByteCounts = readIntArray(data, order, type, count, valueOffset);
                        case TAG_SAMPLE_FORMAT -> sampleFormat = readIntValue(data, order, type, count, valueOffset);
                        case TAG_GDAL_NODATA -> {
                           noData = readNoDataValue(data, order, count, valueOffset);
                           hasNoData = Float.isFinite(noData);
                        }
                     }
                  }

                  if (width <= 0 || height <= 0) {
                     throw new IOException("Missing TIFF dimensions");
                  } else if (compression != COMPRESSION_NONE) {
                     throw new IOException("Unsupported TIFF compression " + compression);
                  } else if (bitsPerSample != 32 || sampleFormat != SAMPLE_FORMAT_IEEE_FLOAT || samplesPerPixel != 1) {
                     throw new IOException(
                        "Unsupported TIFF raster format bits=" + bitsPerSample + " sampleFormat=" + sampleFormat + " samples=" + samplesPerPixel
                     );
                  } else {
                     if ((tileOffsets == null || tileByteCounts == null) && stripOffsets != null && stripByteCounts != null && rowsPerStrip > 0) {
                        tileWidth = width;
                        tileHeight = rowsPerStrip;
                        tileOffsets = stripOffsets;
                        tileByteCounts = stripByteCounts;
                     }

                     if (tileOffsets == null || tileByteCounts == null || tileWidth <= 0 || tileHeight <= 0) {
                        throw new IOException("Missing TIFF tile offsets");
                     } else {
                        FloatRaster raster = FloatRaster.create(width, height);
                        int tilesPerRow = (int)Math.ceil((double)width / tileWidth);

                        for (int tileIndex = 0; tileIndex < tileOffsets.length && tileIndex < tileByteCounts.length; tileIndex++) {
                           int byteCount = tileByteCounts[tileIndex];
                           long offset = tileOffsets[tileIndex];
                           if (byteCount <= 0) {
                              continue;
                           }

                           if (offset < 0L || offset + byteCount > data.length) {
                              throw new IOException("Invalid TIFF tile range");
                           }

                           int tileX = tileIndex % tilesPerRow;
                           int tileY = tileIndex / tilesPerRow;
                           int destX = tileX * tileWidth;
                           int destY = tileY * tileHeight;
                           int copyWidth = Math.max(0, Math.min(tileWidth, width - destX));
                           int copyHeight = Math.max(0, Math.min(tileHeight, height - destY));
                           if (copyWidth > 0 && copyHeight > 0) {
                              ByteBuffer tileData = ByteBuffer.wrap(data, (int)offset, byteCount).order(order);

                              for (int row = 0; row < tileHeight; row++) {
                                 for (int col = 0; col < tileWidth; col++) {
                                    float value = tileData.getFloat();
                                    if (row < copyHeight && col < copyWidth) {
                                       raster.set(destX + col, destY + row, normalizeSample(value, hasNoData, noData));
                                    }
                                 }
                              }
                           }
                        }

                        return new Usgs3depElevationSource.TileRaster(raster);
                     }
                  }
               }
            }
         }
      }
   }

   private static float normalizeSample(float value, boolean hasNoData, float noData) {
      if (!Float.isFinite(value) || value <= FLOAT_NODATA_SENTINEL || hasNoData && Float.compare(value, noData) == 0) {
         return Float.NaN;
      } else {
         return value;
      }
   }

   private static int readIntValue(byte[] data, ByteOrder order, int type, long count, int valueOffset) throws IOException {
      if (count != 1L) {
         throw new IOException("Unsupported TIFF scalar count " + count);
      } else {
         return switch (type) {
            case TYPE_SHORT -> Short.toUnsignedInt(valueBuffer(data, order, TYPE_SHORT, 1L, valueOffset).getShort());
            case TYPE_LONG -> valueOffset;
            default -> throw new IOException("Unsupported TIFF scalar type " + type);
         };
      }
   }

   private static long[] readLongArray(byte[] data, ByteOrder order, int type, long count, int valueOffset) throws IOException {
      if (count > Integer.MAX_VALUE) {
         throw new IOException("Unsupported TIFF array count " + count);
      } else {
         ByteBuffer values = valueBuffer(data, order, type, count, valueOffset);
         int intCount = (int)count;
         long[] result = new long[intCount];

         for (int i = 0; i < intCount; i++) {
            result[i] = switch (type) {
               case TYPE_SHORT -> Short.toUnsignedLong(values.getShort());
               case TYPE_LONG -> Integer.toUnsignedLong(values.getInt());
               default -> throw new IOException("Unsupported TIFF integer type " + type);
            };
         }

         return result;
      }
   }

   private static int[] readIntArray(byte[] data, ByteOrder order, int type, long count, int valueOffset) throws IOException {
      long[] longs = readLongArray(data, order, type, count, valueOffset);
      int[] result = new int[longs.length];

      for (int i = 0; i < longs.length; i++) {
         result[i] = Math.toIntExact(longs[i]);
      }

      return result;
   }

   private static float readNoDataValue(byte[] data, ByteOrder order, long count, int valueOffset) throws IOException {
      ByteBuffer values = valueBuffer(data, order, TYPE_ASCII, count, valueOffset);
      byte[] bytes = new byte[(int)count];
      values.get(bytes);
      String text = new String(bytes, StandardCharsets.US_ASCII).trim();
      int nullIndex = text.indexOf(0);
      if (nullIndex >= 0) {
         text = text.substring(0, nullIndex);
      }

      try {
         return (float)Double.parseDouble(text);
      } catch (NumberFormatException error) {
         return Float.NaN;
      }
   }

   private static ByteBuffer valueBuffer(byte[] data, ByteOrder order, int type, long count, int valueOffset) throws IOException {
      int byteCount = Math.toIntExact(count * typeSize(type));
      if (byteCount <= 4) {
         byte[] inline = ByteBuffer.allocate(4).order(order).putInt(valueOffset).array();
         return ByteBuffer.wrap(inline, 0, byteCount).order(order);
      } else if (valueOffset < 0 || valueOffset + byteCount > data.length) {
         throw new IOException("Invalid TIFF value offset");
      } else {
         return ByteBuffer.wrap(data, valueOffset, byteCount).order(order);
      }
   }

   private static int typeSize(int type) throws IOException {
      return switch (type) {
         case TYPE_ASCII -> 1;
         case TYPE_SHORT -> 2;
         case TYPE_LONG, TYPE_FLOAT -> 4;
         case TYPE_RATIONAL -> 8;
         default -> throw new IOException("Unsupported TIFF field type " + type);
      };
   }

   private static double sampleBilinear(Usgs3depElevationSource.TileRaster tile, double mercatorX, double mercatorY, Usgs3depElevationSource.TileKey key) {
      double localX = (mercatorX - key.minX()) / key.level().metersPerPixel();
      double localY = (key.maxY() - mercatorY) / key.level().metersPerPixel();
      int maxX = tile.raster().width() - 1;
      int maxY = tile.raster().height() - 1;
      int x0 = Mth.clamp(Mth.floor(localX), 0, maxX);
      int y0 = Mth.clamp(Mth.floor(localY), 0, maxY);
      int x1 = Math.min(x0 + 1, maxX);
      int y1 = Math.min(y0 + 1, maxY);
      double dx = localX - x0;
      double dy = localY - y0;
      float v00 = tile.raster().get(x0, y0);
      float v10 = tile.raster().get(x1, y0);
      float v01 = tile.raster().get(x0, y1);
      float v11 = tile.raster().get(x1, y1);
      return blendFiniteSamples(v00, v10, v01, v11, dx, dy);
   }

   private static double blendFiniteSamples(float v00, float v10, float v01, float v11, double dx, double dy) {
      double w00 = (1.0 - dx) * (1.0 - dy);
      double w10 = dx * (1.0 - dy);
      double w01 = (1.0 - dx) * dy;
      double w11 = dx * dy;
      double sum = 0.0;
      double weight = 0.0;
      if (Float.isFinite(v00)) {
         sum += v00 * w00;
         weight += w00;
      }

      if (Float.isFinite(v10)) {
         sum += v10 * w10;
         weight += w10;
      }

      if (Float.isFinite(v01)) {
         sum += v01 * w01;
         weight += w01;
      }

      if (Float.isFinite(v11)) {
         sum += v11 * w11;
         weight += w11;
      }

      return weight <= 0.0 ? Double.NaN : sum / weight;
   }

   private static double lonToMercatorX(double lon) {
      return Math.toRadians(lon) * 6378137.0;
   }

   private static double latToMercatorY(double lat) {
      double clampedLat = Mth.clamp(lat, MIN_LAT, MAX_LAT);
      double radians = Math.toRadians(clampedLat);
      return 6378137.0 * Math.log(Math.tan(Math.PI * 0.25 + radians * 0.5));
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(1, Integer.parseInt(value));
         } catch (NumberFormatException error) {
            return defaultValue;
         }
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.USGS;
   }

   @Override
   public void clearCache() {
      this.fileCache.invalidateAll();
      this.fileCache.cleanUp();
   }

   private record TileRaster(FloatRaster raster) {
      private TileRaster {
         Objects.requireNonNull(raster, "raster");
      }
   }

   private record TileKey(Usgs3depElevationSource.ResolutionLevel level, int tileX, int tileY) {
      private static Usgs3depElevationSource.TileKey forLatLon(Usgs3depElevationSource.ResolutionLevel level, double lat, double lon) {
         return forMercator(level, lonToMercatorX(lon), latToMercatorY(lat));
      }

      private static Usgs3depElevationSource.TileKey forMercator(Usgs3depElevationSource.ResolutionLevel level, double mercatorX, double mercatorY) {
         double span = level.tileSpanMeters();
         int tileX = Mth.floor((mercatorX + MERCATOR_LIMIT) / span);
         int tileY = Mth.floor((MERCATOR_LIMIT - mercatorY) / span);
         return new Usgs3depElevationSource.TileKey(level, tileX, tileY);
      }

      private double minX() {
         return -MERCATOR_LIMIT + this.tileX * this.level.tileSpanMeters();
      }

      private double maxX() {
         return this.minX() + this.level.tileSpanMeters();
      }

      private double maxY() {
         return MERCATOR_LIMIT - this.tileY * this.level.tileSpanMeters();
      }

      private double minY() {
         return this.maxY() - this.level.tileSpanMeters();
      }

      private Path cachePath(Path root) {
         return root.resolve(this.level.id()).resolve(String.format(Locale.ROOT, "%05d/%05d.tif", this.tileY, this.tileX));
      }

      private String url() {
         return String.format(
            Locale.ROOT,
            "%s?bbox=%.3f,%.3f,%.3f,%.3f&bboxSR=3857&imageSR=3857&size=%d,%d&format=tiff&pixelType=F32&interpolation=RSP_BilinearInterpolation&f=image",
            ENDPOINT,
            this.minX(),
            this.minY(),
            this.maxX(),
            this.maxY(),
            TILE_PIXELS,
            TILE_PIXELS
         );
      }
   }

   private static enum ResolutionLevel {
      M1("r1", 1.0),
      M3("r3", 3.4359738368),
      M10("r10", 10.3079215104),
      M30("r30", 30.9220809814);

      private final String id;
      private final double metersPerPixel;

      private ResolutionLevel(String id, double metersPerPixel) {
         this.id = id;
         this.metersPerPixel = metersPerPixel;
      }

      private String id() {
         return this.id;
      }

      private double metersPerPixel() {
         return this.metersPerPixel;
      }

      private double tileSpanMeters() {
         return this.metersPerPixel * TILE_PIXELS;
      }

      private static Usgs3depElevationSource.ResolutionLevel forWorldScale(double worldScale) {
         if (worldScale <= 1.5) {
            return M1;
         } else if (worldScale <= 5.0) {
            return M3;
         } else if (worldScale <= 15.0) {
            return M10;
         } else {
            return M30;
         }
      }
   }
}
