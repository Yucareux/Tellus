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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.zip.InflaterInputStream;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;

public final class SwissAlti3dElevationSource implements TellusCacheHandle {
   private static final int HTTP_CONNECT_TIMEOUT = 8000;
   private static final int HTTP_READ_TIMEOUT = 8000;
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private static final int MAX_FILE_CACHE = intProperty("tellus.swissalti3d.cacheFiles", 32);
   private static final int MAX_TILE_CACHE = intProperty("tellus.swissalti3d.cacheTiles", 16);
   private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.swissalti3d.enabled", "true"));
   private static final double DEFAULT_NO_DATA = -9999.0;
   private static final double MIN_LAT = 45.0;
   private static final double MAX_LAT = 49.0;
   private static final double MIN_LON = 5.0;
   private static final double MAX_LON = 11.5;
   private final Path cacheRoot;
   private final SwissAlti3dCoverageIndex coverageIndex = SwissAlti3dCoverageIndex.create();
   private final LoadingCache<SwissAlti3dElevationSource.TileCacheKey, SwissAlti3dElevationSource.TileFile> fileCache;

   public SwissAlti3dElevationSource() {
      this.cacheRoot = FMLPaths.GAMEDIR.get().resolve("tellus/cache/elevation-swissalti3d");
      this.fileCache = CacheBuilder.newBuilder()
         .maximumSize(MAX_FILE_CACHE)
         .build(new CacheLoader<SwissAlti3dElevationSource.TileCacheKey, SwissAlti3dElevationSource.TileFile>() {
            public SwissAlti3dElevationSource.TileFile load(SwissAlti3dElevationSource.TileCacheKey key) throws Exception {
               return SwissAlti3dElevationSource.this.loadTile(key);
            }
         });
      TellusCacheRegistry.register(this);
   }

   public boolean isLikelyInCoverage(double lat, double lon) {
      if (!this.canUse()) {
         return false;
      } else {
         SwissAlti3dElevationSource.Projection projection = project(lat, lon);
         return projection != null && this.coverageIndex.find(projection.x(), projection.y()) != null;
      }
   }

   public SwissAlti3dElevationSource.Sample sample(double blockX, double blockZ, double worldScale) {
      return this.sample(blockX, blockZ, worldScale, Double.NaN);
   }

   public SwissAlti3dElevationSource.Sample sample(double blockX, double blockZ, double worldScale, double targetResolutionMeters) {
      if (!this.canUse() || worldScale <= 0.0) {
         return SwissAlti3dElevationSource.Sample.none();
      } else {
         SwissAlti3dElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         return latLon == null ? SwissAlti3dElevationSource.Sample.none() : this.sample(latLon.lat(), latLon.lon(), worldScale < 2.0, targetResolutionMeters);
      }
   }

   public SwissAlti3dElevationSource.Sample sampleLocalOnly(double blockX, double blockZ, double worldScale, double targetResolutionMeters) {
      if (!this.canUse() || worldScale <= 0.0) {
         return SwissAlti3dElevationSource.Sample.none();
      } else {
         SwissAlti3dElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         return latLon == null
            ? SwissAlti3dElevationSource.Sample.none()
            : this.sampleLocalOnly(latLon.lat(), latLon.lon(), worldScale < 2.0, targetResolutionMeters);
      }
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      this.prefetchTiles(blockX, blockZ, worldScale, radius, Double.NaN);
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius, double targetResolutionMeters) {
      if (!(worldScale <= 0.0) && radius > 0 && this.canUse()) {
         boolean prefer05m = worldScale < 2.0;
         LinkedHashSet<SwissAlti3dCoverageIndex.AssetReference> refs = new LinkedHashSet<>();
         double blockRadius = Math.max(1, radius) * 256.0;

         for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
               SwissAlti3dElevationSource.LatLon latLon = toLatLon(blockX + dx * blockRadius, blockZ + dz * blockRadius, worldScale);
               if (latLon != null) {
                  SwissAlti3dElevationSource.Projection projection = project(latLon.lat(), latLon.lon());
                  if (projection != null) {
                     SwissAlti3dCoverageIndex.TileReference ref = this.coverageIndex.find(projection.x(), projection.y());
                     if (ref != null) {
                        SwissAlti3dCoverageIndex.AssetReference preferred = ref.preferredAsset(prefer05m);
                        if (preferred != null) {
                           refs.add(preferred);
                        }
                     }
                  }
               }
            }
         }

