package com.yucareux.tellus.worldgen;

import java.util.Arrays;
import java.util.List;

/**
 * One piece of a drawn waterway inside the bounds of the map tile its geometry came from, with its course on
 * the ground and its water level block by block along it.
 *
 * <p>Map lines are often drawn beside the valley floor the terrain shows; the channel then cut a trench into the
 * valley wall next to it. A natural waterway runs where the terrain does, on the lowest ground of a corridor around
 * its line ({@link RiverCorridor}), from and to the ends its tile anchors: the point where pieces of adjacent map
 * tiles (or the ways of one river, or several waterways) meet, a point on the course of the river it joins, or its
 * mapped end at a lake, the sea or a river area (moved where the area moved). So pieces keep meeting wherever they
 * move. The waterway drawn in a narrow river area carries the area along its course ({@link AreaSurface}).</p>
 *
 * <p>The network gives a monotone level from terrain observed every network cell at a moderate resolution.
 * Along the course the level also stays under the native terrain: a narrow gorge or a channel finer than that
 * resolution lowers it, and it then follows the lowest native terrain of the last stretch upstream (a short
 * window, after filling dips of a few blocks). Nor does it drop faster than that terrain: the network's coarse steps
 * only hold it where the terrain rises. The water never rises downstream: where that window would let it
 * climb again (the far end of a long dip in the terrain model) the level is the nearest one that only falls, so a
 * dip becomes a pool and a rise is cut through, whichever moves less water. The pieces upstream of a piece hold
 * its level at most at theirs where they meet it. A fall follows the terrain down its drop (the network's level holds
 * above it, rather than sinking the water into a trench before the lip): where the terrain model
 * blurs a cliff into a slope the water runs down that slope, and only where the level drops by two or more blocks
 * within a block (a cliff in the terrain itself) does it fall as a curtain. Only where the ground stands very far above the
 * water for a long stretch (a mapped line across a ridge) does the waterway run dry rather than through a deep
 * gorge.</p>
 */
final class RiverRun {
   /** Upstream window of the native-terrain level, blocks. */
   private static final double FINE_WINDOW = 96.0;
   /** Dips of the native terrain up to about twice this many blocks are filled first. */
   private static final int FINE_CLOSE = 3;
   /** A drop within one step of at least this many blocks is a waterfall. */
   private static final double FALL_DROP = 2.0;
   /** Spacing of the corridor's stations, samples, and of the drawn polyline's vertices. */
   private static final int SNAP_STRIDE = 4;
   /** Most samples per piece: very long pieces (large worlds) are sampled more sparsely. */
   private static final int MAX_SAMPLES = 16384;
   /** How far an anchored end may lengthen its piece towards the anchor, blocks. */
   private static final double MAX_REACH = 2.0 * RiverCorridor.MAX_RADIUS + 16.0;
   /** How far a course over a cliff (a fall) may move from its mapped line into the notch it has cut, ground metres. */
   private static final double FALL_METERS = 24.0;
   /** Excess of the ground over the water, ground metres, beyond which a long stretch runs dry. */
   private static final double DRY_EXCESS_METERS = 32.0;
   /** Shortest dry stretch, ground metres: shorter ridges, spurs and gorges the terrain model fills are cut through. */
   private static final double DRY_LENGTH_METERS = 64.0;
   /** Span of the chord whose normal is the direction across the course, blocks. */
   private static final double NORMAL_SPAN = 8.0;

   final RiverNetwork.Line line;
   /** The drawn course, and the position of each vertex along the mapped line (the levels' arc parameter). */
   final double[] xs, zs, arc;
   final double minX, minZ, maxX, maxZ;
   /** Sample spacing along the line and the level at each sample. */
   private final double step;
   private final float[] level;
   /** Samples where the waterway runs dry, or null when it never does. */
   private final java.util.BitSet dry;
   /** Whether drops of the level are waterfalls (natural channels profiled on native terrain). */
   private final boolean falls;
   /** Waterfalls: the sample index just above each lip, and the levels above and below. */
   final int[] fallIndex;
   final float[] fallUpper, fallLower;

   private RiverRun(RiverNetwork.Line line, double[] xs, double[] zs, double[] arc, double step, float[] level,
      java.util.BitSet dry, boolean falls) {
      this.line = line; this.xs = xs; this.zs = zs; this.arc = arc;
      double lowX = Double.POSITIVE_INFINITY, lowZ = lowX, highX = -lowX, highZ = -lowX;
      for (int i = 0; i < xs.length; i++) {
         lowX = Math.min(lowX, xs[i]); highX = Math.max(highX, xs[i]); lowZ = Math.min(lowZ, zs[i]); highZ = Math.max(highZ, zs[i]);
      }
      this.minX = lowX; this.minZ = lowZ; this.maxX = highX; this.maxZ = highZ;
      this.step = step; this.level = level; this.dry = dry; this.falls = falls;
      int count = 0;
      if (falls) for (int k = 0; k + 1 < level.length; k++) if (level[k] - level[k + 1] >= FALL_DROP) count++;
      this.fallIndex = new int[count];
      this.fallUpper = new float[count];
      this.fallLower = new float[count];
      count = 0;
      if (falls) for (int k = 0; k + 1 < level.length; k++) {
         if (level[k] - level[k + 1] < FALL_DROP) continue;
         fallIndex[count] = k; fallUpper[count] = level[k]; fallLower[count] = level[k + 1]; count++;
      }
   }

   double length() { return arc[arc.length - 1]; }

