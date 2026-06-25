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
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.zip.InflaterInputStream;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;

public final class CopernicusDemElevationSource implements TellusCacheHandle {
   private static final String GLO_30_BASE_URL = "https://copernicus-dem-30m.s3.eu-central-1.amazonaws.com";
   private static final String GLO_90_BASE_URL = "https://copernicus-dem-90m.s3.eu-central-1.amazonaws.com";
   private static final int HTTP_CONNECT_TIMEOUT = 8000;
   private static final int HTTP_READ_TIMEOUT = 8000;
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private static final int MAX_FILE_CACHE = intProperty("tellus.copernicus.cacheFiles", 16);
   private static final int MAX_TILE_CACHE = intProperty("tellus.copernicus.cacheTiles", 16);
   private static final CopernicusDemElevationSource.TileRecord MISSING_TILE = new CopernicusDemElevationSource.TileRecord(null, true);
   private final Path cacheRoot;
   private final LoadingCache<CopernicusDemElevationSource.TileKey, CopernicusDemElevationSource.TileRecord> fileCache;

   public CopernicusDemElevationSource() {
      this.cacheRoot = FMLPaths.GAMEDIR.get().resolve("tellus/cache/elevation-copernicus");
      this.fileCache = CacheBuilder.newBuilder().maximumSize(MAX_FILE_CACHE).build(new CacheLoader<CopernicusDemElevationSource.TileKey, CopernicusDemElevationSource.TileRecord>() {
         public CopernicusDemElevationSource.TileRecord load(CopernicusDemElevationSource.TileKey key) throws Exception {
            return CopernicusDemElevationSource.this.loadTile(key);
         }
      });
      TellusCacheRegistry.register(this);
   }

   public double sampleElevationMeters(double blockX, double blockZ, double worldScale) {
      if (worldScale <= 0.0) {
         return Double.NaN;
      } else {
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = blockX / blocksPerDegree;
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return this.sampleElevationMeters(lat, lon);
      }
   }

   public double sampleElevationMetersLocalOnly(double blockX, double blockZ, double worldScale) {
      if (worldScale <= 0.0) {
         return Double.NaN;
      } else {
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = blockX / blocksPerDegree;
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return this.sampleElevationMetersLocalOnly(lat, lon);
      }
   }

   public double sampleElevationMeters(double lat, double lon) {
      if (!(lat >= -90.0) || !(lat <= 90.0) || !(lon >= -180.0) || !(lon <= 180.0)) {
         return Double.NaN;
      } else {
         double sample30 = this.sampleAtLevel(CopernicusDemElevationSource.Level.GLO_30, lat, lon);
         if (!Double.isNaN(sample30)) {
            return sample30;
         } else {
            return this.sampleAtLevel(CopernicusDemElevationSource.Level.GLO_90, lat, lon);
         }
      }
   }

