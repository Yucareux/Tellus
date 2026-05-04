package com.yucareux.tellus.world.data.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadMode;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class JsonExternalFeatureSource implements ExternalFeatureSource {
   private static final String DEFAULT_SOURCE = "json";
   private final List<ExternalRoadFeature> roads;
   private final List<ExternalBuildingFeature> buildings;
   private final List<ExternalAreaFeature> areas;
   private final List<ExternalLineFeature> lines;
   private final List<ExternalPointFeature> points;

   public JsonExternalFeatureSource(List<ExternalRoadFeature> roads, List<ExternalBuildingFeature> buildings) {
      this(roads, buildings, List.of(), List.of());
   }

   public JsonExternalFeatureSource(
      List<ExternalRoadFeature> roads,
      List<ExternalBuildingFeature> buildings,
      List<ExternalAreaFeature> areas,
      List<ExternalLineFeature> lines
   ) {
      this(roads, buildings, areas, lines, List.of());
   }

   public JsonExternalFeatureSource(
      List<ExternalRoadFeature> roads,
      List<ExternalBuildingFeature> buildings,
      List<ExternalAreaFeature> areas,
      List<ExternalLineFeature> lines,
      List<ExternalPointFeature> points
   ) {
      this.roads = roads == null ? List.of() : List.copyOf(roads);
      this.buildings = buildings == null ? List.of() : List.copyOf(buildings);
      this.areas = areas == null ? List.of() : List.copyOf(areas);
      this.lines = lines == null ? List.of() : List.copyOf(lines);
      this.points = points == null ? List.of() : List.copyOf(points);
   }

   public static JsonExternalFeatureSource fromPath(Path path) throws IOException {
      Objects.requireNonNull(path, "path");
      try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
         return fromReader(reader);
      }
   }

   public static JsonExternalFeatureSource fromReader(Reader reader) throws IOException {
      Objects.requireNonNull(reader, "reader");
      JsonElement parsed = JsonParser.parseReader(reader);
      if (!parsed.isJsonObject()) {
         throw new IOException("external feature JSON root must be an object");
      }
      JsonObject root = parsed.getAsJsonObject();
      return new JsonExternalFeatureSource(parseRoads(root), parseBuildings(root), parseAreas(root), parseLines(root), parsePoints(root));
   }

   @Override
   public List<ExternalRoadFeature> roadsForBounds(GeoBounds bounds) {
      Objects.requireNonNull(bounds, "bounds");
      List<ExternalRoadFeature> matches = new ArrayList<>();
      for (ExternalRoadFeature road : this.roads) {
         if (boundsForLine(road.points()).intersects(bounds)) {
            matches.add(road);
         }
      }
      return List.copyOf(matches);
   }

   @Override
   public List<ExternalBuildingFeature> buildingsForBounds(GeoBounds bounds) {
      Objects.requireNonNull(bounds, "bounds");
      List<ExternalBuildingFeature> matches = new ArrayList<>();
      for (ExternalBuildingFeature building : this.buildings) {
         if (boundsForRings(building.rings()).intersects(bounds)) {
            matches.add(building);
         }
      }
      return List.copyOf(matches);
   }

   @Override
   public List<ExternalAreaFeature> areasForBounds(GeoBounds bounds) {
      Objects.requireNonNull(bounds, "bounds");
      List<ExternalAreaFeature> matches = new ArrayList<>();
      for (ExternalAreaFeature area : this.areas) {
         if (boundsForRings(area.rings()).intersects(bounds)) {
            matches.add(area);
         }
      }
      return List.copyOf(matches);
   }

   @Override
   public List<ExternalLineFeature> linesForBounds(GeoBounds bounds) {
      Objects.requireNonNull(bounds, "bounds");
      List<ExternalLineFeature> matches = new ArrayList<>();
      for (ExternalLineFeature line : this.lines) {
         if (boundsForLine(line.points()).intersects(bounds)) {
            matches.add(line);
         }
      }
      return List.copyOf(matches);
   }

   @Override
   public List<ExternalPointFeature> pointsForBounds(GeoBounds bounds) {
      Objects.requireNonNull(bounds, "bounds");
      List<ExternalPointFeature> matches = new ArrayList<>();
      for (ExternalPointFeature point : this.points) {
         if (bounds.contains(point.point())) {
            matches.add(point);
         }
      }
      return List.copyOf(matches);
   }

   public List<ExternalRoadFeature> roads() {
      return this.roads;
   }

   public List<ExternalBuildingFeature> buildings() {
      return this.buildings;
   }

   public List<ExternalAreaFeature> areas() {
      return this.areas;
   }

   public List<ExternalLineFeature> lines() {
      return this.lines;
   }

   public List<ExternalPointFeature> points() {
      return this.points;
   }

   private static List<ExternalRoadFeature> parseRoads(JsonObject root) throws IOException {
      JsonArray array = arrayOrEmpty(root, "roads");
      List<ExternalRoadFeature> roads = new ArrayList<>(array.size());
      for (JsonElement element : array) {
         JsonObject object = requireObject(element, "road");
         String source = stringOrDefault(object, DEFAULT_SOURCE, "source");
         String sourceId = requiredString(object, "road sourceId", "sourceId", "source_id", "id");
         RoadClass roadClass = enumOrDefault(RoadClass.class, RoadClass.NORMAL, stringOrDefault(object, "NORMAL", "roadClass", "road_class"));
         RoadMode mode = enumOrDefault(RoadMode.class, RoadMode.NORMAL, stringOrDefault(object, "NORMAL", "mode", "road_mode"));
         int bridgeLevel = intOrDefault(object, 0, "bridgeLevel", "bridge_level");
         String highwayTag = stringOrDefault(object, "", "highwayTag", "highway", "highway_tag");
         List<GeoPoint> points = parsePoints(requiredArray(object, "road points", "points"));
         Map<String, String> tags = parseTags(object);
         roads.add(new ExternalRoadFeature(source, sourceId, roadClass, mode, bridgeLevel, highwayTag, points, tags));
      }
      return roads;
   }

   private static List<ExternalBuildingFeature> parseBuildings(JsonObject root) throws IOException {
      JsonArray array = arrayOrEmpty(root, "buildings");
      List<ExternalBuildingFeature> buildings = new ArrayList<>(array.size());
      for (JsonElement element : array) {
         JsonObject object = requireObject(element, "building");
         String source = stringOrDefault(object, DEFAULT_SOURCE, "source");
         String sourceId = requiredString(object, "building sourceId", "sourceId", "source_id", "id");
         ExternalBuildingKind kind = enumOrDefault(
            ExternalBuildingKind.class,
            ExternalBuildingKind.FOOTPRINT,
            stringOrDefault(object, "FOOTPRINT", "kind", "building_kind")
         );
         double heightMeters = doubleOrDefault(object, 6.0, "heightMeters", "height_meters", "height");
         double minHeightMeters = doubleOrDefault(object, 0.0, "minHeightMeters", "min_height_meters", "min_height");
         int floorCount = intOrDefault(object, Math.max(1, (int)Math.round(heightMeters / 3.2)), "floorCount", "floor_count", "building:levels");
         List<List<GeoPoint>> rings = parseRings(requiredArray(object, "building rings", "rings"));
         Map<String, String> tags = parseTags(object);
         buildings.add(new ExternalBuildingFeature(source, sourceId, kind, heightMeters, minHeightMeters, floorCount, rings, tags));
      }
      return buildings;
   }

   private static List<ExternalAreaFeature> parseAreas(JsonObject root) throws IOException {
      JsonArray array = arrayOrEmpty(root, "areas");
      List<ExternalAreaFeature> areas = new ArrayList<>(array.size());
      for (JsonElement element : array) {
         JsonObject object = requireObject(element, "area");
         String source = stringOrDefault(object, DEFAULT_SOURCE, "source");
         String sourceId = requiredString(object, "area sourceId", "sourceId", "source_id", "id");
         ExternalAreaKind kind = enumOrDefault(
            ExternalAreaKind.class,
            ExternalAreaKind.LANDUSE,
            stringOrDefault(object, "LANDUSE", "kind", "area_kind")
         );
         String typeTag = stringOrDefault(object, "", "typeTag", "type_tag", "landuse", "leisure", "natural", "amenity");
         List<List<GeoPoint>> rings = parseRings(requiredArray(object, "area rings", "rings"));
         Map<String, String> tags = parseTags(object);
         areas.add(new ExternalAreaFeature(source, sourceId, kind, typeTag, rings, tags));
      }
      return areas;
   }

   private static List<ExternalLineFeature> parseLines(JsonObject root) throws IOException {
      JsonArray array = arrayOrEmpty(root, "lines");
      List<ExternalLineFeature> lines = new ArrayList<>(array.size());
      for (JsonElement element : array) {
         JsonObject object = requireObject(element, "line");
         String source = stringOrDefault(object, DEFAULT_SOURCE, "source");
         String sourceId = requiredString(object, "line sourceId", "sourceId", "source_id", "id");
         ExternalLineKind kind = enumOrDefault(
            ExternalLineKind.class,
            ExternalLineKind.BARRIER,
            stringOrDefault(object, "BARRIER", "kind", "line_kind")
         );
         String typeTag = stringOrDefault(object, "", "typeTag", "type_tag", "barrier", "railway", "waterway");
         List<GeoPoint> points = parsePoints(requiredArray(object, "line points", "points"));
         Map<String, String> tags = parseTags(object);
         lines.add(new ExternalLineFeature(source, sourceId, kind, typeTag, points, tags));
      }
      return lines;
   }

   private static List<ExternalPointFeature> parsePoints(JsonObject root) throws IOException {
      JsonArray array = arrayOrEmpty(root, "points");
      List<ExternalPointFeature> points = new ArrayList<>(array.size());
      for (JsonElement element : array) {
         JsonObject object = requireObject(element, "point feature");
         String source = stringOrDefault(object, DEFAULT_SOURCE, "source");
         String sourceId = requiredString(object, "point sourceId", "sourceId", "source_id", "id");
         ExternalPointKind kind = enumOrDefault(
            ExternalPointKind.class,
            ExternalPointKind.AMENITY,
            stringOrDefault(object, "AMENITY", "kind", "point_kind")
         );
         String typeTag = stringOrDefault(object, "", "typeTag", "type_tag", "amenity", "highway", "natural", "entrance", "door");
         GeoPoint point = object.has("point")
            ? parsePoint(requireObject(object.get("point"), "point"))
            : new GeoPoint(requiredDouble(object, "point latitude", "lat", "latitude"), requiredDouble(object, "point longitude", "lon", "lng", "longitude"));
         Map<String, String> tags = parseTags(object);
         points.add(new ExternalPointFeature(source, sourceId, kind, typeTag, point, tags));
      }
      return points;
   }

   private static List<GeoPoint> parsePoints(JsonArray array) throws IOException {
      List<GeoPoint> points = new ArrayList<>(array.size());
      for (JsonElement element : array) {
         points.add(parsePoint(requireObject(element, "point")));
      }
      return points;
   }

   private static List<List<GeoPoint>> parseRings(JsonArray array) throws IOException {
      List<List<GeoPoint>> rings = new ArrayList<>(array.size());
      for (JsonElement element : array) {
         if (!element.isJsonArray()) {
            throw new IOException("building ring must be an array");
         }
         rings.add(parsePoints(element.getAsJsonArray()));
      }
      return rings;
   }

   private static GeoPoint parsePoint(JsonObject object) throws IOException {
      double lat = requiredDouble(object, "point latitude", "lat", "latitude");
      double lon = requiredDouble(object, "point longitude", "lon", "lng", "longitude");
      return new GeoPoint(lat, lon);
   }

   private static Map<String, String> parseTags(JsonObject object) throws IOException {
      JsonElement tagsElement = object.get("tags");
      if (tagsElement == null || tagsElement.isJsonNull()) {
         return Map.of();
      }
      JsonObject tagsObject = requireObject(tagsElement, "tags");
      Map<String, String> tags = new LinkedHashMap<>();
      for (Map.Entry<String, JsonElement> entry : tagsObject.entrySet()) {
         JsonElement value = entry.getValue();
         if (value != null && !value.isJsonNull()) {
            tags.put(entry.getKey(), value.isJsonPrimitive() ? value.getAsString() : value.toString());
         }
      }
      return tags;
   }

   private static GeoBounds boundsForLine(List<GeoPoint> points) {
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

   private static GeoBounds boundsForRings(List<List<GeoPoint>> rings) {
      double south = Double.POSITIVE_INFINITY;
      double west = Double.POSITIVE_INFINITY;
      double north = Double.NEGATIVE_INFINITY;
      double east = Double.NEGATIVE_INFINITY;
      for (List<GeoPoint> ring : rings) {
         GeoBounds bounds = boundsForLine(ring);
         south = Math.min(south, bounds.south());
         west = Math.min(west, bounds.west());
         north = Math.max(north, bounds.north());
         east = Math.max(east, bounds.east());
      }
      return new GeoBounds(south, west, north, east);
   }

   private static JsonArray arrayOrEmpty(JsonObject object, String name) throws IOException {
      JsonElement element = object.get(name);
      if (element == null || element.isJsonNull()) {
         return new JsonArray();
      }
      if (!element.isJsonArray()) {
         throw new IOException(name + " must be an array");
      }
      return element.getAsJsonArray();
   }

   private static JsonArray requiredArray(JsonObject object, String label, String... names) throws IOException {
      for (String name : names) {
         JsonElement element = object.get(name);
         if (element != null && !element.isJsonNull()) {
            if (!element.isJsonArray()) {
               throw new IOException(label + " must be an array");
            }
            return element.getAsJsonArray();
         }
      }
      throw new IOException("missing " + label);
   }

   private static JsonObject requireObject(JsonElement element, String label) throws IOException {
      if (element == null || !element.isJsonObject()) {
         throw new IOException(label + " must be an object");
      }
      return element.getAsJsonObject();
   }

   private static String requiredString(JsonObject object, String label, String... names) throws IOException {
      String value = stringOrDefault(object, null, names);
      if (value == null || value.isBlank()) {
         throw new IOException("missing " + label);
      }
      return value;
   }

   private static String stringOrDefault(JsonObject object, String defaultValue, String... names) {
      for (String name : names) {
         JsonElement element = object.get(name);
         if (element != null && !element.isJsonNull()) {
            return element.getAsString();
         }
      }
      return defaultValue;
   }

   private static double requiredDouble(JsonObject object, String label, String... names) throws IOException {
      for (String name : names) {
         JsonElement element = object.get(name);
         if (element != null && !element.isJsonNull()) {
            return element.getAsDouble();
         }
      }
      throw new IOException("missing " + label);
   }

   private static double doubleOrDefault(JsonObject object, double defaultValue, String... names) {
      for (String name : names) {
         JsonElement element = object.get(name);
         if (element != null && !element.isJsonNull()) {
            return element.getAsDouble();
         }
      }
      return defaultValue;
   }

   private static int intOrDefault(JsonObject object, int defaultValue, String... names) {
      for (String name : names) {
         JsonElement element = object.get(name);
         if (element != null && !element.isJsonNull()) {
            return element.getAsInt();
         }
      }
      return defaultValue;
   }

   private static <T extends Enum<T>> T enumOrDefault(Class<T> type, T defaultValue, String value) {
      if (value == null || value.isBlank()) {
         return defaultValue;
      }
      try {
         return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
         return defaultValue;
      }
   }
}
