package com.yucareux.tellus.world.data.osm;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.pmtiles.PmTilesSafety;
import com.yucareux.tellus.world.data.source.ParallelDownloadRunner;
import com.yucareux.tellus.worldgen.WorldProjection;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Feature;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.GeomType;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Layer;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Value;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.platform.TellusPlatform;
import net.minecraft.util.Mth;

public final class TellusOsmRoadSource implements TellusCacheHandle {
   private static final String PM_TILES_THEME = "transportation";
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double METERS_PER_DEGREE = 111319.49166666667;
   private static final double POINT_EPSILON = 1.0E-9;
   private static final int DEFAULT_TILE_EXTENT = 4096;
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.overture.roads.connectTimeoutMs", 30000, 1, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.overture.roads.readTimeoutMs", 60000, 1, 180000);
   private static final int DIRECTORY_CACHE_ENTRIES = intProperty("tellus.overture.roads.dirCache", 256, 1, 8192);
   private static final int MAX_CACHE_TILES = intProperty("tellus.osm.roads.cacheTiles", 256, 1, 8192);
   private static final int QUERY_ZOOM = intProperty("tellus.osm.roads.queryZoom", 14, 0, 20);
   private static final int MAX_ASYNC_PREFETCH_LOADS = intProperty("tellus.osm.roads.prefetchAsyncMax", 96, 0, 8192);
   private static final int MAX_BRIDGE_LEVEL = intProperty("tellus.overture.roads.maxBridgeLevel", 3, 1, 16);
   private static final int FETCH_RETRY_ATTEMPTS = intProperty("tellus.overture.roads.fetchRetries", 3, 1, 8);
   private static final int PMTILES_TILETYPE_MVT = 1;
   private static final String SEGMENT_LAYER_NAME = "segment";
   private static final byte[] EMPTY_TILE_PAYLOAD = new byte[0];
   private final Path cacheRoot;
   private final PmTilesRangeReader pmTilesReader;
   private final Object initLock = new Object();
   private final LoadingCache<TellusOsmRoadSource.TileKey, OverpassRoadTile> cache;
   private final Set<TellusOsmRoadSource.TileKey> pendingAsyncLoads;
   private final Set<TellusOsmRoadSource.TileKey> tileLoadFailures;
   private volatile int queryZoom = QUERY_ZOOM;
   private volatile boolean available;
   private volatile boolean initialized;

   public TellusOsmRoadSource() {
      String pmTilesUrl = System.getProperty("tellus.overture.roads.pmtiles");
      if (pmTilesUrl == null || pmTilesUrl.isBlank()) {
         pmTilesUrl = OvertureTileUrls.defaultThemeUrl(PM_TILES_THEME);
      }

      this.cacheRoot = TellusPlatform.gameDir()
         .resolve("tellus/cache/map/roads")
         .resolve(OvertureTileUrls.cacheNamespace(pmTilesUrl));
      this.pmTilesReader = PmTilesRangeReader.shared(pmTilesUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, DIRECTORY_CACHE_ENTRIES);
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<TellusOsmRoadSource.TileKey, OverpassRoadTile>() {
         public OverpassRoadTile load(TellusOsmRoadSource.TileKey key) {
            return TellusOsmRoadSource.this.loadTile(key);
         }
      });
      this.pendingAsyncLoads = ConcurrentHashMap.newKeySet();
      this.tileLoadFailures = ConcurrentHashMap.newKeySet();
      TellusCacheRegistry.register(this);
   }

   public boolean available() {
      this.ensureInitialized();
      return this.available;
   }

   public List<RoadFeature> roadsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks) {
      return this.roadsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks, OsmQueryMode.BLOCKING);
   }

   public List<RoadFeature> roadsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks, OsmQueryMode mode) {
      return this.roadsForAreaWithStatus(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks, mode).features();
   }

   public TellusOsmRoadSource.RoadQueryResult roadsForAreaWithStatus(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks, OsmQueryMode mode
   ) {
      this.ensureInitialized();
      if (this.available && !(projection.worldScale() <= 0.0)) {
         TellusOsmRoadSource.GeoBounds bounds = geoBoundsForBlockArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, projection);
         if (bounds == null) {
            return new TellusOsmRoadSource.RoadQueryResult(List.of(), false);
         } else {
            List<TellusOsmRoadSource.TileKey> keys = tileKeysForBounds(bounds, this.queryZoom);
            if (keys.isEmpty()) {
               return new TellusOsmRoadSource.RoadQueryResult(List.of(), false);
            } else {
               List<RoadFeature> roads = new ArrayList<>();
               boolean hadCacheMiss = false;

               for (TellusOsmRoadSource.TileKey key : keys) {
                  TellusOsmRoadSource.TileLookup lookup = this.getTileLookup(key, mode);
                  hadCacheMiss |= lookup.cacheMiss();
                  OverpassRoadTile tile = lookup.tile();
                  if (!tile.isEmpty()) {
                     if (bounds.wrapsLongitude()) {
                        roads.addAll(tile.featuresInBounds(bounds.south(), bounds.west(), bounds.north(), 180.0));
                        roads.addAll(tile.featuresInBounds(bounds.south(), -180.0, bounds.north(), bounds.east()));
                     } else {
                        roads.addAll(tile.featuresInBounds(bounds.south(), bounds.west(), bounds.north(), bounds.east()));
                     }
                  }
               }

               return new TellusOsmRoadSource.RoadQueryResult(roads, hadCacheMiss);
            }
         }
      } else {
         return new TellusOsmRoadSource.RoadQueryResult(List.of(), false);
      }
   }

   public List<RoadAreaFeature> roadAreasForArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks, OsmQueryMode mode
   ) {
      return this.roadAreasForAreaWithStatus(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks, mode).features();
   }

   public TellusOsmRoadSource.RoadAreaQueryResult roadAreasForAreaWithStatus(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks, OsmQueryMode mode
   ) {
      this.ensureInitialized();
      if (this.available && !(projection.worldScale() <= 0.0)) {
         TellusOsmRoadSource.GeoBounds bounds = geoBoundsForBlockArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, projection);
         if (bounds == null) {
            return new TellusOsmRoadSource.RoadAreaQueryResult(List.of(), false);
         } else {
            List<TellusOsmRoadSource.TileKey> keys = tileKeysForBounds(bounds, this.queryZoom);
            if (keys.isEmpty()) {
               return new TellusOsmRoadSource.RoadAreaQueryResult(List.of(), false);
            } else {
               List<RoadAreaFeature> areas = new ArrayList<>();
               boolean hadCacheMiss = false;

               for (TellusOsmRoadSource.TileKey key : keys) {
                  TellusOsmRoadSource.TileLookup lookup = this.getTileLookup(key, mode);
                  hadCacheMiss |= lookup.cacheMiss();
                  OverpassRoadTile tile = lookup.tile();
                  if (!tile.isEmpty()) {
                     if (bounds.wrapsLongitude()) {
                        areas.addAll(tile.areaFeaturesInBounds(bounds.south(), bounds.west(), bounds.north(), 180.0));
                        areas.addAll(tile.areaFeaturesInBounds(bounds.south(), -180.0, bounds.north(), bounds.east()));
                     } else {
                        areas.addAll(tile.areaFeaturesInBounds(bounds.south(), bounds.west(), bounds.north(), bounds.east()));
                     }
                  }
               }

               return new TellusOsmRoadSource.RoadAreaQueryResult(areas, hadCacheMiss);
            }
         }
      } else {
         return new TellusOsmRoadSource.RoadAreaQueryResult(List.of(), false);
      }
   }

   public int downloadAreaTaskCount(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks) {
      return Math.max(1, this.downloadAreaTileKeys(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks).size());
   }

   public int downloadAreaInputs(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks,
      int completedUnits, BiConsumer<Integer, String> progressConsumer
   ) {
      BiConsumer<Integer, String> progress = progressConsumer == null ? (completed, detail) -> {
      } : progressConsumer;
      List<TellusOsmRoadSource.TileKey> keys = this.downloadAreaTileKeys(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks);
      if (keys.isEmpty()) {
         progress.accept(completedUnits, "Skipping OSM road tiles because the source is unavailable");
         return completedUnits + 1;
      }
      int startingUnits = completedUnits;
      progress.accept(completedUnits, "Downloading " + keys.size() + " OSM road source tiles");
      return ParallelDownloadRunner.run(ParallelDownloadRunner.scope("osm-roads", TellusCacheRegistry.generation(TellusCacheDomain.OSM)), keys, completedUnits, this::downloadRawTile, (key, completed, phaseTotal) -> progress.accept(
         completed, "Cached OSM road tile " + (completed - startingUnits) + "/" + phaseTotal + " (" + key.zoom() + "/" + key.x() + "/" + key.y() + ")"
      ));
   }

   public int preloadAreaInputs(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks,
      int completedUnits, BiConsumer<Integer, String> progressConsumer
   ) {
      BiConsumer<Integer, String> progress = progressConsumer == null ? (completed, detail) -> {
      } : progressConsumer;
      List<TellusOsmRoadSource.TileKey> keys = this.downloadAreaTileKeys(
         minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks
      );
      if (keys.isEmpty()) {
         progress.accept(completedUnits, "Skipping OSM road tiles because the source is unavailable");
         return completedUnits + 1;
      }
      int startingUnits = completedUnits;
      progress.accept(completedUnits, "Loading " + keys.size() + " OSM road source tiles");
      return ParallelDownloadRunner.run(ParallelDownloadRunner.scope("osm-roads-memory", TellusCacheRegistry.generation(TellusCacheDomain.OSM)), keys, completedUnits, this::loadTileIntoCache, (key, completed, phaseTotal) -> progress.accept(
         completed, "Loaded OSM road tile " + (completed - startingUnits) + "/" + phaseTotal + " (" + key.zoom() + "/" + key.x() + "/" + key.y() + ")"
      ));
   }

   private void loadTileIntoCache(TellusOsmRoadSource.TileKey key) {
      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
      try {
         if (this.tileLoadFailures.contains(key)) {
            this.cache.invalidate(key);
         }
         this.cache.get(key);
      } catch (Exception error) {
         this.tileLoadFailures.add(key);
         throw new RuntimeException("Failed to preload Overture road tile " + key, error);
      }
      if (Thread.currentThread().isInterrupted()) {
         throw new java.util.concurrent.CancellationException("Interrupted while preloading Overture road tile " + key);
      }
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation)) {
         this.cache.invalidate(key);
         throw new IllegalStateException("Discarded stale Overture road preload for " + key);
      }
   }

   private List<TellusOsmRoadSource.TileKey> downloadAreaTileKeys(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks
   ) {
      this.ensureInitialized();
      if (!this.available || projection.worldScale() <= 0.0) {
         return List.of();
      }

      TellusOsmRoadSource.GeoBounds bounds = geoBoundsForBlockArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, projection);
      if (bounds == null) {
         return List.of();
      }
      return tileKeysForBounds(bounds, this.queryZoom);
   }

   public void prefetchTiles(double blockX, double blockZ, WorldProjection projection, int radius) {
      this.ensureInitialized();
      if (this.available && !(projection.worldScale() <= 0.0) && radius > 0) {
         TellusOsmRoadSource.TileKey center = tileKeyForBlock(blockX, blockZ, projection, this.queryZoom);
         if (center != null) {
            int tilesPerAxis = 1 << this.queryZoom;
            int minX = Math.max(0, center.x() - radius);
            int maxX = Math.min(tilesPerAxis - 1, center.x() + radius);
            int minY = Math.max(0, center.y() - radius);
            int maxY = Math.min(tilesPerAxis - 1, center.y() + radius);

            for (int tileY = minY; tileY <= maxY; tileY++) {
               for (int tileX = minX; tileX <= maxX; tileX++) {
                  this.getTile(new TellusOsmRoadSource.TileKey(this.queryZoom, tileX, tileY), OsmQueryMode.NON_BLOCKING);
               }
            }
         }
      }
   }

   private OverpassRoadTile getTile(TellusOsmRoadSource.TileKey key, OsmQueryMode mode) {
      return this.getTileLookup(key, mode).tile();
   }

   private TellusOsmRoadSource.TileLookup getTileLookup(TellusOsmRoadSource.TileKey key, OsmQueryMode mode) {
      OverpassRoadTile cached = (OverpassRoadTile)this.cache.getIfPresent(key);
      if (cached != null) {
         if (!cached.isEmpty() || !this.tileLoadFailures.contains(key)) {
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_ROADS, OsmPerf.TileLoadPath.MEMORY);
            return new TellusOsmRoadSource.TileLookup(cached, false);
         }

         this.cache.invalidate(key);
      }

      OsmQueryMode queryMode = ManagedTerrainNetworkPolicy.isCacheOnly()
         ? OsmQueryMode.BLOCKING
         : mode == null ? OsmQueryMode.BLOCKING : mode;
      if (queryMode == OsmQueryMode.NON_BLOCKING) {
         this.queueAsyncLoad(key);
         return new TellusOsmRoadSource.TileLookup(OverpassRoadTile.empty(), true);
      } else {
         try {
            return new TellusOsmRoadSource.TileLookup((OverpassRoadTile)this.cache.get(key), false);
         } catch (Exception var6) {
            Tellus.LOGGER.debug("Failed to load Overture road tile {}", key, var6);
            this.tileLoadFailures.add(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_ROADS, OsmPerf.TileLoadPath.FAILURE);
            return new TellusOsmRoadSource.TileLookup(OverpassRoadTile.empty(), true);
         }
      }
   }

   private void queueAsyncLoad(TellusOsmRoadSource.TileKey key) {
      if (MAX_ASYNC_PREFETCH_LOADS <= 0 || this.pendingAsyncLoads.size() < MAX_ASYNC_PREFETCH_LOADS) {
         if (this.pendingAsyncLoads.add(key)) {
            MinecraftVersionCompat.backgroundExecutor().execute(() -> {
               try {
                  this.cache.get(key);
               } catch (Exception var6) {
                  Tellus.LOGGER.debug("Async load failed for Overture road tile {}", key, var6);
                  this.tileLoadFailures.add(key);
                  OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_ROADS, OsmPerf.TileLoadPath.FAILURE);
               } finally {
                  this.pendingAsyncLoads.remove(key);
               }
            });
         }
      }
   }

   private OverpassRoadTile loadTile(TellusOsmRoadSource.TileKey key) {
      this.ensureInitialized();
      if (!this.available) {
         return OverpassRoadTile.empty();
      } else {
         TellusOsmRoadSource.TileGeoBounds bounds = tileBounds(key);
         Path cachePath = this.cachePathFor(key);
         Path parsedCachePath = this.parsedCachePathFor(key);
         if (Files.exists(parsedCachePath)) {
            try {
               OverpassRoadTile parsed = ParsedTileCodec.readRoadTile(parsedCachePath);
               this.tileLoadFailures.remove(key);
               OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_ROADS, OsmPerf.TileLoadPath.PARSED_DISK);
               return parsed;
            } catch (RuntimeException | IOException var12) {
               Tellus.LOGGER.debug("Invalid parsed Overture road cache tile {}, refetching", key, var12);

               try {
                  Files.deleteIfExists(parsedCachePath);
               } catch (IOException var10) {
                  Tellus.LOGGER.debug("Failed to delete invalid parsed Overture road cache tile {}", parsedCachePath, var10);
               }
            }
         }

         if (Files.exists(cachePath)) {
            try {
               byte[] payload = this.readCompressed(cachePath);
               OverpassRoadTile parsed = this.parseVectorTile(payload, bounds, key);
               this.cacheParsedTile(parsedCachePath, parsed);
               this.tileLoadFailures.remove(key);
               OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_ROADS, OsmPerf.TileLoadPath.RAW_DISK);
               return parsed;
            } catch (RuntimeException | IOException var11) {
               Tellus.LOGGER.debug("Invalid Overture road cache tile {}, refetching", key, var11);

               try {
                  Files.deleteIfExists(cachePath);
               } catch (IOException var9) {
                  Tellus.LOGGER.debug("Failed to delete invalid Overture road cache tile {}", cachePath, var9);
               }
            }
         }

         long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
         byte[] payload = this.fetchTilePayloadWithRetry(key);
         if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation)) {
            throw new RuntimeException("Discarded stale Overture road tile " + key);
         }

         OverpassRoadTile parsed;
         try {
            parsed = this.parseVectorTile(payload, bounds, key);
         } catch (RuntimeException var8) {
            Tellus.LOGGER.warn("Overture road parse failed for tile {}", key, var8);
            this.tileLoadFailures.add(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_ROADS, OsmPerf.TileLoadPath.FAILURE);
            throw new RuntimeException("Overture road parse failed for tile " + key, var8);
         }

         if (!this.cacheTile(cachePath, payload, generation)) {
            throw new RuntimeException("Discarded stale Overture road cache write for tile " + key);
         }

         this.cacheParsedTile(parsedCachePath, parsed);
         this.tileLoadFailures.remove(key);
         OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_ROADS, OsmPerf.TileLoadPath.NETWORK);
         return parsed;
      }
   }

   private void downloadRawTile(TellusOsmRoadSource.TileKey key) {
      Path cachePath = this.cachePathFor(key);
      if (Files.isRegularFile(cachePath)) {
         return;
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
      byte[] payload = this.fetchTilePayloadWithRetry(key);
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation) || !this.cacheTile(cachePath, payload, generation)) {
         throw new RuntimeException("Discarded stale Overture road cache write for " + key);
      }
   }

   private void ensureInitialized() {
      if (this.initialized) {
         return;
      }

      synchronized (this.initLock) {
         if (this.initialized) {
            return;
         }

         int resolvedZoom = QUERY_ZOOM;
         boolean sourceAvailable;

         try {
            PmTilesRangeReader.PmTilesHeader header = this.pmTilesReader.header();
            if (header.tileType() != PMTILES_TILETYPE_MVT) {
               Tellus.LOGGER.warn("Unexpected Overture road PMTiles tile type {}, expected MVT", header.tileType());
            }

            resolvedZoom = Mth.clamp(resolvedZoom, header.minZoom(), header.maxZoom());
            sourceAvailable = true;
         } catch (IOException error) {
            sourceAvailable = false;
            Tellus.LOGGER.warn("Overture road PMTiles unavailable, roads disabled", error);
         }

         this.queryZoom = resolvedZoom;
         this.available = sourceAvailable;
         this.initialized = true;
      }
   }

   private byte[] fetchTilePayloadWithRetry(TellusOsmRoadSource.TileKey key) {
      RuntimeException lastFailure = null;

      for (int attempt = 1; attempt <= FETCH_RETRY_ATTEMPTS; attempt++) {
         try {
            byte[] fetched = this.pmTilesReader.getTileBytes(key.zoom(), key.x(), key.y());
            return fetched == null ? EMPTY_TILE_PAYLOAD : fetched;
         } catch (IOException var5) {
            lastFailure = new RuntimeException("Overture road fetch failed for tile " + key, var5);
            if (attempt < FETCH_RETRY_ATTEMPTS) {
               continue;
            }
            break;
         }
      }

      this.tileLoadFailures.add(key);
      OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_ROADS, OsmPerf.TileLoadPath.FAILURE);
      if (lastFailure != null) {
         throw lastFailure;
      } else {
         return EMPTY_TILE_PAYLOAD;
      }
   }

   private boolean cacheTile(Path cachePath, byte[] payload, long generation) {
      try {
         return TellusCacheFiles.writeIfCurrent(TellusCacheDomain.OSM, generation, cachePath, output -> {
            try (OutputStream gzipOutput = new GZIPOutputStream(output)) {
               gzipOutput.write(payload);
            }
         });
      } catch (IOException var9) {
         Tellus.LOGGER.debug("Failed to cache Overture road tile {}", cachePath, var9);
         return false;
      }
   }

   private void cacheParsedTile(Path cachePath, OverpassRoadTile tile) {
      try {
         ParsedTileCodec.writeRoadTile(cachePath, tile);
      } catch (IOException var4) {
         Tellus.LOGGER.debug("Failed to cache parsed Overture road tile {}", cachePath, var4);
      }
   }

   private byte[] readCompressed(Path path) throws IOException {
      byte[] var3;
      try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
         var3 = PmTilesSafety.readBounded(
            input, PmTilesSafety.MAX_DECOMPRESSED_TILE_BYTES, "Cached Overture road tile"
         );
      }

      return var3;
   }

   private OverpassRoadTile parseVectorTile(byte[] payload, TellusOsmRoadSource.TileGeoBounds bounds, TellusOsmRoadSource.TileKey key) {
      if (payload.length == 0) {
         return OverpassRoadTile.empty();
      } else {
         Tile tile;
         try {
            tile = Tile.parseFrom(payload);
         } catch (Exception var12) {
            throw new RuntimeException("Failed to decode transportation MVT payload", var12);
         }

         if (tile.getLayersCount() == 0) {
            return OverpassRoadTile.empty();
         } else {
            List<RoadFeature> features = new ArrayList<>();
            List<RoadAreaFeature> areaFeatures = new ArrayList<>();

            for (Layer layer : tile.getLayersList()) {
               if (SEGMENT_LAYER_NAME.equals(layer.getName())) {
                  int extent = layer.hasExtent() && layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_TILE_EXTENT;

                  for (Feature feature : layer.getFeaturesList()) {
                     if (feature.getType() == GeomType.LINESTRING) {
                        features.addAll(this.parseFeature(feature, layer, key, extent));
                     } else if (feature.getType() == GeomType.POLYGON) {
                        RoadAreaFeature areaFeature = this.parseAreaFeature(feature, layer, key, extent);
                        if (areaFeature != null) {
                           areaFeatures.add(areaFeature);
                        }
                     }
                  }
               }
            }

            return features.isEmpty() && areaFeatures.isEmpty()
               ? OverpassRoadTile.empty()
               : new OverpassRoadTile(features, areaFeatures, bounds.south(), bounds.west(), bounds.north(), bounds.east());
         }
      }
   }

   private List<RoadFeature> parseFeature(Feature feature, Layer layer, TellusOsmRoadSource.TileKey key, int extent) {
      Map<String, Object> tags = decodeTags(feature, layer);
      String subtype = asString(tags.get("subtype"));
      if (subtype != null && "road".equalsIgnoreCase(subtype)) {
         String classTag = resolveClassTag(tags);
         if (classTag == null) {
            return List.of();
         } else {
            RoadClass maybeRoadClass = RoadClass.fromHighwayTag(classTag);
            if (maybeRoadClass == null) {
               return List.of();
            } else {
               TellusOsmRoadSource.LineString line = selectPrimaryLineString(feature.getGeometryList());
               if (line == null) {
                  return List.of();
               } else {
                  long wayId = resolveFeatureId(feature, tags);
                  return buildFeaturesFromLine(wayId, maybeRoadClass, classTag, tags, line, key.zoom(), key.x(), key.y(), extent);
               }
            }
         }
      } else {
         return List.of();
      }
   }

   private RoadAreaFeature parseAreaFeature(Feature feature, Layer layer, TellusOsmRoadSource.TileKey key, int extent) {
      Map<String, Object> tags = decodeTags(feature, layer);
      String subtype = asString(tags.get("subtype"));
      if (subtype == null || !"road".equalsIgnoreCase(subtype)) {
         return null;
      }

      String classTag = resolveClassTag(tags);
      RoadClass roadClass = RoadClass.fromHighwayTag(classTag);
      if (classTag == null || roadClass == null || !isRoadAreaFeature(tags, classTag)) {
         return null;
      }

      List<List<TellusOsmRoadSource.TilePoint>> rings = decodePolygonRings(feature.getGeometryList());
      if (rings.isEmpty()) {
         return null;
      }

      double[][] longitudes = new double[rings.size()][];
      double[][] latitudes = new double[rings.size()][];
      for (int part = 0; part < rings.size(); part++) {
         List<TellusOsmRoadSource.TilePoint> ring = rings.get(part);
         int points = ring.size();
         double[] lonPart = new double[points];
         double[] latPart = new double[points];
         int count = 0;
         double previousLon = Double.NaN;
         double previousLat = Double.NaN;

         for (TellusOsmRoadSource.TilePoint point : ring) {
            double lon = tilePointToLon(key.zoom(), key.x(), (double)point.x / extent);
            double lat = tilePointToLat(key.zoom(), key.y(), (double)point.y / extent);
            if (Double.isFinite(lat)
               && Double.isFinite(lon)
               && !(lat < MIN_LAT)
               && !(lat > MAX_LAT)
               && !(lon < MIN_LON)
               && !(lon > MAX_LON)
               && (count <= 0 || !(Math.abs(lon - previousLon) < POINT_EPSILON) || !(Math.abs(lat - previousLat) < POINT_EPSILON))) {
               lonPart[count] = lon;
               latPart[count] = lat;
               previousLon = lon;
               previousLat = lat;
               count++;
            }
         }

         if (count < 4) {
            return null;
         }

         if (lonPart[0] != lonPart[count - 1] || latPart[0] != latPart[count - 1]) {
            if (count + 1 > lonPart.length) {
               lonPart = Arrays.copyOf(lonPart, count + 1);
               latPart = Arrays.copyOf(latPart, count + 1);
            }

            lonPart[count] = lonPart[0];
            latPart[count] = latPart[0];
            count++;
         }

         longitudes[part] = count == lonPart.length ? lonPart : Arrays.copyOf(lonPart, count);
         latitudes[part] = count == latPart.length ? latPart : Arrays.copyOf(latPart, count);
      }

      String roadSurface = resolveStringAt(tags, "road_surface", 0.5);
      if (roadSurface == null) {
         roadSurface = asString(tags.get("surface"));
      }
      String subclass = resolveStringAt(tags, "subclass_rules", 0.5);
      if (subclass == null) {
         subclass = asString(tags.get("subclass"));
      }
      return new RoadAreaFeature(resolveFeatureId(feature, tags), roadClass, classTag, roadSurface, subclass, longitudes, latitudes);
   }

   private static List<RoadFeature> buildFeaturesFromLine(
      long wayId,
      RoadClass roadClass,
      String highwayTag,
      Map<String, Object> tags,
      TellusOsmRoadSource.LineString line,
      int zoom,
      int tileX,
      int tileY,
      int extent
   ) {
      List<TellusOsmRoadSource.TilePoint> points = line.points();
      int maxPoints = points.size();
      double[] longitudes = new double[maxPoints];
      double[] latitudes = new double[maxPoints];
      int count = 0;
      double previousLon = Double.NaN;
      double previousLat = Double.NaN;

      for (TellusOsmRoadSource.TilePoint point : points) {
         double localX = (double)point.x / extent;
         double localY = (double)point.y / extent;
         double lon = tilePointToLon(zoom, tileX, localX);
         double lat = tilePointToLat(zoom, tileY, localY);
         if (Double.isFinite(lat)
            && Double.isFinite(lon)
            && !(lat < MIN_LAT)
            && !(lat > MAX_LAT)
            && !(lon < MIN_LON)
            && !(lon > MAX_LON)
            && (count <= 0 || !(Math.abs(lon - previousLon) < POINT_EPSILON) || !(Math.abs(lat - previousLat) < POINT_EPSILON))) {
            longitudes[count] = lon;
            latitudes[count] = lat;
            previousLon = lon;
            previousLat = lat;
            count++;
         }
      }

      if (count < 2) {
         return List.of();
      } else {
         double[] trimmedLon = count == longitudes.length ? longitudes : Arrays.copyOf(longitudes, count);
         double[] trimmedLat = count == latitudes.length ? latitudes : Arrays.copyOf(latitudes, count);
         return buildFeatures(wayId, roadClass, highwayTag, tags, trimmedLon, trimmedLat);
      }
   }

   static List<RoadFeature> buildFeatures(
      long wayId, RoadClass roadClass, String highwayTag, Map<String, Object> tags, double[] longitudes, double[] latitudes
   ) {
      Map<String, Object> safeTags = tags == null ? Map.of() : tags;
      TellusOsmRoadSource.LineGeometry geometry = TellusOsmRoadSource.LineGeometry.create(longitudes, latitudes);
      if (geometry == null) {
         return List.of();
      } else {
         List<Double> breakpoints = collectScopedBreakpoints(safeTags);
         List<RoadFeature> features = new ArrayList<>();
         TellusOsmRoadSource.RoadDescriptor activeDescriptor = null;
         double activeStart = 0.0;
         double activeEnd = 0.0;

         for (int i = 1; i < breakpoints.size(); i++) {
            double start = breakpoints.get(i - 1);
            double end = breakpoints.get(i);
            if (!(end - start <= 1.0E-6)) {
               TellusOsmRoadSource.RoadDescriptor descriptor = resolveRoadDescriptorAt(safeTags, roadClass, start + (end - start) * 0.5);
               if (activeDescriptor != null && activeDescriptor.equals(descriptor) && Math.abs(start - activeEnd) <= 1.0E-6) {
                  activeEnd = end;
               } else {
                  appendRoadSlice(features, wayId, roadClass, highwayTag, geometry, activeDescriptor, activeStart, activeEnd);
                  activeDescriptor = descriptor;
                  activeStart = start;
                  activeEnd = end;
               }
            }
         }

         appendRoadSlice(features, wayId, roadClass, highwayTag, geometry, activeDescriptor, activeStart, activeEnd);
         return features;
      }
   }

   private static void appendRoadSlice(
      List<RoadFeature> features,
      long wayId,
      RoadClass roadClass,
      String highwayTag,
      TellusOsmRoadSource.LineGeometry geometry,
      TellusOsmRoadSource.RoadDescriptor descriptor,
      double start,
      double end
   ) {
      if (descriptor != null) {
         RoadFeature feature = buildFeatureFromRange(wayId, roadClass, descriptor, highwayTag, geometry, start, end);
         if (feature != null) {
            features.add(feature);
         }
      }
   }

   private static RoadFeature buildFeatureFromRange(
      long wayId,
      RoadClass roadClass,
      TellusOsmRoadSource.RoadDescriptor descriptor,
      String highwayTag,
      TellusOsmRoadSource.LineGeometry geometry,
      double start,
      double end
   ) {
      double clampedStart = Mth.clamp(start, 0.0, 1.0);
      double clampedEnd = Mth.clamp(end, 0.0, 1.0);
      if (!(clampedEnd - clampedStart <= 1.0E-6) && geometry.totalLength() > 1.0E-9) {
         double startDistance = geometry.totalLength() * clampedStart;
         double endDistance = geometry.totalLength() * clampedEnd;
         List<Double> sliceLon = new ArrayList<>();
         List<Double> sliceLat = new ArrayList<>();
         TellusOsmRoadSource.GeoPoint startPoint = pointAtDistance(geometry, startDistance);
         appendPoint(sliceLon, sliceLat, startPoint.lon(), startPoint.lat());

         for (int i = 1; i < geometry.longitudes().length - 1; i++) {
            double distance = geometry.distances()[i];
            if (distance > startDistance + 1.0E-9 && distance < endDistance - 1.0E-9) {
               appendPoint(sliceLon, sliceLat, geometry.longitudes()[i], geometry.latitudes()[i]);
            }
         }

         TellusOsmRoadSource.GeoPoint endPoint = pointAtDistance(geometry, endDistance);
         appendPoint(sliceLon, sliceLat, endPoint.lon(), endPoint.lat());
         if (sliceLon.size() < 2) {
            return null;
         } else {
            double[] featureLon = new double[sliceLon.size()];
            double[] featureLat = new double[sliceLat.size()];

            for (int i = 0; i < sliceLon.size(); i++) {
               featureLon[i] = sliceLon.get(i);
               featureLat[i] = sliceLat.get(i);
            }

            return new RoadFeature(
               wayId,
               roadClass,
               descriptor.mode(),
               descriptor.bridgeLevel(),
               highwayTag,
               descriptor.roadSurface(),
               descriptor.subclass(),
               descriptor.widthMeters(),
               descriptor.lanes(),
               descriptor.laneMarkings(),
               featureLon,
               featureLat
            );
         }
      } else {
         return null;
      }
   }

   private static void appendPoint(List<Double> longitudes, List<Double> latitudes, double lon, double lat) {
      int size = longitudes.size();
      if (size <= 0
         || Math.abs(longitudes.get(size - 1) - lon) >= POINT_EPSILON
         || Math.abs(latitudes.get(size - 1) - lat) >= POINT_EPSILON) {
         longitudes.add(lon);
         latitudes.add(lat);
      }
   }

   private static TellusOsmRoadSource.GeoPoint pointAtDistance(TellusOsmRoadSource.LineGeometry geometry, double distance) {
      double clampedDistance = Mth.clamp(distance, 0.0, geometry.totalLength());
      if (clampedDistance <= 1.0E-9) {
         return new TellusOsmRoadSource.GeoPoint(geometry.longitudes()[0], geometry.latitudes()[0]);
      } else if (geometry.totalLength() - clampedDistance <= 1.0E-9) {
         int last = geometry.longitudes().length - 1;
         return new TellusOsmRoadSource.GeoPoint(geometry.longitudes()[last], geometry.latitudes()[last]);
      } else {
         for (int i = 1; i < geometry.longitudes().length; i++) {
            double endDistance = geometry.distances()[i];
            if (clampedDistance <= endDistance + 1.0E-9) {
               double startDistance = geometry.distances()[i - 1];
               double segmentLength = endDistance - startDistance;
               if (segmentLength <= 1.0E-9) {
                  return new TellusOsmRoadSource.GeoPoint(geometry.longitudes()[i], geometry.latitudes()[i]);
               }

               double t = (clampedDistance - startDistance) / segmentLength;
               double lon = geometry.longitudes()[i - 1] + (geometry.longitudes()[i] - geometry.longitudes()[i - 1]) * t;
               double lat = geometry.latitudes()[i - 1] + (geometry.latitudes()[i] - geometry.latitudes()[i - 1]) * t;
               return new TellusOsmRoadSource.GeoPoint(lon, lat);
            }
         }

         int last = geometry.longitudes().length - 1;
         return new TellusOsmRoadSource.GeoPoint(geometry.longitudes()[last], geometry.latitudes()[last]);
      }
   }

   private static String resolveClassTag(Map<String, Object> tags) {
      String classTag = asString(tags.get("class"));
      return classTag != null && !classTag.isBlank() ? classTag : null;
   }

   private static List<Double> collectScopedBreakpoints(Map<String, Object> tags) {
      List<Double> breakpoints = new ArrayList<>();
      breakpoints.add(0.0);
      breakpoints.add(1.0);
      collectScopedBreakpoints(tags.get("road_flags"), breakpoints);
      collectScopedBreakpoints(tags.get("level_rules"), breakpoints);
      collectScopedBreakpoints(tags.get("road_surface"), breakpoints);
      collectScopedBreakpoints(tags.get("width_rules"), breakpoints);
      collectScopedBreakpoints(tags.get("lanes_rules"), breakpoints);
      collectScopedBreakpoints(tags.get("lane_markings_rules"), breakpoints);
      collectScopedBreakpoints(tags.get("subclass_rules"), breakpoints);
      breakpoints.sort(Double::compare);
      List<Double> normalized = new ArrayList<>(breakpoints.size());

      for (double breakpoint : breakpoints) {
         double clamped = Mth.clamp(breakpoint, 0.0, 1.0);
         if (normalized.isEmpty() || Math.abs(normalized.get(normalized.size() - 1) - clamped) > 1.0E-6) {
            normalized.add(clamped);
         }
      }

      return normalized;
   }

   private static void collectScopedBreakpoints(Object rulesValue, List<Double> breakpoints) {
      JsonArray rules = parseRuleArray(rulesValue);
      if (rules != null) {
         for (JsonElement ruleElement : rules) {
            if (ruleElement.isJsonObject()) {
               TellusOsmRoadSource.ScopeRange range = parseScopeRange(ruleElement.getAsJsonObject());
               if (range != null) {
                  breakpoints.add(range.start());
                  breakpoints.add(range.end());
               }
            }
         }
      }
   }

   private static TellusOsmRoadSource.RoadDescriptor resolveRoadDescriptorAt(Map<String, Object> tags, RoadClass roadClass, double position) {
      int signedLevel = resolveSignedLevelAt(tags, position);
      String roadSurface = resolveStringAt(tags, "road_surface", position);
      if (roadSurface == null) {
         roadSurface = asString(tags.get("surface"));
      }
      String subclass = resolveStringAt(tags, "subclass_rules", position);
      if (subclass == null) {
         subclass = asString(tags.get("subclass"));
      }
      double widthMeters = resolveWidthMetersAt(tags, position);
      int lanes = resolveLanesAt(tags, position);
      boolean laneMarkings = resolveLaneMarkingsAt(tags, position);
      boolean explicitBridge = isTruthy(asString(tags.get("bridge"))) || containsFlagAt(tags.get("road_flags"), "is_bridge", position);
      if (explicitBridge) {
         int bridgeLevel = signedLevel > 0 && roadClass != RoadClass.DIRT ? Mth.clamp(signedLevel, 1, MAX_BRIDGE_LEVEL) : 1;
         return new TellusOsmRoadSource.RoadDescriptor(RoadMode.BRIDGE, bridgeLevel, roadSurface, subclass, widthMeters, lanes, laneMarkings);
      } else {
         boolean tunnel = containsFlagAt(tags.get("road_flags"), "is_tunnel", position)
            || isTruthy(asString(tags.get("tunnel")))
            || signedLevel < 0;
         return tunnel
            ? new TellusOsmRoadSource.RoadDescriptor(RoadMode.TUNNEL, 0, roadSurface, subclass, widthMeters, lanes, laneMarkings)
            : new TellusOsmRoadSource.RoadDescriptor(RoadMode.NORMAL, 0, roadSurface, subclass, widthMeters, lanes, laneMarkings);
      }
   }

   private static String resolveStringAt(Map<String, Object> tags, String key, double position) {
      Object value = tags.get(key);
      JsonArray rules = parseRuleArray(value);
      if (rules != null) {
         for (JsonElement ruleElement : rules) {
            String directValue = asString(ruleElement);
            if (directValue != null && !directValue.isBlank()) {
               return directValue;
            }

            if (ruleElement.isJsonObject()) {
               JsonObject rule = ruleElement.getAsJsonObject();
               if (ruleAppliesAt(rule, position)) {
                  String ruleValue = asString(rule.get("value"));
                  if (ruleValue != null && !ruleValue.isBlank()) {
                     return ruleValue;
                  }
               }
            }
         }
      }

      String direct = asString(value);
      return direct == null || direct.isBlank() || direct.trim().startsWith("[") ? null : direct;
   }

   private static double resolveWidthMetersAt(Map<String, Object> tags, double position) {
      Double width = resolveDoubleAt(tags.get("width_rules"), position);
      if (width == null) {
         width = parseDouble(tags.get("width"));
      }

      return width == null ? 0.0 : RoadSurfaceStyle.normalizeWidthMeters(width);
   }

   private static int resolveLanesAt(Map<String, Object> tags, double position) {
      Integer lanes = resolveIntegerAt(tags.get("lanes_rules"), position);
      if (lanes == null) {
         lanes = parseInteger(tags.get("lanes"));
      }
      if (lanes == null) {
         Integer forward = parseInteger(tags.get("lanes:forward"));
         Integer backward = parseInteger(tags.get("lanes:backward"));
         if (forward != null || backward != null) {
            lanes = Math.max(0, forward == null ? 0 : forward) + Math.max(0, backward == null ? 0 : backward);
         }
      }
      return lanes == null ? 0 : RoadSurfaceStyle.normalizeLaneCount(lanes);
   }

   private static boolean resolveLaneMarkingsAt(Map<String, Object> tags, double position) {
      String scoped = resolveStringAt(tags, "lane_markings_rules", position);
      if (scoped != null) {
         return RoadSurfaceStyle.normalizeLaneMarkings(scoped);
      }
      return RoadSurfaceStyle.normalizeLaneMarkings(asString(tags.get("lane_markings")));
   }

   private static Integer resolveIntegerAt(Object value, double position) {
      Double resolved = resolveDoubleAt(value, position);
      return resolved == null ? null : (int)Math.round(resolved);
   }

   private static Double resolveDoubleAt(Object value, double position) {
      JsonArray rules = parseRuleArray(value);
      if (rules != null) {
         for (JsonElement ruleElement : rules) {
            Double directValue = parseDouble(ruleElement);
            if (directValue != null) {
               return directValue;
            }

            if (ruleElement.isJsonObject()) {
               JsonObject rule = ruleElement.getAsJsonObject();
               if (ruleAppliesAt(rule, position)) {
                  Double ruleValue = parseDouble(rule.get("value"));
                  if (ruleValue != null) {
                     return ruleValue;
                  }
               }
            }
         }
      }

      return parseDouble(value);
   }

   private static int resolveSignedLevelAt(Map<String, Object> tags, double position) {
      int[] levels = new int[]{0, 0};
      collectLevelValue(tags.get("layer"), levels);
      collectLevelValue(tags.get("level"), levels);
      collectLevelRulesAt(tags.get("level_rules"), levels, position);
      if (levels[0] > 0) {
         return levels[0];
      } else {
         return levels[1] < 0 ? levels[1] : 0;
      }
   }

   private static void collectLevelRulesAt(Object rulesValue, int[] levels, double position) {
      JsonArray rules = parseRuleArray(rulesValue);
      if (rules != null) {
         for (JsonElement ruleElement : rules) {
            if (ruleElement.isJsonObject()) {
               JsonObject rule = ruleElement.getAsJsonObject();
               if (ruleAppliesAt(rule, position)) {
                  collectLevelElement(rule.get("value"), levels);
               }
            }
         }
      }
   }

   private static void collectLevelValue(Object value, int[] levels) {
      if (value != null) {
         collectLevelNumber(parseInteger(value), levels);
      }
   }

   private static void collectLevelElement(JsonElement value, int[] levels) {
      if (value != null && !value.isJsonNull()) {
         collectLevelNumber(parseInteger(value), levels);
      }
   }

   private static void collectLevelNumber( Integer value, int[] levels) {
      if (value != null && value != 0) {
         if (value > 0) {
            levels[0] = Math.max(levels[0], value);
         } else {
            levels[1] = Math.min(levels[1], value);
         }
      }
   }

   private static boolean containsFlagAt(Object rulesValue, String flag, double position) {
      JsonArray rules = parseRuleArray(rulesValue);
      if (rules == null) {
         return false;
      } else {
         for (JsonElement ruleElement : rules) {
            String directValue = asString(ruleElement);
            if (directValue != null && flag.equalsIgnoreCase(directValue)) {
               return true;
            }

            if (ruleElement.isJsonObject()) {
               JsonObject rule = ruleElement.getAsJsonObject();
               if (!ruleAppliesAt(rule, position)) {
                  continue;
               }

               JsonArray values = getArray(rule, "values");
               if (values != null) {
                  for (JsonElement valueElement : values) {
                     String value = asString(valueElement);
                     if (value != null && flag.equalsIgnoreCase(value)) {
                        return true;
                     }
                  }
               }

               String value = asString(rule.get("value"));
               if (value != null && flag.equalsIgnoreCase(value)) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   private static boolean ruleAppliesAt(JsonObject rule, double position) {
      TellusOsmRoadSource.ScopeRange range = parseScopeRange(rule);
      if (range == null) {
         return true;
      } else {
         double clampedPosition = Mth.clamp(position, 0.0, 1.0);
         return clampedPosition + 1.0E-6 >= range.start() && clampedPosition - 1.0E-6 <= range.end();
      }
   }

   private static TellusOsmRoadSource.ScopeRange parseScopeRange(JsonObject rule) {
      JsonArray between = getArray(rule, "between");
      if (between != null && between.size() == 2) {
         Double start = parseDouble(between.get(0));
         Double end = parseDouble(between.get(1));
         if (start != null && end != null) {
            double clampedStart = Mth.clamp(start, 0.0, 1.0);
            double clampedEnd = Mth.clamp(end, 0.0, 1.0);
            if (clampedEnd > clampedStart + 1.0E-6) {
               return new TellusOsmRoadSource.ScopeRange(clampedStart, clampedEnd);
            }
         }
      }

      return null;
   }

   
   private static JsonArray parseRuleArray(Object rulesValue) {
      if (rulesValue == null) {
         return null;
      } else {
         try {
            if (rulesValue instanceof JsonArray array) {
               return array;
            } else if (rulesValue instanceof JsonElement element) {
               return element.isJsonArray() ? element.getAsJsonArray() : null;
            } else {
               String text = String.valueOf(rulesValue);
               if (text.isBlank()) {
                  return null;
               } else {
                  JsonElement parsed = JsonParser.parseString(text);
                  return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
               }
            }
         } catch (RuntimeException var3) {
            return null;
         }
      }
   }

   
   private static Integer parseInteger(Object value) {
      if (value == null) {
         return null;
      } else if (value instanceof Number number) {
         return (int)Math.round(number.doubleValue());
      } else {
         String text = String.valueOf(value);
         if (text.isBlank()) {
            return null;
         } else {
            String normalized = text.trim();
            if (normalized.startsWith("+")) {
               normalized = normalized.substring(1);
            }

            int separator = normalized.indexOf(32);
            if (separator > 0) {
               normalized = normalized.substring(0, separator);
            }

            try {
               return normalized.contains(".") ? (int)Math.round(Double.parseDouble(normalized)) : Integer.parseInt(normalized);
            } catch (NumberFormatException var5) {
               return null;
            }
         }
      }
   }

   
   private static Integer parseInteger(JsonElement value) {
      if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
         try {
            return value.getAsJsonPrimitive().isNumber() ? (int)Math.round(value.getAsDouble()) : parseInteger(value.getAsString());
         } catch (RuntimeException var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static Double parseDouble(JsonElement value) {
      if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
         try {
            return value.getAsJsonPrimitive().isNumber() ? value.getAsDouble() : Double.parseDouble(value.getAsString());
         } catch (RuntimeException var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static Double parseDouble(Object value) {
      if (value == null) {
         return null;
      } else if (value instanceof Number number) {
         return number.doubleValue();
      } else if (value instanceof JsonElement element) {
         return parseDouble(element);
      } else {
         String text = String.valueOf(value).trim();
         if (text.isEmpty()) {
            return null;
         } else {
            int separator = text.indexOf(32);
            if (separator > 0) {
               text = text.substring(0, separator);
            }

            try {
               return Double.parseDouble(text);
            } catch (NumberFormatException error) {
               return null;
            }
         }
      }
   }

   private static boolean isTruthy(String value) {
      if (value == null) {
         return false;
      } else {
         String normalized = value.trim().toLowerCase(Locale.ROOT);
         return !normalized.isEmpty() && !"no".equals(normalized) && !"false".equals(normalized) && !"0".equals(normalized);
      }
   }

   private static boolean isRoadAreaFeature(Map<String, Object> tags, String classTag) {
      if (isTruthy(asString(tags.get("area")))) {
         return true;
      }

      String normalizedClass = classTag == null ? "" : classTag.trim().toLowerCase(Locale.ROOT).replace('-', '_');
      return switch (normalizedClass) {
         case "pedestrian", "footway", "path", "living_street", "service" -> true;
         default -> false;
      };
   }

   
   private static TellusOsmRoadSource.LineString selectPrimaryLineString(List<Integer> geometry) {
      List<TellusOsmRoadSource.LineString> lines = decodeLineStrings(geometry);
      if (lines.isEmpty()) {
         return null;
      } else {
         TellusOsmRoadSource.LineString selected = null;

         for (TellusOsmRoadSource.LineString line : lines) {
            if (selected == null || line.lengthScore > selected.lengthScore) {
               selected = line;
            }
         }

         return selected;
      }
   }

   private static List<TellusOsmRoadSource.LineString> decodeLineStrings(List<Integer> geometry) {
      if (geometry != null && !geometry.isEmpty()) {
         List<TellusOsmRoadSource.LineString> lines = new ArrayList<>();
         List<TellusOsmRoadSource.TilePoint> current = null;
         int cursorX = 0;
         int cursorY = 0;
         int cursor = 0;

         while (cursor < geometry.size()) {
            int commandAndCount = geometry.get(cursor++);
            int command = commandAndCount & 7;
            int count = commandAndCount >>> 3;
            if (count > 0) {
               switch (command) {
                  case 1:
                     for (int i = 0; i < count; i++) {
                        if (cursor + 1 >= geometry.size()) {
                           return lines;
                        }

                        cursorX += zigZagDecode(geometry.get(cursor++));
                        cursorY += zigZagDecode(geometry.get(cursor++));
                        if (current != null && !current.isEmpty()) {
                           addLine(lines, current);
                        }

                        current = new ArrayList<>();
                        current.add(new TellusOsmRoadSource.TilePoint(cursorX, cursorY));
                     }
                     break;
                  case 2:
                     if (current == null) {
                        current = new ArrayList<>();
                     }

                     for (int i = 0; i < count; i++) {
                        if (cursor + 1 >= geometry.size()) {
                           return lines;
                        }

                        cursorX += zigZagDecode(geometry.get(cursor++));
                        cursorY += zigZagDecode(geometry.get(cursor++));
                        current.add(new TellusOsmRoadSource.TilePoint(cursorX, cursorY));
                     }
                     break;
                  case 7:
                     if (current != null && !current.isEmpty()) {
                        addLine(lines, current);
                        current = null;
                     }
                     break;
                  default:
                     return lines;
               }
            }
         }

         if (current != null && !current.isEmpty()) {
            addLine(lines, current);
         }

         return lines;
      } else {
         return List.of();
      }
   }

   private static List<List<TellusOsmRoadSource.TilePoint>> decodePolygonRings(List<Integer> geometry) {
      if (geometry == null || geometry.isEmpty()) {
         return List.of();
      }

      List<List<TellusOsmRoadSource.TilePoint>> rings = new ArrayList<>();
      List<TellusOsmRoadSource.TilePoint> current = null;
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

         switch (command) {
            case 1:
               for (int i = 0; i < count; i++) {
                  if (cursor + 1 >= geometry.size()) {
                     return rings;
                  }

                  if (current != null && current.size() >= 3) {
                     closeRing(current);
                     rings.add(List.copyOf(current));
                  }

                  cursorX += zigZagDecode(geometry.get(cursor++));
                  cursorY += zigZagDecode(geometry.get(cursor++));
                  current = new ArrayList<>();
                  current.add(new TellusOsmRoadSource.TilePoint(cursorX, cursorY));
               }
               break;
            case 2:
               if (current == null) {
                  current = new ArrayList<>();
               }

               for (int i = 0; i < count; i++) {
                  if (cursor + 1 >= geometry.size()) {
                     return rings;
                  }

                  cursorX += zigZagDecode(geometry.get(cursor++));
                  cursorY += zigZagDecode(geometry.get(cursor++));
                  current.add(new TellusOsmRoadSource.TilePoint(cursorX, cursorY));
               }
               break;
            case 7:
               if (current != null && current.size() >= 3) {
                  closeRing(current);
                  rings.add(List.copyOf(current));
               }
               current = null;
               break;
            default:
               return rings;
         }
      }

      if (current != null && current.size() >= 3) {
         closeRing(current);
         rings.add(List.copyOf(current));
      }

      return rings;
   }

   private static void closeRing(List<TellusOsmRoadSource.TilePoint> ring) {
      if (!ring.isEmpty() && !samePoint(ring.get(0), ring.get(ring.size() - 1))) {
         ring.add(ring.get(0));
      }
   }

   private static void addLine(List<TellusOsmRoadSource.LineString> lines, List<TellusOsmRoadSource.TilePoint> points) {
      if (points.size() >= 2) {
         List<TellusOsmRoadSource.TilePoint> cleaned = new ArrayList<>(points.size());

         for (TellusOsmRoadSource.TilePoint point : points) {
            if (cleaned.isEmpty() || !samePoint(cleaned.get(cleaned.size() - 1), point)) {
               cleaned.add(point);
            }
         }

         if (cleaned.size() >= 2) {
            double lengthScore = 0.0;

            for (int i = 1; i < cleaned.size(); i++) {
               TellusOsmRoadSource.TilePoint a = cleaned.get(i - 1);
               TellusOsmRoadSource.TilePoint b = cleaned.get(i);
               double dx = b.x - a.x;
               double dy = b.y - a.y;
               lengthScore += dx * dx + dy * dy;
            }

            if (!(lengthScore <= 0.0)) {
               lines.add(new TellusOsmRoadSource.LineString(List.copyOf(cleaned), lengthScore));
            }
         }
      }
   }

   private static boolean samePoint(TellusOsmRoadSource.TilePoint a, TellusOsmRoadSource.TilePoint b) {
      return a.x == b.x && a.y == b.y;
   }

   private static int zigZagDecode(int encoded) {
      return encoded >>> 1 ^ -(encoded & 1);
   }

   private static double tilePointToLon(int zoom, int tileX, double localX) {
      double n = tilesPerAxis(zoom);
      double normalizedX = (tileX + localX) / n;
      return normalizedX * 360.0 - 180.0;
   }

   private static double tilePointToLat(int zoom, int tileY, double localY) {
      double n = tilesPerAxis(zoom);
      double normalizedY = (tileY + localY) / n;
      double mercator = Math.PI * (1.0 - 2.0 * normalizedY);
      return Math.toDegrees(Math.atan(Math.sinh(mercator)));
   }

   private static Map<String, Object> decodeTags(Feature feature, Layer layer) {
      List<Integer> tags = feature.getTagsList();
      if (tags.isEmpty()) {
         return Map.of();
      } else {
         Map<String, Object> values = new HashMap<>(tags.size() / 2);

         for (int i = 0; i + 1 < tags.size(); i += 2) {
            int keyIndex = tags.get(i);
            int valueIndex = tags.get(i + 1);
            if (keyIndex >= 0 && keyIndex < layer.getKeysCount() && valueIndex >= 0 && valueIndex < layer.getValuesCount()) {
               String key = layer.getKeys(keyIndex);
               Object value = decodeValue(layer.getValues(valueIndex));
               if (key != null && !key.isBlank() && value != null) {
                  values.put(key, value);
               }
            }
         }

         return values;
      }
   }

   
   private static Object decodeValue(Value value) {
      if (value.hasStringValue()) {
         return value.getStringValue();
      } else if (value.hasDoubleValue()) {
         return value.getDoubleValue();
      } else if (value.hasFloatValue()) {
         return (double)value.getFloatValue();
      } else if (value.hasSintValue()) {
         return value.getSintValue();
      } else if (value.hasIntValue()) {
         return value.getIntValue();
      } else if (value.hasUintValue()) {
         return value.getUintValue();
      } else {
         return value.hasBoolValue() ? value.getBoolValue() : null;
      }
   }

   private static long resolveFeatureId(Feature feature, Map<String, Object> tags) {
      long idFromSources = parseSourceWayId(tags.get("sources"));
      if (idFromSources != 0L) {
         return idFromSources;
      }
      if (feature.hasId() && feature.getId() != 0L) {
         return syntheticId(feature.getId());
      }
      long idFromTag = parseLongId(tags.get("id"));
      return idFromTag != 0L ? syntheticId(idFromTag) : -1L;
   }

   private static long parseLongId(Object value) {
      if (value == null) {
         return 0L;
      } else if (value instanceof Number number) {
         return number.longValue();
      } else {
         String text = String.valueOf(value).trim();
         if (text.isEmpty()) {
            return 0L;
         } else {
            try {
               return Long.parseLong(text);
            } catch (NumberFormatException var6) {
               try {
                  UUID uuid = UUID.fromString(text);
                  long mixed = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
                  return mixed == 0L ? 1L : mixed;
               } catch (IllegalArgumentException var5) {
                  return hash64(text);
               }
            }
         }
      }
   }

   private static long parseSourceWayId(Object value) {
      if (value instanceof String sources && !sources.isBlank()) {
         int cursor = 0;

         while (true) {
            int start = findOsmWayDigitsStart(sources, cursor);
            if (start < 0) {
               return 0L;
            }
            int recordIndex = start - 1;

            int end;
            for (end = start; end < sources.length(); end++) {
               char ch = sources.charAt(end);
               if (ch < '0' || ch > '9') {
                  break;
               }
            }

            if (end > start) {
               try {
                  return Long.parseLong(sources.substring(start, end));
               } catch (NumberFormatException var7) {
               }
            }

            cursor = end > start ? end : recordIndex + 1;
         }
      } else {
         return 0L;
      }
   }

   private static int findOsmWayDigitsStart(String sources, int cursor) {
      while (cursor < sources.length()) {
         int key = sources.indexOf("\"record_id\"", cursor);
         if (key < 0) return -1;
         int colon = sources.indexOf(':', key + 11);
         int quote = colon < 0 ? -1 : sources.indexOf('"', colon + 1);
         int prefix = quote + 1;
         if (quote >= 0 && prefix < sources.length() && sources.charAt(prefix) == 'w') return prefix + 1;
         cursor = Math.max(key + 1, prefix);
      }
      return -1;
   }

   private static long syntheticId(long value) {
      if (value == Long.MIN_VALUE) return Long.MIN_VALUE + 1L;
      long absolute = Math.abs(value);
      return absolute == 0L ? -1L : -absolute;
   }

   private static long hash64(String text) {
      long hash = -3750763034362895579L;
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

      for (byte value : bytes) {
         hash ^= value & 255;
         hash *= 1099511628211L;
      }

      return hash == 0L ? 1L : hash;
   }

   
   private static String asString(Object value) {
      return value == null ? null : String.valueOf(value);
   }

   
   private static String asString(JsonElement value) {
      if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
         try {
            return value.getAsString();
         } catch (RuntimeException var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private Path cachePathFor(TellusOsmRoadSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".mvt.gz");
   }

   private Path parsedCachePathFor(TellusOsmRoadSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".pm.parsed.bin");
   }

   
   private static TellusOsmRoadSource.GeoBounds geoBoundsForBlockArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, WorldProjection projection
   ) {
      if (projection.worldScale() <= 0.0) {
         return null;
      } else {
         int margin = Math.max(0, marginBlocks);
         double west = projection.blockXToLon(Math.min(minBlockX, maxBlockX) - margin);
         double east = projection.blockXToLon(Math.max(minBlockX, maxBlockX) + margin);
         double north = clampLat(projection.blockZToLat(Math.min(minBlockZ, maxBlockZ) - margin));
         double south = clampLat(projection.blockZToLat(Math.max(minBlockZ, maxBlockZ) + margin));
         if (south > north) {
            double swap = south;
            south = north;
            north = swap;
         }

         return new TellusOsmRoadSource.GeoBounds(south, west, north, east);
      }
   }

   private static List<TellusOsmRoadSource.TileKey> tileKeysForBounds(TellusOsmRoadSource.GeoBounds bounds, int zoom) {
      int tilesPerAxis = 1 << zoom;
      int minX = Mth.clamp(lonToTileX(bounds.west(), zoom), 0, tilesPerAxis - 1);
      int maxX = Mth.clamp(lonToTileX(bounds.east(), zoom), 0, tilesPerAxis - 1);
      int minY = Mth.clamp(latToTileY(bounds.north(), zoom), 0, tilesPerAxis - 1);
      int maxY = Mth.clamp(latToTileY(bounds.south(), zoom), 0, tilesPerAxis - 1);
      if (maxY < minY) {
         return List.of();
      }

      int columns = bounds.wrapsLongitude() ? tilesPerAxis - minX + maxX + 1 : maxX - minX + 1;
      if (columns <= 0) {
         return List.of();
      }
      List<TellusOsmRoadSource.TileKey> keys = new ArrayList<>(columns * (maxY - minY + 1));
      for (int y = minY; y <= maxY; y++) {
         if (bounds.wrapsLongitude()) {
            addTileKeys(keys, zoom, y, minX, tilesPerAxis - 1);
            addTileKeys(keys, zoom, y, 0, maxX);
         } else {
            addTileKeys(keys, zoom, y, minX, maxX);
         }
      }
      return keys;
   }

   private static void addTileKeys(
      List<TellusOsmRoadSource.TileKey> keys, int zoom, int tileY, int minTileX, int maxTileX
   ) {
      for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
         keys.add(new TellusOsmRoadSource.TileKey(zoom, tileX, tileY));
      }
   }

   
   private static TellusOsmRoadSource.TileKey tileKeyForBlock(double blockX, double blockZ, WorldProjection projection, int zoom) {
      if (projection.worldScale() <= 0.0) {
         return null;
      } else {
         double lon = clampLon(projection.blockXToLon(blockX));
         double lat = clampLat(projection.blockZToLat(blockZ));
         double n = tilesPerAxis(zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(Math.toRadians(lat)) + 1.0 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2.0 * n;
         return !(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n) ? new TellusOsmRoadSource.TileKey(zoom, Mth.floor(x), Mth.floor(y)) : null;
      }
   }

   private static TellusOsmRoadSource.TileGeoBounds tileBounds(TellusOsmRoadSource.TileKey key) {
      double n = tilesPerAxis(key.zoom());
      double west = key.x() / n * 360.0 - 180.0;
      double east = (key.x() + 1.0) / n * 360.0 - 180.0;
      double north = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * key.y() / n))));
      double south = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (key.y() + 1.0) / n))));
      return new TellusOsmRoadSource.TileGeoBounds(south, west, north, east);
   }

   private static int lonToTileX(double lon, int zoom) {
      double n = tilesPerAxis(zoom);
      double x = (clampLon(lon) + 180.0) / 360.0 * n;
      if (x >= n) {
         x = n - 1.0;
      }

      return Mth.floor(x);
   }

   private static int latToTileY(double lat, int zoom) {
      double clampedLat = clampLat(lat);
      double n = tilesPerAxis(zoom);
      double latRad = Math.toRadians(clampedLat);
      double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
      if (y >= n) {
         y = n - 1.0;
      }

      return Mth.floor(y);
   }

   private static double clampLat(double latitude) {
      return Mth.clamp(latitude, MIN_LAT, MAX_LAT);
   }

   private static double clampLon(double longitude) {
      return Mth.clamp(longitude, MIN_LON, MAX_LON);
   }

   private static double tilesPerAxis(int zoom) {
      return (double)(1 << zoom);
   }

   
   private static JsonArray getArray(JsonObject object, String key) {
      JsonElement value = object.get(key);
      return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            int parsed = Integer.parseInt(value);
            return Mth.clamp(parsed, minInclusive, maxInclusive);
         } catch (NumberFormatException var6) {
            Tellus.LOGGER.debug("Invalid integer system property {}='{}', using {}", new Object[]{key, value, defaultValue});
            return defaultValue;
         }
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.OSM;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
      this.pendingAsyncLoads.clear();
      this.tileLoadFailures.clear();
   }

   private record GeoPoint(double lon, double lat) {
   }

   private record ScopeRange(double start, double end) {
   }

   private record RoadDescriptor(RoadMode mode, int bridgeLevel, String roadSurface, String subclass, double widthMeters, int lanes, boolean laneMarkings) {
   }

   private record LineGeometry(double[] longitudes, double[] latitudes, double[] distances, double totalLength) {
      private static TellusOsmRoadSource.LineGeometry create(double[] longitudes, double[] latitudes) {
         if (longitudes == null || latitudes == null || longitudes.length != latitudes.length || longitudes.length < 2) {
            return null;
         } else {
            double[] cleanedLon = new double[longitudes.length];
            double[] cleanedLat = new double[latitudes.length];
            int count = 0;

            for (int i = 0; i < longitudes.length; i++) {
               double lon = longitudes[i];
               double lat = latitudes[i];
               if (Double.isFinite(lon)
                  && Double.isFinite(lat)
                  && (count <= 0 || !(Math.abs(cleanedLon[count - 1] - lon) < POINT_EPSILON) || !(Math.abs(cleanedLat[count - 1] - lat) < POINT_EPSILON))) {
                  cleanedLon[count] = lon;
                  cleanedLat[count] = lat;
                  count++;
               }
            }

            if (count < 2) {
               return null;
            } else {
               double[] trimmedLon = count == cleanedLon.length ? cleanedLon : Arrays.copyOf(cleanedLon, count);
               double[] trimmedLat = count == cleanedLat.length ? cleanedLat : Arrays.copyOf(cleanedLat, count);
               double[] distances = new double[count];
               double totalLength = 0.0;

               for (int i = 1; i < count; i++) {
                  double dx = trimmedLon[i] - trimmedLon[i - 1];
                  double dy = trimmedLat[i] - trimmedLat[i - 1];
                  totalLength += Math.sqrt(dx * dx + dy * dy);
                  distances[i] = totalLength;
               }

               return totalLength <= 1.0E-9 ? null : new TellusOsmRoadSource.LineGeometry(trimmedLon, trimmedLat, distances, totalLength);
            }
         }
      }
   }

   private record GeoBounds(double south, double west, double north, double east) {
      private boolean wrapsLongitude() {
         return this.east < this.west;
      }
   }

   private record LineString(List<TellusOsmRoadSource.TilePoint> points, double lengthScore) {
   }

   public record RoadQueryResult(List<RoadFeature> features, boolean hadCacheMiss) {
      public RoadQueryResult(List<RoadFeature> features, boolean hadCacheMiss) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMiss = hadCacheMiss;
      }
   }

   public record RoadAreaQueryResult(List<RoadAreaFeature> features, boolean hadCacheMiss) {
      public RoadAreaQueryResult(List<RoadAreaFeature> features, boolean hadCacheMiss) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMiss = hadCacheMiss;
      }
   }

   private record TileGeoBounds(double south, double west, double north, double east) {
   }

   private record TileKey(int zoom, int x, int y) {
   }

   private record TileLookup(OverpassRoadTile tile, boolean cacheMiss) {
   }

   private record TilePoint(int x, int y) {
   }
}
