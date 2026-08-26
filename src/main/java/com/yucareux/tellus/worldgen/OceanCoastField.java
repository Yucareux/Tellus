package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.Arrays;
import net.minecraft.util.Mth;

/**
 * Cached, Overture-only ocean mask and coastal distance field. The retained
 * 512x512 core is independent of the underlying MVT boundaries.
 */
public final class OceanCoastField {
   static final int CORE_SIZE = 512;
   private static final int CARDINAL_COST = 10;
   private static final int DIAGONAL_COST = 14;
   private static final int[] DX = new int[]{1, 0, -1, 0, 1, 1, -1, -1};
   private static final int[] DZ = new int[]{0, 1, 0, -1, 1, -1, 1, -1};
   private static final int[] COST = new int[]{
      CARDINAL_COST,
      CARDINAL_COST,
      CARDINAL_COST,
      CARDINAL_COST,
      DIAGONAL_COST,
      DIAGONAL_COST,
      DIAGONAL_COST,
      DIAGONAL_COST
   };
   private static final int CACHE_TILES = intProperty("tellus.oceanCoastCacheTiles", 32, 4, 256);
   private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

   private final TellusOsmWaterSource waterSource;
   private final WorldProjection projection;
   private final double worldScale;
   private final int transitionBlocks;
   private final RawDepthSampler rawDepthSampler;
   private final Cache<Long, MacroTile> cache = CacheBuilder.newBuilder().maximumSize(CACHE_TILES).build();

   public OceanCoastField(
      TellusOsmWaterSource waterSource,
      WorldProjection projection,
      int transitionBlocks,
      RawDepthSampler rawDepthSampler
   ) {
      this.waterSource = waterSource;
      this.projection = projection;
      this.worldScale = projection.worldScale();
      this.transitionBlocks = OceanFloorProfile.clampTransitionDistance(transitionBlocks);
      this.rawDepthSampler = rawDepthSampler;
   }

   public OceanCoastSample sample(int blockX, int blockZ) {
      int macroX = Math.floorDiv(blockX, CORE_SIZE);
      int macroZ = Math.floorDiv(blockZ, CORE_SIZE);
      long key = pack(macroX, macroZ);
      MacroTile tile = this.cache.getIfPresent(key);
      if (tile == null) {
         tile = this.build(macroX, macroZ);
         if (tile.coverageStatus == TellusOsmWaterSource.CoverageStatus.COMPLETE) {
            this.cache.put(key, tile);
         }
      }

      int localX = Math.floorMod(blockX, CORE_SIZE);
      int localZ = Math.floorMod(blockZ, CORE_SIZE);
      int index = localZ * CORE_SIZE + localX;
      return new OceanCoastSample(
         tile.ocean[index],
         Short.toUnsignedInt(tile.distance[index]),
         tile.correction[index],
         tile.coverageStatus
      );
   }

