package com.yucareux.tellus.worldgen.building;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

public final class TellusBuildingBlueprints {
   private TellusBuildingBlueprints() {
   }

   public static BuildingBlueprint create(
      String groupId,
      OsmBuildingFeature feature,
      BuildingProfile profile,
      long worldSeed,
      int baseY,
      int floorY,
      int roofBaseY,
      int topY,
      List<RoadFeature> nearbyRoads,
      WorldProjection projection
   ) {
      Objects.requireNonNull(groupId, "groupId");
      Objects.requireNonNull(feature, "feature");
      Objects.requireNonNull(profile, "profile");
      int minWorldX = Mth.floor(feature.minBlockX(projection));
      int maxWorldX = Mth.ceil(feature.maxBlockX(projection));
      int minWorldZ = Mth.floor(feature.minBlockZ(projection));
      int maxWorldZ = Mth.ceil(feature.maxBlockZ(projection));
      long seed = mixSeed(worldSeed, feature.featureId(), groupId.hashCode());
      BuildingStyle style = TellusBuildingStyles.resolveBuildingStyle(
         profile, feature.metadata(), feature.areaSquareMeters(), maxWorldX - minWorldX + 1, maxWorldZ - minWorldZ + 1, seed
      );
      EntrancePlacement entrance = resolveEntrance(feature, nearbyRoads, projection, minWorldX, maxWorldX, minWorldZ, maxWorldZ, style, seed);
      return new BuildingBlueprint(groupId, seed, profile, style, baseY, floorY, roofBaseY, topY, minWorldX, maxWorldX, minWorldZ, maxWorldZ, entrance.worldX(), entrance.worldZ(), entrance.facing(), entrance.width());
   }

   private static EntrancePlacement resolveEntrance(
      OsmBuildingFeature feature,
      List<RoadFeature> nearbyRoads,
      WorldProjection projection,
      int minWorldX,
      int maxWorldX,
      int minWorldZ,
      int maxWorldZ,
      BuildingStyle style,
      long seed
   ) {
      int entranceWidth = entranceWidth(style);
      double[] centroid = feature.centroidWorld(projection);
      int fallbackX = Mth.clamp((int)Math.round(centroid[0]), minWorldX, maxWorldX);
      int fallbackZ = Mth.clamp((int)Math.round(centroid[1]), minWorldZ, maxWorldZ);
      Direction bestFacing = longestAxisFacing(minWorldX, maxWorldX, minWorldZ, maxWorldZ);
      double bestDistanceSq = Double.POSITIVE_INFINITY;
      if (nearbyRoads != null) {
         for (RoadFeature road : nearbyRoads) {
            for (int i = 0; i < road.pointCount(); i++) {
               double roadX = projection.lonToBlockX(road.lonAt(i));
               double roadZ = projection.latToBlockZ(road.latAt(i));
               double dx = roadX - centroid[0];
               double dz = roadZ - centroid[1];
               double distanceSq = dx * dx + dz * dz;
               if (distanceSq <= 24.0 * 24.0 && distanceSq < bestDistanceSq) {
                  bestDistanceSq = distanceSq;
                  if (Math.abs(dx) >= Math.abs(dz)) {
                     bestFacing = dx > 0.0 ? Direction.EAST : Direction.WEST;
                  } else {
                     bestFacing = dz > 0.0 ? Direction.SOUTH : Direction.NORTH;
                  }
               }
            }
         }
      }

      EntrancePlacement snapped = snapEntranceToFootprint(
         feature, projection, minWorldX, maxWorldX, minWorldZ, maxWorldZ, bestFacing, fallbackX, fallbackZ, entranceWidth
      );
      if (snapped != null) {
         return snapped;
      }

      return switch (bestFacing) {
         case NORTH -> new EntrancePlacement(Mth.clamp(fallbackX, minWorldX + 1, maxWorldX - 1), minWorldZ, bestFacing, entranceWidth);
         case SOUTH -> new EntrancePlacement(Mth.clamp(fallbackX, minWorldX + 1, maxWorldX - 1), maxWorldZ, bestFacing, entranceWidth);
         case EAST -> new EntrancePlacement(maxWorldX, Mth.clamp(fallbackZ, minWorldZ + 1, maxWorldZ - 1), bestFacing, entranceWidth);
         case WEST -> new EntrancePlacement(minWorldX, Mth.clamp(fallbackZ, minWorldZ + 1, maxWorldZ - 1), bestFacing, entranceWidth);
         default -> new EntrancePlacement(fallbackX, minWorldZ, Direction.NORTH, entranceWidth);
      };
   }

