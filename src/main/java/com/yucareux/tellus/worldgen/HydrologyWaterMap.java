package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Fixed-size map queries keep clipping, map zoom and feature ordering independent of the consumer. */
final class HydrologyWaterMap {
   private static final int TILE = 128;
   private static final int TILE_MARGIN = 160;
   static final int WATERFALL_TILE = 512;
   // Bank searches widen to 4096 blocks at 1:1. The limit is a ground distance: at 1:30 the same
   // block count spans 123 km, and every failed search read full-zoom map tiles along that whole
   // transect, which kept a single plan waiting on thousands of downloads.
   private static final double BANK_SEARCH_METERS = 4096.0;
   private static final double MIN_BANK_SEARCH_BLOCKS = 64.0;
   // Half-width a mapped line contributes to hints and containment; drawn widths come from RiverScale.
   static final double MAX_LINE_HALF_WIDTH = 2.999;
   private final InlandWaterInput source;
   private final double scale;
   private final double bankSearchBlocks;
   private final Cache<Long, InputTile> tiles = CacheBuilder.newBuilder().maximumSize(2048).build();
   // Every column reads its input tile several times. Immutable recent tiles
   // are checked before the bounded cache, without taking its locks.
   private final AtomicReferenceArray<InputTile> recentTiles = new AtomicReferenceArray<>(64);
   private final Cache<Long, List<Shape>> waterfallTiles = CacheBuilder.newBuilder().maximumSize(512).build();
   // Neighboring input tiles often reference the same large polygon object.
   // Reuse its projected vertices rather than copying them into every tile.
   private final Cache<OsmWaterFeature, Shape> projected = CacheBuilder.newBuilder().maximumSize(4096).build();

   HydrologyWaterMap(InlandWaterInput source, double scale) {
      this.source = source;
      this.scale = scale;
      this.bankSearchBlocks = bankSearchBlocks(scale);
   }

   static double bankSearchBlocks(double scale) {
      double blocks = scale > 0.0 ? BANK_SEARCH_METERS / scale : 4096.0;
      return Math.max(MIN_BANK_SEARCH_BLOCKS, Math.min(4096.0, blocks));
   }

   Shape project(OsmWaterFeature feature) {
      try { return projected.get(feature, () -> new Shape(feature, scale)); }
      catch (ExecutionException error) { throw new IllegalStateException("Unable to project inland water geometry", error.getCause()); }
   }

   List<Shape> shapes(int x, int z) {
      return tile(x, z, OsmQueryMode.BLOCKING).water;
   }

   /** {@link #shapes} without loading map data on this thread: a missing tile loads in the background and reads as unavailable coverage until then. */
   List<Shape> shapesWithoutWaiting(int x, int z) {
      return tile(x, z, OsmQueryMode.NON_BLOCKING).water;
   }

   List<Shape> waterfalls(int x, int z) {
      int tx = Math.floorDiv(x, WATERFALL_TILE), tz = Math.floorDiv(z, WATERFALL_TILE);
      try {
         return waterfallTiles.get(HydrologyBlockCache.mix(key(tx, tz)), () -> {
            TellusOsmWaterSource.WaterQueryResult query = source.waterForAreaWithStatus(tx * WATERFALL_TILE, tz * WATERFALL_TILE,
               (tx + 1) * WATERFALL_TILE - 1, (tz + 1) * WATERFALL_TILE - 1, scale, 768, OsmQueryMode.BLOCKING);
            if (!query.complete()) throw new OceanCoverageUnavailableException(query.coverageStatus(), x, z);
            List<Shape> result = new ArrayList<>();
            for (OsmWaterFeature feature : query.features()) if (feature.waterfall()) result.add(project(feature));
            result.sort(Comparator.comparingLong(Shape::identity).thenComparingDouble(s -> s.minX).thenComparingDouble(s -> s.minZ));
            return List.copyOf(result);
         });
      } catch (ExecutionException error) { throw new IllegalStateException("Unable to read waterfall map", error.getCause()); }
   }

