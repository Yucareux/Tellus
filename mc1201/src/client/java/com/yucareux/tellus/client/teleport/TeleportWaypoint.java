package com.yucareux.tellus.client.teleport;

import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class TeleportWaypoint {
   private final String id;
   private String label;
   private double latitude;
   private double longitude;
   private final boolean initialSpawn;

   public TeleportWaypoint(String id, String label, double latitude, double longitude, boolean initialSpawn) {
      this.id = Objects.requireNonNull(id, "id");
      this.label = normalizeLabel(label);
      this.latitude = latitude;
      this.longitude = longitude;
      this.initialSpawn = initialSpawn;
   }

   public String id() {
      return this.id;
   }

   public String label() {
      return this.label;
   }

   public void setLabel(String label) {
      this.label = normalizeLabel(label);
   }

   public double latitude() {
      return this.latitude;
   }

   public void setLatitude(double latitude) {
      this.latitude = latitude;
   }

   public double longitude() {
      return this.longitude;
   }

   public void setLongitude(double longitude) {
      this.longitude = longitude;
   }

   public boolean initialSpawn() {
      return this.initialSpawn;
   }

   private static String normalizeLabel(String label) {
      String safeLabel = label == null ? "" : label.trim();
      return safeLabel.isEmpty() ? "Waypoint" : safeLabel;
   }
}
