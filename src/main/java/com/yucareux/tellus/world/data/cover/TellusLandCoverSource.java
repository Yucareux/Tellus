package com.yucareux.tellus.world.data.cover;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.osm.OvertureTileUrls;
import com.yucareux.tellus.world.data.osm.PmTilesRangeReader;
import com.yucareux.tellus.world.data.pmtiles.PmTilesSafety;
import com.yucareux.tellus.world.data.source.ParallelDownloadRunner;
import com.yucareux.tellus.worldgen.WorldProjection;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Feature;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.GeomType;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Layer;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Value;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntBinaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Samples the official ESA WorldCover 2021 COG pyramid and falls back to
 * Overture Maps land-cover vectors when the primary source is unavailable or
 * outside its latitude coverage.
 *
 * <p>The rest of world generation consumes stable WorldCover numeric class
 * values. Native COG pixels preserve the original 10 m classification while
 * built-in overview levels reduce I/O for larger world scales and LODs.</p>
 */
public final class TellusLandCoverSource implements TellusCacheHandle {
   static final int NO_DATA_CLASS = 0;
   static final int TREE_COVER_CLASS = 10;
   static final int SHRUBLAND_CLASS = 20;
   static final int GRASSLAND_CLASS = 30;
   static final int CROPLAND_CLASS = 40;
   static final int BUILT_UP_CLASS = 50;
   static final int BARE_CLASS = 60;
   static final int SNOW_ICE_CLASS = 70;
   static final int WATER_CLASS = 80;
   static final int WETLAND_CLASS = 90;
   static final int MANGROVES_CLASS = 95;
   static final int MOSS_LICHEN_CLASS = 100;

   private static final String PM_TILES_THEME = "base";
   private static final String LAND_COVER_LAYER_NAME = "land_cover";
   private static final int PMTILES_TILETYPE_MVT = 1;
   private static final int DEFAULT_TILE_EXTENT = 4096;
   private static final int DEFAULT_MIN_LAND_COVER_ZOOM = 8;
   private static final int DEFAULT_MAX_LAND_COVER_ZOOM = 13;
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double WEB_MERCATOR_CIRCUMFERENCE_METERS = 40075016.68557849;
   private static final double SOURCE_RESOLUTION_METERS = 10.0;
   private static final int RASTER_CACHE_MAGIC = 0x544C4332;
   private static final int RASTER_CACHE_VERSION = 3;
   private static final Pattern SORT_KEY_PATTERN = Pattern.compile("\\\"sort_key\\\"\\s*:\\s*(-?\\d+)");

