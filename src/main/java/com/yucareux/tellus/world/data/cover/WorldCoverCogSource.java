package com.yucareux.tellus.world.data.cover;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.world.data.source.InputStreamSafety;
import com.yucareux.tellus.worldgen.WorldProjection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.zip.InflaterInputStream;

/**
 * Random-access reader for the official ESA WorldCover 2021 Cloud Optimized
 * GeoTIFFs.
 *
 * <p>The source files are large 3-by-3 degree rasters, but their TIFF tile
 * offsets and overview IFDs are stored at the beginning of each object. This
 * reader caches that compact index and requests only the compressed 1024 by
 * 1024 block needed by a sample.</p>
 */
final class WorldCoverCogSource {
   static final double SOURCE_RESOLUTION_METERS = 10.0;
   static final int UNAVAILABLE = Integer.MIN_VALUE;
   static final int TILE_DEGREES = 3;
   static final int MIN_TILE_LAT = -60;
   static final int MAX_TILE_LAT_EXCLUSIVE = 84;
   static final int MIN_TILE_LON = -180;
   static final int MAX_TILE_LON_EXCLUSIVE = 180;
   static final int BASE_RASTER_SIZE = 36_000;
   static final int TIFF_TILE_SIZE = 1_024;
   static final int[] KNOWN_OVERVIEW_FACTORS = new int[]{1, 2, 4, 8, 16, 32, 64};

   private static final String DEFAULT_BASE_URL =
      "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map";
   private static final String FILE_PATTERN = "ESA_WorldCover_10m_2021_v200_%s_Map.tif";
   private static final int METADATA_MAGIC = 0x57434F47;
   private static final int METADATA_VERSION = 1;
   private static final int METADATA_BOOTSTRAP_BYTES = 64 * 1024;
   private static final int MAX_METADATA_CACHE_BYTES = 512 * 1024;
   private static final int MAX_IFD_ENTRIES = 512;
   private static final int MAX_IFD_LEVELS = 16;
   private static final int MAX_TILES_PER_LEVEL = 16_384;
   private static final int MAX_COMPRESSED_BLOCK_BYTES = 8 * 1024 * 1024;
   private static final int MAX_DECOMPRESSED_BLOCK_BYTES = 16 * 1024 * 1024;
   private static final int MISSING_NEAREST_LAND = -1;
   private static final int CACHE_PRUNE_INTERVAL = 64;
   private static final long FAILURE_RETRY_NANOS = 30_000_000_000L;

   private final URI baseUri;
   private final Path cacheRoot;
   private final RangeAccessFactory rangeAccessFactory;
   private final int fetchRetryAttempts;
   private final long maximumDiskCacheBytes;
   private final LoadingCache<SourceTileKey, CogMetadata> metadataCache;
   private final LoadingCache<BlockKey, byte[]> blockCache;
   private final Cache<NearestLandKey, Integer> nearestLandCache;
   private final Cache<SourceTileKey, Long> metadataFailureDeadlines;
   private final Cache<BlockKey, Long> blockFailureDeadlines;
   private final AtomicInteger writesUntilPrune = new AtomicInteger(1);
   private final Object pruneLock = new Object();

   WorldCoverCogSource() {
      this(Configuration.fromSystemProperties());
   }

   private WorldCoverCogSource(Configuration configuration) {
      this(
         configuration.baseUri(),
         configuration.cacheRoot(),
         uri -> new HttpRangeAccess(uri, configuration.connectTimeoutMs(), configuration.readTimeoutMs()),
         configuration.metadataCacheEntries(),
         configuration.maximumMemoryCacheBytes(),
         configuration.fetchRetryAttempts(),
         configuration.maximumDiskCacheBytes()
      );
   }

   WorldCoverCogSource(URI baseUri, Path cacheRoot, RangeAccessFactory rangeAccessFactory) {
      this(baseUri, cacheRoot, rangeAccessFactory, 32, 64L * 1024L * 1024L, 1, 512L * 1024L * 1024L);
   }

   WorldCoverCogSource(
      URI baseUri,
      Path cacheRoot,
      RangeAccessFactory rangeAccessFactory,
      int metadataCacheEntries,
      long maximumMemoryCacheBytes,
      int fetchRetryAttempts,
      long maximumDiskCacheBytes
   ) {
      this.baseUri = normalizeBaseUri(baseUri);
      this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
      this.rangeAccessFactory = rangeAccessFactory;
      this.fetchRetryAttempts = Math.max(1, fetchRetryAttempts);
      this.maximumDiskCacheBytes = Math.max(1L, maximumDiskCacheBytes);
      this.metadataCache = CacheBuilder.newBuilder()
         .maximumSize(Math.max(1, metadataCacheEntries))
         .build(new CacheLoader<SourceTileKey, CogMetadata>() {
            @Override
            public CogMetadata load(SourceTileKey key) throws Exception {
               return WorldCoverCogSource.this.loadMetadataBlocking(key);
            }
         });
      this.blockCache = CacheBuilder.newBuilder()
         .maximumWeight(Math.max(1L, maximumMemoryCacheBytes))
         .weigher((BlockKey key, byte[] value) -> Math.max(1, value.length))
         .build(new CacheLoader<BlockKey, byte[]>() {
            @Override
            public byte[] load(BlockKey key) throws Exception {
               return WorldCoverCogSource.this.loadBlockBlocking(key);
            }
         });
      this.nearestLandCache = CacheBuilder.newBuilder().maximumSize(131_072).build();
      this.metadataFailureDeadlines = CacheBuilder.newBuilder().maximumSize(2_048).build();
      this.blockFailureDeadlines = CacheBuilder.newBuilder().maximumSize(32_768).build();
   }

   Sample sample(double lon, double lat, double effectiveResolutionMeters, LookupMode lookupMode) {
      SamplePosition position = this.position(lon, lat, effectiveResolutionMeters, lookupMode);
      return position == null ? Sample.unavailable() : this.samplePosition(position, lookupMode);
   }

   Sample sampleVisual(
      double lon,
      double lat,
      double effectiveResolutionMeters,
      double blockX,
      double blockZ,
      double worldScale,
      LookupMode lookupMode
   ) {
      SamplePosition center = this.position(lon, lat, effectiveResolutionMeters, lookupMode);
      if (center == null) {
         return Sample.unavailable();
      }

      Sample centerSample = this.samplePosition(center, lookupMode);
      if (!centerSample.available()) {
         return centerSample;
      }

      double sourceResolutionMeters = SOURCE_RESOLUTION_METERS * center.level().factor();
      double transitionStrength = LandCoverTransition.strength(sourceResolutionMeters, effectiveResolutionMeters);
      if (!(transitionStrength > 0.0) || LandCoverTransition.isHardClass(centerSample.coverClass())) {
         return centerSample;
      }

      double continuousX = continuousPixel(
         (lon - center.tileKey().lon()) / TILE_DEGREES,
         center.level().width()
      );
      double continuousY = continuousPixel(
         (center.tileKey().lat() + TILE_DEGREES - lat) / TILE_DEGREES,
         center.level().height()
      );
      double blendX = continuousX - 0.5;
      double blendY = continuousY - 0.5;
      int x0 = (int)Math.floor(blendX);
      int y0 = (int)Math.floor(blendY);
      int x1 = x0 + 1;
      int y1 = y0 + 1;
      int value00 = this.sampleVisualOffset(
         center, x0 - center.pixelX(), y0 - center.pixelY(), centerSample.coverClass(), lookupMode
      );
      int value10 = this.sampleVisualOffset(
         center, x1 - center.pixelX(), y0 - center.pixelY(), centerSample.coverClass(), lookupMode
      );
      int value01 = this.sampleVisualOffset(
         center, x0 - center.pixelX(), y1 - center.pixelY(), centerSample.coverClass(), lookupMode
      );
      int value11 = this.sampleVisualOffset(
         center, x1 - center.pixelX(), y1 - center.pixelY(), centerSample.coverClass(), lookupMode
      );
      int selected = LandCoverTransition.selectVisualClass(
         centerSample.coverClass(),
         value00,
         value10,
         value01,
         value11,
         blendX - x0,
         blendY - y0,
         transitionStrength,
         blockX,
         blockZ,
         sourceResolutionMeters / Math.max(worldScale, Double.MIN_NORMAL)
      );
      return new Sample(selected, true);
   }