   private InputTile tile(int x, int z, OsmQueryMode mode) {
      int tx = Math.floorDiv(x, TILE);
      int tz = Math.floorDiv(z, TILE);
      long key = HydrologyBlockCache.mix(key(tx, tz));
      int slot = (int)(key >>> 58);
      InputTile recent = recentTiles.get(slot);
      if (recent != null && recent.tx == tx && recent.tz == tz) return recent;
      InputTile tile;
      try {
         tile = tiles.get(key, () -> {
            long started = HydrologyMetrics.now();
            TellusOsmWaterSource.WaterQueryResult query = source.waterForAreaWithStatus(
               tx * TILE, tz * TILE, (tx + 1) * TILE - 1, (tz + 1) * TILE - 1, scale, TILE_MARGIN, mode);
            if (!query.complete()) throw new OceanCoverageUnavailableException(query.coverageStatus(), x, z);
            List<Shape> shapes = new ArrayList<>();
            List<Shape> waterfalls = new ArrayList<>();
            for (OsmWaterFeature feature : query.features()) {
               if (feature.waterfall()) {
                  waterfalls.add(project(feature));
                  // Waterfall areas replace the river polygon over the drop in
                  // some datasets. Their footprint connects the pools; their
                  // outline supplies an annotation, not a second river line.
                  if (!feature.pointGeometry() && !feature.lineGeometry()) shapes.add(project(feature));
               } else if (!feature.oceanHint() && !feature.pointGeometry()) shapes.add(project(feature));
            }
            shapes.sort(Comparator.comparingLong((Shape s) -> s.feature.featureId())
               .thenComparingDouble(s -> s.minX).thenComparingDouble(s -> s.minZ));
            waterfalls.sort(Comparator.comparingLong(Shape::identity).thenComparingDouble(s -> s.minX).thenComparingDouble(s -> s.minZ));
            InputTile result = new InputTile(tx, tz, List.copyOf(shapes), List.copyOf(waterfalls));
            HydrologyMetrics.map(started);
            return result;
         });
      } catch (ExecutionException e) {
         throw new IllegalStateException("Unable to read inland water map", e.getCause());
      }
      recentTiles.set(slot, tile);
      return tile;
   }

   private record InputTile(int tx, int tz, List<Shape> water, List<Shape> waterfalls) {}

   /**
    * Fetches together the map inputs of every tile overlapping the area that is not loaded yet.
    * Reading them one by one afterwards then costs no network round trip each.
    */
   void preload(int minX, int minZ, int maxX, int maxZ) {
      List<int[]> areas = new ArrayList<>();
      for (int tz = Math.floorDiv(minZ, TILE); tz <= Math.floorDiv(maxZ, TILE); tz++)
         for (int tx = Math.floorDiv(minX, TILE); tx <= Math.floorDiv(maxX, TILE); tx++)
            if (tiles.getIfPresent(HydrologyBlockCache.mix(key(tx, tz))) == null)
               areas.add(new int[]{tx * TILE, tz * TILE, (tx + 1) * TILE - 1, (tz + 1) * TILE - 1});
      if (!areas.isEmpty()) source.preload(areas, scale, TILE_MARGIN);
   }

   boolean mayAffectArea(int minX, int minZ, int maxX, int maxZ, int margin) {
      for (int z = Math.floorDiv(minZ, TILE); z <= Math.floorDiv(maxZ, TILE); z++)
         for (int x = Math.floorDiv(minX, TILE); x <= Math.floorDiv(maxX, TILE); x++)
            for (Shape shape : shapes(x * TILE, z * TILE))
               if (shape.minX <= (double)maxX + margin && shape.maxX >= (double)minX - margin
                  && shape.minZ <= (double)maxZ + margin && shape.maxZ >= (double)minZ - margin) return true;
      return false;
   }

   boolean riverPolygon(double x, double z) {
      for (Shape shape : shapes((int)Math.floor(x), (int)Math.floor(z)))
         if (shape.river && !shape.feature.lineGeometry() && shape.contains(x, z)) return true;
      return false;
   }

   boolean polygonWidthEvidence(double x, double z) {
      double polygon = Double.POSITIVE_INFINITY, line = Double.POSITIVE_INFINITY;
      for (Shape shape : shapes((int)Math.floor(x), (int)Math.floor(z))) {
         if (!shape.river) continue;
         double distance = shape.contains(x, z) ? 0.0 : shape.distance(x, z, 96.0);
         if (shape.feature.lineGeometry()) line = Math.min(line, distance);
         else polygon = Math.min(polygon, distance);
      }
      // A separate nearby river polygon is not the width of a line-only
      // stream running beside it. Polygons take priority when they contain it.
      return Double.isFinite(polygon) && polygon <= line;
   }

   boolean island(int x, int z) {
      return island(shapes(x, z), x, z);
   }

   /** {@link #island(int, int)} for a caller that already holds this column's tile shapes. */
   static boolean island(List<Shape> shapes, int x, int z) {
      boolean island = false;
      for (Shape shape : shapes) {
         if (shape.feature.lineGeometry() || shape.feature.pointGeometry()) continue;
         // One ring count decides both containment (odd) and a hole (even).
         int rings = shape.ringsContaining(x, z);
         if ((rings & 1) != 0) return false;
         island |= rings > 0;
      }
      return island;
   }

