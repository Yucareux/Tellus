package com.yucareux.tellus.world.data.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.tellus.util.TellusDiagnostics;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OverpassExternalFeatureSource implements ExternalFeatureSource {
   public static final String ENABLED_PROPERTY = "tellus.arnis.overpass.enabled";
   public static final String ENDPOINTS_PROPERTY = "tellus.arnis.overpass.endpoints";
   public static final String NETWORK_MODE_PROPERTY = "tellus.arnis.overpass.network";
   public static final String CITY_DETAILS_PROPERTY = "tellus.arnis.overpass.cityDetails";
   private static final String SOURCE = "arnis-overpass";
   private static final String CITY_CACHE_PROFILE = "city-v7";
   private static final String NETWORK_CACHE_FIRST = "cache-first";
   private static final String NETWORK_CACHE_ONLY = "cache-only";
   private static final String NETWORK_OFF = "off";
   private static final String DEFAULT_ENDPOINTS = String.join(
      ",",
      "https://overpass-api.de/api/interpreter",
      "https://lz4.overpass-api.de/api/interpreter",
      "https://z.overpass-api.de/api/interpreter",
      "https://overpass.private.coffee/api/interpreter"
   );
   private static final Logger LOGGER = LoggerFactory.getLogger("tellus");
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final int QUERY_ZOOM = intProperty("tellus.arnis.overpass.queryZoom", 14, 0, 20);
   private static final int QUERY_TIMEOUT_SECONDS = intProperty("tellus.arnis.overpass.queryTimeoutSec", 60, 5, 360);
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.arnis.overpass.connectTimeoutMs", 7000, 1, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.arnis.overpass.readTimeoutMs", 60000, 1, 360000);
   private static final long MIN_REQUEST_SPACING_MS = longProperty("tellus.arnis.overpass.minSpacingMs", 500L, 0L, 60000L);
   private static final long FAILURE_COOLDOWN_MS = longProperty("tellus.arnis.overpass.failureCooldownMs", 60000L, 0L, 600000L);
   private static final int MAX_KEYS_PER_QUERY = intProperty("tellus.arnis.overpass.maxTilesPerQuery", 16, 1, 256);
   private static final int MAX_NETWORK_TILES_PER_SESSION = intProperty("tellus.arnis.overpass.maxNetworkTilesPerSession", 96, 0, 1000000);
   private static final int PROBE_TIMEOUT_SECONDS = intProperty("tellus.arnis.overpass.probeTimeoutSec", 5, 1, 60);
   private static final int PROBE_CONNECT_TIMEOUT_MS = intProperty("tellus.arnis.overpass.probeConnectTimeoutMs", 3000, 1, 60000);
   private static final int PROBE_READ_TIMEOUT_MS = intProperty("tellus.arnis.overpass.probeReadTimeoutMs", 7000, 1, 120000);
   private static final int PREFETCH_MAX_TILES = intProperty("tellus.arnis.overpass.prefetchMaxTiles", 32, 1, 4096);
   private static final String PROBE_QUERY = String.format(Locale.ROOT, "[out:json][timeout:%d];node(0,0,0.001,0.001);out ids 1;", PROBE_TIMEOUT_SECONDS);

   private final boolean enabled;
   private final boolean networkEnabled;
   private final Path cacheRoot;
   private final URI[] endpoints;
   private final ConcurrentMap<TileKey, TileFeatures> memoryCache = new ConcurrentHashMap<>();
   private final ConcurrentMap<TileKey, Long> failedUntilMs = new ConcurrentHashMap<>();
   private final Semaphore requestGuard = new Semaphore(1, true);
   private final AtomicLong nextAllowedRequestMs = new AtomicLong(0L);
   private final AtomicLong endpointCursor = new AtomicLong(0L);
   private final AtomicLong networkTilesReserved = new AtomicLong(0L);
   private final AtomicLong skippedNetworkTiles = new AtomicLong(0L);

   private OverpassExternalFeatureSource(boolean enabled, boolean networkEnabled, Path cacheRoot, URI[] endpoints) {
      this.enabled = enabled;
      this.networkEnabled = networkEnabled;
      this.cacheRoot = cacheRoot;
      this.endpoints = endpoints;
   }

   public static OverpassExternalFeatureSource createDefault() {
      boolean enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
      if (!enabled) {
         TellusDiagnostics.traffic("Overpass source disabled by %s=false", ENABLED_PROPERTY);
         return disabled();
      }
      String networkMode = normalizedNetworkMode(System.getProperty(NETWORK_MODE_PROPERTY, NETWORK_CACHE_FIRST));
      if (NETWORK_OFF.equals(networkMode)) {
         TellusDiagnostics.traffic("Overpass source disabled by %s=%s", NETWORK_MODE_PROPERTY, NETWORK_OFF);
         return disabled();
      }
      Path cacheRoot = defaultCacheRoot();
      String endpoints = System.getProperty(ENDPOINTS_PROPERTY, DEFAULT_ENDPOINTS);
      URI[] parsedEndpoints = parseEndpoints(endpoints);
      TellusDiagnostics.traffic(
         "Overpass source ready networkMode=%s networkEnabled=%s cacheRoot=%s endpoints=%d queryZoom=%d cityDetails=%s maxNetworkTiles=%d",
         networkMode,
         !NETWORK_CACHE_ONLY.equals(networkMode),
         cacheRoot,
         parsedEndpoints.length,
         QUERY_ZOOM,
         cityDetailsEnabled(),
         MAX_NETWORK_TILES_PER_SESSION
      );
      return new OverpassExternalFeatureSource(true, !NETWORK_CACHE_ONLY.equals(networkMode), cacheRoot, parsedEndpoints);
   }

   public static CacheEstimate estimateConfiguredCache(GeoBounds bounds) {
      Objects.requireNonNull(bounds, "bounds");
      if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
         return CacheEstimate.disabled();
      }

      String networkMode = normalizedNetworkMode(System.getProperty(NETWORK_MODE_PROPERTY, NETWORK_CACHE_FIRST));
      if (NETWORK_OFF.equals(networkMode)) {
         return CacheEstimate.disabled();
      }

      return estimateCache(bounds, defaultCacheRoot(), !NETWORK_CACHE_ONLY.equals(networkMode));
   }

   public static PrefetchResult prefetchConfiguredBounds(GeoBounds bounds) {
      return prefetchConfiguredBounds(bounds, PREFETCH_MAX_TILES);
   }

   public static PrefetchResult prefetchConfiguredBounds(GeoBounds bounds, int maxMissingTiles) {
      Objects.requireNonNull(bounds, "bounds");
      OverpassExternalFeatureSource source = createDefault();
      if (!source.enabled) {
         return PrefetchResult.disabled();
      }
      return source.prefetchBounds(bounds, maxMissingTiles);
   }

   public static OverpassExternalFeatureSource disabled() {
      return new OverpassExternalFeatureSource(false, false, Path.of("."), new URI[0]);
   }

   public boolean available() {
      return this.enabled;
   }

   public PrefetchResult prefetchBounds(GeoBounds bounds, int maxMissingTiles) {
      Objects.requireNonNull(bounds, "bounds");
      if (!this.enabled) {
         return PrefetchResult.disabled();
      }

      CacheEstimate before = estimateCache(bounds, this.cacheRoot, this.networkEnabled);
      TellusDiagnostics.traffic(
         "Overpass prefetch start bounds=%s tiles=%d cached=%d cityCached=%d missing=%d networkEnabled=%s maxMissing=%d",
         bounds,
         before.totalTiles(),
         before.cachedTiles(),
         before.cityDetailCachedTiles(),
         before.missingTiles(),
         this.networkEnabled,
         maxMissingTiles
      );
      if (!this.networkEnabled || maxMissingTiles <= 0 || before.missingTiles() <= 0) {
         return new PrefetchResult(before, before, 0, 0, 0);
      }

      List<TileKey> keys = tileKeysForBounds(bounds);
      sortByDistanceToBoundsCenter(keys, bounds);
      boolean requireCityDetails = cityDetailsEnabled();
      int attempted = 0;
      int cachedAfterAttempt = 0;
      for (TileKey key : keys) {
         Path cachePath = this.cachePathFor(key);
         if (this.tileReadyForPrefetch(cachePath, requireCityDetails)) {
            continue;
         }
         if (attempted >= maxMissingTiles) {
            break;
         }
         attempted++;
         this.tileForKey(key, requireCityDetails);
         if (this.tileReadyForPrefetch(cachePath, requireCityDetails)) {
            cachedAfterAttempt++;
         }
      }

      CacheEstimate after = estimateCache(bounds, this.cacheRoot, this.networkEnabled);
      TellusDiagnostics.traffic(
         "Overpass prefetch done bounds=%s attempted=%d cachedAfterAttempt=%d failed=%d missingBefore=%d missingAfter=%d cachedBytes=%d",
         bounds,
         attempted,
         cachedAfterAttempt,
         Math.max(0, attempted - cachedAfterAttempt),
         before.missingTiles(),
         after.missingTiles(),
         after.cachedBytes()
      );
      return new PrefetchResult(before, after, attempted, cachedAfterAttempt, Math.max(0, attempted - cachedAfterAttempt));
   }

   private boolean tileReadyForPrefetch(Path cachePath, boolean requireCityDetails) {
      if (!Files.exists(cachePath)) {
         return false;
      }
      return !requireCityDetails || this.cacheHasCityProfile(cachePath);
   }

   public static List<EndpointProbeResult> probeConfiguredEndpoints() {
      if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
         TellusDiagnostics.traffic("Overpass probe skipped: source disabled");
         return List.of(new EndpointProbeResult("Overpass", false, -1, 0L, "disabled"));
      }

      String networkMode = normalizedNetworkMode(System.getProperty(NETWORK_MODE_PROPERTY, NETWORK_CACHE_FIRST));
      if (NETWORK_OFF.equals(networkMode)) {
         TellusDiagnostics.traffic("Overpass probe skipped: network off");
         return List.of(new EndpointProbeResult("Overpass", false, -1, 0L, "network off"));
      }

      URI[] endpoints = parseEndpoints(System.getProperty(ENDPOINTS_PROPERTY, DEFAULT_ENDPOINTS));
      List<EndpointProbeResult> results = new ArrayList<>(endpoints.length);
      for (URI endpoint : endpoints) {
         results.add(probeEndpoint(endpoint));
      }
      return List.copyOf(results);
   }

   @Override
   public List<ExternalRoadFeature> roadsForBounds(GeoBounds bounds) {
      if (!this.enabled) {
         return List.of();
      }

      List<ExternalRoadFeature> roads = new ArrayList<>();
      for (TileFeatures tile : this.tilesForBounds(bounds, false)) {
         roads.addAll(tile.roadsForBounds(bounds));
      }
      return List.copyOf(roads);
   }

   @Override
   public List<ExternalBuildingFeature> buildingsForBounds(GeoBounds bounds) {
      if (!this.enabled) {
         return List.of();
      }

      List<ExternalBuildingFeature> buildings = new ArrayList<>();
      for (TileFeatures tile : this.tilesForBounds(bounds, false)) {
         buildings.addAll(tile.buildingsForBounds(bounds));
      }
      return List.copyOf(buildings);
   }

   @Override
   public List<ExternalAreaFeature> areasForBounds(GeoBounds bounds) {
      if (!this.enabled || !cityDetailsEnabled()) {
         return List.of();
      }

      List<ExternalAreaFeature> areas = new ArrayList<>();
      for (TileFeatures tile : this.tilesForBounds(bounds, true)) {
         areas.addAll(tile.areasForBounds(bounds));
      }
      return List.copyOf(areas);
   }

   @Override
   public List<ExternalLineFeature> linesForBounds(GeoBounds bounds) {
      if (!this.enabled || !cityDetailsEnabled()) {
         return List.of();
      }

      List<ExternalLineFeature> lines = new ArrayList<>();
      for (TileFeatures tile : this.tilesForBounds(bounds, true)) {
         lines.addAll(tile.linesForBounds(bounds));
      }
      return List.copyOf(lines);
   }

   @Override
   public List<ExternalPointFeature> pointsForBounds(GeoBounds bounds) {
      if (!this.enabled || !cityDetailsEnabled()) {
         return List.of();
      }

      List<ExternalPointFeature> points = new ArrayList<>();
      for (TileFeatures tile : this.tilesForBounds(bounds, true)) {
         points.addAll(tile.pointsForBounds(bounds));
      }
      return List.copyOf(points);
   }

   private List<TileFeatures> tilesForBounds(GeoBounds bounds, boolean requireCityDetails) {
      List<TileKey> keys = tileKeysForBounds(bounds);
      if (keys.isEmpty()) {
         return List.of();
      }

      if (keys.size() > MAX_KEYS_PER_QUERY) {
         LOGGER.warn("Skipping Arnis Overpass query over {} tiles, limit is {}", keys.size(), MAX_KEYS_PER_QUERY);
         TellusDiagnostics.traffic(
            "Overpass query skipped bounds=%s tiles=%d limit=%d requireCityDetails=%s",
            bounds,
            keys.size(),
            MAX_KEYS_PER_QUERY,
            requireCityDetails
         );
         return List.of();
      }

      List<TileFeatures> tiles = new ArrayList<>(keys.size());
      for (TileKey key : keys) {
         tiles.add(this.tileForKey(key, requireCityDetails));
      }
      return tiles;
   }

   private TileFeatures tileForKey(TileKey key) {
      return this.tileForKey(key, false);
   }

   private TileFeatures tileForKey(TileKey key, boolean requireCityDetails) {
      TileFeatures cached = this.memoryCache.get(key);
      if (cached != null && (!requireCityDetails || cached.cityDetailsLoaded())) {
         return cached;
      }

      Long failedUntil = this.failedUntilMs.get(key);
      if (failedUntil != null && System.currentTimeMillis() < failedUntil) {
         return TileFeatures.empty(key.bounds());
      }

      TileFeatures loaded = this.loadTile(key, requireCityDetails, cached);
      this.memoryCache.put(key, loaded);
      return loaded;
   }

   private TileFeatures loadTile(TileKey key, boolean requireCityDetails, TileFeatures fallback) {
      Path cachePath = this.cachePathFor(key);
      if (Files.exists(cachePath)) {
         try {
            TileFeatures parsed = this.parseTile(key, this.readCompressed(cachePath), this.cacheHasCityProfile(cachePath));
            if (!requireCityDetails || parsed.cityDetailsLoaded()) {
               TellusDiagnostics.traffic(
                  "Overpass cache hit tile=%s cityDetails=%s roads=%d buildings=%d areas=%d lines=%d points=%d",
                  key,
                  parsed.cityDetailsLoaded(),
                  parsed.roads().size(),
                  parsed.buildings().size(),
                  parsed.areas().size(),
                  parsed.lines().size(),
                  parsed.points().size()
               );
               return parsed;
            }
            TellusDiagnostics.traffic("Overpass cache missing city profile tile=%s; refetching with city details", key);
            fallback = parsed;
         } catch (IOException | RuntimeException error) {
            LOGGER.debug("Invalid Arnis Overpass cache tile {}, refetching", key, error);
            TellusDiagnostics.traffic("Overpass cache invalid tile=%s error=%s", key, shortError(error));
            try {
               Files.deleteIfExists(cachePath);
            } catch (IOException deleteError) {
               LOGGER.debug("Failed to delete invalid Arnis Overpass cache tile {}", cachePath, deleteError);
            }
         }
      }

      if (!this.networkEnabled) {
         TellusDiagnostics.traffic("Overpass cache miss tile=%s networkEnabled=false fallback=%s", key, fallback != null);
         return fallback != null ? fallback : TileFeatures.empty(key.bounds());
      }
      if (!this.reserveNetworkTile()) {
         long skipped = this.skippedNetworkTiles.incrementAndGet();
         if (skipped == 1L || skipped % 64L == 0L) {
            LOGGER.warn(
               "Skipping Arnis Overpass network tile {} because session network tile budget {} is exhausted; cached data and Overture fallback remain available",
               key,
               MAX_NETWORK_TILES_PER_SESSION
            );
         }
         TellusDiagnostics.traffic(
            "Overpass network budget exhausted tile=%s reserved=%d budget=%d skipped=%d fallback=%s",
            key,
            this.networkTilesReserved.get(),
            MAX_NETWORK_TILES_PER_SESSION,
            skipped,
            fallback != null
         );
         return fallback != null ? fallback : TileFeatures.empty(key.bounds());
      }

      try {
         boolean includeCityDetails = requireCityDetails && cityDetailsEnabled();
         String response = this.fetchTile(key, includeCityDetails);
         TileFeatures parsed = this.parseTile(key, response, includeCityDetails);
         this.cacheTile(cachePath, response, includeCityDetails);
         this.failedUntilMs.remove(key);
         TellusDiagnostics.traffic(
            "Overpass tile fetched tile=%s cityDetails=%s bytes=%d roads=%d buildings=%d areas=%d lines=%d points=%d",
            key,
            includeCityDetails,
            response.getBytes(StandardCharsets.UTF_8).length,
            parsed.roads().size(),
            parsed.buildings().size(),
            parsed.areas().size(),
            parsed.lines().size(),
            parsed.points().size()
         );
         return parsed;
      } catch (IOException | RuntimeException error) {
         LOGGER.warn("Arnis Overpass tile unavailable {}", key, error);
         TellusDiagnostics.traffic("Overpass tile unavailable tile=%s cooldownMs=%d error=%s", key, FAILURE_COOLDOWN_MS, shortError(error));
         this.failedUntilMs.put(key, System.currentTimeMillis() + FAILURE_COOLDOWN_MS);
         return fallback != null ? fallback : TileFeatures.empty(key.bounds());
      }
   }

   private boolean reserveNetworkTile() {
      if (MAX_NETWORK_TILES_PER_SESSION <= 0) {
         return false;
      }

      while (true) {
         long current = this.networkTilesReserved.get();
         if (current >= MAX_NETWORK_TILES_PER_SESSION) {
            return false;
         }
         if (this.networkTilesReserved.compareAndSet(current, current + 1L)) {
            return true;
         }
      }
   }

   private String fetchTile(TileKey key, boolean includeCityDetails) throws IOException {
      GeoBounds bounds = key.bounds();
      String query = overpassQuery(bounds, includeCityDetails);

      IOException lastError = null;
      long startEndpoint = this.endpointCursor.getAndIncrement();
      for (int attempt = 0; attempt < this.endpoints.length; attempt++) {
         URI endpoint = this.endpoints[Math.floorMod(startEndpoint + attempt, this.endpoints.length)];
         try {
            TellusDiagnostics.traffic("Overpass request start tile=%s endpoint=%s cityDetails=%s attempt=%d", key, endpoint, includeCityDetails, attempt + 1);
            return this.executeQuery(endpoint, query);
         } catch (IOException error) {
            lastError = error;
            TellusDiagnostics.traffic("Overpass request failed tile=%s endpoint=%s attempt=%d error=%s", key, endpoint, attempt + 1, shortError(error));
         }
      }

      throw new IOException("all Arnis Overpass endpoints failed", lastError);
   }

   private static String overpassQuery(GeoBounds bounds, boolean includeCityDetails) {
      StringBuilder selectors = new StringBuilder()
         .append("way[\"highway\"];")
         .append("way[\"building\"];")
         .append("way[\"building:part\"];")
         .append("relation[\"building\"];")
         .append("relation[\"building:part\"];")
         .append("relation[\"type\"=\"multipolygon\"][\"building\"];")
         .append("relation[\"type\"=\"multipolygon\"][\"building:part\"];");
      if (includeCityDetails) {
         selectors
            .append("way[\"barrier\"];")
            .append("relation[\"barrier\"];")
            .append("way[\"railway\"~\"^(rail|light_rail|subway|tram)$\"];")
            .append("way[\"waterway\"~\"^(river|stream|canal|ditch|drain)$\"];")
            .append("way[\"water\"];")
            .append("relation[\"water\"];")
            .append("way[\"natural\"=\"water\"];")
            .append("relation[\"natural\"=\"water\"];")
            .append("way[\"amenity\"=\"parking\"];")
            .append("relation[\"amenity\"=\"parking\"];")
            .append("way[\"landuse\"~\"^(grass|residential|commercial|retail|industrial|cemetery|construction|farmland|meadow|recreation_ground|forest|orchard|greenfield|vineyard|education|religious|military|railway|brownfield|landfill|quarry|traffic_island)$\"];")
            .append("relation[\"landuse\"~\"^(grass|residential|commercial|retail|industrial|cemetery|construction|farmland|meadow|recreation_ground|forest|orchard|greenfield|vineyard|education|religious|military|railway|brownfield|landfill|quarry|traffic_island)$\"];")
            .append("way[\"leisure\"~\"^(park|garden|pitch|playground|track|sports_centre|recreation_ground|nature_reserve|disc_golf_course|golf_course|schoolyard|beach_resort|dog_park|swimming_pool|swimming_area|bathing_place|outdoor_seating|water_park|slipway|ice_rink)$\"];")
            .append("relation[\"leisure\"~\"^(park|garden|pitch|playground|track|sports_centre|recreation_ground|nature_reserve|disc_golf_course|golf_course|schoolyard|beach_resort|dog_park|swimming_pool|swimming_area|bathing_place|outdoor_seating|water_park|slipway|ice_rink)$\"];")
            .append("way[\"natural\"~\"^(wood|tree_row|scrub|heath|beach|sand|dune|shoal|wetland|bare_rock|scree|grassland|blockfield|glacier|mud|reef|mountain_range|saddle|ridge|shrubbery|tundra|hill|cliff)$\"];")
            .append("relation[\"natural\"~\"^(wood|tree_row|scrub|heath|beach|sand|dune|shoal|wetland|bare_rock|scree|grassland|blockfield|glacier|mud|reef|mountain_range|saddle|ridge|shrubbery|tundra|hill|cliff)$\"];")
            .append("node[\"highway\"~\"^(traffic_signals|crossing|street_lamp|bus_stop)$\"];")
            .append("node[\"amenity\"~\"^(bench|bicycle_parking|fountain|shelter|fuel|recycling|waste_disposal|waste_basket|vending_machine|atm|drinking_water)$\"];")
            .append("node[\"advertising\"~\"^(column|flag|poster_box)$\"];")
            .append("node[\"emergency\"=\"fire_hydrant\"];")
            .append("node[\"historic\"~\"^(memorial|monument|wayside_cross)$\"];")
            .append("node[\"tourism\"=\"information\"];")
            .append("node[\"man_made\"~\"^(antenna|mast|chimney|water_well|water_tower)$\"];")
            .append("node[\"power\"~\"^(tower|pole)$\"];")
            .append("node[\"barrier\"~\"^(bollard|block|entrance|gate|swing_gate|lift_gate|stile)$\"];")
            .append("node[\"railway\"~\"^(level_crossing|crossing|tram_stop)$\"];")
            .append("way[\"power\"~\"^(line|minor_line)$\"];")
            .append("way[\"man_made\"=\"pier\"];")
            .append("node[\"natural\"=\"tree\"];")
            .append("node[\"entrance\"];")
            .append("node[\"door\"];");
      }
      return String.format(
         Locale.ROOT,
         "[out:json][timeout:%d][bbox:%.7f,%.7f,%.7f,%.7f];(%s);out tags geom;",
         QUERY_TIMEOUT_SECONDS,
         bounds.south(),
         bounds.west(),
         bounds.north(),
         bounds.east(),
         selectors
      );
   }

   private String executeQuery(URI endpoint, String query) throws IOException {
      this.acquireRequestGuard();
      long startMs = System.currentTimeMillis();
      try {
         this.applyRateLimitDelay();
         URI requestUri = URI.create(endpoint.toString() + "?data=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
         HttpURLConnection connection = (HttpURLConnection)requestUri.toURL().openConnection();
         try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Tellus-Arnis/1.0 (Minecraft Mod)");

            int status = connection.getResponseCode();
            if (status != 200) {
               throw new IOException("Arnis Overpass HTTP " + status + " (" + endpoint.getHost() + ")");
            }
            try (InputStream input = Objects.requireNonNull(connection.getInputStream(), "overpassResponse")) {
               byte[] response = input.readAllBytes();
               TellusDiagnostics.traffic(
                  "Overpass request ok endpoint=%s status=%d bytes=%d elapsedMs=%d",
                  endpoint,
                  status,
                  response.length,
                  System.currentTimeMillis() - startMs
               );
               return new String(response, StandardCharsets.UTF_8);
            }
         } finally {
            connection.disconnect();
         }
      } finally {
         this.nextAllowedRequestMs.accumulateAndGet(System.currentTimeMillis() + MIN_REQUEST_SPACING_MS, Math::max);
         this.requestGuard.release();
      }
   }

   private static EndpointProbeResult probeEndpoint(URI endpoint) {
      long startMs = System.currentTimeMillis();
      int status = -1;
      try {
         URI requestUri = URI.create(endpoint.toString() + "?data=" + URLEncoder.encode(PROBE_QUERY, StandardCharsets.UTF_8));
         HttpURLConnection connection = (HttpURLConnection)requestUri.toURL().openConnection();
         try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Tellus-Arnis/1.0 (Minecraft Mod; connectivity probe)");
            status = connection.getResponseCode();
            if (status == 200) {
               try (InputStream input = Objects.requireNonNull(connection.getInputStream(), "overpassProbeResponse")) {
                  input.readNBytes(256);
               }
               EndpointProbeResult result = new EndpointProbeResult(endpoint.toString(), true, status, System.currentTimeMillis() - startMs, "ok");
               TellusDiagnostics.traffic("Overpass probe endpoint=%s ok=%s status=%d elapsedMs=%d message=%s", endpoint, result.ok(), result.httpStatus(), result.elapsedMs(), result.message());
               return result;
            }
            EndpointProbeResult result = new EndpointProbeResult(endpoint.toString(), false, status, System.currentTimeMillis() - startMs, "HTTP " + status);
            TellusDiagnostics.traffic("Overpass probe endpoint=%s ok=%s status=%d elapsedMs=%d message=%s", endpoint, result.ok(), result.httpStatus(), result.elapsedMs(), result.message());
            return result;
         } finally {
            connection.disconnect();
         }
      } catch (IOException | RuntimeException error) {
         EndpointProbeResult result = new EndpointProbeResult(endpoint.toString(), false, status, System.currentTimeMillis() - startMs, shortError(error));
         TellusDiagnostics.traffic("Overpass probe endpoint=%s ok=%s status=%d elapsedMs=%d message=%s", endpoint, result.ok(), result.httpStatus(), result.elapsedMs(), result.message());
         return result;
      }
   }

   private static String shortError(Throwable error) {
      String message = error.getMessage();
      return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
   }

   private void acquireRequestGuard() throws IOException {
      try {
         this.requestGuard.acquire();
      } catch (InterruptedException error) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while waiting for Arnis Overpass request slot", error);
      }
   }

   private void applyRateLimitDelay() throws IOException {
      long waitMs = this.nextAllowedRequestMs.get() - System.currentTimeMillis();
      if (waitMs > 0L) {
         try {
            TimeUnit.MILLISECONDS.sleep(waitMs);
         } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while rate-limiting Arnis Overpass requests", error);
         }
      }
   }

   private TileFeatures parseTile(TileKey key, String response, boolean cityDetailsLoaded) {
      JsonElement parsed = JsonParser.parseString(response);
      if (!parsed.isJsonObject()) {
         return TileFeatures.empty(key.bounds());
      }

      JsonArray elements = parsed.getAsJsonObject().getAsJsonArray("elements");
      if (elements == null || elements.isEmpty()) {
         return TileFeatures.empty(key.bounds());
      }

      List<ExternalRoadFeature> roads = new ArrayList<>();
      List<ExternalBuildingFeature> buildings = new ArrayList<>();
      List<ExternalAreaFeature> areas = new ArrayList<>();
      List<ExternalLineFeature> lines = new ArrayList<>();
      List<ExternalPointFeature> points = new ArrayList<>();
      for (JsonElement element : elements) {
         if (!element.isJsonObject()) {
            continue;
         }
         JsonObject object = element.getAsJsonObject();
         String type = stringOrDefault(object, "", "type");
         if (!"way".equals(type) && !"relation".equals(type) && !"node".equals(type)) {
            continue;
         }
         Map<String, String> tags = parseTags(object);
         String id = Long.toString(longOrDefault(object, 0L, "id"));
         if (cityDetailsLoaded && "node".equals(type)) {
            ExternalPointFeature point = parsePointFeature("node/" + id, tags, object);
            if (point != null) {
               points.add(point);
            }
            continue;
         }
         if ("way".equals(type) && tags.containsKey("highway")) {
            JsonArray geometry = object.getAsJsonArray("geometry");
            if (geometry == null) {
               continue;
            }
            ExternalRoadFeature road = parseRoad(id, tags, geometry);
            if (road != null) {
               roads.add(road);
            }
         }
         if ("way".equals(type) && (tags.containsKey("building") || tags.containsKey("building:part"))) {
            JsonArray geometry = object.getAsJsonArray("geometry");
            if (geometry == null) {
               continue;
            }
            ExternalBuildingFeature building = parseBuilding(id, tags, geometry);
            if (building != null) {
               buildings.add(building);
            }
         } else if ("relation".equals(type) && (tags.containsKey("building") || tags.containsKey("building:part"))) {
            ExternalBuildingFeature building = parseBuildingRelation(id, tags, object.getAsJsonArray("members"));
            if (building != null) {
               buildings.add(building);
            }
         }
         if (cityDetailsLoaded) {
            if ("way".equals(type)) {
               JsonArray geometry = object.getAsJsonArray("geometry");
               if (geometry != null) {
                  ExternalAreaFeature area = parseArea("way/" + id, tags, geometry);
                  if (area != null) {
                     areas.add(area);
                  }
                  ExternalLineFeature line = parseLine("way/" + id, tags, geometry);
                  if (line != null) {
                     lines.add(line);
                  }
               }
            } else if ("relation".equals(type)) {
               ExternalAreaFeature area = parseAreaRelation("relation/" + id, tags, object.getAsJsonArray("members"));
               if (area != null) {
                  areas.add(area);
               }
            }
         }
      }

      return new TileFeatures(key.bounds(), roads, buildings, areas, lines, points, cityDetailsLoaded);
   }

   private static ExternalRoadFeature parseRoad(String id, Map<String, String> tags, JsonArray geometry) {
      String highway = tags.get("highway");
      RoadClass roadClass = RoadClass.fromHighwayTag(highway);
      if (roadClass == null) {
         return null;
      }
      List<GeoPoint> points = parsePoints(geometry);
      if (points.size() < 2) {
         return null;
      }
      RoadMode mode = roadMode(tags);
      int bridgeLevel = mode == RoadMode.BRIDGE ? Math.max(1, intFromTag(tags.get("layer"), 1)) : 0;
      return new ExternalRoadFeature(SOURCE, "way/" + id, roadClass, mode, bridgeLevel, highway, points, tags);
   }

   private static ExternalBuildingFeature parseBuilding(String id, Map<String, String> tags, JsonArray geometry) {
      List<GeoPoint> ring = parsePoints(geometry);
      if (ring.size() < 3) {
         return null;
      }
      GeoPoint first = ring.get(0);
      GeoPoint last = ring.get(ring.size() - 1);
      if (!first.equals(last)) {
         ring = new ArrayList<>(ring);
         ring.add(first);
      }
      if (ring.size() < 4) {
         return null;
      }

      double height = heightMeters(tags);
      double minHeight = minHeightMeters(tags);
      if (!(height > minHeight)) {
         height = minHeight + 3.2;
      }
      int floorCount = floorCount(tags, height);
      ExternalBuildingKind kind = tags.containsKey("building:part") ? ExternalBuildingKind.PART : ExternalBuildingKind.FOOTPRINT;
      return new ExternalBuildingFeature(SOURCE, "way/" + id, kind, height, minHeight, floorCount, List.of(ring), tags);
   }

   private static ExternalBuildingFeature parseBuildingRelation(String id, Map<String, String> tags, JsonArray members) {
      if (members == null || members.isEmpty()) {
         return null;
      }

      List<List<GeoPoint>> outerSegments = new ArrayList<>();
      List<List<GeoPoint>> innerSegments = new ArrayList<>();
      for (JsonElement memberElement : members) {
         if (!memberElement.isJsonObject()) {
            continue;
         }
         JsonObject member = memberElement.getAsJsonObject();
         if (!"way".equals(stringOrDefault(member, "", "type"))) {
            continue;
         }
         JsonArray geometry = member.getAsJsonArray("geometry");
         if (geometry == null) {
            continue;
         }
         List<GeoPoint> segment = parsePoints(geometry);
         if (segment.size() < 2) {
            continue;
         }
         String role = stringOrDefault(member, "", "role").trim().toLowerCase(Locale.ROOT);
         if ("inner".equals(role)) {
            innerSegments.add(segment);
         } else if (role.isEmpty() || "outer".equals(role) || "outline".equals(role)) {
            outerSegments.add(segment);
         }
      }

      List<List<GeoPoint>> rings = new ArrayList<>();
      rings.addAll(mergeSegmentsToRings(outerSegments));
      if (rings.isEmpty()) {
         return null;
      }
      rings.addAll(mergeSegmentsToRings(innerSegments));

      double height = heightMeters(tags);
      double minHeight = minHeightMeters(tags);
      if (!(height > minHeight)) {
         height = minHeight + 3.2;
      }
      int floorCount = floorCount(tags, height);
      ExternalBuildingKind kind = tags.containsKey("building:part") ? ExternalBuildingKind.PART : ExternalBuildingKind.FOOTPRINT;
      return new ExternalBuildingFeature(SOURCE, "relation/" + id, kind, height, minHeight, floorCount, rings, tags);
   }

   private static ExternalAreaFeature parseArea(String id, Map<String, String> tags, JsonArray geometry) {
      ExternalAreaKind kind = areaKind(tags);
      if (kind == null || tags.containsKey("building") || tags.containsKey("building:part")) {
         return null;
      }
      List<GeoPoint> ring = parsePoints(geometry);
      if (ring.size() < 3) {
         return null;
      }
      closeRing(ring);
      if (ring.size() < 4 || !isClosedRing(ring)) {
         return null;
      }
      return new ExternalAreaFeature(SOURCE, id, kind, areaTypeTag(kind, tags), List.of(List.copyOf(ring)), tags);
   }

   private static ExternalAreaFeature parseAreaRelation(String id, Map<String, String> tags, JsonArray members) {
      ExternalAreaKind kind = areaKind(tags);
      if (kind == null || tags.containsKey("building") || tags.containsKey("building:part")) {
         return null;
      }
      if (members == null || members.isEmpty()) {
         return null;
      }

      List<List<GeoPoint>> outerSegments = new ArrayList<>();
      List<List<GeoPoint>> innerSegments = new ArrayList<>();
      for (JsonElement memberElement : members) {
         if (!memberElement.isJsonObject()) {
            continue;
         }
         JsonObject member = memberElement.getAsJsonObject();
         if (!"way".equals(stringOrDefault(member, "", "type"))) {
            continue;
         }
         JsonArray geometry = member.getAsJsonArray("geometry");
         if (geometry == null) {
            continue;
         }
         List<GeoPoint> segment = parsePoints(geometry);
         if (segment.size() < 2) {
            continue;
         }
         String role = stringOrDefault(member, "", "role").trim().toLowerCase(Locale.ROOT);
         if ("inner".equals(role)) {
            innerSegments.add(segment);
         } else if (role.isEmpty() || "outer".equals(role) || "outline".equals(role)) {
            outerSegments.add(segment);
         }
      }

      List<List<GeoPoint>> rings = new ArrayList<>();
      rings.addAll(mergeSegmentsToRings(outerSegments));
      if (rings.isEmpty()) {
         return null;
      }
      rings.addAll(mergeSegmentsToRings(innerSegments));
      return new ExternalAreaFeature(SOURCE, id, kind, areaTypeTag(kind, tags), rings, tags);
   }

   private static ExternalLineFeature parseLine(String id, Map<String, String> tags, JsonArray geometry) {
      ExternalLineKind kind = lineKind(tags);
      if (kind == null) {
         return null;
      }
      List<GeoPoint> points = parsePoints(geometry);
      if (points.size() < 2) {
         return null;
      }
      return new ExternalLineFeature(SOURCE, id, kind, lineTypeTag(kind, tags), points, tags);
   }

   private static ExternalPointFeature parsePointFeature(String id, Map<String, String> tags, JsonObject object) {
      ExternalPointKind kind = pointKind(tags);
      if (kind == null) {
         return null;
      }
      double lat = doubleOrDefault(object, Double.NaN, "lat");
      double lon = doubleOrDefault(object, Double.NaN, "lon");
      if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
         return null;
      }
      return new ExternalPointFeature(SOURCE, id, kind, pointTypeTag(kind, tags), new GeoPoint(lat, lon), tags);
   }

   private static ExternalAreaKind areaKind(Map<String, String> tags) {
      if ("parking".equalsIgnoreCase(tags.get("amenity"))) {
         return ExternalAreaKind.PARKING;
      }
      if (tags.containsKey("water") || "water".equals(tags.get("natural"))) {
         return ExternalAreaKind.WATER;
      }
      if (tags.containsKey("landuse")) {
         return ExternalAreaKind.LANDUSE;
      }
      if (tags.containsKey("leisure")) {
         return ExternalAreaKind.LEISURE;
      }
      if (tags.containsKey("natural")) {
         return ExternalAreaKind.NATURAL;
      }
      return tags.containsKey("amenity") ? ExternalAreaKind.AMENITY : null;
   }

   private static String areaTypeTag(ExternalAreaKind kind, Map<String, String> tags) {
      return switch (kind) {
         case PARKING, AMENITY -> Objects.toString(tags.get("amenity"), "");
         case LANDUSE -> Objects.toString(tags.get("landuse"), "");
         case LEISURE -> Objects.toString(tags.get("leisure"), "");
         case NATURAL -> Objects.toString(tags.get("natural"), "");
         case WATER -> {
            String water = tags.get("water");
            yield water != null ? water : Objects.toString(tags.get("natural"), "");
         }
      };
   }

   private static ExternalLineKind lineKind(Map<String, String> tags) {
      if (tags.containsKey("barrier")) {
         return ExternalLineKind.BARRIER;
      }
      if (tags.containsKey("railway")) {
         return ExternalLineKind.RAILWAY;
      }
      if (tags.containsKey("waterway")) {
         return ExternalLineKind.WATERWAY;
      }
      if (tags.containsKey("power")) {
         return ExternalLineKind.POWER;
      }
      return "pier".equals(tags.get("man_made")) ? ExternalLineKind.MAN_MADE : null;
   }

   private static String lineTypeTag(ExternalLineKind kind, Map<String, String> tags) {
      return switch (kind) {
         case BARRIER -> Objects.toString(tags.get("barrier"), "");
         case RAILWAY -> Objects.toString(tags.get("railway"), "");
         case WATERWAY -> Objects.toString(tags.get("waterway"), "");
         case POWER -> Objects.toString(tags.get("power"), "");
         case MAN_MADE -> Objects.toString(tags.get("man_made"), "");
      };
   }

   private static ExternalPointKind pointKind(Map<String, String> tags) {
      String highway = tags.get("highway");
      if ("traffic_signals".equals(highway)) {
         return ExternalPointKind.TRAFFIC_SIGNAL;
      }
      if ("crossing".equals(highway)) {
         return ExternalPointKind.CROSSING;
      }
      if ("street_lamp".equals(highway) || "bus_stop".equals(highway)) {
         return ExternalPointKind.HIGHWAY;
      }
      if (tags.containsKey("entrance") || tags.containsKey("door")) {
         return ExternalPointKind.ENTRANCE;
      }
      if (tags.containsKey("amenity")) {
         return ExternalPointKind.AMENITY;
      }
      if ("tree".equals(tags.get("natural"))) {
         return ExternalPointKind.NATURAL;
      }
      if (tags.containsKey("advertising")) {
         return ExternalPointKind.ADVERTISING;
      }
      if (tags.containsKey("emergency")) {
         return ExternalPointKind.EMERGENCY;
      }
      if (tags.containsKey("historic")) {
         return ExternalPointKind.HISTORIC;
      }
      if (tags.containsKey("tourism")) {
         return ExternalPointKind.TOURISM;
      }
      if (tags.containsKey("man_made")) {
         return ExternalPointKind.MAN_MADE;
      }
      if (tags.containsKey("power")) {
         return ExternalPointKind.POWER;
      }
      if (tags.containsKey("barrier")) {
         return ExternalPointKind.BARRIER;
      }
      return tags.containsKey("railway") ? ExternalPointKind.RAILWAY : null;
   }

   private static String pointTypeTag(ExternalPointKind kind, Map<String, String> tags) {
      return switch (kind) {
         case TRAFFIC_SIGNAL, CROSSING, HIGHWAY -> Objects.toString(tags.get("highway"), "");
         case ENTRANCE -> {
            String entrance = tags.get("entrance");
            yield entrance != null ? entrance : Objects.toString(tags.get("door"), "");
         }
         case AMENITY -> Objects.toString(tags.get("amenity"), "");
         case NATURAL -> Objects.toString(tags.get("natural"), "");
         case ADVERTISING -> Objects.toString(tags.get("advertising"), "");
         case EMERGENCY -> Objects.toString(tags.get("emergency"), "");
         case HISTORIC -> Objects.toString(tags.get("historic"), "");
         case TOURISM -> Objects.toString(tags.get("tourism"), "");
         case MAN_MADE -> Objects.toString(tags.get("man_made"), "");
         case POWER -> Objects.toString(tags.get("power"), "");
         case BARRIER -> Objects.toString(tags.get("barrier"), "");
         case RAILWAY -> Objects.toString(tags.get("railway"), "");
      };
   }

   private static List<List<GeoPoint>> mergeSegmentsToRings(List<List<GeoPoint>> segments) {
      List<List<GeoPoint>> remaining = new ArrayList<>();
      for (List<GeoPoint> segment : segments) {
         if (segment.size() >= 2) {
            remaining.add(new ArrayList<>(segment));
         }
      }

      List<List<GeoPoint>> rings = new ArrayList<>();
      while (!remaining.isEmpty()) {
         List<GeoPoint> ring = remaining.remove(0);
         boolean changed = true;
         while (changed && !isClosedRing(ring)) {
            changed = false;
            for (int index = 0; index < remaining.size(); index++) {
               List<GeoPoint> segment = remaining.get(index);
               if (appendOrPrepend(ring, segment)) {
                  remaining.remove(index);
                  changed = true;
                  break;
               }
            }
         }
         closeRing(ring);
         if (ring.size() >= 4 && isClosedRing(ring)) {
            rings.add(List.copyOf(ring));
         }
      }
      return rings;
   }

   private static boolean appendOrPrepend(List<GeoPoint> ring, List<GeoPoint> segment) {
      GeoPoint ringFirst = ring.get(0);
      GeoPoint ringLast = ring.get(ring.size() - 1);
      GeoPoint segmentFirst = segment.get(0);
      GeoPoint segmentLast = segment.get(segment.size() - 1);

      if (samePoint(ringLast, segmentFirst)) {
         ring.addAll(segment.subList(1, segment.size()));
         return true;
      }
      if (samePoint(ringLast, segmentLast)) {
         for (int index = segment.size() - 2; index >= 0; index--) {
            ring.add(segment.get(index));
         }
         return true;
      }
      if (samePoint(ringFirst, segmentLast)) {
         ring.addAll(0, segment.subList(0, segment.size() - 1));
         return true;
      }
      if (samePoint(ringFirst, segmentFirst)) {
         for (int index = 1; index < segment.size(); index++) {
            ring.add(0, segment.get(index));
         }
         return true;
      }
      return false;
   }

   private static boolean isClosedRing(List<GeoPoint> ring) {
      return ring.size() >= 2 && samePoint(ring.get(0), ring.get(ring.size() - 1));
   }

   private static void closeRing(List<GeoPoint> ring) {
      if (ring.size() >= 3 && !isClosedRing(ring)) {
         ring.add(ring.get(0));
      }
   }

   private static boolean samePoint(GeoPoint first, GeoPoint second) {
      return Math.abs(first.latitude() - second.latitude()) < 1.0E-7 && Math.abs(first.longitude() - second.longitude()) < 1.0E-7;
   }

   private static List<GeoPoint> parsePoints(JsonArray geometry) {
      List<GeoPoint> points = new ArrayList<>(geometry.size());
      GeoPoint previous = null;
      for (JsonElement pointElement : geometry) {
         if (!pointElement.isJsonObject()) {
            continue;
         }
         JsonObject pointObject = pointElement.getAsJsonObject();
         double lat = doubleOrDefault(pointObject, Double.NaN, "lat");
         double lon = doubleOrDefault(pointObject, Double.NaN, "lon");
         if (Double.isFinite(lat) && Double.isFinite(lon)) {
            GeoPoint point = new GeoPoint(lat, lon);
            if (!point.equals(previous)) {
               points.add(point);
               previous = point;
            }
         }
      }
      return points;
   }

   private static RoadMode roadMode(Map<String, String> tags) {
      if (truthy(tags.get("tunnel"))) {
         return RoadMode.TUNNEL;
      }
      if (truthy(tags.get("bridge"))) {
         return RoadMode.BRIDGE;
      }
      int layer = intFromTag(tags.get("layer"), 0);
      return layer > 0 ? RoadMode.BRIDGE : layer < 0 ? RoadMode.TUNNEL : RoadMode.NORMAL;
   }

   private static double heightMeters(Map<String, String> tags) {
      Double height = doubleFromTag(first(tags, "height", "building:height", "building_height"));
      if (height != null && height > 0.0) {
         return height;
      }
      Double levels = doubleFromTag(first(tags, "building:levels", "building_levels", "levels", "level"));
      if (levels != null && levels > 0.0) {
         return levels * 3.2;
      }
      return 6.0;
   }

   private static double minHeightMeters(Map<String, String> tags) {
      Double minHeight = doubleFromTag(first(tags, "min_height", "min:height", "building:min_height"));
      if (minHeight != null && minHeight > 0.0) {
         return minHeight;
      }
      Double minLevel = doubleFromTag(first(tags, "building:min_level", "min_level"));
      return minLevel != null && minLevel > 0.0 ? minLevel * 3.2 : 0.0;
   }

   private static int floorCount(Map<String, String> tags, double heightMeters) {
      Double levels = doubleFromTag(first(tags, "building:levels", "building_levels", "levels", "level"));
      return levels != null && levels > 0.0 ? Math.max(1, (int)Math.round(levels)) : Math.max(1, (int)Math.round(heightMeters / 3.2));
   }

   private static Map<String, String> parseTags(JsonObject object) {
      JsonObject tagsObject = object.getAsJsonObject("tags");
      if (tagsObject == null) {
         return Map.of();
      }
      Map<String, String> tags = new LinkedHashMap<>();
      for (Map.Entry<String, JsonElement> entry : tagsObject.entrySet()) {
         JsonElement value = entry.getValue();
         if (value != null && !value.isJsonNull()) {
            tags.put(entry.getKey(), value.isJsonPrimitive() ? value.getAsString() : value.toString());
         }
      }
      return tags;
   }

   private String readCompressed(Path path) throws IOException {
      try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
         return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
   }

   private void cacheTile(Path path, String response, boolean cityDetailsLoaded) {
      try {
         Files.createDirectories(path.getParent());
         Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
         try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(tempPath))) {
            output.write(response.getBytes(StandardCharsets.UTF_8));
         }
         Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
         if (cityDetailsLoaded) {
            Files.writeString(cacheProfilePath(path), CITY_CACHE_PROFILE + "\n", StandardCharsets.UTF_8);
         } else {
            Files.deleteIfExists(cacheProfilePath(path));
         }
         TellusDiagnostics.traffic(
            "Overpass cache write path=%s cityDetails=%s bytes=%d",
            path,
            cityDetailsLoaded,
            Files.size(path)
         );
      } catch (IOException error) {
         LOGGER.debug("Failed to cache Arnis Overpass tile {}", path, error);
         TellusDiagnostics.traffic("Overpass cache write failed path=%s error=%s", path, shortError(error));
      }
   }

   private static boolean cacheHasCityProfile(Path path) {
      Path profilePath = cacheProfilePath(path);
      if (!Files.isRegularFile(profilePath)) {
         return false;
      }
      try {
         return Files.readString(profilePath, StandardCharsets.UTF_8).trim().equals(CITY_CACHE_PROFILE);
      } catch (IOException error) {
         LOGGER.debug("Failed to read Arnis Overpass cache profile {}", profilePath, error);
         return false;
      }
   }

   private static Path cacheProfilePath(Path path) {
      return path.resolveSibling(path.getFileName() + ".profile");
   }

   private Path cachePathFor(TileKey key) {
      return cachePathFor(this.cacheRoot, key);
   }

   private static Path defaultCacheRoot() {
      return FabricLoader.getInstance().getGameDir().resolve("tellus/cache/map/arnis-overpass");
   }

   private static Path cachePathFor(Path cacheRoot, TileKey key) {
      return cacheRoot.resolve(Integer.toString(key.zoom())).resolve(Integer.toString(key.x())).resolve(key.y() + ".json.gz");
   }

   private static CacheEstimate estimateCache(GeoBounds bounds, Path cacheRoot, boolean networkEnabled) {
      List<TileKey> keys = tileKeysForBounds(bounds);
      int cached = 0;
      int cityCached = 0;
      long cachedBytes = 0L;
      boolean cityDetailsEnabled = cityDetailsEnabled();
      for (TileKey key : keys) {
         Path path = cachePathFor(cacheRoot, key);
         if (Files.exists(path)) {
            cached++;
            try {
               cachedBytes += Files.size(path);
            } catch (IOException error) {
               LOGGER.debug("Failed to stat Arnis Overpass cache tile {}", path, error);
            }
            if (cityDetailsEnabled && cacheHasCityProfile(path)) {
               cityCached++;
            }
         }
      }
      int missing = cityDetailsEnabled ? keys.size() - cityCached : keys.size() - cached;
      return new CacheEstimate(
         true,
         networkEnabled,
         keys.size(),
         cached,
         missing,
         cachedBytes,
         MAX_NETWORK_TILES_PER_SESSION,
         cityDetailsEnabled,
         cityCached,
         cityDetailsEnabled ? keys.size() - cityCached : 0
      );
   }

   private static void sortByDistanceToBoundsCenter(List<TileKey> keys, GeoBounds bounds) {
      double centerLat = (bounds.south() + bounds.north()) * 0.5;
      double centerLon = (bounds.west() + bounds.east()) * 0.5;
      keys.sort((left, right) -> Double.compare(tileDistanceSq(left, centerLat, centerLon), tileDistanceSq(right, centerLat, centerLon)));
   }

   private static double tileDistanceSq(TileKey key, double centerLat, double centerLon) {
      GeoBounds tile = key.bounds();
      double tileLat = (tile.south() + tile.north()) * 0.5;
      double tileLon = (tile.west() + tile.east()) * 0.5;
      double dLat = tileLat - centerLat;
      double dLon = tileLon - centerLon;
      return dLat * dLat + dLon * dLon;
   }

   private static List<TileKey> tileKeysForBounds(GeoBounds bounds) {
      int minX = lonToTileX(bounds.west(), QUERY_ZOOM);
      int maxX = lonToTileX(bounds.east(), QUERY_ZOOM);
      int minY = latToTileY(bounds.north(), QUERY_ZOOM);
      int maxY = latToTileY(bounds.south(), QUERY_ZOOM);
      List<TileKey> keys = new ArrayList<>();
      for (int y = minY; y <= maxY; y++) {
         for (int x = minX; x <= maxX; x++) {
            keys.add(new TileKey(QUERY_ZOOM, x, y));
         }
      }
      return keys;
   }

   private static int lonToTileX(double lon, int zoom) {
      int tiles = 1 << zoom;
      int x = (int)Math.floor((lon + 180.0) / 360.0 * tiles);
      return Math.max(0, Math.min(tiles - 1, x));
   }

   private static int latToTileY(double lat, int zoom) {
      double clamped = Math.max(MIN_LAT, Math.min(MAX_LAT, lat));
      int tiles = 1 << zoom;
      double latRad = Math.toRadians(clamped);
      int y = (int)Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) * 0.5 * tiles);
      return Math.max(0, Math.min(tiles - 1, y));
   }

   private static GeoBounds tileBounds(int zoom, int x, int y) {
      int tiles = 1 << zoom;
      double west = x / (double)tiles * 360.0 - 180.0;
      double east = (x + 1) / (double)tiles * 360.0 - 180.0;
      double north = tileYToLat(y, zoom);
      double south = tileYToLat(y + 1, zoom);
      return new GeoBounds(south, west, north, east);
   }

   private static double tileYToLat(int y, int zoom) {
      double n = Math.PI - 2.0 * Math.PI * y / (1 << zoom);
      return Math.toDegrees(Math.atan(Math.sinh(n)));
   }

   private static String stringOrDefault(JsonObject object, String defaultValue, String name) {
      JsonElement value = object.get(name);
      return value == null || value.isJsonNull() ? defaultValue : value.getAsString();
   }

   private static long longOrDefault(JsonObject object, long defaultValue, String name) {
      JsonElement value = object.get(name);
      return value == null || value.isJsonNull() ? defaultValue : value.getAsLong();
   }

   private static double doubleOrDefault(JsonObject object, double defaultValue, String name) {
      JsonElement value = object.get(name);
      return value == null || value.isJsonNull() ? defaultValue : value.getAsDouble();
   }

   private static boolean truthy(String value) {
      if (value == null) {
         return false;
      }
      String normalized = value.trim().toLowerCase(Locale.ROOT);
      return normalized.equals("yes") || normalized.equals("true") || normalized.equals("1");
   }

   private static String first(Map<String, String> tags, String... keys) {
      for (String key : keys) {
         String value = tags.get(key);
         if (value != null && !value.isBlank()) {
            return value;
         }
      }
      return null;
   }

   private static int intFromTag(String value, int defaultValue) {
      Double parsed = doubleFromTag(value);
      return parsed == null ? defaultValue : (int)Math.round(parsed);
   }

   private static Double doubleFromTag(String value) {
      if (value == null || value.isBlank()) {
         return null;
      }
      String normalized = value.trim().replace(',', '.');
      StringBuilder number = new StringBuilder();
      boolean seenDigit = false;
      for (int index = 0; index < normalized.length(); index++) {
         char ch = normalized.charAt(index);
         if ((ch >= '0' && ch <= '9') || ch == '.' || (ch == '-' && number.isEmpty())) {
            number.append(ch);
            if (ch >= '0' && ch <= '9') {
               seenDigit = true;
            }
         } else if (seenDigit) {
            break;
         }
      }
      if (!seenDigit) {
         return null;
      }
      try {
         return Double.parseDouble(number.toString());
      } catch (NumberFormatException error) {
         return null;
      }
   }

   private static URI[] parseEndpoints(String config) {
      String[] parts = Objects.requireNonNull(config, "overpassEndpoints").split(",");
      List<URI> parsed = new ArrayList<>(parts.length);
      for (String part : parts) {
         String trimmed = part == null ? "" : part.trim();
         if (!trimmed.isEmpty()) {
            try {
               parsed.add(URI.create(trimmed));
            } catch (IllegalArgumentException error) {
               LOGGER.warn("Ignoring invalid Arnis Overpass endpoint '{}'", trimmed);
            }
         }
      }
      return parsed.isEmpty() ? new URI[]{URI.create("https://overpass-api.de/api/interpreter")} : parsed.toArray(URI[]::new);
   }

   private static String normalizedNetworkMode(String value) {
      String normalized = value == null ? NETWORK_CACHE_FIRST : value.trim().toLowerCase(Locale.ROOT);
      return switch (normalized) {
         case NETWORK_CACHE_FIRST, NETWORK_CACHE_ONLY, NETWORK_OFF -> normalized;
         default -> {
            LOGGER.debug("Invalid Arnis Overpass network mode '{}', using {}", value, NETWORK_CACHE_FIRST);
            yield NETWORK_CACHE_FIRST;
         }
      };
   }

   private static boolean cityDetailsEnabled() {
      return Boolean.parseBoolean(System.getProperty(CITY_DETAILS_PROPERTY, "true"));
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }
      try {
         int parsed = Integer.parseInt(value.trim());
         return Math.max(minInclusive, Math.min(maxInclusive, parsed));
      } catch (NumberFormatException error) {
         LOGGER.debug("Invalid integer system property {}='{}', using {}", key, value, defaultValue);
         return defaultValue;
      }
   }

   private static long longProperty(String key, long defaultValue, long minInclusive, long maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }
      try {
         long parsed = Long.parseLong(value.trim());
         return Math.max(minInclusive, Math.min(maxInclusive, parsed));
      } catch (NumberFormatException error) {
         LOGGER.debug("Invalid long system property {}='{}', using {}", key, value, defaultValue);
         return defaultValue;
      }
   }

   private record TileKey(int zoom, int x, int y) {
      private GeoBounds bounds() {
         return tileBounds(this.zoom, this.x, this.y);
      }
   }

   public record EndpointProbeResult(String endpoint, boolean ok, int httpStatus, long elapsedMs, String message) {
   }

   public record CacheEstimate(
      boolean enabled,
      boolean networkEnabled,
      int totalTiles,
      int cachedTiles,
      int missingTiles,
      long cachedBytes,
      int sessionNetworkTileBudget,
      boolean cityDetailsEnabled,
      int cityDetailCachedTiles,
      int cityDetailMissingTiles
   ) {
      private static CacheEstimate disabled() {
         return new CacheEstimate(false, false, 0, 0, 0, 0L, 0, false, 0, 0);
      }
   }

   public record PrefetchResult(CacheEstimate before, CacheEstimate after, int attemptedTiles, int cachedTiles, int failedTiles) {
      private static PrefetchResult disabled() {
         CacheEstimate disabled = CacheEstimate.disabled();
         return new PrefetchResult(disabled, disabled, 0, 0, 0);
      }
   }

   private record TileFeatures(
      GeoBounds bounds,
      List<ExternalRoadFeature> roads,
      List<ExternalBuildingFeature> buildings,
      List<ExternalAreaFeature> areas,
      List<ExternalLineFeature> lines,
      List<ExternalPointFeature> points,
      boolean cityDetailsLoaded
   ) {
      private TileFeatures {
         roads = roads == null ? List.of() : List.copyOf(roads);
         buildings = buildings == null ? List.of() : List.copyOf(buildings);
         areas = areas == null ? List.of() : List.copyOf(areas);
         lines = lines == null ? List.of() : List.copyOf(lines);
         points = points == null ? List.of() : List.copyOf(points);
      }

      private static TileFeatures empty(GeoBounds bounds) {
         return new TileFeatures(bounds, List.of(), List.of(), List.of(), List.of(), List.of(), false);
      }

      private List<ExternalRoadFeature> roadsForBounds(GeoBounds queryBounds) {
         if (!this.bounds.intersects(queryBounds)) {
            return List.of();
         }
         List<ExternalRoadFeature> matches = new ArrayList<>();
         for (ExternalRoadFeature road : this.roads) {
            if (lineBounds(road.points()).intersects(queryBounds)) {
               matches.add(road);
            }
         }
         return matches;
      }

      private List<ExternalBuildingFeature> buildingsForBounds(GeoBounds queryBounds) {
         if (!this.bounds.intersects(queryBounds)) {
            return List.of();
         }
         List<ExternalBuildingFeature> matches = new ArrayList<>();
         for (ExternalBuildingFeature building : this.buildings) {
            if (ringsBounds(building.rings()).intersects(queryBounds)) {
               matches.add(building);
            }
         }
         return matches;
      }

      private List<ExternalAreaFeature> areasForBounds(GeoBounds queryBounds) {
         if (!this.bounds.intersects(queryBounds)) {
            return List.of();
         }
         List<ExternalAreaFeature> matches = new ArrayList<>();
         for (ExternalAreaFeature area : this.areas) {
            if (ringsBounds(area.rings()).intersects(queryBounds)) {
               matches.add(area);
            }
         }
         return matches;
      }

      private List<ExternalLineFeature> linesForBounds(GeoBounds queryBounds) {
         if (!this.bounds.intersects(queryBounds)) {
            return List.of();
         }
         List<ExternalLineFeature> matches = new ArrayList<>();
         for (ExternalLineFeature line : this.lines) {
            if (lineBounds(line.points()).intersects(queryBounds)) {
               matches.add(line);
            }
         }
         return matches;
      }

      private List<ExternalPointFeature> pointsForBounds(GeoBounds queryBounds) {
         if (!this.bounds.intersects(queryBounds)) {
            return List.of();
         }
         List<ExternalPointFeature> matches = new ArrayList<>();
         for (ExternalPointFeature point : this.points) {
            if (queryBounds.contains(point.point())) {
               matches.add(point);
            }
         }
         return matches;
      }

      private static GeoBounds lineBounds(List<GeoPoint> points) {
         double south = Double.POSITIVE_INFINITY;
         double west = Double.POSITIVE_INFINITY;
         double north = Double.NEGATIVE_INFINITY;
         double east = Double.NEGATIVE_INFINITY;
         for (GeoPoint point : points) {
            south = Math.min(south, point.latitude());
            west = Math.min(west, point.longitude());
            north = Math.max(north, point.latitude());
            east = Math.max(east, point.longitude());
         }
         return new GeoBounds(south, west, north, east);
      }

      private static GeoBounds ringsBounds(List<List<GeoPoint>> rings) {
         double south = Double.POSITIVE_INFINITY;
         double west = Double.POSITIVE_INFINITY;
         double north = Double.NEGATIVE_INFINITY;
         double east = Double.NEGATIVE_INFINITY;
         for (List<GeoPoint> ring : rings) {
            GeoBounds bounds = lineBounds(ring);
            south = Math.min(south, bounds.south());
            west = Math.min(west, bounds.west());
            north = Math.max(north, bounds.north());
            east = Math.max(east, bounds.east());
         }
         return new GeoBounds(south, west, north, east);
      }
   }
}
