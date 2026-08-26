package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import java.util.List;
import net.minecraft.util.Mth;

/**
 * Builds circular, chunk-aligned terrain-carving exclusion zones around
 * Overture waterfall point features. Water inside the zone follows the raw
 * DEM while waterfall polygon geometry is deliberately ignored by the
 * Overture parser.
 */
public final class WaterfallNoCarveZone {
   private static final int CHUNK_SHIFT = 4;
   private static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;
   private static final int BASE_RADIUS_CHUNKS = intProperty(
      "tellus.water.waterfallNoCarveRadiusChunks", 32, 0, 64
   );

   private WaterfallNoCarveZone() {
   }

   public static int radiusChunks() {
      return BASE_RADIUS_CHUNKS;
   }

   /**
    * Keeps the protected area approximately constant in real-world distance.
    * Once one chunk is wider than the configured 1:1 radius, protection is no
    * longer applied at that world scale.
    */
   public static int radiusChunks(double worldScale) {
      if (!(worldScale > 0.0) || BASE_RADIUS_CHUNKS <= 0) {
         return 0;
      }
      return (int)Math.floor(BASE_RADIUS_CHUNKS / Math.max(1.0, worldScale));
   }

   public static int radiusBlocks(double worldScale) {
      return radiusChunks(worldScale) * CHUNK_SIZE;
   }

   /**
    * Includes the remainder of the marker's own chunk so a marker just beyond
    * a query edge is still returned when its protected chunks overlap the area.
    */
   public static int queryMarginBlocks(double worldScale) {
      int radiusChunks = radiusChunks(worldScale);
      return radiusChunks <= 0 ? 0 : radiusChunks * CHUNK_SIZE + CHUNK_SIZE - 1;
   }

   public static boolean isMarker(OsmWaterFeature feature) {
      return feature != null && feature.waterfallMarker();
   }

   public static boolean containsBlock(OsmWaterFeature marker, int blockX, int blockZ, WorldProjection projection) {
      if (!isMarker(marker) || !(projection.worldScale() > 0.0)) {
         return false;
      }
      int radiusChunks = radiusChunks(projection.worldScale());
      if (radiusChunks <= 0) {
         return false;
      }
      int markerBlockX = markerBlockX(marker, projection);
      int markerBlockZ = markerBlockZ(marker, projection);
      return withinChunkRadius(blockX, blockZ, markerBlockX, markerBlockZ, radiusChunks);
   }

   public static void markBlockGrid(
      boolean[] protectedMask,
      int gridMinX,
      int gridMinZ,
      int gridWidth,
      int gridHeight,
      List<OsmWaterFeature> features,
      WorldProjection projection
   ) {
      requireGrid(protectedMask, gridWidth, gridHeight);
      int radiusChunks = radiusChunks(projection.worldScale());
      if (!(projection.worldScale() > 0.0) || radiusChunks <= 0 || features == null || features.isEmpty()) {
         return;
      }

      long gridMaxX = (long)gridMinX + gridWidth - 1L;
      long gridMaxZ = (long)gridMinZ + gridHeight - 1L;
      for (OsmWaterFeature feature : features) {
         if (!isMarker(feature)) {
            continue;
         }
         long markerChunkX = Math.floorDiv(markerBlockX(feature, projection), CHUNK_SIZE);
         long markerChunkZ = Math.floorDiv(markerBlockZ(feature, projection), CHUNK_SIZE);
         for (int chunkOffsetZ = -radiusChunks; chunkOffsetZ <= radiusChunks; chunkOffsetZ++) {
            int chunkExtentX = maxChunkOffsetAt(chunkOffsetZ, radiusChunks);
            long zoneMinX = (markerChunkX - chunkExtentX) * CHUNK_SIZE;
            long zoneMaxX = (markerChunkX + chunkExtentX + 1L) * CHUNK_SIZE - 1L;
            long zoneMinZ = (markerChunkZ + chunkOffsetZ) * CHUNK_SIZE;
            long zoneMaxZ = zoneMinZ + CHUNK_SIZE - 1L;
            int minX = (int)Math.max(gridMinX, zoneMinX);
            int maxX = (int)Math.min(gridMaxX, zoneMaxX);
            int minZ = (int)Math.max(gridMinZ, zoneMinZ);
            int maxZ = (int)Math.min(gridMaxZ, zoneMaxZ);
            if (maxX < minX || maxZ < minZ) {
               continue;
            }

            int localMinX = minX - gridMinX;
            int localMaxX = maxX - gridMinX;
            for (int worldZ = minZ; worldZ <= maxZ; worldZ++) {
               int row = (worldZ - gridMinZ) * gridWidth;
               java.util.Arrays.fill(protectedMask, row + localMinX, row + localMaxX + 1, true);
            }
         }
      }
   }

