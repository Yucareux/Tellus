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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.zip.InflaterInputStream;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;

public final class NorwayDtm1ElevationSource implements TellusCacheHandle {
   private static final int HTTP_CONNECT_TIMEOUT = 8000;
   private static final int HTTP_READ_TIMEOUT = 8000;
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private static final int MAX_FILE_CACHE = intProperty("tellus.norwaydtm1.cacheFiles", 8);
   private static final int MAX_TILE_CACHE = intProperty("tellus.norwaydtm1.cacheTiles", 16);
   private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.norwaydtm1.enabled", "true"));
   private static final double DEFAULT_NO_DATA = -9999.0;
   private static final double PROJ_A = 6378137.0;
   private static final double PROJ_INV_F = 298.257222101;
   private static final double PROJ_F = 1.0 / PROJ_INV_F;
   private static final double PROJ_E2 = 2.0 * PROJ_F - PROJ_F * PROJ_F;
   private static final double PROJ_E4 = PROJ_E2 * PROJ_E2;
   private static final double PROJ_E6 = PROJ_E4 * PROJ_E2;
   private static final double PROJ_EP2 = PROJ_E2 / (1.0 - PROJ_E2);
   private static final double PROJ_K0 = 0.9996;
   private static final double PROJ_LON_0 = Math.toRadians(15.0);
   private static final double PROJ_FALSE_EASTING = 500000.0;
   private static final double PROJ_FALSE_NORTHING = 0.0;
   private final Path cacheRoot;
   private final NorwayDtm1CoverageIndex coverageIndex = NorwayDtm1CoverageIndex.create();
   private final LoadingCache<NorwayDtm1ElevationSource.TileCacheKey, NorwayDtm1ElevationSource.TileFile> fileCache;

   public NorwayDtm1ElevationSource() {
      this.cacheRoot = FMLPaths.GAMEDIR.get().resolve("tellus/cache/elevation-norway-dtm1");
      this.fileCache = CacheBuilder.newBuilder().maximumSize(MAX_FILE_CACHE).build(new CacheLoader<NorwayDtm1ElevationSource.TileCacheKey, NorwayDtm1ElevationSource.TileFile>() {
         public NorwayDtm1ElevationSource.TileFile load(NorwayDtm1ElevationSource.TileCacheKey key) throws Exception {
            return NorwayDtm1ElevationSource.this.loadTile(key);
         }
      });
      TellusCacheRegistry.register(this);
   }

   public boolean isLikelyInCoverage(double lat, double lon) {
      return ENABLED && this.coverageIndex.available() && this.coverageIndex.findTile(lat, lon) != null;
   }

   public NorwayDtm1ElevationSource.Sample sample(double blockX, double blockZ, double worldScale) {
      return this.sample(blockX, blockZ, worldScale, Double.NaN);
   }

   public NorwayDtm1ElevationSource.Sample sample(double blockX, double blockZ, double worldScale, double targetResolutionMeters) {
      if (!this.canUse() || worldScale <= 0.0) {
         return NorwayDtm1ElevationSource.Sample.none();
      } else {
         NorwayDtm1ElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         return latLon == null ? NorwayDtm1ElevationSource.Sample.none() : this.sampleAtLatLon(latLon.lat(), latLon.lon(), targetResolutionMeters);
      }
   }

   public NorwayDtm1ElevationSource.Sample sampleLocalOnly(double blockX, double blockZ, double worldScale, double targetResolutionMeters) {
      if (!this.canUse() || worldScale <= 0.0) {
         return NorwayDtm1ElevationSource.Sample.none();
      } else {
         NorwayDtm1ElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         return latLon == null ? NorwayDtm1ElevationSource.Sample.none() : this.sampleAtLatLonLocalOnly(latLon.lat(), latLon.lon(), targetResolutionMeters);
      }
   }

   public NorwayDtm1ElevationSource.Sample sample(double lat, double lon) {
      return this.sampleAtLatLon(lat, lon, Double.NaN);
   }

   private NorwayDtm1ElevationSource.Sample sampleAtLatLon(double lat, double lon, double targetResolutionMeters) {
      if (!this.canUse()) {
         return NorwayDtm1ElevationSource.Sample.none();
      } else {
         NorwayDtm1CoverageIndex.TileReference tile = this.coverageIndex.findTile(lat, lon);
         return tile == null
            ? NorwayDtm1ElevationSource.Sample.none()
            : this.sample(tile, lat, lon, TellusElevationSource.DemUsage.NORWAYDTM1, targetResolutionMeters);
      }
   }

   private NorwayDtm1ElevationSource.Sample sampleAtLatLonLocalOnly(double lat, double lon, double targetResolutionMeters) {
      if (!this.canUse()) {
         return NorwayDtm1ElevationSource.Sample.none();
      } else {
         NorwayDtm1CoverageIndex.TileReference tile = this.coverageIndex.findTile(lat, lon);
         return tile == null
            ? NorwayDtm1ElevationSource.Sample.none()
            : this.sampleLocalOnly(tile, lat, lon, TellusElevationSource.DemUsage.NORWAYDTM1, targetResolutionMeters);
      }
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      this.prefetchTiles(blockX, blockZ, worldScale, radius, Double.NaN);
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius, double targetResolutionMeters) {
      if (!(worldScale <= 0.0) && radius > 0 && this.canUse()) {
         LinkedHashSet<NorwayDtm1CoverageIndex.TileReference> refs = new LinkedHashSet<>();
         double blockRadius = Math.max(1, radius) * 256.0;

         for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
               NorwayDtm1ElevationSource.LatLon latLon = toLatLon(blockX + dx * blockRadius, blockZ + dz * blockRadius, worldScale);
               if (latLon != null) {
                  NorwayDtm1CoverageIndex.TileReference tile = this.coverageIndex.findTile(latLon.lat(), latLon.lon());
                  if (tile != null) {
                     refs.add(tile);
                  }
               }
            }
         }