   /** Intersections of a cross-river transect with actual vector banks, including clipped fragments. */
   Banks banks(double x, double z, double nx, double nz, double expectedHalfWidth) {
      if (!polygonWidthEvidence(x, z)) return null;
      expectedHalfWidth = Double.isFinite(expectedHalfWidth) ? Math.max(0.6, expectedHalfWidth) : bankSearchBlocks;
      double radius = Math.max(32.0, Math.min(4096.0, expectedHalfWidth * 3.0 + 16.0));
      // The first search always spans the expected width, even past the ground limit.
      double limit = Math.max(radius, bankSearchBlocks);
      while (true) {
         Banks banks = banksWithin(x, z, nx, nz, expectedHalfWidth, radius);
         if (banks != null || radius >= limit) return banks;
         // A relocated center can be close to one bank of a wide river.
         // Increase the search until both vector banks are available; absence
         // of the far bank in the first search is not evidence of a tiny stream.
         radius = Math.min(limit, radius * 2.0);
      }
   }

   private Banks banksWithin(double x, double z, double nx, double nz, double expectedHalfWidth, double radius) {
      double[] interval = waterInterval(x, z, nx, nz, radius);
      if (interval == null) return null;
      double low = interval[0], high = interval[1], best = interval[2];
      if (high - low < 0.5 || best > Math.min(64.0, expectedHalfWidth + 16.0)) return null;
      // Preserve bank asymmetry in-map.
      if (low <= 0.0 && high >= 0.0) return new Banks(Math.max(0.35, -low), Math.max(0.35, high));
      double half = (high - low) * 0.5;
      // A center relocated well away from the mapped water keeps the measured width, rather than
      // using its displacement as extra channel width. A center just beside a wide river (a route
      // leaving it for a tributary, a seam crossing moved onto low ground) reaches across to the
      // far bank instead: centering the river's width on it put half of that width on the dry side,
      // a block of water standing out from the shore.
      if (best > half) return new Banks(half, half);
      return low > 0.0 ? new Banks(0.35, high) : new Banks(-low, 0.35);
   }

   /**
    * Length of the mapped river polygon's interval through the point along a direction, searched within
    * {@code radius}; infinite when the point is not inside such an interval or it does not close within
    * the radius. A route crossing a wide river measures the river's length on its transect, while its
    * own direction spans only the river's width.
    */
   double polygonChord(double x, double z, double dx, double dz, double radius) {
      double[] interval = waterInterval(x, z, dx, dz, radius);
      return interval == null || interval[0] > 0.0 || interval[1] < 0.0 ? Double.POSITIVE_INFINITY : interval[1] - interval[0];
   }

   /** The water interval {low, high, gap} along the transect nearest its center (adjoining fragments merged), or null. */
   private double[] waterInterval(double x, double z, double nx, double nz, double radius) {
      LinkedHashSet<Shape> nearby = new LinkedHashSet<>();
      // Input queries overlap by 160 blocks. Sampling along the transect also
      // finds the other fragment of a river clipped at a vector-tile boundary.
      int steps = Math.max(1, (int)Math.ceil(radius / 128.0));
      for (int k = -steps; k <= steps; k++) {
         double d = k * radius / steps;
         for (Shape shape : shapes((int)Math.round(x + nx * d), (int)Math.round(z + nz * d)))
            if (shape.river && !shape.feature.lineGeometry()) nearby.add(shape);
      }
      List<Double> hits = new ArrayList<>();
      for (Shape shape : nearby) for (int p = 0; p < shape.xs.length; p++)
         for (int i = 1; i < shape.xs[p].length; i++) {
            double ax = shape.xs[p][i - 1] - x, az = shape.zs[p][i - 1] - z;
            double dx = shape.xs[p][i] - shape.xs[p][i - 1], dz = shape.zs[p][i] - shape.zs[p][i - 1];
            double denominator = nx * dz - nz * dx;
            if (Math.abs(denominator) < 1.0E-9) continue;
            double t = (ax * nz - az * nx) / denominator;
            double d = (ax * dz - az * dx) / denominator;
            if (t >= 0.0 && t <= 1.0 && Math.abs(d) <= radius) hits.add(d);
         }
      hits.sort(Double::compare);
      double last = Double.NaN, low = Double.NaN, high = Double.NaN, best = Double.POSITIVE_INFINITY;
      for (double d : hits) {
         if (Double.isFinite(last) && d - last > 0.05) {
            double mid = (last + d) * 0.5;
            boolean inside = false;
            for (Shape shape : nearby) if (shape.contains(x + nx * mid, z + nz * mid)) { inside = true; break; }
            if (inside) {
               if (Double.isFinite(high) && Math.abs(high - last) < 0.1) { high = d; best = Math.max(low, Math.max(-high, 0.0)); }
               else {
                  double gap = Math.max(last, Math.max(-d, 0.0));
                  if (gap < best) { low = last; high = d; best = gap; }
               }
            }
         }
         last = d;
      }
      return Double.isFinite(low) ? new double[]{low, high, best} : null;
   }