   public void clear() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
   }

   private MacroTile build(int macroX, int macroZ) {
      int halo = this.transitionBlocks + 1;
      int size = CORE_SIZE + halo * 2;
      int area = Math.multiplyExact(size, size);
      int minX = macroX * CORE_SIZE - halo;
      int minZ = macroZ * CORE_SIZE - halo;
      int maxX = minX + size - 1;
      int maxZ = minZ + size - 1;
      TellusOsmWaterSource.WaterQueryResult query = this.waterSource.waterForAreaWithStatus(
         minX, minZ, maxX, maxZ, this.projection, 0, OsmQueryMode.BLOCKING
      );
      if (!query.complete()) {
         return MacroTile.incomplete(query.coverageStatus(), this.transitionBlocks);
      }

      Scratch scratch = SCRATCH.get();
      scratch.ensureCapacity(area);
      boolean[] ocean = scratch.ocean;
      Arrays.fill(ocean, 0, area, false);
      for (OsmWaterFeature feature : query.features()) {
         if (feature.oceanHint()) {
            this.rasterize(feature, minX, minZ, size, ocean);
         }
      }

      int maxCost = this.transitionBlocks * CARDINAL_COST;
      int[] distance = scratch.distance;
      int[] nearestSeed = scratch.nearestSeed;
      Arrays.fill(distance, 0, area, Integer.MAX_VALUE);
      Arrays.fill(nearestSeed, 0, area, -1);
      scratch.heap.reset(area);

      for (int z = 1; z < size - 1; z++) {
         int row = z * size;
         for (int x = 1; x < size - 1; x++) {
            int index = row + x;
            if (ocean[index] && isCoastCell(ocean, index, size)) {
               distance[index] = 0;
               nearestSeed[index] = index;
               scratch.heap.addOrDecrease(index, distance);
            }
         }
      }

      propagate(ocean, true, size, maxCost, distance, nearestSeed, scratch.heap);
      boolean[] correctedSeed = scratch.correctedSeed;
      boolean[] validDepth = scratch.validDepth;
      int[] rawDepth = scratch.rawDepth;
      Arrays.fill(correctedSeed, 0, area, false);
      Arrays.fill(validDepth, 0, area, false);
      Arrays.fill(rawDepth, 0, area, 0);
      int triggerBlocks = OceanFloorProfile.scaleBlockDistance(OceanFloorProfile.TRIGGER_SCAN_BLOCKS, this.worldScale);
      int triggerCost = Math.min(maxCost, triggerBlocks * CARDINAL_COST);

      if (this.rawDepthSampler != null && triggerCost >= 0) {
         for (int z = 1; z < size - 1; z++) {
            int row = z * size;
            for (int x = 1; x < size - 1; x++) {
               int index = row + x;
               if (ocean[index] && distance[index] <= triggerCost) {
                  BathymetrySample sample = this.rawDepthSampler.sample(minX + x, minZ + z);
                  rawDepth[index] = Math.max(OceanFloorProfile.MIN_DEPTH, sample.depth());
                  validDepth[index] = sample.valid();
               }
            }
         }

         for (int z = 1; z < size - 1; z++) {
            int row = z * size;
            for (int x = 1; x < size - 1; x++) {
               int index = row + x;
               if (!ocean[index] || distance[index] > triggerCost) {
                  continue;
               }

               int coastwardDepth = 0;
               for (int n = 0; n < DX.length; n++) {
                  int neighbor = (z + DZ[n]) * size + x + DX[n];
                  if (ocean[neighbor] && distance[neighbor] < distance[index] && rawDepth[neighbor] > 0) {
                     coastwardDepth = coastwardDepth == 0
                        ? rawDepth[neighbor]
                        : Math.min(coastwardDepth, rawDepth[neighbor]);
                  }
               }

               if (OceanFloorProfile.shouldCorrect(
                  validDepth[index], rawDepth[index], distance[index] / (double)CARDINAL_COST, coastwardDepth, 1.0
               )) {
                  int seed = nearestSeed[index];
                  if (seed >= 0) {
                     correctedSeed[seed] = true;
                  }
               }
            }
         }
      }

      this.dilateCorrectedSeeds(correctedSeed, distance, nearestSeed, size, area, scratch);
      boolean[] coreOcean = new boolean[CORE_SIZE * CORE_SIZE];
      short[] coreDistance = new short[CORE_SIZE * CORE_SIZE];
      boolean[] coreCorrection = new boolean[CORE_SIZE * CORE_SIZE];
      for (int z = 0; z < CORE_SIZE; z++) {
         int paddedRow = (z + halo) * size + halo;
         int coreRow = z * CORE_SIZE;
         for (int x = 0; x < CORE_SIZE; x++) {
            int padded = paddedRow + x;
            int core = coreRow + x;
            coreOcean[core] = ocean[padded];
            int cost = distance[padded];
            int blocks = cost == Integer.MAX_VALUE
               ? this.transitionBlocks
               : Math.min(this.transitionBlocks, (cost + CARDINAL_COST / 2) / CARDINAL_COST);
            coreDistance[core] = (short)blocks;
            int seed = nearestSeed[padded];
            coreCorrection[core] = seed >= 0 && correctedSeed[seed];
         }
      }

      return new MacroTile(coreOcean, coreDistance, coreCorrection, TellusOsmWaterSource.CoverageStatus.COMPLETE);
   }

   private void dilateCorrectedSeeds(
      boolean[] correctedSeed,
      int[] coastDistance,
      int[] nearestSeed,
      int size,
      int area,
      Scratch scratch
   ) {
      int[] dilationDistance = scratch.dilationDistance;
      Arrays.fill(dilationDistance, 0, area, Integer.MAX_VALUE);
      scratch.heap.reset(area);
      for (int index = 0; index < area; index++) {
         if (correctedSeed[index]) {
            dilationDistance[index] = 0;
            scratch.heap.addOrDecrease(index, dilationDistance);
         }
      }

      if (scratch.heap.isEmpty()) {
         return;
      }

      int dilationBlocks = OceanFloorProfile.scaleBlockDistance(OceanFloorProfile.CORRECTION_DILATION_BLOCKS, this.worldScale);
      int dilationCost = dilationBlocks * CARDINAL_COST;
      propagate(null, false, size, dilationCost, dilationDistance, null, scratch.heap);
      for (int index = 0; index < area; index++) {
         if (coastDistance[index] == 0 && nearestSeed[index] == index && dilationDistance[index] <= dilationCost) {
            correctedSeed[index] = true;
         }
      }
   }

   private static void propagate(
      boolean[] allowed,
      boolean restrictToAllowed,
      int size,
      int maxCost,
      int[] distance,
      int[] nearestSeed,
      MinHeap heap
   ) {
      while (!heap.isEmpty()) {
         int index = heap.removeMin(distance);
         int current = distance[index];
         if (current >= maxCost) {
            continue;
         }

         int x = index % size;
         int z = index / size;
         for (int n = 0; n < DX.length; n++) {
            int nx = x + DX[n];
            int nz = z + DZ[n];
            if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
               continue;
            }
            int neighbor = nz * size + nx;
            if (restrictToAllowed && !allowed[neighbor]) {
               continue;
            }
            int candidate = current + COST[n];
            if (candidate <= maxCost && candidate < distance[neighbor]) {
               distance[neighbor] = candidate;
               if (nearestSeed != null) {
                  nearestSeed[neighbor] = nearestSeed[index];
               }
               heap.addOrDecrease(neighbor, distance);
            }
         }
      }
   }

   private void rasterize(OsmWaterFeature feature, int gridMinX, int gridMinZ, int gridSize, boolean[] ocean) {
      if (feature.crossesWorldSeam(this.projection)) {
         return;
      }
      int partCount = feature.partCount();
      double[][] partXs = new double[partCount][];
      double[][] partZs = new double[partCount][];
      int minWorldX = Integer.MAX_VALUE;
      int maxWorldX = Integer.MIN_VALUE;
      int minWorldZ = Integer.MAX_VALUE;
      int maxWorldZ = Integer.MIN_VALUE;
      for (int part = 0; part < partCount; part++) {
         int count = feature.pointCount(part);
         partXs[part] = new double[count];
         partZs[part] = new double[count];
         for (int point = 0; point < count; point++) {
            double worldX = this.projection.lonToBlockX(feature.lonAt(part, point));
            double worldZ = this.projection.latToBlockZ(feature.latAt(part, point));
            partXs[part][point] = worldX;
            partZs[part][point] = worldZ;
            minWorldX = Math.min(minWorldX, Mth.floor(worldX));
            maxWorldX = Math.max(maxWorldX, Mth.ceil(worldX));
            minWorldZ = Math.min(minWorldZ, Mth.floor(worldZ));
            maxWorldZ = Math.max(maxWorldZ, Mth.ceil(worldZ));
         }
      }

      int gridMaxX = gridMinX + gridSize - 1;
      int gridMaxZ = gridMinZ + gridSize - 1;
      int fillMinX = Math.max(gridMinX, minWorldX);
      int fillMaxX = Math.min(gridMaxX, maxWorldX);
      int fillMinZ = Math.max(gridMinZ, minWorldZ);
      int fillMaxZ = Math.min(gridMaxZ, maxWorldZ);
      if (fillMaxX >= fillMinX && fillMaxZ >= fillMinZ && !feature.lineGeometry()) {
         ScanlinePolygonRasterizer.fill(
            partXs,
            partZs,
            fillMinX,
            fillMinZ,
            fillMaxX,
            fillMaxZ,
            (worldX, worldZ) -> ocean[(worldZ - gridMinZ) * gridSize + worldX - gridMinX] = true
         );
      }
   }

   private static boolean isCoastCell(boolean[] ocean, int index, int size) {
      return !ocean[index - 1] || !ocean[index + 1] || !ocean[index - size] || !ocean[index + size];
   }

   private static long pack(int x, int z) {
      return (long)x & 4294967295L | ((long)z & 4294967295L) << 32;
   }

   private static int intProperty(String key, int defaultValue, int min, int max) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return defaultValue;
      }
      try {
         return Mth.clamp(Integer.parseInt(value), min, max);
      } catch (NumberFormatException ignored) {
         return defaultValue;
      }
   }

   @FunctionalInterface
   public interface RawDepthSampler {
      BathymetrySample sample(int blockX, int blockZ);
   }

   public record BathymetrySample(boolean valid, int depth) {
   }

   private record MacroTile(
      boolean[] ocean,
      short[] distance,
      boolean[] correction,
      TellusOsmWaterSource.CoverageStatus coverageStatus
   ) {
      private static MacroTile incomplete(TellusOsmWaterSource.CoverageStatus status, int transitionBlocks) {
         short[] distance = new short[CORE_SIZE * CORE_SIZE];
         Arrays.fill(distance, (short)transitionBlocks);
         return new MacroTile(new boolean[CORE_SIZE * CORE_SIZE], distance, new boolean[CORE_SIZE * CORE_SIZE], status);
      }
   }

   private static final class Scratch {
      private boolean[] ocean = new boolean[0];
      private boolean[] correctedSeed = new boolean[0];
      private boolean[] validDepth = new boolean[0];
      private int[] distance = new int[0];
      private int[] dilationDistance = new int[0];
      private int[] nearestSeed = new int[0];
      private int[] rawDepth = new int[0];
      private final MinHeap heap = new MinHeap();

      private void ensureCapacity(int area) {
         if (this.ocean.length < area) {
            this.ocean = new boolean[area];
            this.correctedSeed = new boolean[area];
            this.validDepth = new boolean[area];
            this.distance = new int[area];
            this.dilationDistance = new int[area];
            this.nearestSeed = new int[area];
            this.rawDepth = new int[area];
         }
      }
   }

   private static final class MinHeap {
      private int[] nodes = new int[0];
      private int[] positions = new int[0];
      private int size;

      private void reset(int capacity) {
         if (this.nodes.length < capacity) {
            this.nodes = new int[capacity];
            this.positions = new int[capacity];
         }
         Arrays.fill(this.positions, 0, capacity, -1);
         this.size = 0;
      }

      private boolean isEmpty() {
         return this.size == 0;
      }

      private void addOrDecrease(int node, int[] priorities) {
         int position = this.positions[node];
         if (position < 0) {
            position = this.size++;
            this.nodes[position] = node;
            this.positions[node] = position;
         }
         this.siftUp(position, priorities);
      }

      private int removeMin(int[] priorities) {
         int result = this.nodes[0];
         this.positions[result] = -1;
         int last = this.nodes[--this.size];
         if (this.size > 0) {
            this.nodes[0] = last;
            this.positions[last] = 0;
            this.siftDown(0, priorities);
         }
         return result;
      }

      private void siftUp(int position, int[] priorities) {
         int node = this.nodes[position];
         while (position > 0) {
            int parent = (position - 1) >>> 1;
            int parentNode = this.nodes[parent];
            if (priorities[parentNode] <= priorities[node]) {
               break;
            }
            this.nodes[position] = parentNode;
            this.positions[parentNode] = position;
            position = parent;
         }
         this.nodes[position] = node;
         this.positions[node] = position;
      }

      private void siftDown(int position, int[] priorities) {
         int node = this.nodes[position];
         while (true) {
            int left = position * 2 + 1;
            if (left >= this.size) {
               break;
            }
            int right = left + 1;
            int best = right < this.size && priorities[this.nodes[right]] < priorities[this.nodes[left]] ? right : left;
            if (priorities[node] <= priorities[this.nodes[best]]) {
               break;
            }
            int child = this.nodes[best];
            this.nodes[position] = child;
            this.positions[child] = position;
            position = best;
         }
         this.nodes[position] = node;
         this.positions[node] = position;
      }
   }
}