         for (NorwayDtm1CoverageIndex.TileReference ref : refs) {
            this.prefetchTile(ref, targetResolutionMeters);
         }
      }
   }

   private NorwayDtm1ElevationSource.Sample sample(
      NorwayDtm1CoverageIndex.TileReference tileRef,
      double lat,
      double lon,
      TellusElevationSource.DemUsage usage,
      double targetResolutionMeters
   ) {
      NorwayDtm1ElevationSource.Projection projection = project(lat, lon);
      if (projection == null) {
         return NorwayDtm1ElevationSource.Sample.none();
      } else {
         NorwayDtm1ElevationSource.TileFile tile = this.getTile(tileRef, usage, targetResolutionMeters);
         if (tile == null) {
            return NorwayDtm1ElevationSource.Sample.none();
         } else {
            double value = tile.sampleProjected(projection.x(), projection.y());
            return Double.isFinite(value)
               ? new NorwayDtm1ElevationSource.Sample(value, usage, tile.effectiveResolutionMeters())
               : NorwayDtm1ElevationSource.Sample.none();
         }
      }
   }

   private NorwayDtm1ElevationSource.Sample sampleLocalOnly(
      NorwayDtm1CoverageIndex.TileReference tileRef,
      double lat,
      double lon,
      TellusElevationSource.DemUsage usage,
      double targetResolutionMeters
   ) {
      NorwayDtm1ElevationSource.Projection projection = project(lat, lon);
      if (projection == null) {
         return NorwayDtm1ElevationSource.Sample.none();
      } else {
         NorwayDtm1ElevationSource.TileFile tile = this.getTileLocalOnly(tileRef, usage, targetResolutionMeters);
         if (tile == null) {
            return NorwayDtm1ElevationSource.Sample.none();
         } else {
            double value = tile.sampleProjected(projection.x(), projection.y());
            return Double.isFinite(value)
               ? new NorwayDtm1ElevationSource.Sample(value, usage, tile.effectiveResolutionMeters())
               : NorwayDtm1ElevationSource.Sample.none();
         }
      }
   }

   private boolean canUse() {
      return ENABLED && this.coverageIndex.available();
   }

   private void prefetchTile(NorwayDtm1CoverageIndex.TileReference tileRef, double targetResolutionMeters) {
      NorwayDtm1ElevationSource.TileCacheKey cacheKey = NorwayDtm1ElevationSource.TileCacheKey.forTarget(tileRef, targetResolutionMeters);
      if (this.fileCache.getIfPresent(cacheKey) == null) {
         try {
            this.fileCache.get(cacheKey);
         } catch (ExecutionException error) {
            Tellus.LOGGER.debug("Failed to prefetch Norway DTM1 tile {}", tileRef.id(), error);
         }
      }
   }

   private NorwayDtm1ElevationSource.TileFile getTile(
      NorwayDtm1CoverageIndex.TileReference tileRef, TellusElevationSource.DemUsage usage, double targetResolutionMeters
   ) {
      NorwayDtm1ElevationSource.TileCacheKey cacheKey = NorwayDtm1ElevationSource.TileCacheKey.forTarget(tileRef, targetResolutionMeters);
      try {
         return this.fileCache.get(cacheKey);
      } catch (ExecutionException error) {
         Tellus.LOGGER.debug("Failed to load Norway DTM1 tile {}", tileRef.id(), error);
         return null;
      }
   }

   private NorwayDtm1ElevationSource.TileFile getTileLocalOnly(
      NorwayDtm1CoverageIndex.TileReference tileRef, TellusElevationSource.DemUsage usage, double targetResolutionMeters
   ) {
      NorwayDtm1ElevationSource.TileCacheKey cacheKey = NorwayDtm1ElevationSource.TileCacheKey.forTarget(tileRef, targetResolutionMeters);
      NorwayDtm1ElevationSource.TileFile cached = this.fileCache.getIfPresent(cacheKey);
      if (cached != null) {
         return cached;
      } else {
         Path cacheDir = this.cacheRoot.resolve(tileRef.cacheDirectory());
         if (!Files.isDirectory(cacheDir)) {
            return null;
         } else {
            try {
               NorwayDtm1ElevationSource.TileFile opened = NorwayDtm1ElevationSource.TileFile.openLocalOnly(
                  tileRef, cacheDir, targetResolutionMeters, nativeResolutionMeters(tileRef)
               );
               NorwayDtm1ElevationSource.TileFile raced = this.fileCache.asMap().putIfAbsent(cacheKey, opened);
               return raced != null ? raced : opened;
            } catch (IOException error) {
               Tellus.LOGGER.debug("Failed to load cached Norway DTM1 tile {}", tileRef.id(), error);
               return null;
            }
         }
      }
   }

   private NorwayDtm1ElevationSource.TileFile loadTile(NorwayDtm1ElevationSource.TileCacheKey cacheKey) throws Exception {
      NorwayDtm1CoverageIndex.TileReference tileRef = cacheKey.tileRef();
      return NorwayDtm1ElevationSource.TileFile.open(
         tileRef,
         this.cacheRoot.resolve(tileRef.cacheDirectory()),
         cacheKey.targetResolutionMeters(),
         nativeResolutionMeters(tileRef)
      );
   }

   private static NorwayDtm1ElevationSource.LatLon toLatLon(double blockX, double blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      return lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 ? new NorwayDtm1ElevationSource.LatLon(lat, lon) : null;
   }

   private static NorwayDtm1ElevationSource.Projection project(double latDeg, double lonDeg) {
      if (!(latDeg >= -90.0) || !(latDeg <= 90.0) || !(lonDeg >= -180.0) || !(lonDeg <= 180.0)) {
         return null;
      } else {
         double lat = Math.toRadians(latDeg);
         double lon = Math.toRadians(lonDeg);
         double deltaLon = normalizeLongitudeRadians(lon - PROJ_LON_0);
         double sinLat = Math.sin(lat);
         double cosLat = Math.cos(lat);
         double tanLat = Math.tan(lat);
         double n = PROJ_A / Math.sqrt(1.0 - PROJ_E2 * sinLat * sinLat);
         double t = tanLat * tanLat;
         double c = PROJ_EP2 * cosLat * cosLat;
         double a = cosLat * deltaLon;
         double m = meridionalArc(lat);
         double a2 = a * a;
         double a3 = a2 * a;
         double a4 = a2 * a2;
         double a5 = a4 * a;
         double a6 = a4 * a2;
         double x = PROJ_FALSE_EASTING
            + PROJ_K0 * n * (a + (1.0 - t + c) * a3 / 6.0 + (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * PROJ_EP2) * a5 / 120.0);
         double y = PROJ_FALSE_NORTHING
            + PROJ_K0
               * (m + n * tanLat * (a2 / 2.0 + (5.0 - t + 9.0 * c + 4.0 * c * c) * a4 / 24.0
                  + (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * PROJ_EP2) * a6 / 720.0));
         return new NorwayDtm1ElevationSource.Projection(x, y);
      }
   }

   private static double meridionalArc(double lat) {
      return PROJ_A
         * ((1.0 - PROJ_E2 / 4.0 - 3.0 * PROJ_E4 / 64.0 - 5.0 * PROJ_E6 / 256.0) * lat
            - (3.0 * PROJ_E2 / 8.0 + 3.0 * PROJ_E4 / 32.0 + 45.0 * PROJ_E6 / 1024.0) * Math.sin(2.0 * lat)
            + (15.0 * PROJ_E4 / 256.0 + 45.0 * PROJ_E6 / 1024.0) * Math.sin(4.0 * lat)
            - 35.0 * PROJ_E6 / 3072.0 * Math.sin(6.0 * lat));
   }

   private static double normalizeLongitudeRadians(double lon) {
      double wrapped = lon;
      while (wrapped <= -Math.PI) {
         wrapped += Math.PI * 2.0;
      }

      while (wrapped > Math.PI) {
         wrapped -= Math.PI * 2.0;
      }

      return wrapped;
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

   private static double nativeResolutionMeters(NorwayDtm1CoverageIndex.TileReference tileRef) {
      return 1.0;
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.NORWAYDTM1;
   }

   @Override
   public void clearCache() {
      this.fileCache.invalidateAll();
      this.fileCache.cleanUp();
   }

   private record LatLon(double lat, double lon) {
   }

   private record Projection(double x, double y) {
   }

   private record TileCacheKey(NorwayDtm1CoverageIndex.TileReference tileRef, int targetResolutionCentimeters) {
      private TileCacheKey {
         Objects.requireNonNull(tileRef, "tileRef");
      }

      private static NorwayDtm1ElevationSource.TileCacheKey base(NorwayDtm1CoverageIndex.TileReference tileRef) {
         return new NorwayDtm1ElevationSource.TileCacheKey(tileRef, 0);
      }

      private static NorwayDtm1ElevationSource.TileCacheKey forTarget(NorwayDtm1CoverageIndex.TileReference tileRef, double targetResolutionMeters) {
         if (!(targetResolutionMeters > 0.0) || !Double.isFinite(targetResolutionMeters)) {
            return base(tileRef);
         } else {
            return new NorwayDtm1ElevationSource.TileCacheKey(tileRef, Math.max(1, (int)Math.round(targetResolutionMeters * 100.0)));
         }
      }

      private double targetResolutionMeters() {
         return this.targetResolutionCentimeters <= 0 ? Double.NaN : this.targetResolutionCentimeters / 100.0;
      }
   }

   public record Sample(double elevation, TellusElevationSource.DemUsage usage, double resolutionMeters) {
      private static NorwayDtm1ElevationSource.Sample none() {
         return new NorwayDtm1ElevationSource.Sample(Double.NaN, null, Double.NaN);
      }

      public boolean usable() {
         return this.usage != null && Double.isFinite(this.elevation);
      }
   }

   private static final class CachedRangeReader {
      private final NorwayDtm1CoverageIndex.TileReference tileRef;
      private final Path cacheDir;
      private final boolean localOnly;

      private CachedRangeReader(NorwayDtm1CoverageIndex.TileReference tileRef, Path cacheDir) {
         this(tileRef, cacheDir, false);
      }

      private CachedRangeReader(NorwayDtm1CoverageIndex.TileReference tileRef, Path cacheDir, boolean localOnly) {
         this.tileRef = Objects.requireNonNull(tileRef, "tileRef");
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
            } else {
               if (this.localOnly) {
                  throw new EOFException("Norway DTM1 range not cached for " + this.tileRef.id());
               }

               byte[] data = this.readRemoteRange(offset, length);
               this.writeCachedRange(cachePath, data);
               return data;
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

      private byte[] readRemoteRange(long offset, int length) throws IOException {
         String rangeHeader = "bytes=" + offset + "-" + (offset + length - 1L);
         HttpURLConnection connection = NorwayDtm1ElevationSource.openConnection(URI.create(this.tileRef.url()), rangeHeader);

         try {
            int status = connection.getResponseCode();
            if (status != 200 && status != 206) {
               throw new IOException("Unexpected Norway DTM1 HTTP status " + status + " for " + this.tileRef.id());
            } else {
               DownloadProgressReporter.requestStarted((long)length);
               try (InputStream input = connection.getInputStream()) {
                  byte[] data = DownloadProgressReporter.readAllBytesWithProgress(input);
                  if (data.length != length) {
                     throw new EOFException("Unexpected Norway DTM1 range length " + data.length + " for " + this.tileRef.id());
                  } else {
                     return data;
                  }
               }
            }
         } finally {
            DownloadProgressReporter.requestFinished();
            connection.disconnect();
         }
      }

      private void writeCachedRange(Path cachePath, byte[] data) {
         try {
            Files.createDirectories(cachePath.getParent());
            Path tempPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
            Files.write(tempPath, data);
            Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (IOException error) {
            Tellus.LOGGER.debug("Failed to cache Norway DTM1 range {}", cachePath, error);
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
      private static final int TAG_MODEL_PIXEL_SCALE = 33550;
      private static final int TAG_MODEL_TIEPOINT = 33922;
      private static final int TAG_GDAL_NODATA = 42113;
      private static final int TYPE_BYTE = 1;
      private static final int TYPE_ASCII = 2;
      private static final int TYPE_SHORT = 3;
      private static final int TYPE_LONG = 4;
      private static final int TYPE_DOUBLE = 12;
      private static final int TYPE_LONG8 = 16;
      private static final int COMPRESSION_NONE = 1;
      private static final int COMPRESSION_LZW = 5;
      private static final int COMPRESSION_DEFLATE = 8;
      private final String tileId;
      private final NorwayDtm1ElevationSource.CachedRangeReader reader;
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
      private final double originX;
      private final double originY;
      private final double pixelSizeX;
      private final double pixelSizeY;
      private final float noData;
      private final Map<Integer, float[]> tileCache;

      private TileFile(
         String tileId,
         NorwayDtm1ElevationSource.CachedRangeReader reader,
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
         int[] tileByteCounts,
         double originX,
         double originY,
         double pixelSizeX,
         double pixelSizeY,
         float noData
      ) {
         this.tileId = tileId;
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
         this.originX = originX;
         this.originY = originY;
         this.pixelSizeX = pixelSizeX;
         this.pixelSizeY = pixelSizeY;
         this.noData = noData;
         this.tileCache = new LinkedHashMap<Integer, float[]>(MAX_TILE_CACHE, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Entry<Integer, float[]> eldest) {
               return this.size() > MAX_TILE_CACHE;
            }
         };
      }

      private double sampleProjected(double x, double y) {
         double globalX = (x - this.originX) / this.pixelSizeX;
         double globalY = (this.originY - y) / this.pixelSizeY;
         if (globalX < -1.0 || globalY < -1.0 || globalX > this.width || globalY > this.height) {
            return Double.NaN;
         } else {
            int maxPixelX = this.width - 1;
            int maxPixelY = this.height - 1;
            double clampedX = Mth.clamp(globalX, 0.0, maxPixelX);
            double clampedY = Mth.clamp(globalY, 0.0, maxPixelY);
            int x0 = Mth.floor(clampedX);
            int y0 = Mth.floor(clampedY);
            int x1 = Math.min(x0 + 1, maxPixelX);
            int y1 = Math.min(y0 + 1, maxPixelY);
            double dx = clampedX - x0;
            double dy = clampedY - y0;
            float v00 = this.sampleValue(x0, y0);
            float v10 = this.sampleValue(x1, y0);
            float v01 = this.sampleValue(x0, y1);
            float v11 = this.sampleValue(x1, y1);
            return blendFiniteSamples(v00, v10, v01, v11, dx, dy);
         }
      }

      private double effectiveResolutionMeters() {
         return (Math.abs(this.pixelSizeX) + Math.abs(this.pixelSizeY)) * 0.5;
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
            Tellus.LOGGER.debug("Failed to read Norway DTM1 tile {} block from {}", tileIndex, this.tileId, error);
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
            throw new IOException("Unsupported Norway DTM1 TIFF samples per pixel " + this.samplesPerPixel);
         } else {
            byte[] raw = switch (this.compression) {
               case COMPRESSION_NONE -> compressed;
               case COMPRESSION_LZW -> decompressLzw(compressed, expectedSize);
               case COMPRESSION_DEFLATE -> inflate(compressed, expectedSize);
               default -> throw new IOException("Unsupported Norway DTM1 TIFF compression " + this.compression);
            };
            if (raw.length != expectedSize) {
               throw new IOException("Unexpected Norway DTM1 tile length " + raw.length + " for " + this.tileId);
            } else {
               if (this.predictor == 3) {
                  applyFloatingPointPredictor(raw, this.tileWidth, this.tileHeight, this.bytesPerSample, this.samplesPerPixel, this.order);
               } else if (this.predictor != 1) {
                  throw new IOException("Unsupported Norway DTM1 TIFF predictor " + this.predictor);
               }

               float[] values = new float[this.tileWidth * this.tileHeight];
               ByteBuffer buffer = ByteBuffer.wrap(raw).order(this.order);

               for (int i = 0; i < values.length; i++) {
                  float value = buffer.getFloat();
                  values[i] = value <= this.noData + 1.0F ? Float.NaN : value;
               }

               return values;
            }
         }
      }

      private static NorwayDtm1ElevationSource.TileFile open(
         NorwayDtm1CoverageIndex.TileReference tileRef, Path cacheDir, double targetResolutionMeters, double nativeResolutionMeters
      ) throws IOException {
         return open(tileRef, cacheDir, targetResolutionMeters, nativeResolutionMeters, false);
      }

      private static NorwayDtm1ElevationSource.TileFile openLocalOnly(
         NorwayDtm1CoverageIndex.TileReference tileRef, Path cacheDir, double targetResolutionMeters, double nativeResolutionMeters
      ) throws IOException {
         return open(tileRef, cacheDir, targetResolutionMeters, nativeResolutionMeters, true);
      }

      private static NorwayDtm1ElevationSource.TileFile open(
         NorwayDtm1CoverageIndex.TileReference tileRef, Path cacheDir, double targetResolutionMeters, double nativeResolutionMeters, boolean localOnly
      ) throws IOException {
         NorwayDtm1ElevationSource.CachedRangeReader reader = new NorwayDtm1ElevationSource.CachedRangeReader(tileRef, cacheDir, localOnly);
         byte[] header = reader.read(0L, 32);
         ByteOrder order = switch (header[0]) {
            case 73 -> ByteOrder.LITTLE_ENDIAN;
            case 77 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Invalid Norway DTM1 TIFF byte order for " + tileRef.id());
         };
         ByteBuffer headerBuf = ByteBuffer.wrap(header).order(order);
         headerBuf.getShort();
         short magic = headerBuf.getShort();
         if (magic == 42) {
            return openStandardTiff(tileRef, reader, order, headerBuf, targetResolutionMeters, nativeResolutionMeters);
         } else if (magic == 43) {
            return openBigTiff(tileRef, reader, order, headerBuf, targetResolutionMeters, nativeResolutionMeters);
         } else {
            throw new IOException("Unsupported Norway DTM1 TIFF magic " + magic + " for " + tileRef.id());
         }
      }

      private static NorwayDtm1ElevationSource.TileFile openStandardTiff(
         NorwayDtm1CoverageIndex.TileReference tileRef,
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         ByteOrder order,
         ByteBuffer headerBuf,
         double targetResolutionMeters,
         double nativeResolutionMeters
      ) throws IOException {
         long firstIfdOffset = Integer.toUnsignedLong(headerBuf.getInt());
         NorwayDtm1ElevationSource.TileFile.GeoReference baseGeoReference = readStandardGeoReference(reader, order, firstIfdOffset);
         long ifdOffset = selectIfdOffset(reader, order, false, firstIfdOffset, targetResolutionMeters, nativeResolutionMeters);
         int entryCount = Short.toUnsignedInt(ByteBuffer.wrap(reader.read(ifdOffset, 2)).order(order).getShort());
         byte[] entryBytes = reader.read(ifdOffset + 2L, entryCount * 12);
         ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
         return buildTileFile(tileRef, reader, order, false, entryCount, entries, baseGeoReference);
      }

      private static NorwayDtm1ElevationSource.TileFile openBigTiff(
         NorwayDtm1CoverageIndex.TileReference tileRef,
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         ByteOrder order,
         ByteBuffer headerBuf,
         double targetResolutionMeters,
         double nativeResolutionMeters
      ) throws IOException {
         short offsetSize = headerBuf.getShort();
         headerBuf.getShort();
         if (offsetSize != 8) {
            throw new IOException("Unsupported Norway DTM1 BigTIFF offset size " + offsetSize + " for " + tileRef.id());
         } else {
            long firstIfdOffset = headerBuf.getLong();
            NorwayDtm1ElevationSource.TileFile.GeoReference baseGeoReference = readBigGeoReference(reader, order, firstIfdOffset);
            long ifdOffset = selectIfdOffset(reader, order, true, firstIfdOffset, targetResolutionMeters, nativeResolutionMeters);
            long entryCount = ByteBuffer.wrap(reader.read(ifdOffset, 8)).order(order).getLong();
            if (entryCount < 0L || entryCount > 1000000L) {
               throw new IOException("Invalid Norway DTM1 BigTIFF entry count " + entryCount + " for " + tileRef.id());
            } else {
               byte[] entryBytes = reader.read(ifdOffset + 8L, Math.toIntExact(entryCount * 20L));
               ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
               return buildTileFile(tileRef, reader, order, true, entryCount, entries, baseGeoReference);
            }
         }
      }

      private static long selectIfdOffset(
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         ByteOrder order,
         boolean bigTiff,
         long firstIfdOffset,
         double targetResolutionMeters,
         double nativeResolutionMeters
      ) throws IOException {
         if (!(targetResolutionMeters > 0.0) || !Double.isFinite(targetResolutionMeters) || !(nativeResolutionMeters > 0.0)) {
            return firstIfdOffset;
         } else {
            long currentOffset = firstIfdOffset;
            long bestOffset = firstIfdOffset;
            int baseWidth = -1;
            double bestResolution = nativeResolutionMeters;
            double bestScore = Math.abs(Math.log(bestResolution / targetResolutionMeters));

            for (int level = 0; currentOffset != 0L && level < 16; level++) {
               NorwayDtm1ElevationSource.TileFile.IfdSummary summary = bigTiff
                  ? readBigIfdSummary(reader, order, currentOffset)
                  : readStandardIfdSummary(reader, order, currentOffset);
               if (summary.width() <= 0) {
                  break;
               }

               if (baseWidth < 0) {
                  baseWidth = summary.width();
               }

               double candidateResolution = nativeResolutionMeters * ((double)baseWidth / (double)summary.width());
               double candidateScore = Math.abs(Math.log(candidateResolution / targetResolutionMeters));
               if (candidateScore < bestScore - 1.0E-9
                  || Math.abs(candidateScore - bestScore) <= 1.0E-9 && candidateResolution < bestResolution) {
                  bestOffset = currentOffset;
                  bestResolution = candidateResolution;
                  bestScore = candidateScore;
               }

               currentOffset = summary.nextIfdOffset();
            }

            return bestOffset;
         }
      }

      private static NorwayDtm1ElevationSource.TileFile.IfdSummary readStandardIfdSummary(
         NorwayDtm1ElevationSource.CachedRangeReader reader, ByteOrder order, long ifdOffset
      ) throws IOException {
         int entryCount = Short.toUnsignedInt(ByteBuffer.wrap(reader.read(ifdOffset, 2)).order(order).getShort());
         byte[] ifdBytes = reader.read(ifdOffset + 2L, entryCount * 12 + 4);
         ByteBuffer entries = ByteBuffer.wrap(ifdBytes).order(order);
         int width = -1;

         for (int i = 0; i < entryCount; i++) {
            int tag = Short.toUnsignedInt(entries.getShort());
            int type = Short.toUnsignedInt(entries.getShort());
            long count = Integer.toUnsignedLong(entries.getInt());
            byte[] inlineBytes = new byte[4];
            entries.get(inlineBytes, 0, 4);
            if (tag == TAG_IMAGE_WIDTH && count == 1L) {
               width = readInlineDimension(inlineBytes, type, order);
            }
         }

         long nextIfdOffset = Integer.toUnsignedLong(ByteBuffer.wrap(ifdBytes, entryCount * 12, 4).order(order).getInt());
         return new NorwayDtm1ElevationSource.TileFile.IfdSummary(width, nextIfdOffset);
      }

      private static NorwayDtm1ElevationSource.TileFile.IfdSummary readBigIfdSummary(
         NorwayDtm1ElevationSource.CachedRangeReader reader, ByteOrder order, long ifdOffset
      ) throws IOException {
         long entryCount = ByteBuffer.wrap(reader.read(ifdOffset, 8)).order(order).getLong();
         if (entryCount < 0L || entryCount > 1000000L) {
            throw new IOException("Invalid Norway DTM1 BigTIFF entry count " + entryCount);
         } else {
            byte[] ifdBytes = reader.read(ifdOffset + 8L, Math.toIntExact(entryCount * 20L + 8L));
            ByteBuffer entries = ByteBuffer.wrap(ifdBytes).order(order);
            int width = -1;

            for (int i = 0; i < entryCount; i++) {
               int tag = Short.toUnsignedInt(entries.getShort());
               int type = Short.toUnsignedInt(entries.getShort());
               long count = entries.getLong();
               byte[] inlineBytes = new byte[8];
               entries.get(inlineBytes);
               if (tag == TAG_IMAGE_WIDTH && count == 1L) {
                  width = readInlineDimension(inlineBytes, type, order);
               }
            }

            long nextIfdOffset = ByteBuffer.wrap(ifdBytes, Math.toIntExact(entryCount * 20L), 8).order(order).getLong();
            return new NorwayDtm1ElevationSource.TileFile.IfdSummary(width, nextIfdOffset);
         }
      }

      private static int readInlineDimension(byte[] inlineBytes, int type, ByteOrder order) throws IOException {
         ByteBuffer buffer = ByteBuffer.wrap(inlineBytes).order(order);
         return switch (type) {
            case TYPE_SHORT -> Short.toUnsignedInt(buffer.getShort());
            case TYPE_LONG -> Math.toIntExact(Integer.toUnsignedLong(buffer.getInt()));
            case TYPE_LONG8 -> Math.toIntExact(buffer.getLong());
            default -> throw new IOException("Unsupported TIFF dimension type " + type);
         };
      }

      private static NorwayDtm1ElevationSource.TileFile.GeoReference readStandardGeoReference(
         NorwayDtm1ElevationSource.CachedRangeReader reader, ByteOrder order, long ifdOffset
      ) throws IOException {
         int entryCount = Short.toUnsignedInt(ByteBuffer.wrap(reader.read(ifdOffset, 2)).order(order).getShort());
         byte[] entryBytes = reader.read(ifdOffset + 2L, entryCount * 12);
         ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
         return readGeoReference(reader, order, false, entryCount, entries);
      }

      private static NorwayDtm1ElevationSource.TileFile.GeoReference readBigGeoReference(
         NorwayDtm1ElevationSource.CachedRangeReader reader, ByteOrder order, long ifdOffset
      ) throws IOException {
         long entryCount = ByteBuffer.wrap(reader.read(ifdOffset, 8)).order(order).getLong();
         if (entryCount < 0L || entryCount > 1000000L) {
            throw new IOException("Invalid Norway DTM1 BigTIFF entry count " + entryCount);
         } else {
            byte[] entryBytes = reader.read(ifdOffset + 8L, Math.toIntExact(entryCount * 20L));
            ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
            return readGeoReference(reader, order, true, entryCount, entries);
         }
      }

      private static NorwayDtm1ElevationSource.TileFile.GeoReference readGeoReference(
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         ByteOrder order,
         boolean bigTiff,
         long entryCount,
         ByteBuffer entries
      ) throws IOException {
         int inlineSize = bigTiff ? 8 : 4;
         int width = -1;
         int height = -1;
         double[] pixelScale = null;
         double[] tiePoints = null;

         for (int i = 0; i < entryCount; i++) {
            int tag = Short.toUnsignedInt(entries.getShort());
            int type = Short.toUnsignedInt(entries.getShort());
            long count = bigTiff ? entries.getLong() : Integer.toUnsignedLong(entries.getInt());
            byte[] inlineBytes = new byte[inlineSize];
            if (bigTiff) {
               entries.get(inlineBytes);
            } else {
               entries.get(inlineBytes, 0, 4);
            }

            long valueOrOffset = bigTiff
               ? ByteBuffer.wrap(inlineBytes).order(order).getLong()
               : Integer.toUnsignedLong(ByteBuffer.wrap(inlineBytes).order(order).getInt());
            switch (tag) {
               case TAG_IMAGE_WIDTH -> width = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_IMAGE_HEIGHT -> height = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_MODEL_PIXEL_SCALE -> pixelScale = readDoubleArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_MODEL_TIEPOINT -> tiePoints = readDoubleArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
            }
         }

         return width > 0 && height > 0 ? geoReferenceFromTags(width, height, pixelScale, tiePoints) : null;
      }

      private static NorwayDtm1ElevationSource.TileFile buildTileFile(
         NorwayDtm1CoverageIndex.TileReference tileRef,
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         ByteOrder order,
         boolean bigTiff,
         long entryCount,
         ByteBuffer entries,
         NorwayDtm1ElevationSource.TileFile.GeoReference baseGeoReference
      ) throws IOException {
         int inlineSize = bigTiff ? 8 : 4;
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
         double[] pixelScale = null;
         double[] tiePoints = null;
         float noData = (float)DEFAULT_NO_DATA;

         for (int i = 0; i < entryCount; i++) {
            int tag = Short.toUnsignedInt(entries.getShort());
            int type = Short.toUnsignedInt(entries.getShort());
            long count = bigTiff ? entries.getLong() : Integer.toUnsignedLong(entries.getInt());
            byte[] inlineBytes = new byte[inlineSize];
            if (bigTiff) {
               entries.get(inlineBytes);
            } else {
               entries.get(inlineBytes, 0, 4);
            }

            long valueOrOffset = bigTiff
               ? ByteBuffer.wrap(inlineBytes).order(order).getLong()
               : Integer.toUnsignedLong(ByteBuffer.wrap(inlineBytes).order(order).getInt());
            switch (tag) {
               case TAG_IMAGE_WIDTH -> width = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_IMAGE_HEIGHT -> height = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_BITS_PER_SAMPLE -> bitsPerSample = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_COMPRESSION -> compression = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_STRIP_OFFSETS -> stripOffsets = readLongArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_SAMPLES_PER_PIXEL -> samplesPerPixel = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_ROWS_PER_STRIP -> rowsPerStrip = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_STRIP_BYTE_COUNTS -> stripByteCounts = readIntArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_PREDICTOR -> predictor = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_TILE_WIDTH -> tileWidth = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_TILE_HEIGHT -> tileHeight = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_TILE_OFFSETS -> tileOffsets = readLongArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_TILE_BYTE_COUNTS -> tileByteCounts = readIntArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_SAMPLE_FORMAT -> sampleFormat = readIntValue(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_MODEL_PIXEL_SCALE -> pixelScale = readDoubleArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_MODEL_TIEPOINT -> tiePoints = readDoubleArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
               case TAG_GDAL_NODATA -> noData = parseNoData(readAscii(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize));
            }
         }

         if (width <= 0 || height <= 0) {
            throw new IOException("Missing Norway DTM1 TIFF size tags for " + tileRef.id());
         } else {
            if ((tileOffsets == null || tileByteCounts == null) && stripOffsets != null && stripByteCounts != null && rowsPerStrip > 0) {
               tileWidth = width;
               tileHeight = rowsPerStrip;
               tileOffsets = stripOffsets;
               tileByteCounts = stripByteCounts;
            }

            if (tileWidth <= 0 || tileHeight <= 0) {
               throw new IOException("Missing Norway DTM1 TIFF tile geometry for " + tileRef.id());
            } else if (tileOffsets == null || tileByteCounts == null) {
               throw new IOException("Missing Norway DTM1 TIFF tile offsets for " + tileRef.id());
            } else if (bitsPerSample != 32 || sampleFormat != 3) {
               throw new IOException("Unsupported Norway DTM1 TIFF sample format " + sampleFormat + " bits " + bitsPerSample);
            } else {
               NorwayDtm1ElevationSource.TileFile.GeoReference geoReference = geoReferenceFromTags(width, height, pixelScale, tiePoints);
               if (geoReference == null && baseGeoReference != null) {
                  // DTM1 overview IFDs can omit georeferencing; inherit the base transform and scale the pixel size.
                  geoReference = baseGeoReference.scaledTo(width, height);
               }

               if (geoReference != null) {
                  return new NorwayDtm1ElevationSource.TileFile(
                     tileRef.id(),
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
                     tileByteCounts,
                     geoReference.originX(),
                     geoReference.originY(),
                     geoReference.pixelSizeX(),
                     geoReference.pixelSizeY(),
                     noData
                  );
               }

               throw new IOException("Missing Norway DTM1 TIFF georeferencing tags for " + tileRef.id());
            }
         }
      }

      private static NorwayDtm1ElevationSource.TileFile.GeoReference geoReferenceFromTags(int width, int height, double[] pixelScale, double[] tiePoints) {
         if (width <= 0 || height <= 0 || pixelScale == null || pixelScale.length < 2 || tiePoints == null || tiePoints.length < 6) {
            return null;
         } else {
            double pixelSizeX = pixelScale[0];
            double pixelSizeY = pixelScale[1];
            double originX = tiePoints[3] - tiePoints[0] * pixelSizeX;
            double originY = tiePoints[4] + tiePoints[1] * pixelSizeY;
            return new NorwayDtm1ElevationSource.TileFile.GeoReference(width, height, originX, originY, pixelSizeX, pixelSizeY);
         }
      }

      private static int readIntValue(
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         long valueOrOffset,
         byte[] inlineBytes,
         long count,
         int type,
         ByteOrder order,
         int inlineSize
      ) throws IOException {
         if (count != 1L) {
            throw new IOException("Expected single TIFF value");
         } else {
            byte[] data = readFieldData(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
            return switch (type) {
               case TYPE_BYTE -> buffer.get() & 255;
               case TYPE_SHORT -> Short.toUnsignedInt(buffer.getShort());
               case TYPE_LONG -> Math.toIntExact(Integer.toUnsignedLong(buffer.getInt()));
               case TYPE_LONG8 -> Math.toIntExact(buffer.getLong());
               default -> throw new IOException("Unsupported TIFF value type " + type);
            };
         }
      }

      private static long[] readLongArray(
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         long valueOrOffset,
         byte[] inlineBytes,
         long count,
         int type,
         ByteOrder order,
         int inlineSize
      ) throws IOException {
         if (count <= 0L) {
            return new long[0];
         } else {
            byte[] data = readFieldData(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
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

      private static int[] readIntArray(
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         long valueOrOffset,
         byte[] inlineBytes,
         long count,
         int type,
         ByteOrder order,
         int inlineSize
      ) throws IOException {
         long[] values = readLongArray(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
         int[] output = new int[values.length];

         for (int i = 0; i < values.length; i++) {
            if (values[i] > 2147483647L) {
               throw new IOException("TIFF byte count too large " + values[i]);
            }

            output[i] = (int)values[i];
         }

         return output;
      }

      private static double[] readDoubleArray(
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         long valueOrOffset,
         byte[] inlineBytes,
         long count,
         int type,
         ByteOrder order,
         int inlineSize
      ) throws IOException {
         if (count <= 0L) {
            return new double[0];
         } else if (type != TYPE_DOUBLE) {
            throw new IOException("Unsupported TIFF double array type " + type);
         } else {
            byte[] data = readFieldData(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
            double[] values = new double[(int)count];

            for (int i = 0; i < count; i++) {
               values[i] = buffer.getDouble();
            }

            return values;
         }
      }

      private static String readAscii(
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         long valueOrOffset,
         byte[] inlineBytes,
         long count,
         int type,
         ByteOrder order,
         int inlineSize
      ) throws IOException {
         if (count <= 0L) {
            return "";
         } else if (type != TYPE_ASCII) {
            throw new IOException("Unsupported TIFF ASCII type " + type);
         } else {
            byte[] data = readFieldData(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
            int length = (int)Math.min(count, data.length);

            while (length > 0 && data[length - 1] == 0) {
               length--;
            }

            return new String(data, 0, length, StandardCharsets.US_ASCII);
         }
      }

      private static byte[] readFieldData(
         NorwayDtm1ElevationSource.CachedRangeReader reader,
         long valueOrOffset,
         byte[] inlineBytes,
         long count,
         int type,
         ByteOrder order,
         int inlineSize
      ) throws IOException {
         int size = typeSize(type);
         if (size <= 0) {
            throw new IOException("Unsupported TIFF field type " + type);
         } else {
            long byteSize = count * size;
            if (byteSize <= inlineSize) {
               byte[] data = new byte[(int)byteSize];
               System.arraycopy(inlineBytes, 0, data, 0, (int)byteSize);
               return data;
            } else if (byteSize > Integer.MAX_VALUE) {
               throw new IOException("TIFF field too large");
            } else {
               return reader.read(valueOrOffset, (int)byteSize);
            }
         }
      }

      private static float parseNoData(String value) {
         if (value == null || value.isBlank()) {
            return (float)DEFAULT_NO_DATA;
         } else {
            try {
               return Float.parseFloat(value.trim());
            } catch (NumberFormatException error) {
               return (float)DEFAULT_NO_DATA;
            }
         }
      }

      private static int typeSize(int type) {
         return switch (type) {
            case TYPE_BYTE, TYPE_ASCII -> 1;
            case TYPE_SHORT -> 2;
            case TYPE_LONG -> 4;
            case TYPE_DOUBLE, TYPE_LONG8 -> 8;
            default -> 0;
         };
      }

      private record IfdSummary(int width, long nextIfdOffset) {
      }

      private record GeoReference(int width, int height, double originX, double originY, double pixelSizeX, double pixelSizeY) {
         private NorwayDtm1ElevationSource.TileFile.GeoReference scaledTo(int newWidth, int newHeight) {
            if (this.width <= 0 || this.height <= 0 || newWidth <= 0 || newHeight <= 0) {
               return null;
            } else {
               return new NorwayDtm1ElevationSource.TileFile.GeoReference(
                  newWidth,
                  newHeight,
                  this.originX,
                  this.originY,
                  this.pixelSizeX * ((double)this.width / (double)newWidth),
                  this.pixelSizeY * ((double)this.height / (double)newHeight)
               );
            }
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
         byte[] output = new byte[expectedSize];
         int outputPos = 0;
         int bitPos = 0;
         int codeSize = 9;
         int clearCode = 256;
         int endCode = 257;
         int nextCode = 258;
         byte[][] dictionary = new byte[4096][];

         for (int i = 0; i < 256; i++) {
            dictionary[i] = new byte[]{(byte)i};
         }

         byte[] previous = null;

         while (true) {
            int code = readLzwCode(compressed, bitPos, codeSize);
            bitPos += codeSize;
            if (code < 0) {
               break;
            }

            if (code == clearCode) {
               for (int i = 258; i < dictionary.length; i++) {
                  dictionary[i] = null;
               }

               codeSize = 9;
               nextCode = 258;
               previous = null;
            } else if (code == endCode) {
               break;
            } else {
               byte[] entry;
               if (code < nextCode && dictionary[code] != null) {
                  entry = dictionary[code];
               } else {
                  if (previous == null) {
                     throw new IOException("Invalid Norway DTM1 LZW stream");
                  }

                  entry = append(previous, previous[0]);
               }

               if (outputPos + entry.length > output.length) {
                  throw new IOException("Norway DTM1 LZW output overflow");
               }

               System.arraycopy(entry, 0, output, outputPos, entry.length);
               outputPos += entry.length;
               if (previous != null && nextCode < dictionary.length) {
                  dictionary[nextCode++] = append(previous, entry[0]);
                  if (nextCode == 511 || nextCode == 1023 || nextCode == 2047) {
                     codeSize++;
                  }
               }

               previous = entry;
            }
         }

         if (outputPos != output.length) {
            byte[] copy = new byte[outputPos];
            System.arraycopy(output, 0, copy, 0, outputPos);
            return copy;
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

      private static int readLzwCode(byte[] data, int bitPos, int codeSize) {
         int totalBits = data.length * 8;
         if (bitPos + codeSize > totalBits) {
            return -1;
         } else {
            int code = 0;

            for (int i = 0; i < codeSize; i++) {
               int currentBit = bitPos + i;
               int currentByte = data[currentBit >> 3] & 255;
               int bit = currentByte >> 7 - (currentBit & 7) & 1;
               code = code << 1 | bit;
            }

            return code;
         }
      }

      private static byte[] append(byte[] prefix, byte suffix) {
         byte[] out = new byte[prefix.length + 1];
         System.arraycopy(prefix, 0, out, 0, prefix.length);
         out[prefix.length] = suffix;
         return out;
      }
   }
}
