package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.Tellus;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;

public class ArcticDemElevationSource implements TellusCacheHandle {
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double BUCKET_SIZE_METERS = 100000.0;
   private static final int HTTP_CONNECT_TIMEOUT = 8000;
   private static final int HTTP_READ_TIMEOUT = 8000;
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private static final double NO_DATA = -9999.0;
   private static final boolean DEBUG_DEM = Boolean.getBoolean("tellus.debug.dem");
   private static final double DEBUG_SAMPLE_LAT = doubleProperty("tellus.debug.dem.sampleLat", Double.NaN);
   private static final double DEBUG_SAMPLE_LON = doubleProperty("tellus.debug.dem.sampleLon", Double.NaN);
   private static final double DEBUG_SAMPLE_EPS = doubleProperty("tellus.debug.dem.sampleEps", 1.0E-4);
   private static final double PROJ_A = 6378137.0;
   private static final double PROJ_E = 0.081819190843;
   private final ArcticDemElevationSource.Dataset dataset;
   private final Path cacheRoot;
   private final LoadingCache<ArcticDemElevationSource.Tier, ArcticDemElevationSource.TileIndex> indexCache;
   private final LoadingCache<String, ArcticDemElevationSource.TileFile> fileCache;
   private final ThreadLocal<ArcticDemElevationSource.TileLookup> lookupCache = ThreadLocal.withInitial(ArcticDemElevationSource.TileLookup::new);
   private final boolean enabled;
   private final int maxTileCache;
   private final long failureCooldownMs;
   private volatile long suspendedUntilMs;
   private final AtomicInteger debugMask = new AtomicInteger();
   private final AtomicInteger debugSampleMask = new AtomicInteger();
   private volatile ArcticDemElevationSource.Tier lastLoggedTier;

   public ArcticDemElevationSource() {
      this(ArcticDemElevationSource.Dataset.ARCTIC);
   }

   ArcticDemElevationSource(ArcticDemElevationSource.Dataset dataset) {
      this.dataset = dataset;
      this.enabled = Boolean.parseBoolean(System.getProperty(dataset.enabledPropertyKey(), "true"));
      int maxFileCache = intProperty(dataset.cacheFilesPropertyKey(), 8);
      this.maxTileCache = intProperty(dataset.cacheTilesPropertyKey(), 16);
      this.failureCooldownMs = longProperty(dataset.retryPropertyKey(), 60000L);
      this.cacheRoot = FMLPaths.GAMEDIR.get().resolve(dataset.cacheRelativePath());
      this.indexCache = CacheBuilder.newBuilder()
         .maximumSize(ArcticDemElevationSource.Tier.values().length)
         .build(new CacheLoader<ArcticDemElevationSource.Tier, ArcticDemElevationSource.TileIndex>() {
            public ArcticDemElevationSource.TileIndex load(ArcticDemElevationSource.Tier tier) throws Exception {
               return ArcticDemElevationSource.TileIndex.load(tier, ArcticDemElevationSource.this.cacheRoot, dataset.baseUrl());
            }
         });
      this.fileCache = CacheBuilder.newBuilder().maximumSize(maxFileCache).build(new CacheLoader<String, ArcticDemElevationSource.TileFile>() {
         public ArcticDemElevationSource.TileFile load(String url) throws Exception {
            return ArcticDemElevationSource.TileFile.open(url, ArcticDemElevationSource.this.maxTileCache, dataset.displayName());
         }
      });
      TellusCacheRegistry.register(this);
   }

