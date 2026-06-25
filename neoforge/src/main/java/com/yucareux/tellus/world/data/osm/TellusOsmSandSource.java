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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.util.Mth;
import net.minecraft.Util;

public final class TellusOsmSandSource implements TellusCacheHandle {
   private static final String DEFAULT_PM_TILES_URL = "https://overturemaps-tiles-us-west-2-beta.s3.amazonaws.com/2026-01-21/base.pmtiles";
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double METERS_PER_DEGREE = 111319.49166666667;
   private static final double POINT_EPSILON = 1.0E-9;
   private static final int DEFAULT_TILE_EXTENT = 4096;
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.overture.sand.connectTimeoutMs", 7000, 1, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.overture.sand.readTimeoutMs", 20000, 1, 180000);
   private static final int DIRECTORY_CACHE_ENTRIES = intProperty("tellus.overture.sand.dirCache", 256, 1, 8192);
   private static final int MAX_CACHE_TILES = intProperty("tellus.osm.sand.cacheTiles", 256, 1, 8192);
   private static final int MAX_ASYNC_PREFETCH_LOADS = intProperty("tellus.osm.sand.prefetchAsyncMax", 96, 0, 8192);
   private static final int FETCH_RETRY_ATTEMPTS = intProperty("tellus.overture.sand.fetchRetries", 3, 1, 8);
   private static final int MIN_TILE_WIDTH_BLOCKS = intProperty("tellus.osm.sand.minTileWidthBlocks", 96, 1, 16384);
   private static final int PMTILES_TILETYPE_MVT = 1;
   private static final String LAND_LAYER_NAME = "land";
   private static final String SAND_SUBTYPE = "sand";
   private static final byte[] EMPTY_TILE_PAYLOAD = new byte[0];
   private final Path cacheRoot = FMLPaths.GAMEDIR.get().resolve("tellus/cache/map/sand");
   private final PmTilesRangeReader pmTilesReader;
   private final Object initLock = new Object();
   private final LoadingCache<TellusOsmSandSource.TileKey, OsmSandTile> cache;
   private final Set<TellusOsmSandSource.TileKey> pendingAsyncLoads;
   private final Set<TellusOsmSandSource.TileKey> tileLoadFailures;
   private volatile boolean available;
   private volatile int minZoom;
   private volatile int maxZoom;
   private volatile boolean initialized;

   public TellusOsmSandSource() {
      String pmTilesUrl = System.getProperty("tellus.overture.sand.pmtiles", DEFAULT_PM_TILES_URL);
      this.pmTilesReader = new PmTilesRangeReader(pmTilesUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, DIRECTORY_CACHE_ENTRIES);
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<TellusOsmSandSource.TileKey, OsmSandTile>() {
         public OsmSandTile load(TellusOsmSandSource.TileKey key) {
            return TellusOsmSandSource.this.loadTile(key);
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

   public boolean containsSand(int blockX, int blockZ, double worldScale, OsmQueryMode mode) {
      this.ensureInitialized();
      if (!this.available || worldScale <= 0.0) {
         return false;
      } else {
         int zoom = this.queryZoomForScale(worldScale);
         TellusOsmSandSource.TileKey key = tileKeyForBlock(blockX, blockZ, worldScale, zoom);
         if (key == null) {
            return false;
         } else {
            OsmSandTile tile = this.getTileLookup(key, mode).tile();
            if (tile.isEmpty()) {
               return false;
            } else {
               for (OsmSandFeature feature : tile.features()) {
                  if (feature.containsBlock(blockX, blockZ, worldScale)) {
                     return true;
                  }
               }

               return false;
            }
         }
      }
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      this.ensureInitialized();
      if (this.available && !(worldScale <= 0.0) && radius > 0) {
         int zoom = this.queryZoomForScale(worldScale);
         TellusOsmSandSource.TileKey center = tileKeyForBlock(blockX, blockZ, worldScale, zoom);
         if (center != null) {
            int tilesPerAxis = 1 << zoom;
            int minX = Math.max(0, center.x() - radius);
            int maxX = Math.min(tilesPerAxis - 1, center.x() + radius);
            int minY = Math.max(0, center.y() - radius);
            int maxY = Math.min(tilesPerAxis - 1, center.y() + radius);

            for (int tileY = minY; tileY <= maxY; tileY++) {
               for (int tileX = minX; tileX <= maxX; tileX++) {
                  this.getTile(new TellusOsmSandSource.TileKey(zoom, tileX, tileY), OsmQueryMode.NON_BLOCKING);
               }
            }
         }
      }
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

   private OsmSandTile getTile(TellusOsmSandSource.TileKey key, OsmQueryMode mode) {
      return this.getTileLookup(key, mode).tile();
   }

   private TellusOsmSandSource.TileLookup getTileLookup(TellusOsmSandSource.TileKey key, OsmQueryMode mode) {
      OsmSandTile cached = this.cache.getIfPresent(key);
      if (cached != null) {
         if (!cached.isEmpty() || !this.tileLoadFailures.contains(key)) {
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_SAND, OsmPerf.TileLoadPath.MEMORY);
            return new TellusOsmSandSource.TileLookup(cached, false);
         }

         this.cache.invalidate(key);
      }

      OsmQueryMode queryMode = mode == null ? OsmQueryMode.BLOCKING : mode;
      if (queryMode == OsmQueryMode.NON_BLOCKING) {
         this.queueAsyncLoad(key);
         return new TellusOsmSandSource.TileLookup(OsmSandTile.empty(), true);
      } else {
         try {
            return new TellusOsmSandSource.TileLookup(this.cache.get(key), false);
         } catch (Exception error) {
            Tellus.LOGGER.debug("Failed to load Overture sand tile {}", key, error);
            this.tileLoadFailures.add(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_SAND, OsmPerf.TileLoadPath.FAILURE);
            return new TellusOsmSandSource.TileLookup(OsmSandTile.empty(), false);
         }
      }
   }

   private void queueAsyncLoad(TellusOsmSandSource.TileKey key) {
      if (MAX_ASYNC_PREFETCH_LOADS <= 0 || this.pendingAsyncLoads.size() < MAX_ASYNC_PREFETCH_LOADS) {
         if (this.pendingAsyncLoads.add(key)) {
            Util.backgroundExecutor().execute(() -> {
               try {
                  this.cache.get(key);
               } catch (Exception error) {
                  Tellus.LOGGER.debug("Async load failed for Overture sand tile {}", key, error);
                  this.tileLoadFailures.add(key);
                  OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_SAND, OsmPerf.TileLoadPath.FAILURE);
               } finally {
                  this.pendingAsyncLoads.remove(key);
               }
            });
         }
      }
   }

   private OsmSandTile loadTile(TellusOsmSandSource.TileKey key) {
      this.ensureInitialized();
      if (!this.available) {
         return OsmSandTile.empty();
      } else {
         Path cachePath = this.cachePathFor(key);
         Path parsedCachePath = this.parsedCachePathFor(key);
         if (Files.exists(parsedCachePath)) {
            try {
               OsmSandTile parsed = ParsedTileCodec.readSandTile(parsedCachePath);
               this.tileLoadFailures.remove(key);
               OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_SAND, OsmPerf.TileLoadPath.PARSED_DISK);
               return parsed;
            } catch (RuntimeException | IOException error) {
               Tellus.LOGGER.debug("Invalid parsed Overture sand cache tile {}, refetching", key, error);

               try {
                  Files.deleteIfExists(parsedCachePath);
               } catch (IOException deleteError) {
                  Tellus.LOGGER.debug("Failed to delete invalid parsed Overture sand cache tile {}", parsedCachePath, deleteError);
               }
            }
         }

         if (Files.exists(cachePath)) {
            try {
               byte[] payload = this.readCompressed(cachePath);
               OsmSandTile parsed = this.parseVectorTile(payload, key);
               this.cacheParsedTile(parsedCachePath, parsed);
               this.tileLoadFailures.remove(key);
               OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_SAND, OsmPerf.TileLoadPath.RAW_DISK);
               return parsed;
            } catch (RuntimeException | IOException error) {
               Tellus.LOGGER.debug("Invalid Overture sand cache tile {}, refetching", key, error);

               try {
                  Files.deleteIfExists(cachePath);
               } catch (IOException deleteError) {
                  Tellus.LOGGER.debug("Failed to delete invalid Overture sand cache tile {}", cachePath, deleteError);
               }
            }
         }

         byte[] payload = this.fetchTilePayloadWithRetry(key);

         OsmSandTile parsed;
         try {
            parsed = this.parseVectorTile(payload, key);
         } catch (RuntimeException error) {
            Tellus.LOGGER.warn("Overture sand parse failed for tile {}", key, error);
            this.tileLoadFailures.add(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_SAND, OsmPerf.TileLoadPath.FAILURE);
            throw new RuntimeException("Overture sand parse failed for tile " + key, error);
         }

         this.cacheTile(cachePath, payload);
         this.cacheParsedTile(parsedCachePath, parsed);
         this.tileLoadFailures.remove(key);
         OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_SAND, OsmPerf.TileLoadPath.NETWORK);
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
               Tellus.LOGGER.warn("Unexpected Overture sand PMTiles tile type {}, expected MVT", header.tileType());
            }

            resolvedMinZoom = header.minZoom();
            resolvedMaxZoom = header.maxZoom();
            sourceAvailable = true;
         } catch (IOException error) {
            sourceAvailable = false;
            Tellus.LOGGER.warn("Overture sand PMTiles unavailable, OSM sand disabled", error);
         }

         this.available = sourceAvailable;
         this.minZoom = resolvedMinZoom;
         this.maxZoom = resolvedMaxZoom;
         this.initialized = true;
      }
   }

   private byte[] fetchTilePayloadWithRetry(TellusOsmSandSource.TileKey key) {
      RuntimeException lastFailure = null;

      for (int attempt = 1; attempt <= FETCH_RETRY_ATTEMPTS; attempt++) {
         try {
            byte[] fetched = this.pmTilesReader.getTileBytes(key.zoom(), key.x(), key.y());
            return fetched == null ? EMPTY_TILE_PAYLOAD : fetched;
         } catch (IOException error) {
            lastFailure = new RuntimeException("Overture sand fetch failed for tile " + key, error);
            if (attempt < FETCH_RETRY_ATTEMPTS) {
               continue;
            }
            break;
         }
      }

      this.tileLoadFailures.add(key);
      OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_SAND, OsmPerf.TileLoadPath.FAILURE);
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
         Tellus.LOGGER.debug("Failed to cache Overture sand tile {}", cachePath, error);
      }
   }

   private void cacheParsedTile(Path cachePath, OsmSandTile tile) {
      try {
         ParsedTileCodec.writeSandTile(cachePath, tile);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache parsed Overture sand tile {}", cachePath, error);
      }
   }

   private byte[] readCompressed(Path path) throws IOException {
      try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
         return input.readAllBytes();
      }
   }

   private OsmSandTile parseVectorTile(byte[] payload, TellusOsmSandSource.TileKey key) {
      if (payload.length == 0) {
         return OsmSandTile.empty();
      } else {
         Tile tile;
         try {
            tile = Tile.parseFrom(payload);
         } catch (Exception error) {
            throw new RuntimeException("Failed to decode sand MVT payload", error);
         }

         if (tile.getLayersCount() == 0) {
            return OsmSandTile.empty();
         } else {
            List<OsmSandFeature> features = new ArrayList<>();

            for (Layer layer : tile.getLayersList()) {
               if (LAND_LAYER_NAME.equals(layer.getName())) {
                  int extent = layer.hasExtent() && layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_TILE_EXTENT;

                  for (Feature feature : layer.getFeaturesList()) {
                     if (feature.getType() == GeomType.POLYGON) {
                        OsmSandFeature parsed = this.parseFeature(feature, layer, key, extent);
                        if (parsed != null) {
                           features.add(parsed);
                        }
                     }
                  }
               }
            }

            return features.isEmpty() ? OsmSandTile.empty() : new OsmSandTile(features);
         }
      }
   }

   private OsmSandFeature parseFeature(Feature feature, Layer layer, TellusOsmSandSource.TileKey key, int extent) {
      Map<String, Object> tags = decodeTags(feature, layer);
      if (!isSandLikeFeature(tags)) {
         return null;
      } else {
         List<List<TellusOsmSandSource.TilePoint>> rings = decodePolygonRings(feature.getGeometryList());
         if (rings.isEmpty()) {
            return null;
         } else {
            double[][] longitudes = new double[rings.size()][];
            double[][] latitudes = new double[rings.size()][];

            for (int part = 0; part < rings.size(); part++) {
               List<TellusOsmSandSource.TilePoint> ring = rings.get(part);
               int points = ring.size();
               double[] lonPart = new double[points];
               double[] latPart = new double[points];
               int count = 0;
               double previousLon = Double.NaN;
               double previousLat = Double.NaN;

               for (TellusOsmSandSource.TilePoint point : ring) {
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

            return new OsmSandFeature(resolveFeatureId(feature, tags), longitudes, latitudes);
         }
      }
   }

   private static boolean isSandLikeFeature(Map<String, Object> tags) {
      String subtype = nonBlank(asString(tags.get("subtype")));
      if (SAND_SUBTYPE.equalsIgnoreCase(subtype)) {
         return true;
      } else {
         String classTag = nonBlank(asString(tags.get("class")));
         return "sand".equalsIgnoreCase(classTag) || "beach".equalsIgnoreCase(classTag) || "dune".equalsIgnoreCase(classTag);
      }
   }

   private static List<List<TellusOsmSandSource.TilePoint>> decodePolygonRings(List<Integer> geometry) {
      if (geometry != null && !geometry.isEmpty()) {
         List<List<TellusOsmSandSource.TilePoint>> rings = new ArrayList<>();
         List<TellusOsmSandSource.TilePoint> current = null;
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
                        current.add(new TellusOsmSandSource.TilePoint(cursorX, cursorY));
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
                        current.add(new TellusOsmSandSource.TilePoint(cursorX, cursorY));
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

   private static void addRing(List<List<TellusOsmSandSource.TilePoint>> rings, List<TellusOsmSandSource.TilePoint> points) {
      List<TellusOsmSandSource.TilePoint> cleaned = new ArrayList<>(points.size());

      for (TellusOsmSandSource.TilePoint point : points) {
         if (cleaned.isEmpty() || !samePoint(cleaned.get(cleaned.size() - 1), point)) {
            cleaned.add(point);
         }
      }

      if (cleaned.size() >= 3) {
         TellusOsmSandSource.TilePoint first = cleaned.get(0);
         TellusOsmSandSource.TilePoint last = cleaned.get(cleaned.size() - 1);
         if (!samePoint(first, last)) {
            cleaned.add(first);
         }

         if (cleaned.size() >= 4) {
            rings.add(List.copyOf(cleaned));
         }
      }
   }

   private static boolean samePoint(TellusOsmSandSource.TilePoint a, TellusOsmSandSource.TilePoint b) {
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

   private Path cachePathFor(TellusOsmSandSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".mvt.gz");
   }

   private Path parsedCachePathFor(TellusOsmSandSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".pm.parsed.bin");
   }

   private static TellusOsmSandSource.TileKey tileKeyForBlock(double blockX, double blockZ, double worldScale, int zoom) {
      if (worldScale <= 0.0) {
         return null;
      } else {
         double blocksPerDegree = METERS_PER_DEGREE / worldScale;
         double lon = clampLon(blockX / blocksPerDegree);
         double lat = clampLat(EarthProjection.blockZToLat(blockZ, worldScale));
         double n = tilesPerAxis(zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(Math.toRadians(lat)) + 1.0 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2.0 * n;
         return !(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n) ? new TellusOsmSandSource.TileKey(zoom, Mth.floor(x), Mth.floor(y)) : null;
      }
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

   private record TileKey(int zoom, int x, int y) {
   }

   private record TileLookup(OsmSandTile tile, boolean cacheMiss) {
   }

   private record TilePoint(int x, int y) {
   }
}