   private static final int RASTER_SIZE = intProperty("tellus.overture.landCover.rasterSize", 512, 64, 1024);
   private static final int MAX_CACHE_TILES = intProperty("tellus.landcover.cacheTiles", 96, 1, 2048);
   private static final int DIRECTORY_CACHE_ENTRIES = intProperty("tellus.overture.landCover.dirCache", 256, 1, 8192);
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.overture.landCover.connectTimeoutMs", 30000, 1, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.overture.landCover.readTimeoutMs", 60000, 1, 180000);
   private static final int FETCH_RETRY_ATTEMPTS = intProperty("tellus.overture.landCover.fetchRetries", 3, 1, 8);
   private static final int NEAREST_LAND_RADIUS_PIXELS = intProperty("tellus.landcover.nearestLandRadiusPixels", 64, 1, 512);
   private static final int NEAREST_LAND_CACHE_ENTRIES = intProperty("tellus.landcover.nearestLandCacheEntries", 131072, 1024, 1048576);
   private static final int NEAREST_LAND_NOT_FOUND = -1;

   private final Path cacheRoot;
   private final PmTilesRangeReader pmTilesReader;
   private final WorldCoverCogSource worldCoverSource;
   private final Object initLock = new Object();
   private final LoadingCache<TileKey, RasterTile> cache;
   private final Cache<NearestLandKey, Integer> nearestLandCache;
   private volatile boolean initialized;
   private volatile boolean available;
   private volatile int minZoom = DEFAULT_MIN_LAND_COVER_ZOOM;
   private volatile int maxZoom = DEFAULT_MAX_LAND_COVER_ZOOM;
   private volatile int initializationFailures;
   private volatile long nextInitializationAttemptNanos;

   public TellusLandCoverSource() {
      String pmTilesUrl = System.getProperty("tellus.overture.landCover.pmtiles");
      if (pmTilesUrl == null || pmTilesUrl.isBlank()) {
         pmTilesUrl = OvertureTileUrls.defaultThemeUrl(PM_TILES_THEME);
      }

      this.cacheRoot = TellusPlatform.gameDir()
         .resolve("tellus/cache/land-cover-overture")
         .resolve(OvertureTileUrls.cacheNamespace(pmTilesUrl));
      this.pmTilesReader = PmTilesRangeReader.shared(pmTilesUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, DIRECTORY_CACHE_ENTRIES);
      this.worldCoverSource = new WorldCoverCogSource();
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<TileKey, RasterTile>() {
         @Override
         public RasterTile load(TileKey key) throws Exception {
            return TellusLandCoverSource.this.loadTileBlocking(key);
         }
      });
      this.nearestLandCache = CacheBuilder.newBuilder().maximumSize(NEAREST_LAND_CACHE_ENTRIES).build();
      TellusCacheRegistry.register(this);
   }

   public boolean isSnowIce(double blockX, double blockZ, WorldProjection projection) {
      return this.sampleCoverClass(blockX, blockZ, projection) == SNOW_ICE_CLASS;
   }

   public int sampleCoverClass(double blockX, double blockZ, WorldProjection projection) {
      return this.sampleCoverClass(blockX, blockZ, projection, projection.worldScale(), managedLookupMode());
   }

   public int sampleCoverClass(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      return this.sampleCoverClass(blockX, blockZ, projection, previewResolutionMeters, managedLookupMode());
   }

   public int sampleCoverClassLocalOnly(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      return this.sampleCoverClass(blockX, blockZ, projection, previewResolutionMeters, LookupMode.LOCAL_ONLY);
   }

   public int sampleCoverClassMemoryOnly(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      return this.sampleCoverClass(blockX, blockZ, projection, previewResolutionMeters, LookupMode.MEMORY_ONLY);
   }

   private int sampleCoverClass(
      double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters, LookupMode lookupMode
   ) {
      if (!isWithinTileCoverage(blockX, blockZ, projection)) {
         return NO_DATA_CLASS;
      }

      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      double resolutionMeters = effectiveSampleResolutionMeters(projection.worldScale(), previewResolutionMeters);
      WorldCoverCogSource.Sample worldCover = this.worldCoverSource.sample(
         lon, lat, resolutionMeters, worldCoverLookupMode(lookupMode)
      );
      if (worldCover.available()) {
         return worldCover.coverClass();
      }
      int zoom = this.queryZoom(resolutionMeters, lookupMode);
      return this.sampleAtLonLat(lon, lat, zoom, lookupMode);
   }

   public int sampleSmoothedCoverClass(double blockX, double blockZ, WorldProjection projection) {
      return this.sampleSmoothedCoverClass(blockX, blockZ, projection, projection.worldScale());
   }

   public int sampleSmoothedCoverClass(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      if (!isWithinTileCoverage(blockX, blockZ, projection)) {
         return NO_DATA_CLASS;
      }

      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      LookupMode lookupMode = managedLookupMode();
      double resolutionMeters = effectiveSampleResolutionMeters(projection.worldScale(), previewResolutionMeters);
      WorldCoverCogSource.Sample worldCover = this.worldCoverSource.sampleSmoothed(
         lon, lat, resolutionMeters, worldCoverLookupMode(lookupMode)
      );
      if (worldCover.available()) {
         return worldCover.coverClass();
      }
      int zoom = this.queryZoom(resolutionMeters, lookupMode);
      TilePosition center = tilePosition(lon, lat, zoom);
      if (center == null) {
         return NO_DATA_CLASS;
      }

      int[] counts = new int[256];
      int bestClass = this.sampleGrid(zoom, center.globalPixelX(), center.globalPixelY(), lookupMode);
      int bestCount = 0;
      for (int dz = -1; dz <= 1; dz++) {
         for (int dx = -1; dx <= 1; dx++) {
            int coverClass = this.sampleGrid(zoom, center.globalPixelX() + dx, center.globalPixelY() + dz, lookupMode);
            if (coverClass >= 0 && coverClass < counts.length) {
               int count = ++counts[coverClass];
               if (count > bestCount) {
                  bestCount = count;
                  bestClass = coverClass;
               }
            }
         }
      }

      return bestClass == Integer.MIN_VALUE ? NO_DATA_CLASS : bestClass;
   }

   public int sampleVisualCoverClass(double blockX, double blockZ, WorldProjection projection) {
      return this.sampleVisualCoverClass(blockX, blockZ, projection, projection.worldScale(), managedLookupMode());
   }

   public int sampleVisualCoverClass(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      return this.sampleVisualCoverClass(blockX, blockZ, projection, previewResolutionMeters, managedLookupMode());
   }

   public int sampleVisualCoverClassLocalOnly(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      return this.sampleVisualCoverClass(blockX, blockZ, projection, previewResolutionMeters, LookupMode.LOCAL_ONLY);
   }

   public int sampleVisualCoverClassMemoryOnly(double blockX, double blockZ, WorldProjection projection, double previewResolutionMeters) {
      return this.sampleVisualCoverClass(blockX, blockZ, projection, previewResolutionMeters, LookupMode.MEMORY_ONLY);
   }

   private int sampleVisualCoverClass(
      double blockX,
      double blockZ,
      WorldProjection projection,
      double previewResolutionMeters,
      LookupMode lookupMode
   ) {
      if (!isWithinTileCoverage(blockX, blockZ, projection)) {
         return NO_DATA_CLASS;
      }

      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      double resolutionMeters = effectiveSampleResolutionMeters(projection.worldScale(), previewResolutionMeters);
      WorldCoverCogSource.Sample worldCover = this.worldCoverSource.sampleVisual(
         lon,
         lat,
         resolutionMeters,
         blockX,
         blockZ,
         projection.worldScale(),
         worldCoverLookupMode(lookupMode)
      );
      if (worldCover.available()) {
         return worldCover.coverClass();
      }

      int zoom = this.queryZoom(resolutionMeters, lookupMode);
      return this.sampleVisualAtLonLat(lon, lat, zoom, resolutionMeters, blockX, blockZ, projection.worldScale(), lookupMode);
   }

   public int sampleNearestLandCoverClass(double blockX, double blockZ, WorldProjection projection, int fallbackCoverClass) {
      return this.sampleNearestLandCoverClass(blockX, blockZ, projection, fallbackCoverClass, managedLookupMode());
   }

   private static LookupMode managedLookupMode() {
      return ManagedTerrainNetworkPolicy.isCacheOnly() ? LookupMode.LOCAL_ONLY : LookupMode.BLOCKING;
   }

   private static WorldCoverCogSource.LookupMode worldCoverLookupMode(LookupMode lookupMode) {
      return switch (lookupMode) {
         case BLOCKING -> WorldCoverCogSource.LookupMode.BLOCKING;
         case LOCAL_ONLY -> WorldCoverCogSource.LookupMode.LOCAL_ONLY;
         case MEMORY_ONLY -> WorldCoverCogSource.LookupMode.MEMORY_ONLY;
      };
   }

   public int sampleNearestLandCoverClassLocalOnly(double blockX, double blockZ, WorldProjection projection, int fallbackCoverClass) {
      return this.sampleNearestLandCoverClass(blockX, blockZ, projection, fallbackCoverClass, LookupMode.LOCAL_ONLY);
   }

   public int sampleNearestLandCoverClassMemoryOnly(double blockX, double blockZ, WorldProjection projection, int fallbackCoverClass) {
      return this.sampleNearestLandCoverClass(blockX, blockZ, projection, fallbackCoverClass, LookupMode.MEMORY_ONLY);
   }

   private int sampleNearestLandCoverClass(
      double blockX, double blockZ, WorldProjection projection, int fallbackCoverClass, LookupMode lookupMode
   ) {
      if (!isWithinTileCoverage(blockX, blockZ, projection)) {
         return fallbackCoverClass;
      }

      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      WorldCoverCogSource.Sample worldCover = this.worldCoverSource.sampleNearestLand(
         lon,
         lat,
         Math.max(SOURCE_RESOLUTION_METERS, projection.worldScale()),
         NEAREST_LAND_RADIUS_PIXELS,
         fallbackCoverClass,
         TellusLandCoverSource::isLandCoverClass,
         worldCoverLookupMode(lookupMode)
      );
      if (worldCover.available()) {
         return worldCover.coverClass();
      }
      int zoom = this.queryZoom(Math.max(SOURCE_RESOLUTION_METERS, projection.worldScale()), lookupMode);
      TilePosition center = tilePosition(lon, lat, zoom);
      if (center == null) {
         return fallbackCoverClass;
      }

      RasterTile centerTile = this.getTile(center.key(), lookupMode);
      if (centerTile == null) {
         return lookupMode == LookupMode.MEMORY_ONLY ? Integer.MIN_VALUE : fallbackCoverClass;
      }

      NearestLandKey cacheKey = new NearestLandKey(zoom, center.globalPixelX(), center.globalPixelY());
      Integer cached = this.nearestLandCache.getIfPresent(cacheKey);
      if (cached != null) {
         return cached == NEAREST_LAND_NOT_FOUND ? fallbackCoverClass : cached;
      }

      Map<TileKey, RasterTile> searchTiles = new HashMap<>();
      searchTiles.put(center.key(), centerTile);
      Set<TileKey> unavailableTiles = new HashSet<>();
      NearestLandSearch nearest = findNearestLandCoverClassWithCoverage(
         center.globalPixelX(),
         center.globalPixelY(),
         NEAREST_LAND_RADIUS_PIXELS,
         (pixelX, pixelY) -> this.sampleGrid(
            zoom, pixelX, pixelY, lookupMode, searchTiles, unavailableTiles
         ),
         (pixelX, pixelY) -> isGlobalPixelInBounds(zoom, pixelX, pixelY)
      );
      if (nearest.coverClass() != Integer.MIN_VALUE) {
         if (nearest.complete()) {
            this.nearestLandCache.put(cacheKey, nearest.coverClass());
         }
         return nearest.coverClass();
      }

      if (nearest.complete()) {
         this.nearestLandCache.put(cacheKey, NEAREST_LAND_NOT_FOUND);
      }
      return lookupMode == LookupMode.MEMORY_ONLY ? Integer.MIN_VALUE : fallbackCoverClass;
   }

   static int findNearestLandCoverClass(int centerX, int centerY, int maxRadius, IntBinaryOperator sampler) {
      int bestClass = Integer.MIN_VALUE;
      int bestDistanceSquared = Integer.MAX_VALUE;
      int boundedRadius = Math.max(0, maxRadius);

      for (int radius = 1; radius <= boundedRadius; radius++) {
         int minX = centerX - radius;
         int maxX = centerX + radius;
         int minY = centerY - radius;
         int maxY = centerY + radius;

         for (int x = minX; x <= maxX; x++) {
            int topClass = sampler.applyAsInt(x, minY);
            int topDistanceSquared = squaredDistance(centerX, centerY, x, minY);
            if (isLandCoverClass(topClass) && topDistanceSquared < bestDistanceSquared) {
               bestClass = topClass;
               bestDistanceSquared = topDistanceSquared;
            }

            int bottomClass = sampler.applyAsInt(x, maxY);
            int bottomDistanceSquared = squaredDistance(centerX, centerY, x, maxY);
            if (isLandCoverClass(bottomClass) && bottomDistanceSquared < bestDistanceSquared) {
               bestClass = bottomClass;
               bestDistanceSquared = bottomDistanceSquared;
            }
         }

         for (int y = minY + 1; y < maxY; y++) {
            int leftClass = sampler.applyAsInt(minX, y);
            int leftDistanceSquared = squaredDistance(centerX, centerY, minX, y);
            if (isLandCoverClass(leftClass) && leftDistanceSquared < bestDistanceSquared) {
               bestClass = leftClass;
               bestDistanceSquared = leftDistanceSquared;
            }

            int rightClass = sampler.applyAsInt(maxX, y);
            int rightDistanceSquared = squaredDistance(centerX, centerY, maxX, y);
            if (isLandCoverClass(rightClass) && rightDistanceSquared < bestDistanceSquared) {
               bestClass = rightClass;
               bestDistanceSquared = rightDistanceSquared;
            }
         }

         int nextRadius = radius + 1;
         if (bestClass != Integer.MIN_VALUE && bestDistanceSquared < nextRadius * nextRadius) {
            break;
         }
      }

      return bestClass;
   }

   static NearestLandSearch findNearestLandCoverClassWithCoverage(
      int centerX,
      int centerY,
      int maxRadius,
      IntBinaryOperator sampler,
      PixelCoverage expectedCoverage
   ) {
      boolean[] complete = new boolean[]{true};
      int nearest = findNearestLandCoverClass(centerX, centerY, maxRadius, (pixelX, pixelY) -> {
         int coverClass = sampler.applyAsInt(pixelX, pixelY);
         if (coverClass == Integer.MIN_VALUE && expectedCoverage.contains(pixelX, pixelY)) {
            complete[0] = false;
         }
         return coverClass;
      });
      return new NearestLandSearch(nearest, complete[0]);
   }

   private static boolean isLandCoverClass(int coverClass) {
      return coverClass != Integer.MIN_VALUE
         && coverClass != NO_DATA_CLASS
         && coverClass != WATER_CLASS
         && coverClass != MANGROVES_CLASS;
   }

   private static int squaredDistance(int centerX, int centerY, int x, int y) {
      int dx = x - centerX;
      int dy = y - centerY;
      return dx * dx + dy * dy;
   }

   public void prefetchTiles(double blockX, double blockZ, WorldProjection projection, int radius) {
      this.prefetchTiles(blockX, blockZ, projection, radius, projection.worldScale());
   }

   public void prefetchTiles(double blockX, double blockZ, WorldProjection projection, int radius, double previewResolutionMeters) {
      if (!isWithinTileCoverage(blockX, blockZ, projection)) {
         return;
      }

      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
      double resolutionMeters = effectiveSampleResolutionMeters(projection.worldScale(), previewResolutionMeters);
      if (this.worldCoverSource.prefetch(lon, lat, resolutionMeters, radius)) {
         return;
      }

      int zoom = this.queryZoom(resolutionMeters, LookupMode.BLOCKING);
      TilePosition center = tilePosition(lon, lat, zoom);
      if (center == null) {
         return;
      }

      int clampedRadius = Math.max(0, radius);
      int tilesPerAxis = 1 << zoom;
      int minX = (int)Math.max(0L, (long)center.key().x() - clampedRadius);
      int maxX = (int)Math.min(tilesPerAxis - 1L, (long)center.key().x() + clampedRadius);
      int minY = (int)Math.max(0L, (long)center.key().y() - clampedRadius);
      int maxY = (int)Math.min(tilesPerAxis - 1L, (long)center.key().y() + clampedRadius);
      for (int tileY = minY; tileY <= maxY; tileY++) {
         for (int tileX = minX; tileX <= maxX; tileX++) {
            this.prefetchTile(new TileKey(zoom, tileX, tileY));
         }
      }
   }

   public int preloadAreaTaskCount(
      double minBlockX, double minBlockZ, double maxBlockX, double maxBlockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      List<WorldCoverCogSource.BlockKey> worldCoverBlocks = this.worldCoverSource.areaBlockKeys(
         minBlockX,
         minBlockZ,
         maxBlockX,
         maxBlockZ,
         projection,
         effectiveSampleResolutionMeters(projection.worldScale(), previewResolutionMeters)
      );
      if (!worldCoverBlocks.isEmpty()) {
         int taskCount = worldCoverBlocks.size();
         if (!this.worldCoverSource.fullyCoversArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection)) {
            taskCount += this.areaTileKeys(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, previewResolutionMeters).size();
         }
         return taskCount;
      }
      return Math.max(1, this.areaTileKeys(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, previewResolutionMeters).size());
   }

   public int preloadAreaInputs(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      double previewResolutionMeters,
      int completedUnits,
      BiConsumer<Integer, String> progressConsumer
   ) {
      return this.preloadAreaInputs(
         minBlockX,
         minBlockZ,
         maxBlockX,
         maxBlockZ,
         projection,
         previewResolutionMeters,
         completedUnits,
         progressConsumer,
         false
      );
   }

   public int preloadAreaInputsIntoMemory(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      double previewResolutionMeters,
      int completedUnits,
      BiConsumer<Integer, String> progressConsumer
   ) {
      return this.preloadAreaInputs(
         minBlockX,
         minBlockZ,
         maxBlockX,
         maxBlockZ,
         projection,
         previewResolutionMeters,
         completedUnits,
         progressConsumer,
         true
      );
   }

   private int preloadAreaInputs(
      double minBlockX,
      double minBlockZ,
      double maxBlockX,
      double maxBlockZ,
      WorldProjection projection,
      double previewResolutionMeters,
      int completedUnits,
      BiConsumer<Integer, String> progressConsumer,
      boolean loadOvertureIntoMemory
   ) {
      BiConsumer<Integer, String> progress = progressConsumer == null ? (completed, detail) -> {
      } : progressConsumer;
      List<WorldCoverCogSource.BlockKey> worldCoverBlocks = this.worldCoverSource.areaBlockKeys(
         minBlockX,
         minBlockZ,
         maxBlockX,
         maxBlockZ,
         projection,
         effectiveSampleResolutionMeters(projection.worldScale(), previewResolutionMeters)
      );
      boolean worldCoverFullyCoversArea = this.worldCoverSource.fullyCoversArea(
         minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection
      );
      if (!worldCoverBlocks.isEmpty()) {
         int worldCoverStartingUnits = completedUnits;
         progress.accept(completedUnits, "Downloading " + worldCoverBlocks.size() + " ESA WorldCover data blocks");
         completedUnits = ParallelDownloadRunner.run(
            ParallelDownloadRunner.scope("land-cover-worldcover-2021-v200", TellusCacheRegistry.generation(TellusCacheDomain.LAND_COVER)),
            worldCoverBlocks,
            completedUnits,
            this.worldCoverSource::downloadBlock,
            (key, completed, phaseTotal) -> progress.accept(
               completed,
               "Cached WorldCover block " + (completed - worldCoverStartingUnits) + "/" + phaseTotal + " (" + key.label() + ")"
            )
         );
         if (worldCoverFullyCoversArea) {
            return completedUnits;
         }
      }

      List<TileKey> keys = this.areaTileKeys(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, previewResolutionMeters);
      if (keys.isEmpty()) {
         progress.accept(completedUnits, "Skipping land cover; selected area is outside source coverage");
         return completedUnits + 1;
      }

      int overtureStartingUnits = completedUnits;
      progress.accept(
         completedUnits,
         (loadOvertureIntoMemory ? "Loading " : "Downloading ") + keys.size() + " Overture land-cover source tiles"
      );
      return ParallelDownloadRunner.run(
         ParallelDownloadRunner.scope(
            loadOvertureIntoMemory ? "land-cover-memory" : "land-cover",
            TellusCacheRegistry.generation(TellusCacheDomain.LAND_COVER)
         ),
         keys,
         completedUnits,
         loadOvertureIntoMemory ? this::loadTileIntoCache : this::downloadRawTile,
         (key, completed, phaseTotal) -> progress.accept(
            completed,
            (loadOvertureIntoMemory ? "Loaded " : "Cached ")
               + "Overture land-cover tile "
               + (completed - overtureStartingUnits)
               + "/"
               + phaseTotal
               + " ("
               + key.label()
               + ")"
         )
      );
   }

   private List<TileKey> areaTileKeys(
      double minBlockX, double minBlockZ, double maxBlockX, double maxBlockZ, WorldProjection projection, double previewResolutionMeters
   ) {
      double worldScale = projection.worldScale();
      if (!(Double.isFinite(worldScale) && worldScale > 0.0)
         || !Double.isFinite(minBlockX)
         || !Double.isFinite(minBlockZ)
         || !Double.isFinite(maxBlockX)
         || !Double.isFinite(maxBlockZ)) {
         return List.of();
      }

      // Source coverage spans every longitude, so in block space it is the full world width around the origin.
      double blocksPerDegree = projection.equatorialBlocksPerDegree();
      double coverageMinX = MIN_LON * blocksPerDegree;
      double coverageMaxX = MAX_LON * blocksPerDegree;
      double coverageZA = projection.latToBlockZ(MIN_LAT);
      double coverageZB = projection.latToBlockZ(MAX_LAT);
      double coverageMinZ = Math.min(coverageZA, coverageZB);
      double coverageMaxZ = Math.max(coverageZA, coverageZB);
      double requestedMinX = Math.min(minBlockX, maxBlockX);
      double requestedMaxX = Math.max(minBlockX, maxBlockX);
      double requestedMinZ = Math.min(minBlockZ, maxBlockZ);
      double requestedMaxZ = Math.max(minBlockZ, maxBlockZ);
      if (requestedMaxX < coverageMinX
         || requestedMinX > coverageMaxX
         || requestedMaxZ < coverageMinZ
         || requestedMinZ > coverageMaxZ) {
         return List.of();
      }

      int zoom = this.queryZoom(effectiveSampleResolutionMeters(projection.worldScale(), previewResolutionMeters), LookupMode.BLOCKING);
      double lonA = projection.blockXToLon(Math.max(coverageMinX, requestedMinX));
      double lonB = projection.blockXToLon(Math.min(coverageMaxX, requestedMaxX));
      double latA = projection.blockZToLat(Math.max(coverageMinZ, requestedMinZ));
      double latB = projection.blockZToLat(Math.min(coverageMaxZ, requestedMaxZ));
      double minLat = Math.max(MIN_LAT, Math.min(latA, latB));
      double maxLat = Math.min(MAX_LAT, Math.max(latA, latB));
      if (minLat > maxLat) {
         return List.of();
      }

      Set<TileKey> keys = new LinkedHashSet<>();
      if (projection.isCentered() && lonB < lonA) {
         // The area crosses the real antimeridian inside a centred world: cover both longitude ranges.
         this.addAreaTileKeys(keys, Math.max(MIN_LON, lonA), MAX_LON, minLat, maxLat, zoom);
         this.addAreaTileKeys(keys, MIN_LON, Math.min(MAX_LON, lonB), minLat, maxLat, zoom);
      } else {
         double minLon = Math.max(MIN_LON, Math.min(lonA, lonB));
         double maxLon = Math.min(MAX_LON, Math.max(lonA, lonB));
         if (minLon > maxLon) {
            return List.of();
         }

         this.addAreaTileKeys(keys, minLon, maxLon, minLat, maxLat, zoom);
      }

      return new ArrayList<>(keys);
   }

   private void addAreaTileKeys(Set<TileKey> keys, double minLon, double maxLon, double minLat, double maxLat, int zoom) {
      TilePosition northWest = tilePosition(minLon, maxLat, zoom);
      TilePosition southEast = tilePosition(maxLon, minLat, zoom);
      if (northWest == null || southEast == null) {
         return;
      }

      for (int tileY = northWest.key().y(); tileY <= southEast.key().y(); tileY++) {
         for (int tileX = northWest.key().x(); tileX <= southEast.key().x(); tileX++) {
            keys.add(new TileKey(zoom, tileX, tileY));
         }
      }
   }

   private void downloadRawTile(TileKey key) {
      Path rawPath = this.rawCachePath(key);
      if (Files.isRegularFile(rawPath)) {
         return;
      }

      this.ensureInitialized();
      if (!this.available) {
         throw new IllegalStateException("Overture base PMTiles is unavailable");
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.LAND_COVER);
      try {
         byte[] payload = this.fetchTilePayloadWithRetry(key);
         if (!this.cacheRawTile(rawPath, payload, generation)) {
            throw new IOException("Discarded stale Overture land-cover cache write for " + key);
         }
      } catch (IOException error) {
         throw new RuntimeException("Failed to download Overture land-cover tile " + key, error);
      }
   }

   private void loadTileIntoCache(TileKey key) {
      long generation = TellusCacheRegistry.generation(TellusCacheDomain.LAND_COVER);
      try {
         this.cache.get(key);
      } catch (Exception error) {
         if (isInterruptedLoad(error)) {
            Thread.currentThread().interrupt();
         }
         throw new RuntimeException("Failed to preload Overture land-cover tile " + key, error);
      }
      if (Thread.currentThread().isInterrupted()) {
         throw new java.util.concurrent.CancellationException("Interrupted while preloading Overture land-cover tile " + key);
      }
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.LAND_COVER, generation)) {
         this.cache.invalidate(key);
         throw new IllegalStateException("Discarded stale Overture land-cover preload for " + key);
      }
   }

   private int sampleAtLonLat(double lon, double lat, int zoom, LookupMode lookupMode) {
      TilePosition position = tilePosition(lon, lat, zoom);
      if (position == null) {
         return NO_DATA_CLASS;
      }

      RasterTile tile = this.getTile(position.key(), lookupMode);
      if (tile == null) {
         return lookupMode == LookupMode.MEMORY_ONLY ? Integer.MIN_VALUE : NO_DATA_CLASS;
      }
      return tile.sample(position.pixelX(), position.pixelY());
   }

   private int sampleVisualAtLonLat(
      double lon,
      double lat,
      int zoom,
      double effectiveResolutionMeters,
      double blockX,
      double blockZ,
      double worldScale,
      LookupMode lookupMode
   ) {
      TilePosition center = tilePosition(lon, lat, zoom);
      if (center == null) {
         return NO_DATA_CLASS;
      }

      int centerClass = this.sampleGrid(zoom, center.globalPixelX(), center.globalPixelY(), lookupMode);
      if (centerClass == Integer.MIN_VALUE) {
         return lookupMode == LookupMode.MEMORY_ONLY ? Integer.MIN_VALUE : NO_DATA_CLASS;
      }

      double sourceResolutionMeters = WEB_MERCATOR_CIRCUMFERENCE_METERS / ((1L << zoom) * (double)RASTER_SIZE);
      double transitionStrength = LandCoverTransition.strength(sourceResolutionMeters, effectiveResolutionMeters);
      if (!(transitionStrength > 0.0) || LandCoverTransition.isHardClass(centerClass)) {
         return centerClass;
      }

      double blendX = center.globalPixelCoordinateX() - 0.5;
      double blendY = center.globalPixelCoordinateY() - 0.5;
      int x0 = (int)Math.floor(blendX);
      int y0 = (int)Math.floor(blendY);
      int x1 = x0 + 1;
      int y1 = y0 + 1;
      int value00 = visualSampleOrCenter(zoom, x0, y0, lookupMode, centerClass);
      int value10 = visualSampleOrCenter(zoom, x1, y0, lookupMode, centerClass);
      int value01 = visualSampleOrCenter(zoom, x0, y1, lookupMode, centerClass);
      int value11 = visualSampleOrCenter(zoom, x1, y1, lookupMode, centerClass);
      return LandCoverTransition.selectVisualClass(
         centerClass,
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
   }

   private int visualSampleOrCenter(
      int zoom, int globalPixelX, int globalPixelY, LookupMode lookupMode, int centerClass
   ) {
      int sampled = this.sampleGrid(zoom, globalPixelX, globalPixelY, lookupMode);
      return sampled == Integer.MIN_VALUE ? centerClass : sampled;
   }

   private int sampleGrid(int zoom, int globalPixelX, int globalPixelY, LookupMode lookupMode) {
      return this.sampleGrid(zoom, globalPixelX, globalPixelY, lookupMode, null, null);
   }

   private int sampleGrid(
      int zoom,
      int globalPixelX,
      int globalPixelY,
      LookupMode lookupMode,
      Map<TileKey, RasterTile> searchTiles,
      Set<TileKey> unavailableTiles
   ) {
      int tilesPerAxis = 1 << zoom;
      int tileX = Math.floorDiv(globalPixelX, RASTER_SIZE);
      int tileY = Math.floorDiv(globalPixelY, RASTER_SIZE);
      if (tileX < 0 || tileY < 0 || tileX >= tilesPerAxis || tileY >= tilesPerAxis) {
         return Integer.MIN_VALUE;
      }

      TileKey key = new TileKey(zoom, tileX, tileY);
      RasterTile tile = searchTiles == null ? null : searchTiles.get(key);
      if (tile == null && unavailableTiles != null && unavailableTiles.contains(key)) {
         return Integer.MIN_VALUE;
      }
      if (tile == null) {
         tile = this.getTile(key, lookupMode);
         if (tile != null && searchTiles != null) {
            searchTiles.put(key, tile);
         } else if (tile == null && unavailableTiles != null) {
            unavailableTiles.add(key);
         }
      }
      if (tile == null) {
         return Integer.MIN_VALUE;
      }
      return tile.sample(Math.floorMod(globalPixelX, RASTER_SIZE), Math.floorMod(globalPixelY, RASTER_SIZE));
   }

   private RasterTile getTile(TileKey key, LookupMode lookupMode) {
      RasterTile cached = this.cache.getIfPresent(key);
      if (cached != null) {
         return cached;
      }

      if (lookupMode == LookupMode.MEMORY_ONLY) {
         return null;
      }
      if (lookupMode == LookupMode.LOCAL_ONLY) {
         return this.loadTileLocalOnly(key);
      }

      RasterTile local = this.loadTileLocalOnly(key);
      if (local != null) {
         return local;
      }
      this.ensureInitialized();
      if (!this.available) {
         return null;
      }

      try {
         return this.cache.get(key);
      } catch (Exception error) {
         if (isInterruptedLoad(error)) {
            Thread.currentThread().interrupt();
         } else {
            Tellus.LOGGER.warn("Failed to load Overture land-cover tile {}", key, error);
         }
         return null;
      }
   }

   private RasterTile loadTileLocalOnly(TileKey key) {
      RasterTile cached = this.cache.getIfPresent(key);
      if (cached != null) {
         return cached;
      }

      RasterTile local = this.readLocalTile(key);
      if (local == null) {
         return null;
      }
      RasterTile raced = this.cache.asMap().putIfAbsent(key, local);
      return raced == null ? local : raced;
   }

   private RasterTile loadTileBlocking(TileKey key) throws IOException {
      RasterTile local = this.readLocalTile(key);
      if (local != null) {
         return local;
      }

      this.ensureInitialized();
      if (!this.available) {
         throw new IOException("Overture base PMTiles is unavailable");
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.LAND_COVER);
      byte[] payload = this.fetchTilePayloadWithRetry(key);
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.LAND_COVER, generation)) {
         throw new IOException("Discarded stale Overture land-cover tile " + key);
      }

      RasterTile tile = new RasterTile(rasterizeVectorTile(payload, RASTER_SIZE));
      if (!this.cacheRawTile(this.rawCachePath(key), payload, generation)) {
         throw new IOException("Discarded stale Overture land-cover cache write for " + key);
      }
      this.cacheRasterTile(this.rasterCachePath(key), tile, generation);
      return tile;
   }

   private RasterTile readLocalTile(TileKey key) {
      Path rasterPath = this.rasterCachePath(key);
      if (Files.exists(rasterPath)) {
         try {
            return readRasterTile(rasterPath);
         } catch (IOException error) {
            Tellus.LOGGER.debug("Invalid cached Overture land-cover raster {}, rebuilding", rasterPath, error);
            deleteQuietly(rasterPath);
         }
      }

      Path rawPath = this.rawCachePath(key);
      if (!Files.exists(rawPath)) {
         return null;
      }
      try {
         byte[] payload = readGzipBytes(rawPath);
         RasterTile tile = new RasterTile(rasterizeVectorTile(payload, RASTER_SIZE));
         this.cacheRasterTile(rasterPath, tile, TellusCacheRegistry.generation(TellusCacheDomain.LAND_COVER));
         return tile;
      } catch (RuntimeException | IOException error) {
         Tellus.LOGGER.debug("Invalid cached Overture land-cover MVT {}, refetching", rawPath, error);
         deleteQuietly(rawPath);
         return null;
      }
   }

   private byte[] fetchTilePayloadWithRetry(TileKey key) throws IOException {
      IOException lastFailure = null;
      for (int attempt = 1; attempt <= FETCH_RETRY_ATTEMPTS; attempt++) {
         try {
            byte[] payload = this.pmTilesReader.getTileBytes(key.zoom(), key.x(), key.y());
            return payload == null ? new byte[0] : payload;
         } catch (IOException error) {
            if (isInterruptedLoad(error)) {
               Thread.currentThread().interrupt();
               throw error;
            }
            lastFailure = error;
         }
      }
      throw new IOException("Overture land-cover fetch failed for tile " + key, lastFailure);
   }

   private boolean cacheRawTile(Path cachePath, byte[] payload, long generation) {
      try {
         return TellusCacheFiles.writeIfCurrent(TellusCacheDomain.LAND_COVER, generation, cachePath, output -> {
            try (OutputStream gzip = new GZIPOutputStream(output)) {
               gzip.write(payload);
            }
         });
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache Overture land-cover MVT {}", cachePath, error);
         return false;
      }
   }

   private void cacheRasterTile(Path cachePath, RasterTile tile, long generation) {
      try {
         TellusCacheFiles.writeIfCurrent(TellusCacheDomain.LAND_COVER, generation, cachePath, output -> {
            try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(output))) {
               data.writeInt(RASTER_CACHE_MAGIC);
               data.writeInt(RASTER_CACHE_VERSION);
               data.writeInt(RASTER_SIZE);
               data.writeInt(tile.values.length);
               data.write(tile.values);
            }
         });
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache parsed Overture land-cover raster {}", cachePath, error);
      }
   }

   private static RasterTile readRasterTile(Path path) throws IOException {
      int expectedLength = RASTER_SIZE * RASTER_SIZE;
      byte[] decoded;
      try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
         decoded = PmTilesSafety.readBounded(
            input, 16 + expectedLength, "Cached Overture land-cover raster"
         );
      }
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(decoded))) {
         if (input.readInt() != RASTER_CACHE_MAGIC || input.readInt() != RASTER_CACHE_VERSION) {
            throw new IOException("Unsupported land-cover raster cache format");
         }
         int rasterSize = input.readInt();
         int length = input.readInt();
         if (rasterSize != RASTER_SIZE || length != expectedLength) {
            throw new IOException("Land-cover raster cache dimensions do not match configuration");
         }
         byte[] values = input.readNBytes(length);
         if (values.length != length) {
            throw new EOFException("Truncated land-cover raster cache");
         }
         if (input.read() != -1) {
            throw new IOException("Land-cover raster cache contains trailing data");
         }
         return new RasterTile(values);
      }
   }

   static byte[] rasterizeVectorTile(byte[] payload, int rasterSize) {
      byte[] values = new byte[rasterSize * rasterSize];
      if (payload == null || payload.length == 0) {
         return values;
      }

      Tile tile;
      try {
         tile = Tile.parseFrom(payload);
      } catch (Exception error) {
         throw new IllegalArgumentException("Failed to decode Overture base MVT payload", error);
      }

      List<RasterFeature> landCoverFeatures = new ArrayList<>();
      for (Layer layer : tile.getLayersList()) {
         if (!LAND_COVER_LAYER_NAME.equals(layer.getName())) {
            continue;
         }

         int extent = layer.hasExtent() && layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_TILE_EXTENT;
         for (Feature feature : layer.getFeaturesList()) {
            if (feature.getType() != GeomType.POLYGON) {
               continue;
            }

            int coverClass = coverClassForSubtype(tagString(feature, layer, "subtype"));
            if (coverClass < 0) {
               continue;
            }
            List<Ring> rings = decodePolygonRings(feature.getGeometryList(), (double)rasterSize / extent);
            if (rings.isEmpty()) {
               continue;
            }
            int sortKey = parseSortKey(tagString(feature, layer, "cartography"));
            landCoverFeatures.add(new RasterFeature(coverClass, sortKey, rings));
         }
      }

      // Overture assigns lower sort keys to features that should be drawn in
      // front. Rasterize the background first so lower-key features win where
      // polygons overlap.
      landCoverFeatures.sort(Comparator.comparingInt(RasterFeature::sortKey).reversed());
      for (RasterFeature feature : landCoverFeatures) {
         rasterizeFeature(values, rasterSize, feature);
      }
      return values;
   }

   private static void rasterizeFeature(byte[] values, int rasterSize, RasterFeature feature) {
      int minY = rasterSize - 1;
      int maxY = 0;
      boolean hasPoints = false;
      for (Ring ring : feature.rings()) {
         for (double y : ring.ys()) {
            minY = Math.min(minY, Math.max(0, (int)Math.floor(y)));
            maxY = Math.max(maxY, Math.min(rasterSize - 1, (int)Math.floor(y)));
            hasPoints = true;
         }
      }
      if (!hasPoints || minY > maxY) {
         return;
      }

      double[] intersections = new double[32];
      byte encodedClass = (byte)feature.coverClass();
      for (int pixelY = minY; pixelY <= maxY; pixelY++) {
         double scanY = pixelY + 0.5;
         int intersectionCount = 0;
         for (Ring ring : feature.rings()) {
            double[] xs = ring.xs();
            double[] ys = ring.ys();
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
               double yA = ys[i];
               double yB = ys[j];
               if ((yA > scanY) != (yB > scanY)) {
                  if (intersectionCount == intersections.length) {
                     intersections = Arrays.copyOf(intersections, intersections.length * 2);
                  }
                  intersections[intersectionCount++] = xs[i] + (scanY - yA) * (xs[j] - xs[i]) / (yB - yA);
               }
            }
         }
         if (intersectionCount < 2) {
            continue;
         }

         Arrays.sort(intersections, 0, intersectionCount);
         int rowOffset = pixelY * rasterSize;
         for (int i = 0; i + 1 < intersectionCount; i += 2) {
            double left = Math.min(intersections[i], intersections[i + 1]);
            double right = Math.max(intersections[i], intersections[i + 1]);
            int startX = Math.max(0, (int)Math.ceil(left - 0.5));
            int endX = Math.min(rasterSize - 1, (int)Math.floor(right - 0.5));
            if (startX <= endX) {
               Arrays.fill(values, rowOffset + startX, rowOffset + endX + 1, encodedClass);
            }
         }
      }
   }

   private static List<Ring> decodePolygonRings(List<Integer> geometry, double scale) {
      if (geometry == null || geometry.isEmpty()) {
         return List.of();
      }

      List<Ring> rings = new ArrayList<>();
      List<Double> currentX = null;
      List<Double> currentY = null;
      int cursorX = 0;
      int cursorY = 0;
      int cursor = 0;
      while (cursor < geometry.size()) {
         int commandAndCount = geometry.get(cursor++);
         int command = commandAndCount & 7;
         int count = commandAndCount >>> 3;
         if (count <= 0) {
            continue;
         }

         if (command == 1 || command == 2) {
            for (int i = 0; i < count; i++) {
               if (cursor + 1 >= geometry.size()) {
                  addRing(rings, currentX, currentY);
                  return rings;
               }
               cursorX += zigZagDecode(geometry.get(cursor++));
               cursorY += zigZagDecode(geometry.get(cursor++));
               if (command == 1) {
                  addRing(rings, currentX, currentY);
                  currentX = new ArrayList<>();
                  currentY = new ArrayList<>();
               } else if (currentX == null) {
                  currentX = new ArrayList<>();
                  currentY = new ArrayList<>();
               }
               currentX.add(cursorX * scale);
               currentY.add(cursorY * scale);
            }
         } else if (command == 7) {
            addRing(rings, currentX, currentY);
            currentX = null;
            currentY = null;
         } else {
            break;
         }
      }
      addRing(rings, currentX, currentY);
      return rings;
   }

   private static void addRing(List<Ring> rings, List<Double> xs, List<Double> ys) {
      if (xs == null || ys == null || xs.size() != ys.size() || xs.size() < 3) {
         return;
      }

      double[] ringX = new double[xs.size()];
      double[] ringY = new double[ys.size()];
      for (int i = 0; i < xs.size(); i++) {
         ringX[i] = xs.get(i);
         ringY[i] = ys.get(i);
      }
      rings.add(new Ring(ringX, ringY));
   }

   private static int zigZagDecode(int encoded) {
      return encoded >>> 1 ^ -(encoded & 1);
   }

   static int coverClassForSubtype(String subtype) {
      if (subtype == null || subtype.isBlank()) {
         return -1;
      }
      return switch (subtype.trim().toLowerCase(Locale.ROOT)) {
         case "forest" -> TREE_COVER_CLASS;
         case "shrub" -> SHRUBLAND_CLASS;
         case "grass" -> GRASSLAND_CLASS;
         case "crop" -> CROPLAND_CLASS;
         case "urban" -> BUILT_UP_CLASS;
         case "barren" -> BARE_CLASS;
         case "snow" -> SNOW_ICE_CLASS;
         case "wetland" -> WETLAND_CLASS;
         case "mangrove" -> MANGROVES_CLASS;
         case "moss" -> MOSS_LICHEN_CLASS;
         default -> -1;
      };
   }

   private static String tagString(Feature feature, Layer layer, String requestedKey) {
      List<Integer> tags = feature.getTagsList();
      for (int i = 0; i + 1 < tags.size(); i += 2) {
         int keyIndex = tags.get(i);
         int valueIndex = tags.get(i + 1);
         if (keyIndex >= 0
            && keyIndex < layer.getKeysCount()
            && valueIndex >= 0
            && valueIndex < layer.getValuesCount()
            && requestedKey.equals(layer.getKeys(keyIndex))) {
            Value value = layer.getValues(valueIndex);
            return value.hasStringValue() ? value.getStringValue() : String.valueOf(decodeValue(value));
         }
      }
      return null;
   }

   private static Object decodeValue(Value value) {
      if (value.hasDoubleValue()) {
         return value.getDoubleValue();
      } else if (value.hasFloatValue()) {
         return value.getFloatValue();
      } else if (value.hasSintValue()) {
         return value.getSintValue();
      } else if (value.hasIntValue()) {
         return value.getIntValue();
      } else if (value.hasUintValue()) {
         return value.getUintValue();
      } else if (value.hasBoolValue()) {
         return value.getBoolValue();
      }
      return "";
   }

   private static int parseSortKey(String cartography) {
      if (cartography == null) {
         return 0;
      }
      Matcher matcher = SORT_KEY_PATTERN.matcher(cartography);
      if (!matcher.find()) {
         return 0;
      }
      try {
         return Integer.parseInt(matcher.group(1));
      } catch (NumberFormatException ignored) {
         return 0;
      }
   }

   private int queryZoom(double resolutionMeters, LookupMode lookupMode) {
      if (lookupMode == LookupMode.BLOCKING) {
         this.ensureInitialized();
      }
      return selectZoom(resolutionMeters, this.minZoom, this.maxZoom, RASTER_SIZE);
   }

   static int selectZoom(double effectiveResolutionMeters, int minZoom, int maxZoom, int rasterSize) {
      int low = Math.max(0, Math.min(minZoom, maxZoom));
      int high = Math.max(low, maxZoom);
      double resolution = Double.isFinite(effectiveResolutionMeters) && effectiveResolutionMeters > 0.0
         ? Math.max(SOURCE_RESOLUTION_METERS, effectiveResolutionMeters)
         : SOURCE_RESOLUTION_METERS;
      double ratio = WEB_MERCATOR_CIRCUMFERENCE_METERS / (Math.max(1, rasterSize) * resolution);
      int desired = ratio > 1.0 ? (int)Math.ceil(Math.log(ratio) / Math.log(2.0)) : 0;
      return Math.max(low, Math.min(high, desired));
   }

   private void ensureInitialized() {
      if (this.initialized) {
         return;
      }
      long now = System.nanoTime();
      if (!retryDeadlineReached(now, this.nextInitializationAttemptNanos)) {
         return;
      }
      synchronized (this.initLock) {
         if (this.initialized || !retryDeadlineReached(System.nanoTime(), this.nextInitializationAttemptNanos)) {
            return;
         }

         boolean sourceAvailable = false;
         try {
            PmTilesRangeReader.PmTilesHeader header = this.pmTilesReader.header();
            if (header.tileType() != PMTILES_TILETYPE_MVT) {
               throw new IOException("Unexpected Overture base PMTiles tile type " + header.tileType());
            }
            int resolvedMin = Math.max(DEFAULT_MIN_LAND_COVER_ZOOM, header.minZoom());
            int resolvedMax = header.maxZoom();
            if (resolvedMax < resolvedMin) {
               throw new IOException("Overture base PMTiles has no supported land-cover zooms");
            }
            this.minZoom = resolvedMin;
            this.maxZoom = resolvedMax;
            sourceAvailable = true;
            this.initializationFailures = 0;
            this.nextInitializationAttemptNanos = 0L;
         } catch (IOException error) {
            if (isInterruptedLoad(error)) {
               Thread.currentThread().interrupt();
               this.nextInitializationAttemptNanos = 0L;
               Tellus.LOGGER.debug("Overture land-cover PMTiles initialization interrupted", error);
            } else {
               int failures = ++this.initializationFailures;
               long delaySeconds = retryDelaySeconds(failures);
               this.nextInitializationAttemptNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(delaySeconds);
               Tellus.LOGGER.warn("Overture base PMTiles unavailable; retrying land cover in {}s", delaySeconds, error);
            }
         }
         this.available = sourceAvailable;
         this.initialized = sourceAvailable;
      }
   }

   static long retryDelaySeconds(int failureCount) {
      if (failureCount <= 0) {
         return 0L;
      }
      return failureCount <= 5 ? 1L << failureCount - 1 : 60L;
   }

   private static boolean retryDeadlineReached(long now, long deadline) {
      return deadline == 0L || now - deadline >= 0L;
   }

   private void prefetchTile(TileKey key) {
      if (this.cache.getIfPresent(key) != null) {
         return;
      }
      try {
         this.cache.get(key);
      } catch (Exception error) {
         if (isInterruptedLoad(error)) {
            Thread.currentThread().interrupt();
         } else {
            Tellus.LOGGER.debug("Failed to prefetch Overture land-cover tile {}", key, error);
         }
      }
   }

   private Path rawCachePath(TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".mvt.gz");
   }

   private Path rasterCachePath(TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".raster-v" + RASTER_CACHE_VERSION + ".gz");
   }

   private static TilePosition tilePosition(double lon, double lat, int zoom) {
      if (!Double.isFinite(lon) || !Double.isFinite(lat) || lon < MIN_LON || lon > MAX_LON || lat < MIN_LAT || lat > MAX_LAT) {
         return null;
      }
      double safeLon = lon == MAX_LON ? Math.nextDown(MAX_LON) : lon;
      double safeLat = Math.max(MIN_LAT, Math.min(MAX_LAT, lat));
      int tilesPerAxis = 1 << zoom;
      double tileXValue = (safeLon + 180.0) / 360.0 * tilesPerAxis;
      double radians = Math.toRadians(safeLat);
      double mercator = Math.log(Math.tan(radians) + 1.0 / Math.cos(radians));
      double tileYValue = (1.0 - mercator / Math.PI) * 0.5 * tilesPerAxis;
      tileXValue = Math.max(0.0, Math.min(Math.nextDown((double)tilesPerAxis), tileXValue));
      tileYValue = Math.max(0.0, Math.min(Math.nextDown((double)tilesPerAxis), tileYValue));
      int tileX = (int)Math.floor(tileXValue);
      int tileY = (int)Math.floor(tileYValue);
      double globalPixelCoordinateX = tileXValue * RASTER_SIZE;
      double globalPixelCoordinateY = tileYValue * RASTER_SIZE;
      int pixelX = Math.min(RASTER_SIZE - 1, (int)Math.floor((tileXValue - tileX) * RASTER_SIZE));
      int pixelY = Math.min(RASTER_SIZE - 1, (int)Math.floor((tileYValue - tileY) * RASTER_SIZE));
      return new TilePosition(
         new TileKey(zoom, tileX, tileY),
         pixelX,
         pixelY,
         globalPixelCoordinateX,
         globalPixelCoordinateY
      );
   }

   private static double effectiveSampleResolutionMeters(double worldScale, double previewResolutionMeters) {
      return Double.isFinite(previewResolutionMeters) && previewResolutionMeters > 0.0
         ? Math.max(worldScale, previewResolutionMeters)
         : worldScale;
   }

   private static byte[] readGzipBytes(Path path) throws IOException {
      try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
         return PmTilesSafety.readBounded(
            input, PmTilesSafety.MAX_DECOMPRESSED_TILE_BYTES, "Cached Overture land-cover tile"
         );
      }
   }

   private static void deleteQuietly(Path path) {
      try {
         Files.deleteIfExists(path);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to delete invalid land-cover cache file {}", path, error);
      }
   }

   static boolean isInterruptedLoad(Throwable throwable) {
      if (Thread.currentThread().isInterrupted()) {
         return true;
      }
      for (Throwable current = throwable; current != null; current = current.getCause()) {
         if (current instanceof ClosedByInterruptException
            || current instanceof InterruptedException
            || current instanceof InterruptedIOException && !(current instanceof SocketTimeoutException)) {
            return true;
         }
      }
      return false;
   }

   static boolean isWithinTileCoverage(double blockX, double blockZ, WorldProjection projection) {
      double worldScale = projection.worldScale();
      if (!(Double.isFinite(worldScale) && worldScale > 0.0)
         || !Double.isFinite(blockX)
         || !Double.isFinite(blockZ)) {
         return false;
      }

      // Longitude coverage is global, so only the block X must lie inside the world width around the origin.
      double relativeLon = projection.blockXToRelativeLongitude(blockX);
      double zA = projection.latToBlockZ(MIN_LAT);
      double zB = projection.latToBlockZ(MAX_LAT);
      return relativeLon >= MIN_LON && relativeLon <= MAX_LON && blockZ >= Math.min(zA, zB) && blockZ <= Math.max(zA, zB);
   }

   private static boolean isGlobalPixelInBounds(int zoom, int globalPixelX, int globalPixelY) {
      long pixelsPerAxis = (1L << zoom) * RASTER_SIZE;
      return globalPixelX >= 0 && globalPixelY >= 0 && globalPixelX < pixelsPerAxis && globalPixelY < pixelsPerAxis;
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return defaultValue;
      }
      try {
         return Math.max(minInclusive, Math.min(maxInclusive, Integer.parseInt(value.trim())));
      } catch (NumberFormatException error) {
         Tellus.LOGGER.warn("Invalid integer system property {}={}, using {}", key, value, defaultValue);
         return defaultValue;
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.LAND_COVER;
   }

   @Override
   public void clearCache() {
      this.worldCoverSource.clearMemoryCache();
      this.cache.invalidateAll();
      this.cache.cleanUp();
      this.nearestLandCache.invalidateAll();
      this.nearestLandCache.cleanUp();
   }

   private enum LookupMode {
      BLOCKING,
      LOCAL_ONLY,
      MEMORY_ONLY
   }

   private static final class RasterTile {
      private final byte[] values;

      private RasterTile(byte[] values) {
         if (values.length != RASTER_SIZE * RASTER_SIZE) {
            throw new IllegalArgumentException("Unexpected land-cover raster size " + values.length);
         }
         this.values = values;
      }

      private int sample(int pixelX, int pixelY) {
         if (pixelX < 0 || pixelY < 0 || pixelX >= RASTER_SIZE || pixelY >= RASTER_SIZE) {
            return Integer.MIN_VALUE;
         }
         return this.values[pixelY * RASTER_SIZE + pixelX] & 255;
      }
   }

   private record TileKey(int zoom, int x, int y) {
      private String label() {
         return this.zoom + "/" + this.x + "/" + this.y;
      }
   }

   private record TilePosition(
      TileKey key,
      int pixelX,
      int pixelY,
      double globalPixelCoordinateX,
      double globalPixelCoordinateY
   ) {
      private int globalPixelX() {
         return this.key.x() * RASTER_SIZE + this.pixelX;
      }

      private int globalPixelY() {
         return this.key.y() * RASTER_SIZE + this.pixelY;
      }
   }

   private record NearestLandKey(int zoom, int globalPixelX, int globalPixelY) {
   }

   record NearestLandSearch(int coverClass, boolean complete) {
   }

   @FunctionalInterface
   interface PixelCoverage {
      boolean contains(int pixelX, int pixelY);
   }

   private record RasterFeature(int coverClass, int sortKey, List<Ring> rings) {
   }

   private record Ring(double[] xs, double[] ys) {
   }
}