   public static void markSampleGrid(
      boolean[] protectedMask,
      double[] sampleWorldX,
      double[] sampleWorldZ,
      List<OsmWaterFeature> features,
      WorldProjection projection
   ) {
      int gridWidth = sampleWorldX == null ? 0 : sampleWorldX.length;
      int gridHeight = sampleWorldZ == null ? 0 : sampleWorldZ.length;
      requireGrid(protectedMask, gridWidth, gridHeight);
      int radiusChunks = radiusChunks(projection.worldScale());
      if (!(projection.worldScale() > 0.0) || radiusChunks <= 0 || features == null || features.isEmpty()) {
         return;
      }
      long radiusSquared = radiusSquared(radiusChunks);

      for (OsmWaterFeature feature : features) {
         if (!isMarker(feature)) {
            continue;
         }
         int markerBlockX = markerBlockX(feature, projection);
         int markerBlockZ = markerBlockZ(feature, projection);
         long markerChunkX = Math.floorDiv(markerBlockX, CHUNK_SIZE);
         long markerChunkZ = Math.floorDiv(markerBlockZ, CHUNK_SIZE);
         for (int z = 0; z < gridHeight; z++) {
            int blockZ = Mth.floor(sampleWorldZ[z]);
            long chunkOffsetZ = Math.floorDiv(blockZ, CHUNK_SIZE) - markerChunkZ;
            long remainingRadiusSquared = radiusSquared - chunkOffsetZ * chunkOffsetZ;
            if (remainingRadiusSquared < 0L) {
               continue;
            }
            int row = z * gridWidth;
            for (int x = 0; x < gridWidth; x++) {
               long chunkOffsetX = Math.floorDiv(Mth.floor(sampleWorldX[x]), CHUNK_SIZE) - markerChunkX;
               if (chunkOffsetX * chunkOffsetX <= remainingRadiusSquared) {
                  protectedMask[row + x] = true;
               }
            }
         }
      }
   }

   public static void markRegularCellGrid(
      boolean[] protectedMask,
      int baseX,
      int baseZ,
      int gridSize,
      int cellSize,
      List<OsmWaterFeature> features,
      WorldProjection projection
   ) {
      requireGrid(protectedMask, gridSize, gridSize);
      int radiusChunks = radiusChunks(projection.worldScale());
      if (!(projection.worldScale() > 0.0) || radiusChunks <= 0 || cellSize <= 0 || features == null || features.isEmpty()) {
         return;
      }

      for (OsmWaterFeature feature : features) {
         if (!isMarker(feature)) {
            continue;
         }
         long markerChunkX = Math.floorDiv(markerBlockX(feature, projection), CHUNK_SIZE);
         long markerChunkZ = Math.floorDiv(markerBlockZ(feature, projection), CHUNK_SIZE);
         for (int z = 0; z < gridSize; z++) {
            long cellMinZ = (long)baseZ + (long)z * cellSize;
            long cellMaxZ = cellMinZ + cellSize - 1L;
            long cellMinChunkZ = Math.floorDiv(cellMinZ, CHUNK_SIZE);
            long cellMaxChunkZ = Math.floorDiv(cellMaxZ, CHUNK_SIZE);
            int row = z * gridSize;
            for (int x = 0; x < gridSize; x++) {
               long cellMinX = (long)baseX + (long)x * cellSize;
               long cellMaxX = cellMinX + cellSize - 1L;
               long cellMinChunkX = Math.floorDiv(cellMinX, CHUNK_SIZE);
               long cellMaxChunkX = Math.floorDiv(cellMaxX, CHUNK_SIZE);
               if (chunkRectangleIntersectsRadius(
                  cellMinChunkX,
                  cellMaxChunkX,
                  cellMinChunkZ,
                  cellMaxChunkZ,
                  markerChunkX,
                  markerChunkZ,
                  radiusChunks
               )) {
                  protectedMask[row + x] = true;
               }
            }
         }
      }
   }

   private static int markerBlockX(OsmWaterFeature marker, WorldProjection projection) {
      return Mth.floor(projection.lonToBlockX(marker.lonAt(0, 0)));
   }

   private static int markerBlockZ(OsmWaterFeature marker, WorldProjection projection) {
      return Mth.floor(projection.latToBlockZ(marker.latAt(0, 0)));
   }

   private static boolean withinChunkRadius(
      int blockX,
      int blockZ,
      int markerBlockX,
      int markerBlockZ,
      int radiusChunks
   ) {
      long chunkOffsetX = (long)Math.floorDiv(blockX, CHUNK_SIZE)
         - Math.floorDiv(markerBlockX, CHUNK_SIZE);
      long chunkOffsetZ = (long)Math.floorDiv(blockZ, CHUNK_SIZE)
         - Math.floorDiv(markerBlockZ, CHUNK_SIZE);
      return chunkOffsetX * chunkOffsetX + chunkOffsetZ * chunkOffsetZ <= radiusSquared(radiusChunks);
   }

   private static int maxChunkOffsetAt(int chunkOffsetZ, int radiusChunks) {
      long remainingRadiusSquared = radiusSquared(radiusChunks) - (long)chunkOffsetZ * chunkOffsetZ;
      return (int)Math.floor(Math.sqrt(remainingRadiusSquared));
   }

   private static boolean chunkRectangleIntersectsRadius(
      long minChunkX,
      long maxChunkX,
      long minChunkZ,
      long maxChunkZ,
      long markerChunkX,
      long markerChunkZ,
      int radiusChunks
   ) {
      long nearestChunkX = clamp(markerChunkX, minChunkX, maxChunkX);
      long nearestChunkZ = clamp(markerChunkZ, minChunkZ, maxChunkZ);
      long chunkOffsetX = nearestChunkX - markerChunkX;
      long chunkOffsetZ = nearestChunkZ - markerChunkZ;
      return chunkOffsetX * chunkOffsetX + chunkOffsetZ * chunkOffsetZ <= radiusSquared(radiusChunks);
   }

   private static long clamp(long value, long min, long max) {
      return Math.max(min, Math.min(max, value));
   }

   private static long radiusSquared(int radiusChunks) {
      return (long)radiusChunks * radiusChunks;
   }

   private static void requireGrid(boolean[] mask, int width, int height) {
      if (width < 0 || height < 0 || mask == null || (long)width * height > mask.length) {
         throw new IllegalArgumentException("Invalid waterfall no-carve grid");
      }
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return defaultValue;
      }
      try {
         return Mth.clamp(Integer.parseInt(value), minInclusive, maxInclusive);
      } catch (NumberFormatException ignored) {
         return defaultValue;
      }
   }
}
