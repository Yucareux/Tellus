package com.yucareux.tellus.world.data.osm;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class RoadFeature {
   private final long wayId;
   private final RoadClass roadClass;
   private final RoadMode mode;
   private final int bridgeLevel;
   private final String highwayTag;
   private final Map<String, String> tags;
   private final double[] longitudes;
   private final double[] latitudes;
   private final double minLon;
   private final double maxLon;
   private final double minLat;
   private final double maxLat;

   public RoadFeature(long wayId,  RoadClass roadClass,  RoadMode mode, int bridgeLevel, String highwayTag, double[] longitudes, double[] latitudes) {
      this(wayId, roadClass, mode, bridgeLevel, highwayTag, longitudes, latitudes, Map.of());
   }

   public RoadFeature(
      long wayId,
      RoadClass roadClass,
      RoadMode mode,
      int bridgeLevel,
      String highwayTag,
      double[] longitudes,
      double[] latitudes,
      Map<String, String> tags
   ) {
      this.wayId = wayId;
      this.roadClass = Objects.requireNonNull(roadClass, "roadClass");
      this.mode = Objects.requireNonNull(mode, "mode");
      this.bridgeLevel = Math.max(0, bridgeLevel);
      this.highwayTag = normalizeHighwayTag(highwayTag);
      this.tags = Collections.unmodifiableMap(normalizeTags(tags));
      this.longitudes = Objects.requireNonNull(longitudes, "longitudes");
      this.latitudes = Objects.requireNonNull(latitudes, "latitudes");
      if (this.longitudes.length == this.latitudes.length && this.longitudes.length >= 2) {
         double lowLon = Double.POSITIVE_INFINITY;
         double highLon = Double.NEGATIVE_INFINITY;
         double lowLat = Double.POSITIVE_INFINITY;
         double highLat = Double.NEGATIVE_INFINITY;

         for (int i = 0; i < this.longitudes.length; i++) {
            double lon = this.longitudes[i];
            double lat = this.latitudes[i];
            lowLon = Math.min(lowLon, lon);
            highLon = Math.max(highLon, lon);
            lowLat = Math.min(lowLat, lat);
            highLat = Math.max(highLat, lat);
         }

         this.minLon = lowLon;
         this.maxLon = highLon;
         this.minLat = lowLat;
         this.maxLat = highLat;
      } else {
         throw new IllegalArgumentException("RoadFeature requires at least two matching lon/lat points");
      }
   }

   public long wayId() {
      return this.wayId;
   }

   
   public RoadClass roadClass() {
      return this.roadClass;
   }

   
   public RoadMode mode() {
      return this.mode;
   }

   public int bridgeLevel() {
      return this.bridgeLevel;
   }

   public String highwayTag() {
      return this.highwayTag;
   }

   public Map<String, String> tags() {
      return this.tags;
   }

   public String tag(String key) {
      return this.tags.get(normalizeTagKey(key));
   }

   public String surfaceTag() {
      String surface = this.tag("surface");
      return surface == null ? "" : surface.trim().toLowerCase(Locale.ROOT);
   }

   public boolean hasSidewalk() {
      return this.hasLeftSidewalk() || this.hasRightSidewalk();
   }

   public boolean hasLeftSidewalk() {
      String sidewalk = this.tag("sidewalk");
      return isSidewalkBoth(sidewalk)
         || isSidewalkSide(sidewalk, "left")
         || isAttachedSidewalk(this.tag("sidewalk:left"))
         || isAttachedSidewalk(this.tag("sidewalk:both"));
   }

   public boolean hasRightSidewalk() {
      String sidewalk = this.tag("sidewalk");
      return isSidewalkBoth(sidewalk)
         || isSidewalkSide(sidewalk, "right")
         || isAttachedSidewalk(this.tag("sidewalk:right"))
         || isAttachedSidewalk(this.tag("sidewalk:both"));
   }

   public int laneCount() {
      int explicit = this.positiveIntTag("lanes");
      if (explicit > 0) {
         return explicit;
      }

      int directional = this.positiveIntTag("lanes:forward") + this.positiveIntTag("lanes:backward");
      return Math.max(0, directional);
   }

   public boolean isUnpavedSurface() {
      String surface = this.surfaceTag();
      if (surface.isEmpty()) {
         return this.tag("tracktype") != null;
      }

      return switch (surface) {
         case "unpaved", "compacted", "fine_gravel", "gravel", "pebblestone", "ground", "earth", "dirt", "grass", "grass_paver", "mud", "sand", "woodchips" -> true;
         default -> false;
      };
   }

   public boolean isPavedSurface() {
      String surface = this.surfaceTag();
      if (surface.isEmpty()) {
         return false;
      }

      return switch (surface) {
         case "paved", "asphalt", "concrete", "concrete:lanes", "concrete:plates", "paving_stones", "sett", "cobblestone", "bricks", "brick", "tiles" -> true;
         default -> false;
      };
   }

   public boolean matchesHighwayTag(String highwayTag) {
      return this.highwayTag.equals(normalizeHighwayTag(highwayTag));
   }

   public boolean isSecondaryRoad() {
      return this.matchesHighwayTag("secondary") || this.matchesHighwayTag("secondary_link");
   }

   public int pointCount() {
      return this.longitudes.length;
   }

   public double lonAt(int index) {
      return this.longitudes[index];
   }

   public double latAt(int index) {
      return this.latitudes[index];
   }

   public double[] longitudes() {
      return Arrays.copyOf(this.longitudes, this.longitudes.length);
   }

   public double[] latitudes() {
      return Arrays.copyOf(this.latitudes, this.latitudes.length);
   }

   public double minLon() {
      return this.minLon;
   }

   public double maxLon() {
      return this.maxLon;
   }

   public double minLat() {
      return this.minLat;
   }

   public double maxLat() {
      return this.maxLat;
   }

   public boolean intersects(double south, double west, double north, double east) {
      return this.maxLon >= west && this.minLon <= east && this.maxLat >= south && this.minLat <= north;
   }

   private static String normalizeHighwayTag(String highwayTag) {
      return highwayTag == null ? "" : highwayTag.trim().toLowerCase(Locale.ROOT);
   }

   private static Map<String, String> normalizeTags(Map<String, String> tags) {
      if (tags == null || tags.isEmpty()) {
         return Map.of();
      }

      Map<String, String> normalized = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : tags.entrySet()) {
         String key = normalizeTagKey(entry.getKey());
         String value = entry.getValue();
         if (!key.isEmpty() && value != null && !value.isBlank()) {
            normalized.put(key, value.trim());
         }
      }
      return normalized;
   }

   private static String normalizeTagKey(String key) {
      return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
   }

   private int positiveIntTag(String key) {
      String value = this.tag(key);
      if (value == null || value.isBlank()) {
         return 0;
      }

      String trimmed = value.trim();
      int end = 0;
      while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
         end++;
      }
      if (end == 0) {
         return 0;
      }

      try {
         return Math.max(0, Integer.parseInt(trimmed.substring(0, end)));
      } catch (NumberFormatException ignored) {
         return 0;
      }
   }

   private static boolean isAttachedSidewalk(String value) {
      if (value == null || value.isBlank()) {
         return false;
      }

      return switch (value.trim().toLowerCase(Locale.ROOT)) {
         case "no", "none", "separate" -> false;
         default -> true;
      };
   }

   private static boolean isSidewalkBoth(String value) {
      if (value == null || value.isBlank()) {
         return false;
      }

      return switch (value.trim().toLowerCase(Locale.ROOT)) {
         case "yes", "both", "left;right", "right;left" -> true;
         default -> false;
      };
   }

   private static boolean isSidewalkSide(String value, String side) {
      return value != null && value.trim().toLowerCase(Locale.ROOT).equals(side);
   }
}
