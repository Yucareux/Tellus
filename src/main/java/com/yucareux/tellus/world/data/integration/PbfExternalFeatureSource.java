package com.yucareux.tellus.world.data.integration;

import com.yucareux.tellus.util.TellusDiagnostics;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import org.openstreetmap.osmosis.pbf2.v0_6.PbfReader;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.container.v0_6.NodeContainer;
import org.openstreetmap.osmosis.core.container.v0_6.RelationContainer;
import org.openstreetmap.osmosis.core.container.v0_6.WayContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Entity;
import org.openstreetmap.osmosis.core.domain.v0_6.EntityType;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Relation;
import org.openstreetmap.osmosis.core.domain.v0_6.RelationMember;
import org.openstreetmap.osmosis.core.domain.v0_6.Tag;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PbfExternalFeatureSource implements ExternalFeatureSource {
   public static final String ENABLED_PROPERTY = "tellus.arnis.pbf.enabled";
   public static final String PATHS_PROPERTY = "tellus.arnis.pbf.paths";
   public static final String DIRECTORY_PROPERTY = "tellus.arnis.pbf.directory";
   public static final String DEFAULT_RELATIVE_DIRECTORY = "tellus/cache/osm-pbf";
   private static final Logger LOGGER = LoggerFactory.getLogger("tellus");
   private static final String SOURCE = "arnis-pbf";

   private final boolean enabled;
   private final List<Path> paths;
   private final JsonExternalFeatureSource delegate;

   private PbfExternalFeatureSource(boolean enabled, List<Path> paths, JsonExternalFeatureSource delegate) {
      this.enabled = enabled;
      this.paths = paths == null ? List.of() : List.copyOf(paths);
      this.delegate = Objects.requireNonNull(delegate, "delegate");
   }

   public static PbfExternalFeatureSource createDefault() {
      if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
         TellusDiagnostics.traffic("PBF source disabled by %s=false", ENABLED_PROPERTY);
         return disabled();
      }

      List<Path> paths = configuredPaths();
      if (paths.isEmpty()) {
         TellusDiagnostics.traffic("PBF source ready but no .osm.pbf files are configured");
         return disabled();
      }

      try {
         ParsedFeatures parsed = ParsedFeatures.empty();
         for (Path path : paths) {
            ParsedFeatures fileFeatures = parseFile(path);
            parsed = parsed.merge(fileFeatures);
         }
         JsonExternalFeatureSource delegate = parsed.toSource();
         LOGGER.info(
            "Loaded Tellus PBF features from {} file(s) (roads={}, buildings={}, areas={}, lines={}, points={})",
            paths.size(),
            delegate.roads().size(),
            delegate.buildings().size(),
            delegate.areas().size(),
            delegate.lines().size(),
            delegate.points().size()
         );
         TellusDiagnostics.traffic(
            "PBF source loaded files=%d roads=%d buildings=%d areas=%d lines=%d points=%d",
            paths.size(),
            delegate.roads().size(),
            delegate.buildings().size(),
            delegate.areas().size(),
            delegate.lines().size(),
            delegate.points().size()
         );
         return new PbfExternalFeatureSource(true, paths, delegate);
      } catch (IOException | RuntimeException error) {
         LOGGER.warn("Failed to load Tellus PBF features from {}", paths, error);
         TellusDiagnostics.traffic("PBF source unavailable paths=%s error=%s", paths, shortError(error));
         return disabled();
      }
   }

   public static PbfExternalFeatureSource disabled() {
      return new PbfExternalFeatureSource(false, List.of(), new JsonExternalFeatureSource(List.of(), List.of()));
   }

   public boolean available() {
      return this.enabled
         && (!this.delegate.roads().isEmpty()
            || !this.delegate.buildings().isEmpty()
            || !this.delegate.areas().isEmpty()
            || !this.delegate.lines().isEmpty()
            || !this.delegate.points().isEmpty());
   }

   public boolean roadsAvailable() {
      return this.enabled && !this.delegate.roads().isEmpty();
   }

   public boolean buildingsAvailable() {
      return this.enabled && !this.delegate.buildings().isEmpty();
   }

   public boolean cityDetailsAvailable() {
      return this.enabled && (!this.delegate.areas().isEmpty() || !this.delegate.lines().isEmpty() || !this.delegate.points().isEmpty());
   }

   public List<Path> paths() {
      return this.paths;
   }

   public static FileSummary summarizeConfiguredFiles() {
      boolean enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
      if (!enabled) {
         return new FileSummary(false, configuredLocationLabel(), 0, 0L, List.of());
      }
      List<Path> paths = configuredPaths();
      long bytes = 0L;
      for (Path path : paths) {
         try {
            bytes += Files.size(path);
         } catch (IOException error) {
            LOGGER.debug("Failed to stat Tellus PBF path {}", path, error);
         }
      }
      return new FileSummary(true, configuredLocationLabel(), paths.size(), bytes, paths);
   }

   public static String configuredLocationLabel() {
      String configuredPaths = System.getProperty(PATHS_PROPERTY);
      if (configuredPaths != null && !configuredPaths.isBlank()) {
         return configuredPaths.trim();
      }
      return configuredDirectoryPath().toString();
   }

   @Override
   public List<ExternalRoadFeature> roadsForBounds(GeoBounds bounds) {
      return this.enabled ? this.delegate.roadsForBounds(bounds) : List.of();
   }

   @Override
   public List<ExternalBuildingFeature> buildingsForBounds(GeoBounds bounds) {
      return this.enabled ? this.delegate.buildingsForBounds(bounds) : List.of();
   }

   @Override
   public List<ExternalAreaFeature> areasForBounds(GeoBounds bounds) {
      return this.enabled ? this.delegate.areasForBounds(bounds) : List.of();
   }

   @Override
   public List<ExternalLineFeature> linesForBounds(GeoBounds bounds) {
      return this.enabled ? this.delegate.linesForBounds(bounds) : List.of();
   }

   @Override
   public List<ExternalPointFeature> pointsForBounds(GeoBounds bounds) {
      return this.enabled ? this.delegate.pointsForBounds(bounds) : List.of();
   }

   private static List<Path> configuredPaths() {
      String configuredPaths = System.getProperty(PATHS_PROPERTY);
      if (configuredPaths != null && !configuredPaths.isBlank()) {
         List<Path> paths = new ArrayList<>();
         for (String part : configuredPaths.split(",")) {
            String trimmed = part == null ? "" : part.trim();
            if (!trimmed.isEmpty()) {
               Path path = Path.of(trimmed).toAbsolutePath().normalize();
               if (Files.isRegularFile(path)) {
                  paths.add(path);
               } else {
                  LOGGER.warn("Ignoring missing Tellus PBF path {}", path);
                  TellusDiagnostics.traffic("PBF configured path missing path=%s", path);
               }
            }
         }
         paths.sort(Comparator.naturalOrder());
         return List.copyOf(paths);
      }

      Path directory = configuredDirectoryPath();
      if (!Files.isDirectory(directory)) {
         return List.of();
      }

      try {
         List<Path> paths = new ArrayList<>();
         try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
               .filter(PbfExternalFeatureSource::isPbfPath)
               .sorted()
               .forEach(paths::add);
         }
         return List.copyOf(paths);
      } catch (IOException error) {
         LOGGER.warn("Failed to list Tellus PBF directory {}", directory, error);
         TellusDiagnostics.traffic("PBF directory list failed path=%s error=%s", directory, shortError(error));
         return List.of();
      }
   }

   private static boolean isPbfPath(Path path) {
      String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
      return name.endsWith(".osm.pbf") || name.endsWith(".pbf");
   }

   private static Path configuredDirectoryPath() {
      String configuredDirectory = System.getProperty(DIRECTORY_PROPERTY);
      Path directory = configuredDirectory == null || configuredDirectory.isBlank()
         ? FabricLoader.getInstance().getGameDir().resolve(DEFAULT_RELATIVE_DIRECTORY)
         : Path.of(configuredDirectory.trim());
      return directory.toAbsolutePath().normalize();
   }

   private static ParsedFeatures parseFile(Path path) throws IOException {
      long startMs = System.currentTimeMillis();
      PbfSink sink = new PbfSink();
      PbfReader reader = new PbfReader(path.toFile(), 1);
      reader.setSink(sink);
      reader.run();
      ParsedFeatures parsed = sink.toParsedFeatures();
      TellusDiagnostics.traffic(
         "PBF file loaded path=%s bytes=%d elapsedMs=%d nodes=%d ways=%d relations=%d roads=%d buildings=%d areas=%d lines=%d points=%d",
         path,
         Files.size(path),
         System.currentTimeMillis() - startMs,
         sink.nodeCount,
         sink.wayCount,
         sink.relationCount,
         parsed.roads().size(),
         parsed.buildings().size(),
         parsed.areas().size(),
         parsed.lines().size(),
         parsed.points().size()
      );
      return parsed;
   }

   private static ExternalRoadFeature parseRoad(String id, Map<String, String> tags, List<GeoPoint> points) {
      String highway = tags.get("highway");
      RoadClass roadClass = RoadClass.fromHighwayTag(highway);
      if (roadClass == null || points.size() < 2) {
         return null;
      }
      RoadMode mode = roadMode(tags);
      int bridgeLevel = mode == RoadMode.BRIDGE ? Math.max(1, intFromTag(tags.get("layer"), 1)) : 0;
      return new ExternalRoadFeature(SOURCE, id, roadClass, mode, bridgeLevel, highway, points, tags);
   }

   private static ExternalBuildingFeature parseBuilding(String id, Map<String, String> tags, List<GeoPoint> points) {
      List<GeoPoint> ring = closedRing(points);
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
      return new ExternalBuildingFeature(SOURCE, id, kind, height, minHeight, floorCount, List.of(ring), tags);
   }

   private static ExternalBuildingFeature parseBuildingRelation(String id, Map<String, String> tags, List<List<GeoPoint>> rings) {
      if (rings.isEmpty()) {
         return null;
      }
      double height = heightMeters(tags);
      double minHeight = minHeightMeters(tags);
      if (!(height > minHeight)) {
         height = minHeight + 3.2;
      }
      int floorCount = floorCount(tags, height);
      ExternalBuildingKind kind = tags.containsKey("building:part") ? ExternalBuildingKind.PART : ExternalBuildingKind.FOOTPRINT;
      return new ExternalBuildingFeature(SOURCE, id, kind, height, minHeight, floorCount, rings, tags);
   }

   private static ExternalAreaFeature parseArea(String id, Map<String, String> tags, List<GeoPoint> points) {
      ExternalAreaKind kind = areaKind(tags);
      if (kind == null || tags.containsKey("building") || tags.containsKey("building:part")) {
         return null;
      }
      List<GeoPoint> ring = closedRing(points);
      if (ring.size() < 4) {
         return null;
      }
      return new ExternalAreaFeature(SOURCE, id, kind, areaTypeTag(kind, tags), List.of(ring), tags);
   }

   private static ExternalAreaFeature parseAreaRelation(String id, Map<String, String> tags, List<List<GeoPoint>> rings) {
      ExternalAreaKind kind = areaKind(tags);
      if (kind == null || tags.containsKey("building") || tags.containsKey("building:part") || rings.isEmpty()) {
         return null;
      }
      return new ExternalAreaFeature(SOURCE, id, kind, areaTypeTag(kind, tags), rings, tags);
   }

   private static ExternalLineFeature parseLine(String id, Map<String, String> tags, List<GeoPoint> points) {
      ExternalLineKind kind = lineKind(tags);
      if (kind == null || points.size() < 2) {
         return null;
      }
      return new ExternalLineFeature(SOURCE, id, kind, lineTypeTag(kind, tags), points, tags);
   }

   private static ExternalPointFeature parsePointFeature(String id, Map<String, String> tags, GeoPoint point) {
      ExternalPointKind kind = pointKind(tags);
      if (kind == null) {
         return null;
      }
      return new ExternalPointFeature(SOURCE, id, kind, pointTypeTag(kind, tags), point, tags);
   }

   private static Map<String, String> tags(Entity entity) {
      Map<String, String> tags = new LinkedHashMap<>();
      for (Tag tag : entity.getTags()) {
         if (tag.getKey() != null && !tag.getKey().isBlank() && tag.getValue() != null) {
            tags.put(tag.getKey(), tag.getValue());
         }
      }
      return tags.isEmpty() ? Map.of() : Map.copyOf(tags);
   }

   private static List<GeoPoint> pointsForWay(Way way, Map<Long, GeoPoint> nodes) {
      List<GeoPoint> points = new ArrayList<>(way.getWayNodes().size());
      GeoPoint previous = null;
      for (WayNode wayNode : way.getWayNodes()) {
         GeoPoint point = nodes.get(wayNode.getNodeId());
         if (point != null && !point.equals(previous)) {
            points.add(point);
            previous = point;
         }
      }
      return List.copyOf(points);
   }

   private static List<List<GeoPoint>> relationRings(Relation relation, Map<Long, List<GeoPoint>> wayPointsById) {
      List<List<GeoPoint>> outerSegments = new ArrayList<>();
      List<List<GeoPoint>> innerSegments = new ArrayList<>();
      for (RelationMember member : relation.getMembers()) {
         if (member.getMemberType() != EntityType.Way) {
            continue;
         }
         List<GeoPoint> segment = wayPointsById.get(member.getMemberId());
         if (segment == null || segment.size() < 2) {
            continue;
         }
         String role = Objects.toString(member.getMemberRole(), "").trim().toLowerCase(Locale.ROOT);
         if ("inner".equals(role)) {
            innerSegments.add(segment);
         } else if (role.isEmpty() || "outer".equals(role) || "outline".equals(role)) {
            outerSegments.add(segment);
         }
      }

      List<List<GeoPoint>> rings = new ArrayList<>();
      rings.addAll(mergeSegmentsToRings(outerSegments));
      if (rings.isEmpty()) {
         return List.of();
      }
      rings.addAll(mergeSegmentsToRings(innerSegments));
      return List.copyOf(rings);
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

   private static List<GeoPoint> closedRing(List<GeoPoint> points) {
      if (points.size() < 3) {
         return List.of();
      }
      List<GeoPoint> ring = new ArrayList<>(points);
      closeRing(ring);
      return ring.size() >= 4 && isClosedRing(ring) ? List.copyOf(ring) : List.of();
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
      return minHeight == null || minHeight < 0.0 ? 0.0 : minHeight;
   }

   private static int floorCount(Map<String, String> tags, double heightMeters) {
      Double levels = doubleFromTag(first(tags, "building:levels", "building_levels", "levels", "level"));
      return levels != null && levels > 0.0 ? Math.max(1, (int)Math.round(levels)) : Math.max(1, (int)Math.round(heightMeters / 3.2));
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

   private static boolean truthy(String value) {
      if (value == null) {
         return false;
      }
      String normalized = value.trim().toLowerCase(Locale.ROOT);
      return normalized.equals("yes") || normalized.equals("true") || normalized.equals("1");
   }

   private static String shortError(Throwable error) {
      String message = error.getMessage();
      return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
   }

   public record FileSummary(boolean enabled, String location, int fileCount, long bytes, List<Path> paths) {
      public FileSummary {
         location = location == null ? "" : location;
         paths = paths == null ? List.of() : List.copyOf(paths);
      }
   }

   private static final class PbfSink implements Sink {
      private final Map<Long, GeoPoint> nodes = new HashMap<>();
      private final Map<Long, List<GeoPoint>> wayPointsById = new HashMap<>();
      private final List<ExternalRoadFeature> roads = new ArrayList<>();
      private final List<ExternalBuildingFeature> buildings = new ArrayList<>();
      private final List<ExternalAreaFeature> areas = new ArrayList<>();
      private final List<ExternalLineFeature> lines = new ArrayList<>();
      private final List<ExternalPointFeature> points = new ArrayList<>();
      private long nodeCount;
      private long wayCount;
      private long relationCount;

      @Override
      public void initialize(Map<String, Object> metaData) {
      }

      @Override
      public void process(EntityContainer entityContainer) {
         if (entityContainer instanceof NodeContainer nodeContainer) {
            this.processNode(nodeContainer.getEntity());
         } else if (entityContainer instanceof WayContainer wayContainer) {
            this.processWay(wayContainer.getEntity());
         } else if (entityContainer instanceof RelationContainer relationContainer) {
            this.processRelation(relationContainer.getEntity());
         }
      }

      @Override
      public void complete() {
      }

      @Override
      public void close() {
      }

      private void processNode(Node node) {
         this.nodeCount++;
         GeoPoint point = new GeoPoint(node.getLatitude(), node.getLongitude());
         this.nodes.put(node.getId(), point);
         Map<String, String> tags = tags(node);
         if (!tags.isEmpty()) {
            ExternalPointFeature pointFeature = parsePointFeature("node/" + node.getId(), tags, point);
            if (pointFeature != null) {
               this.points.add(pointFeature);
            }
         }
      }

      private void processWay(Way way) {
         this.wayCount++;
         List<GeoPoint> points = pointsForWay(way, this.nodes);
         if (points.size() >= 2) {
            this.wayPointsById.put(way.getId(), points);
         }

         Map<String, String> tags = tags(way);
         if (tags.isEmpty()) {
            return;
         }

         ExternalRoadFeature road = parseRoad("way/" + way.getId(), tags, points);
         if (road != null) {
            this.roads.add(road);
         }
         if (tags.containsKey("building") || tags.containsKey("building:part")) {
            ExternalBuildingFeature building = parseBuilding("way/" + way.getId(), tags, points);
            if (building != null) {
               this.buildings.add(building);
            }
         } else {
            ExternalAreaFeature area = parseArea("way/" + way.getId(), tags, points);
            if (area != null) {
               this.areas.add(area);
            }
         }
         ExternalLineFeature line = parseLine("way/" + way.getId(), tags, points);
         if (line != null) {
            this.lines.add(line);
         }
      }

      private void processRelation(Relation relation) {
         this.relationCount++;
         Map<String, String> tags = tags(relation);
         if (tags.isEmpty()) {
            return;
         }
         if (!"multipolygon".equals(tags.get("type")) && !tags.containsKey("building") && !tags.containsKey("building:part") && areaKind(tags) == null) {
            return;
         }

         List<List<GeoPoint>> rings = relationRings(relation, this.wayPointsById);
         if (tags.containsKey("building") || tags.containsKey("building:part")) {
            ExternalBuildingFeature building = parseBuildingRelation("relation/" + relation.getId(), tags, rings);
            if (building != null) {
               this.buildings.add(building);
            }
         } else {
            ExternalAreaFeature area = parseAreaRelation("relation/" + relation.getId(), tags, rings);
            if (area != null) {
               this.areas.add(area);
            }
         }
      }

      private ParsedFeatures toParsedFeatures() {
         return new ParsedFeatures(this.roads, this.buildings, this.areas, this.lines, this.points);
      }
   }

   private record ParsedFeatures(
      List<ExternalRoadFeature> roads,
      List<ExternalBuildingFeature> buildings,
      List<ExternalAreaFeature> areas,
      List<ExternalLineFeature> lines,
      List<ExternalPointFeature> points
   ) {
      private ParsedFeatures {
         roads = roads == null ? List.of() : List.copyOf(roads);
         buildings = buildings == null ? List.of() : List.copyOf(buildings);
         areas = areas == null ? List.of() : List.copyOf(areas);
         lines = lines == null ? List.of() : List.copyOf(lines);
         points = points == null ? List.of() : List.copyOf(points);
      }

      private static ParsedFeatures empty() {
         return new ParsedFeatures(List.of(), List.of(), List.of(), List.of(), List.of());
      }

      private ParsedFeatures merge(ParsedFeatures other) {
         List<ExternalRoadFeature> mergedRoads = new ArrayList<>(this.roads);
         mergedRoads.addAll(other.roads);
         List<ExternalBuildingFeature> mergedBuildings = new ArrayList<>(this.buildings);
         mergedBuildings.addAll(other.buildings);
         List<ExternalAreaFeature> mergedAreas = new ArrayList<>(this.areas);
         mergedAreas.addAll(other.areas);
         List<ExternalLineFeature> mergedLines = new ArrayList<>(this.lines);
         mergedLines.addAll(other.lines);
         List<ExternalPointFeature> mergedPoints = new ArrayList<>(this.points);
         mergedPoints.addAll(other.points);
         return new ParsedFeatures(mergedRoads, mergedBuildings, mergedAreas, mergedLines, mergedPoints);
      }

      private JsonExternalFeatureSource toSource() {
         return new JsonExternalFeatureSource(this.roads, this.buildings, this.areas, this.lines, this.points);
      }
   }
}