   record Banks(double left, double right) {}

   Hint hint(int x, int z, double footprintRadius) {
      return hint(shapes(x, z), x, z, footprintRadius);
   }

   /** {@link #hint(int, int, double)} for a caller that already holds this column's tile shapes. */
   static Hint hint(List<Shape> shapes, int x, int z, double footprintRadius) {
      Hint result = Hint.DRY;
      for (Shape shape : shapes) {
         double radius = footprintRadius + (shape.feature.lineGeometry() ? shape.lineWidth : 0.0);
         // Outside the expanded bounds neither containment nor distance can match.
         if (footprintRadius >= 0.0 && !shape.near(x, z, radius)) continue;
         boolean inside = shape.contains(x, z);
         if (!inside && !shape.within(x, z, radius)) continue;
         if (!shape.river) return new Hint(false, shape.engineered, shape, inside, 0.0);
         // Polygon width is measured from both banks later. A line is a hint
         // with a physical channel width, not a one-block water mask.
         if (!result.river || !shape.feature.lineGeometry())
            result = new Hint(true, shape.engineered, shape, inside, shape.feature.lineGeometry() ? shape.lineWidth : 0.0);
      }
      return result;
   }

   static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

   record Hint(boolean river, boolean engineered, Shape shape, boolean inside, double lineWidth) {
      static final Hint DRY = new Hint(false, false, null, false, 0.0);
      boolean wet() { return shape != null; }
      boolean lake() { return wet() && !river; }
   }

   /** Receives the open segments of a shape which may touch a queried box. */
   interface SegmentVisitor {
      void visit(double ax, double az, double bx, double bz);
   }

   static final class Shape {
      // Shapes with fewer segments are cheaper to scan than to index.
      private static final int INDEXED_SEGMENTS = 24;
      final OsmWaterFeature feature;
      final double[][] xs;
      final double[][] zs;
      final boolean river;
      final boolean engineered;
      final double lineWidth;
      final double minX, minZ, maxX, maxZ;
      private final SegmentGrid segments;
      private final RingBands rings;

      Shape(OsmWaterFeature feature, double scale) {
         this.feature = feature;
         OsmWaterKind kind = feature.kind();
         this.engineered = kind == OsmWaterKind.CANAL || kind == OsmWaterKind.DITCH || kind == OsmWaterKind.DRAIN
            || kind == OsmWaterKind.RESERVOIR || kind == OsmWaterKind.BASIN;
         double widthMeters = Double.isFinite(feature.widthMeters()) ? feature.widthMeters()
            : kind == OsmWaterKind.RIVER ? 6.0 : kind == OsmWaterKind.CANAL ? 4.0 : 2.5;
         double centerZ = EarthProjection.latToBlockZ((feature.minLat() + feature.maxLat()) * 0.5, scale);
         this.lineWidth = Math.max(0.6, Math.min(MAX_LINE_HALF_WIDTH,
            widthMeters / Math.max(0.01, EarthProjection.groundMetersPerBlockX(centerZ, scale)) * 0.5));
         this.xs = new double[feature.partCount()][];
         this.zs = new double[feature.partCount()][];
         double lowX = Double.POSITIVE_INFINITY, lowZ = lowX, highX = -lowX, highZ = -lowX;
         for (int p = 0; p < xs.length; p++) {
            xs[p] = new double[feature.pointCount(p)];
            zs[p] = new double[xs[p].length];
            for (int i = 0; i < xs[p].length; i++) {
               xs[p][i] = feature.lonAt(p, i) * EarthProjection.blocksPerDegree(scale);
               zs[p][i] = EarthProjection.latToBlockZ(feature.latAt(p, i), scale);
               lowX = Math.min(lowX, xs[p][i]); highX = Math.max(highX, xs[p][i]);
               lowZ = Math.min(lowZ, zs[p][i]); highZ = Math.max(highZ, zs[p][i]);
            }
         }
         minX = lowX; maxX = highX; minZ = lowZ; maxZ = highZ;
         double longAxis = Math.max(maxX - minX, maxZ - minZ);
         double shortAxis = Math.max(1.0, Math.min(maxX - minX, maxZ - minZ));
         // Keep explicit lake/reservoir tags authoritative. Thin, long untyped
         // water polygons still provide river evidence when line tags are absent.
         this.river = feature.flowingWater() || feature.waterfall() && !feature.pointGeometry() && !feature.lineGeometry()
            || kind == OsmWaterKind.UNKNOWN && (thin(longAxis, shortAxis, scale) || thinOutline(scale));
         int segmentCount = 0;
         for (double[] part : xs) segmentCount += Math.max(0, part.length - 1);
         boolean indexed = !feature.pointGeometry() && segmentCount >= INDEXED_SEGMENTS;
         this.segments = indexed ? new SegmentGrid(this, segmentCount) : null;
         this.rings = indexed && !feature.lineGeometry() ? new RingBands(this, segmentCount) : null;
      }

