package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Inland water shared by blocks, previews and LODs: rivers from the {@link RiverNetwork} drawn by
 * {@link RiverChannels}, and lakes at one level per mapped water body. Everything is planned on fixed world
 * tiles at the terrain's native resolution, never at a renderer's, so every consumer sees the same water.
 */
final class InlandHydrologyPlanner {
   private final InlandWaterInput source;
   private final double scale;
   private final TerrainSampler sampler;
   private final OutletSampler outlets;
   private final FootprintMask inlandMask;
   private final int bankRadius;
   private volatile State state;

   InlandHydrologyPlanner(TellusOsmWaterSource source, double scale, int bankRadius, TerrainSampler sampler, OutletSampler outlets, FootprintMask inlandMask, InlandWaterfallHeight heightScale) {
      this(InlandWaterInput.from(source), scale, bankRadius, sampler, outlets, inlandMask, heightScale);
   }

   InlandHydrologyPlanner(InlandWaterInput source, double scale, int bankRadius, TerrainSampler sampler, OutletSampler outlets, FootprintMask inlandMask, InlandWaterfallHeight heightScale) {
      this.source = source;
      this.scale = scale;
      this.sampler = sampler;
      this.outlets = outlets;
      this.inlandMask = inlandMask;
      this.bankRadius = Math.max(12, Math.min(64, bankRadius));
      this.state = new State();
   }

   void clear() { state = new State(); }

   boolean mayAffect(int x, int z) {
      return state.mayAffect(x, z);
   }

   Column sample(int x, int z, int rawTerrain) {
      return sealedColumn(state, x, z, rawTerrain);
   }

   /**
    * Water surface of an already planned lake: the identity-wide level that planned columns use,
    * independent of tile clipping and of the caller's resolution. Never plans a lake;
    * {@link Integer#MIN_VALUE} when this one is not planned (or incomplete), or for line and point water.
    */
   int plannedLakeLevel(OsmWaterFeature feature) {
      if (feature.featureId() == -1L || feature.lineGeometry() || feature.pointGeometry()) return Integer.MIN_VALUE;
      Lake lake = state.lakes.getIfPresent(feature.featureId());
      return lake != null && lake.complete ? lake.level : Integer.MIN_VALUE;
   }

   private Column sealedColumn(State current, int x, int z, int rawTerrain) {
      return current.sealed.get(x, z, current.sealLoader).forTerrain(rawTerrain);
   }

   /**
    * Seals a column against its neighbours: a wet bed lies below every touching water surface, and dry ground
    * touching water stands at least at its level, so no reader ever sees an open side before fluid ticks.
    */
   private Column seal(State current, int x, int z) {
      Column center = current.sample(x, z, 0);
      if (!center.managed() || !center.wet() && !center.bank()) return center;
      int bed = center.terrain();
      for (int d = 0; d < 8; d += 2) {
         int nx = x + DX[d], nz = z + DZ[d];
         if (!inlandMask.allowed(nx, nz)) {
            int boundaryWater = outlets.waterLevel(nx, nz);
            if (boundaryWater != Integer.MIN_VALUE)
               bed = center.wet() ? Math.min(bed, boundaryWater - 1) : Math.max(bed, boundaryWater);
            continue;
         }
         Column neighbor = current.sample(nx, nz, 0);
         if (!neighbor.wet()) continue;
         // Falling water does not spread sideways in the air: beside a curtain the ground only holds the pool at
         // its foot (two blocks above the bed below a fall), not the whole height of the fall, and none where that
         // foot stands well above it (a curtain on a ledge of a cascade, whose water falls on down the slope).
         int level = neighbor.falling() && !center.wet() ? Math.min(neighbor.water(), neighbor.terrain() + 2) : neighbor.water();
         if (neighbor.falling() && !center.wet() && level > center.terrain() + 2) continue;
         bed = center.wet() ? Math.min(bed, level - 1) : Math.max(bed, level);
      }
      return new Column(true, center.wet(), center.bank(), bed, center.wet() ? center.water() : bed, center.flowLevel(), center.falling());
   }

