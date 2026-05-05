package com.yucareux.tellus.client.teleport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.tellus.Tellus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

@Environment(EnvType.CLIENT)
public final class TeleportWaypointStore {
   public static final String INITIAL_SPAWN_ID = "initial_spawn";
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final int FORMAT_VERSION = 1;
   private final Path path;

   private TeleportWaypointStore(Path path) {
      this.path = path;
   }

   public static TeleportWaypointStore create(Minecraft minecraft) {
      Path directory = FabricLoader.getInstance().getGameDir().resolve("tellus").resolve("teleport-points");
      return new TeleportWaypointStore(directory.resolve(safeFileName(resolveWorldKey(minecraft)) + ".json"));
   }

   public List<TeleportWaypoint> load(double spawnLatitude, double spawnLongitude) {
      List<TeleportWaypoint> waypoints = new ArrayList<>();
      if (Files.isRegularFile(this.path)) {
         try {
            JsonElement parsed = JsonParser.parseString(Files.readString(this.path, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
               JsonArray array = parsed.getAsJsonObject().getAsJsonArray("waypoints");
               if (array != null) {
                  for (JsonElement element : array) {
                     if (element.isJsonObject()) {
                        readWaypoint(element.getAsJsonObject(), waypoints);
                     }
                  }
               }
            }
         } catch (IOException | RuntimeException exception) {
            Tellus.LOGGER.warn("Failed to read Tellus teleport waypoints from {}", this.path, exception);
         }
      }

      if (waypoints.stream().noneMatch(waypoint -> INITIAL_SPAWN_ID.equals(waypoint.id()))) {
         waypoints.add(0, initialSpawn(spawnLatitude, spawnLongitude));
         this.save(waypoints);
      }

      return waypoints;
   }

   public void save(List<TeleportWaypoint> waypoints) {
      JsonObject root = new JsonObject();
      root.addProperty("version", FORMAT_VERSION);
      JsonArray array = new JsonArray();
      for (TeleportWaypoint waypoint : waypoints) {
         JsonObject entry = new JsonObject();
         entry.addProperty("id", waypoint.id());
         entry.addProperty("label", waypoint.label());
         entry.addProperty("latitude", waypoint.latitude());
         entry.addProperty("longitude", waypoint.longitude());
         entry.addProperty("initial_spawn", waypoint.initialSpawn());
         array.add(entry);
      }

      root.add("waypoints", array);
      try {
         Files.createDirectories(this.path.getParent());
         Files.writeString(this.path, GSON.toJson(root), StandardCharsets.UTF_8);
      } catch (IOException exception) {
         Tellus.LOGGER.warn("Failed to save Tellus teleport waypoints to {}", this.path, exception);
      }
   }

   private static void readWaypoint(JsonObject object, List<TeleportWaypoint> waypoints) {
      String id = stringValue(object, "id", "");
      if (id.isBlank()) {
         return;
      }

      double latitude = doubleValue(object, "latitude", Double.NaN);
      double longitude = doubleValue(object, "longitude", Double.NaN);
      if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
         return;
      }

      String label = stringValue(object, "label", id);
      boolean initialSpawn = booleanValue(object, "initial_spawn", INITIAL_SPAWN_ID.equals(id));
      waypoints.add(new TeleportWaypoint(id, label, latitude, longitude, initialSpawn));
   }

   private static TeleportWaypoint initialSpawn(double latitude, double longitude) {
      return new TeleportWaypoint(INITIAL_SPAWN_ID, "Initial Spawn", latitude, longitude, true);
   }

   private static String resolveWorldKey(Minecraft minecraft) {
      String dimension = minecraft.level == null ? "unknown" : minecraft.level.dimension().location().toString();
      if (minecraft.getSingleplayerServer() != null) {
         return "singleplayer-" + minecraft.getSingleplayerServer().getWorldData().getLevelName() + "-" + dimension;
      }

      ServerData server = minecraft.getCurrentServer();
      if (server != null) {
         return "server-" + server.ip + "-" + dimension;
      }

      return "level-" + dimension;
   }

   private static String safeFileName(String raw) {
      String normalized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
      String clipped = normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
      return clipped + "-" + Integer.toHexString(raw.hashCode());
   }

   private static String stringValue(JsonObject object, String key, String fallback) {
      JsonElement element = object.get(key);
      return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
   }

   private static double doubleValue(JsonObject object, String key, double fallback) {
      JsonElement element = object.get(key);
      return element != null && element.isJsonPrimitive() ? element.getAsDouble() : fallback;
   }

   private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
      JsonElement element = object.get(key);
      return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
   }
}
