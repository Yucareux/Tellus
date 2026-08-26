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

public final class TellusOsmBuildingSource implements TellusCacheHandle {
   private static final String PM_TILES_THEME = "buildings";
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double METERS_PER_DEGREE = 111319.49166666667;
   private static final double POINT_EPSILON = 1.0E-9;
   private static final int DEFAULT_TILE_EXTENT = 4096;
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.overture.buildings.connectTimeoutMs", 30000, 1, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.overture.buildings.readTimeoutMs", 60000, 1, 180000);
   private static final int DIRECTORY_CACHE_ENTRIES = intProperty("tellus.overture.buildings.dirCache", 256, 1, 8192);
   private static final int MAX_CACHE_TILES = intProperty("tellus.osm.buildings.cacheTiles", 256, 1, 8192);
   private static final int QUERY_ZOOM = intProperty("tellus.osm.buildings.queryZoom", 14, 0, 20);
   private static final int MAX_ASYNC_PREFETCH_LOADS = intProperty("tellus.osm.buildings.prefetchAsyncMax", 96, 0, 8192);
   private static final int FETCH_RETRY_ATTEMPTS = intProperty("tellus.overture.buildings.fetchRetries", 3, 1, 8);
   private static final int PMTILES_TILETYPE_MVT = 1;
   private static final String BUILDING_LAYER_NAME = "building";
   private static final String BUILDING_PART_LAYER_NAME = "building_part";
   private static final byte[] EMPTY_TILE_PAYLOAD = new byte[0];
   private final Path cacheRoot;
   private final PmTilesRangeReader pmTilesReader;
   private final Object initLock = new Object();
   private final LoadingCache<TileKey, OsmBuildingTile> cache;
   private final Set<TileKey> pendingAsyncLoads;
   private final Set<TileKey> tileLoadFailures;
   private volatile int queryZoom = QUERY_ZOOM;
   private volatile boolean available;
   private volatile boolean initialized;

   public TellusOsmBuildingSource() {
      String pmTilesUrl = System.getProperty("tellus.overture.buildings.pmtiles");
      if (pmTilesUrl == null || pmTilesUrl.isBlank()) {
         pmTilesUrl = OvertureTileUrls.defaultThemeUrl(PM_TILES_THEME);
      }

      this.cacheRoot = TellusPlatform.gameDir()
         .resolve("tellus/cache/map/buildings")
         .resolve(OvertureTileUrls.cacheNamespace(pmTilesUrl));
      this.pmTilesReader = PmTilesRangeReader.shared(pmTilesUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, DIRECTORY_CACHE_ENTRIES);
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<TileKey, OsmBuildingTile>() {
         public OsmBuildingTile load(TileKey key) {
            return TellusOsmBuildingSource.this.loadTile(key);
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

   public BuildingQueryResult buildingsForAreaWithStatus(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks, OsmQueryMode mode
   ) {
      this.ensureInitialized();
      if (this.available && !(projection.worldScale() <= 0.0)) {
         GeoBounds bounds = geoBoundsForBlockArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, projection);
         if (bounds == null) {
            return new BuildingQueryResult(List.of(), false);
         } else {
            List<TileKey> keys = tileKeysForBounds(bounds, this.queryZoom);
            if (keys.isEmpty()) {
               return new BuildingQueryResult(List.of(), false);
            } else {
               List<OsmBuildingFeature> features = new ArrayList<>();
               boolean hadCacheMiss = false;

               for (TileKey key : keys) {
                  TileLookup lookup = this.getTileLookup(key, mode);
                  hadCacheMiss |= lookup.cacheMiss();
                  OsmBuildingTile tile = lookup.tile();
                  if (!tile.isEmpty()) {
                     if (bounds.wrapsLongitude()) {
                        features.addAll(tile.featuresInBounds(bounds.south(), bounds.west(), bounds.north(), 180.0));
                        features.addAll(tile.featuresInBounds(bounds.south(), -180.0, bounds.north(), bounds.east()));
                     } else {
                        features.addAll(tile.featuresInBounds(bounds.south(), bounds.west(), bounds.north(), bounds.east()));
                     }
                  }
               }

               return new BuildingQueryResult(features, hadCacheMiss);
            }
         }
      } else {
         return new BuildingQueryResult(List.of(), false);
      }
   }

   public List<OsmBuildingFeature> buildingsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks) {
      return this.buildingsForAreaWithStatus(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks, OsmQueryMode.BLOCKING).features();
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
      List<TileKey> keys = this.downloadAreaTileKeys(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks);
      if (keys.isEmpty()) {
         progress.accept(completedUnits, "Skipping OSM building tiles because the source is unavailable");
         return completedUnits + 1;
      }
      int startingUnits = completedUnits;
      progress.accept(completedUnits, "Downloading " + keys.size() + " OSM building source tiles");
      return ParallelDownloadRunner.run(ParallelDownloadRunner.scope("osm-buildings", TellusCacheRegistry.generation(TellusCacheDomain.OSM)), keys, completedUnits, this::downloadRawTile, (key, completed, phaseTotal) -> progress.accept(
         completed, "Cached OSM building tile " + (completed - startingUnits) + "/" + phaseTotal + " (" + key.zoom() + "/" + key.x() + "/" + key.y() + ")"
      ));
   }

   public int preloadAreaInputs(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks,
      int completedUnits, BiConsumer<Integer, String> progressConsumer
   ) {
      BiConsumer<Integer, String> progress = progressConsumer == null ? (completed, detail) -> {
      } : progressConsumer;
      List<TileKey> keys = this.downloadAreaTileKeys(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection, marginBlocks);
      if (keys.isEmpty()) {
         progress.accept(completedUnits, "Skipping OSM building tiles because the source is unavailable");
         return completedUnits + 1;
      }
      int startingUnits = completedUnits;
      progress.accept(completedUnits, "Loading " + keys.size() + " OSM building source tiles");
      return ParallelDownloadRunner.run(ParallelDownloadRunner.scope("osm-buildings-memory", TellusCacheRegistry.generation(TellusCacheDomain.OSM)), keys, completedUnits, this::loadTileIntoCache, (key, completed, phaseTotal) -> progress.accept(
         completed, "Loaded OSM building tile " + (completed - startingUnits) + "/" + phaseTotal + " (" + key.zoom() + "/" + key.x() + "/" + key.y() + ")"
      ));
   }

   private void loadTileIntoCache(TileKey key) {
      try {
         this.cache.get(key);
      } catch (Exception error) {
         this.tileLoadFailures.add(key);
         throw new RuntimeException("Failed to preload Overture building tile " + key, error);
      }
   }

   private List<TileKey> downloadAreaTileKeys(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, int marginBlocks
   ) {
      this.ensureInitialized();
      if (!this.available || projection.worldScale() <= 0.0) {
         return List.of();
      }

      GeoBounds bounds = geoBoundsForBlockArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, projection);
      return bounds == null ? List.of() : tileKeysForBounds(bounds, this.queryZoom);
   }

   public void prefetchTiles(double blockX, double blockZ, WorldProjection projection, int radius) {
      this.ensureInitialized();
      if (this.available && !(projection.worldScale() <= 0.0) && radius > 0) {
         TileKey center = tileKeyForBlock(blockX, blockZ, projection, this.queryZoom);
         if (center != null) {
            int tilesPerAxis = 1 << this.queryZoom;
            int minX = Math.max(0, center.x() - radius);
            int maxX = Math.min(tilesPerAxis - 1, center.x() + radius);
            int minY = Math.max(0, center.y() - radius);
            int maxY = Math.min(tilesPerAxis - 1, center.y() + radius);

            for (int tileY = minY; tileY <= maxY; tileY++) {
               for (int tileX = minX; tileX <= maxX; tileX++) {
                  this.getTile(new TileKey(this.queryZoom, tileX, tileY), OsmQueryMode.NON_BLOCKING);
               }
            }
         }
      }
   }

   private OsmBuildingTile getTile(TileKey key, OsmQueryMode mode) {
      return this.getTileLookup(key, mode).tile();
   }

   private TileLookup getTileLookup(TileKey key, OsmQueryMode mode) {
      OsmBuildingTile cached = this.cache.getIfPresent(key);
      if (cached != null) {
         if (!cached.isEmpty() || !this.tileLoadFailures.contains(key)) {
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_BUILDINGS, OsmPerf.TileLoadPath.MEMORY);
            return new TileLookup(cached, false);
         }

         this.cache.invalidate(key);
      }

      OsmQueryMode queryMode = ManagedTerrainNetworkPolicy.isCacheOnly()
         ? OsmQueryMode.BLOCKING
         : mode == null ? OsmQueryMode.BLOCKING : mode;
      if (queryMode == OsmQueryMode.NON_BLOCKING) {
         this.queueAsyncLoad(key);
         return new TileLookup(OsmBuildingTile.empty(), true);
      } else {
         try {
            return new TileLookup(this.cache.get(key), false);
         } catch (Exception error) {
            Tellus.LOGGER.debug("Failed to load Overture building tile {}", key, error);
            this.tileLoadFailures.add(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_BUILDINGS, OsmPerf.TileLoadPath.FAILURE);
            return new TileLookup(OsmBuildingTile.empty(), true);
         }
      }
   }

   private void queueAsyncLoad(TileKey key) {
      if (MAX_ASYNC_PREFETCH_LOADS <= 0 || this.pendingAsyncLoads.size() < MAX_ASYNC_PREFETCH_LOADS) {
         if (this.pendingAsyncLoads.add(key)) {
            MinecraftVersionCompat.backgroundExecutor().execute(() -> {
               try {
                  this.cache.get(key);
               } catch (Exception error) {
                  Tellus.LOGGER.debug("Async load failed for Overture building tile {}", key, error);
                  this.tileLoadFailures.add(key);
                  OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_BUILDINGS, OsmPerf.TileLoadPath.FAILURE);
               } finally {
                  this.pendingAsyncLoads.remove(key);
               }
            });
         }
      }
   }

   private OsmBuildingTile loadTile(TileKey key) {
      this.ensureInitialized();
      if (!this.available) {
         return OsmBuildingTile.empty();
      } else {
         TileGeoBounds bounds = tileBounds(key);
         Path cachePath = this.cachePathFor(key);
         Path parsedCachePath = this.parsedCachePathFor(key);
         if (Files.exists(parsedCachePath)) {
            try {
               OsmBuildingTile parsed = ParsedTileCodec.readBuildingTile(parsedCachePath);
               this.tileLoadFailures.remove(key);
               OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_BUILDINGS, OsmPerf.TileLoadPath.PARSED_DISK);
               return parsed;
            } catch (RuntimeException | IOException error) {
               Tellus.LOGGER.debug("Invalid parsed Overture building cache tile {}, refetching", key, error);

               try {
                  Files.deleteIfExists(parsedCachePath);
               } catch (IOException deleteError) {
                  Tellus.LOGGER.debug("Failed to delete invalid parsed Overture building cache tile {}", parsedCachePath, deleteError);
               }
            }
         }

         if (Files.exists(cachePath)) {
            try {
               byte[] payload = this.readCompressed(cachePath);
               OsmBuildingTile parsed = this.parseVectorTile(payload, bounds, key);
               this.cacheParsedTile(parsedCachePath, parsed);
               this.tileLoadFailures.remove(key);
               OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_BUILDINGS, OsmPerf.TileLoadPath.RAW_DISK);
               return parsed;
            } catch (RuntimeException | IOException error) {
               Tellus.LOGGER.debug("Invalid Overture building cache tile {}, refetching", key, error);

               try {
                  Files.deleteIfExists(cachePath);
               } catch (IOException deleteError) {
                  Tellus.LOGGER.debug("Failed to delete invalid Overture building cache tile {}", cachePath, deleteError);
               }
            }
         }

         long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
         byte[] payload = this.fetchTilePayloadWithRetry(key);
         if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation)) {
            throw new RuntimeException("Discarded stale Overture building tile " + key);
         }

         OsmBuildingTile parsed;

         try {
            parsed = this.parseVectorTile(payload, bounds, key);
         } catch (RuntimeException error) {
            Tellus.LOGGER.warn("Overture building parse failed for tile {}", key, error);
            this.tileLoadFailures.add(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_BUILDINGS, OsmPerf.TileLoadPath.FAILURE);
            throw new RuntimeException("Overture building parse failed for tile " + key, error);
         }

         if (!this.cacheTile(cachePath, payload, generation)) {
            throw new RuntimeException("Discarded stale Overture building cache write for tile " + key);
         }

         this.cacheParsedTile(parsedCachePath, parsed);
         this.tileLoadFailures.remove(key);
         OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_BUILDINGS, OsmPerf.TileLoadPath.NETWORK);
         return parsed;
      }
   }

   private void downloadRawTile(TileKey key) {
      Path cachePath = this.cachePathFor(key);
      if (Files.isRegularFile(cachePath)) {
         return;
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
      byte[] payload = this.fetchTilePayloadWithRetry(key);
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation) || !this.cacheTile(cachePath, payload, generation)) {
         throw new RuntimeException("Discarded stale Overture building cache write for " + key);
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
               Tellus.LOGGER.warn("Unexpected Overture building PMTiles tile type {}, expected MVT", header.tileType());
            }

            resolvedZoom = Mth.clamp(resolvedZoom, header.minZoom(), header.maxZoom());
            sourceAvailable = true;
         } catch (IOException error) {
            sourceAvailable = false;
            Tellus.LOGGER.warn("Overture building PMTiles unavailable, OSM buildings disabled", error);
         }

         this.queryZoom = resolvedZoom;
         this.available = sourceAvailable;
         this.initialized = true;
      }
   }

   private byte[] fetchTilePayloadWithRetry(TileKey key) {
      RuntimeException lastFailure = null;

      for (int attempt = 1; attempt <= FETCH_RETRY_ATTEMPTS; attempt++) {
         try {
            byte[] fetched = this.pmTilesReader.getTileBytes(key.zoom(), key.x(), key.y());
            return fetched == null ? EMPTY_TILE_PAYLOAD : fetched;
         } catch (IOException error) {
            lastFailure = new RuntimeException("Overture building fetch failed for tile " + key, error);
            if (attempt < FETCH_RETRY_ATTEMPTS) {
               continue;
            }
            break;
         }
      }

      this.tileLoadFailures.add(key);
      OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_BUILDINGS, OsmPerf.TileLoadPath.FAILURE);
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
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache Overture building tile {}", cachePath, error);
         return false;
      }
   }

   private void cacheParsedTile(Path cachePath, OsmBuildingTile tile) {
      try {
         ParsedTileCodec.writeBuildingTile(cachePath, tile);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache parsed Overture building tile {}", cachePath, error);
      }
   }

   private byte[] readCompressed(Path path) throws IOException {
      try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
         return PmTilesSafety.readBounded(
            input, PmTilesSafety.MAX_DECOMPRESSED_TILE_BYTES, "Cached Overture building tile"
         );
      }
   }

   private OsmBuildingTile parseVectorTile(byte[] payload, TileGeoBounds bounds, TileKey key) {
      if (payload.length == 0) {
         return OsmBuildingTile.empty();
      } else {
         Tile tile;

         try {
            tile = Tile.parseFrom(payload);
         } catch (Exception error) {
            throw new RuntimeException("Failed to decode building MVT payload", error);
         }

         if (tile.getLayersCount() == 0) {
            return OsmBuildingTile.empty();
         } else {
            List<OsmBuildingFeature> features = new ArrayList<>();

            for (Layer layer : tile.getLayersList()) {
               String name = layer.getName();
               if (BUILDING_PART_LAYER_NAME.equals(name) || BUILDING_LAYER_NAME.equals(name)) {
                  int extent = layer.hasExtent() && layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_TILE_EXTENT;

                  for (Feature feature : layer.getFeaturesList()) {
                     if (feature.getType() == GeomType.POLYGON) {
                        OsmBuildingFeature parsed = this.parseFeature(feature, layer, key, extent, BUILDING_PART_LAYER_NAME.equals(name));
                        if (parsed != null) {
                           features.add(parsed);
                        }
                     }
                  }
               }
            }

            return features.isEmpty() ? OsmBuildingTile.empty() : new OsmBuildingTile(features, bounds.south(), bounds.west(), bounds.north(), bounds.east());
         }
      }
   }

   private OsmBuildingFeature parseFeature(Feature feature, Layer layer, TileKey key, int extent, boolean buildingPart) {
      Map<String, Object> tags = decodeTags(feature, layer);
      if (isTruthy(asString(tags.get("is_underground")))) {
         return null;
      } else {
         OsmBuildingKind kind = buildingPart ? OsmBuildingKind.PART : OsmBuildingKind.FOOTPRINT;
         boolean hasParts = isTruthy(asString(tags.get("has_parts")));
         if (!buildingPart && hasParts) {
            return null;
         } else {
            double heightMeters = buildingPart ? resolvePartHeightMeters(tags) : resolveFootprintHeightMeters(tags);
            double minHeightMeters = buildingPart ? resolveMinHeightMeters(tags) : 0.0;
            if (!(heightMeters > 0.0) || heightMeters <= minHeightMeters) {
               return null;
            } else {
               List<List<TilePoint>> rings = decodePolygonRings(feature.getGeometryList());
               if (rings.isEmpty()) {
                  return null;
               } else {
                  double[][] longitudes = new double[rings.size()][];
                  double[][] latitudes = new double[rings.size()][];

                  for (int part = 0; part < rings.size(); part++) {
                     List<TilePoint> ring = rings.get(part);
                     int points = ring.size();
                     double[] lonPart = new double[points];
                     double[] latPart = new double[points];
                     int count = 0;
                     double previousLon = Double.NaN;
                     double previousLat = Double.NaN;

                     for (TilePoint point : ring) {
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

                  long featureId = resolveFeatureId(feature, tags);
                  String buildingId = nonBlank(asString(tags.get(kind == OsmBuildingKind.PART ? "building_id" : "id")));
                  OsmBuildingMetadata metadata = resolveMetadata(tags, heightMeters);
                  return new OsmBuildingFeature(kind, featureId, buildingId, hasParts, metadata, heightMeters, Math.max(0.0, minHeightMeters), longitudes, latitudes);
               }
            }
         }
      }
   }

   private static OsmBuildingMetadata resolveMetadata(Map<String, Object> tags, double heightMeters) {
      int floorCount = resolveFloorCount(tags, heightMeters);
      return new OsmBuildingMetadata(
         firstNonBlank(tags, "class", "building_class", "category", "kind"),
         firstNonBlank(tags, "subtype", "building_subtype", "building", "type"),
         firstNonBlank(tags, "building_use", "building:use", "use", "function", "subtype"),
         firstNonBlank(tags, "@name", "name"),
         floorCount,
         firstNonBlank(tags, "roof_shape", "roof:shape"),
         firstNonBlank(tags, "roof_material", "roof:material"),
         firstNonBlank(tags, "roof:colour", "roof_color", "roof_colour"),
         firstNonBlank(tags, "facade_material", "facade:material", "building:material", "building_material", "material"),
         firstNonBlank(tags, "facade_color", "facade:colour", "facade_colour", "building:colour", "building_color", "building_colour"),
         firstNonBlank(tags, "amenity"),
         firstNonBlank(tags, "tourism"),
         firstNonBlank(tags, "office"),
         firstNonBlank(tags, "shop"),
         firstNonBlank(tags, "man_made"),
         firstNonBlank(tags, "historic"),
         firstNonBlank(tags, "building:part", "building_part"),
         resolveOptionalInt(tags, "roof:levels", "roof_levels"),
         resolveOptionalInt(tags, "min_level", "building:min_level", "min_floor")
      );
   }

   private static double resolveFootprintHeightMeters(Map<String, Object> tags) {
      Double height = parseDouble(tags.get("height"));
      if (height != null && height > 0.0) {
         return height;
      } else {
         Double floors = parseDouble(tags.get("num_floors"));
         if (floors != null && floors > 0.0) {
            return floors * 3.2;
         } else {
            return 6.0;
         }
      }
   }

   private static double resolvePartHeightMeters(Map<String, Object> tags) {
      Double height = parseDouble(tags.get("height"));
      if (height != null && height > 0.0) {
         return height;
      } else {
         Double floors = parseDouble(tags.get("num_floors"));
         if (floors != null && floors > 0.0) {
            return floors * 3.2;
         } else {
            return 4.0;
         }
      }
   }

   private static double resolveMinHeightMeters(Map<String, Object> tags) {
      Double minHeight = parseDouble(tags.get("min_height"));
      if (minHeight != null && minHeight > 0.0) {
         return minHeight;
      } else {
         Double minFloor = parseDouble(tags.get("min_floor"));
         return minFloor != null && minFloor > 0.0 ? minFloor * 3.2 : 0.0;
      }
   }

   private static int resolveFloorCount(Map<String, Object> tags, double heightMeters) {
      Object[] candidates = new Object[]{
         tags.get("num_floors"), tags.get("floors"), tags.get("building_levels"), tags.get("building:levels"), tags.get("level")
      };

      for (Object candidate : candidates) {
         Double parsed = parseDouble(candidate);
         if (parsed != null && parsed > 0.0) {
            return Math.max(1, (int)Math.round(parsed));
         }
      }

      return Math.max(1, (int)Math.round(heightMeters / 3.2));
   }

   private static int resolveOptionalInt(Map<String, Object> tags, String... keys) {
      for (String key : keys) {
         Double parsed = parseDouble(tags.get(key));
         if (parsed != null && parsed > 0.0) {
            return Math.max(0, (int)Math.round(parsed));
         }
      }

      return 0;
   }

   private static List<List<TilePoint>> decodePolygonRings(List<Integer> geometry) {
      if (geometry != null && !geometry.isEmpty()) {
         List<List<TilePoint>> rings = new ArrayList<>();
         List<TilePoint> current = null;
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
                           return rings;
                        }

                        cursorX += zigZagDecode(geometry.get(cursor++));
                        cursorY += zigZagDecode(geometry.get(cursor++));
                        if (current != null && !current.isEmpty()) {
                           addRing(rings, current);
                        }

                        current = new ArrayList<>();
                        current.add(new TilePoint(cursorX, cursorY));
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
                        current.add(new TilePoint(cursorX, cursorY));
                     }
                     break;
                  case 7:
                     if (current != null && !current.isEmpty()) {
                        addRing(rings, current);
                        current = null;
                     }
                     break;
                  default:
                     return rings;
               }
            }
         }

         if (current != null && !current.isEmpty()) {
            addRing(rings, current);
         }

         return rings;
      } else {
         return List.of();
      }
   }

   private static void addRing(List<List<TilePoint>> rings, List<TilePoint> points) {
      List<TilePoint> cleaned = new ArrayList<>(points.size());

      for (TilePoint point : points) {
         if (cleaned.isEmpty() || !samePoint(cleaned.get(cleaned.size() - 1), point)) {
            cleaned.add(point);
         }
      }

      if (cleaned.size() >= 3) {
         TilePoint first = cleaned.get(0);
         TilePoint last = cleaned.get(cleaned.size() - 1);
         if (!samePoint(first, last)) {
            cleaned.add(first);
         }

         if (cleaned.size() >= 4) {
            rings.add(List.copyOf(cleaned));
         }
      }
   }

   private static boolean samePoint(TilePoint a, TilePoint b) {
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
               if (value != null) {
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
      } else if (value.hasIntValue()) {
         return value.getIntValue();
      } else if (value.hasSintValue()) {
         return value.getSintValue();
      } else if (value.hasUintValue()) {
         return value.getUintValue();
      } else if (value.hasFloatValue()) {
         return value.getFloatValue();
      } else if (value.hasDoubleValue()) {
         return value.getDoubleValue();
      } else if (value.hasBoolValue()) {
         return value.getBoolValue();
      } else {
         return null;
      }
   }

   private static long resolveFeatureId(Feature feature, Map<String, Object> tags) {
      Object sources = tags.get("sources");
      if (sources instanceof String stringSources && !stringSources.isBlank()) {
         Long resolved = tryParseSourcesId(stringSources);
         if (resolved != null) return resolved;
      }
      if (feature.hasId()) {
         return syntheticId(feature.getId());
      } else {
         Object[] candidates = new Object[]{tags.get("id"), tags.get("@id"), tags.get("building_id")};

         for (Object candidate : candidates) {
            Long resolved = tryResolveId(candidate);
            if (resolved != null) {
               return syntheticId(resolved);
            }
         }

         return syntheticId(hash64(String.valueOf(tags)));
      }
   }

   private static Long tryResolveId(Object value) {
      if (value == null) {
         return null;
      } else if (value instanceof Number number) {
         return number.longValue();
      } else {
         String text = String.valueOf(value).trim();
         if (text.isEmpty()) {
            return null;
         } else {
            try {
               return text.contains("-") ? hashUuid(text) : Long.parseLong(text);
            } catch (RuntimeException error) {
               return hash64(text);
            }
         }
      }
   }

   private static long hashUuid(String text) {
      UUID uuid = UUID.fromString(text);
      return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
   }

   private static Long tryParseSourcesId(String sources) {
      int cursor = 0;
      while (cursor < sources.length()) {
         int key = sources.indexOf("\"record_id\"", cursor);
         if (key < 0) return null;
         int colon = sources.indexOf(':', key + 11);
         int quote = colon < 0 ? -1 : sources.indexOf('"', colon + 1);
         int prefix = quote + 1;
         if (quote >= 0 && prefix < sources.length() && (sources.charAt(prefix) == 'w' || sources.charAt(prefix) == 'r')) {
            char elementType = sources.charAt(prefix);
            int start = prefix + 1;
            int end = start;
            while (end < sources.length() && Character.isDigit(sources.charAt(end))) end++;
            if (end > start) {
               try {
                  long id = Long.parseLong(sources.substring(start, end));
                  return elementType == 'r' ? Long.MAX_VALUE - id : id;
               } catch (NumberFormatException ignored) {
                  return null;
               }
            }
         }
         cursor = Math.max(key + 1, prefix);
      }
      return null;
   }
   private static long syntheticId(long value) {
      if (value == Long.MIN_VALUE) return Long.MIN_VALUE + 1L;
      long absolute = Math.abs(value);
      return absolute == 0L ? -1L : -absolute;
   }

   private static long hash64(String text) {
      long hash = -3750763034362895579L;

      for (int i = 0; i < text.length(); i++) {
         hash ^= text.charAt(i);
         hash *= 1099511628211L;
      }

      return hash;
   }

   private static Double parseDouble(Object value) {
      if (value == null) {
         return null;
      } else if (value instanceof Number number) {
         return number.doubleValue();
      } else {
         String text = String.valueOf(value);
         if (text.isBlank()) {
            return null;
         } else {
            try {
               return Double.parseDouble(text.trim());
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

   private static String asString(Object value) {
      return value == null ? null : String.valueOf(value);
   }

   private static String firstNonBlank(Map<String, Object> tags, String... keys) {
      for (String key : keys) {
         String value = nonBlank(asString(tags.get(key)));
         if (value != null) {
            return value;
         }
      }

      return null;
   }

   private static String nonBlank(String value) {
      return value != null && !value.isBlank() ? value : null;
   }

   private Path cachePathFor(TileKey key) {
      return this.cacheRoot.resolve("raw").resolve(key.zoom() + "/" + key.x()).resolve(key.y() + ".mvt.gz");
   }

   private Path parsedCachePathFor(TileKey key) {
      return this.cacheRoot.resolve("parsed").resolve(key.zoom() + "/" + key.x()).resolve(key.y() + ".tile");
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

   private static GeoBounds geoBoundsForBlockArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int margin, WorldProjection projection) {
      if (projection.worldScale() <= 0.0) {
         return null;
      } else {
         double west = projection.blockXToLon(Math.min(minBlockX, maxBlockX) - margin);
         double east = projection.blockXToLon(Math.max(minBlockX, maxBlockX) + margin);
         double north = clampLat(projection.blockZToLat(Math.min(minBlockZ, maxBlockZ) - margin));
         double south = clampLat(projection.blockZToLat(Math.max(minBlockZ, maxBlockZ) + margin));
         return south <= north ? new GeoBounds(south, west, north, east) : null;
      }
   }

   private static List<TileKey> tileKeysForBounds(GeoBounds bounds, int zoom) {
      int minX = clampTile(lonToTileX(bounds.west(), zoom), zoom);
      int maxX = clampTile(lonToTileX(bounds.east(), zoom), zoom);
      int minY = clampTile(latToTileY(bounds.north(), zoom), zoom);
      int maxY = clampTile(latToTileY(bounds.south(), zoom), zoom);
      if (maxY < minY) {
         return List.of();
      }

      int tilesPerAxis = 1 << zoom;
      int columns = bounds.wrapsLongitude() ? tilesPerAxis - minX + maxX + 1 : maxX - minX + 1;
      if (columns <= 0) {
         return List.of();
      }
      List<TileKey> keys = new ArrayList<>(columns * (maxY - minY + 1));
      for (int tileY = minY; tileY <= maxY; tileY++) {
         if (bounds.wrapsLongitude()) {
            addTileKeys(keys, zoom, tileY, minX, tilesPerAxis - 1);
            addTileKeys(keys, zoom, tileY, 0, maxX);
         } else {
            addTileKeys(keys, zoom, tileY, minX, maxX);
         }
      }
      return keys;
   }

   private static void addTileKeys(List<TileKey> keys, int zoom, int tileY, int minTileX, int maxTileX) {
      for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
         keys.add(new TileKey(zoom, tileX, tileY));
      }
   }

   private static TileKey tileKeyForBlock(double blockX, double blockZ, WorldProjection projection, int zoom) {
      if (projection.worldScale() <= 0.0) {
         return null;
      } else {
         double lon = clampLon(projection.blockXToLon(blockX));
         double lat = clampLat(projection.blockZToLat(blockZ));
         return new TileKey(zoom, clampTile(lonToTileX(lon, zoom), zoom), clampTile(latToTileY(lat, zoom), zoom));
      }
   }

   private static TileGeoBounds tileBounds(TileKey key) {
      double west = tileXToLon(key.x(), key.zoom());
      double east = tileXToLon(key.x() + 1, key.zoom());
      double north = tileYToLat(key.y(), key.zoom());
      double south = tileYToLat(key.y() + 1, key.zoom());
      return new TileGeoBounds(south, west, north, east);
   }

   private static int lonToTileX(double lon, int zoom) {
      double normalized = (lon + 180.0) / 360.0;
      return (int)Math.floor(normalized * tilesPerAxis(zoom));
   }

   private static int latToTileY(double lat, int zoom) {
      double clampedLat = clampLat(lat);
      double latRad = Math.toRadians(clampedLat);
      double normalized = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0;
      return (int)Math.floor(normalized * tilesPerAxis(zoom));
   }

   private static double tileXToLon(int tileX, int zoom) {
      return (double)tileX / tilesPerAxis(zoom) * 360.0 - 180.0;
   }

   private static double tileYToLat(int tileY, int zoom) {
      double mercator = Math.PI * (1.0 - 2.0 * (double)tileY / tilesPerAxis(zoom));
      return Math.toDegrees(Math.atan(Math.sinh(mercator)));
   }

   private static int clampTile(int value, int zoom) {
      int max = (1 << zoom) - 1;
      return Mth.clamp(value, 0, max);
   }

   private static double tilesPerAxis(int zoom) {
      return (double)(1 << zoom);
   }

   private static double clampLon(double lon) {
      return Mth.clamp(lon, MIN_LON, MAX_LON);
   }

   private static double clampLat(double lat) {
      return Mth.clamp(lat, MIN_LAT, MAX_LAT);
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Mth.clamp(Integer.parseInt(value.trim()), minInclusive, maxInclusive);
         } catch (NumberFormatException error) {
            return defaultValue;
         }
      }
   }

   public record BuildingQueryResult(List<OsmBuildingFeature> features, boolean hadCacheMiss) {
      public BuildingQueryResult(List<OsmBuildingFeature> features, boolean hadCacheMiss) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMiss = hadCacheMiss;
      }
   }

   private record TileLookup(OsmBuildingTile tile, boolean cacheMiss) {
   }

   private record TileKey(int zoom, int x, int y) {
   }

   private record GeoBounds(double south, double west, double north, double east) {
      private boolean wrapsLongitude() {
         return this.east < this.west;
      }
   }

   private record TileGeoBounds(double south, double west, double north, double east) {
   }

   private record TilePoint(int x, int y) {
   }
}