   public double sampleElevationMetersLocalOnly(double lat, double lon) {
      if (!(lat >= -90.0) || !(lat <= 90.0) || !(lon >= -180.0) || !(lon <= 180.0)) {
         return Double.NaN;
      } else {
         double sample30 = this.sampleAtLevelLocalOnly(CopernicusDemElevationSource.Level.GLO_30, lat, lon);
         return !Double.isNaN(sample30) ? sample30 : this.sampleAtLevelLocalOnly(CopernicusDemElevationSource.Level.GLO_90, lat, lon);
      }
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      if (!(worldScale <= 0.0) && radius > 0) {
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = blockX / blocksPerDegree;
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);

         CopernicusDemElevationSource.TileKey center30 = tileKeyForLatLon(CopernicusDemElevationSource.Level.GLO_30, lat, lon);
         if (center30 != null) {
            this.prefetchNeighborhood(center30, 1);
            CopernicusDemElevationSource.TileRecord cached = this.fileCache.getIfPresent(center30);
            if (cached != null && cached.missing()) {
               CopernicusDemElevationSource.TileKey center90 = tileKeyForLatLon(CopernicusDemElevationSource.Level.GLO_90, lat, lon);
               if (center90 != null) {
                  this.prefetchNeighborhood(center90, 1);
               }
            }
         }
      }
   }

   private void prefetchNeighborhood(CopernicusDemElevationSource.TileKey center, int tileRadius) {
      for (int dLat = -tileRadius; dLat <= tileRadius; dLat++) {
         for (int dLon = -tileRadius; dLon <= tileRadius; dLon++) {
            CopernicusDemElevationSource.TileKey key = new CopernicusDemElevationSource.TileKey(center.level(), center.tileLat() + dLat, center.tileLon() + dLon);
            if (key.tileLat() >= -90 && key.tileLat() <= 89 && key.tileLon() >= -180 && key.tileLon() <= 179) {
               this.prefetchTile(key);
            }
         }
      }
   }

   private void prefetchTile(CopernicusDemElevationSource.TileKey key) {
      if (this.fileCache.getIfPresent(key) == null) {
         try {
            this.fileCache.get(key);
         } catch (ExecutionException error) {
            Tellus.LOGGER.debug("Failed to prefetch Copernicus DEM tile {}", key, error);
         }
      }
   }

   private double sampleAtLevel(CopernicusDemElevationSource.Level level, double lat, double lon) {
      double clampedLat = Math.max(-90.0, Math.min(89.999999, lat));
      double clampedLon = Math.max(-180.0, Math.min(179.999999, lon));
      int samplesPerDegree = level.samplesPerDegree;
      int maxPixelX = 360 * samplesPerDegree - 1;
      int maxPixelY = 180 * samplesPerDegree - 1;
      double globalX = Mth.clamp((clampedLon + 180.0) * samplesPerDegree, 0.0, maxPixelX);
      double globalY = Mth.clamp((90.0 - clampedLat) * samplesPerDegree, 0.0, maxPixelY);
      int x0 = Mth.floor(globalX);
      int y0 = Mth.floor(globalY);
      int x1 = Math.min(x0 + 1, maxPixelX);
      int y1 = Math.min(y0 + 1, maxPixelY);
      double dx = globalX - x0;
      double dy = globalY - y0;
      float v00 = this.samplePixel(level, x0, y0);
      float v10 = this.samplePixel(level, x1, y0);
      float v01 = this.samplePixel(level, x0, y1);
      float v11 = this.samplePixel(level, x1, y1);
      return blendFiniteSamples(v00, v10, v01, v11, dx, dy);
   }

   private double sampleAtLevelLocalOnly(CopernicusDemElevationSource.Level level, double lat, double lon) {
      double clampedLat = Math.max(-90.0, Math.min(89.999999, lat));
      double clampedLon = Math.max(-180.0, Math.min(179.999999, lon));
      int samplesPerDegree = level.samplesPerDegree;
      int maxPixelX = 360 * samplesPerDegree - 1;
      int maxPixelY = 180 * samplesPerDegree - 1;
      double globalX = Mth.clamp((clampedLon + 180.0) * samplesPerDegree, 0.0, maxPixelX);
      double globalY = Mth.clamp((90.0 - clampedLat) * samplesPerDegree, 0.0, maxPixelY);
      int x0 = Mth.floor(globalX);
      int y0 = Mth.floor(globalY);
      int x1 = Math.min(x0 + 1, maxPixelX);
      int y1 = Math.min(y0 + 1, maxPixelY);
      double dx = globalX - x0;
      double dy = globalY - y0;
      float v00 = this.samplePixelLocalOnly(level, x0, y0);
      float v10 = this.samplePixelLocalOnly(level, x1, y0);
      float v01 = this.samplePixelLocalOnly(level, x0, y1);
      float v11 = this.samplePixelLocalOnly(level, x1, y1);
      return blendFiniteSamples(v00, v10, v01, v11, dx, dy);
   }

   private float samplePixel(CopernicusDemElevationSource.Level level, int pixelX, int pixelY) {
      int tileColumn = Math.floorDiv(pixelX, level.samplesPerDegree);
      int tileRow = Math.floorDiv(pixelY, level.samplesPerDegree);
      int tileLon = tileColumn - 180;
      int tileLat = 89 - tileRow;
      CopernicusDemElevationSource.TileFile tile = this.getTile(new CopernicusDemElevationSource.TileKey(level, tileLat, tileLon));
      if (tile == null) {
         return Float.NaN;
      } else {
         int localX = pixelX - tileColumn * level.samplesPerDegree;
         int localY = pixelY - tileRow * level.samplesPerDegree;
         return tile.sampleValue(localX, localY);
      }
   }

   private float samplePixelLocalOnly(CopernicusDemElevationSource.Level level, int pixelX, int pixelY) {
      int tileColumn = Math.floorDiv(pixelX, level.samplesPerDegree);
      int tileRow = Math.floorDiv(pixelY, level.samplesPerDegree);
      int tileLon = tileColumn - 180;
      int tileLat = 89 - tileRow;
      CopernicusDemElevationSource.TileFile tile = this.getTileLocalOnly(new CopernicusDemElevationSource.TileKey(level, tileLat, tileLon));
      if (tile == null) {
         return Float.NaN;
      } else {
         int localX = pixelX - tileColumn * level.samplesPerDegree;
         int localY = pixelY - tileRow * level.samplesPerDegree;
         return tile.sampleValue(localX, localY);
      }
   }

   private CopernicusDemElevationSource.TileFile getTile(CopernicusDemElevationSource.TileKey key) {
      try {
         CopernicusDemElevationSource.TileRecord record = this.fileCache.get(key);
         return record.missing() ? null : record.file();
      } catch (ExecutionException error) {
         Tellus.LOGGER.debug("Failed to load Copernicus DEM tile {}", key, error);
         return null;
      }
   }

   private CopernicusDemElevationSource.TileFile getTileLocalOnly(CopernicusDemElevationSource.TileKey key) {
      CopernicusDemElevationSource.TileRecord cached = this.fileCache.getIfPresent(key);
      if (cached != null) {
         return cached.missing() ? null : cached.file();
      } else {
         Path tileCacheDir = this.cacheRoot.resolve(key.cacheDirectory());
         Path missingMarker = tileCacheDir.resolve(".missing");
         if (Files.exists(missingMarker)) {
            this.fileCache.put(key, MISSING_TILE);
            return null;
         } else if (!Files.isDirectory(tileCacheDir)) {
            return null;
         } else {
            try {
               CopernicusDemElevationSource.TileRecord record = new CopernicusDemElevationSource.TileRecord(
                  CopernicusDemElevationSource.TileFile.openLocalOnly(key.url(), tileCacheDir), false
               );
               CopernicusDemElevationSource.TileRecord raced = this.fileCache.asMap().putIfAbsent(key, record);
               CopernicusDemElevationSource.TileRecord resolved = raced != null ? raced : record;
               return resolved.missing() ? null : resolved.file();
            } catch (IOException error) {
               Tellus.LOGGER.debug("Failed to load cached Copernicus DEM tile {}", key, error);
               return null;
            }
         }
      }
   }

   private CopernicusDemElevationSource.TileRecord loadTile(CopernicusDemElevationSource.TileKey key) throws Exception {
      Path tileCacheDir = this.cacheRoot.resolve(key.cacheDirectory());
      Path missingMarker = tileCacheDir.resolve(".missing");
      if (Files.exists(missingMarker)) {
         return MISSING_TILE;
      }

      try {
         return new CopernicusDemElevationSource.TileRecord(CopernicusDemElevationSource.TileFile.open(key.url(), tileCacheDir), false);
      } catch (CopernicusDemElevationSource.MissingTileException error) {
         this.writeMissingMarker(missingMarker);
         return MISSING_TILE;
      }
   }

   private void writeMissingMarker(Path missingMarker) {
      try {
         Files.createDirectories(missingMarker.getParent());
         if (!Files.exists(missingMarker)) {
            Files.writeString(missingMarker, "missing");
         }
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to persist Copernicus missing-tile marker {}", missingMarker, error);
      }
   }

   private static CopernicusDemElevationSource.TileKey tileKeyForLatLon(CopernicusDemElevationSource.Level level, double lat, double lon) {
      if (!(lat >= -90.0) || !(lat <= 90.0) || !(lon >= -180.0) || !(lon <= 180.0)) {
         return null;
      } else {
         int tileLat = Math.max(-90, Math.min(89, (int)Math.floor(Math.min(lat, 89.999999))));
         int tileLon = Math.max(-180, Math.min(179, (int)Math.floor(Math.min(lon, 179.999999))));
         return new CopernicusDemElevationSource.TileKey(level, tileLat, tileLon);
      }
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

   private static HttpURLConnection openConnection(URI uri, String rangeHeader) throws IOException {
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
      connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
      connection.setReadTimeout(HTTP_READ_TIMEOUT);
      connection.setRequestProperty("User-Agent", HTTP_USER_AGENT);
      if (rangeHeader != null) {
         connection.setRequestProperty("Range", rangeHeader);
      }

      return connection;
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
      return TellusCacheDomain.COPERNICUS;
   }

   @Override
   public void clearCache() {
      this.fileCache.invalidateAll();
      this.fileCache.cleanUp();
   }

   private static final class MissingTileException extends IOException {
      private MissingTileException(String message) {
         super(message);
      }
   }

   private static final class RangeReader {
      private final String url;
      private final Path cacheDir;
      private final boolean localOnly;

      private RangeReader(String url, Path cacheDir) {
         this(url, cacheDir, false);
      }

      private RangeReader(String url, Path cacheDir, boolean localOnly) {
         this.url = Objects.requireNonNull(url, "url");
         this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
         this.localOnly = localOnly;
      }

      private byte[] read(long offset, int length) throws IOException {
         if (length <= 0) {
            return new byte[0];
         } else {
            Path cachePath = this.cacheDir.resolve(offset + "-" + length + ".bin");
            byte[] cached = this.readCachedRange(cachePath, length);
            if (cached != null) {
               return cached;
            } else if (this.localOnly) {
               throw new EOFException("Copernicus DEM range not cached for " + this.url);
            }

            String rangeHeader = "bytes=" + offset + "-" + (offset + length - 1L);
            HttpURLConnection connection = CopernicusDemElevationSource.openConnection(URI.create(this.url), rangeHeader);
            try {
               int status = connection.getResponseCode();
               if (status == 404) {
                  throw new CopernicusDemElevationSource.MissingTileException("Missing Copernicus DEM tile " + this.url);
               } else if (status == 416) {
                  throw new EOFException("Range not satisfiable for " + this.url);
               } else if (status != 206 && status != 200) {
                  throw new IOException("Unexpected HTTP status " + status + " for " + this.url);
               } else {
                  DownloadProgressReporter.requestStarted((long)length);
                  try (InputStream input = connection.getInputStream()) {
                     byte[] data = DownloadProgressReporter.readAllBytesWithProgress(input);
                     if (data.length != length) {
                        throw new EOFException("Unexpected range length " + data.length + " for " + this.url);
                     } else {
                        this.writeCachedRange(cachePath, data);
                        return data;
                     }
                  } finally {
                     DownloadProgressReporter.requestFinished();
                  }
               }
            } finally {
               connection.disconnect();
            }
         }
      }

      private byte[] readCachedRange(Path cachePath, int expectedLength) throws IOException {
         if (!Files.exists(cachePath)) {
            return null;
         } else {
            byte[] data = Files.readAllBytes(cachePath);
            if (data.length == expectedLength) {
               return data;
            } else {
               Files.deleteIfExists(cachePath);
               return null;
            }
         }
      }

      private void writeCachedRange(Path cachePath, byte[] data) {
         try {
            Files.createDirectories(cachePath.getParent());
            Path tempPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
            Files.write(tempPath, data);
            Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (IOException error) {
            Tellus.LOGGER.debug("Failed to cache Copernicus DEM range {}", cachePath, error);
         }
      }
   }

   private static final class TileFile {
      private static final int TAG_IMAGE_WIDTH = 256;
      private static final int TAG_IMAGE_HEIGHT = 257;
      private static final int TAG_BITS_PER_SAMPLE = 258;
      private static final int TAG_COMPRESSION = 259;
      private static final int TAG_STRIP_OFFSETS = 273;
      private static final int TAG_SAMPLES_PER_PIXEL = 277;
      private static final int TAG_ROWS_PER_STRIP = 278;
      private static final int TAG_STRIP_BYTE_COUNTS = 279;
      private static final int TAG_PREDICTOR = 317;
      private static final int TAG_TILE_WIDTH = 322;
      private static final int TAG_TILE_HEIGHT = 323;
      private static final int TAG_TILE_OFFSETS = 324;
      private static final int TAG_TILE_BYTE_COUNTS = 325;
      private static final int TAG_SAMPLE_FORMAT = 339;
      private static final int TYPE_BYTE = 1;
      private static final int TYPE_SHORT = 3;
      private static final int TYPE_LONG = 4;
      private static final int TYPE_LONG8 = 16;
      private static final int COMPRESSION_LZW = 5;
      private static final int COMPRESSION_DEFLATE = 8;
      private final String url;
      private final CopernicusDemElevationSource.RangeReader reader;
      private final ByteOrder order;
      private final int width;
      private final int height;
      private final int tileWidth;
      private final int tileHeight;
      private final int tilesPerRow;
      private final int compression;
      private final int predictor;
      private final int bytesPerSample;
      private final int samplesPerPixel;
      private final long[] tileOffsets;
      private final int[] tileByteCounts;
      private final Map<Integer, float[]> tileCache;

      private TileFile(
         String url,
         CopernicusDemElevationSource.RangeReader reader,
         ByteOrder order,
         int width,
         int height,
         int tileWidth,
         int tileHeight,
         int compression,
         int predictor,
         int bytesPerSample,
         int samplesPerPixel,
         long[] tileOffsets,
         int[] tileByteCounts
      ) {
         this.url = url;
         this.reader = reader;
         this.order = order;
         this.width = width;
         this.height = height;
         this.tileWidth = tileWidth;
         this.tileHeight = tileHeight;
         this.tilesPerRow = (int)Math.ceil((double)width / tileWidth);
         this.compression = compression;
         this.predictor = predictor;
         this.bytesPerSample = bytesPerSample;
         this.samplesPerPixel = samplesPerPixel;
         this.tileOffsets = tileOffsets;
         this.tileByteCounts = tileByteCounts;
         this.tileCache = new LinkedHashMap<Integer, float[]>(MAX_TILE_CACHE, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Entry<Integer, float[]> eldest) {
               return this.size() > MAX_TILE_CACHE;
            }
         };
      }

      private float sampleValue(int pixelX, int pixelY) {
         int clampedX = Mth.clamp(pixelX, 0, this.width - 1);
         int clampedY = Mth.clamp(pixelY, 0, this.height - 1);
         int tileX = clampedX / this.tileWidth;
         int tileY = clampedY / this.tileHeight;
         int tileIndex = tileY * this.tilesPerRow + tileX;

         try {
            float[] tile = this.getTile(tileIndex);
            int localX = clampedX - tileX * this.tileWidth;
            int localY = clampedY - tileY * this.tileHeight;
            return tile[localX + localY * this.tileWidth];
         } catch (IOException error) {
            Tellus.LOGGER.debug("Failed to read Copernicus DEM tile {} from {}", tileIndex, this.url, error);
            return Float.NaN;
         }
      }

      private float[] getTile(int tileIndex) throws IOException {
         synchronized(this.tileCache) {
            float[] cached = this.tileCache.get(tileIndex);
            if (cached != null) {
               return cached;
            }
         }

         float[] tile = this.readTile(tileIndex);
         synchronized(this.tileCache) {
            this.tileCache.put(tileIndex, tile);
            return tile;
         }
      }

      private float[] readTile(int tileIndex) throws IOException {
         long offset = this.tileOffsets[tileIndex];
         int length = this.tileByteCounts[tileIndex];
         byte[] compressed = this.reader.read(offset, length);
         int expectedSize = this.tileWidth * this.tileHeight * this.bytesPerSample * this.samplesPerPixel;
         if (this.samplesPerPixel != 1) {
            throw new IOException("Unsupported TIFF samples per pixel " + this.samplesPerPixel);
         } else {
            byte[] raw = switch (this.compression) {
               case COMPRESSION_LZW -> decompressLzw(compressed, expectedSize);
               case COMPRESSION_DEFLATE -> inflate(compressed, expectedSize);
               default -> throw new IOException("Unsupported TIFF compression " + this.compression);
            };
            if (this.predictor == 3) {
               applyFloatingPointPredictor(raw, this.tileWidth, this.tileHeight, this.bytesPerSample, this.samplesPerPixel, this.order);
            } else if (this.predictor != 1) {
               throw new IOException("Unsupported TIFF predictor " + this.predictor);
            }

            float[] values = new float[this.tileWidth * this.tileHeight];
            ByteBuffer buffer = ByteBuffer.wrap(raw).order(this.order);

            for (int i = 0; i < values.length; i++) {
               values[i] = buffer.getFloat();
            }

            return values;
         }
      }

      private static CopernicusDemElevationSource.TileFile open(String url, Path cacheDir) throws IOException {
         return open(url, cacheDir, false);
      }

      private static CopernicusDemElevationSource.TileFile openLocalOnly(String url, Path cacheDir) throws IOException {
         return open(url, cacheDir, true);
      }

      private static CopernicusDemElevationSource.TileFile open(String url, Path cacheDir, boolean localOnly) throws IOException {
         CopernicusDemElevationSource.RangeReader reader = new CopernicusDemElevationSource.RangeReader(url, cacheDir, localOnly);
         byte[] header = reader.read(0L, 16);
         ByteOrder order = switch (header[0]) {
            case 73 -> ByteOrder.LITTLE_ENDIAN;
            case 77 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Invalid TIFF byte order");
         };
         ByteBuffer headerBuf = ByteBuffer.wrap(header).order(order);
         headerBuf.getShort();
         short magic = headerBuf.getShort();
         if (magic != 42) {
            throw new IOException("Expected standard TIFF magic");
         } else {
            long ifdOffset = Integer.toUnsignedLong(headerBuf.getInt());
            int entryCount = Short.toUnsignedInt(ByteBuffer.wrap(reader.read(ifdOffset, 2)).order(order).getShort());
            byte[] entryBytes = reader.read(ifdOffset + 2L, entryCount * 12);
            ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
            int width = -1;
            int height = -1;
            int tileWidth = -1;
            int tileHeight = -1;
            int compression = -1;
            int predictor = 1;
            int bitsPerSample = -1;
            int sampleFormat = -1;
            int samplesPerPixel = 1;
            long[] tileOffsets = null;
            int[] tileByteCounts = null;
            int rowsPerStrip = -1;
            long[] stripOffsets = null;
            int[] stripByteCounts = null;

            for (int i = 0; i < entryCount; i++) {
               int tag = Short.toUnsignedInt(entries.getShort());
               int type = Short.toUnsignedInt(entries.getShort());
               long count = Integer.toUnsignedLong(entries.getInt());
               long value = Integer.toUnsignedLong(entries.getInt());
               switch (tag) {
                  case TAG_IMAGE_WIDTH:
                     width = readIntValue(type, count, value);
                     break;
                  case TAG_IMAGE_HEIGHT:
                     height = readIntValue(type, count, value);
                     break;
                  case TAG_BITS_PER_SAMPLE:
                     bitsPerSample = readIntValue(type, count, value);
                     break;
                  case TAG_COMPRESSION:
                     compression = readIntValue(type, count, value);
                     break;
                  case TAG_STRIP_OFFSETS:
                     stripOffsets = readLongArray(reader, value, count, type, order);
                     break;
                  case TAG_SAMPLES_PER_PIXEL:
                     samplesPerPixel = readIntValue(type, count, value);
                     break;
                  case TAG_ROWS_PER_STRIP:
                     rowsPerStrip = readIntValue(type, count, value);
                     break;
                  case TAG_STRIP_BYTE_COUNTS:
                     stripByteCounts = readIntArray(reader, value, count, type, order);
                     break;
                  case TAG_PREDICTOR:
                     predictor = readIntValue(type, count, value);
                     break;
                  case TAG_TILE_WIDTH:
                     tileWidth = readIntValue(type, count, value);
                     break;
                  case TAG_TILE_HEIGHT:
                     tileHeight = readIntValue(type, count, value);
                     break;
                  case TAG_TILE_OFFSETS:
                     tileOffsets = readLongArray(reader, value, count, type, order);
                     break;
                  case TAG_TILE_BYTE_COUNTS:
                     tileByteCounts = readIntArray(reader, value, count, type, order);
                     break;
                  case TAG_SAMPLE_FORMAT:
                     sampleFormat = readIntValue(type, count, value);
               }
            }

            if (width <= 0 || height <= 0) {
               throw new IOException("Missing TIFF size tags");
            } else {
               if ((tileOffsets == null || tileByteCounts == null) && stripOffsets != null && stripByteCounts != null && rowsPerStrip > 0) {
                  tileWidth = width;
                  tileHeight = rowsPerStrip;
                  tileOffsets = stripOffsets;
                  tileByteCounts = stripByteCounts;
               }

               if (tileWidth <= 0 || tileHeight <= 0) {
                  throw new IOException("Missing TIFF tile geometry");
               } else if (tileOffsets == null || tileByteCounts == null) {
                  throw new IOException("Missing TIFF tile offsets");
               } else if (bitsPerSample == 32 && sampleFormat == 3) {
                  return new CopernicusDemElevationSource.TileFile(
                     url,
                     reader,
                     order,
                     width,
                     height,
                     tileWidth,
                     tileHeight,
                     compression,
                     predictor,
                     4,
                     samplesPerPixel,
                     tileOffsets,
                     tileByteCounts
                  );
               } else {
                  throw new IOException("Unsupported TIFF sample format " + sampleFormat + " bits " + bitsPerSample);
               }
            }
         }
      }

      private static int readIntValue(int type, long count, long value) throws IOException {
         if (count != 1L) {
            throw new IOException("Expected single TIFF value");
         } else if (type == TYPE_BYTE || type == TYPE_SHORT || type == TYPE_LONG || type == TYPE_LONG8) {
            return (int)value;
         } else {
            throw new IOException("Unsupported TIFF value type " + type);
         }
      }

      private static long[] readLongArray(CopernicusDemElevationSource.RangeReader reader, long valueOrOffset, long count, int type, ByteOrder order)
         throws IOException {
         if (count <= 0L) {
            return new long[0];
         } else {
            int size = typeSize(type);
            if (size <= 0) {
               throw new IOException("Unsupported TIFF array type " + type);
            } else {
               long byteSize = count * size;
               byte[] data = byteSize <= 4L
                  ? ByteBuffer.allocate(4).order(order).putInt((int)valueOrOffset).array()
                  : reader.read(valueOrOffset, (int)byteSize);
               ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
               long[] values = new long[(int)count];

               for (int i = 0; i < count; i++) {
                  values[i] = switch (type) {
                     case TYPE_BYTE -> buffer.get() & 255;
                     case TYPE_SHORT -> Short.toUnsignedInt(buffer.getShort());
                     case TYPE_LONG -> Integer.toUnsignedLong(buffer.getInt());
                     case TYPE_LONG8 -> buffer.getLong();
                     default -> throw new IOException("Unsupported TIFF array type " + type);
                  };
               }

               return values;
            }
         }
      }

      private static int[] readIntArray(CopernicusDemElevationSource.RangeReader reader, long valueOrOffset, long count, int type, ByteOrder order)
         throws IOException {
         long[] values = readLongArray(reader, valueOrOffset, count, type, order);
         int[] output = new int[values.length];

         for (int i = 0; i < values.length; i++) {
            if (values[i] > 2147483647L) {
               throw new IOException("TIFF byte count too large " + values[i]);
            }

            output[i] = (int)values[i];
         }

         return output;
      }

      private static int typeSize(int type) {
         return switch (type) {
            case TYPE_BYTE -> 1;
            case TYPE_SHORT -> 2;
            case TYPE_LONG -> 4;
            case TYPE_LONG8 -> 8;
            default -> 0;
         };
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
            } else {
               return output;
            }
         }
      }

      private static byte[] decompressLzw(byte[] compressed, int expectedSize) throws IOException {
         byte[][] table = new byte[4096][];

         for (int i = 0; i < 256; i++) {
            table[i] = new byte[]{(byte)i};
         }

         int clearCode = 256;
         int endCode = 257;
         int codeSize = 9;
         int nextCode = 258;
         byte[] output = new byte[expectedSize];
         int outPos = 0;
         CopernicusDemElevationSource.TileFile.LzwBitReader reader = new CopernicusDemElevationSource.TileFile.LzwBitReader(compressed);
         byte[] previous = null;

         while (true) {
            int code = reader.read(codeSize);
            if (code < 0) {
               break;
            }

            if (code == clearCode) {
               for (int i = 0; i < 256; i++) {
                  table[i] = new byte[]{(byte)i};
               }

               for (int i = 256; i < table.length; i++) {
                  table[i] = null;
               }

               codeSize = 9;
               nextCode = 258;
               previous = null;
            } else {
               if (code == endCode) {
                  break;
               }

               byte[] entry;
               if (code < nextCode && table[code] != null) {
                  entry = table[code];
               } else {
                  if (code != nextCode || previous == null) {
                     throw new IOException("Invalid LZW code " + code);
                  }

                  entry = concat(previous, previous[0]);
               }

               if (outPos + entry.length > output.length) {
                  throw new IOException("Unexpected LZW output size");
               }

               System.arraycopy(entry, 0, output, outPos, entry.length);
               outPos += entry.length;
               if (previous != null && nextCode < table.length) {
                  table[nextCode++] = concat(previous, entry[0]);
                  int threshold = (1 << codeSize) - 1;
                  if (nextCode == threshold && codeSize < 12) {
                     codeSize++;
                  }
               }

               previous = entry;
               if (outPos == output.length) {
                  break;
               }
            }
         }

         if (outPos != output.length) {
            throw new IOException("Unexpected LZW output length " + outPos);
         } else {
            return output;
         }
      }

      private static void applyFloatingPointPredictor(byte[] data, int width, int height, int bytesPerSample, int samplesPerPixel, ByteOrder order)
         throws IOException {
         int rowBytes = width * bytesPerSample * samplesPerPixel;
         if (rowBytes > 0) {
            if (samplesPerPixel > 0 && rowBytes % (bytesPerSample * samplesPerPixel) == 0) {
               int wordCount = rowBytes / bytesPerSample;
               byte[] tmp = new byte[rowBytes];

               for (int row = 0; row < height; row++) {
                  int rowStart = row * rowBytes;
                  int count = rowBytes;

                  for (int offset = rowStart; count > samplesPerPixel; count -= samplesPerPixel) {
                     for (int i = 0; i < samplesPerPixel; i++) {
                        int target = offset + samplesPerPixel;
                        int value = (data[target] & 255) + (data[offset] & 255);
                        data[target] = (byte)value;
                        offset++;
                     }
                  }

                  System.arraycopy(data, rowStart, tmp, 0, rowBytes);

                  for (int word = 0; word < wordCount; word++) {
                     int base = rowStart + word * bytesPerSample;

                     for (int byteIndex = 0; byteIndex < bytesPerSample; byteIndex++) {
                        int sourceIndex = order == ByteOrder.BIG_ENDIAN ? byteIndex * wordCount + word : (bytesPerSample - byteIndex - 1) * wordCount + word;
                        data[base + byteIndex] = tmp[sourceIndex];
                     }
                  }
               }
            } else {
               throw new IOException("Invalid floating point predictor stride");
            }
         }
      }

      private static byte[] concat(byte[] prefix, byte suffix) {
         byte[] combined = new byte[prefix.length + 1];
         System.arraycopy(prefix, 0, combined, 0, prefix.length);
         combined[prefix.length] = suffix;
         return combined;
      }

      private static final class LzwBitReader {
         private final byte[] data;
         private int bitPos;

         private LzwBitReader(byte[] data) {
            this.data = data;
         }

         private int read(int bits) {
            int totalBits = this.data.length * 8;
            if (this.bitPos + bits > totalBits) {
               return -1;
            } else {
               int result = 0;

               for (int i = 0; i < bits; i++) {
                  int byteIndex = (this.bitPos + i) / 8;
                  int bitIndex = 7 - (this.bitPos + i) % 8;
                  int bit = this.data[byteIndex] >> bitIndex & 1;
                  result = result << 1 | bit;
               }

               this.bitPos += bits;
               return result;
            }
         }
      }
   }

   private static enum Level {
      GLO_30("10", 3600, GLO_30_BASE_URL),
      GLO_90("30", 1200, GLO_90_BASE_URL);

      private final String productCode;
      private final int samplesPerDegree;
      private final String baseUrl;

      private Level(String productCode, int samplesPerDegree, String baseUrl) {
         this.productCode = productCode;
         this.samplesPerDegree = samplesPerDegree;
         this.baseUrl = baseUrl;
      }
   }

   private record TileRecord(CopernicusDemElevationSource.TileFile file, boolean missing) {
   }

   private record TileKey(CopernicusDemElevationSource.Level level, int tileLat, int tileLon) {
      private String cacheDirectory() {
         String ns = this.tileLat >= 0 ? "N" : "S";
         String ew = this.tileLon >= 0 ? "E" : "W";
         int lat = Math.abs(this.tileLat);
         int lon = Math.abs(this.tileLon);
         return this.level.name().toLowerCase(Locale.ROOT) + "/" + String.format("%s%02d_%s%03d", ns, lat, ew, lon);
      }

      private String url() {
         String ns = this.tileLat >= 0 ? "N" : "S";
         String ew = this.tileLon >= 0 ? "E" : "W";
         int lat = Math.abs(this.tileLat);
         int lon = Math.abs(this.tileLon);
         String name = String.format(
            "Copernicus_DSM_COG_%s_%s%02d_00_%s%03d_00_DEM",
            this.level.productCode,
            ns,
            lat,
            ew,
            lon
         );
         return this.level.baseUrl + "/" + name + "/" + name + ".tif";
      }
   }
}