   Sample sampleSmoothed(double lon, double lat, double effectiveResolutionMeters, LookupMode lookupMode) {
      SamplePosition center = this.position(lon, lat, effectiveResolutionMeters, lookupMode);
      if (center == null) {
         return Sample.unavailable();
      }

      Sample centerSample = this.samplePosition(center, lookupMode);
      if (!centerSample.available()) {
         return centerSample;
      }

      int[] counts = new int[256];
      int bestClass = centerSample.coverClass();
      int bestCount = 0;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            Sample sample = this.sampleOffset(center, dx, dy, lookupMode);
            if (sample.available() && sample.coverClass() >= 0 && sample.coverClass() < counts.length) {
               int count = ++counts[sample.coverClass()];
               if (count > bestCount || count == bestCount && sample.coverClass() == centerSample.coverClass()) {
                  bestCount = count;
                  bestClass = sample.coverClass();
               }
            }
         }
      }
      return new Sample(bestClass, true);
   }

   Sample sampleNearestLand(
      double lon,
      double lat,
      double effectiveResolutionMeters,
      int maximumRadiusPixels,
      int fallbackCoverClass,
      IntPredicate landClassPredicate,
      LookupMode lookupMode
   ) {
      SamplePosition center = this.position(lon, lat, effectiveResolutionMeters, lookupMode);
      if (center == null) {
         return Sample.unavailable();
      }

      Sample centerSample = this.samplePosition(center, lookupMode);
      if (!centerSample.available()) {
         return Sample.unavailable();
      }

      NearestLandKey cacheKey = new NearestLandKey(
         center.tileKey(), center.level().factor(), center.pixelX(), center.pixelY(), Math.max(0, maximumRadiusPixels)
      );
      Integer cached = this.nearestLandCache.getIfPresent(cacheKey);
      if (cached != null) {
         return new Sample(cached == MISSING_NEAREST_LAND ? fallbackCoverClass : cached, true);
      }

      boolean complete = true;
      int bestClass = UNAVAILABLE;
      int bestDistanceSquared = Integer.MAX_VALUE;
      int boundedRadius = Math.max(0, maximumRadiusPixels);
      for (int radius = 1; radius <= boundedRadius; radius++) {
         int min = -radius;
         int max = radius;
         for (int dx = min; dx <= max; dx++) {
            Sample top = this.sampleOffset(center, dx, min, lookupMode);
            if (!top.available()) {
               complete = false;
            } else if (landClassPredicate.test(top.coverClass())) {
               int distanceSquared = dx * dx + min * min;
               if (distanceSquared < bestDistanceSquared) {
                  bestClass = top.coverClass();
                  bestDistanceSquared = distanceSquared;
               }
            }

            Sample bottom = this.sampleOffset(center, dx, max, lookupMode);
            if (!bottom.available()) {
               complete = false;
            } else if (landClassPredicate.test(bottom.coverClass())) {
               int distanceSquared = dx * dx + max * max;
               if (distanceSquared < bestDistanceSquared) {
                  bestClass = bottom.coverClass();
                  bestDistanceSquared = distanceSquared;
               }
            }
         }

         for (int dy = min + 1; dy < max; dy++) {
            Sample left = this.sampleOffset(center, min, dy, lookupMode);
            if (!left.available()) {
               complete = false;
            } else if (landClassPredicate.test(left.coverClass())) {
               int distanceSquared = min * min + dy * dy;
               if (distanceSquared < bestDistanceSquared) {
                  bestClass = left.coverClass();
                  bestDistanceSquared = distanceSquared;
               }
            }

            Sample right = this.sampleOffset(center, max, dy, lookupMode);
            if (!right.available()) {
               complete = false;
            } else if (landClassPredicate.test(right.coverClass())) {
               int distanceSquared = max * max + dy * dy;
               if (distanceSquared < bestDistanceSquared) {
                  bestClass = right.coverClass();
                  bestDistanceSquared = distanceSquared;
               }
            }
         }

         int nextRadius = radius + 1;
         if (bestClass != UNAVAILABLE && bestDistanceSquared < nextRadius * nextRadius) {
            break;
         }
      }

      if (bestClass != UNAVAILABLE) {
         if (complete) {
            this.nearestLandCache.put(cacheKey, bestClass);
         }
         return new Sample(bestClass, true);
      }
      if (complete) {
         this.nearestLandCache.put(cacheKey, MISSING_NEAREST_LAND);
         return new Sample(fallbackCoverClass, true);
      }
      return Sample.unavailable();
   }

   boolean prefetch(double lon, double lat, double effectiveResolutionMeters, int radius) {
      SamplePosition center = this.position(lon, lat, effectiveResolutionMeters, LookupMode.BLOCKING);
      if (center == null) {
         return false;
      }

      CogLevel level = center.level();
      int centerBlockX = center.pixelX() / level.tileWidth();
      int centerBlockY = center.pixelY() / level.tileHeight();
      int clampedRadius = Math.max(0, radius);
      boolean centerAvailable = false;
      for (int blockY = Math.max(0, centerBlockY - clampedRadius);
         blockY <= Math.min(level.tilesPerColumn() - 1, centerBlockY + clampedRadius);
         blockY++) {
         for (int blockX = Math.max(0, centerBlockX - clampedRadius);
            blockX <= Math.min(level.tilesPerRow() - 1, centerBlockX + clampedRadius);
            blockX++) {
            BlockKey key = new BlockKey(center.tileKey(), level.factor(), blockX, blockY);
            byte[] values = this.getBlock(key, center.metadata(), LookupMode.BLOCKING);
            if (blockX == centerBlockX && blockY == centerBlockY) {
               centerAvailable = values != null;
            }
         }
      }
      return centerAvailable;
   }

   List<BlockKey> areaBlockKeys(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      double effectiveResolutionMeters
   ) {
      double worldScale = projection.worldScale();
      if (!(Double.isFinite(worldScale) && worldScale > 0.0)
         || !Double.isFinite(minBlockX)
         || !Double.isFinite(minBlockZ)
         || !Double.isFinite(maxBlockX)
         || !Double.isFinite(maxBlockZ)) {
         return List.of();
      }

      double lonA = projection.blockXToLon(minBlockX);
      double lonB = projection.blockXToLon(maxBlockX);
      double latA = projection.blockZToLat(minBlockZ);
      double latB = projection.blockZToLat(maxBlockZ);
      double minLat = Math.max(MIN_TILE_LAT, Math.min(latA, latB));
      double maxLat = Math.min(MAX_TILE_LAT_EXCLUSIVE, Math.max(latA, latB));
      if (!(minLat <= maxLat)) {
         return List.of();
      }
      double[][] longitudeRanges = projection.isCentered() && lonB < lonA
         ? new double[][]{{lonA, MAX_TILE_LON_EXCLUSIVE}, {MIN_TILE_LON, lonB}}
         : new double[][]{{
            Math.max(MIN_TILE_LON, Math.min(lonA, lonB)),
            Math.min(MAX_TILE_LON_EXCLUSIVE, Math.max(lonA, lonB))
         }};

      int factor = selectOverviewFactor(effectiveResolutionMeters);
      int rasterSize = rasterSizeForFactor(factor);
      int firstLat = tileOrigin(minLat, MIN_TILE_LAT, MAX_TILE_LAT_EXCLUSIVE);
      int lastLat = tileOrigin(maxLat == MAX_TILE_LAT_EXCLUSIVE ? Math.nextDown(maxLat) : maxLat, MIN_TILE_LAT, MAX_TILE_LAT_EXCLUSIVE);
      Set<BlockKey> keys = new LinkedHashSet<>();
      for (double[] longitudeRange : longitudeRanges) {
         double minLon = longitudeRange[0];
         double maxLon = longitudeRange[1];
         int firstLon = tileOrigin(minLon, MIN_TILE_LON, MAX_TILE_LON_EXCLUSIVE);
         int lastLon = tileOrigin(
            maxLon == MAX_TILE_LON_EXCLUSIVE ? Math.nextDown(maxLon) : maxLon,
            MIN_TILE_LON,
            MAX_TILE_LON_EXCLUSIVE
         );
         for (int tileLat = firstLat; tileLat <= lastLat; tileLat += TILE_DEGREES) {
            for (int tileLon = firstLon; tileLon <= lastLon; tileLon += TILE_DEGREES) {
               SourceTileKey tileKey = new SourceTileKey(tileLat, tileLon);
               double intersectionMinLon = Math.max(minLon, tileLon);
               double intersectionMaxLon = Math.min(maxLon, tileLon + TILE_DEGREES);
               double intersectionMinLat = Math.max(minLat, tileLat);
               double intersectionMaxLat = Math.min(maxLat, tileLat + TILE_DEGREES);
               int minPixelX = pixelForCoordinate(intersectionMinLon, tileLon, rasterSize);
               int maxPixelX = pixelForCoordinate(intersectionMaxLon, tileLon, rasterSize);
               int minPixelY = pixelForCoordinate(tileLat + TILE_DEGREES - intersectionMaxLat, 0.0, rasterSize);
               int maxPixelY = pixelForCoordinate(tileLat + TILE_DEGREES - intersectionMinLat, 0.0, rasterSize);
               int minCogBlockX = minPixelX / TIFF_TILE_SIZE;
               int maxCogBlockX = maxPixelX / TIFF_TILE_SIZE;
               int minCogBlockY = minPixelY / TIFF_TILE_SIZE;
               int maxCogBlockY = maxPixelY / TIFF_TILE_SIZE;
               for (int blockY = minCogBlockY; blockY <= maxCogBlockY; blockY++) {
                  for (int blockX = minCogBlockX; blockX <= maxCogBlockX; blockX++) {
                     keys.add(new BlockKey(tileKey, factor, blockX, blockY));
                  }
               }
            }
         }
      }
      return new ArrayList<>(keys);
   }

   boolean fullyCoversArea(
      double minBlockX, double minBlockZ, double maxBlockX, double maxBlockZ, WorldProjection projection
   ) {
      double worldScale = projection.worldScale();
      if (!(Double.isFinite(worldScale) && worldScale > 0.0)
         || !Double.isFinite(minBlockX)
         || !Double.isFinite(minBlockZ)
         || !Double.isFinite(maxBlockX)
         || !Double.isFinite(maxBlockZ)) {
         return false;
      }
      double lonA = projection.blockXToLon(minBlockX);
      double lonB = projection.blockXToLon(maxBlockX);
      double latA = projection.blockZToLat(minBlockZ);
      double latB = projection.blockZToLat(maxBlockZ);
      return Math.min(lonA, lonB) >= MIN_TILE_LON
         && Math.max(lonA, lonB) <= MAX_TILE_LON_EXCLUSIVE
         && Math.min(latA, latB) >= MIN_TILE_LAT
         && Math.max(latA, latB) < MAX_TILE_LAT_EXCLUSIVE;
   }

   void downloadBlock(BlockKey key) throws IOException {
      CogMetadata metadata = this.getMetadata(key.tileKey(), LookupMode.BLOCKING);
      if (metadata == null || metadata.missing()) {
         return;
      }
      if (this.getBlock(key, metadata, LookupMode.BLOCKING) == null) {
         throw new IOException("WorldCover block is unavailable: " + key.label());
      }
   }

   void clearMemoryCache() {
      this.metadataCache.invalidateAll();
      this.metadataCache.cleanUp();
      this.blockCache.invalidateAll();
      this.blockCache.cleanUp();
      this.nearestLandCache.invalidateAll();
      this.nearestLandCache.cleanUp();
      this.metadataFailureDeadlines.invalidateAll();
      this.metadataFailureDeadlines.cleanUp();
      this.blockFailureDeadlines.invalidateAll();
      this.blockFailureDeadlines.cleanUp();
   }

   private SamplePosition position(double lon, double lat, double effectiveResolutionMeters, LookupMode lookupMode) {
      SourceTileKey tileKey = SourceTileKey.forLonLat(lon, lat);
      if (tileKey == null) {
         return null;
      }
      CogMetadata metadata = this.getMetadata(tileKey, lookupMode);
      if (metadata == null || metadata.missing()) {
         return null;
      }
      CogLevel level = metadata.levelForResolution(effectiveResolutionMeters);
      if (level == null) {
         return null;
      }

      double normalizedX = (lon - tileKey.lon()) / TILE_DEGREES;
      double normalizedY = (tileKey.lat() + TILE_DEGREES - lat) / TILE_DEGREES;
      int pixelX = normalizedPixel(normalizedX, level.width());
      int pixelY = normalizedPixel(normalizedY, level.height());
      return new SamplePosition(tileKey, metadata, level, pixelX, pixelY);
   }

   private Sample samplePosition(SamplePosition position, LookupMode lookupMode) {
      CogLevel level = position.level();
      int blockX = position.pixelX() / level.tileWidth();
      int blockY = position.pixelY() / level.tileHeight();
      BlockKey key = new BlockKey(position.tileKey(), level.factor(), blockX, blockY);
      byte[] values = this.getBlock(key, position.metadata(), lookupMode);
      if (values == null) {
         return Sample.unavailable();
      }
      int localX = position.pixelX() - blockX * level.tileWidth();
      int localY = position.pixelY() - blockY * level.tileHeight();
      int index = localY * level.tileWidth() + localX;
      if (index < 0 || index >= values.length) {
         return Sample.unavailable();
      }
      int coverClass = values[index] & 255;
      return new Sample(isWorldCoverClass(coverClass) ? coverClass : 0, true);
   }

   private Sample sampleOffset(SamplePosition center, int dx, int dy, LookupMode lookupMode) {
      int pixelX = center.pixelX() + dx;
      int pixelY = center.pixelY() + dy;
      if (pixelX >= 0 && pixelY >= 0 && pixelX < center.level().width() && pixelY < center.level().height()) {
         return this.samplePosition(
            new SamplePosition(center.tileKey(), center.metadata(), center.level(), pixelX, pixelY),
            lookupMode
         );
      }

      double lon = center.tileKey().lon() + (pixelX + 0.5) * TILE_DEGREES / center.level().width();
      double lat = center.tileKey().lat() + TILE_DEGREES - (pixelY + 0.5) * TILE_DEGREES / center.level().height();
      return this.sample(lon, lat, SOURCE_RESOLUTION_METERS * center.level().factor(), lookupMode);
   }

   private int sampleVisualOffset(
      SamplePosition center, int dx, int dy, int fallbackCoverClass, LookupMode lookupMode
   ) {
      Sample sample = this.sampleOffset(center, dx, dy, lookupMode);
      return sample.available() ? sample.coverClass() : fallbackCoverClass;
   }

   private CogMetadata getMetadata(SourceTileKey key, LookupMode lookupMode) {
      CogMetadata cached = this.metadataCache.getIfPresent(key);
      if (cached != null) {
         return cached;
      }
      if (lookupMode == LookupMode.MEMORY_ONLY) {
         return null;
      }

      CogMetadata local = this.readLocalMetadata(key);
      if (local != null) {
         CogMetadata raced = this.metadataCache.asMap().putIfAbsent(key, local);
         return raced == null ? local : raced;
      }
      if (lookupMode == LookupMode.LOCAL_ONLY || !retryAllowed(this.metadataFailureDeadlines, key)) {
         return null;
      }

      try {
         CogMetadata loaded = this.metadataCache.get(key);
         this.metadataFailureDeadlines.invalidate(key);
         return loaded;
      } catch (ExecutionException error) {
         Throwable cause = error.getCause();
         if (TellusLandCoverSource.isInterruptedLoad(cause)) {
            Thread.currentThread().interrupt();
         } else {
            rememberFailure(this.metadataFailureDeadlines, key);
            Tellus.LOGGER.warn("Failed to load WorldCover COG index {}", key.code(), cause);
         }
         return null;
      }
   }

   private CogMetadata loadMetadataBlocking(SourceTileKey key) throws IOException {
      CogMetadata local = this.readLocalMetadata(key);
      if (local != null) {
         return local;
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.LAND_COVER);
      URI uri = this.tileUri(key);
      CogMetadata metadata;
      try {
         metadata = CogMetadata.read((offset, length) -> this.fetchRange(uri, offset, length));
      } catch (MissingCogException missing) {
         metadata = CogMetadata.missingMetadata();
      }
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.LAND_COVER, generation)) {
         throw new IOException("Discarded stale WorldCover COG index " + key.code());
      }
      if (metadata.missing()) {
         this.cacheMissingMetadata(key, generation);
      } else {
         this.cacheMetadata(key, metadata, generation);
      }
      return metadata;
   }

   private byte[] getBlock(BlockKey key, CogMetadata metadata, LookupMode lookupMode) {
      byte[] cached = this.blockCache.getIfPresent(key);
      if (cached != null) {
         return cached;
      }
      if (lookupMode == LookupMode.MEMORY_ONLY) {
         return null;
      }

      byte[] local = this.readLocalBlock(key, metadata);
      if (local != null) {
         byte[] raced = this.blockCache.asMap().putIfAbsent(key, local);
         return raced == null ? local : raced;
      }
      if (lookupMode == LookupMode.LOCAL_ONLY || !retryAllowed(this.blockFailureDeadlines, key)) {
         return null;
      }

      try {
         byte[] loaded = this.blockCache.get(key);
         this.blockFailureDeadlines.invalidate(key);
         return loaded;
      } catch (ExecutionException error) {
         Throwable cause = error.getCause();
         if (TellusLandCoverSource.isInterruptedLoad(cause)) {
            Thread.currentThread().interrupt();
         } else {
            rememberFailure(this.blockFailureDeadlines, key);
            Tellus.LOGGER.warn("Failed to load WorldCover block {}", key.label(), cause);
         }
         return null;
      }
   }

   private byte[] loadBlockBlocking(BlockKey key) throws IOException {
      CogMetadata metadata = this.getMetadata(key.tileKey(), LookupMode.BLOCKING);
      if (metadata == null || metadata.missing()) {
         throw new IOException("WorldCover COG is unavailable for " + key.tileKey().code());
      }
      byte[] local = this.readLocalBlock(key, metadata);
      if (local != null) {
         return local;
      }

      CogLevel level = metadata.levelForFactor(key.overviewFactor());
      int tileIndex = tileIndex(level, key.blockX(), key.blockY());
      long offset = level.tileOffsets()[tileIndex];
      int length = level.tileByteCounts()[tileIndex];
      URI uri = this.tileUri(key.tileKey());
      long generation = TellusCacheRegistry.generation(TellusCacheDomain.LAND_COVER);
      byte[] compressed = this.fetchRange(uri, offset, length);
      byte[] inflated = inflate(compressed, decodedBlockSize(level));
      if (!TellusCacheFiles.writeBytesIfCurrent(
         TellusCacheDomain.LAND_COVER, generation, this.blockCachePath(key), compressed
      )) {
         throw new IOException("Discarded stale WorldCover block cache write " + key.label());
      }
      this.pruneDiskCacheAfterWrite();
      return inflated;
   }

   private byte[] readLocalBlock(BlockKey key, CogMetadata metadata) {
      Path path = this.blockCachePath(key);
      if (!Files.isRegularFile(path)) {
         return null;
      }
      try {
         long size = Files.size(path);
         if (size <= 0L || size > MAX_COMPRESSED_BLOCK_BYTES) {
            throw new IOException("Invalid compressed WorldCover block size " + size);
         }
         byte[] compressed = Files.readAllBytes(path);
         CogLevel level = metadata.levelForFactor(key.overviewFactor());
         tileIndex(level, key.blockX(), key.blockY());
         byte[] values = inflate(compressed, decodedBlockSize(level));
         touch(path);
         return values;
      } catch (IOException | RuntimeException error) {
         Tellus.LOGGER.debug("Invalid cached WorldCover block {}, refetching", path, error);
         deleteQuietly(path);
         return null;
      }
   }

   private CogMetadata readLocalMetadata(SourceTileKey key) {
      if (Files.isRegularFile(this.missingMetadataPath(key))) {
         return CogMetadata.missingMetadata();
      }
      Path path = this.metadataCachePath(key);
      if (!Files.isRegularFile(path)) {
         return null;
      }
      try {
         byte[] bytes;
         try (InputStream input = Files.newInputStream(path)) {
            bytes = InputStreamSafety.readAllBytes(input, MAX_METADATA_CACHE_BYTES, "WorldCover COG metadata cache");
         }
         CogMetadata metadata = CogMetadata.fromCache(bytes, key);
         touch(path);
         return metadata;
      } catch (IOException | RuntimeException error) {
         Tellus.LOGGER.debug("Invalid cached WorldCover COG metadata {}, refetching", path, error);
         deleteQuietly(path);
         return null;
      }
   }

   private void cacheMetadata(SourceTileKey key, CogMetadata metadata, long generation) throws IOException {
      byte[] encoded = metadata.toCache(key);
      if (!TellusCacheFiles.writeBytesIfCurrent(
         TellusCacheDomain.LAND_COVER, generation, this.metadataCachePath(key), encoded
      )) {
         throw new IOException("Discarded stale WorldCover metadata cache write " + key.code());
      }
      deleteQuietly(this.missingMetadataPath(key));
   }

   private void cacheMissingMetadata(SourceTileKey key, long generation) throws IOException {
      if (!TellusCacheFiles.writeBytesIfCurrent(
         TellusCacheDomain.LAND_COVER, generation, this.missingMetadataPath(key), new byte[]{1}
      )) {
         throw new IOException("Discarded stale WorldCover missing marker " + key.code());
      }
   }

   private byte[] fetchRange(URI uri, long offset, int length) throws IOException {
      IOException lastFailure = null;
      for (int attempt = 1; attempt <= this.fetchRetryAttempts; attempt++) {
         try {
            return this.rangeAccessFactory.open(uri).read(offset, length);
         } catch (MissingCogException missing) {
            throw missing;
         } catch (IOException error) {
            if (TellusLandCoverSource.isInterruptedLoad(error)) {
               Thread.currentThread().interrupt();
               throw error;
            }
            lastFailure = error;
         }
      }
      throw new IOException("WorldCover range request failed for " + uri + " at " + offset + "+" + length, lastFailure);
   }

   private URI tileUri(SourceTileKey key) {
      return this.baseUri.resolve(String.format(Locale.ROOT, FILE_PATTERN, key.code()));
   }

   private Path metadataCachePath(SourceTileKey key) {
      return this.cacheRoot.resolve(key.code()).resolve("index-v" + METADATA_VERSION + ".bin");
   }

   private Path missingMetadataPath(SourceTileKey key) {
      return this.cacheRoot.resolve(key.code()).resolve("missing-v" + METADATA_VERSION);
   }

   private Path blockCachePath(BlockKey key) {
      return this.cacheRoot
         .resolve(key.tileKey().code())
         .resolve("f" + key.overviewFactor())
         .resolve(key.blockY() + "-" + key.blockX() + ".deflate");
   }

   private void pruneDiskCacheAfterWrite() {
      if (this.writesUntilPrune.getAndIncrement() % CACHE_PRUNE_INTERVAL != 0) {
         return;
      }
      synchronized (this.pruneLock) {
         try {
            if (!Files.isDirectory(this.cacheRoot)) {
               return;
            }
            List<CacheFile> blocks = new ArrayList<>();
            long total = 0L;
            try (var paths = Files.walk(this.cacheRoot)) {
               for (Path path : (Iterable<Path>)paths::iterator) {
                  if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".deflate")) {
                     long size = Files.size(path);
                     total += size;
                     blocks.add(new CacheFile(path, size, Files.getLastModifiedTime(path).toMillis()));
                  }
               }
            }
            if (total <= this.maximumDiskCacheBytes) {
               return;
            }
            blocks.sort(Comparator.comparingLong(CacheFile::lastModifiedMillis));
            long target = Math.max(1L, this.maximumDiskCacheBytes * 9L / 10L);
            for (CacheFile block : blocks) {
               if (total <= target) {
                  break;
               }
               if (Files.deleteIfExists(block.path())) {
                  total -= block.size();
               }
            }
         } catch (IOException error) {
            Tellus.LOGGER.debug("Failed to prune WorldCover block cache {}", this.cacheRoot, error);
         }
      }
   }

   static int selectOverviewFactor(double effectiveResolutionMeters) {
      double resolution = Double.isFinite(effectiveResolutionMeters) && effectiveResolutionMeters > 0.0
         ? Math.max(SOURCE_RESOLUTION_METERS, effectiveResolutionMeters)
         : SOURCE_RESOLUTION_METERS;
      int selected = 1;
      for (int factor : KNOWN_OVERVIEW_FACTORS) {
         if (SOURCE_RESOLUTION_METERS * factor <= resolution) {
            selected = factor;
         } else {
            break;
         }
      }
      return selected;
   }

   static int rasterSizeForFactor(int factor) {
      if (factor <= 0) {
         throw new IllegalArgumentException("Overview factor must be positive");
      }
      return BASE_RASTER_SIZE / factor;
   }

   private static int tileOrigin(double coordinate, int minimum, int maximumExclusive) {
      double safe = coordinate == maximumExclusive ? Math.nextDown(coordinate) : coordinate;
      int origin = (int)Math.floor(safe / TILE_DEGREES) * TILE_DEGREES;
      return Math.max(minimum, Math.min(maximumExclusive - TILE_DEGREES, origin));
   }

   private static int pixelForCoordinate(double coordinate, double origin, int rasterSize) {
      double normalized = (coordinate - origin) / TILE_DEGREES;
      return normalizedPixel(normalized, rasterSize);
   }

   private static int normalizedPixel(double normalized, int rasterSize) {
      double safe = Math.max(0.0, Math.min(Math.nextDown(1.0), normalized));
      return Math.max(0, Math.min(rasterSize - 1, (int)Math.floor(safe * rasterSize)));
   }

   private static double continuousPixel(double normalized, int rasterSize) {
      double safe = Math.max(0.0, Math.min(Math.nextDown(1.0), normalized));
      return safe * rasterSize;
   }

   private static int tileIndex(CogLevel level, int blockX, int blockY) {
      if (level == null
         || blockX < 0
         || blockY < 0
         || blockX >= level.tilesPerRow()
         || blockY >= level.tilesPerColumn()) {
         throw new IllegalArgumentException("WorldCover block lies outside its overview");
      }
      return blockY * level.tilesPerRow() + blockX;
   }

   private static int decodedBlockSize(CogLevel level) throws IOException {
      long size = (long)level.tileWidth() * level.tileHeight();
      if (size <= 0L || size > MAX_DECOMPRESSED_BLOCK_BYTES) {
         throw new IOException("Invalid WorldCover decoded block size " + size);
      }
      return (int)size;
   }

   static byte[] inflate(byte[] compressed, int expectedSize) throws IOException {
      if (expectedSize <= 0 || expectedSize > MAX_DECOMPRESSED_BLOCK_BYTES) {
         throw new IOException("Invalid expected WorldCover block size " + expectedSize);
      }
      byte[] output = new byte[expectedSize];
      try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
         int offset = 0;
         while (offset < output.length) {
            int read = inflater.read(output, offset, output.length - offset);
            if (read < 0) {
               break;
            }
            offset += read;
         }
         if (offset != output.length || inflater.read() != -1) {
            throw new IOException("Unexpected inflated WorldCover block length");
         }
      }
      return output;
   }

   private static boolean isWorldCoverClass(int value) {
      return value == 0
         || value == 10
         || value == 20
         || value == 30
         || value == 40
         || value == 50
         || value == 60
         || value == 70
         || value == 80
         || value == 90
         || value == 95
         || value == 100;
   }

   private static <K> boolean retryAllowed(Cache<K, Long> deadlines, K key) {
      Long deadline = deadlines.getIfPresent(key);
      return deadline == null || System.nanoTime() - deadline >= 0L;
   }

   private static <K> void rememberFailure(Cache<K, Long> deadlines, K key) {
      deadlines.put(key, System.nanoTime() + FAILURE_RETRY_NANOS);
   }

   private static URI normalizeBaseUri(URI uri) {
      if (uri == null || uri.getScheme() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
         throw new IllegalArgumentException("WorldCover base URL must use HTTP or HTTPS");
      }
      String text = uri.normalize().toString();
      return URI.create(text.endsWith("/") ? text : text + "/");
   }

   private static void touch(Path path) {
      try {
         Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
      } catch (IOException ignored) {
      }
   }

   private static void deleteQuietly(Path path) {
      try {
         Files.deleteIfExists(path);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to delete invalid WorldCover cache file {}", path, error);
      }
   }

   enum LookupMode {
      BLOCKING,
      LOCAL_ONLY,
      MEMORY_ONLY
   }

   record Sample(int coverClass, boolean available) {
      static Sample unavailable() {
         return new Sample(UNAVAILABLE, false);
      }
   }

   record SourceTileKey(int lat, int lon) {
      SourceTileKey {
         if (lat < MIN_TILE_LAT
            || lat >= MAX_TILE_LAT_EXCLUSIVE
            || lon < MIN_TILE_LON
            || lon >= MAX_TILE_LON_EXCLUSIVE
            || Math.floorMod(lat, TILE_DEGREES) != 0
            || Math.floorMod(lon, TILE_DEGREES) != 0) {
            throw new IllegalArgumentException("Invalid WorldCover source tile " + lat + "," + lon);
         }
      }

      static SourceTileKey forLonLat(double lon, double lat) {
         if (!Double.isFinite(lon)
            || !Double.isFinite(lat)
            || lon < MIN_TILE_LON
            || lon > MAX_TILE_LON_EXCLUSIVE
            || lat < MIN_TILE_LAT
            || lat >= MAX_TILE_LAT_EXCLUSIVE) {
            return null;
         }
         double safeLon = lon == MAX_TILE_LON_EXCLUSIVE ? Math.nextDown(lon) : lon;
         int tileLat = (int)Math.floor(lat / TILE_DEGREES) * TILE_DEGREES;
         int tileLon = (int)Math.floor(safeLon / TILE_DEGREES) * TILE_DEGREES;
         return new SourceTileKey(tileLat, tileLon);
      }

      String code() {
         return String.format(
            Locale.ROOT,
            "%s%02d%s%03d",
            this.lat >= 0 ? "N" : "S",
            Math.abs(this.lat),
            this.lon >= 0 ? "E" : "W",
            Math.abs(this.lon)
         );
      }
   }

   record BlockKey(SourceTileKey tileKey, int overviewFactor, int blockX, int blockY) {
      BlockKey {
         if (tileKey == null || overviewFactor <= 0 || blockX < 0 || blockY < 0) {
            throw new IllegalArgumentException("Invalid WorldCover block key");
         }
      }

      String label() {
         return this.tileKey.code() + "/f" + this.overviewFactor + "/" + this.blockY + "-" + this.blockX;
      }
   }

   private record SamplePosition(
      SourceTileKey tileKey, CogMetadata metadata, CogLevel level, int pixelX, int pixelY
   ) {
   }

   private record NearestLandKey(
      SourceTileKey tileKey, int overviewFactor, int pixelX, int pixelY, int maximumRadius
   ) {
   }

   private record CacheFile(Path path, long size, long lastModifiedMillis) {
   }

   @FunctionalInterface
   interface RangeAccess {
      byte[] read(long offset, int length) throws IOException;
   }

   @FunctionalInterface
   interface RangeAccessFactory {
      RangeAccess open(URI uri) throws IOException;
   }

   static final class CogMetadata {
      private static final CogMetadata MISSING = new CogMetadata(true, List.of());
      private final boolean missing;
      private final List<CogLevel> levels;

      private CogMetadata(boolean missing, List<CogLevel> levels) {
         this.missing = missing;
         this.levels = levels;
      }

      static CogMetadata missingMetadata() {
         return MISSING;
      }

      static CogMetadata read(RangeAccess source) throws IOException {
         BufferedRangeAccess input = new BufferedRangeAccess(source, source.read(0L, METADATA_BOOTSTRAP_BYTES));
         byte[] header = input.read(0L, 8);
         ByteOrder order;
         if (header[0] == 'I' && header[1] == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
         } else if (header[0] == 'M' && header[1] == 'M') {
            order = ByteOrder.BIG_ENDIAN;
         } else {
            throw new IOException("Invalid WorldCover TIFF byte order");
         }
         ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(order);
         headerBuffer.position(2);
         if (Short.toUnsignedInt(headerBuffer.getShort()) != 42) {
            throw new IOException("Unsupported WorldCover TIFF format");
         }
         long ifdOffset = Integer.toUnsignedLong(headerBuffer.getInt());
         Set<Long> visitedOffsets = new HashSet<>();
         List<RawLevel> rawLevels = new ArrayList<>();
         for (int levelIndex = 0; ifdOffset != 0L; levelIndex++) {
            if (levelIndex >= MAX_IFD_LEVELS || !visitedOffsets.add(ifdOffset)) {
               throw new IOException("Invalid WorldCover TIFF IFD chain");
            }
            byte[] countBytes = input.read(ifdOffset, 2);
            int entryCount = Short.toUnsignedInt(ByteBuffer.wrap(countBytes).order(order).getShort());
            if (entryCount <= 0 || entryCount > MAX_IFD_ENTRIES) {
               throw new IOException("Invalid WorldCover TIFF IFD entry count " + entryCount);
            }
            int directoryLength = Math.addExact(Math.multiplyExact(entryCount, 12), 4);
            byte[] directory = input.read(ifdOffset + 2L, directoryLength);
            ByteBuffer entries = ByteBuffer.wrap(directory).order(order);
            List<TiffEntry> tags = new ArrayList<>(entryCount);
            for (int i = 0; i < entryCount; i++) {
               int tag = Short.toUnsignedInt(entries.getShort());
               int type = Short.toUnsignedInt(entries.getShort());
               long count = Integer.toUnsignedLong(entries.getInt());
               byte[] inline = new byte[4];
               entries.get(inline);
               long valueOffset = Integer.toUnsignedLong(ByteBuffer.wrap(inline).order(order).getInt());
               tags.add(new TiffEntry(tag, type, count, valueOffset, inline));
            }
            long nextIfdOffset = Integer.toUnsignedLong(entries.getInt());
            rawLevels.add(readLevel(input, tags, order));
            ifdOffset = nextIfdOffset;
         }
         if (rawLevels.isEmpty()) {
            throw new IOException("WorldCover TIFF contains no image levels");
         }

         rawLevels.sort(Comparator.comparingInt(RawLevel::width).reversed());
         int nativeWidth = rawLevels.get(0).width();
         List<CogLevel> levels = new ArrayList<>(rawLevels.size());
         for (RawLevel raw : rawLevels) {
            int factor = Math.max(1, (int)Math.round((double)nativeWidth / raw.width()));
            levels.add(
               new CogLevel(
                  factor,
                  raw.width(),
                  raw.height(),
                  raw.tileWidth(),
                  raw.tileHeight(),
                  raw.tileOffsets(),
                  raw.tileByteCounts()
               )
            );
         }
         return new CogMetadata(false, List.copyOf(levels));
      }

      private static RawLevel readLevel(
         BufferedRangeAccess input, List<TiffEntry> tags, ByteOrder order
      ) throws IOException {
         int width = requiredInt(input, tags, 256, order);
         int height = requiredInt(input, tags, 257, order);
         int bits = optionalInt(input, tags, 258, order, 1);
         int compression = requiredInt(input, tags, 259, order);
         int photometric = optionalInt(input, tags, 262, order, 1);
         int samples = optionalInt(input, tags, 277, order, 1);
         int planar = optionalInt(input, tags, 284, order, 1);
         int predictor = optionalInt(input, tags, 317, order, 1);
         int tileWidth = requiredInt(input, tags, 322, order);
         int tileHeight = requiredInt(input, tags, 323, order);
         int sampleFormat = optionalInt(input, tags, 339, order, 1);
         long[] tileOffsets = requiredUnsignedArray(input, tags, 324, order);
         long[] rawByteCounts = requiredUnsignedArray(input, tags, 325, order);
         if (width <= 0
            || height <= 0
            || tileWidth <= 0
            || tileHeight <= 0
            || tileWidth > 4_096
            || tileHeight > 4_096) {
            throw new IOException("Invalid WorldCover TIFF dimensions");
         }
         if (bits != 8
            || compression != 8
            || !(photometric == 1 || photometric == 3)
            || samples != 1
            || planar != 1
            || predictor != 1
            || sampleFormat != 1) {
            throw new IOException("Unsupported WorldCover TIFF pixel encoding");
         }
         int tilesPerRow = (width + tileWidth - 1) / tileWidth;
         int tilesPerColumn = (height + tileHeight - 1) / tileHeight;
         int expectedTiles = Math.multiplyExact(tilesPerRow, tilesPerColumn);
         if (expectedTiles <= 0
            || expectedTiles > MAX_TILES_PER_LEVEL
            || tileOffsets.length != expectedTiles
            || rawByteCounts.length != expectedTiles) {
            throw new IOException("Invalid WorldCover TIFF tile directory");
         }
         int[] tileByteCounts = new int[expectedTiles];
         for (int i = 0; i < expectedTiles; i++) {
            long count = rawByteCounts[i];
            if (tileOffsets[i] < 0L || count <= 0L || count > MAX_COMPRESSED_BLOCK_BYTES) {
               throw new IOException("Invalid WorldCover TIFF block range");
            }
            try {
               Math.addExact(tileOffsets[i], count);
            } catch (ArithmeticException error) {
               throw new IOException("WorldCover TIFF block range overflows", error);
            }
            tileByteCounts[i] = (int)count;
         }
         return new RawLevel(width, height, tileWidth, tileHeight, tileOffsets, tileByteCounts);
      }

      private static int requiredInt(
         BufferedRangeAccess input, List<TiffEntry> tags, int requestedTag, ByteOrder order
      ) throws IOException {
         TiffEntry entry = findTag(tags, requestedTag);
         if (entry == null) {
            throw new IOException("Missing WorldCover TIFF tag " + requestedTag);
         }
         long[] values = unsignedValues(input, entry, order);
         if (values.length != 1 || values[0] > Integer.MAX_VALUE) {
            throw new IOException("Invalid WorldCover TIFF tag " + requestedTag);
         }
         return (int)values[0];
      }

      private static int optionalInt(
         BufferedRangeAccess input, List<TiffEntry> tags, int requestedTag, ByteOrder order, int fallback
      ) throws IOException {
         TiffEntry entry = findTag(tags, requestedTag);
         if (entry == null) {
            return fallback;
         }
         long[] values = unsignedValues(input, entry, order);
         if (values.length < 1 || values[0] > Integer.MAX_VALUE) {
            throw new IOException("Invalid WorldCover TIFF tag " + requestedTag);
         }
         return (int)values[0];
      }

      private static long[] requiredUnsignedArray(
         BufferedRangeAccess input, List<TiffEntry> tags, int requestedTag, ByteOrder order
      ) throws IOException {
         TiffEntry entry = findTag(tags, requestedTag);
         if (entry == null) {
            throw new IOException("Missing WorldCover TIFF tag " + requestedTag);
         }
         return unsignedValues(input, entry, order);
      }

      private static long[] unsignedValues(
         BufferedRangeAccess input, TiffEntry entry, ByteOrder order
      ) throws IOException {
         if (entry.count() <= 0L || entry.count() > MAX_TILES_PER_LEVEL) {
            throw new IOException("Invalid WorldCover TIFF value count");
         }
         int typeSize = switch (entry.type()) {
            case 1 -> 1;
            case 3 -> 2;
            case 4 -> 4;
            default -> throw new IOException("Unsupported WorldCover TIFF integer type " + entry.type());
         };
         int byteCount = Math.toIntExact(Math.multiplyExact(entry.count(), typeSize));
         byte[] bytes = entryBytes(input, entry, byteCount);
         ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
         int count = Math.toIntExact(entry.count());
         long[] values = new long[count];
         for (int i = 0; i < count; i++) {
            values[i] = switch (entry.type()) {
               case 1 -> Byte.toUnsignedInt(buffer.get());
               case 3 -> Short.toUnsignedInt(buffer.getShort());
               case 4 -> Integer.toUnsignedLong(buffer.getInt());
               default -> throw new IOException("Unsupported WorldCover TIFF integer type");
            };
         }
         return values;
      }

      private static byte[] entryBytes(BufferedRangeAccess input, TiffEntry entry, int byteCount) throws IOException {
         if (byteCount <= 4) {
            byte[] bytes = new byte[byteCount];
            System.arraycopy(entry.inlineValue(), 0, bytes, 0, byteCount);
            return bytes;
         }
         return input.read(entry.valueOffset(), byteCount);
      }

      private static TiffEntry findTag(List<TiffEntry> tags, int requestedTag) {
         for (TiffEntry entry : tags) {
            if (entry.tag() == requestedTag) {
               return entry;
            }
         }
         return null;
      }

      static CogMetadata fromCache(byte[] encoded, SourceTileKey expectedKey) throws IOException {
         try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != METADATA_MAGIC || input.readInt() != METADATA_VERSION) {
               throw new IOException("Unsupported WorldCover metadata cache");
            }
            if (input.readInt() != expectedKey.lat() || input.readInt() != expectedKey.lon()) {
               throw new IOException("WorldCover metadata cache tile mismatch");
            }
            int levelCount = input.readInt();
            if (levelCount <= 0 || levelCount > MAX_IFD_LEVELS) {
               throw new IOException("Invalid WorldCover metadata level count");
            }
            List<CogLevel> levels = new ArrayList<>(levelCount);
            for (int levelIndex = 0; levelIndex < levelCount; levelIndex++) {
               int factor = input.readInt();
               int width = input.readInt();
               int height = input.readInt();
               int tileWidth = input.readInt();
               int tileHeight = input.readInt();
               int tileCount = input.readInt();
               if (factor <= 0
                  || width <= 0
                  || height <= 0
                  || tileWidth <= 0
                  || tileHeight <= 0
                  || tileCount <= 0
                  || tileCount > MAX_TILES_PER_LEVEL) {
                  throw new IOException("Invalid WorldCover cached overview");
               }
               long[] offsets = new long[tileCount];
               int[] byteCounts = new int[tileCount];
               for (int i = 0; i < tileCount; i++) {
                  offsets[i] = input.readLong();
                  byteCounts[i] = input.readInt();
                  if (offsets[i] < 0L || byteCounts[i] <= 0 || byteCounts[i] > MAX_COMPRESSED_BLOCK_BYTES) {
                     throw new IOException("Invalid WorldCover cached block range");
                  }
               }
               CogLevel level = new CogLevel(factor, width, height, tileWidth, tileHeight, offsets, byteCounts);
               if (level.tilesPerRow() * level.tilesPerColumn() != tileCount) {
                  throw new IOException("WorldCover cached block count mismatch");
               }
               levels.add(level);
            }
            if (input.read() != -1) {
               throw new IOException("WorldCover metadata cache contains trailing data");
            }
            return new CogMetadata(false, List.copyOf(levels));
         } catch (EOFException error) {
            throw new IOException("Truncated WorldCover metadata cache", error);
         }
      }

      byte[] toCache(SourceTileKey key) throws IOException {
         ByteArrayOutputStream bytes = new ByteArrayOutputStream();
         try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(METADATA_MAGIC);
            output.writeInt(METADATA_VERSION);
            output.writeInt(key.lat());
            output.writeInt(key.lon());
            output.writeInt(this.levels.size());
            for (CogLevel level : this.levels) {
               output.writeInt(level.factor());
               output.writeInt(level.width());
               output.writeInt(level.height());
               output.writeInt(level.tileWidth());
               output.writeInt(level.tileHeight());
               output.writeInt(level.tileOffsets().length);
               for (int i = 0; i < level.tileOffsets().length; i++) {
                  output.writeLong(level.tileOffsets()[i]);
                  output.writeInt(level.tileByteCounts()[i]);
               }
            }
         }
         if (bytes.size() > MAX_METADATA_CACHE_BYTES) {
            throw new IOException("WorldCover metadata cache exceeds safety limit");
         }
         return bytes.toByteArray();
      }

      boolean missing() {
         return this.missing;
      }

      List<CogLevel> levels() {
         return this.levels;
      }

      CogLevel levelForResolution(double effectiveResolutionMeters) {
         if (this.missing || this.levels.isEmpty()) {
            return null;
         }
         double resolution = Double.isFinite(effectiveResolutionMeters) && effectiveResolutionMeters > 0.0
            ? Math.max(SOURCE_RESOLUTION_METERS, effectiveResolutionMeters)
            : SOURCE_RESOLUTION_METERS;
         CogLevel selected = this.levels.get(0);
         for (CogLevel level : this.levels) {
            if (SOURCE_RESOLUTION_METERS * level.factor() <= resolution) {
               selected = level;
            } else {
               break;
            }
         }
         return selected;
      }

      CogLevel levelForFactor(int factor) {
         for (CogLevel level : this.levels) {
            if (level.factor() == factor) {
               return level;
            }
         }
         throw new IllegalArgumentException("WorldCover COG lacks overview factor " + factor);
      }
   }

   record CogLevel(
      int factor,
      int width,
      int height,
      int tileWidth,
      int tileHeight,
      long[] tileOffsets,
      int[] tileByteCounts
   ) {
      CogLevel {
         tileOffsets = tileOffsets.clone();
         tileByteCounts = tileByteCounts.clone();
      }

      @Override
      public long[] tileOffsets() {
         return this.tileOffsets;
      }

      @Override
      public int[] tileByteCounts() {
         return this.tileByteCounts;
      }

      int tilesPerRow() {
         return (this.width + this.tileWidth - 1) / this.tileWidth;
      }

      int tilesPerColumn() {
         return (this.height + this.tileHeight - 1) / this.tileHeight;
      }
   }

   private record RawLevel(
      int width, int height, int tileWidth, int tileHeight, long[] tileOffsets, int[] tileByteCounts
   ) {
   }

   private record TiffEntry(int tag, int type, long count, long valueOffset, byte[] inlineValue) {
   }

   private static final class BufferedRangeAccess {
      private final RangeAccess source;
      private final byte[] bootstrap;

      private BufferedRangeAccess(RangeAccess source, byte[] bootstrap) {
         this.source = source;
         this.bootstrap = bootstrap;
      }

      private byte[] read(long offset, int length) throws IOException {
         if (offset < 0L || length < 0) {
            throw new IOException("Invalid WorldCover TIFF range");
         }
         long end = Math.addExact(offset, length);
         if (end <= this.bootstrap.length) {
            byte[] result = new byte[length];
            System.arraycopy(this.bootstrap, (int)offset, result, 0, length);
            return result;
         }
         return this.source.read(offset, length);
      }
   }

   static final class HttpRangeAccess implements RangeAccess {
      private final URI uri;
      private final int connectTimeoutMs;
      private final int readTimeoutMs;

      HttpRangeAccess(URI uri, int connectTimeoutMs, int readTimeoutMs) {
         this.uri = uri;
         this.connectTimeoutMs = connectTimeoutMs;
         this.readTimeoutMs = readTimeoutMs;
      }

      @Override
      public byte[] read(long offset, int length) throws IOException {
         if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
            throw new IOException("Network access is disabled during managed Distant Horizons generation");
         }
         if (offset < 0L || length <= 0 || length > MAX_COMPRESSED_BLOCK_BYTES) {
            throw new IOException("Invalid WorldCover HTTP range");
         }
         long endInclusive = Math.addExact(offset, length - 1L);
         HttpURLConnection connection = (HttpURLConnection)this.uri.toURL().openConnection();
         connection.setRequestProperty("Range", "bytes=" + offset + "-" + endInclusive);
         connection.setRequestProperty("User-Agent", "Tellus/1.0 (Minecraft Mod)");
         connection.setInstanceFollowRedirects(true);
         connection.setConnectTimeout(this.connectTimeoutMs);
         connection.setReadTimeout(this.readTimeoutMs);
         int responseCode = connection.getResponseCode();
         if (responseCode == 404) {
            connection.disconnect();
            throw new MissingCogException(this.uri.toString());
         }
         long responseLength = connection.getContentLengthLong();
         DownloadProgressReporter.requestStarted(responseLength);
         try {
            if (responseCode != 206 && !(responseCode == 200 && offset == 0L)) {
               throw httpError(connection, responseCode);
            }
            if (responseCode == 206) {
               String contentRange = connection.getHeaderField("Content-Range");
               String expectedPrefix = "bytes " + offset + "-" + endInclusive + "/";
               if (contentRange == null || !contentRange.startsWith(expectedPrefix)) {
                  throw new IOException("WorldCover server returned an unexpected Content-Range " + contentRange);
               }
            }
            if (responseCode == 206 && responseLength >= 0L && responseLength != length) {
               throw new IOException("WorldCover server returned an unexpected range length " + responseLength);
            }
            try (InputStream input = connection.getInputStream()) {
               byte[] bytes = input.readNBytes(length);
               if (bytes.length != length) {
                  throw new EOFException("Truncated WorldCover HTTP range");
               }
               return bytes;
            }
         } finally {
            DownloadProgressReporter.requestFinished();
            connection.disconnect();
         }
      }

      private static IOException httpError(HttpURLConnection connection, int responseCode) throws IOException {
         InputStream error = connection.getErrorStream();
         if (error == null) {
            return new IOException("WorldCover HTTP error " + responseCode);
         }
         try (error) {
            byte[] message = error.readNBytes(512);
            return new IOException(
               "WorldCover HTTP error " + responseCode + ": " + new String(message, StandardCharsets.UTF_8).trim()
            );
         }
      }
   }

   private static final class MissingCogException extends IOException {
      private MissingCogException(String uri) {
         super("WorldCover COG does not exist: " + uri);
      }
   }

   private record Configuration(
      URI baseUri,
      Path cacheRoot,
      int connectTimeoutMs,
      int readTimeoutMs,
      int fetchRetryAttempts,
      int metadataCacheEntries,
      long maximumMemoryCacheBytes,
      long maximumDiskCacheBytes
   ) {
      private static Configuration fromSystemProperties() {
         URI baseUri = configuredBaseUri();
         String namespace = Integer.toUnsignedString(baseUri.toString().hashCode(), 36);
         Path cacheRoot = TellusPlatform.gameDir()
            .resolve("tellus/cache/worldcover-2021-v200-range")
            .resolve(namespace);
         return new Configuration(
            baseUri,
            cacheRoot,
            intProperty("tellus.worldcover.connectTimeoutMs", 30_000, 1, 120_000),
            intProperty("tellus.worldcover.readTimeoutMs", 60_000, 1, 180_000),
            intProperty("tellus.worldcover.fetchRetries", 3, 1, 8),
            intProperty("tellus.worldcover.metadataCacheEntries", 64, 1, 2_048),
            (long)intProperty("tellus.worldcover.memoryCacheMb", 64, 8, 2_048) * 1024L * 1024L,
            (long)intProperty("tellus.worldcover.diskCacheMb", 512, 32, 16_384) * 1024L * 1024L
         );
      }

      private static URI configuredBaseUri() {
         String configured = System.getProperty("tellus.worldcover.baseUrl");
         if (configured == null || configured.isBlank()) {
            return normalizeBaseUri(URI.create(DEFAULT_BASE_URL));
         }
         try {
            return normalizeBaseUri(URI.create(configured.trim()));
         } catch (IllegalArgumentException error) {
            Tellus.LOGGER.warn("Invalid WorldCover base URL '{}', using the official source", configured);
            return normalizeBaseUri(URI.create(DEFAULT_BASE_URL));
         }
      }
   }

   private static int intProperty(String key, int fallback, int minimum, int maximum) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return fallback;
      }
      try {
         return Math.max(minimum, Math.min(maximum, Integer.parseInt(value.trim())));
      } catch (NumberFormatException error) {
         Tellus.LOGGER.warn("Invalid integer system property {}={}, using {}", key, value, fallback);
         return fallback;
      }
   }
}