   public double sampleElevationMeters(double blockX, double blockZ, double worldScale) {
      if (this.enabled && !this.isSuspended()) {
         if (worldScale <= 0.0) {
            return Double.NaN;
         } else {
            ArcticDemElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
            double lat = latLon.lat();
            double lon = latLon.lon();
            if (!this.isInCoverage(lat, lon)) {
               this.debugOnce(
                  ArcticDemElevationSource.DebugReason.OUT_OF_BOUNDS, "{} out of coverage at lat={}, lon={}.", this.dataset.displayName(), lat, lon
               );
               return Double.NaN;
            } else {
               ArcticDemElevationSource.Tier tier = ArcticDemElevationSource.Tier.forScale(worldScale);
               this.debugTier(tier, worldScale);
               ArcticDemElevationSource.TileIndex index = this.getIndex(tier);
               if (index == null) {
                  this.debugOnce(ArcticDemElevationSource.DebugReason.INDEX_FAILED, "{} index unavailable; falling back to Terrarium.", this.dataset.displayName());
                  return Double.NaN;
               } else {
                  ArcticDemElevationSource.Projection projection = this.project(lat, lon);
                  if (projection == null) {
                     this.debugOnce(
                        ArcticDemElevationSource.DebugReason.PROJECTION_FAILED, "{} projection failed for lat={}, lon={}.", this.dataset.displayName(), lat, lon
                     );
                     return Double.NaN;
                  } else {
                     ArcticDemElevationSource.TileLookup lookup = this.lookupCache.get();
                     ArcticDemElevationSource.TileEntry tile = lookup.get(tier, projection.x, projection.y);
                     if (tile == null) {
                        tile = index.findTile(projection.x, projection.y);
                        lookup.update(tier, tile);
                     }

                     if (tile == null) {
                        this.debugOnce(
                           ArcticDemElevationSource.DebugReason.TILE_NOT_FOUND,
                           "{} tile not found for x={}, y={}, tier={}.",
                           this.dataset.displayName(),
                           projection.x,
                           projection.y,
                           tier.id
                        );
                        return Double.NaN;
                     } else {
                        ArcticDemElevationSource.TileFile tileFile = this.getTileFile(tile.url);
                        if (tileFile == null) {
                           this.debugOnce(ArcticDemElevationSource.DebugReason.TILEFILE_FAILED, "{} tile load failed for {}.", this.dataset.displayName(), tile.url);
                           return Double.NaN;
                        } else {
                           boolean probeSample = this.shouldDebugSample(lat, lon);
                           double value;
                           if (probeSample && this.debugSampleMask.compareAndSet(0, 1)) {
                              ArcticDemElevationSource.TileFile.SampleResult result = tileFile.sampleWithDebug(
                                 tile, projection.x, projection.y, index.pixelSize
                              );
                              value = result.value();
                              if (DEBUG_DEM && result.debug() != null) {
                                 ArcticDemElevationSource.TileFile.SampleDebug debug = result.debug();
                                 Tellus.LOGGER
                                    .info(
                                       "{} probe lat={}, lon={}, tier={}, tile={}, local=({},{}) px=({},{})->({},{}), v00={}, v10={}, v01={}, v11={}, value={}",
                                       new Object[]{
                                          this.dataset.displayName(),
                                          String.format("%.5f", lat),
                                          String.format("%.5f", lon),
                                          tier.id,
                                          tile.url,
                                          String.format("%.2f", debug.localX()),
                                          String.format("%.2f", debug.localY()),
                                          debug.x0(),
                                          debug.y0(),
                                          debug.x1(),
                                          debug.y1(),
                                          Float.isFinite(debug.v00()) ? String.format("%.2f", debug.v00()) : "NaN",
                                          Float.isFinite(debug.v10()) ? String.format("%.2f", debug.v10()) : "NaN",
                                          Float.isFinite(debug.v01()) ? String.format("%.2f", debug.v01()) : "NaN",
                                          Float.isFinite(debug.v11()) ? String.format("%.2f", debug.v11()) : "NaN",
                                          Double.isFinite(result.value()) ? String.format("%.2f", result.value()) : "NaN"
                                       }
                                    );
                              }
                           } else {
                              value = tileFile.sample(tile, projection.x, projection.y, index.pixelSize);
                           }

                           if (tileFile.hasFailure()) {
                              this.suspend();
                           }

                           if (!Double.isNaN(value) && !(value <= NO_DATA + 1.0)) {
                              this.debugOnce(ArcticDemElevationSource.DebugReason.SUCCESS, "{} active (tier {}, tile {}).", this.dataset.displayName(), tier.id, tile.url);
                              return value;
                           } else {
                              this.debugOnce(ArcticDemElevationSource.DebugReason.NO_DATA, "{} returned no-data for {}.", this.dataset.displayName(), tile.url);
                              return Double.NaN;
                           }
                        }
                     }
                  }
               }
            }
         }
      } else {
         if (!this.enabled) {
            this.debugOnce(
               ArcticDemElevationSource.DebugReason.DISABLED, "{} disabled ({}=false).", this.dataset.displayName(), this.dataset.enabledPropertyKey()
            );
         } else {
            this.debugOnce(ArcticDemElevationSource.DebugReason.SUSPENDED, "{} suspended; falling back to Terrarium.", this.dataset.displayName());
         }

         return Double.NaN;
      }
   }

