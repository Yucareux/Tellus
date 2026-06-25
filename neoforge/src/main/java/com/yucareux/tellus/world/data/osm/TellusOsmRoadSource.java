package com.yucareux.tellus.world.data.osm;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.worldgen.EarthProjection;
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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;
import net.minecraft.Util;

public final class TellusOsmRoadSource implements TellusCacheHandle {
   private static final String DEFAULT_PM_TILES_URL = "https://overturemaps-extras-us-west-2.s3.us-west-2.amazonaws.com/tiles/2026-02-18.0/transportation.pmtiles";
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double METERS_PER_DEGREE = 111319.49166666667;
   private static final double POINT_EPSILON = 1.0E-9;
   private static final int DEFAULT_TILE_EXTENT = 4096;
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.overture.roads.connectTimeoutMs", 7000, 1, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.overture.roads.readTimeoutMs", 20000, 1, 180000);
   private static final int DIRECTORY_CACHE_ENTRIES = intProperty("tellus.overture.roads.dirCache", 256, 1, 8192);
   private static final int MAX_CACHE_TILES = intProperty("tellus.osm.roads.cacheTiles", 256, 1, 8192);
   private static final int QUERY_ZOOM = intProperty("tellus.osm.roads.queryZoom", 14, 0, 20);
   private static final int MAX_ASYNC_PREFETCH_LOADS = intProperty("tellus.osm.roads.prefetchAsyncMax", 96, 0, 8192);
   private static final int MAX_BRIDGE_LEVEL = intProperty("tellus.overture.roads.maxBridgeLevel", 3, 1, 16);
   private static final int FETCH_RETRY_ATTEMPTS = intProperty("tellus.overture.roads.fetchRetries", 3, 1, 8);
   private static final int PMTILES_TILETYPE_MVT = 1;
   private static final String SEGMENT_LAYER_NAME = "segment";
   private static final byte[] EMPTY_TILE_PAYLOAD = new byte[0];
   private final Path cacheRoot = FMLPaths.GAMEDIR.get().resolve("tellus/cache/map/roads");
   private final PmTilesRangeReader pmTilesReader;
   private final Object initLock = new Object();
   private final LoadingCache<TellusOsmRoadSource.TileKey, OverpassRoadTile> cache;
   private final Set<TellusOsmRoadSource.TileKey> pendingAsyncLoads;
   private final Set<TellusOsmRoadSource.TileKey> tileLoadFailures;
   private volatile int queryZoom = QUERY_ZOOM;
   private volatile boolean available;
   private volatile boolean initialized;

   public TellusOsmRoadSource() {
      String pmTilesUrl = System.getProperty(
         "tellus.overture.roads.pmtiles", DEFAULT_PM_TILES_URL
      );
      this.pmTilesReader = new PmTilesRangeReader(pmTilesUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, DIRECTORY_CACHE_ENTRIES);
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

   public List<RoadFeature> roadsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks) {
      return this.roadsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, marginBlocks, OsmQueryMode.BLOCKING);
   }

   public List<RoadFeature> roadsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks, OsmQueryMode mode) {
      return this.roadsForAreaWithStatus(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, marginBlocks, mode).features();
   }

   public TellusOsmRoadSource.RoadQueryResult roadsForAreaWithStatus(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, int marginBlocks, OsmQueryMode mode
   ) {
      this.ensureInitialized();
      if (this.available && !(worldScale <= 0.0)) {
         TellusOsmRoadSource.GeoBounds bounds = geoBoundsForBlockArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, worldScale);
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
                     List<RoadFeature> tileFeatures = tile.featuresInBounds(bounds.south(), bounds.west(), bounds.north(), bounds.east());
                     roads.addAll(tileFeatures);
                  }
               }

               return new TellusOsmRoadSource.RoadQueryResult(roads, hadCacheMiss);
            }
         }
      } else {
         return new TellusOsmRoadSource.RoadQueryResult(List.of(), false);
      }
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      this.ensureInitialized();
      if (this.available && !(worldScale <= 0.0) && radius > 0) {
         TellusOsmRoadSource.TileKey center = tileKeyForBlock(blockX, blockZ, worldScale, this.queryZoom);
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

      OsmQueryMode queryMode = mode == null ? OsmQueryMode.BLOCKING : mode;
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
            return new TellusOsmRoadSource.TileLookup(OverpassRoadTile.empty(), false);
         }
      }
   }

   private void queueAsyncLoad(TellusOsmRoadSource.TileKey key) {
      if (MAX_ASYNC_PREFETCH_LOADS <= 0 || this.pendingAsyncLoads.size() < MAX_ASYNC_PREFETCH_LOADS) {
         if (this.pendingAsyncLoads.add(key)) {
            Util.backgroundExecutor().execute(() -> {
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

         byte[] payload = this.fetchTilePayloadWithRetry(key);

         OverpassRoadTile parsed;
         try {
            parsed = this.parseVectorTile(payload, bounds, key);
         } catch (RuntimeException var8) {
            Tellus.LOGGER.warn("Overture road parse failed for tile {}", key, var8);
            this.tileLoadFailures.add(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_ROADS, OsmPerf.TileLoadPath.FAILURE);
            throw new RuntimeException("Overture road parse failed for tile " + key, var8);
         }

         this.cacheTile(cachePath, payload);
         this.cacheParsedTile(parsedCachePath, parsed);
         this.tileLoadFailures.remove(key);
         OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_ROADS, OsmPerf.TileLoadPath.NETWORK);
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

   private void cacheTile(Path cachePath, byte[] payload) {
      try {
         Files.createDirectories(cachePath.getParent());
         Path tempPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");

         try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(tempPath))) {
            output.write(payload);
         }

         Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException var9) {
         Tellus.LOGGER.debug("Failed to cache Overture road tile {}", cachePath, var9);
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
         var3 = input.readAllBytes();
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

            for (Layer layer : tile.getLayersList()) {
               if (SEGMENT_LAYER_NAME.equals(layer.getName())) {
                  int extent = layer.hasExtent() && layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_TILE_EXTENT;

                  for (Feature feature : layer.getFeaturesList()) {
                     if (feature.getType() == GeomType.LINESTRING) {
                        features.addAll(this.parseFeature(feature, layer, key, extent));
                     }
                  }
               }
            }

            return features.isEmpty() ? OverpassRoadTile.empty() : new OverpassRoadTile(features, bounds.south(), bounds.west(), bounds.north(), bounds.east());
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
         RoadFeature feature = buildFeatureFromRange(wayId, roadClass, descriptor.mode(), descriptor.bridgeLevel(), highwayTag, geometry, start, end);
         if (feature != null) {
            features.add(feature);
         }
      }
   }

   private static RoadFeature buildFeatureFromRange(
      long wayId,
      RoadClass roadClass,
      RoadMode mode,
      int bridgeLevel,
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

            return new RoadFeature(wayId, roadClass, mode, bridgeLevel, highwayTag, featureLon, featureLat);
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
      boolean explicitBridge = isTruthy(asString(tags.get("bridge"))) || containsFlagAt(tags.get("road_flags"), "is_bridge", position);
      if (explicitBridge) {
         int bridgeLevel = signedLevel > 0 && roadClass != RoadClass.DIRT ? Mth.clamp(signedLevel, 1, MAX_BRIDGE_LEVEL) : 1;
         return new TellusOsmRoadSource.RoadDescriptor(RoadMode.BRIDGE, bridgeLevel);
      } else {
         boolean tunnel = containsFlagAt(tags.get("road_flags"), "is_tunnel", position)
            || isTruthy(asString(tags.get("tunnel")))
            || signedLevel < 0;
         return tunnel ? new TellusOsmRoadSource.RoadDescriptor(RoadMode.TUNNEL, 0) : new TellusOsmRoadSource.RoadDescriptor(RoadMode.NORMAL, 0);
      }
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

   private static boolean isTruthy(String value) {
      if (value == null) {
         return false;
      } else {
         String normalized = value.trim().toLowerCase(Locale.ROOT);
         return !normalized.isEmpty() && !"no".equals(normalized) && !"false".equals(normalized) && !"0".equals(normalized);
      }
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
               } catch (NumberFormatException var7) {
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

         return new TellusOsmRoadSource.GeoBounds(south, west, north, east);
      }
   }

   private static List<TellusOsmRoadSource.TileKey> tileKeysForBounds(TellusOsmRoadSource.GeoBounds bounds, int zoom) {
      int tilesPerAxis = 1 << zoom;
      int minX = Mth.clamp(lonToTileX(bounds.west(), zoom), 0, tilesPerAxis - 1);
      int maxX = Mth.clamp(lonToTileX(bounds.east(), zoom), 0, tilesPerAxis - 1);
      int minY = Mth.clamp(latToTileY(bounds.north(), zoom), 0, tilesPerAxis - 1);
      int maxY = Mth.clamp(latToTileY(bounds.south(), zoom), 0, tilesPerAxis - 1);
      if (maxX >= minX && maxY >= minY) {
         List<TellusOsmRoadSource.TileKey> keys = new ArrayList<>((maxX - minX + 1) * (maxY - minY + 1));

         for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
               keys.add(new TellusOsmRoadSource.TileKey(zoom, x, y));
            }
         }

         return keys;
      } else {
         return List.of();
      }
   }

   
   private static TellusOsmRoadSource.TileKey tileKeyForBlock(double blockX, double blockZ, double worldScale, int zoom) {
      if (worldScale <= 0.0) {
         return null;
      } else {
         double blocksPerDegree = METERS_PER_DEGREE / worldScale;
         double lon = clampLon(blockX / blocksPerDegree);
         double lat = clampLat(EarthProjection.blockZToLat(blockZ, worldScale));
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

   private record RoadDescriptor(RoadMode mode, int bridgeLevel) {
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

   private record TileGeoBounds(double south, double west, double north, double east) {
   }

   private record TileKey(int zoom, int x, int y) {
   }

   private record TileLookup(OverpassRoadTile tile, boolean cacheMiss) {
   }

   private record TilePoint(int x, int y) {
   }
}
