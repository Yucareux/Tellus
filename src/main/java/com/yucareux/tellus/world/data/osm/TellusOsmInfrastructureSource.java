package com.yucareux.tellus.world.data.osm;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import com.yucareux.tellus.platform.TellusPlatform;
import net.minecraft.util.Mth;

public final class TellusOsmInfrastructureSource implements TellusCacheHandle {
   private static final String PM_TILES_THEME = "base";
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double METERS_PER_DEGREE = 111319.49166666667;
   private static final int DEFAULT_TILE_EXTENT = 4096;
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.overture.infrastructure.connectTimeoutMs", 7000, 1, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.overture.infrastructure.readTimeoutMs", 20000, 1, 180000);
   private static final int DIRECTORY_CACHE_ENTRIES = intProperty("tellus.overture.infrastructure.dirCache", 256, 1, 8192);
   private static final int MAX_CACHE_TILES = intProperty("tellus.osm.infrastructure.cacheTiles", 256, 1, 8192);
   private static final int QUERY_ZOOM = intProperty("tellus.osm.infrastructure.queryZoom", 14, 0, 20);
   private static final int MAX_ASYNC_PREFETCH_LOADS = intProperty("tellus.osm.infrastructure.prefetchAsyncMax", 96, 0, 8192);
   private static final int FETCH_RETRY_ATTEMPTS = intProperty("tellus.overture.infrastructure.fetchRetries", 3, 1, 8);
   private static final int PMTILES_TILETYPE_MVT = 1;
   private static final String INFRASTRUCTURE_LAYER_NAME = "infrastructure";
   private static final byte[] EMPTY_TILE_PAYLOAD = new byte[0];

   private final Path cacheRoot;
   private final PmTilesRangeReader pmTilesReader;
   private final Object initLock = new Object();
   private final LoadingCache<TellusOsmInfrastructureSource.TileKey, OsmStreetLightTile> cache;
   private final Set<TellusOsmInfrastructureSource.TileKey> pendingAsyncLoads;
   private final Set<TellusOsmInfrastructureSource.TileKey> tileLoadFailures;
   private volatile int queryZoom = QUERY_ZOOM;
   private volatile boolean available;
   private volatile boolean initialized;

   public TellusOsmInfrastructureSource() {
      String pmTilesUrl = System.getProperty("tellus.overture.infrastructure.pmtiles");
      if (pmTilesUrl == null || pmTilesUrl.isBlank()) {
         pmTilesUrl = OvertureTileUrls.defaultThemeUrl(PM_TILES_THEME);
      }

      this.cacheRoot = TellusPlatform.gameDir()
         .resolve("tellus/cache/map/infrastructure")
         .resolve(OvertureTileUrls.cacheNamespace(pmTilesUrl));
      this.pmTilesReader = PmTilesRangeReader.shared(pmTilesUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, DIRECTORY_CACHE_ENTRIES);
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<TellusOsmInfrastructureSource.TileKey, OsmStreetLightTile>() {
         @Override
         public OsmStreetLightTile load(TellusOsmInfrastructureSource.TileKey key) {
            return TellusOsmInfrastructureSource.this.loadTile(key);
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

   public StreetLightQueryResult streetLightsForAreaWithStatus(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks, OsmQueryMode mode
   ) {
      StreetLightQueryResult result = this.roadPointsForAreaWithStatus(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks, mode);
      return new StreetLightQueryResult(filterByKind(result.features(), RoadPointKind.STREET_LIGHT), result.hadCacheMiss());
   }

   public StreetLightQueryResult roadPointsForAreaWithStatus(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks, OsmQueryMode mode
   ) {
      this.ensureInitialized();
      if (!this.available || projection.worldScale() <= 0.0) {
         return new StreetLightQueryResult(List.of(), false);
      }

      TellusOsmInfrastructureSource.GeoBounds bounds = geoBoundsForBlockArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, projection);
      if (bounds == null) {
         return new StreetLightQueryResult(List.of(), false);
      }

      List<TellusOsmInfrastructureSource.TileKey> keys = tileKeysForBounds(bounds, this.queryZoom);
      if (keys.isEmpty()) {
         return new StreetLightQueryResult(List.of(), false);
      }

      List<OsmStreetLightFeature> features = new ArrayList<>();
      boolean hadCacheMiss = false;
      for (TellusOsmInfrastructureSource.TileKey key : keys) {
         TellusOsmInfrastructureSource.TileLookup lookup = this.getTileLookup(key, mode);
         hadCacheMiss |= lookup.cacheMiss();
         OsmStreetLightTile tile = lookup.tile();
         if (!tile.isEmpty()) {
            if (bounds.wrapsLongitude()) {
               features.addAll(tile.featuresInBounds(bounds.south(), bounds.west(), bounds.north(), 180.0));
               features.addAll(tile.featuresInBounds(bounds.south(), -180.0, bounds.north(), bounds.east()));
            } else {
               features.addAll(tile.featuresInBounds(bounds.south(), bounds.west(), bounds.north(), bounds.east()));
            }
         }
      }

      return new StreetLightQueryResult(features, hadCacheMiss);
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
      List<TellusOsmInfrastructureSource.TileKey> keys = this.downloadAreaTileKeys(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks);
      if (keys.isEmpty()) {
         progress.accept(completedUnits, "Skipping OSM infrastructure tiles because the source is unavailable");
         return completedUnits + 1;
      }
      int startingUnits = completedUnits;
      progress.accept(completedUnits, "Downloading " + keys.size() + " OSM infrastructure source tiles");
      return ParallelDownloadRunner.run(
         ParallelDownloadRunner.scope("osm-infrastructure", TellusCacheRegistry.generation(TellusCacheDomain.OSM)),
         keys,
         completedUnits,
         this::downloadRawTile,
         (key, completed, phaseTotal) -> progress.accept(
            completed, "Cached OSM infrastructure tile " + (completed - startingUnits) + "/" + phaseTotal + " (" + key.zoom() + "/" + key.x() + "/" + key.y() + ")"
         )
      );
   }

   public int preloadAreaInputs(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks,
      int completedUnits, BiConsumer<Integer, String> progressConsumer
   ) {
      BiConsumer<Integer, String> progress = progressConsumer == null ? (completed, detail) -> {
      } : progressConsumer;
      List<TellusOsmInfrastructureSource.TileKey> keys = this.downloadAreaTileKeys(
         minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks
      );
      if (keys.isEmpty()) {
         progress.accept(completedUnits, "Skipping OSM infrastructure tiles because the source is unavailable");
         return completedUnits + 1;
      }
      int startingUnits = completedUnits;
      progress.accept(completedUnits, "Loading " + keys.size() + " OSM infrastructure source tiles");
      return ParallelDownloadRunner.run(
         ParallelDownloadRunner.scope("osm-infrastructure-memory", TellusCacheRegistry.generation(TellusCacheDomain.OSM)),
         keys,
         completedUnits,
         this::loadTileIntoCache,
         (key, completed, phaseTotal) -> progress.accept(
            completed, "Loaded OSM infrastructure tile " + (completed - startingUnits) + "/" + phaseTotal + " (" + key.zoom() + "/" + key.x() + "/" + key.y() + ")"
         )
      );
   }

   private void loadTileIntoCache(TellusOsmInfrastructureSource.TileKey key) {
      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
      try {
         if (this.tileLoadFailures.contains(key)) {
            this.cache.invalidate(key);
         }
         this.cache.get(key);
      } catch (Exception error) {
         this.tileLoadFailures.add(key);
         throw new RuntimeException("Failed to preload Overture infrastructure tile " + key, error);
      }
      if (Thread.currentThread().isInterrupted()) {
         throw new java.util.concurrent.CancellationException("Interrupted while preloading Overture infrastructure tile " + key);
      }
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation)) {
         this.cache.invalidate(key);
         throw new IllegalStateException("Discarded stale Overture infrastructure preload for " + key);
      }
   }

   private List<TellusOsmInfrastructureSource.TileKey> downloadAreaTileKeys(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks
   ) {
      this.ensureInitialized();
      if (!this.available || projection.worldScale() <= 0.0) {
         return List.of();
      }

      TellusOsmInfrastructureSource.GeoBounds bounds = geoBoundsForBlockArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, projection);
      return bounds == null ? List.of() : tileKeysForBounds(bounds, this.queryZoom);
   }

   public void prefetchTiles(double blockX, double blockZ, WorldProjection projection, int radius) {
      this.ensureInitialized();
      if (this.available && projection.worldScale() > 0.0 && radius > 0) {
         TellusOsmInfrastructureSource.TileKey center = tileKeyForBlock(blockX, blockZ, projection, this.queryZoom);
         if (center != null) {
            int tilesPerAxis = 1 << this.queryZoom;
            int minX = Math.max(0, center.x() - radius);
            int maxX = Math.min(tilesPerAxis - 1, center.x() + radius);
            int minY = Math.max(0, center.y() - radius);
            int maxY = Math.min(tilesPerAxis - 1, center.y() + radius);

            for (int tileY = minY; tileY <= maxY; tileY++) {
               for (int tileX = minX; tileX <= maxX; tileX++) {
                  this.getTileLookup(new TellusOsmInfrastructureSource.TileKey(this.queryZoom, tileX, tileY), OsmQueryMode.NON_BLOCKING);
               }
            }
         }
      }
   }

   private TellusOsmInfrastructureSource.TileLookup getTileLookup(TellusOsmInfrastructureSource.TileKey key, OsmQueryMode mode) {
      OsmStreetLightTile cached = this.cache.getIfPresent(key);
      if (cached != null) {
         if (!cached.isEmpty() || !this.tileLoadFailures.contains(key)) {
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_STREET_LIGHTS, OsmPerf.TileLoadPath.MEMORY);
            return new TellusOsmInfrastructureSource.TileLookup(cached, false);
         }

         this.cache.invalidate(key);
      }

      OsmQueryMode queryMode = ManagedTerrainNetworkPolicy.isCacheOnly()
         ? OsmQueryMode.BLOCKING
         : mode == null ? OsmQueryMode.BLOCKING : mode;
      if (queryMode == OsmQueryMode.NON_BLOCKING) {
         this.queueAsyncLoad(key);
         return new TellusOsmInfrastructureSource.TileLookup(OsmStreetLightTile.empty(), true);
      }

      try {
         return new TellusOsmInfrastructureSource.TileLookup(this.cache.get(key), false);
      } catch (Exception error) {
         Tellus.LOGGER.debug("Failed to load Overture infrastructure tile {}", key, error);
         this.tileLoadFailures.add(key);
         OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_STREET_LIGHTS, OsmPerf.TileLoadPath.FAILURE);
         return new TellusOsmInfrastructureSource.TileLookup(OsmStreetLightTile.empty(), true);
      }
   }

   private void queueAsyncLoad(TellusOsmInfrastructureSource.TileKey key) {
      if (MAX_ASYNC_PREFETCH_LOADS <= 0 || this.pendingAsyncLoads.size() < MAX_ASYNC_PREFETCH_LOADS) {
         if (this.pendingAsyncLoads.add(key)) {
            CompletableFuture.runAsync(() -> {
               try {
                  this.cache.get(key);
               } catch (Exception error) {
                  Tellus.LOGGER.debug("Async load failed for Overture infrastructure tile {}", key, error);
                  this.tileLoadFailures.add(key);
                  OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_STREET_LIGHTS, OsmPerf.TileLoadPath.FAILURE);
               } finally {
                  this.pendingAsyncLoads.remove(key);
               }
            });
         }
      }
   }

   private OsmStreetLightTile loadTile(TellusOsmInfrastructureSource.TileKey key) {
      this.ensureInitialized();
      if (!this.available) {
         return OsmStreetLightTile.empty();
      }

      TellusOsmInfrastructureSource.TileGeoBounds bounds = tileBounds(key);
      Path cachePath = this.cachePathFor(key);
      Path parsedCachePath = this.parsedCachePathFor(key);
      if (Files.exists(parsedCachePath)) {
         try {
            OsmStreetLightTile parsed = ParsedTileCodec.readStreetLightTile(parsedCachePath);
            this.tileLoadFailures.remove(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_STREET_LIGHTS, OsmPerf.TileLoadPath.PARSED_DISK);
            return parsed;
         } catch (RuntimeException | IOException error) {
            Tellus.LOGGER.debug("Invalid parsed Overture infrastructure cache tile {}, refetching", key, error);
            deleteQuietly(parsedCachePath);
         }
      }

      if (Files.exists(cachePath)) {
         try {
            byte[] payload = this.readCompressed(cachePath);
            OsmStreetLightTile parsed = this.parseVectorTile(payload, bounds, key);
            this.cacheParsedTile(parsedCachePath, parsed);
            this.tileLoadFailures.remove(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_STREET_LIGHTS, OsmPerf.TileLoadPath.RAW_DISK);
            return parsed;
         } catch (RuntimeException | IOException error) {
            Tellus.LOGGER.debug("Invalid Overture infrastructure cache tile {}, refetching", key, error);
            deleteQuietly(cachePath);
         }
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
      byte[] payload = this.fetchTilePayloadWithRetry(key);
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation)) {
         throw new RuntimeException("Discarded stale Overture infrastructure tile " + key);
      }

      OsmStreetLightTile parsed;
      try {
         parsed = this.parseVectorTile(payload, bounds, key);
      } catch (RuntimeException error) {
         Tellus.LOGGER.warn("Overture infrastructure parse failed for tile {}", key, error);
         this.tileLoadFailures.add(key);
         OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_STREET_LIGHTS, OsmPerf.TileLoadPath.FAILURE);
         throw new RuntimeException("Overture infrastructure parse failed for tile " + key, error);
      }

      if (!this.cacheTile(cachePath, payload, generation)) {
         throw new RuntimeException("Discarded stale Overture infrastructure cache write for tile " + key);
      }

      this.cacheParsedTile(parsedCachePath, parsed, generation);
      this.tileLoadFailures.remove(key);
      OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_STREET_LIGHTS, OsmPerf.TileLoadPath.NETWORK);
      return parsed;
   }

   private void downloadRawTile(TellusOsmInfrastructureSource.TileKey key) {
      Path cachePath = this.cachePathFor(key);
      if (Files.isRegularFile(cachePath)) {
         return;
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
      byte[] payload = this.fetchTilePayloadWithRetry(key);
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation) || !this.cacheTile(cachePath, payload, generation)) {
         throw new RuntimeException("Discarded stale Overture infrastructure cache write for " + key);
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
               Tellus.LOGGER.warn("Unexpected Overture infrastructure PMTiles tile type {}, expected MVT", header.tileType());
            }

            resolvedZoom = Mth.clamp(resolvedZoom, header.minZoom(), header.maxZoom());
            sourceAvailable = true;
         } catch (IOException error) {
            sourceAvailable = false;
            Tellus.LOGGER.warn("Overture infrastructure PMTiles unavailable, street lamps disabled", error);
         }

         this.queryZoom = resolvedZoom;
         this.available = sourceAvailable;
         this.initialized = true;
      }
   }

   private byte[] fetchTilePayloadWithRetry(TellusOsmInfrastructureSource.TileKey key) {
      RuntimeException lastFailure = null;
      for (int attempt = 1; attempt <= FETCH_RETRY_ATTEMPTS; attempt++) {
         try {
            byte[] fetched = this.pmTilesReader.getTileBytes(key.zoom(), key.x(), key.y());
            return fetched == null ? EMPTY_TILE_PAYLOAD : fetched;
         } catch (IOException error) {
            lastFailure = new RuntimeException("Overture infrastructure fetch failed for tile " + key, error);
         }
      }

      this.tileLoadFailures.add(key);
      OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_STREET_LIGHTS, OsmPerf.TileLoadPath.FAILURE);
      throw lastFailure == null ? new RuntimeException("Overture infrastructure fetch failed for tile " + key) : lastFailure;
   }

   private boolean cacheTile(Path cachePath, byte[] payload, long generation) {
      try {
         return TellusCacheFiles.writeIfCurrent(TellusCacheDomain.OSM, generation, cachePath, output -> {
            try (OutputStream gzipOutput = new GZIPOutputStream(output)) {
               gzipOutput.write(payload);
            }
         });
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache Overture infrastructure tile {}", cachePath, error);
         return false;
      }
   }

   private void cacheParsedTile(Path cachePath, OsmStreetLightTile tile) {
      this.cacheParsedTile(cachePath, tile, TellusCacheRegistry.generation(TellusCacheDomain.OSM));
   }

   private void cacheParsedTile(Path cachePath, OsmStreetLightTile tile, long generation) {
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation)) {
         throw new RuntimeException("Discarded stale Overture infrastructure parsed cache write");
      }

      try {
         ParsedTileCodec.writeStreetLightTile(cachePath, tile);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache parsed Overture infrastructure tile {}", cachePath, error);
      }
   }

   private byte[] readCompressed(Path path) throws IOException {
      try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
         return PmTilesSafety.readBounded(
            input, PmTilesSafety.MAX_DECOMPRESSED_TILE_BYTES, "Cached Overture infrastructure tile"
         );
      }
   }

   private OsmStreetLightTile parseVectorTile(byte[] payload, TellusOsmInfrastructureSource.TileGeoBounds bounds, TellusOsmInfrastructureSource.TileKey key) {
      if (payload.length == 0) {
         return OsmStreetLightTile.empty();
      }

      Tile tile;
      try {
         tile = Tile.parseFrom(payload);
      } catch (Exception error) {
         throw new RuntimeException("Failed to decode infrastructure MVT payload", error);
      }

      if (tile.getLayersCount() == 0) {
         return OsmStreetLightTile.empty();
      }

      List<OsmStreetLightFeature> features = new ArrayList<>();
      for (Layer layer : tile.getLayersList()) {
         if (INFRASTRUCTURE_LAYER_NAME.equals(layer.getName())) {
            int extent = layer.hasExtent() && layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_TILE_EXTENT;
            for (Feature feature : layer.getFeaturesList()) {
               if (feature.getType() == GeomType.POINT) {
                  features.addAll(this.parseFeature(feature, layer, key, extent));
               }
            }
         }
      }

      return features.isEmpty() ? OsmStreetLightTile.empty() : new OsmStreetLightTile(features, bounds.south(), bounds.west(), bounds.north(), bounds.east());
   }

   private List<OsmStreetLightFeature> parseFeature(Feature feature, Layer layer, TellusOsmInfrastructureSource.TileKey key, int extent) {
      Map<String, Object> tags = decodeTags(feature, layer);
      String subtype = nonBlank(asString(tags.get("subtype")));
      String classTag = nonBlank(asString(tags.get("class")));
      RoadPointKind kind = RoadPointKind.fromInfrastructureClass(classTag);
      if (kind == null || !isRoadPointSubtype(subtype)) {
         return List.of();
      }

      List<TellusOsmInfrastructureSource.TilePoint> points = decodePoints(feature.getGeometryList());
      if (points.isEmpty()) {
         return List.of();
      }

      long baseId = resolveFeatureId(feature, tags);
      List<OsmStreetLightFeature> features = new ArrayList<>(points.size());
      for (int i = 0; i < points.size(); i++) {
         TellusOsmInfrastructureSource.TilePoint point = points.get(i);
         double lon = tilePointToLon(key.zoom(), key.x(), (double)point.x() / extent);
         double lat = tilePointToLat(key.zoom(), key.y(), (double)point.y() / extent);
         if (Double.isFinite(lat) && Double.isFinite(lon) && lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON) {
            long id = points.size() == 1 ? baseId : mixId(baseId, i);
            features.add(new OsmStreetLightFeature(id, lon, lat, kind));
         }
      }

      return features.isEmpty() ? List.of() : features;
   }

   private static boolean isRoadPointSubtype(String subtype) {
      return subtype == null
         || subtype.isBlank()
         || "transportation".equalsIgnoreCase(subtype)
         || "transit".equalsIgnoreCase(subtype)
         || "amenity".equalsIgnoreCase(subtype)
         || "barrier".equalsIgnoreCase(subtype)
         || "infrastructure".equalsIgnoreCase(subtype);
   }

   private static List<OsmStreetLightFeature> filterByKind(List<OsmStreetLightFeature> features, RoadPointKind kind) {
      if (features == null || features.isEmpty()) {
         return List.of();
      }

      List<OsmStreetLightFeature> matches = new ArrayList<>();
      for (OsmStreetLightFeature feature : features) {
         if (feature.kind() == kind) {
            matches.add(feature);
         }
      }
      return matches.isEmpty() ? List.of() : matches;
   }

   private static List<TellusOsmInfrastructureSource.TilePoint> decodePoints(List<Integer> geometry) {
      if (geometry == null || geometry.isEmpty()) {
         return List.of();
      }

      List<TellusOsmInfrastructureSource.TilePoint> points = new ArrayList<>();
      int cursorX = 0;
      int cursorY = 0;
      int cursor = 0;
      while (cursor < geometry.size()) {
         int commandAndCount = geometry.get(cursor++);
         int command = commandAndCount & 7;
         int count = commandAndCount >>> 3;
         if (command != 1 || count <= 0) {
            if (command == 2) {
               cursor += count * 2;
               continue;
            }

            break;
         }

         for (int i = 0; i < count; i++) {
            if (cursor + 1 >= geometry.size()) {
               return points;
            }

            cursorX += zigZagDecode(geometry.get(cursor++));
            cursorY += zigZagDecode(geometry.get(cursor++));
            points.add(new TellusOsmInfrastructureSource.TilePoint(cursorX, cursorY));
         }
      }

      return points;
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
      }

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
      if (feature.hasId() && feature.getId() != 0L) {
         return feature.getId();
      }

      long idFromTag = parseLongId(tags.get("id"));
      if (idFromTag != 0L) {
         return idFromTag;
      }

      return parseLongId(tags.get("sources"));
   }

   private static long parseLongId(Object value) {
      if (value == null) {
         return 0L;
      } else if (value instanceof Number number) {
         return number.longValue();
      }

      String text = String.valueOf(value).trim();
      if (text.isEmpty()) {
         return 0L;
      }

      try {
         return Long.parseLong(text);
      } catch (NumberFormatException error) {
         try {
            UUID uuid = UUID.fromString(text);
            long mixed = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
            return mixed == 0L ? 1L : mixed;
         } catch (IllegalArgumentException uuidError) {
            return hash64(text);
         }
      }
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

   private static long mixId(long baseId, int index) {
      long value = baseId == 0L ? 1L : baseId;
      value ^= (long)index * -7046029254386353131L;
      value ^= value >>> 33;
      return value == 0L ? index + 1L : value;
   }

   private static String asString(Object value) {
      return value == null ? null : String.valueOf(value);
   }

   private static String nonBlank(String value) {
      return value == null || value.isBlank() ? null : value.trim();
   }

   private Path cachePathFor(TellusOsmInfrastructureSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".mvt.gz");
   }

   private Path parsedCachePathFor(TellusOsmInfrastructureSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".pm.parsed.bin");
   }

   private static TellusOsmInfrastructureSource.GeoBounds geoBoundsForBlockArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, WorldProjection projection
   ) {
      if (projection.worldScale() <= 0.0) {
         return null;
      }

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

      return new TellusOsmInfrastructureSource.GeoBounds(south, west, north, east);
   }

   private static List<TellusOsmInfrastructureSource.TileKey> tileKeysForBounds(TellusOsmInfrastructureSource.GeoBounds bounds, int zoom) {
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
      List<TellusOsmInfrastructureSource.TileKey> keys = new ArrayList<>(columns * (maxY - minY + 1));
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
      List<TellusOsmInfrastructureSource.TileKey> keys, int zoom, int tileY, int minTileX, int maxTileX
   ) {
      for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
         keys.add(new TellusOsmInfrastructureSource.TileKey(zoom, tileX, tileY));
      }
   }

   private static TellusOsmInfrastructureSource.TileKey tileKeyForBlock(double blockX, double blockZ, WorldProjection projection, int zoom) {
      if (projection.worldScale() <= 0.0) {
         return null;
      }

      double lon = clampLon(projection.blockXToLon(blockX));
      double lat = clampLat(projection.blockZToLat(blockZ));
      double n = tilesPerAxis(zoom);
      double x = (lon + 180.0) / 360.0 * n;
      double y = (1.0 - Math.log(Math.tan(Math.toRadians(lat)) + 1.0 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2.0 * n;
      return x < 0.0 || y < 0.0 || x >= n || y >= n ? null : new TellusOsmInfrastructureSource.TileKey(zoom, Mth.floor(x), Mth.floor(y));
   }

   private static TellusOsmInfrastructureSource.TileGeoBounds tileBounds(TellusOsmInfrastructureSource.TileKey key) {
      double n = tilesPerAxis(key.zoom());
      double west = key.x() / n * 360.0 - 180.0;
      double east = (key.x() + 1.0) / n * 360.0 - 180.0;
      double north = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * key.y() / n))));
      double south = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (key.y() + 1.0) / n))));
      return new TellusOsmInfrastructureSource.TileGeoBounds(south, west, north, east);
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

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }

      try {
         int parsed = Integer.parseInt(value);
         return Mth.clamp(parsed, minInclusive, maxInclusive);
      } catch (NumberFormatException error) {
         Tellus.LOGGER.debug("Invalid integer system property {}='{}', using {}", new Object[]{key, value, defaultValue});
         return defaultValue;
      }
   }

   private static void deleteQuietly(Path path) {
      try {
         Files.deleteIfExists(path);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to delete invalid Overture infrastructure cache tile {}", path, error);
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

   public record StreetLightQueryResult(List<OsmStreetLightFeature> features, boolean hadCacheMiss) {
      public StreetLightQueryResult(List<OsmStreetLightFeature> features, boolean hadCacheMiss) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMiss = hadCacheMiss;
      }
   }

   private record GeoBounds(double south, double west, double north, double east) {
      private boolean wrapsLongitude() {
         return this.east < this.west;
      }
   }

   private record TileGeoBounds(double south, double west, double north, double east) {
   }

   private record TileKey(int zoom, int x, int y) {
   }

   private record TileLookup(OsmStreetLightTile tile, boolean cacheMiss) {
   }

   private record TilePoint(int x, int y) {
   }
}
