package com.yucareux.tellus.world.data.integration;

public record GeoPoint(double latitude, double longitude) {
   public GeoPoint {
      if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
         throw new IllegalArgumentException("latitude out of range: " + latitude);
      }
      if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
         throw new IllegalArgumentException("longitude out of range: " + longitude);
      }
   }
}