   /** The level where the piece ends. */
   double endLevel() { return level[level.length - 1]; }

   /** This piece with its water held at or below {@code cap}: the level of the water arriving from upstream. */
   RiverRun capped(double cap) {
      boolean above = false;
      for (float value : level) if (value > cap) { above = true; break; }
      if (!above) return this;
      float[] lowered = new float[level.length];
      for (int k = 0; k < level.length; k++) lowered[k] = (float)Math.min(level[k], cap);
      return new RiverRun(line, xs, zs, arc, step, lowered, dry, falls);
   }

   /** The pieces of a line inside its map tile's bounds, as {xs, zs} polylines, in drawing order. */
   static List<double[][]> pieces(RiverNetwork.Line line) {
      List<double[][]> out = new java.util.ArrayList<>();
      java.util.ArrayList<double[]> points = new java.util.ArrayList<>();
      for (int p = 0; p < line.shape().xs.length; p++) {
         double[] xs = line.shape().xs[p], zs = line.shape().zs[p];
         for (int i = 1; i < xs.length; i++) {
            double ax = xs[i - 1], az = zs[i - 1], bx = xs[i], bz = zs[i];
            double[] clipped = clip(ax, az, bx, bz, line.clipMinX(), line.clipMinZ(), line.clipMaxX(), line.clipMaxZ());
            if (clipped == null) { flush(points, out); continue; }
            double sx = ax + (bx - ax) * clipped[0], sz = az + (bz - az) * clipped[0];
            double ex = ax + (bx - ax) * clipped[1], ez = az + (bz - az) * clipped[1];
            if (clipped[0] > 0.0) flush(points, out);
            if (points.isEmpty()) points.add(new double[]{sx, sz});
            points.add(new double[]{ex, ez});
            if (clipped[1] < 1.0) flush(points, out);
         }
         flush(points, out);
      }
      return out;
   }

   private static void flush(List<double[]> points, List<double[][]> out) {
      if (points.size() >= 2) {
         double[] xs = new double[points.size()], zs = new double[points.size()];
         for (int i = 0; i < xs.length; i++) { xs[i] = points.get(i)[0]; zs[i] = points.get(i)[1]; }
         out.add(new double[][]{xs, zs});
      }
      points.clear();
   }

   static double[] clip(double ax, double az, double bx, double bz, double minX, double minZ, double maxX, double maxZ) {
      double dx = bx - ax, dz = bz - az, t0 = 0.0, t1 = 1.0;
      double[] p = {-dx, dx, -dz, dz}, q = {ax - minX, maxX - ax, az - minZ, maxZ - az};
      for (int k = 0; k < 4; k++) {
         if (p[k] == 0.0) { if (q[k] < 0.0) return null; }
         else {
            double r = q[k] / p[k];
            if (p[k] < 0.0) { if (r > t1) return null; if (r > t0) t0 = r; }
            else { if (r < t0) return null; if (r < t1) t1 = r; }
         }
      }
      return t1 - t0 > 0.0 ? new double[]{t0, t1} : null;
   }

   /** About a sample per block along a piece of {@code length} blocks; very long pieces are sampled more sparsely. */
   static int sampleCount(double length) {
      return (int)Math.min(MAX_SAMPLES, Math.max(2, Math.ceil(length) + 1));
   }

   /** Whether a piece of {@code length} blocks is sampled finely enough to follow the terrain (and have falls). */
   static boolean fine(double length) {
      return length / (sampleCount(length) - 1) <= 2.0;
   }

   /**
    * A piece's course: samples {@code step} apart along its mapped line, the direction across it at each and the
    * course's offset along that direction (null: on the line), the network's levels along the line (knots), and the
    * drawn polyline: its vertices, their arc positions along the mapped line and the mapped points they moved from.
    */
   record Course(RiverNetwork.Line line, double[] knotS, double[] knotL, double[] px, double[] pz, double[] nx, double[] nz,
      double[] offset, double step, double total, double mpb, double[] xs, double[] zs, double[] arc, double[] mappedX, double[] mappedZ) {}