         for (SwissAlti3dCoverageIndex.AssetReference ref : refs) {
            this.prefetchTile(ref, targetResolutionMeters);
         }
      }
   }

   private SwissAlti3dElevationSource.Sample sample(double lat, double lon, boolean prefer05m, double targetResolutionMeters) {
      if (!this.canUse()) {
         return SwissAlti3dElevationSource.Sample.none();
      } else {
         SwissAlti3dElevationSource.Projection projection = project(lat, lon);
         if (projection == null) {
            return SwissAlti3dElevationSource.Sample.none();
         } else {
            SwissAlti3dCoverageIndex.TileReference tileRef = this.coverageIndex.find(projection.x(), projection.y());
            if (tileRef == null) {
               return SwissAlti3dElevationSource.Sample.none();
            } else {
               SwissAlti3dCoverageIndex.AssetReference preferred = tileRef.preferredAsset(prefer05m);
               TellusElevationSource.DemUsage preferredUsage = prefer05m
                  ? TellusElevationSource.DemUsage.SWISSALTI3D_05M
                  : TellusElevationSource.DemUsage.SWISSALTI3D_2M;
               SwissAlti3dElevationSource.Sample preferredSample = this.sample(preferred, projection, preferredUsage, targetResolutionMeters);
               if (preferredSample.usable()) {
                  return preferredSample;
               } else {
                  SwissAlti3dCoverageIndex.AssetReference alternate = tileRef.alternateAsset(prefer05m);
                  TellusElevationSource.DemUsage alternateUsage = prefer05m
                     ? TellusElevationSource.DemUsage.SWISSALTI3D_2M
                     : TellusElevationSource.DemUsage.SWISSALTI3D_05M;
                  return this.sample(alternate, projection, alternateUsage, targetResolutionMeters);
               }
            }
         }
      }
   }

   private SwissAlti3dElevationSource.Sample sampleLocalOnly(double lat, double lon, boolean prefer05m, double targetResolutionMeters) {
      if (!this.canUse()) {
         return SwissAlti3dElevationSource.Sample.none();
      } else {
         SwissAlti3dElevationSource.Projection projection = project(lat, lon);
         if (projection == null) {
            return SwissAlti3dElevationSource.Sample.none();
         } else {
            SwissAlti3dCoverageIndex.TileReference tileRef = this.coverageIndex.find(projection.x(), projection.y());
            if (tileRef == null) {
               return SwissAlti3dElevationSource.Sample.none();
            } else {
               SwissAlti3dCoverageIndex.AssetReference preferred = tileRef.preferredAsset(prefer05m);
               TellusElevationSource.DemUsage preferredUsage = prefer05m
                  ? TellusElevationSource.DemUsage.SWISSALTI3D_05M
                  : TellusElevationSource.DemUsage.SWISSALTI3D_2M;
               SwissAlti3dElevationSource.Sample preferredSample = this.sampleLocalOnly(preferred, projection, preferredUsage, targetResolutionMeters);
               if (preferredSample.usable()) {
                  return preferredSample;
               } else {
                  SwissAlti3dCoverageIndex.AssetReference alternate = tileRef.alternateAsset(prefer05m);
                  TellusElevationSource.DemUsage alternateUsage = prefer05m
                     ? TellusElevationSource.DemUsage.SWISSALTI3D_2M
                     : TellusElevationSource.DemUsage.SWISSALTI3D_05M;
                  return this.sampleLocalOnly(alternate, projection, alternateUsage, targetResolutionMeters);
               }
            }
         }
      }
   }

   private SwissAlti3dElevationSource.Sample sample(
      SwissAlti3dCoverageIndex.AssetReference assetRef,
      SwissAlti3dElevationSource.Projection projection,
      TellusElevationSource.DemUsage usage,
      double targetResolutionMeters
   ) {
      if (assetRef == null) {
         return SwissAlti3dElevationSource.Sample.none();
      } else {
         SwissAlti3dElevationSource.TileFile tile = this.getTile(assetRef, usage, targetResolutionMeters);
         if (tile == null) {
            return SwissAlti3dElevationSource.Sample.none();
         } else {
            double value = tile.sampleProjected(projection.x(), projection.y());
            return Double.isFinite(value)
               ? new SwissAlti3dElevationSource.Sample(value, usage, tile.effectiveResolutionMeters())
               : SwissAlti3dElevationSource.Sample.none();
         }
      }
   }

   private SwissAlti3dElevationSource.Sample sampleLocalOnly(
      SwissAlti3dCoverageIndex.AssetReference assetRef,
      SwissAlti3dElevationSource.Projection projection,
      TellusElevationSource.DemUsage usage,
      double targetResolutionMeters
   ) {
      if (assetRef == null) {
         return SwissAlti3dElevationSource.Sample.none();
      } else {
         SwissAlti3dElevationSource.TileFile tile = this.getTileLocalOnly(assetRef, usage, targetResolutionMeters);
         if (tile == null) {
            return SwissAlti3dElevationSource.Sample.none();
         } else {
            double value = tile.sampleProjected(projection.x(), projection.y());
            return Double.isFinite(value)
               ? new SwissAlti3dElevationSource.Sample(value, usage, tile.effectiveResolutionMeters())
               : SwissAlti3dElevationSource.Sample.none();
         }
      }
   }

   private boolean canUse() {
      return ENABLED && this.coverageIndex.available();
   }

   private void prefetchTile(SwissAlti3dCoverageIndex.AssetReference assetRef, double targetResolutionMeters) {
      SwissAlti3dElevationSource.TileCacheKey cacheKey = SwissAlti3dElevationSource.TileCacheKey.forTarget(assetRef, targetResolutionMeters);
      if (this.fileCache.getIfPresent(cacheKey) == null) {
         try {
            this.fileCache.get(cacheKey);
         } catch (ExecutionException error) {
            Tellus.LOGGER.debug("Failed to prefetch swissALTI3D tile {}", assetRef.id(), error);
         }
      }
   }

   private SwissAlti3dElevationSource.TileFile getTile(
      SwissAlti3dCoverageIndex.AssetReference assetRef, TellusElevationSource.DemUsage usage, double targetResolutionMeters
   ) {
      SwissAlti3dElevationSource.TileCacheKey cacheKey = SwissAlti3dElevationSource.TileCacheKey.forTarget(assetRef, targetResolutionMeters);
      try {
         return this.fileCache.get(cacheKey);
      } catch (ExecutionException error) {
         Tellus.LOGGER.debug("Failed to load swissALTI3D tile {}", assetRef.id(), error);
         return null;
      }
   }

   private SwissAlti3dElevationSource.TileFile getTileLocalOnly(
      SwissAlti3dCoverageIndex.AssetReference assetRef, TellusElevationSource.DemUsage usage, double targetResolutionMeters
   ) {
      SwissAlti3dElevationSource.TileCacheKey cacheKey = SwissAlti3dElevationSource.TileCacheKey.forTarget(assetRef, targetResolutionMeters);
      SwissAlti3dElevationSource.TileFile cached = this.fileCache.getIfPresent(cacheKey);
      if (cached != null) {
         return cached;
      } else {
         Path cacheDir = this.cacheRoot.resolve(assetRef.cacheDirectory());
         if (!Files.isDirectory(cacheDir)) {
            return null;
         } else {
            try {
               SwissAlti3dElevationSource.TileFile opened = SwissAlti3dElevationSource.TileFile.openLocalOnly(
                  assetRef, cacheDir, targetResolutionMeters, nativeResolutionMeters(assetRef)
               );
               SwissAlti3dElevationSource.TileFile raced = this.fileCache.asMap().putIfAbsent(cacheKey, opened);
               return raced != null ? raced : opened;
            } catch (IOException error) {
               Tellus.LOGGER.debug("Failed to load cached swissALTI3D tile {}", assetRef.id(), error);
               return null;
            }
         }
      }
   }

   private SwissAlti3dElevationSource.TileFile loadTile(SwissAlti3dElevationSource.TileCacheKey cacheKey) throws Exception {
      SwissAlti3dCoverageIndex.AssetReference assetRef = cacheKey.assetRef();
      return SwissAlti3dElevationSource.TileFile.open(
         assetRef,
         this.cacheRoot.resolve(assetRef.cacheDirectory()),
         cacheKey.targetResolutionMeters(),
         nativeResolutionMeters(assetRef)
      );
   }

   private static SwissAlti3dElevationSource.LatLon toLatLon(double blockX, double blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      return lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 ? new SwissAlti3dElevationSource.LatLon(lat, lon) : null;
   }

   private static SwissAlti3dElevationSource.Projection project(double latDeg, double lonDeg) {
      if (!(latDeg >= MIN_LAT) || !(latDeg <= MAX_LAT) || !(lonDeg >= MIN_LON) || !(lonDeg <= MAX_LON)) {
         return null;
      } else {
         double latSeconds = latDeg * 3600.0;
         double lonSeconds = lonDeg * 3600.0;
         double phi = (latSeconds - 169028.66) / 10000.0;
         double lambda = (lonSeconds - 26782.5) / 10000.0;
         double eastLv03 = 600072.37 + 211455.93 * lambda - 10938.51 * lambda * phi - 0.36 * lambda * phi * phi - 44.54 * lambda * lambda * lambda;
         double northLv03 = 200147.07
            + 308807.95 * phi
            + 3745.25 * lambda * lambda
            + 76.63 * phi * phi
            - 194.56 * lambda * lambda * phi
            + 119.79 * phi * phi * phi;
         return new SwissAlti3dElevationSource.Projection(eastLv03 + 2000000.0, northLv03 + 1000000.0);
      }
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

   private static double nativeResolutionMeters(SwissAlti3dCoverageIndex.AssetReference assetRef) {
      return assetRef.id().endsWith("_05m") ? 0.5 : 2.0;
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.SWISSALTI3D;
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

   private record TileCacheKey(SwissAlti3dCoverageIndex.AssetReference assetRef, int targetResolutionCentimeters) {
      private TileCacheKey {
         Objects.requireNonNull(assetRef, "assetRef");
      }

      private static SwissAlti3dElevationSource.TileCacheKey base(SwissAlti3dCoverageIndex.AssetReference assetRef) {
         return new SwissAlti3dElevationSource.TileCacheKey(assetRef, 0);
      }

      private static SwissAlti3dElevationSource.TileCacheKey forTarget(
         SwissAlti3dCoverageIndex.AssetReference assetRef, double targetResolutionMeters
      ) {
         if (!(targetResolutionMeters > 0.0) || !Double.isFinite(targetResolutionMeters)) {
            return base(assetRef);
         } else {
            return new SwissAlti3dElevationSource.TileCacheKey(assetRef, Math.max(1, (int)Math.round(targetResolutionMeters * 100.0)));
         }
      }

      private double targetResolutionMeters() {
         return this.targetResolutionCentimeters <= 0 ? Double.NaN : this.targetResolutionCentimeters / 100.0;
      }
   }

   public record Sample(double elevation, TellusElevationSource.DemUsage usage, double resolutionMeters) {
      private static SwissAlti3dElevationSource.Sample none() {
         return new SwissAlti3dElevationSource.Sample(Double.NaN, null, Double.NaN);
      }

      public boolean usable() {
         return this.usage != null && Double.isFinite(this.elevation);
      }
   }

   private static final class CachedRangeReader {
      private final SwissAlti3dCoverageIndex.AssetReference assetRef;
      private final Path cacheDir;
      private final boolean localOnly;
      private final Map<Long, byte[]> cachedFiles = new LinkedHashMap<>(16, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Entry<Long, byte[]> eldest) {
            return this.size() > 32;
         }
      };

      CachedRangeReader(SwissAlti3dCoverageIndex.AssetReference assetRef, Path cacheDir, boolean localOnly) {
         this.assetRef = assetRef;
         this.cacheDir = cacheDir;
         this.localOnly = localOnly;
      }

      byte[] read(long offset, int length) throws IOException {
         if (length < 0) {
            throw new EOFException("Negative range length " + length);
         } else if (length == 0) {
            return new byte[0];
         } else {
            synchronized(this.cachedFiles) {
               byte[] cached = this.cachedFiles.get(offset);
               if (cached != null && cached.length == length) {
                  return cached;
               }
            }

            Path cacheFile = this.cacheDir.resolve(offset + "_" + length + ".bin");
            if (Files.exists(cacheFile)) {
               byte[] cached = Files.readAllBytes(cacheFile);
               synchronized(this.cachedFiles) {
                  this.cachedFiles.put(offset, cached);
               }

               return cached;
            } else {
               if (this.localOnly) {
                  throw new EOFException("swissALTI3D range not cached for " + this.assetRef.id());
               }

               Files.createDirectories(this.cacheDir);
               byte[] downloaded = this.download(offset, length);
               Path tempFile = Files.createTempFile(this.cacheDir, "range_", ".bin");

               try {
                  Files.write(tempFile, downloaded);
                  Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
               } catch (IOException error) {
                  Files.deleteIfExists(tempFile);
                  throw error;
               }

               synchronized(this.cachedFiles) {
                  this.cachedFiles.put(offset, downloaded);
               }

               return downloaded;
            }
         }
      }

      private byte[] download(long offset, int length) throws IOException {
         long end = offset + length - 1L;
         HttpURLConnection connection = openConnection(URI.create(this.assetRef.url()), "bytes=" + offset + "-" + end);

         try {
            int response = connection.getResponseCode();
            if (response != 206 && response != 200) {
               throw new IOException("Unexpected swissALTI3D response " + response + " for " + this.assetRef.id());
            }

            long expectedBytes = response == 206 ? (long)length : Math.max(0L, connection.getContentLengthLong());
            DownloadProgressReporter.requestStarted(expectedBytes);

            try (InputStream in = connection.getInputStream()) {
               byte[] bytes = DownloadProgressReporter.readAllBytesWithProgress(in);
               if (bytes.length < length && response == 206) {
                  throw new EOFException("Short swissALTI3D read for " + this.assetRef.id() + ": expected " + length + ", got " + bytes.length);
               } else if (response == 200 && bytes.length < end + 1L) {
                  throw new EOFException("Short swissALTI3D full response for " + this.assetRef.id());
               } else if (response == 200) {
                  byte[] slice = new byte[length];
                  System.arraycopy(bytes, (int)offset, slice, 0, length);
                  return slice;
               } else {
                  return bytes;
               }
            }
         } finally {
            DownloadProgressReporter.requestFinished();
            connection.disconnect();
         }
      }
   }

   private static final class TileFile {
      private static final int COMPRESSION_NONE = 1;
      private static final int COMPRESSION_LZW = 5;
      private static final int COMPRESSION_DEFLATE = 8;
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
      private static final int TYPE_RATIONAL = 5;
      private static final int TYPE_DOUBLE = 12;
      private static final int TYPE_LONG8 = 16;
      private final String tileId;
      private final SwissAlti3dElevationSource.CachedRangeReader reader;
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
      private final Map<Integer, float[]> tileCache = new LinkedHashMap<>(16, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Entry<Integer, float[]> eldest) {
            return this.size() > MAX_TILE_CACHE;
         }
      };

      private TileFile(
         String tileId,
         SwissAlti3dElevationSource.CachedRangeReader reader,
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
         this.tilesPerRow = Mth.positiveCeilDiv(width, tileWidth);
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
            Tellus.LOGGER.debug("Failed to read swissALTI3D tile {} block from {}", tileIndex, this.tileId, error);
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
            throw new IOException("Unsupported swissALTI3D TIFF samples per pixel " + this.samplesPerPixel);
         } else {
            byte[] raw = switch (this.compression) {
               case COMPRESSION_NONE -> compressed;
               case COMPRESSION_LZW -> decompressLzw(compressed, expectedSize);
               case COMPRESSION_DEFLATE -> inflate(compressed, expectedSize);
               default -> throw new IOException("Unsupported swissALTI3D TIFF compression " + this.compression);
            };
            if (raw.length != expectedSize) {
               throw new IOException("Unexpected swissALTI3D tile length " + raw.length + " for " + this.tileId);
            } else {
               if (this.predictor == 3) {
                  applyFloatingPointPredictor(raw, this.tileWidth, this.tileHeight, this.bytesPerSample, this.samplesPerPixel, this.order);
               } else if (this.predictor != 1) {
                  throw new IOException("Unsupported swissALTI3D TIFF predictor " + this.predictor);
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

      private static SwissAlti3dElevationSource.TileFile open(
         SwissAlti3dCoverageIndex.AssetReference assetRef, Path cacheDir, double targetResolutionMeters, double nativeResolutionMeters
      ) throws IOException {
         return open(assetRef, cacheDir, targetResolutionMeters, nativeResolutionMeters, false);
      }

      private static SwissAlti3dElevationSource.TileFile openLocalOnly(
         SwissAlti3dCoverageIndex.AssetReference assetRef, Path cacheDir, double targetResolutionMeters, double nativeResolutionMeters
      ) throws IOException {
         return open(assetRef, cacheDir, targetResolutionMeters, nativeResolutionMeters, true);
      }

      private static SwissAlti3dElevationSource.TileFile open(
         SwissAlti3dCoverageIndex.AssetReference assetRef,
         Path cacheDir,
         double targetResolutionMeters,
         double nativeResolutionMeters,
         boolean localOnly
      ) throws IOException {
         SwissAlti3dElevationSource.CachedRangeReader reader = new SwissAlti3dElevationSource.CachedRangeReader(assetRef, cacheDir, localOnly);
         byte[] header = reader.read(0L, 32);
         ByteOrder order = switch (header[0]) {
            case 73 -> ByteOrder.LITTLE_ENDIAN;
            case 77 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Invalid swissALTI3D TIFF byte order for " + assetRef.id());
         };
         ByteBuffer headerBuf = ByteBuffer.wrap(header).order(order);
         headerBuf.getShort();
         short magic = headerBuf.getShort();
         if (magic == 42) {
            return openStandardTiff(assetRef, reader, order, headerBuf, targetResolutionMeters, nativeResolutionMeters);
         } else if (magic == 43) {
            return openBigTiff(assetRef, reader, order, headerBuf, targetResolutionMeters, nativeResolutionMeters);
         } else {
            throw new IOException("Unsupported swissALTI3D TIFF magic " + magic + " for " + assetRef.id());
         }
      }

      private static SwissAlti3dElevationSource.TileFile openStandardTiff(
         SwissAlti3dCoverageIndex.AssetReference assetRef,
         SwissAlti3dElevationSource.CachedRangeReader reader,
         ByteOrder order,
         ByteBuffer headerBuf,
         double targetResolutionMeters,
         double nativeResolutionMeters
      ) throws IOException {
         long firstIfdOffset = Integer.toUnsignedLong(headerBuf.getInt());
         SwissAlti3dElevationSource.TileFile.GeoReference baseGeoReference = readStandardGeoReference(reader, order, firstIfdOffset);
         long ifdOffset = selectIfdOffset(reader, order, false, firstIfdOffset, targetResolutionMeters, nativeResolutionMeters);
         int entryCount = Short.toUnsignedInt(ByteBuffer.wrap(reader.read(ifdOffset, 2)).order(order).getShort());
         byte[] entryBytes = reader.read(ifdOffset + 2L, entryCount * 12);
         ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
         return buildTileFile(assetRef, reader, order, false, entryCount, entries, baseGeoReference);
      }

      private static SwissAlti3dElevationSource.TileFile openBigTiff(
         SwissAlti3dCoverageIndex.AssetReference assetRef,
         SwissAlti3dElevationSource.CachedRangeReader reader,
         ByteOrder order,
         ByteBuffer headerBuf,
         double targetResolutionMeters,
         double nativeResolutionMeters
      ) throws IOException {
         short offsetSize = headerBuf.getShort();
         headerBuf.getShort();
         if (offsetSize != 8) {
            throw new IOException("Unsupported swissALTI3D BigTIFF offset size " + offsetSize + " for " + assetRef.id());
         } else {
            long firstIfdOffset = headerBuf.getLong();
            SwissAlti3dElevationSource.TileFile.GeoReference baseGeoReference = readBigGeoReference(reader, order, firstIfdOffset);
            long ifdOffset = selectIfdOffset(reader, order, true, firstIfdOffset, targetResolutionMeters, nativeResolutionMeters);
            long entryCount = ByteBuffer.wrap(reader.read(ifdOffset, 8)).order(order).getLong();
            if (entryCount < 0L || entryCount > 1000000L) {
               throw new IOException("Invalid swissALTI3D BigTIFF entry count " + entryCount + " for " + assetRef.id());
            } else {
               byte[] entryBytes = reader.read(ifdOffset + 8L, Math.toIntExact(entryCount * 20L));
               ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
               return buildTileFile(assetRef, reader, order, true, entryCount, entries, baseGeoReference);
            }
         }
      }

      private static long selectIfdOffset(
         SwissAlti3dElevationSource.CachedRangeReader reader,
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
               SwissAlti3dElevationSource.TileFile.IfdSummary summary = bigTiff
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

      private static SwissAlti3dElevationSource.TileFile.IfdSummary readStandardIfdSummary(
         SwissAlti3dElevationSource.CachedRangeReader reader, ByteOrder order, long ifdOffset
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
         return new SwissAlti3dElevationSource.TileFile.IfdSummary(width, nextIfdOffset);
      }

      private static SwissAlti3dElevationSource.TileFile.IfdSummary readBigIfdSummary(
         SwissAlti3dElevationSource.CachedRangeReader reader, ByteOrder order, long ifdOffset
      ) throws IOException {
         long entryCount = ByteBuffer.wrap(reader.read(ifdOffset, 8)).order(order).getLong();
         if (entryCount < 0L || entryCount > 1000000L) {
            throw new IOException("Invalid swissALTI3D BigTIFF entry count " + entryCount);
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
            return new SwissAlti3dElevationSource.TileFile.IfdSummary(width, nextIfdOffset);
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

      private static SwissAlti3dElevationSource.TileFile.GeoReference readStandardGeoReference(
         SwissAlti3dElevationSource.CachedRangeReader reader, ByteOrder order, long ifdOffset
      ) throws IOException {
         int entryCount = Short.toUnsignedInt(ByteBuffer.wrap(reader.read(ifdOffset, 2)).order(order).getShort());
         byte[] entryBytes = reader.read(ifdOffset + 2L, entryCount * 12);
         ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
         return readGeoReference(reader, order, false, entryCount, entries);
      }

      private static SwissAlti3dElevationSource.TileFile.GeoReference readBigGeoReference(
         SwissAlti3dElevationSource.CachedRangeReader reader, ByteOrder order, long ifdOffset
      ) throws IOException {
         long entryCount = ByteBuffer.wrap(reader.read(ifdOffset, 8)).order(order).getLong();
         if (entryCount < 0L || entryCount > 1000000L) {
            throw new IOException("Invalid swissALTI3D BigTIFF entry count " + entryCount);
         } else {
            byte[] entryBytes = reader.read(ifdOffset + 8L, Math.toIntExact(entryCount * 20L));
            ByteBuffer entries = ByteBuffer.wrap(entryBytes).order(order);
            return readGeoReference(reader, order, true, entryCount, entries);
         }
      }

      private static SwissAlti3dElevationSource.TileFile.GeoReference readGeoReference(
         SwissAlti3dElevationSource.CachedRangeReader reader,
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

      private static SwissAlti3dElevationSource.TileFile buildTileFile(
         SwissAlti3dCoverageIndex.AssetReference assetRef,
         SwissAlti3dElevationSource.CachedRangeReader reader,
         ByteOrder order,
         boolean bigTiff,
         long entryCount,
         ByteBuffer entries,
         SwissAlti3dElevationSource.TileFile.GeoReference baseGeoReference
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
            throw new IOException("Missing swissALTI3D TIFF size tags for " + assetRef.id());
         } else {
            if ((tileOffsets == null || tileByteCounts == null) && stripOffsets != null && stripByteCounts != null && rowsPerStrip > 0) {
               tileWidth = width;
               tileHeight = rowsPerStrip;
               tileOffsets = stripOffsets;
               tileByteCounts = stripByteCounts;
            }

            if (tileWidth <= 0 || tileHeight <= 0) {
               throw new IOException("Missing swissALTI3D TIFF tile geometry for " + assetRef.id());
            } else if (tileOffsets == null || tileByteCounts == null) {
               throw new IOException("Missing swissALTI3D TIFF tile offsets for " + assetRef.id());
            } else if (bitsPerSample != 32 || sampleFormat != 3) {
               throw new IOException("Unsupported swissALTI3D TIFF sample format " + sampleFormat + " bits " + bitsPerSample);
            } else {
               SwissAlti3dElevationSource.TileFile.GeoReference geoReference = geoReferenceFromTags(width, height, pixelScale, tiePoints);
               if (geoReference == null && baseGeoReference != null) {
                  // swissALTI3D COG overviews omit georeferencing tags; inherit the base transform and scale the pixel size.
                  geoReference = baseGeoReference.scaledTo(width, height);
               }

               if (geoReference != null) {
                  double originX = geoReference.originX();
                  double originY = geoReference.originY();
                  double pixelSizeX = geoReference.pixelSizeX();
                  double pixelSizeY = geoReference.pixelSizeY();
                  return new SwissAlti3dElevationSource.TileFile(
                     assetRef.id(),
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
                     originX,
                     originY,
                     pixelSizeX,
                     pixelSizeY,
                     noData
                  );
               }

               throw new IOException("Missing swissALTI3D TIFF georeferencing tags for " + assetRef.id());
            }
         }
      }

      private static SwissAlti3dElevationSource.TileFile.GeoReference geoReferenceFromTags(
         int width, int height, double[] pixelScale, double[] tiePoints
      ) {
         if (width <= 0 || height <= 0 || pixelScale == null || pixelScale.length < 2 || tiePoints == null || tiePoints.length < 6) {
            return null;
         } else {
            double pixelSizeX = pixelScale[0];
            double pixelSizeY = pixelScale[1];
            double originX = tiePoints[3] - tiePoints[0] * pixelSizeX;
            double originY = tiePoints[4] + tiePoints[1] * pixelSizeY;
            return new SwissAlti3dElevationSource.TileFile.GeoReference(width, height, originX, originY, pixelSizeX, pixelSizeY);
         }
      }

      private static int readIntValue(
         SwissAlti3dElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
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
         SwissAlti3dElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
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
         SwissAlti3dElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
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
         SwissAlti3dElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
      ) throws IOException {
         if (count <= 0L) {
            return new double[0];
         } else {
            byte[] data = readFieldData(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
            double[] values = new double[(int)count];

            for (int i = 0; i < count; i++) {
               values[i] = switch (type) {
                  case TYPE_SHORT -> buffer.getShort();
                  case TYPE_LONG -> buffer.getInt();
                  case TYPE_RATIONAL -> {
                     long numerator = Integer.toUnsignedLong(buffer.getInt());
                     long denominator = Integer.toUnsignedLong(buffer.getInt());
                     yield denominator == 0L ? 0.0 : (double)numerator / (double)denominator;
                  }
                  case TYPE_DOUBLE -> buffer.getDouble();
                  default -> throw new IOException("Unsupported TIFF double array type " + type);
               };
            }

            return values;
         }
      }

      private static String readAscii(
         SwissAlti3dElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
      ) throws IOException {
         if (type != TYPE_ASCII) {
            throw new IOException("Expected ASCII TIFF field");
         } else {
            byte[] data = readFieldData(reader, valueOrOffset, inlineBytes, count, type, order, inlineSize);
            int length = data.length;

            while (length > 0 && data[length - 1] == 0) {
               length--;
            }

            return new String(data, 0, length, StandardCharsets.US_ASCII);
         }
      }

      private static byte[] readFieldData(
         SwissAlti3dElevationSource.CachedRangeReader reader, long valueOrOffset, byte[] inlineBytes, long count, int type, ByteOrder order, int inlineSize
      ) throws IOException {
         int valueSize = switch (type) {
            case TYPE_BYTE, TYPE_ASCII -> 1;
            case TYPE_SHORT -> 2;
            case TYPE_LONG -> 4;
            case TYPE_RATIONAL, TYPE_DOUBLE, TYPE_LONG8 -> 8;
            default -> throw new IOException("Unsupported TIFF field type " + type);
         };
         long totalSize = count * valueSize;
         if (totalSize < 0L || totalSize > 2147483647L) {
            throw new IOException("Unsupported TIFF field size " + totalSize);
         } else if (totalSize <= inlineSize) {
            byte[] inline = new byte[(int)totalSize];
            System.arraycopy(inlineBytes, 0, inline, 0, (int)totalSize);
            return inline;
         } else {
            return reader.read(valueOrOffset, (int)totalSize);
         }
      }

      private static float parseNoData(String ascii) {
         if (ascii == null || ascii.isBlank()) {
            return (float)DEFAULT_NO_DATA;
         } else {
            try {
               return Float.parseFloat(ascii.trim());
            } catch (NumberFormatException error) {
               return (float)DEFAULT_NO_DATA;
            }
         }
      }

      private record IfdSummary(int width, long nextIfdOffset) {
      }

      private record GeoReference(int width, int height, double originX, double originY, double pixelSizeX, double pixelSizeY) {
         private SwissAlti3dElevationSource.TileFile.GeoReference scaledTo(int targetWidth, int targetHeight) {
            if (targetWidth <= 0 || targetHeight <= 0) {
               return null;
            } else if (targetWidth == this.width && targetHeight == this.height) {
               return this;
            } else {
               double scaleX = (double)this.width / (double)targetWidth;
               double scaleY = (double)this.height / (double)targetHeight;
               return new SwissAlti3dElevationSource.TileFile.GeoReference(
                  targetWidth,
                  targetHeight,
                  this.originX,
                  this.originY,
                  this.pixelSizeX * scaleX,
                  this.pixelSizeY * scaleY
               );
            }
         }
      }

      private static double blendFiniteSamples(float v00, float v10, float v01, float v11, double dx, double dy) {
         double sum = 0.0;
         double weight = 0.0;
         if (Float.isFinite(v00)) {
            double w = (1.0 - dx) * (1.0 - dy);
            sum += v00 * w;
            weight += w;
         }

         if (Float.isFinite(v10)) {
            double w = dx * (1.0 - dy);
            sum += v10 * w;
            weight += w;
         }

         if (Float.isFinite(v01)) {
            double w = (1.0 - dx) * dy;
            sum += v01 * w;
            weight += w;
         }

         if (Float.isFinite(v11)) {
            double w = dx * dy;
            sum += v11 * w;
            weight += w;
         }

         return weight > 0.0 ? sum / weight : Double.NaN;
      }

      private static byte[] inflate(byte[] compressed, int expectedSize) throws IOException {
         try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            byte[] raw = in.readAllBytes();
            if (raw.length < expectedSize) {
               throw new EOFException("Unexpected DEFLATE length " + raw.length + " expected " + expectedSize);
            } else if (raw.length == expectedSize) {
               return raw;
            } else {
               byte[] copy = new byte[expectedSize];
               System.arraycopy(raw, 0, copy, 0, expectedSize);
               return copy;
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
                     throw new IOException("Invalid swissALTI3D LZW stream");
                  }

                  entry = append(previous, previous[0]);
               }

               if (outputPos + entry.length > output.length) {
                  throw new IOException("swissALTI3D LZW output overflow");
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

      private static void applyFloatingPointPredictor(byte[] raw, int width, int height, int bytesPerSample, int samplesPerPixel, ByteOrder order)
         throws IOException {
         int stride = width * bytesPerSample * samplesPerPixel;
         byte[] row = new byte[stride];

         for (int y = 0; y < height; y++) {
            int rowOffset = y * stride;

            for (int i = 0; i < stride; i++) {
               row[i] = raw[rowOffset + i];
            }

            for (int i = bytesPerSample * samplesPerPixel; i < stride; i++) {
               row[i] = (byte)(row[i] + row[i - bytesPerSample * samplesPerPixel]);
            }

            for (int x = 0; x < width; x++) {
               int sampleOffset = x * bytesPerSample * samplesPerPixel;
               if (order == ByteOrder.LITTLE_ENDIAN) {
                  for (int b = 0; b < bytesPerSample; b++) {
                     raw[rowOffset + sampleOffset + b] = row[sampleOffset + bytesPerSample - 1 - b];
                  }
               } else {
                  System.arraycopy(row, sampleOffset, raw, rowOffset + sampleOffset, bytesPerSample);
               }
            }
         }
      }
   }
}