   public double sampleElevationMetersLocalOnly(double blockX, double blockZ, double worldScale) {
      if (this.enabled && !this.isSuspended()) {
         if (worldScale <= 0.0) {
            return Double.NaN;
         } else {
            ArcticDemElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
            double lat = latLon.lat();
            double lon = latLon.lon();
            if (!this.isInCoverage(lat, lon)) {
               return Double.NaN;
            } else {
               ArcticDemElevationSource.Tier tier = ArcticDemElevationSource.Tier.forScale(worldScale);
               ArcticDemElevationSource.TileIndex index = this.getIndexLocalOnly(tier);
               if (index == null) {
                  return Double.NaN;
               } else {
                  ArcticDemElevationSource.Projection projection = this.project(lat, lon);
                  if (projection == null) {
                     return Double.NaN;
                  } else {
                     ArcticDemElevationSource.TileLookup lookup = this.lookupCache.get();
                     ArcticDemElevationSource.TileEntry tile = lookup.get(tier, projection.x, projection.y);
                     if (tile == null) {
                        tile = index.findTile(projection.x, projection.y);
                        lookup.update(tier, tile);
                     }

                     if (tile == null) {
                        return Double.NaN;
                     } else {
                        ArcticDemElevationSource.TileFile tileFile = this.getTileFileLocalOnly(tile.url);
                        if (tileFile == null) {
                           return Double.NaN;
                        } else {
                           double value = tileFile.sample(tile, projection.x, projection.y, index.pixelSize);
                           return !Double.isNaN(value) && !(value <= NO_DATA + 1.0) ? value : Double.NaN;
                        }
                     }
                  }
               }
            }
         }
      } else {
         return Double.NaN;
      }
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      if (this.enabled && !this.isSuspended()) {
         if (!(worldScale <= 0.0) && radius > 0) {
            ArcticDemElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
            double lat = latLon.lat();
            double lon = latLon.lon();
            if (this.isInCoverage(lat, lon)) {
               ArcticDemElevationSource.Tier tier = ArcticDemElevationSource.Tier.forScale(worldScale);
               ArcticDemElevationSource.TileIndex index = this.getIndex(tier);
               if (index != null) {
                  ArcticDemElevationSource.Projection projection = this.project(lat, lon);
                  if (projection != null) {
                     double span = index.tileSpanMeters;
                     int clampedRadius = Math.max(1, radius);

                     for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
                        for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
                           double x = projection.x + dx * span;
                           double y = projection.y + dz * span;
                           ArcticDemElevationSource.TileEntry tile = index.findTile(x, y);
                           if (tile != null) {
                              try {
                                 this.fileCache.get(tile.url);
                              } catch (ExecutionException var27) {
                                 Tellus.LOGGER.debug("Failed to prefetch {} tile {}", this.dataset.displayName(), tile.url, var27);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static ArcticDemElevationSource.LatLon toLatLon(double blockX, double blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      return new ArcticDemElevationSource.LatLon(lat, lon);
   }

   private boolean isInCoverage(double lat, double lon) {
      return lat >= this.dataset.minLat() && lat <= this.dataset.maxLat() && lon >= MIN_LON && lon <= MAX_LON;
   }

   private ArcticDemElevationSource.TileIndex getIndex(ArcticDemElevationSource.Tier tier) {
      try {
         return (ArcticDemElevationSource.TileIndex)this.indexCache.get(tier);
      } catch (ExecutionException var3) {
         Tellus.LOGGER.warn("Failed to load {} index for {}", this.dataset.displayName(), tier.id, var3);
         this.suspend();
         return null;
      }
   }

   private ArcticDemElevationSource.TileIndex getIndexLocalOnly(ArcticDemElevationSource.Tier tier) {
      return (ArcticDemElevationSource.TileIndex)this.indexCache.getIfPresent(tier);
   }

   private ArcticDemElevationSource.TileFile getTileFile(String url) {
      try {
         return (ArcticDemElevationSource.TileFile)this.fileCache.get(url);
      } catch (ExecutionException var3) {
         Tellus.LOGGER.debug("Failed to load {} tile {}", this.dataset.displayName(), url, var3);
         this.suspend();
         return null;
      }
   }

   private ArcticDemElevationSource.TileFile getTileFileLocalOnly(String url) {
      return (ArcticDemElevationSource.TileFile)this.fileCache.getIfPresent(url);
   }

   private boolean isSuspended() {
      return System.currentTimeMillis() < this.suspendedUntilMs;
   }

   private void suspend() {
      this.suspendedUntilMs = Math.max(this.suspendedUntilMs, System.currentTimeMillis() + this.failureCooldownMs);
   }

   private void debugOnce(ArcticDemElevationSource.DebugReason reason, String message, Object... args) {
      if (DEBUG_DEM) {
         int bit = 1 << reason.ordinal();

         int previous;
         do {
            previous = this.debugMask.get();
            if ((previous & bit) != 0) {
               return;
            }
         } while (!this.debugMask.compareAndSet(previous, previous | bit));

         Tellus.LOGGER.info(message, args);
      }
   }

   private void debugTier(ArcticDemElevationSource.Tier tier, double worldScale) {
      if (DEBUG_DEM) {
         if (tier != this.lastLoggedTier) {
            this.lastLoggedTier = tier;
            Tellus.LOGGER.info("{} tier set to {} (worldScale {}).", this.dataset.displayName(), tier.id, String.format("%.2f", worldScale));
         }
      }
   }

   private boolean shouldDebugSample(double lat, double lon) {
      if (!DEBUG_DEM) {
         return false;
      } else {
         return Double.isFinite(DEBUG_SAMPLE_LAT) && Double.isFinite(DEBUG_SAMPLE_LON)
            ? Math.abs(lat - DEBUG_SAMPLE_LAT) <= DEBUG_SAMPLE_EPS && Math.abs(lon - DEBUG_SAMPLE_LON) <= DEBUG_SAMPLE_EPS
            : false;
      }
   }

   private ArcticDemElevationSource.Projection project(double latDeg, double lonDeg) {
      return this.dataset.projection().project(latDeg, lonDeg);
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

   private static long longProperty(String key, long defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(1L, Long.parseLong(value));
         } catch (NumberFormatException var5) {
            return defaultValue;
         }
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return this.dataset.cacheDomain();
   }

   @Override
   public void clearCache() {
      this.indexCache.invalidateAll();
      this.indexCache.cleanUp();
      this.fileCache.invalidateAll();
      this.fileCache.cleanUp();
      this.lookupCache.remove();
      this.lastLoggedTier = null;
      this.suspendedUntilMs = 0L;
   }

   private static double doubleProperty(String key, double defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Double.parseDouble(value);
         } catch (NumberFormatException var5) {
            return defaultValue;
         }
      }
   }

   private static HttpURLConnection openConnection(URI uri) throws IOException {
      return openConnection(uri, null);
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

   private static long bucketKey(int bx, int by) {
      return (long)bx << 32 ^ by & 4294967295L;
   }

   static enum Dataset {
      ARCTIC(
         "ArcticDEM",
         "arcticdem",
         "https://pgc-opendata-dems.s3.us-west-2.amazonaws.com/arcticdem/mosaics/v4.1",
         "tellus/cache/elevation-arcticdem/v4.1",
         TellusCacheDomain.ARCTICDEM,
         50.593818479,
         83.988230361,
         ArcticDemElevationSource.PolarProjection.NORTH_3413
      ),
      REMA(
         "REMA",
         "rema",
         "https://pgc-opendata-dems.s3.us-west-2.amazonaws.com/rema/mosaics/v2.0",
         "tellus/cache/elevation-rema/v2.0",
         TellusCacheDomain.REMA,
         -90.0,
         -60.0,
         ArcticDemElevationSource.PolarProjection.SOUTH_3031
      );

      private final String displayName;
      private final String propertyId;
      private final String baseUrl;
      private final String cacheRelativePath;
      private final TellusCacheDomain cacheDomain;
      private final double minLat;
      private final double maxLat;
      private final ArcticDemElevationSource.PolarProjection projection;

      private Dataset(
         String displayName,
         String propertyId,
         String baseUrl,
         String cacheRelativePath,
         TellusCacheDomain cacheDomain,
         double minLat,
         double maxLat,
         ArcticDemElevationSource.PolarProjection projection
      ) {
         this.displayName = displayName;
         this.propertyId = propertyId;
         this.baseUrl = baseUrl;
         this.cacheRelativePath = cacheRelativePath;
         this.cacheDomain = cacheDomain;
         this.minLat = minLat;
         this.maxLat = maxLat;
         this.projection = projection;
      }

      private String displayName() {
         return this.displayName;
      }

      private String baseUrl() {
         return this.baseUrl;
      }

      private String cacheRelativePath() {
         return this.cacheRelativePath;
      }

      private TellusCacheDomain cacheDomain() {
         return this.cacheDomain;
      }

      private double minLat() {
         return this.minLat;
      }

      private double maxLat() {
         return this.maxLat;
      }

      private ArcticDemElevationSource.PolarProjection projection() {
         return this.projection;
      }

      private String enabledPropertyKey() {
         return "tellus." + this.propertyId + ".enabled";
      }

      private String cacheFilesPropertyKey() {
         return "tellus." + this.propertyId + ".cacheFiles";
      }

      private String cacheTilesPropertyKey() {
         return "tellus." + this.propertyId + ".cacheTiles";
      }

      private String retryPropertyKey() {
         return "tellus." + this.propertyId + ".retryMs";
      }
   }

   private static enum PolarProjection {
      NORTH_3413(true, 70.0, -45.0),
      SOUTH_3031(false, -71.0, 0.0);

      private final boolean north;
      private final double lon0;
      private final double tC;
      private final double mC;

      private PolarProjection(boolean north, double latTsDeg, double lon0Deg) {
         this.north = north;
         this.lon0 = Math.toRadians(lon0Deg);
         double latTs = Math.toRadians(Math.abs(latTsDeg));
         double sinTs = Math.sin(latTs);
         this.tC = Math.tan((Math.PI / 4) - latTs / 2.0) / Math.pow((1.0 - PROJ_E * sinTs) / (1.0 + PROJ_E * sinTs), PROJ_E / 2.0);
         this.mC = Math.cos(latTs) / Math.sqrt(1.0 - PROJ_E * PROJ_E * sinTs * sinTs);
      }

      private ArcticDemElevationSource.Projection project(double latDeg, double lonDeg) {
         if (this.north ? latDeg <= 0.0 : latDeg >= 0.0) {
            return null;
         } else {
            double lat = Math.toRadians(Math.abs(latDeg));
            double lon = Math.toRadians(lonDeg);
            double sinLat = Math.sin(lat);
            double t = Math.tan((Math.PI / 4) - lat / 2.0) / Math.pow((1.0 - PROJ_E * sinLat) / (1.0 + PROJ_E * sinLat), PROJ_E / 2.0);
            double rho = PROJ_A * this.mC * t / this.tC;
            double x = rho * Math.sin(lon - this.lon0);
            double y = this.north ? -rho * Math.cos(lon - this.lon0) : rho * Math.cos(lon - this.lon0);
            return new ArcticDemElevationSource.Projection(x, y);
         }
      }
   }

   private static enum DebugReason {
      DISABLED,
      SUSPENDED,
      OUT_OF_BOUNDS,
      INDEX_FAILED,
      PROJECTION_FAILED,
      TILE_NOT_FOUND,
      TILEFILE_FAILED,
      NO_DATA,
      SUCCESS;
   }

   private record LatLon(double lat, double lon) {
   }

   private record Projection(double x, double y) {
   }

   private static final class RangeReader {
      private final String url;

      private RangeReader(String url) {
         this.url = url;
      }

      private byte[] read(long offset, int length) throws IOException {
         if (length <= 0) {
            return new byte[0];
         } else {
            String rangeHeader = "bytes=" + offset + "-" + (offset + length - 1L);
            HttpURLConnection connection = ArcticDemElevationSource.openConnection(URI.create(this.url), rangeHeader);
            try {
               int status = connection.getResponseCode();
               if (status == 416) {
                  throw new EOFException("Range not satisfiable for " + this.url);
               } else if (status != 206 && status != 200) {
                  throw new IOException("Unexpected HTTP status " + status + " for " + this.url);
               } else {
                  DownloadProgressReporter.requestStarted((long)length);
                  byte[] var9;
                  try (InputStream input = connection.getInputStream()) {
                     byte[] data = DownloadProgressReporter.readAllBytesWithProgress(input);
                     if (data.length != length) {
                        throw new EOFException("Unexpected range length " + data.length + " for " + this.url);
                     }

                     var9 = data;
                  }

                  return var9;
               }
            } finally {
               DownloadProgressReporter.requestFinished();
               connection.disconnect();
            }
         }
      }
   }

   private static enum Tier {
      TWO_M("2m", 2.0),
      TEN_M("10m", 10.0),
      THIRTY_TWO_M("32m", 32.0);

      private final String id;
      private final double meters;

      private Tier(String id, double meters) {
         this.id = id;
         this.meters = meters;
      }

      private static ArcticDemElevationSource.Tier forScale(double worldScale) {
         if (worldScale >= THIRTY_TWO_M.meters) {
            return THIRTY_TWO_M;
         } else {
            return worldScale >= TEN_M.meters ? TEN_M : TWO_M;
         }
      }
   }

   private record TileEntry(String url, double xMin, double yMin, double xMax, double yMax) {
      private boolean contains(double x, double y) {
         return x >= this.xMin && x <= this.xMax && y >= this.yMin && y <= this.yMax;
      }

      private double widthMeters() {
         return this.xMax - this.xMin;
      }
   }

   private static final class TileFile {
      private static final int TAG_IMAGE_WIDTH = 256;
      private static final int TAG_IMAGE_HEIGHT = 257;
      private static final int TAG_TILE_WIDTH = 322;
      private static final int TAG_TILE_HEIGHT = 323;
      private static final int TAG_TILE_OFFSETS = 324;
      private static final int TAG_TILE_BYTE_COUNTS = 325;
      private static final int TAG_COMPRESSION = 259;
      private static final int TAG_BITS_PER_SAMPLE = 258;
      private static final int TAG_SAMPLE_FORMAT = 339;
      private static final int TAG_PREDICTOR = 317;
      private static final int TAG_SAMPLES_PER_PIXEL = 277;
      private static final int TYPE_SHORT = 3;
      private static final int TYPE_LONG = 4;
      private static final int TYPE_DOUBLE = 12;
      private static final int TYPE_LONG8 = 16;
      private static final int COMPRESSION_LZW = 5;
      private static final int COMPRESSION_DEFLATE = 8;
      private final String url;
      private final ArcticDemElevationSource.RangeReader reader;
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
      private final String sourceLabel;
      private final Map<Integer, float[]> tileCache;
      private volatile boolean failed;

      private TileFile(
         String url,
         ArcticDemElevationSource.RangeReader reader,
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
         String sourceLabel,
         int maxTileCache
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
         this.sourceLabel = sourceLabel;
         this.tileCache = new LinkedHashMap<Integer, float[]>(maxTileCache, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Entry<Integer, float[]> eldest) {
               return this.size() > maxTileCache;
            }
         };
      }

      private double sample(ArcticDemElevationSource.TileEntry tile, double x, double y, double pixelSize) {
         if (this.failed) {
            return Double.NaN;
         } else {
            double localX = (x - tile.xMin()) / pixelSize;
            double localY = (tile.yMax() - y) / pixelSize;
            return !this.isWithinImage(localX, localY) ? Double.NaN : this.sampleBilinear(localX, localY);
         }
      }

      private ArcticDemElevationSource.TileFile.SampleResult sampleWithDebug(ArcticDemElevationSource.TileEntry tile, double x, double y, double pixelSize) {
         if (this.failed) {
            return new ArcticDemElevationSource.TileFile.SampleResult(Double.NaN, null);
         } else {
            double localX = (x - tile.xMin()) / pixelSize;
            double localY = (tile.yMax() - y) / pixelSize;
            if (!this.isWithinImage(localX, localY)) {
               return new ArcticDemElevationSource.TileFile.SampleResult(Double.NaN, null);
            } else {
               int x0 = Mth.clamp(Mth.floor(localX), 0, this.width - 1);
               int y0 = Mth.clamp(Mth.floor(localY), 0, this.height - 1);
               int x1 = Math.min(x0 + 1, this.width - 1);
               int y1 = Math.min(y0 + 1, this.height - 1);
               float v00 = this.sampleValue(x0, y0);
               float v10 = this.sampleValue(x1, y0);
               float v01 = this.sampleValue(x0, y1);
               float v11 = this.sampleValue(x1, y1);
               double dx = localX - x0;
               double dy = localY - y0;
               double value = blendFiniteSamples(v00, v10, v01, v11, dx, dy);
               return new ArcticDemElevationSource.TileFile.SampleResult(
                  value, new ArcticDemElevationSource.TileFile.SampleDebug(localX, localY, x0, y0, x1, y1, v00, v10, v01, v11)
               );
            }
         }
      }

      private double sampleBilinear(double localX, double localY) {
         int x0 = Mth.clamp(Mth.floor(localX), 0, this.width - 1);
         int y0 = Mth.clamp(Mth.floor(localY), 0, this.height - 1);
         int x1 = Math.min(x0 + 1, this.width - 1);
         int y1 = Math.min(y0 + 1, this.height - 1);
         float v00 = this.sampleValue(x0, y0);
         float v10 = this.sampleValue(x1, y0);
         float v01 = this.sampleValue(x0, y1);
         float v11 = this.sampleValue(x1, y1);
         double dx = localX - x0;
         double dy = localY - y0;
         return blendFiniteSamples(v00, v10, v01, v11, dx, dy);
      }

      private boolean isWithinImage(double localX, double localY) {
         return localX >= 0.0 && localY >= 0.0 && localX < this.width && localY < this.height;
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

      private float sampleValue(int pixelX, int pixelY) {
         if (this.failed) {
            return Float.NaN;
         } else {
            int tileX = pixelX / this.tileWidth;
            int tileY = pixelY / this.tileHeight;
            int tileIndex = tileY * this.tilesPerRow + tileX;

            float[] tile;
            try {
               tile = this.getTile(tileIndex);
            } catch (IOException var10) {
               this.failed = true;
               if (Thread.currentThread().isInterrupted()) {
                  Thread.currentThread().interrupt();
               }

               Tellus.LOGGER.debug("Failed to read {} tile {} from {}", new Object[]{this.sourceLabel, tileIndex, this.url, var10});
               return Float.NaN;
            }

            int localX = pixelX - tileX * this.tileWidth;
            int localY = pixelY - tileY * this.tileHeight;
            float value = tile[localX + localY * this.tileWidth];
            return Float.isFinite(value) && !(value <= (float)(NO_DATA + 1.0)) ? value : Float.NaN;
         }
      }

      private boolean hasFailure() {
         return this.failed;
      }

      private float[] getTile(int tileIndex) throws IOException {
         synchronized (this.tileCache) {
            float[] cached = this.tileCache.get(tileIndex);
            if (cached != null) {
               return cached;
            }
         }

         float[] tile = this.readTile(tileIndex);
         synchronized (this.tileCache) {
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

      private static ArcticDemElevationSource.TileFile open(String url, int maxTileCache, String sourceLabel) throws IOException {
         ArcticDemElevationSource.RangeReader reader = new ArcticDemElevationSource.RangeReader(url);
         byte[] header = reader.read(0L, 32);

         ByteOrder order = switch (header[0]) {
            case 73 -> ByteOrder.LITTLE_ENDIAN;
            case 77 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Invalid TIFF byte order");
         };
         ByteBuffer headerBuf = ByteBuffer.wrap(header).order(order);
         headerBuf.getShort();
         short magic = headerBuf.getShort();
         if (magic != 43) {
            throw new IOException("Expected BigTIFF magic");
         } else {
            headerBuf.getShort();
            headerBuf.getShort();
            long ifdOffset = headerBuf.getLong();
            byte[] countBytes = reader.read(ifdOffset, 8);
            long entryCount = ByteBuffer.wrap(countBytes).order(order).getLong();
            if (entryCount > 0L && entryCount <= 1000000L) {
               byte[] entryBytes = reader.read(ifdOffset + 8L, (int)entryCount * 20);
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

               for (int i = 0; i < entryCount; i++) {
                  int tag = Short.toUnsignedInt(entries.getShort());
                  int type = Short.toUnsignedInt(entries.getShort());
                  long count = entries.getLong();
                  long value = entries.getLong();
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
                     case TAG_SAMPLES_PER_PIXEL:
                        samplesPerPixel = readIntValue(type, count, value);
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

               if (width <= 0 || height <= 0 || tileWidth <= 0 || tileHeight <= 0) {
                  throw new IOException("Missing TIFF size tags");
               } else if (tileOffsets == null || tileByteCounts == null) {
                  throw new IOException("Missing TIFF tile offsets");
               } else if (bitsPerSample == 32 && sampleFormat == 3) {
                  return new ArcticDemElevationSource.TileFile(
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
                     tileByteCounts,
                     sourceLabel,
                     maxTileCache
                  );
               } else {
                  throw new IOException("Unsupported TIFF sample format " + sampleFormat + " bits " + bitsPerSample);
               }
            } else {
               throw new IOException("Invalid BigTIFF entry count " + entryCount);
            }
         }
      }

      private static int readIntValue(int type, long count, long value) throws IOException {
         if (count != 1L) {
            throw new IOException("Expected single TIFF value");
         } else if (type == TYPE_SHORT) {
            return (int)(value & 65535L);
         } else if (type == TYPE_LONG) {
            return (int)(value & 4294967295L);
         } else if (type == TYPE_LONG8) {
            if (value > 2147483647L) {
               throw new IOException("TIFF value out of range " + value);
            } else {
               return (int)value;
            }
         } else {
            throw new IOException("Unsupported TIFF value type " + type);
         }
      }

      private static long[] readLongArray(ArcticDemElevationSource.RangeReader reader, long offset, long count, int type, ByteOrder order) throws IOException {
         if (count <= 0L) {
            return new long[0];
         } else {
            int size = typeSize(type);
            if (size <= 0) {
               throw new IOException("Unsupported TIFF array type " + type);
            } else {
               long byteSize = count * size;
               if (byteSize > 2147483647L) {
                  throw new IOException("TIFF array too large");
               } else {
                  byte[] data = reader.read(offset, (int)byteSize);
                  ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
                  long[] values = new long[(int)count];

                  for (int i = 0; i < count; i++) {
                     values[i] = switch (type) {
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
      }

      private static int[] readIntArray(ArcticDemElevationSource.RangeReader reader, long offset, long count, int type, ByteOrder order) throws IOException {
         long[] values = readLongArray(reader, offset, count, type, order);
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
            case TYPE_SHORT -> 2;
            case TYPE_LONG -> 4;
            case TYPE_DOUBLE, TYPE_LONG8 -> 8;
            default -> 0;
         };
      }

      private static byte[] inflate(byte[] compressed, int expectedSize) throws IOException {
         byte[] output = new byte[expectedSize];

         byte[] var8;
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

            var8 = output;
         }

         return var8;
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
         ArcticDemElevationSource.TileFile.LzwBitReader reader = new ArcticDemElevationSource.TileFile.LzwBitReader(compressed);
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

      private static void applyFloatingPointPredictor(byte[] data, int width, int height, int bytesPerSample, int samplesPerPixel, ByteOrder order) throws IOException {
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

      private record SampleDebug(double localX, double localY, int x0, int y0, int x1, int y1, float v00, float v10, float v01, float v11) {
      }

      private record SampleResult(double value, ArcticDemElevationSource.TileFile.SampleDebug debug) {
      }
   }

   private static final class TileIndex {
      private static final Pattern GEO_TRANSFORM_PATTERN = Pattern.compile("<GeoTransform>([^<]+)</GeoTransform>");
      private static final Pattern TILE_PATTERN = Pattern.compile(
         "<ComplexSource>\\s*<SourceFilename[^>]*>([^<]+)</SourceFilename>.*?<SourceProperties[^>]*RasterXSize=\\\"(\\d+)\\\" RasterYSize=\\\"(\\d+)\\\".*?>.*?<DstRect xOff=\\\"(\\d+)\\\" yOff=\\\"(\\d+)\\\" xSize=\\\"(\\d+)\\\" ySize=\\\"(\\d+)\\\"",
         32
      );
      private final double pixelSize;
      private final double minX;
      private final double minY;
      private final double maxX;
      private final double maxY;
      private final double tileSpanMeters;
      private final Map<Long, List<ArcticDemElevationSource.TileEntry>> buckets;

      private TileIndex(
         double pixelSize,
         double minX,
         double minY,
         double maxX,
         double maxY,
         double tileSpanMeters,
         Map<Long, List<ArcticDemElevationSource.TileEntry>> buckets
      ) {
         this.pixelSize = pixelSize;
         this.minX = minX;
         this.minY = minY;
         this.maxX = maxX;
         this.maxY = maxY;
         this.tileSpanMeters = tileSpanMeters;
         this.buckets = buckets;
      }

      private ArcticDemElevationSource.TileEntry findTile(double x, double y) {
         if (!(x < this.minX) && !(x > this.maxX) && !(y < this.minY) && !(y > this.maxY)) {
            int bx = (int)Math.floor((x - this.minX) / BUCKET_SIZE_METERS);
            int by = (int)Math.floor((y - this.minY) / BUCKET_SIZE_METERS);
            long key = ArcticDemElevationSource.bucketKey(bx, by);
            List<ArcticDemElevationSource.TileEntry> bucket = this.buckets.get(key);
            if (bucket == null) {
               return null;
            } else {
               for (ArcticDemElevationSource.TileEntry tile : bucket) {
                  if (tile.contains(x, y)) {
                     return tile;
                  }
               }

               return null;
            }
         } else {
            return null;
         }
      }

      private static ArcticDemElevationSource.TileIndex load(ArcticDemElevationSource.Tier tier, Path cacheRoot, String baseUrl) throws IOException {
         Path cachePath = cacheRoot.resolve(tier.id + "_dem_tiles.vrt");
         String vrtText;
         if (Files.exists(cachePath)) {
            vrtText = new String(Files.readAllBytes(cachePath), StandardCharsets.UTF_8);
         } else {
            vrtText = downloadVrt(tier, cachePath, baseUrl);
         }

         Matcher geoMatcher = GEO_TRANSFORM_PATTERN.matcher(vrtText);
         if (!geoMatcher.find()) {
            throw new IOException("Missing GeoTransform in ArcticDEM VRT");
         } else {
            double[] transform = parseGeoTransform(geoMatcher.group(1));
            double originX = transform[0];
            double pixelSizeX = transform[1];
            double originY = transform[3];
            double pixelSizeY = transform[5];
            List<ArcticDemElevationSource.TileEntry> tiles = new ArrayList<>();
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            Matcher tileMatcher = TILE_PATTERN.matcher(vrtText);

            while (tileMatcher.find()) {
               String src = tileMatcher.group(1);
               long xOff = Long.parseLong(tileMatcher.group(4));
               long yOff = Long.parseLong(tileMatcher.group(5));
               int xSize = Integer.parseInt(tileMatcher.group(6));
               int ySize = Integer.parseInt(tileMatcher.group(7));
               double xMin = originX + xOff * pixelSizeX;
               double yMax = originY + yOff * pixelSizeY;
               double xMax = xMin + xSize * pixelSizeX;
               double yMin = yMax + ySize * pixelSizeY;
               String url = toHttpUrl(src);
               ArcticDemElevationSource.TileEntry tile = new ArcticDemElevationSource.TileEntry(
                  url, Math.min(xMin, xMax), Math.min(yMin, yMax), Math.max(xMin, xMax), Math.max(yMin, yMax)
               );
               tiles.add(tile);
               minX = Math.min(minX, tile.xMin());
               minY = Math.min(minY, tile.yMin());
               maxX = Math.max(maxX, tile.xMax());
               maxY = Math.max(maxY, tile.yMax());
            }

            if (tiles.isEmpty()) {
               throw new IOException("No tiles found in ArcticDEM VRT");
            } else {
               double tileSpanMeters = medianTileSpan(tiles);
               Map<Long, List<ArcticDemElevationSource.TileEntry>> buckets = buildBuckets(tiles, minX, minY);
               return new ArcticDemElevationSource.TileIndex(Math.abs(pixelSizeX), minX, minY, maxX, maxY, tileSpanMeters, buckets);
            }
         }
      }

      private static Map<Long, List<ArcticDemElevationSource.TileEntry>> buildBuckets(List<ArcticDemElevationSource.TileEntry> tiles, double minX, double minY) {
         Map<Long, List<ArcticDemElevationSource.TileEntry>> buckets = new LinkedHashMap<>();

         for (ArcticDemElevationSource.TileEntry tile : tiles) {
            int bx0 = (int)Math.floor((tile.xMin() - minX) / BUCKET_SIZE_METERS);
            int bx1 = (int)Math.floor((tile.xMax() - minX) / BUCKET_SIZE_METERS);
            int by0 = (int)Math.floor((tile.yMin() - minY) / BUCKET_SIZE_METERS);
            int by1 = (int)Math.floor((tile.yMax() - minY) / BUCKET_SIZE_METERS);

            for (int by = by0; by <= by1; by++) {
               for (int bx = bx0; bx <= bx1; bx++) {
                  long key = ArcticDemElevationSource.bucketKey(bx, by);
                  buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tile);
               }
            }
         }

         return buckets;
      }

      private static double medianTileSpan(List<ArcticDemElevationSource.TileEntry> tiles) {
         double[] spans = new double[tiles.size()];

         for (int i = 0; i < tiles.size(); i++) {
            spans[i] = tiles.get(i).widthMeters();
         }

         Arrays.sort(spans);
         return spans[spans.length / 2];
      }

      private static double[] parseGeoTransform(String text) {
         String[] parts = text.trim().split(",");
         if (parts.length < 6) {
            throw new IllegalArgumentException("Invalid GeoTransform");
         } else {
            double[] values = new double[6];

            for (int i = 0; i < 6; i++) {
               values[i] = Double.parseDouble(parts[i].trim());
            }

            return values;
         }
      }

      private static String downloadVrt(ArcticDemElevationSource.Tier tier, Path cachePath, String baseUrl) throws IOException {
         URI uri = URI.create(String.format("%s/%s_dem_tiles.vrt", baseUrl, tier.id));
         HttpURLConnection connection = ArcticDemElevationSource.openConnection(uri);

         byte[] data;
         try {
            DownloadProgressReporter.requestStarted(Math.max(0L, connection.getContentLengthLong()));
            try (InputStream input = connection.getInputStream()) {
               data = DownloadProgressReporter.readAllBytesWithProgress(input);
            }
         } finally {
            DownloadProgressReporter.requestFinished();
            connection.disconnect();
         }

         Files.createDirectories(cachePath.getParent());
         Files.write(cachePath, data);
         return new String(data, StandardCharsets.UTF_8);
      }

      private static String toHttpUrl(String sourceFilename) {
         String trimmed = sourceFilename.trim();
         String prefix = "/vsis3/";
         if (trimmed.startsWith(prefix)) {
            String path = trimmed.substring(prefix.length());
            int slash = path.indexOf(47);
            if (slash > 0) {
               String bucket = path.substring(0, slash);
               String key = path.substring(slash + 1);
               return "https://" + bucket + ".s3.us-west-2.amazonaws.com/" + key;
            }
         }

         return trimmed;
      }
   }

   private static final class TileLookup {
      private ArcticDemElevationSource.Tier tier;
      private ArcticDemElevationSource.TileEntry tile;

      private ArcticDemElevationSource.TileEntry get(ArcticDemElevationSource.Tier tier, double x, double y) {
         if (this.tile != null && this.tier == tier) {
            return this.tile.contains(x, y) ? this.tile : null;
         } else {
            return null;
         }
      }

      private void update(ArcticDemElevationSource.Tier tier, ArcticDemElevationSource.TileEntry tile) {
         this.tier = tier;
         this.tile = tile;
      }
   }
}