      private static boolean thin(double length, double width, double scale) {
         return length >= Math.max(64.0, 750.0 / scale) && width <= Math.max(12.0, 400.0 / scale) && length / width >= 3.5;
      }

      /**
       * The same test on the rectangle with the outline's own area and perimeter, independent of
       * orientation. Judged by its bounding box alone, a diagonal river polygon looked like a lake
       * and was flooded at one level: on raised ground at its lower end and cut into the valley at
       * its upper end. Compact outlines (a disc, a square) have no such rectangle.
       */
      private boolean thinOutline(double scale) {
         double area = area(), perimeter = 0.0;
         if (area <= 0.0) return false;
         for (int p = 0; p < xs.length; p++)
            for (int i = 0, j = xs[p].length - 1; i < xs[p].length; j = i++) perimeter += Math.hypot(xs[p][i] - xs[p][j], zs[p][i] - zs[p][j]);
         double half = perimeter * 0.25, spread = half * half - area;
         if (spread <= 0.0) return false;
         double root = Math.sqrt(spread);
         return thin(half + root, Math.max(1.0, half - root), scale);
      }

      /** Enclosed area in square blocks (rings combined by orientation); zero for lines and points. */
      double area() {
         if (feature.lineGeometry() || feature.pointGeometry()) return 0.0;
         double twice = 0.0;
         for (int p = 0; p < xs.length; p++)
            for (int i = 0, j = xs[p].length - 1; i < xs[p].length; j = i++) twice += xs[p][j] * zs[p][i] - xs[p][i] * zs[p][j];
         return Math.abs(twice) * 0.5;
      }

      // Typical width, computed on first use; a benign race computes the same value.
      private volatile double width = -1.0;

      /**
       * Typical width of the outline in blocks: twice its area over its perimeter (a long strip's own width, a disc's
       * radius); zero for lines and points.
       */
      double width() {
         double known = width;
         if (known >= 0.0) return known;
         double perimeter = 0.0;
         if (!feature.lineGeometry() && !feature.pointGeometry())
            for (int p = 0; p < xs.length; p++)
               for (int i = 0, j = xs[p].length - 1; i < xs[p].length; j = i++) perimeter += Math.hypot(xs[p][i] - xs[p][j], zs[p][i] - zs[p][j]);
         width = known = perimeter > 0.0 ? 2.0 * area() / perimeter : 0.0;
         return known;
      }

      long identity() {
         if (feature.featureId() != -1L) return feature.featureId();
         long hash = Double.doubleToLongBits(minX) ^ Long.rotateLeft(Double.doubleToLongBits(minZ), 17);
         hash ^= Long.rotateLeft(Double.doubleToLongBits(maxX), 31) ^ Double.doubleToLongBits(maxZ);
         return hash ^ 0xA110C0A7E123L;
      }

      /** Whether the point lies within the bounds expanded by {@code margin}. */
      boolean near(double x, double z, double margin) {
         return x >= minX - margin && x <= maxX + margin && z >= minZ - margin && z <= maxZ + margin;
      }

      boolean contains(double x, double z) {
         if (feature.pointGeometry()) return false;
         if (feature.lineGeometry()) return within(x, z, lineWidth);
         return (ringsContaining(x, z) & 1) != 0;
      }

      boolean island(double x, double z) {
         if (feature.lineGeometry() || feature.pointGeometry()) return false;
         int rings = ringsContaining(x, z);
         return rings > 0 && (rings & 1) == 0;
      }