   /**
    * Where one piece (from {@link #pieces}) runs; null when no network level reaches it. It keeps to its mapped line,
    * or with a corridor {@code radius} above zero runs through it from the {@code start} and {@code end} points its tile
    * anchors (null where an end is free). Reads no levels of river areas: a narrow area moves with the course of the
    * waterway in it, before any of its levels are read.
    */
   static Course course(RiverNetwork.Line line, double[] xs, double[] zs, RiverNetwork.Tile tile,
      RiverChannels.Terrain terrain, RiverScale scale, int radius, double[] start, double[] end) {
      if (radius > 0 && start != null) { double[][] moved = toward(xs, zs, start, true); xs = moved[0]; zs = moved[1]; }
      if (radius > 0 && end != null) { double[][] moved = toward(xs, zs, end, false); xs = moved[0]; zs = moved[1]; }
      double[][] knots = knots(xs, zs, tile);
      if (knots[0].length == 0) return null;
      double[] arc = new double[xs.length];
      for (int i = 1; i < xs.length; i++) arc[i] = arc[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
      double total = arc[xs.length - 1];
      int samples = sampleCount(total);
      double step = total / (samples - 1);
      double[] px = new double[samples], pz = new double[samples], nx = new double[samples], nz = new double[samples];
      int segment = 0;
      for (int k = 0; k < samples; k++) {
         double s = Math.min(total, k * step);
         while (segment + 2 < xs.length && arc[segment + 1] < s) segment++;
         double span = arc[segment + 1] - arc[segment];
         double t = span > 0.0 ? Math.max(0.0, Math.min(1.0, (s - arc[segment]) / span)) : 0.0;
         double dx = xs[segment + 1] - xs[segment], dz = zs[segment + 1] - zs[segment];
         double length = Math.max(1.0E-9, Math.hypot(dx, dz));
         px[k] = xs[segment] + dx * t; pz[k] = zs[segment] + dz * t;
         nx[k] = -dz / length; nz[k] = dx / length;
      }
      // Normals of the chord over a few blocks either side: at a sharp corner of the mapped line the direction
      // across the course turns gradually, so offsets on both sides of it do not tear the course apart. A course carrying
      // a river area takes them over its corridor's width: where its outline swings up a valley side the course cuts
      // across the bend to the floor, which offsets along the line's own sharp turns could not reach without folding.
      double span = line.inRiverArea() && radius > 0 ? Math.max(NORMAL_SPAN, radius) : NORMAL_SPAN;
      int reach = Math.max(1, (int)Math.round(span / Math.max(step, 1.0E-6)));
      double[] snx = new double[samples], snz = new double[samples];
      for (int k = 0; k < samples; k++) {
         int a = Math.max(0, k - reach), b = Math.min(samples - 1, k + reach);
         double dx = px[b] - px[a], dz = pz[b] - pz[a], length = Math.hypot(dx, dz);
         if (length < 1.0E-6) { snx[k] = nx[k]; snz[k] = nz[k]; }
         else { snx[k] = -dz / length; snz[k] = dx / length; }
      }
      nx = snx; nz = snz;
      double mpb = scale.metersPerBlock((pz[0] + pz[samples - 1]) * 0.5);
      double[] offset = null;
      if (radius > 0 && terrain != null && step <= 2.0) {
         int last = samples - 1;
         double startOffset = start == null ? Double.NaN : (start[0] - px[0]) * nx[0] + (start[1] - pz[0]) * nz[0];
         double endOffset = end == null ? Double.NaN : (end[0] - px[last]) * nx[last] + (end[1] - pz[last]) * nz[last];
         // Near a river area, a lake or the sea the course keeps to its mapped line, moved as that water is, so it meets
         // the water where the map does: the terrain model shows the flat water itself as the lowest ground around,
         // which would draw the course along its edge at the course's own level.
         double[] held = null;
         double halfWidth = RiverNetwork.routedHalfWidth(line);
         double near = Math.max(RiverScale.MIN_HALF_WIDTH, halfWidth) + RiverCorridor.HOLD;
         for (int k = 0; k <= last; k = k == last ? last + 1 : Math.min(last, k + SNAP_STRIDE)) {
            double[] shift = tile.holding(px[k], pz[k], near, line, terrain);
            if (shift == null) continue;
            if (held == null) { held = new double[samples]; Arrays.fill(held, Double.NaN); }
            held[k] = shift[0] * nx[k] + shift[1] * nz[k];
         }
         offset = RiverCorridor.offsets(px, pz, nx, nz, step, SNAP_STRIDE, radius, halfWidth, terrain, startOffset, endOffset, held,
            (int)Math.round(FALL_METERS / mpb));
      }
      if (offset == null) return new Course(line, knots[0], knots[1], px, pz, nx, nz, null, step, total, mpb, xs, zs, arc, xs, zs);
      // The drawn course: a vertex every few samples on the routed course, keeping the mapped arc parameter. Anchored
      // ends lie exactly on their anchors, so every piece meeting there ends at the same point.
      int vertices = (samples - 1 + SNAP_STRIDE - 1) / SNAP_STRIDE + 1;
      double[] cxs = new double[vertices], czs = new double[vertices], carc = new double[vertices];
      double[] mxs = new double[vertices], mzs = new double[vertices];
      for (int v = 0; v < vertices; v++) {
         int k = Math.min(samples - 1, v * SNAP_STRIDE);
         mxs[v] = px[k]; mzs[v] = pz[k];
         cxs[v] = px[k] + nx[k] * offset[k]; czs[v] = pz[k] + nz[k] * offset[k]; carc[v] = Math.min(total, k * step);
      }
      if (start != null) { cxs[0] = start[0]; czs[0] = start[1]; }
      if (end != null) { cxs[vertices - 1] = end[0]; czs[vertices - 1] = end[1]; }
      return new Course(line, knots[0], knots[1], px, pz, nx, nz, offset, step, total, mpb, cxs, czs, carc, mxs, mzs);
   }

   /** The levels along a piece's course (see the class comment). */
   static RiverRun profile(Course course, RiverNetwork.Tile tile, RiverChannels.Terrain terrain) {
      RiverNetwork.Line line = course.line();
      double[] knotS = course.knotS(), knotL = course.knotL(), px = course.px(), pz = course.pz(), nx = course.nx(), nz = course.nz();
      double[] offset = course.offset();
      double step = course.step(), total = course.total(), mpb = course.mpb();
      int samples = px.length;
      float[] upper = new float[samples], ground = new float[samples];
      // The water must fit under the ground across the whole channel: a course along one side of its
      // valley would otherwise hold the water up against the lower bank.
      double across = Math.min(8.0, Math.max(0.0, line.channelHalfWidth()));
      for (int k = 0; k < samples; k++) {
         // Without native terrain (coarse levels) the network level is the whole profile.
         if (terrain == null) { upper[k] = ground[k] = (float)interpolate(knotS, knotL, Math.min(total, k * step)); continue; }
         upper[k] = (float)networkLevel(knotS, knotL, Math.min(total, k * step));
         double cx = px[k], cz = pz[k];
         if (offset != null) { cx += nx[k] * offset[k]; cz += nz[k] * offset[k]; }
         double low = terrain.height((int)Math.floor(cx), (int)Math.floor(cz));
         if (across >= 0.5) {
            for (double o : new double[]{across * 0.5, across}) {
               low = Math.min(low, terrain.height((int)Math.floor(cx + nx[k] * o), (int)Math.floor(cz + nz[k] * o)));
               low = Math.min(low, terrain.height((int)Math.floor(cx - nx[k] * o), (int)Math.floor(cz - nz[k] * o)));
            }
         }
         ground[k] = (float)low;
      }
      // Native terrain with short dips filled, capped by the network's level (as below) but where the course goes down a cliff.
      // Inside a river area the terrain along the line is the water's own surface (the model flattens it), as the area's
      // levels are: the network's coarse cells would spread a fall over their length and put its lip apart from the
      // area's crest.
      float[] closed = closing(ground, FINE_CLOSE);
      // Down a cliff (a fall, a cascade) a natural course follows its own terrain too: the network's coarser cells put
      // the drop elsewhere and would sink the water into a slot cut through the lip.
      boolean[] cliff = terrain == null || line.engineered() || step > 2.0 ? new boolean[samples] : cliffs(closed, step);
      // The network's level drops a whole cell's descent at a time, often where the native terrain still stands above the
      // cell below: the water fell sheer there and lay in a trench until the terrain came down to it. A natural course
      // never drops faster than its terrain: the network's level only holds it where the terrain rises (a bridge, a col),
      // at the level reached upstream unless the network's is higher.
      boolean slope = terrain != null && !line.engineered() && step <= 2.0;
      float[] bounded = new float[samples];
      for (int k = 0; k < samples; k++) {
         float g = Float.isFinite(closed[k]) ? closed[k] : upper[k];
         float cap = !slope ? upper[k] : k == 0 ? g : Math.max(upper[k], bounded[k - 1]);
         bounded[k] = (line.inRiverArea() || cliff[k]) && terrain != null && Float.isFinite(closed[k]) ? g : Math.min(cap, g);
      }
      // A line drawn in its river area stands at the area's level where its channel shows beyond the outline (along a
      // bank, where it leaves the area), read every few blocks, so it meets the area's water there. Deep inside the
      // area its channel is never drawn.
      if (line.inRiverArea() && terrain != null) {
         int stride = 8;
         double spill = Math.max(RiverScale.MIN_HALF_WIDTH, line.channelHalfWidth()) + 2.0;
         float[] area = new float[samples];
         java.util.Arrays.fill(area, Float.NaN);
         for (int k = 0; k < samples; k = k == samples - 1 ? samples : Math.min(samples - 1, k + stride)) {
            // The area moves with this course: its outline lies around the course as it is mapped around the line.
            if (tile.insideRiverArea(px[k], pz[k]) && tile.insideRiverArea(px[k] + nx[k] * spill, pz[k] + nz[k] * spill)
               && tile.insideRiverArea(px[k] - nx[k] * spill, pz[k] - nz[k] * spill)) continue;
            double x = offset == null ? px[k] : px[k] + nx[k] * offset[k], z = offset == null ? pz[k] : pz[k] + nz[k] * offset[k];
            area[k] = (float)tile.areaLevel(x, z, terrain);
         }
         for (int a = 0; a < samples; ) {
            int b = a == samples - 1 ? samples : Math.min(samples - 1, a + stride);
            if (b < samples && Float.isFinite(area[a]) && Float.isFinite(area[b]))
               for (int k = a + 1; k < b; k++) area[k] = area[a] + (area[b] - area[a]) * (k - a) / (float)(b - a);
            a = b;
         }
         for (int k = 0; k < samples; k++) if (Float.isFinite(area[k])) bounded[k] = area[k];
      }
      // Lowest of the last stretch upstream, then the nearest level that never rises downstream: past the window
      // the lowest level would climb back up out of a long dip.
      int window = Math.max(1, (int)Math.round(FINE_WINDOW / Math.max(step, 1.0E-6)));
      float[] level = slidingMinimum(bounded, window);
      float[] base = terrain != null && tile.hasBaseWater() ? baseWater(bounded, level, px, pz, nx, nz, offset, tile, step, mpb) : null;
      if (base != null) level = slidingMinimum(bounded, window);
      // The larger river this waterway flows into: from the first sample in its water (its river area or channel)
      // the level is that river's, and the stretch before descends to it.
      int entry = samples;
      float[] received = null;
      for (int k = samples - 1; k >= 0 && terrain != null; k--) {
         double x = offset == null ? px[k] : px[k] + nx[k] * offset[k], z = offset == null ? pz[k] : pz[k] + nz[k] * offset[k];
         double receiving = tile.receivingLevel(line, x, z, terrain);
         if (Double.isNaN(receiving)) break;
         if (received == null) received = new float[samples];
         received[k] = (float)receiving;
         entry = k;
      }
      if (received != null) { mouth(bounded, level, received, step, entry); level = slidingMinimum(bounded, window); }
      if (terrain != null) level = descending(level);
      if (terrain != null && tile.hasBaseWater())
         outflow(level, base, px, pz, nx, nz, offset, tile, Math.max(12.0, 3.0 * terrain.resolution((int)Math.floor(px[0]), (int)Math.floor(pz[0]))));
      if (received != null) backwater(level, received[entry], entry);
      if (base != null && !Float.isNaN(base[samples - 1])) {
         int contact = samples - 1;
         while (contact > 0 && !Float.isNaN(base[contact - 1])) contact--;
         settle(level, base, contact);
      }
      java.util.BitSet dry = terrain == null || line.inRiverArea() ? null : dryStretches(closed, level, step, mpb);
      boolean falls = !line.engineered() && terrain != null && step <= 2.0;
      return new RiverRun(line, course.xs(), course.zs(), course.arc(), step, level, dry, falls);
   }

   /**
    * A piece lengthened or shortened at one end along its own direction there, so that end lies level with an
    * {@code anchor} beside it; the corridor then moves it across onto the anchor. A tributary whose main river moved
    * away reaches it, one whose main river moved towards it stops at it. Lengthens by at most {@link #MAX_REACH} and
    * keeps at least a quarter of the piece.
    */
   static double[][] toward(double[] xs, double[] zs, double[] anchor, boolean atStart) {
      int n = xs.length;
      double total = 0.0;
      for (int i = 1; i < n; i++) total += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
      if (!(total > 1.0E-6)) return new double[][]{xs, zs};
      // Direction at the end, outwards, over a few blocks of the line.
      double[] inner = pointAt(xs, zs, atStart ? Math.min(total, 8.0) : Math.max(0.0, total - 8.0));
      double ex = atStart ? xs[0] : xs[n - 1], ez = atStart ? zs[0] : zs[n - 1];
      double dx = ex - inner[0], dz = ez - inner[1], length = Math.hypot(dx, dz);
      if (length < 1.0E-6) return new double[][]{xs, zs};
      dx /= length; dz /= length;
      double along = (anchor[0] - ex) * dx + (anchor[1] - ez) * dz;
      along = Math.max(-0.75 * total, Math.min(MAX_REACH, along));
      if (Math.abs(along) < 0.5) return new double[][]{xs, zs};
      if (along > 0.0) {
         double[] nxs = new double[n + 1], nzs = new double[n + 1];
         int shift = atStart ? 1 : 0;
         System.arraycopy(xs, 0, nxs, shift, n);
         System.arraycopy(zs, 0, nzs, shift, n);
         int at = atStart ? 0 : n;
         nxs[at] = ex + dx * along; nzs[at] = ez + dz * along;
         return new double[][]{nxs, nzs};
      }
      // Shortened: the part of the line beyond the cut is dropped.
      double cut = atStart ? -along : total + along;
      double[] point = pointAt(xs, zs, cut);
      double walked = 0.0;
      int split = 1;
      while (split < n - 1 && walked + Math.hypot(xs[split] - xs[split - 1], zs[split] - zs[split - 1]) <= cut) {
         walked += Math.hypot(xs[split] - xs[split - 1], zs[split] - zs[split - 1]);
         split++;
      }
      // Vertices [0, split) lie before the cut, [split, n) after it.
      int kept = atStart ? n - split + 1 : split + 1;
      double[] nxs = new double[kept], nzs = new double[kept];
      if (atStart) {
         nxs[0] = point[0]; nzs[0] = point[1];
         System.arraycopy(xs, split, nxs, 1, n - split);
         System.arraycopy(zs, split, nzs, 1, n - split);
      } else {
         System.arraycopy(xs, 0, nxs, 0, split);
         System.arraycopy(zs, 0, nzs, 0, split);
         nxs[split] = point[0]; nzs[split] = point[1];
      }
      return new double[][]{nxs, nzs};
   }

   /** The point of a polyline at an arc length (clamped to its ends). */
   private static double[] pointAt(double[] xs, double[] zs, double s) {
      double walked = 0.0;
      for (int i = 1; i < xs.length; i++) {
         double length = Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
         if (walked + length >= s && length > 1.0E-12) {
            double t = Math.max(0.0, Math.min(1.0, (s - walked) / length));
            return new double[]{xs[i - 1] + (xs[i] - xs[i - 1]) * t, zs[i - 1] + (zs[i] - zs[i - 1]) * t};
         }
         walked += length;
      }
      return s <= 0.0 ? new double[]{xs[0], zs[0]} : new double[]{xs[xs.length - 1], zs[zs.length - 1]};
   }

   /**
    * A lake or the sea a waterway runs into holds its level: inside it the waterway stands no higher, and where it
    * arrives a few blocks above that level (a channel narrower than the terrain model shows, a lake level read from
    * a smoothed surface) it descends to it gently over the last stretch instead of dropping at the shore. A larger
    * drop is a fall into the water and stays one. Lowers {@code bounded} in place; returns the water body's level
    * at each sample in it (NaN elsewhere), or null when none is met.
    */
   private static float[] baseWater(float[] bounded, float[] level, double[] px, double[] pz, double[] nx, double[] nz,
      double[] offset, RiverNetwork.Tile tile, double step, double mpb) {
      int samples = bounded.length;
      float[] base = null;
      for (int k = 0; k < samples; k++) {
         double x = px[k], z = pz[k];
         if (offset != null) { x += nx[k] * offset[k]; z += nz[k] * offset[k]; }
         double b = tile.baseLevel(x, z);
         if (Double.isNaN(b)) continue;
         if (base == null) { base = new float[samples]; Arrays.fill(base, Float.NaN); }
         base[k] = (float)b;
      }
      if (base == null) return null;
      double maxRamp = Math.max(2.0, RAMP_METERS / mpb);
      for (int k = 0; k < samples; k++) {
         if (Float.isNaN(base[k])) continue;
         bounded[k] = Math.min(bounded[k], base[k]);
         if (k == 0 || !Float.isNaN(base[k - 1])) continue;
         double drop = level[k - 1] - base[k];
         if (drop <= 0.0 || drop > maxRamp) continue;
         // Only the dry stretch since the last water body descends.
         int from = k - 1;
         while (from > 0 && Float.isNaN(base[from - 1])) from--;
         taper(bounded, level, k, from, drop, drop / RAMP_SLOPE, step);
      }
      return base;
   }

   /**
    * A waterway leaving a lake (drawn from its shore, or out of one it runs through) stands no higher than the lake:
    * the terrain model often shows the lip of the outlet above the water (Lake Quill), which cannot climb over it.
    * Lowers {@code level} in place. Only lakes: a course drawn out of the sea is mapped the wrong way round.
    */
   private static void outflow(float[] level, float[] base, double[] px, double[] pz, double[] nx, double[] nz, double[] offset,
      RiverNetwork.Tile tile, double limit) {
      double lake = Double.NaN;
      if (px.length > 1) {
         // A course drawn from the shore: the water just behind its first point.
         double dx = px[0] - px[1], dz = pz[0] - pz[1], length = Math.hypot(dx, dz);
         if (length > 1.0E-9) lake = tile.lakeLevel(px[0] + dx / length * 2.0, pz[0] + dz / length * 2.0);
      }
      boolean checked = false;
      for (int k = 0; k < level.length; k++) {
         if (base != null && !Float.isNaN(base[k])) {
            double x = px[k], z = pz[k];
            if (offset != null) { x += nx[k] * offset[k]; z += nz[k] * offset[k]; }
            lake = tile.lakeLevel(x, z);
            checked = false;
         } else if (!Double.isNaN(lake)) {
            // The level never rises downstream, so the first sample out stands highest. Far above the lake, beyond
            // the terrain model's blur, the two disagree for another reason (a lake level read too low): no canyon.
            if (!checked) {
               checked = true;
               if (level[k] - lake > limit) { lake = Double.NaN; continue; }
            }
            level[k] = (float)Math.min(level[k], lake);
         }
      }
   }

   /**
    * A waterway reaching a larger one (a tributary at its mouth, a side arm in its river area) stands at that river's
    * level ({@code received}, from sample {@code entry} on) wherever it is in its water. Arriving above it, its last
    * stretch descends to it (gently over a small drop, as cascades over a large one, within {@link #MOUTH_LENGTH}
    * blocks) and cuts down through the main river's lowered banks instead of running along their top between walls.
    * Lowers {@code bounded} in place.
    */
   private static void mouth(float[] bounded, float[] level, float[] received, double step, int entry) {
      for (int k = entry; k < bounded.length; k++) bounded[k] = Math.min(bounded[k], received[k]);
      if (entry == 0) return;
      double drop = level[entry - 1] - received[entry];
      taper(bounded, level, entry, 0, drop, Math.min(MOUTH_LENGTH, drop / MOUTH_SLOPE), step);
   }

   /**
    * Lowers the stretch before sample {@code entry} (back to {@code stop} at most) by {@code drop} at the entry, fading
    * to nothing {@code length} blocks upstream: the waterway keeps its own descent and gains the drop over that stretch
    * instead of in one fall.
    */
   private static void taper(float[] bounded, float[] level, int entry, int stop, double drop, double length, double step) {
      if (!(drop > 0.0) || !(length > 0.0)) return;
      for (int j = entry - 1; j >= stop; j--) {
         double fade = 1.0 - (entry - j) * step / length;
         if (fade <= 0.0) break;
         bounded[j] = (float)Math.min(bounded[j], level[j] - drop * fade);
      }
   }

   /**
    * A waterway arriving a little below the river it joins (its own terrain lower than that river's water)
    * stands at that river's level over its last stretch, a pool backed up from the mouth, rather than stepping up
    * into it. Raises {@code level} in place, never by more than {@link #BACKWATER} blocks.
    */
   private static void backwater(float[] level, double receiving, int entry) {
      int last = level.length - 1, at = Math.min(entry, last);
      if (level[at] >= receiving || receiving - level[at] > BACKWATER) return;
      for (int j = last; j >= 0 && level[j] < receiving; j--) level[j] = (float)receiving;
   }

   /**
    * A waterway ending in a lake or the sea (from sample {@code contact} on, at the levels in {@code base}) is level
    * with it there, and arriving a little below it (terrain under sea level beside a mouth) its last stretch stands
    * at that level too, never more than {@link #BACKWATER} blocks up: a channel well below the sea stays (a polder).
    */
   private static void settle(float[] level, float[] base, int contact) {
      float surface = base[contact];
      if (contact > 0 && level[contact - 1] < surface) {
         if (surface - level[contact - 1] > BACKWATER) return;
         for (int j = contact - 1; j >= 0 && level[j] < surface; j--) level[j] = surface;
      }
      for (int k = contact; k < level.length; k++) level[k] = Math.min(base[k], k == contact ? surface : level[k - 1]);
   }

   /** Gentlest descent of a tributary to the river it joins, blocks per block, and the longest such stretch, blocks. */
   private static final double MOUTH_SLOPE = 0.25, MOUTH_LENGTH = 48.0;
   /** Deepest backwater pool at a mouth, blocks. */
   private static final double BACKWATER = 3.0;

   /** Largest arrival above a lake or the sea that descends to it as a slope rather than a fall, ground metres. */
   private static final double RAMP_METERS = 8.0;
   /** Steepness of that slope, blocks of drop per block. */
   private static final double RAMP_SLOPE = 0.125;

   /** Samples within a long stretch where the ground stands far above the water. */
   private static java.util.BitSet dryStretches(float[] ground, float[] level, double step, double mpb) {
      double excess = Math.max(6.0, DRY_EXCESS_METERS / mpb), length = Math.max(4.0, DRY_LENGTH_METERS / mpb);
      java.util.BitSet dry = null;
      int start = -1;
      for (int k = 0; k <= level.length; k++) {
         boolean high = k < level.length && ground[k] - level[k] > excess;
         if (high && start < 0) start = k;
         if (!high && start >= 0) {
            if ((k - start) * step >= length) {
               if (dry == null) dry = new java.util.BitSet(level.length);
               dry.set(start, k);
            }
            start = -1;
         }
      }
      return dry;
   }

   /** Morphological closing of a sequence (dilate then erode, half-width {@code radius}). */
   private static float[] closing(float[] values, int radius) {
      int n = values.length;
      float[] dilated = new float[n], result = new float[n];
      for (int k = 0; k < n; k++) {
         float best = Float.NEGATIVE_INFINITY;
         for (int j = Math.max(0, k - radius); j <= Math.min(n - 1, k + radius); j++) if (Float.isFinite(values[j])) best = Math.max(best, values[j]);
         dilated[k] = best == Float.NEGATIVE_INFINITY ? values[k] : best;
      }
      for (int k = 0; k < n; k++) {
         float best = Float.POSITIVE_INFINITY;
         for (int j = Math.max(0, k - radius); j <= Math.min(n - 1, k + radius); j++) best = Math.min(best, dilated[j]);
         result[k] = Float.isFinite(values[k]) ? Math.max(values[k], best) : best;
      }
      return result;
   }

   /**
    * The sequence nearest {@code values} (least squares) that never rises: adjacent stretches where it would rise
    * merge into one at their mean level (pool adjacent violators, linear time). A short dip before a long rise
    * fills up to it, a short rise before a long dip is cut down to it.
    */
   static float[] descending(float[] values) {
      int n = values.length;
      double[] sum = new double[n];
      int[] count = new int[n];
      int blocks = 0;
      for (float value : values) {
         sum[blocks] = value; count[blocks] = 1; blocks++;
         while (blocks > 1 && sum[blocks - 1] * count[blocks - 2] > sum[blocks - 2] * count[blocks - 1]) {
            sum[blocks - 2] += sum[blocks - 1]; count[blocks - 2] += count[blocks - 1]; blocks--;
         }
      }
      float[] result = new float[n];
      int k = 0;
      for (int b = 0; b < blocks; b++) {
         float mean = (float)(sum[b] / count[b]);
         for (int j = 0; j < count[b]; j++) result[k++] = mean;
      }
      return result;
   }

   /** Samples where the terrain falls more steeply than a block per block over a few blocks: a fall or a cascade down a cliff. */
   private static boolean[] cliffs(float[] ground, double step) {
      int n = ground.length, w = Math.max(1, (int)Math.round(2.0 / Math.max(step, 1.0E-6)));
      boolean[] cliff = new boolean[n];
      for (int k = 0; k < n; k++) {
         int a = Math.max(0, k - w), b = Math.min(n - 1, k + w);
         cliff[k] = b > a && (ground[a] - ground[b]) / ((b - a) * step) > 1.0;
      }
      return cliff;
   }

   /** Minimum over the window ending at each index (monotone deque, linear time). */
   private static float[] slidingMinimum(float[] values, int window) {
      int n = values.length;
      float[] result = new float[n];
      int[] deque = new int[n];
      int head = 0, tail = 0;
      for (int k = 0; k < n; k++) {
         while (tail > head && values[deque[tail - 1]] >= values[k]) tail--;
         deque[tail++] = k;
         while (deque[head] <= k - window) head++;
         result[k] = values[deque[head]];
      }
      return result;
   }

   /** A drop between the levels of neighbouring network cells, blocks, within which a fall or cascade lies. */
   private static final double FALL_CELL_DROP = 4.0;

   /**
    * The network's level at an arc position for a profile on native terrain: linear between the levels of neighbouring
    * cells, except across a drop of {@link #FALL_CELL_DROP} or more (a fall or cascade within a cell), where the upper
    * cell's level holds to the next cell's. The terrain then places the drop: spread over the cells, the network's level
    * would sink the water into a trench above the lip of a fall the terrain shows within one cell.
    */
   private static double networkLevel(double[] s, double[] l, double at) {
      int i = Arrays.binarySearch(s, at);
      if (i < 0) i = -i - 2;
      if (i >= 0 && i + 1 < s.length && at > s[i] && l[i] - l[i + 1] >= FALL_CELL_DROP) return l[i];
      return interpolate(s, l, at);
   }

   private static double interpolate(double[] s, double[] l, double at) {
      if (at <= s[0]) return l[0];
      int n = s.length;
      if (at >= s[n - 1]) return l[n - 1];
      int i = Arrays.binarySearch(s, at);
      if (i >= 0) return l[i];
      i = -i - 2;
      double t = (at - s[i]) / Math.max(1.0E-9, s[i + 1] - s[i]);
      return l[i] + (l[i + 1] - l[i]) * t;
   }

   /** Arc positions of the midpoints of the pieces in each network cell, and those cells' levels. */
   private static double[][] knots(double[] xs, double[] zs, RiverNetwork.Tile tile) {
      RiverGraph graph = tile.graph;
      int cellSize = graph.cellSize;
      it.unimi.dsi.fastutil.doubles.DoubleArrayList s = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
      it.unimi.dsi.fastutil.doubles.DoubleArrayList l = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
      double walked = 0.0;
      for (int i = 1; i < xs.length; i++) {
         double ax = xs[i - 1], az = zs[i - 1], bx = xs[i], bz = zs[i];
         double dx = bx - ax, dz = bz - az, length = Math.sqrt(dx * dx + dz * dz);
         if (length < 1.0E-9) continue;
         int cx = (int)Math.floor(ax / cellSize), cz = (int)Math.floor(az / cellSize);
         int endX = (int)Math.floor(bx / cellSize), endZ = (int)Math.floor(bz / cellSize);
         int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0, stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
         double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : cellSize / Math.abs(dx);
         double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : cellSize / Math.abs(dz);
         double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? (cx + 1) * (double)cellSize : cx * (double)cellSize) - ax) / dx;
         double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? (cz + 1) * (double)cellSize : cz * (double)cellSize) - az) / dz;
         double tIn = 0.0;
         int guard = Math.abs(endX - cx) + Math.abs(endZ - cz) + 2;
         while (true) {
            double tOut = Math.min(1.0, Math.min(tMaxX, tMaxZ));
            int cell = graph.cell(cx, cz);
            if (tOut > tIn && cell >= 0 && Double.isFinite(tile.level[cell])) {
               double mid = walked + length * (tIn + tOut) * 0.5;
               // Pieces of one cell share its level; keep knots strictly increasing.
               if (s.isEmpty() || mid > s.getDouble(s.size() - 1)) { s.add(mid); l.add(tile.level[cell]); }
            }
            if (cx == endX && cz == endZ || --guard < 0 || tOut >= 1.0) break;
            if (tMaxX <= tMaxZ) { cx += stepX; tIn = tMaxX; tMaxX += tDeltaX; }
            else { cz += stepZ; tIn = tMaxZ; tMaxZ += tDeltaZ; }
         }
         walked += length;
      }
      return new double[][]{s.toDoubleArray(), l.toDoubleArray()};
   }

   /** The drawn position at a mapped arc position. */
   double[] position(double s) {
      int i = Arrays.binarySearch(arc, s);
      if (i < 0) i = -i - 2;
      i = Math.max(0, Math.min(xs.length - 2, i));
      double span = arc[i + 1] - arc[i];
      double t = span > 0.0 ? Math.max(0.0, Math.min(1.0, (s - arc[i]) / span)) : 0.0;
      return new double[]{xs[i] + (xs[i + 1] - xs[i]) * t, zs[i] + (zs[i + 1] - zs[i]) * t};
   }

   /** Whether the waterway runs dry at an arc position. */
   boolean dry(double s) {
      if (dry == null) return false;
      int k = (int)Math.round(s / step);
      return k >= 0 && k < level.length && dry.get(k);
   }

   /** The level at an arc position (between samples, the nearer one's; a fall holds its upper level to the lip). */
   double level(double s) {
      int k = (int)Math.floor(s / step);
      if (k < 0) return level[0];
      if (k >= level.length - 1) return level[level.length - 1];
      double t = s / step - k;
      return t < 0.5 || level[k] - level[k + 1] >= FALL_DROP ? level[k] : level[k + 1];
   }

   /** The top water block at an arc position: one below a terrain surface at the level. */
   int waterY(double s) {
      return (int)Math.ceil(level(s) - 1.0E-6) - 1;
   }

   /**
    * The level banks are shaped against: the mean level within a few blocks, so the drop of a fall becomes a
    * short slope in the banks beside it rather than a straight cliff running out from the lip.
    */
   double bankLevel(double s) {
      float[] mean = smoothed;
      if (mean == null) smoothed = mean = runningMean(level, Math.max(1, (int)Math.round(BANK_SMOOTHING / Math.max(step, 1.0E-6))));
      int k = (int)Math.round(s / step);
      return mean[Math.max(0, Math.min(mean.length - 1, k))];
   }

   // The mean level around each sample, made on first use; a benign race computes the same values.
   private volatile float[] smoothed;

   /** The mean of each value and those within {@code reach} either side (the ends repeated beyond the ends). */
   private static float[] runningMean(float[] values, int reach) {
      int n = values.length;
      double[] prefix = new double[n + 1];
      for (int k = 0; k < n; k++) prefix[k + 1] = prefix[k] + values[k];
      float[] mean = new float[n];
      for (int k = 0; k < n; k++) {
         int lo = k - reach, hi = k + reach;
         double sum = prefix[Math.min(n, hi + 1)] - prefix[Math.max(0, lo)];
         if (lo < 0) sum += -lo * (double)values[0];
         if (hi >= n) sum += (hi - n + 1) * (double)values[n - 1];
         mean[k] = (float)(sum / (2 * reach + 1));
      }
      return mean;
   }

   private static final double BANK_SMOOTHING = 8.0;

   /** The fall whose lip lies within a distance of an arc position, or -1. */
   int fallNear(double s, double distance) {
      // Falls are in course order: the first lip at or after s - distance, if within s + distance.
      int low = 0, high = fallIndex.length;
      while (low < high) {
         int mid = (low + high) >>> 1;
         if (lipS(mid) < s - distance) low = mid + 1; else high = mid;
      }
      return low < fallIndex.length && lipS(low) <= s + distance ? low : -1;
   }

   /** Arc position of a fall's lip: halfway between the samples it lies between. */
   double lipS(int fall) {
      return (fallIndex[fall] + 0.5) * step;
   }
}