   private static EntrancePlacement snapEntranceToFootprint(
      OsmBuildingFeature feature,
      WorldProjection projection,
      int minWorldX,
      int maxWorldX,
      int minWorldZ,
      int maxWorldZ,
      Direction preferredFacing,
      int preferredWorldX,
      int preferredWorldZ,
      int entranceWidth
   ) {
      EntranceCandidate preferred = findBoundaryCandidate(
         feature, projection, minWorldX, maxWorldX, minWorldZ, maxWorldZ, preferredFacing, preferredWorldX, preferredWorldZ
      );
      if (preferred != null) {
         return new EntrancePlacement(preferred.worldX(), preferred.worldZ(), preferred.facing(), entranceWidth);
      }

      EntranceCandidate best = null;
      for (Direction direction : Direction.Plane.HORIZONTAL) {
         EntranceCandidate candidate = findBoundaryCandidate(
            feature, projection, minWorldX, maxWorldX, minWorldZ, maxWorldZ, direction, preferredWorldX, preferredWorldZ
         );
         if (candidate != null && (best == null || candidate.score() < best.score())) {
            best = candidate;
         }
      }

      return best == null ? null : new EntrancePlacement(best.worldX(), best.worldZ(), best.facing(), entranceWidth);
   }

   private static int entranceWidth(BuildingStyle style) {
      if (style.garageDoor()) {
         return 3;
      }
      return style.singleDoor() ? 1 : 1;
   }

   private static EntranceCandidate findBoundaryCandidate(
      OsmBuildingFeature feature,
      WorldProjection projection,
      int minWorldX,
      int maxWorldX,
      int minWorldZ,
      int maxWorldZ,
      Direction facing,
      int preferredWorldX,
      int preferredWorldZ
   ) {
      EntranceCandidate best = null;
      for (int worldZ = minWorldZ; worldZ <= maxWorldZ; worldZ++) {
         for (int worldX = minWorldX; worldX <= maxWorldX; worldX++) {
            if (!feature.containsWorld(worldX + 0.5, worldZ + 0.5, projection)
               || !touchesExterior(feature, projection, worldX, worldZ, facing)) {
               continue;
            }

            double dx = worldX - preferredWorldX;
            double dz = worldZ - preferredWorldZ;
            double score = dx * dx + dz * dz;
            if (best == null || score < best.score()) {
               best = new EntranceCandidate(worldX, worldZ, facing, score);
            }
         }
      }

      return best;
   }

   private static boolean touchesExterior(
      OsmBuildingFeature feature, WorldProjection projection, int worldX, int worldZ, Direction facing
   ) {
      return !feature.containsWorld(worldX + 0.5 + facing.getStepX(), worldZ + 0.5 + facing.getStepZ(), projection);
   }

   private static Direction longestAxisFacing(int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ) {
      int width = maxWorldX - minWorldX;
      int depth = maxWorldZ - minWorldZ;
      return width >= depth ? Direction.SOUTH : Direction.EAST;
   }

   private static long mixSeed(long worldSeed, long featureId, long salt) {
      long seed = worldSeed ^ featureId * 341873128712L ^ salt * 132897987541L;
      seed ^= seed >>> 33;
      seed *= -49064778989728563L;
      seed ^= seed >>> 33;
      seed *= -4265267296055464877L;
      seed ^= seed >>> 33;
      return seed;
   }

   private record EntrancePlacement(int worldX, int worldZ, Direction facing, int width) {
   }

   private record EntranceCandidate(int worldX, int worldZ, Direction facing, double score) {
   }
}