   static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
   static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};

   Column sampleFootprint(int x, int z, int raw, int cellSize) {
      State current = state;
      if (cellSize <= 1) return sealedColumn(current, x, z, raw);
      long hash = HydrologyBlockCache.mix(HydrologyWaterMap.key(x, z) + cellSize * 0x632BE59BD9B4E019L);
      int slot = (int)(hash >>> 32) & current.footprints.length() - 1;
      Footprint cached = current.footprints.get(slot);
      if (cached != null && cached.x == x && cached.z == z && cached.size == cellSize) return cached.column.forTerrain(raw);
      Footprint computed = new Footprint(x, z, cellSize, footprint(current, x, z, cellSize));
      Footprint published = current.footprints.get(slot);
      // A concurrent request for the same cell keeps the value published first.
      if (published != null && published.x == x && published.z == z && published.size == cellSize) computed = published;
      else current.footprints.set(slot, computed);
      return computed.column.forTerrain(raw);
   }

   /**
    * The column representing a coarse cell: its centre's, unless water lies elsewhere in the cell, which then
    * represents it (a narrow channel or a falling curtain between cell centres must not vanish from a LOD).
    * Only actual planned wet columns qualify; a mapped outline by itself adds no water.
    */
   private Column footprint(State current, int x, int z, int cellSize) {
      Column center = sealedColumn(current, x, z, 0);
      if (!center.managed() || center.falling()) return center;
      int minX = x - cellSize / 2, minZ = z - cellSize / 2;
      int maxX = minX + cellSize - 1, maxZ = minZ + cellSize - 1;
      List<int[]> points = new ArrayList<>();
      for (int tz = Math.floorDiv(minZ, RiverChannels.TILE); tz <= Math.floorDiv(maxZ, RiverChannels.TILE); tz++)
         for (int tx = Math.floorDiv(minX, RiverChannels.TILE); tx <= Math.floorDiv(maxX, RiverChannels.TILE); tx++)
            current.channels(tx, tz).footprintPoints(x, z, minX, minZ, maxX, maxZ, points);
      if (center.wet() && points.isEmpty()) return center;
      // Small lakes can also fit between sample centres.
      for (HydrologyWaterMap.Shape shape : current.map.shapes(x, z))
         if (!shape.river) addShapePoints(shape, x, z, minX, minZ, maxX, maxZ, points);
      sortByDistance(points, x, z);
      LongOpenHashSet seen = new LongOpenHashSet();
      int checked = 0;
      Column selected = center;
      for (int[] point : points) {
         if (!seen.add(HydrologyWaterMap.key(point[0], point[1])) || !inlandMask.allowed(point[0], point[1])) continue;
         Column column = sealedColumn(current, point[0], point[1], 0);
         if (column.managed() && column.wet()) {
            if (column.falling()) return column;
            if (!selected.wet()) selected = column;
         }
         if (++checked == 32) break;
      }
      return selected;
   }

   /**
    * River water for a coarse LOD cell, from the river network at the detail level matching the cell: only
    * waterways wide enough to show at that size, each at least half a cell wide so it stays unbroken, at the
    * network's level. Null when no such river covers the cell centre.
    */
   Column sampleCoarseRiver(int x, int z, int cellSize) {
      return state.coarseRiver(x, z, Math.max(1, cellSize));
   }

   /** Network detail level for a LOD cell: each level doubles the network cell, which stays about half a LOD cell. */
   static int coarseLevel(double cellMeters) {
      int level = 0;
      while (level < 14 && RiverScale.NETWORK_CELL_METERS * (1L << level) * 2.0 < cellMeters) level++;
      return Math.max(1, level);
   }

   private static void addShapePoints(HydrologyWaterMap.Shape shape, int x, int z, int minX, int minZ, int maxX, int maxZ, List<int[]> points) {
      int cx = (int)Math.max(minX, Math.min(maxX, (shape.minX + shape.maxX) * 0.5));
      int cz = (int)Math.max(minZ, Math.min(maxZ, (shape.minZ + shape.maxZ) * 0.5));
      if (shape.contains(cx, cz)) points.add(new int[]{cx, cz});
      // Only segments reaching the cell can clip to a point inside it.
      shape.segments(minX, minZ, maxX, maxZ, (ax, az, bx, bz) -> {
         int[] point = clippedPoint(ax, az, bx, bz, x, z, minX, minZ, maxX, maxZ);
         if (point != null) points.add(point);
      });
   }

   /**
    * Orders candidates by distance from the cell center, then by coordinates.
    * Each distance is computed once rather than on every comparison.
    */
   private static void sortByDistance(List<int[]> points, int x, int z) {
      int count = points.size();
      if (count < 2) return;
      Ranked[] ranked = new Ranked[count];
      for (int i = 0; i < count; i++) {
         int[] p = points.get(i);
         ranked[i] = new Ranked(p, Math.hypot((double)p[0] - x, (double)p[1] - z));
      }
      Arrays.sort(ranked, RANKED_ORDER);
      for (int i = 0; i < count; i++) points.set(i, ranked[i].point);
   }

   private record Ranked(int[] point, double distance) {}
   private static final Comparator<Ranked> RANKED_ORDER = Comparator.comparingDouble(Ranked::distance)
      .thenComparingInt((Ranked r) -> r.point[0]).thenComparingInt((Ranked r) -> r.point[1]);

   /** The point of a segment, clipped to a box, nearest the box's reference point; null when it misses the box. */
   static int[] clippedPoint(double ax, double az, double bx, double bz, int x, int z, int minX, int minZ, int maxX, int maxZ) {
      double dx = bx - ax, dz = bz - az, low = 0.0, high = 1.0;
      double[] a = {ax, az}, delta = {dx, dz};
      int[] minimum = {minX, minZ}, maximum = {maxX, maxZ};
      for (int axis = 0; axis < 2; axis++) {
         if (Math.abs(delta[axis]) < 1.0E-9) {
            if (a[axis] < minimum[axis] || a[axis] > maximum[axis]) return null;
         } else {
            double first = (minimum[axis] - a[axis]) / delta[axis], last = (maximum[axis] - a[axis]) / delta[axis];
            low = Math.max(low, Math.min(first, last)); high = Math.min(high, Math.max(first, last));
            if (low > high) return null;
         }
      }
      double t = Math.max(low, Math.min(high, ((x - ax) * dx + (z - az) * dz) / Math.max(1.0E-9, dx * dx + dz * dz)));
      return new int[]{Math.max(minX, Math.min(maxX, (int)Math.round(ax + t * dx))),
         Math.max(minZ, Math.min(maxZ, (int)Math.round(az + t * dz)))};
   }

   record Elevation(double height, double resolutionBlocks) {}
   interface TerrainSampler {
      Elevation sample(int x, int z);

      /** Optionally loads the inputs for a batch of upcoming samples; sampling results must not change. */
      default void preload(int[] xs, int[] zs, int count) {
      }

      /**
       * Terrain from a model no finer than {@code meters}, for analyses over areas where the native model would
       * read far too many tiles. Defaults to the native terrain.
       */
      default Elevation sampleAtResolution(int x, int z, double meters) {
         return sample(x, z);
      }

      /** {@link #preload} for samples of {@link #sampleAtResolution} at that resolution. */
      default void preloadAtResolution(int[] xs, int[] zs, int count, double meters) {
      }
   }
   interface OutletSampler { int waterLevel(int x, int z); }
   interface FootprintMask { boolean allowed(int x, int z); }
   /** Real-world metres of height to blocks at a row, for consumers that measure drops in metres. */
   interface InlandWaterfallHeight { double blocks(double meters, int z); }
   private record Footprint(int x, int z, int size, Column column) {}
   private record CoarseKey(int level, int tx, int tz) {}

   record Column(boolean managed, boolean wet, boolean bank, int terrain, int water, int flowLevel, boolean falling) {
      Column(boolean managed, boolean wet, boolean bank, int terrain, int water, int flowLevel) {
         this(managed, wet, bank, terrain, water, flowLevel, false);
      }
      Column forTerrain(int raw) { return !managed ? fallback(raw) : !wet && !bank ? dry(raw) : this; }
      static Column dry(int terrain) { return new Column(true, false, false, terrain, terrain, 0); }
      static Column fallback(int terrain) { return new Column(false, false, false, terrain, terrain, 0); }
   }

   private static final Column UNAFFECTED = Column.dry(0);

   private final class State {
      final HydrologyWaterMap map = new HydrologyWaterMap(source, scale);
      final RiverNetwork network = new RiverNetwork(source, scale, sampler, outlets, new RiverNetwork.LakeLevels() {
         @Override
         public int level(HydrologyWaterMap.Shape shape) {
            return networkLakeLevel(shape);
         }

         @Override
         public boolean flowing(HydrologyWaterMap.Shape shape) {
            return lake(shape).flowing;
         }
      });

      /** A lake's planned water surface for the river network, so waterways run into it at its level. */
      private int networkLakeLevel(HydrologyWaterMap.Shape shape) {
         Lake lake = lake(shape);
         return lake.complete && lake.wet && !lake.flowing ? lake.level : Integer.MIN_VALUE;
      }
      final HydrologyBlockCache<Elevation> terrain = new HydrologyBlockCache<>(4096);
      final Cache<Long, RiverChannels> channels = CacheBuilder.newBuilder().maximumSize(256).build();
      final AtomicReferenceArray<RiverChannels> recentChannels = new AtomicReferenceArray<>(64);
      final Cache<Long, Lake> lakes = CacheBuilder.newBuilder().maximumSize(2048).build();
      final HydrologyBlockCache<Column> columns = new HydrologyBlockCache<>(2048);
      final HydrologyBlockCache<Column> sealed = new HydrologyBlockCache<>(1024);
      // Coarse cells are rarely requested twice; a direct-mapped memo keeps
      // repeats cheap without the bookkeeping of a bounded LRU cache.
      final AtomicReferenceArray<Footprint> footprints = new AtomicReferenceArray<>(32768);
      final HydrologyBlockCache.Loader<Elevation> terrainLoader = (x, z) -> sampler.sample(x, z);
      final HydrologyBlockCache.Loader<Column> columnLoader = this::sampleUncached;
      final HydrologyBlockCache.Loader<Column> sealLoader = (x, z) -> seal(this, x, z);
      final RiverChannels.Terrain channelTerrain = new RiverChannels.Terrain() {
         @Override
         public double height(int x, int z) {
            return elevation(x, z).height();
         }

         @Override
         public double resolution(int x, int z) {
            return elevation(x, z).resolutionBlocks();
         }
      };

      Elevation elevation(int x, int z) {
         return terrain.get(x, z, terrainLoader);
      }

      boolean mayAffect(int x, int z) {
         return mayAffect(map.shapes(x, z), x, z);
      }

      private boolean mayAffect(List<HydrologyWaterMap.Shape> shapes, int x, int z) {
         for (HydrologyWaterMap.Shape shape : shapes) {
            if (!shape.near(x, z, 160.0)) continue;
            // A line contains only points within its width, all of which are within 160 blocks.
            if (shape.feature.lineGeometry() ? shape.within(x, z, 160.0) : shape.contains(x, z) || shape.within(x, z, 160.0)) return true;
         }
         return false;
      }

      Column sample(int x, int z, int raw) {
         return columns.get(x, z, columnLoader).forTerrain(raw);
      }

      // Coarse networks and their plans, per detail level.
      final AtomicReferenceArray<RiverNetwork> coarseNetworks = new AtomicReferenceArray<>(16);
      final Cache<CoarseKey, RiverChannels> coarseChannels = CacheBuilder.newBuilder().maximumSize(512).build();

      Column coarseRiver(int x, int z, int cellSize) {
         int level = coarseLevel(cellSize * scale);
         RiverNetwork network = coarseNetworks.get(level);
         if (network == null) {
            coarseNetworks.compareAndSet(level, null, this.network.coarse(level));
            network = coarseNetworks.get(level);
         }
         RiverNetwork chosen = network;
         // Plans are half a network tile, so each lies inside one; their channels are at least half a LOD cell wide.
         int planSize = chosen.tileSize() / 2;
         int tx = Math.floorDiv(x, planSize), tz = Math.floorDiv(z, planSize);
         RiverChannels plan = cached(coarseChannels, new CoarseKey(level, tx, tz), () -> {
            RiverNetwork.Tile tile = chosen.tileAt(tx * planSize, tz * planSize);
            return new RiverChannels(tx, tz, planSize, false, chosen.cellSize(), tile, null);
         });
         return plan.sample(x, z, Integer.MAX_VALUE, null);
      }

      RiverChannels channels(int tx, int tz) {
         long key = HydrologyBlockCache.mix(HydrologyWaterMap.key(tx, tz));
         int slot = (int)(key >>> 58);
         RiverChannels recent = recentChannels.get(slot);
         if (recent != null && recent.tx == tx && recent.tz == tz) return recent;
         RiverChannels built = cached(channels, key, () -> {
            long started = HydrologyMetrics.now();
            RiverNetwork.Tile tile = network.tileAt(tx * RiverChannels.TILE, tz * RiverChannels.TILE);
            RiverChannels plan = new RiverChannels(tx, tz, tile, network.scale(), channelTerrain);
            HydrologyMetrics.plan(started);
            return plan;
         });
         recentChannels.set(slot, built);
         return built;
      }

      private Column sampleUncached(int x, int z) {
         checkCancelled();
         List<HydrologyWaterMap.Shape> shapes = map.shapes(x, z);
         if (!mayAffect(shapes, x, z)) return UNAFFECTED;
         Elevation here = elevation(x, z);
         int raw = (int)Math.ceil(here.height());
         // Lake identity and level are independent of the hydrology tile and of
         // which clipped fragment happened to be requested first.
         Column lakeColumn = null;
         long[] seen = null;
         int seenCount = 0;
         double lakeReach = bankRadius + 16.0;
         boolean lakeHint = false;
         for (HydrologyWaterMap.Shape shape : shapes) {
            // River areas, mapped waterways and falls are drawn by the river plans, never as a flat lake.
            if (shape.river) continue;
            long identity = shape.identity();
            boolean repeated = false;
            for (int i = 0; i < seenCount && !repeated; i++) repeated = seen[i] == identity;
            if (repeated) continue;
            // A lake clipped into vector-tile fragments is one body: any of its fragments within
            // reach admits the column, and the identity is then evaluated once.
            if (!shape.within(x, z, lakeReach) && !shape.contains(x, z)) continue;
            if (shape.contains(x, z)) lakeHint = true;
            if (seen == null) seen = new long[4];
            else if (seenCount == seen.length) seen = Arrays.copyOf(seen, seenCount * 2);
            seen[seenCount++] = identity;
            Lake lake = lake(shape);
            if (lake.flowing && channels(Math.floorDiv(x, RiverChannels.TILE), Math.floorDiv(z, RiverChannels.TILE)).drawsArea(shape)) continue;
            double canonicalHeight = here.height();
            // A LOD's smoothed DEM is not allowed to change the water footprint.
            if (lake.complete && canonicalHeight - lake.level > 12.0 && lake.contains(x, z)) {
               double[] neighborhood = new double[9];
               int count = 0;
               int radius = (int)Math.max(4.0, Math.min(32.0, here.resolutionBlocks() * 0.5));
               for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                  int nx = x + dx * radius, nz = z + dz * radius;
                  if (lake.contains(nx, nz)) neighborhood[count++] = elevation(nx, nz).height();
               }
               if (count > 2) { Arrays.sort(neighborhood, 0, count); canonicalHeight = neighborhood[count / 2]; }
            }
            Column candidate = lake.sample(x, z, raw, canonicalHeight, channelTerrain);
            if (!candidate.managed() && lakeHint) return candidate;
            if (candidate.wet()) { lakeColumn = candidate; break; }
            if (candidate.bank() && (lakeColumn == null || candidate.terrain() < lakeColumn.terrain())) lakeColumn = candidate;
         }
         if (lakeColumn != null && lakeColumn.wet()) return lakeColumn;
         RiverChannels plan = channels(Math.floorDiv(x, RiverChannels.TILE), Math.floorDiv(z, RiverChannels.TILE));
         Column river = plan.sample(x, z, raw, channelTerrain);
         if (river == null) river = Column.dry(raw);
         if (river.wet()) return river;
         if (lakeColumn != null && lakeColumn.bank() && (!river.bank() || lakeColumn.terrain() < river.terrain())) return lakeColumn;
         return river;
      }

      Lake lake(HydrologyWaterMap.Shape shape) {
         return cached(lakes, shape.identity(), () -> buildLake(shape));
      }

      private Lake buildLake(HydrologyWaterMap.Shape seed) {
         TellusOsmWaterSource.WaterQueryResult result = source.connectedWaterBody(seed.feature, scale);
         if (!result.complete()) throw new OceanCoverageUnavailableException(result.coverageStatus(), (int)seed.minX, (int)seed.minZ);
         if (result.features().isEmpty()) return new Lake(List.of(seed), 0, seed.engineered, false);
         List<HydrologyWaterMap.Shape> shapes = new ArrayList<>();
         for (OsmWaterFeature feature : result.features()) shapes.add(map.project(feature));
         shapes.sort(Comparator.comparingDouble((HydrologyWaterMap.Shape s) -> s.minX).thenComparingDouble(s -> s.minZ));
         double perimeter = 0.0;
         for (HydrologyWaterMap.Shape shape : shapes) for (int p = 0; p < shape.xs.length; p++)
            for (int i = 1; i < shape.xs[p].length; i++) perimeter += Math.hypot(shape.xs[p][i] - shape.xs[p][i - 1], shape.zs[p][i] - shape.zs[p][i - 1]);
         double spacing = Math.max(8.0, perimeter / 512.0);
         List<Double> levels = new ArrayList<>();
         it.unimi.dsi.fastutil.ints.IntArrayList shoreXs = new it.unimi.dsi.fastutil.ints.IntArrayList();
         it.unimi.dsi.fastutil.ints.IntArrayList shoreZs = new it.unimi.dsi.fastutil.ints.IntArrayList();
         for (HydrologyWaterMap.Shape shape : shapes) for (int p = 0; p < shape.xs.length; p++) {
            for (int i = 1; i < shape.xs[p].length; i++) {
               double ax = shape.xs[p][i - 1], az = shape.zs[p][i - 1];
               double dx = shape.xs[p][i] - ax, dz = shape.zs[p][i] - az;
               double length = Math.hypot(dx, dz);
               if (length < 1.0E-6) continue;
               int samples = Math.max(1, (int)Math.ceil(length / spacing));
               for (int sample = 0; sample < samples; sample++) {
                  double t = (sample + 0.5) / samples;
                  int x = (int)Math.round(ax + t * dx), z = (int)Math.round(az + t * dz);
                  double nx = -dz / length * 3.0, nz = dx / length * 3.0;
                  // A clipped MVT seam inside the assembled waterbody is not a shore.
                  if (containsAny(shapes, x + nx, z + nz) && containsAny(shapes, x - nx, z - nz)) continue;
                  shoreXs.add(x);
                  shoreZs.add(z);
               }
            }
         }
         // A detailed outline gives a sample per segment (the Great Salt Lake's over 4000), each decoding terrain tiles
         // of its own at fine scales; every few of them measure the shore as well.
         if (shoreXs.size() > 1024) {
            int stride = shoreXs.size() / 512, kept = 0;
            for (int i = 0; i < shoreXs.size(); i += stride, kept++) { shoreXs.set(kept, shoreXs.getInt(i)); shoreZs.set(kept, shoreZs.getInt(i)); }
            shoreXs.size(kept); shoreZs.size(kept);
         }
         for (double h : surveyHeights(shoreXs, shoreZs)) if (Double.isFinite(h)) levels.add(h);
         if (levels.isEmpty()) return new Lake(shapes, 0, seed.engineered, false);
         levels.sort(Double::compare);
         // Terrain models flatten water bodies: away from the shore, the surface inside the lake is its
         // water. Only a pond without such an interior falls back to its low shoreline. Shore percentiles
         // and outlet contacts used to put large lakes on slopes tens of blocks below their shores.
         double[] survey = interiorSurvey(shapes);
         double interior = survey == null ? Double.NaN : survey[0];
         // Water mapped without saying what it is whose surface is far from flat in the terrain model (a river reach
         // with its falls, a gorge below them) is no lake: where river water reaches it, the river plans level it from
         // the terrain instead. Its flat level stays for any part they do not draw.
         boolean flowing = seed.feature.kind() == com.yucareux.tellus.world.data.osm.OsmWaterKind.UNKNOWN && survey != null
            && survey[2] - survey[1] > Math.max(2.5, 5.0 / Math.sqrt(Math.max(1.0, scale)));
         double shoreMedian = levels.get(levels.size() / 2), shoreLow = levels.get((int)((levels.size() - 1) * 0.12));
         int level;
         // A lake whose terrain is far from flat (the Great Salt Lake: arms at different heights, a receded bed) stood
         // above much of its shore, walled in by banks and its islands sunk below it. It now keeps to its shore's low
         // end, never below its own lowest tenth: an outline running down a slope does not sink it.
         if (Double.isFinite(interior))
            level = (int)Math.ceil(Math.min(Math.min(interior, shoreMedian), Math.max(shoreLow, survey[1])) - 1.0E-6) - 1;
         else {
            double shore = shoreLow - 0.75;
            // Only a pond reads its outlets: every shore point of a large lake read a map tile for them.
            List<Double> outletLevels = new ArrayList<>();
            for (int point = 0; point < shoreXs.size(); point++) {
               int x = shoreXs.getInt(point), z = shoreZs.getInt(point);
               double h = elevation(x, z).height();
               if (!Double.isFinite(h)) continue;
               for (HydrologyWaterMap.Shape nearby : map.shapes(x, z)) {
                  if (nearby.river && (nearby.contains(x, z) || nearby.distance(x, z, 12.0) < 12.0)) {
                     outletLevels.add(h); break;
                  }
               }
            }
            if (!outletLevels.isEmpty()) {
               outletLevels.sort(Double::compare);
               shore = Math.min(shore, outletLevels.get((int)((outletLevels.size() - 1) * 0.25)) - 0.75);
            }
            level = (int)Math.floor(shore);
         }
         return new Lake(List.copyOf(shapes), level, seed.engineered, true, flowing);
      }

      /**
       * Terrain inside a water body, at least a terrain cell and a half from any shore, as {median, 10th percentile,
       * 90th percentile}; null when too few such points exist (a pond, a narrow lake).
       */
      private double[] interiorSurvey(List<HydrologyWaterMap.Shape> shapes) {
         double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = -minX, maxZ = -minX, area = 0.0;
         for (HydrologyWaterMap.Shape shape : shapes) {
            minX = Math.min(minX, shape.minX); minZ = Math.min(minZ, shape.minZ);
            maxX = Math.max(maxX, shape.maxX); maxZ = Math.max(maxZ, shape.maxZ);
            area += shape.area();
         }
         if (!(area > 0.0)) return null;
         double resolution = elevation((int)Math.floor((minX + maxX) * 0.5), (int)Math.floor((minZ + maxZ) * 0.5)).resolutionBlocks();
         double margin = Math.max(2.0, resolution * 1.5);
         double spacing = Math.max(2.0, Math.sqrt(area / 400.0));
         it.unimi.dsi.fastutil.ints.IntArrayList xs = new it.unimi.dsi.fastutil.ints.IntArrayList(), zs = new it.unimi.dsi.fastutil.ints.IntArrayList();
         for (double z = minZ + spacing * 0.5; z <= maxZ && xs.size() < 1024; z += spacing)
            for (double x = minX + spacing * 0.5; x <= maxX && xs.size() < 1024; x += spacing) {
               if (!containsAny(shapes, x, z)) continue;
               boolean nearShore = false;
               for (HydrologyWaterMap.Shape shape : shapes) if (shape.within(x, z, margin)) { nearShore = true; break; }
               if (nearShore) continue;
               xs.add((int)Math.floor(x)); zs.add((int)Math.floor(z));
            }
         if (xs.size() < 6) return null;
         double[] heights = surveyHeights(xs, zs);
         int n = 0;
         for (double h : heights) if (Double.isFinite(h)) heights[n++] = h;
         if (n < 6) return null;
         Arrays.sort(heights, 0, n);
         return new double[]{heights[n / 2], heights[(n - 1) / 10], heights[(n - 1) * 9 / 10]};
      }

      /**
       * Terrain at a large lake's survey points, which span many DEM tiles: fetched together a batch at a time, then
       * read in order. Preloaded all at once, a fine scale's tiles overflowed the terrain cache before they were read
       * and were decoded again one by one; 64 points' tiles at every zoom the sampler may probe fit it.
       */
      private double[] surveyHeights(it.unimi.dsi.fastutil.ints.IntArrayList xs, it.unimi.dsi.fastutil.ints.IntArrayList zs) {
         double[] heights = new double[xs.size()];
         for (int start = 0; start < heights.length; start += 64) {
            int end = Math.min(heights.length, start + 64);
            sampler.preload(Arrays.copyOfRange(xs.elements(), start, end), Arrays.copyOfRange(zs.elements(), start, end), end - start);
            for (int i = start; i < end; i++) heights[i] = elevation(xs.getInt(i), zs.getInt(i)).height();
         }
         return heights;
      }
   }

   private final class Lake {
      final List<HydrologyWaterMap.Shape> shapes;
      final int level;
      final boolean engineered, complete;
      // Mapped as water of no particular kind but far from flat in the terrain: levelled by the river plans, never flat.
      final boolean flowing;
      // Whether any block column lies inside the outline. A pond smaller than the block grid has no
      // water; its bank ring used to remain as a dry, lowered crater.
      final boolean wet;
      // How far the water may spread beyond the outline onto shore lower than its level. A fixed
      // several blocks turned a small pond into a much larger patch.
      private final double maximumExpansion;
      // Extra bank blend of a larger lake, blocks.
      private final double shoreBlend;
      // Shoreline pieces per 128-block bin, on a dense grid over the lake.
      private final int shoreMinX, shoreMinZ, shoreColumns;
      private final Shore[][] shore;

      Lake(List<HydrologyWaterMap.Shape> shapes, int level, boolean engineered, boolean complete) {
         this(shapes, level, engineered, complete, false);
      }

      Lake(List<HydrologyWaterMap.Shape> shapes, int level, boolean engineered, boolean complete, boolean flowing) {
         this.shapes = shapes; this.level = level; this.engineered = engineered; this.complete = complete; this.flowing = flowing;
         this.wet = complete && containsBlock(shapes);
         double area = 0.0;
         for (HydrologyWaterMap.Shape shape : shapes) area += shape.area();
         this.maximumExpansion = engineered ? 0.0 : Math.max(1.0, Math.min(7.0, Math.sqrt(area) * 0.5));
         // A larger lake blends its shore a little further; a pond hardly at all.
         this.shoreBlend = Math.min(16.0, Math.sqrt(area) * 0.25);
         List<Shore> pieces = new ArrayList<>();
         List<int[]> ranges = new ArrayList<>();
         int lowX = 0, lowZ = 0, highX = -1, highZ = -1;
         if (complete) {
            int influence = Math.max((int)RiverChannels.MAX_BLEND + 16, LakeBedProfile.maximumShoreInfluenceBlocks());
            for (HydrologyWaterMap.Shape shape : shapes) for (int p = 0; p < shape.xs.length; p++) {
               for (int i = 1; i < shape.xs[p].length; i++) {
                  double ax = shape.xs[p][i - 1], az = shape.zs[p][i - 1];
                  double dx = shape.xs[p][i] - ax, dz = shape.zs[p][i] - az;
                  double length = Math.hypot(dx, dz);
                  int count = Math.max(1, (int)Math.ceil(length / 128.0));
                  for (int piece = 0; piece < count; piece++) {
                     double t0 = piece / (double)count, t1 = (piece + 1.0) / count, mid = (t0 + t1) * 0.5;
                     double x = ax + mid * dx, z = az + mid * dz;
                     double nx = -dz / Math.max(1.0E-6, length) * 3.0, nz = dx / Math.max(1.0E-6, length) * 3.0;
                     if (containsAny(shapes, x + nx, z + nz) && containsAny(shapes, x - nx, z - nz)) continue;
                     Shore segment = new Shore(ax + t0 * dx, az + t0 * dz, ax + t1 * dx, az + t1 * dz);
                     int[] range = {
                        (int)Math.floor((Math.min(segment.ax, segment.bx) - influence) / 128.0),
                        (int)Math.floor((Math.min(segment.az, segment.bz) - influence) / 128.0),
                        (int)Math.floor((Math.max(segment.ax, segment.bx) + influence) / 128.0),
                        (int)Math.floor((Math.max(segment.az, segment.bz) + influence) / 128.0)};
                     if (pieces.isEmpty()) { lowX = range[0]; lowZ = range[1]; highX = range[2]; highZ = range[3]; }
                     lowX = Math.min(lowX, range[0]); lowZ = Math.min(lowZ, range[1]);
                     highX = Math.max(highX, range[2]); highZ = Math.max(highZ, range[3]);
                     pieces.add(segment);
                     ranges.add(range);
                  }
               }
            }
         }
         this.shoreMinX = lowX;
         this.shoreMinZ = lowZ;
         this.shoreColumns = highX - lowX + 1;
         this.shore = new Shore[Math.max(0, shoreColumns * (highZ - lowZ + 1))][];
         int[] counts = new int[shore.length];
         for (int[] range : ranges) for (int bz = range[1]; bz <= range[3]; bz++) for (int bx = range[0]; bx <= range[2]; bx++)
            counts[(bz - lowZ) * shoreColumns + bx - lowX]++;
         for (int k = 0; k < pieces.size(); k++) {
            int[] range = ranges.get(k);
            for (int bz = range[1]; bz <= range[3]; bz++) for (int bx = range[0]; bx <= range[2]; bx++) {
               int bin = (bz - lowZ) * shoreColumns + bx - lowX;
               if (shore[bin] == null) { shore[bin] = new Shore[counts[bin]]; counts[bin] = 0; }
               shore[bin][counts[bin]++] = pieces.get(k);
            }
         }
      }

      private Shore[] shoreNear(int x, int z) {
         int bx = Math.floorDiv(x, 128) - shoreMinX, bz = Math.floorDiv(z, 128) - shoreMinZ;
         if (bx < 0 || bz < 0 || bx >= shoreColumns || bz * shoreColumns + bx >= shore.length) return null;
         return shore[bz * shoreColumns + bx];
      }

      boolean contains(int x, int z) { return containsAny(shapes, x, z); }

      Column sample(int x, int z, int raw, double canonicalHeight, RiverChannels.Terrain terrain) {
         if (!complete) return Column.fallback(raw);
         if (!wet) return Column.dry(raw);
         boolean inside = containsAny(shapes, x, z), island = false;
         if (!inside) for (HydrologyWaterMap.Shape shape : shapes) island |= shape.island(x, z);
         double distance = Double.POSITIVE_INFINITY, shoreX = x, shoreZ = z;
         Shore[] nearby = shoreNear(x, z);
         if (nearby != null) {
            double prune = Double.POSITIVE_INFINITY;
            for (Shore segment : nearby) {
               double dx = segment.bx - segment.ax, dz = segment.bz - segment.az;
               double t = Math.max(0.0, Math.min(1.0, ((x - segment.ax) * dx + (z - segment.az) * dz) / Math.max(1.0E-12, dx * dx + dz * dz)));
               double ex = x - segment.ax - dx * t, ez = z - segment.az - dz * t;
               // Squared offsets rule out segments which cannot be nearer; the
               // remaining distances are computed exactly as Shore.distance does.
               if (ex * ex + ez * ez > prune) continue;
               double d = segment.distance(x, z);
               if (d < distance) { distance = d; shoreX = segment.ax + dx * t; shoreZ = segment.az + dz * t; }
               prune = HydrologyWaterMap.pruneBound(distance);
            }
         }
         double expansion = engineered ? 0.0
            : Math.min(maximumExpansion, 2.0 + 5.0 * (0.5 + 0.5 * InlandWaterProfile.noise(x, z, 89, 0xA15EA5L)));
         double cell = terrain == null ? 1.0 : terrain.resolution(x, z), steepest = Math.max(12.0, 3.0 * cell);
         boolean wet;
         if (inside) {
            // The terrain model blurs a steep shore into the water (Lake Quill's cirque): ground near the outline no
            // higher than the shore beside it is that blur and is filled, up to a few of the model's cells. A hill
            // rising above its shore (an unmapped island, a misplaced outline) stays dry.
            double excess = canonicalHeight - level;
            wet = excess <= 12 || terrain != null && distance <= 4.0 * cell
               && excess <= Math.min(steepest, RiverChannels.edgeHeight(terrain, shoreX, shoreZ) - level + 12.0);
         } else {
            // Spread only onto shore lying just below the level, where the outline and DEM disagree.
            // Ground further down (a slope below a pond, a dam face) would carry the water on a
            // raised bed between walls.
            wet = !island && distance <= expansion && canonicalHeight < level && canonicalHeight >= level - 1.0;
         }
         if (wet) {
            double depthDistance = inside ? distance + expansion : Math.max(0.0, expansion - distance);
            int depth = LakeBedProfile.depth(Math.min(LakeBedProfile.maximumShoreInfluenceBlocks(), depthDistance), x, z, scale);
            return new Column(true, true, false, level - Math.max(1, depth), level, 0);
         }
         if (distance <= RiverChannels.MAX_BLEND) {
            // The shore, a mapped island's too, keeps the natural terrain's shape, moved by the excess of the ground
            // at the water's edge over a lip level with the water's surface and fading out with distance.
            double edge = Math.ceil(RiverChannels.edgeHeight(terrain, shoreX, shoreZ));
            // An outline the water does not reach (up a slope above it) is no shore: shaping it cut a ring into the slope.
            if (edge - level > steepest) return Column.dry(raw);
            int shaped = (int)Math.round(RiverChannels.displaced(raw, edge, level, distance, shoreBlend, x, z));
            if (distance <= 1.5) shaped = Math.max(shaped, level);
            if (shaped != raw) return new Column(true, false, true, shaped, shaped, 0);
         }
         return Column.dry(raw);
      }
   }

   private static boolean containsAny(List<HydrologyWaterMap.Shape> shapes, double x, double z) {
      for (HydrologyWaterMap.Shape shape : shapes) if (shape.contains(x, z)) return true;
      return false;
   }

   /** Whether a block column (an integer coordinate, as sampled) lies inside the outlines. */
   private static boolean containsBlock(List<HydrologyWaterMap.Shape> shapes) {
      double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY, maxX = -minX, maxZ = -minX;
      for (HydrologyWaterMap.Shape shape : shapes) {
         minX = Math.min(minX, shape.minX); minZ = Math.min(minZ, shape.minZ);
         maxX = Math.max(maxX, shape.maxX); maxZ = Math.max(maxZ, shape.maxZ);
      }
      long x0 = (long)Math.ceil(minX), x1 = (long)Math.floor(maxX), z0 = (long)Math.ceil(minZ), z1 = (long)Math.floor(maxZ);
      if (x1 < x0 || z1 < z0) return false;
      // Larger outlines certainly contain columns; only small ones are checked.
      if ((x1 - x0 + 1) * (z1 - z0 + 1) > 4096) return true;
      for (long z = z0; z <= z1; z++) for (long x = x0; x <= x1; x++) if (containsAny(shapes, x, z)) return true;
      return false;
   }

   private record Shore(double ax, double az, double bx, double bz) {
      double distance(double x, double z) {
         double dx = bx - ax, dz = bz - az;
         double t = Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / Math.max(1.0E-12, dx * dx + dz * dz)));
         double ex = x - ax - dx * t, ez = z - az - dz * t;
         return Math.sqrt(ex * ex + ez * ez);
      }
   }

   private static <K, V> V cached(Cache<K, V> cache, K key, java.util.concurrent.Callable<V> loader) {
      try { return cache.get(key, loader); }
      catch (ExecutionException e) {
         if (e.getCause() instanceof RuntimeException runtime) throw runtime;
         throw new IllegalStateException("Unable to plan inland hydrology", e.getCause());
      } catch (com.google.common.util.concurrent.UncheckedExecutionException e) {
         if (e.getCause() instanceof RuntimeException runtime) throw runtime;
         throw e;
      }
   }

   private static void checkCancelled() {
      if (Thread.currentThread().isInterrupted()) throw new CancellationException("Inland water planning interrupted");
   }
}