      int ringsContaining(double x, double z) {
         if (x < minX || z < minZ || x > maxX || z > maxZ) return 0;
         if (this.rings != null) return this.rings.count(this, x, z);
         int rings = 0;
         for (int p = 0; p < xs.length; p++) {
            boolean inside = false;
            for (int i = 0, j = xs[p].length - 1; i < xs[p].length; j = i++) {
               if ((zs[p][i] > z) != (zs[p][j] > z)
                  && x < (xs[p][j] - xs[p][i]) * (z - zs[p][i]) / (zs[p][j] - zs[p][i]) + xs[p][i]) inside = !inside;
            }
            if (inside) rings++;
         }
         return rings;
      }

      /**
       * Nearest distance among the segments whose bounds, expanded by
       * {@code maximum}, contain the point; infinity when none qualify.
       */
      double distance(double x, double z, double maximum) {
         if (x < minX - maximum || x > maxX + maximum || z < minZ - maximum || z > maxZ + maximum)
            return Double.POSITIVE_INFINITY;
         double best = Double.POSITIVE_INFINITY;
         if (feature.pointGeometry()) {
            for (int p = 0; p < xs.length; p++) for (int i = 0; i < xs[p].length; i++)
               best = Math.min(best, Math.hypot(x - xs[p][i], z - zs[p][i]));
            return best;
         }
         if (this.segments != null) return this.segments.distance(x, z, maximum);
         double prune = Double.POSITIVE_INFINITY;
         for (int p = 0; p < xs.length; p++) {
            for (int i = 1; i < xs[p].length; i++) {
               double ax = xs[p][i - 1], az = zs[p][i - 1], bx = xs[p][i], bz = zs[p][i];
               if (x < Math.min(ax, bx) - maximum || x > Math.max(ax, bx) + maximum
                  || z < Math.min(az, bz) - maximum || z > Math.max(az, bz) + maximum) continue;
               double dx = bx - ax, dz = bz - az;
               double t = Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / Math.max(1.0E-12, dx * dx + dz * dz)));
               double ex = x - ax - t * dx, ez = z - az - t * dz;
               if (ex * ex + ez * ez > prune) continue;
               best = Math.min(best, Math.hypot(ex, ez));
               prune = pruneBound(best);
            }
         }
         return best;
      }

      /** Exactly {@code distance(x, z, maximum) <= maximum}, stopping at the first qualifying segment. */
      boolean within(double x, double z, double maximum) {
         if (x < minX - maximum || x > maxX + maximum || z < minZ - maximum || z > maxZ + maximum) return false;
         if (feature.pointGeometry()) return distance(x, z, maximum) <= maximum;
         double limit = maximum * maximum;
         if (this.segments != null) return this.segments.within(x, z, maximum, limit);
         for (int p = 0; p < xs.length; p++) {
            for (int i = 1; i < xs[p].length; i++) {
               double ax = xs[p][i - 1], az = zs[p][i - 1], bx = xs[p][i], bz = zs[p][i];
               if (x < Math.min(ax, bx) - maximum || x > Math.max(ax, bx) + maximum
                  || z < Math.min(az, bz) - maximum || z > Math.max(az, bz) + maximum) continue;
               double dx = bx - ax, dz = bz - az;
               double t = Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / Math.max(1.0E-12, dx * dx + dz * dz)));
               if (withinSegment(x - ax - t * dx, z - az - t * dz, maximum, limit)) return true;
            }
         }
         return false;
      }

      /**
       * The point of the outline nearest to a position, among segments within {@code maximum} of it, as
       * {x, z}; null when none is that close.
       */
      double[] nearestPoint(double x, double z, double maximum) {
         if (feature.pointGeometry() || x < minX - maximum || x > maxX + maximum || z < minZ - maximum || z > maxZ + maximum) return null;
         double[] best = new double[3];
         best[2] = maximum * maximum;
         boolean[] found = {false};
         segments(x - maximum, z - maximum, x + maximum, z + maximum, (ax, az, bx, bz) -> {
            double dx = bx - ax, dz = bz - az;
            double t = Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / Math.max(1.0E-12, dx * dx + dz * dz)));
            double px = ax + t * dx, pz = az + t * dz, ex = x - px, ez = z - pz, squared = ex * ex + ez * ez;
            if (squared <= best[2]) { best[0] = px; best[1] = pz; best[2] = squared; found[0] = true; }
         });
         return found[0] ? new double[]{best[0], best[1]} : null;
      }

      /** Visits every open segment which may intersect the box; a segment can be visited more than once. */
      void segments(double minX, double minZ, double maxX, double maxZ, SegmentVisitor visitor) {
         // Padded like the grid lookup: box clipping can accept a segment
         // that misses the box by a rounding error.
         if (maxX + SegmentGrid.PAD < this.minX || minX - SegmentGrid.PAD > this.maxX
            || maxZ + SegmentGrid.PAD < this.minZ || minZ - SegmentGrid.PAD > this.maxZ) return;
         if (this.segments != null) { this.segments.visit(minX, minZ, maxX, maxZ, visitor); return; }
         for (int p = 0; p < xs.length; p++)
            for (int i = 1; i < xs[p].length; i++) visitor.visit(xs[p][i - 1], zs[p][i - 1], xs[p][i], zs[p][i]);
      }
   }

   /**
    * Squared distances decide clear cases. Rounding only matters within a
    * relative 1e-9 of the limit, where the exact {@code hypot} comparison of
    * the linear scan is used.
    */
   static boolean withinSegment(double ex, double ez, double maximum, double limit) {
      double squared = ex * ex + ez * ez;
      if (squared < limit * (1.0 - 1.0E-9)) return true;
      if (squared > limit * (1.0 + 1.0E-9)) return false;
      return Math.hypot(ex, ez) <= maximum;
   }

   /** A squared distance above this bound cannot produce a {@code hypot} below {@code best}. */
   static double pruneBound(double best) {
      return best * best * (1.0 + 1.0E-9);
   }

   /** Open segments bucketed on a uniform grid over the shape's bounds. */
   private static final class SegmentGrid {
      private final double[] ax, az, bx, bz;
      private final double originX, originZ, cell;
      private final int columns, rows;
      private final int[] start, members;

      SegmentGrid(Shape shape, int count) {
         ax = new double[count]; az = new double[count]; bx = new double[count]; bz = new double[count];
         int s = 0;
         for (int p = 0; p < shape.xs.length; p++)
            for (int i = 1; i < shape.xs[p].length; i++) {
               ax[s] = shape.xs[p][i - 1]; az[s] = shape.zs[p][i - 1]; bx[s] = shape.xs[p][i]; bz[s] = shape.zs[p][i];
               s++;
            }
         originX = shape.minX; originZ = shape.minZ;
         cell = Math.max(8.0, Math.max(shape.maxX - shape.minX, shape.maxZ - shape.minZ) / 64.0);
         columns = (int)((shape.maxX - shape.minX) / cell) + 1;
         rows = (int)((shape.maxZ - shape.minZ) / cell) + 1;
         start = new int[columns * rows + 1];
         for (int k = 0; k < count; k++) forCells(k, c -> start[c + 1]++);
         for (int c = 0; c < columns * rows; c++) start[c + 1] += start[c];
         members = new int[start[columns * rows]];
         int[] fill = java.util.Arrays.copyOf(start, columns * rows);
         for (int k = 0; k < count; k++) { int segment = k; forCells(k, c -> members[fill[c]++] = segment); }
      }

      private interface CellAction { void apply(int cell); }

      private void forCells(int k, CellAction action) {
         int x0 = column(Math.min(ax[k], bx[k])), x1 = column(Math.max(ax[k], bx[k]));
         int z0 = row(Math.min(az[k], bz[k])), z1 = row(Math.max(az[k], bz[k]));
         for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) action.apply(z * columns + x);
      }

      private int column(double x) { return Math.max(0, Math.min(columns - 1, (int)((x - originX) / cell))); }
      private int row(double z) { return Math.max(0, Math.min(rows - 1, (int)((z - originZ) / cell))); }

      // Cells are selected with a small pad, so rounding at a cell boundary can
      // only add candidates; each candidate still applies the exact filter.
      private static final double PAD = 1.0E-3;

      double distance(double x, double z, double maximum) {
         double best = Double.POSITIVE_INFINITY, prune = Double.POSITIVE_INFINITY;
         double reach = maximum + PAD;
         int x0 = column(x - reach), x1 = column(x + reach), z1 = row(z + reach);
         for (int row = row(z - reach); row <= z1; row++)
            for (int c = row * columns + x0, end = row * columns + x1; c <= end; c++)
               for (int m = start[c]; m < start[c + 1]; m++) {
                  int k = members[m];
                  double sax = ax[k], saz = az[k], sbx = bx[k], sbz = bz[k];
                  if (x < Math.min(sax, sbx) - maximum || x > Math.max(sax, sbx) + maximum
                     || z < Math.min(saz, sbz) - maximum || z > Math.max(saz, sbz) + maximum) continue;
                  double dx = sbx - sax, dz = sbz - saz;
                  double t = Math.max(0.0, Math.min(1.0, ((x - sax) * dx + (z - saz) * dz) / Math.max(1.0E-12, dx * dx + dz * dz)));
                  double ex = x - sax - t * dx, ez = z - saz - t * dz;
                  if (ex * ex + ez * ez > prune) continue;
                  best = Math.min(best, Math.hypot(ex, ez));
                  prune = pruneBound(best);
               }
         return best;
      }

      boolean within(double x, double z, double maximum, double limit) {
         double reach = maximum + PAD;
         int x0 = column(x - reach), x1 = column(x + reach), z1 = row(z + reach);
         for (int row = row(z - reach); row <= z1; row++)
            for (int c = row * columns + x0, end = row * columns + x1; c <= end; c++)
               for (int m = start[c]; m < start[c + 1]; m++) {
                  int k = members[m];
                  double sax = ax[k], saz = az[k], sbx = bx[k], sbz = bz[k];
                  if (x < Math.min(sax, sbx) - maximum || x > Math.max(sax, sbx) + maximum
                     || z < Math.min(saz, sbz) - maximum || z > Math.max(saz, sbz) + maximum) continue;
                  double dx = sbx - sax, dz = sbz - saz;
                  double t = Math.max(0.0, Math.min(1.0, ((x - sax) * dx + (z - saz) * dz) / Math.max(1.0E-12, dx * dx + dz * dz)));
                  if (withinSegment(x - sax - t * dx, z - saz - t * dz, maximum, limit)) return true;
               }
         return false;
      }

      void visit(double minX, double minZ, double maxX, double maxZ, SegmentVisitor visitor) {
         int x0 = column(minX - PAD), x1 = column(maxX + PAD), z1 = row(maxZ + PAD);
         for (int row = row(minZ - PAD); row <= z1; row++)
            for (int c = row * columns + x0, end = row * columns + x1; c <= end; c++)
               for (int m = start[c]; m < start[c + 1]; m++) {
                  int k = members[m];
                  visitor.visit(ax[k], az[k], bx[k], bz[k]);
               }
      }
   }

   /**
    * Ring edges (including each closing edge) bucketed into horizontal bands.
    * Only edges spanning the query's z can toggle the even-odd test, so each
    * band reproduces the full ray cast exactly. Entries are grouped by part.
    */
   private static final class RingBands {
      private final double origin, height;
      private final int bands;
      private final int[] start, parts, edges;

      RingBands(Shape shape, int segmentCount) {
         origin = shape.minZ;
         double span = shape.maxZ - shape.minZ;
         bands = span > 0.0 ? Math.max(1, Math.min(4096, segmentCount / 2)) : 1;
         height = span > 0.0 ? span / bands : 1.0;
         start = new int[bands + 1];
         forEachEdge(shape, (band, part, edge) -> start[band + 1]++);
         for (int b = 0; b < bands; b++) start[b + 1] += start[b];
         parts = new int[start[bands]];
         edges = new int[start[bands]];
         int[] fill = java.util.Arrays.copyOf(start, bands);
         forEachEdge(shape, (band, part, edge) -> { int slot = fill[band]++; parts[slot] = part; edges[slot] = edge; });
      }

      private interface EdgeAction { void apply(int band, int part, int edge); }

      private void forEachEdge(Shape shape, EdgeAction action) {
         for (int p = 0; p < shape.xs.length; p++) {
            double[] z = shape.zs[p];
            for (int i = 0, j = z.length - 1; i < z.length; j = i++) {
               int last = band(Math.max(z[i], z[j]));
               for (int b = band(Math.min(z[i], z[j])); b <= last; b++) action.apply(b, p, i);
            }
         }
      }

      private int band(double z) { return Math.max(0, Math.min(bands - 1, (int)((z - origin) / height))); }

      int count(Shape shape, double x, double z) {
         int b = band(z), rings = 0, part = -1;
         boolean inside = false;
         for (int m = start[b]; m < start[b + 1]; m++) {
            int p = parts[m], i = edges[m];
            if (p != part) { if (inside) rings++; part = p; inside = false; }
            double[] xs = shape.xs[p], zs = shape.zs[p];
            int j = i == 0 ? xs.length - 1 : i - 1;
            if ((zs[i] > z) != (zs[j] > z)
               && x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i]) inside = !inside;
         }
         return inside ? rings + 1 : rings;
      }
   }
}
