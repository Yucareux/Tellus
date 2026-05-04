package com.yucareux.tellus.world.data.integration;

import java.util.Objects;

public record GeoBounds(double south, double west, double north, double east) {
   public GeoBounds {
      validateLatitude(south, "south");
      validateLatitude(north, "north");
      validateLongitude(west, "west");
      validateLongitude(east, "east");
      if (south > north) {
         throw new IllegalArgumentException("south must be <= north");
      }
      if (west > east) {
         throw new IllegalArgumentException("west must be <= east");
      }
   }

   public boolean contains(GeoPoint point) {
      Objects.requireNonNull(point, "point");
      return point.latitude() >= this.south && point.latitude() <= this.north && point.longitude() >= this.west && point.longitude() <= this.east;
   }

   public boolean intersects(GeoBounds other) {
      Objects.requireNonNull(other, "other");
      return this.east >= other.west && this.west <= other.east && this.north >= other.south && this.south <= other.north;
   }

   public GeoBounds expand(double latitudeDegrees, double longitudeDegrees) {
      if (!Double.isFinite(latitudeDegrees) || latitudeDegrees < 0.0) {
         throw new IllegalArgumentException("latitudeDegrees must be finite and >= 0");
      }
      if (!Double.isFinite(longitudeDegrees) || longitudeDegrees < 0.0) {
         throw new IllegalArgumentException("longitudeDegrees must be finite and >= 0");
      }
      return new GeoBounds(
         Math.max(-90.0, this.south - latitudeDegrees),
         Math.max(-180.0, this.west - longitudeDegrees),
         Math.min(90.0, this.north + latitudeDegrees),
         Math.min(180.0, this.east + longitudeDegrees)
      );
   }

   private static void validateLatitude(double value, String name) {
      if (!Double.isFinite(value) || value < -90.0 || value > 90.0) {
         throw new IllegalArgumentException(name + " latitude out of range: " + value);
      }
   }

   private static void validateLongitude(double value, String name) {
      if (!Double.isFinite(value) || value < -180.0 || value > 180.0) {
         throw new IllegalArgumentException(name + " longitude out of range: " + value);
      }
   }
}
