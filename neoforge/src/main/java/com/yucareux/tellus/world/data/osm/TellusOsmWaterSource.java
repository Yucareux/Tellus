package com.yucareux.tellus.world.data.osm;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.worldgen.EarthProjection;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Feature;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Layer;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Value;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;
import net.minecraft.Util;

public final class TellusOsmWaterSource implements TellusCacheHandle {
   private static final String DEFAULT_PM_TILES_URL = "https://overturemaps-tiles-us-west-2-beta.s3.amazonaws.com/2026-01-21/base.pmtiles";
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double METERS_PER_DEGREE = 111319.49166666667;
   private static final double POINT_EPSILON = 1.0E-9;
   private static final int DEFAULT_TILE_EXTENT = 4096;
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.overture.water.connectTimeoutMs", 7000, 1, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.overture.water.readTimeoutMs", 20000, 1, 180000);
   private static final int DIRECTORY_CACHE_ENTRIES = intProperty("tellus.overture.water.dirCache", 256, 1, 8192);
   private static final int MAX_CACHE_TILES = intProperty("tellus.osm.water.cacheTiles", 256, 1, 8192);
   private static final int MAX_ASYNC_PREFETCH_LOADS = intProperty("tellus.osm.water.prefetchAsyncMax", 96, 0, 8192);
   private static final int FETCH_RETRY_ATTEMPTS = intProperty("tellus.overture.water.fetchRetries", 3, 1, 8);
   private static final int MAX_QUERY_TILES = intProperty("tellus.osm.water.maxQueryTiles", 64, 1, 4096);
   private static final int MIN_TILE_WIDTH_BLOCKS = intProperty("tellus.osm.water.minTileWidthBlocks", 96, 1, 16384);
   private static final int PMTILES_TILETYPE_MVT = 1;
   private static final String WATER_LAYER_NAME = "water";
   private static final byte[] EMPTY_TILE_PAYLOAD = new byte[0];
   private final Path cacheRoot = FMLPaths.GAMEDIR.get().resolve("tellus/cache/map/water");
   private final PmTilesRangeReader pmTilesReader;
   private final Object initLock = new Object();
   private final LoadingCache<TellusOsmWaterSource.TileKey, OsmWaterTile> cache;
   private final Set<TellusOsmWaterSource.TileKey> pendingAsyncLoads;
   private final Set<TellusOsmWaterSource.TileKey> tileLoadFailures;
   private volatile boolean available;
   private volatile int minZoom;
   private volatile int maxZoom;
   private volatile boolean initialized;

   public TellusOsmWaterSource() {
      String pmTilesUrl = System.getProperty("tellus.overture.water.pmtiles", DEFAULT_PM_TILES_URL);
      this.pmTilesReader = new PmTilesRangeReader(pmTilesUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, DIRECTORY_CACHE_ENTRIES);
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<TellusOsmWaterSource.TileKey, OsmWaterTile>() {
         public OsmWaterTile load(TellusOsmWaterSource.TileKey key) {
            return TellusOsmWaterSource.this.loadTile(key);
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

   public WaterQueryResult waterForAreaWithStatus(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks, OsmQueryMode mode
   ) {
      this.ensureInitialized();
      if (this.available && !(worldScale <= 0.0)) {
         TellusOsmWaterSource.GeoBounds bounds = geoBoundsForBlockArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, worldScale);
         if (bounds == null) {
            return new WaterQueryResult(List.of(), false, 0);
         } else {
            int zoom = this.queryZoomForBounds(bounds, worldScale);
            List<TellusOsmWaterSource.TileKey> keys = tileKeysForBounds(bounds, zoom);
            if (keys.isEmpty()) {
               return new WaterQueryResult(List.of(), false, zoom);
            } else {
               List<OsmWaterFeature> features = new ArrayList<>();
               boolean hadCacheMiss = false;

               for (TellusOsmWaterSource.TileKey key : keys) {
                  TellusOsmWaterSource.TileLookup lookup = this.getTileLookup(key, mode);
                  hadCacheMiss |= lookup.cacheMiss();
                  OsmWaterTile tile = lookup.tile();
                  if (!tile.isEmpty()) {
                     features.addAll(tile.featuresInBounds(bounds.south(), bounds.west(), bounds.north(), bounds.east()));
                  }
               }

               return new WaterQueryResult(features, hadCacheMiss, zoom);
            }
         }
      } else {
         return new WaterQueryResult(List.of(), false, 0);
      }
   }

   public List<OsmWaterFeature> waterForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks) {
      return this.waterForAreaWithStatus(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, marginBlocks, OsmQueryMode.BLOCKING).features();
   }

   public FastWaterSample sampleWater(int blockX, int blockZ, double worldScale, OsmQueryMode mode) {
      WaterQueryResult result = this.waterForAreaWithStatus(blockX - 1, blockZ - 1, blockX + 1, blockZ + 1, worldScale, 0, mode);
      if (result.features().isEmpty()) {
         return new FastWaterSample(false, false, result.hadCacheMiss());
      } else {
         boolean hasWater = false;
         boolean ocean = false;

         for (OsmWaterFeature feature : result.features()) {
            if (feature.containsBlock(blockX, blockZ, worldScale)) {
               hasWater = true;
               if (feature.oceanHint()) {
                  ocean = true;
                  break;
               }
            }
         }

         return new FastWaterSample(hasWater, ocean, result.hadCacheMiss());
      }
   }

   public boolean hasWaterInArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks, OsmQueryMode mode) {
      return !this.waterForAreaWithStatus(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, marginBlocks, mode).features().isEmpty();
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      this.ensureInitialized();
      if (this.available && !(worldScale <= 0.0) && radius > 0) {
         int zoom = this.queryZoomForScale(worldScale);
         TellusOsmWaterSource.TileKey center = tileKeyForBlock(blockX, blockZ, worldScale, zoom);
         if (center != null) {
            int tilesPerAxis = 1 << zoom;
            int minX = Math.max(0, center.x() - radius);
            int maxX = Math.min(tilesPerAxis - 1, center.x() + radius);
            int minY = Math.max(0, center.y() - radius);
            int maxY = Math.min(tilesPerAxis - 1, center.y() + radius);

            for (int tileY = minY; tileY <= maxY; tileY++) {
               for (int tileX = minX; tileX <= maxX; tileX++) {
                  this.getTile(new TellusOsmWaterSource.TileKey(zoom, tileX, tileY), OsmQueryMode.NON_BLOCKING);
               }
            }
         }
      }
   }

   private int queryZoomForBounds(TellusOsmWaterSource.GeoBounds bounds, double worldScale) {
      this.ensureInitialized();
      int zoom = this.queryZoomForScale(worldScale);

      while (zoom > this.minZoom) {
         if (tileKeysForBounds(bounds, zoom).size() <= MAX_QUERY_TILES) {
            break;
         }

         zoom--;
      }

      return zoom;
   }

   private int queryZoomForScale(double worldScale) {
      this.ensureInitialized();
      if (worldScale <= 0.0) {
         return this.minZoom;
      } else {
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);

         for (int zoom = this.maxZoom; zoom >= this.minZoom; zoom--) {
            double tileWidthBlocks = 360.0 * blocksPerDegree / (1 << zoom);
            if (tileWidthBlocks >= MIN_TILE_WIDTH_BLOCKS) {
               return zoom;
            }
         }

         return this.minZoom;
      }
   }

   private OsmWaterTile getTile(TellusOsmWaterSource.TileKey key, OsmQueryMode mode) {
      return this.getTileLookup(key, mode).tile();
   }

   private TellusOsmWaterSource.TileLookup getTileLookup(TellusOsmWaterSource.TileKey key, OsmQueryMode mode) {
      OsmWaterTile cached = this.cache.getIfPresent(key);
      if (cached != null) {
         if (!cached.isEmpty() || !this.tileLoadFailures.contains(key)) {
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_WATER, OsmPerf.TileLoadPath.MEMORY);
            return new TellusOsmWaterSource.TileLookup(cached, false);
         }

         this.cache.invalidate(key);
      }

      OsmQueryMode queryMode = mode == null ? OsmQueryMode.BLOCKING : mode;
      if (queryMode == OsmQueryMode.NON_BLOCKING) {
         this.queueAsyncLoad(key);
         return new TellusOsmWaterSource.TileLookup(OsmWaterTile.empty(), true);
      } else {
         try {
            return new TellusOsmWaterSource.TileLookup(this.cache.get(key), false);
         } catch (Exception error) {
            Tellus.LOGGER.debug("Failed to load Overture water tile {}", key, error);
            this.tileLoadFailures.add(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_WATER, OsmPerf.TileLoadPath.FAILURE);
            return new TellusOsmWaterSource.TileLookup(OsmWaterTile.empty(), false);
         }
      }
   }

   private void queueAsyncLoad(TellusOsmWaterSource.TileKey key) {
      if (MAX_ASYNC_PREFETCH_LOADS <= 0 || this.pendingAsyncLoads.size() < MAX_ASYNC_PREFETCH_LOADS) {
         if (this.pendingAsyncLoads.add(key)) {
            Util.backgroundExecutor().execute(() -> {
               try {
                  this.cache.get(key);
               } catch (Exception error) {
                  Tellus.LOGGER.debug("Async load failed for Overture water tile {}", key, error);
                  this.tileLoadFailures.add(key);
                  OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_WATER, OsmPerf.TileLoadPath.FAILURE);
               } finally {
                  this.pendingAsyncLoads.remove(key);
               }
            });
         }
      }
   }

   private OsmWaterTile loadTile(TellusOsmWaterSource.TileKey key) {
      this.ensureInitialized();
      if (!this.available) {
         return OsmWaterTile.empty();
      } else {
         TellusOsmWaterSource.TileGeoBounds bounds = tileBounds(key);
         Path cachePath = this.cachePathFor(key);
         Path parsedCachePath = this.parsedCachePathFor(key);
         if (Files.exists(parsedCachePath)) {
            try {
               OsmWaterTile parsed = ParsedTileCodec.readWaterTile(parsedCachePath);
               this.tileLoadFailures.remove(key);
               OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_WATER, OsmPerf.TileLoadPath.PARSED_DISK);
               return parsed;
            } catch (RuntimeException | IOException error) {
               Tellus.LOGGER.debug("Invalid parsed Overture water cache tile {}, refetching", key, error);

               try {
                  Files.deleteIfExists(parsedCachePath);
               } catch (IOException deleteError) {
                  Tellus.LOGGER.debug("Failed to delete invalid parsed Overture water cache tile {}", parsedCachePath, deleteError);
               }
            }
         }

         if (Files.exists(cachePath)) {
            try {
               byte[] payload = this.readCompressed(cachePath);
               OsmWaterTile parsed = this.parseVectorTile(payload, bounds, key);
               this.cacheParsedTile(parsedCachePath, parsed);
               this.tileLoadFailures.remove(key);
               OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_WATER, OsmPerf.TileLoadPath.RAW_DISK);
               return parsed;
            } catch (RuntimeException | IOException error) {
               Tellus.LOGGER.debug("Invalid Overture water cache tile {}, refetching", key, error);

               try {
                  Files.deleteIfExists(cachePath);
               } catch (IOException deleteError) {
                  Tellus.LOGGER.debug("Failed to delete invalid Overture water cache tile {}", cachePath, deleteError);
               }
            }
         }

         byte[] payload = this.fetchTilePayloadWithRetry(key);

         OsmWaterTile parsed;
         try {
            parsed = this.parseVectorTile(payload, bounds, key);
         } catch (RuntimeException error) {
            Tellus.LOGGER.warn("Overture water parse failed for tile {}", key, error);
            this.tileLoadFailures.add(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_WATER, OsmPerf.TileLoadPath.FAILURE);
            throw new RuntimeException("Overture water parse failed for tile " + key, error);
         }

         this.cacheTile(cachePath, payload);
         this.cacheParsedTile(parsedCachePath, parsed);
         this.tileLoadFailures.remove(key);
         OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_WATER, OsmPerf.TileLoadPath.NETWORK);
         return parsed;
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

         int resolvedMinZoom = 0;
         int resolvedMaxZoom = 0;
         boolean sourceAvailable;

         try {
            PmTilesRangeReader.PmTilesHeader header = this.pmTilesReader.header();
            if (header.tileType() != PMTILES_TILETYPE_MVT) {
               Tellus.LOGGER.warn("Unexpected Overture water PMTiles tile type {}, expected MVT", header.tileType());
            }

            resolvedMinZoom = header.minZoom();
            resolvedMaxZoom = header.maxZoom();
            sourceAvailable = true;
         } catch (IOException error) {
            sourceAvailable = false;
            Tellus.LOGGER.warn("Overture water PMTiles unavailable, OSM water disabled", error);
         }

         this.available = sourceAvailable;
         this.minZoom = resolvedMinZoom;
         this.maxZoom = resolvedMaxZoom;
         this.initialized = true;
      }
   }

   private byte[] fetchTilePayloadWithRetry(TellusOsmWaterSource.TileKey key) {
      RuntimeException lastFailure = null;

      for (int attempt = 1; attempt <= FETCH_RETRY_ATTEMPTS; attempt++) {
         try {
            byte[] fetched = this.pmTilesReader.getTileBytes(key.zoom(), key.x(), key.y());
            return fetched == null ? EMPTY_TILE_PAYLOAD : fetched;
         } catch (IOException error) {
            lastFailure = new RuntimeException("Overture water fetch failed for tile " + key, error);
            if (attempt < FETCH_RETRY_ATTEMPTS) {
               continue;
            }
            break;
         }
      }

      this.tileLoadFailures.add(key);
      OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_WATER, OsmPerf.TileLoadPath.FAILURE);
      if (lastFailure != null) {
         throw lastFailure;
      } else {
         return EMPTY_TILE_PAYLOAD;
      }
   }

   private void cacheTile(Path cachePath, byte[] payload) {
      try {
         Files.createDirectories(cachePath.getParent());
         Path tempPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");

         try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(tempPath))) {
            output.write(payload);
         }

         Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache Overture water tile {}", cachePath, error);
      }
   }

   private void cacheParsedTile(Path cachePath, OsmWaterTile tile) {
      try {
         ParsedTileCodec.writeWaterTile(cachePath, tile);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache parsed Overture water tile {}", cachePath, error);
      }
   }

   private byte[] readCompressed(Path path) throws IOException {
      try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
         return input.readAllBytes();
      }
   }

   private OsmWaterTile parseVectorTile(byte[] payload, TellusOsmWaterSource.TileGeoBounds bounds, TellusOsmWaterSource.TileKey key) {
      if (payload.length == 0) {
         return OsmWaterTile.empty();
      } else {
         Tile tile;
         try {
            tile = Tile.parseFrom(payload);
         } catch (Exception error) {
            throw new RuntimeException("Failed to decode water MVT payload", error);
         }

         if (tile.getLayersCount() == 0) {
            return OsmWaterTile.empty();
         } else {
            List<OsmWaterFeature> features = new ArrayList<>();

            for (Layer layer : tile.getLayersList()) {
               if (WATER_LAYER_NAME.equals(layer.getName())) {
                  int extent = layer.hasExtent() && layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_TILE_EXTENT;

                  for (Feature feature : layer.getFeaturesList()) {
                     features.addAll(this.parseFeature(feature, layer, key, extent));
                  }
               }
            }

            return features.isEmpty() ? OsmWaterTile.empty() : new OsmWaterTile(features, bounds.south(), bounds.west(), bounds.north(), bounds.east());
         }
      }
   }

   private List<OsmWaterFeature> parseFeature(Feature feature, Layer layer, TellusOsmWaterSource.TileKey key, int extent) {
      Map<String, Object> tags = decodeTags(feature, layer);
      String classTag = nonBlank(asString(tags.get("class")));
      String subtype = nonBlank(asString(tags.get("subtype")));
      if (classTag == null && subtype == null) {
         return List.of();
      } else {
         boolean oceanHint = "ocean".equalsIgnoreCase(classTag) || "ocean".equalsIgnoreCase(subtype);
         long featureId = resolveFeatureId(feature, tags);
         return switch (feature.getType()) {
            case POLYGON -> buildPolygonFeatures(featureId, oceanHint, decodePolygonRings(feature.getGeometryList()), key.zoom(), key.x(), key.y(), extent);
            case LINESTRING -> buildLineFeatures(featureId, oceanHint, decodeLineStrings(feature.getGeometryList()), key.zoom(), key.x(), key.y(), extent);
            default -> List.of();
         };
      }
   }

   private static List<OsmWaterFeature> buildPolygonFeatures(
      long featureId,
      boolean oceanHint,
      List<List<TellusOsmWaterSource.TilePoint>> rings,
      int zoom,
      int tileX,
      int tileY,
      int extent
   ) {
      if (rings.isEmpty()) {
         return List.of();
      } else {
         double[][] longitudes = new double[rings.size()][];
         double[][] latitudes = new double[rings.size()][];

         for (int part = 0; part < rings.size(); part++) {
            List<TellusOsmWaterSource.TilePoint> ring = rings.get(part);
            int points = ring.size();
            double[] lonPart = new double[points];
            double[] latPart = new double[points];
            int count = 0;
            double previousLon = Double.NaN;
            double previousLat = Double.NaN;

            for (TellusOsmWaterSource.TilePoint point : ring) {
               double lon = tilePointToLon(zoom, tileX, (double)point.x / extent);
               double lat = tilePointToLat(zoom, tileY, (double)point.y / extent);
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
               return List.of();
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

         return List.of(new OsmWaterFeature(featureId, false, oceanHint, longitudes, latitudes));
      }
   }

   private static List<OsmWaterFeature> buildLineFeatures(
      long featureId,
      boolean oceanHint,
      List<List<TellusOsmWaterSource.TilePoint>> lines,
      int zoom,
      int tileX,
      int tileY,
      int extent
   ) {
      if (lines.isEmpty()) {
         return List.of();
      } else {
         List<double[]> longitudeParts = new ArrayList<>();
         List<double[]> latitudeParts = new ArrayList<>();

         for (List<TellusOsmWaterSource.TilePoint> line : lines) {
            double[] longitudes = new double[line.size()];
            double[] latitudes = new double[line.size()];
            int count = 0;
            double previousLon = Double.NaN;
            double previousLat = Double.NaN;

            for (TellusOsmWaterSource.TilePoint point : line) {
               double lon = tilePointToLon(zoom, tileX, (double)point.x / extent);
               double lat = tilePointToLat(zoom, tileY, (double)point.y / extent);
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

            if (count >= 2) {
               longitudeParts.add(count == longitudes.length ? longitudes : Arrays.copyOf(longitudes, count));
               latitudeParts.add(count == latitudes.length ? latitudes : Arrays.copyOf(latitudes, count));
            }
         }

         if (longitudeParts.isEmpty()) {
            return List.of();
         } else {
            return List.of(new OsmWaterFeature(featureId, true, oceanHint, longitudeParts.toArray(new double[0][]), latitudeParts.toArray(new double[0][])));
         }
      }
   }

   private static List<List<TellusOsmWaterSource.TilePoint>> decodePolygonRings(List<Integer> geometry) {
      if (geometry != null && !geometry.isEmpty()) {
         List<List<TellusOsmWaterSource.TilePoint>> rings = new ArrayList<>();
         List<TellusOsmWaterSource.TilePoint> current = null;
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
                        current.add(new TellusOsmWaterSource.TilePoint(cursorX, cursorY));
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
                        current.add(new TellusOsmWaterSource.TilePoint(cursorX, cursorY));
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

   private static List<List<TellusOsmWaterSource.TilePoint>> decodeLineStrings(List<Integer> geometry) {
      if (geometry != null && !geometry.isEmpty()) {
         List<List<TellusOsmWaterSource.TilePoint>> lines = new ArrayList<>();
         List<TellusOsmWaterSource.TilePoint> current = null;
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
                        current.add(new TellusOsmWaterSource.TilePoint(cursorX, cursorY));
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
                        current.add(new TellusOsmWaterSource.TilePoint(cursorX, cursorY));
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

   private static void addRing(List<List<TellusOsmWaterSource.TilePoint>> rings, List<TellusOsmWaterSource.TilePoint> points) {
      List<TellusOsmWaterSource.TilePoint> cleaned = new ArrayList<>(points.size());

      for (TellusOsmWaterSource.TilePoint point : points) {
         if (cleaned.isEmpty() || !samePoint(cleaned.get(cleaned.size() - 1), point)) {
            cleaned.add(point);
         }
      }

      if (cleaned.size() >= 3) {
         TellusOsmWaterSource.TilePoint first = cleaned.get(0);
         TellusOsmWaterSource.TilePoint last = cleaned.get(cleaned.size() - 1);
         if (!samePoint(first, last)) {
            cleaned.add(first);
         }

         if (cleaned.size() >= 4) {
            rings.add(List.copyOf(cleaned));
         }
      }
   }

   private static void addLine(List<List<TellusOsmWaterSource.TilePoint>> lines, List<TellusOsmWaterSource.TilePoint> points) {
      if (points.size() >= 2) {
         List<TellusOsmWaterSource.TilePoint> cleaned = new ArrayList<>(points.size());

         for (TellusOsmWaterSource.TilePoint point : points) {
            if (cleaned.isEmpty() || !samePoint(cleaned.get(cleaned.size() - 1), point)) {
               cleaned.add(point);
            }
         }

         if (cleaned.size() >= 2) {
            lines.add(List.copyOf(cleaned));
         }
      }
   }

   private static boolean samePoint(TellusOsmWaterSource.TilePoint a, TellusOsmWaterSource.TilePoint b) {
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
      if (feature.hasId() && feature.getId() != 0L) {
         return feature.getId();
      } else {
         long idFromTag = parseLongId(tags.get("id"));
         if (idFromTag != 0L) {
            return idFromTag;
         } else {
            long idFromSources = parseSourceWayId(tags.get("sources"));
            return idFromSources != 0L ? idFromSources : 0L;
         }
      }
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
            } catch (NumberFormatException error) {
               try {
                  UUID uuid = UUID.fromString(text);
                  long mixed = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
                  return mixed == 0L ? 1L : mixed;
               } catch (IllegalArgumentException ignored) {
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
            int recordIndex = sources.indexOf("\"record_id\":\"w", cursor);
            if (recordIndex < 0) {
               return 0L;
            }

            int start = recordIndex + "\"record_id\":\"w".length();

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
               } catch (NumberFormatException ignored) {
               }
            }

            cursor = end > start ? end : recordIndex + 1;
         }
      } else {
         return 0L;
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

   private Path cachePathFor(TellusOsmWaterSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".mvt.gz");
   }

   private Path parsedCachePathFor(TellusOsmWaterSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".pm.parsed.bin");
   }

   private static TellusOsmWaterSource.GeoBounds geoBoundsForBlockArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, double worldScale
   ) {
      if (worldScale <= 0.0) {
         return null;
      } else {
         int margin = Math.max(0, marginBlocks);
         double blocksPerDegree = METERS_PER_DEGREE / worldScale;
         double west = clampLon((Math.min(minBlockX, maxBlockX) - margin) / blocksPerDegree);
         double east = clampLon((Math.max(minBlockX, maxBlockX) + margin) / blocksPerDegree);
         double north = clampLat(EarthProjection.blockZToLat(Math.min(minBlockZ, maxBlockZ) - margin, worldScale));
         double south = clampLat(EarthProjection.blockZToLat(Math.max(minBlockZ, maxBlockZ) + margin, worldScale));
         if (south > north) {
            double swap = south;
            south = north;
            north = swap;
         }

         return new TellusOsmWaterSource.GeoBounds(south, west, north, east);
      }
   }

   private static List<TellusOsmWaterSource.TileKey> tileKeysForBounds(TellusOsmWaterSource.GeoBounds bounds, int zoom) {
      int tilesPerAxis = 1 << zoom;
      int minX = Mth.clamp(lonToTileX(bounds.west(), zoom), 0, tilesPerAxis - 1);
      int maxX = Mth.clamp(lonToTileX(bounds.east(), zoom), 0, tilesPerAxis - 1);
      int minY = Mth.clamp(latToTileY(bounds.north(), zoom), 0, tilesPerAxis - 1);
      int maxY = Mth.clamp(latToTileY(bounds.south(), zoom), 0, tilesPerAxis - 1);
      if (maxX >= minX && maxY >= minY) {
         List<TellusOsmWaterSource.TileKey> keys = new ArrayList<>((maxX - minX + 1) * (maxY - minY + 1));

         for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
               keys.add(new TellusOsmWaterSource.TileKey(zoom, x, y));
            }
         }

         return keys;
      } else {
         return List.of();
      }
   }

   private static TellusOsmWaterSource.TileKey tileKeyForBlock(double blockX, double blockZ, double worldScale, int zoom) {
      if (worldScale <= 0.0) {
         return null;
      } else {
         double blocksPerDegree = METERS_PER_DEGREE / worldScale;
         double lon = clampLon(blockX / blocksPerDegree);
         double lat = clampLat(EarthProjection.blockZToLat(blockZ, worldScale));
         double n = tilesPerAxis(zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(Math.toRadians(lat)) + 1.0 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2.0 * n;
         return !(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n) ? new TellusOsmWaterSource.TileKey(zoom, Mth.floor(x), Mth.floor(y)) : null;
      }
   }

   private static TellusOsmWaterSource.TileGeoBounds tileBounds(TellusOsmWaterSource.TileKey key) {
      double n = tilesPerAxis(key.zoom());
      double west = key.x() / n * 360.0 - 180.0;
      double east = (key.x() + 1.0) / n * 360.0 - 180.0;
      double north = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * key.y() / n))));
      double south = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (key.y() + 1.0) / n))));
      return new TellusOsmWaterSource.TileGeoBounds(south, west, north, east);
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

   private static String asString(Object value) {
      return value == null ? null : String.valueOf(value);
   }

   private static String nonBlank(String value) {
      return value != null && !value.isBlank() ? value : null;
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            int parsed = Integer.parseInt(value);
            return Mth.clamp(parsed, minInclusive, maxInclusive);
         } catch (NumberFormatException error) {
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

   private record GeoBounds(double south, double west, double north, double east) {
   }

   public record FastWaterSample(boolean hasWater, boolean ocean, boolean hadCacheMiss) {
   }

   private record TileGeoBounds(double south, double west, double north, double east) {
   }

   private record TileKey(int zoom, int x, int y) {
   }

   private record TileLookup(OsmWaterTile tile, boolean cacheMiss) {
   }

   private record TilePoint(int x, int y) {
   }

   public record WaterQueryResult(List<OsmWaterFeature> features, boolean hadCacheMiss, int zoom) {
      public WaterQueryResult(List<OsmWaterFeature> features, boolean hadCacheMiss, int zoom) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMiss = hadCacheMiss;
         this.zoom = zoom;
      }
   }
}
