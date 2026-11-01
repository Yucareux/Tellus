package com.yucareux.tellus.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in diagnostic (see {@code tools/river-scan}): generates the block-level water columns of an
 * area exactly as chunk generation does, from a Tellus cache directory, and reports artifacts:
 * raised beds and perched water ("water bridges"), walls beside water, level steps between touching
 * pools and cliffs where water shaping stops. It also times the inland planner alone.
 *
 * <p>{@code TELLUS_RIVER_SCAN="label|gameDir|lat|lon|scale|radiusBlocks"}; output (report images)
 * goes to {@code TELLUS_RIVER_SCAN_OUT}/label. Missing map or DEM tiles are downloaded as in game,
 * so a cache that already holds the area gives an offline run.</p>
 */
@EnabledIfEnvironmentVariable(named = "TELLUS_RIVER_SCAN", matches = ".+")
class RiverArtifactScanTest {
   private static final int REGION = 64;
   private static final byte DRY = 0, WET = 1, BANK = 2, FALLING = 3, OCEAN = 4;

   @Test
   void scan() throws Exception {
      String[] spec = System.getenv("TELLUS_RIVER_SCAN").split("\\|");
      String label = spec[0];
      System.setProperty("tellus.gameDir", Path.of(spec[1]).toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of(spec[1]).resolve("config").toAbsolutePath().toString());
      double lat = Double.parseDouble(spec[2]), lon = Double.parseDouble(spec[3]), scale = Double.parseDouble(spec[4]);
      int radius = Integer.parseInt(spec[5]);
      Path out = Path.of(System.getenv().getOrDefault("TELLUS_RIVER_SCAN_OUT", "build/river-scan")).resolve(label);
      Files.createDirectories(out);

      JsonObject json = new JsonObject();
      json.addProperty("spawn_latitude", lat);
      json.addProperty("spawn_longitude", lon);
      json.addProperty("world_scale", scale);
      json.addProperty("experimental_increase_height", true);
      json.addProperty("experimental_height_coordinate_profile", HighYPackedCoordinateProfile.PROFILE_ID);
      EarthGeneratorSettings settings = EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
      WaterSurfaceResolver resolver = TellusWorldgenSources.waterResolver(settings);

      int centerX = (int)Math.floor(EarthProjection.longitudeToBlockX(lon, scale));
      int centerZ = (int)Math.floor(EarthProjection.latToBlockZ(lat, scale));
      int minX = Math.floorDiv(centerX - radius, REGION) * REGION, minZ = Math.floorDiv(centerZ - radius, REGION) * REGION;
      int regions = (2 * radius + REGION - 1) / REGION, size = regions * REGION;
      Area area = new Area(minX, minZ, size, scale);
      long started = System.nanoTime();
      readRegions(resolver, area, regions);
      double seconds = (System.nanoTime() - started) / 1e9;
      timePlanner(resolver, area);
      System.out.printf(Locale.ROOT, "RIVERSCAN_RESULT label=%s scale=%.1f size=%d seconds=%.1f wet=%d banks=%d%n",
         label, scale, size, seconds, area.count(WET) + area.count(FALLING), area.count(BANK));
      // Water depth of inland wet columns: mean, median, 90th percentile, deepest.
      it.unimi.dsi.fastutil.ints.IntArrayList depths = new it.unimi.dsi.fastutil.ints.IntArrayList();
      for (int i = 0; i < size * size; i++) if (area.kind[i] == WET) depths.add(area.water[i] - area.terrain[i]);
      if (!depths.isEmpty()) {
         int[] sorted = depths.toIntArray();
         java.util.Arrays.sort(sorted);
         double mean = 0.0;
         for (int d : sorted) mean += d;
         System.out.printf(Locale.ROOT, "RIVERSCAN_DEPTH mean=%.1f median=%d p90=%d max=%d%n", mean / sorted.length,
            sorted[sorted.length / 2], sorted[(int)((sorted.length - 1) * 0.9)], sorted[sorted.length - 1]);
      }

      // A raised bed puts stone under water above the natural ground; perched water stands above it
      // between walls; a wall is a bank raised above the ground; a step is a level change of two or
      // more between touching non-falling wet columns; a cliff is a height change between dry
      // columns at least three blocks larger than the natural one.
      int cells = size * size;
      int[] bridge = new int[cells], perched = new int[cells], wall = new int[cells], step = new int[cells], cliff = new int[cells];
      for (int i = 0; i < cells; i++) {
         int x = i % size, z = i / size;
         if (area.kind[i] == WET || area.kind[i] == FALLING) {
            bridge[i] = Math.max(0, area.terrain[i] - area.raw[i]);
            if (area.kind[i] == WET) perched[i] = Math.max(0, area.water[i] - area.raw[i]);
         } else if (area.kind[i] == BANK) wall[i] = Math.max(0, area.terrain[i] - area.raw[i]);
         for (int d = 0; d < 2; d++) {
            int nx = x + (d == 0 ? 1 : 0), nz = z + (d == 1 ? 1 : 0);
            if (nx >= size || nz >= size) continue;
            int n = nz * size + nx;
            if (area.kind[i] == WET && area.kind[n] == WET && Math.abs(area.water[n] - area.water[i]) >= 2) step[i] = step[n] = 2;
            if (area.dry(i) && area.dry(n))
               cliff[i] = Math.max(cliff[i], Math.abs(area.terrain[n] - area.terrain[i]) - Math.abs(area.raw[n] - area.raw[i]));
         }
      }
      report("BRIDGE", bridge, 2, area, out);
      report("PERCHED", perched, 2, area, out);
      report("WALL", wall, 2, area, out);
      report("STEP", step, 2, area, out);
      report("CLIFF", cliff, 3, area, out);
      // How deep water is sunk below the natural ground (a channel in a trench beside or below its valley floor) and how
      // far banks are cut back into it (a valley wall cut away towards the water).
      int[] sunk = new int[cells], cut = new int[cells];
      long sunkSum = 0, cutSum = 0;
      int wetColumns = 0, bankColumns = 0;
      for (int i = 0; i < cells; i++) {
         if (area.kind[i] == WET) { sunk[i] = Math.max(0, area.raw[i] - area.water[i] - 1); sunkSum += sunk[i]; wetColumns++; }
         else if (area.kind[i] == BANK) { cut[i] = Math.max(0, area.raw[i] - area.terrain[i]); cutSum += cut[i]; bankColumns++; }
      }
      report("SUNK", sunk, 4, area, out);
      report("CUT", cut, 4, area, out);
      System.out.printf(Locale.ROOT, "RIVERSCAN_CUTMEAN sunk=%.2f cut=%.2f%n", wetColumns == 0 ? 0.0 : sunkSum / (double)wetColumns,
         bankColumns == 0 ? 0.0 : cutSum / (double)bankColumns);
      int[] hard = new int[cells], reverse = new int[cells], missing = new int[cells];
      levelSteps(resolver, area, hard, reverse);
      // Waterfalls: curtain columns by the height of their fall, so each fall can be found and looked at.
      int[] falls = new int[cells];
      for (int i = 0; i < cells; i++) if (area.kind[i] == FALLING) falls[i] = Math.max(1, area.water[i] - area.terrain[i]);
      report("FALLS", falls, 1, area, out);
      // Curtains falling further than the natural ground drops beside them: sheer water where the terrain only slopes.
      int[] sheer = new int[cells];
      for (int i = 0; i < cells; i++) {
         if (area.kind[i] != FALLING) continue;
         int x = i % size, z = i / size, drop = 0;
         for (int d = 0; d < 4; d++) {
            int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
            if (nx >= 0 && nz >= 0 && nx < size && nz < size) drop = Math.max(drop, area.raw[i] - area.raw[nz * size + nx]);
         }
         sheer[i] = Math.max(0, area.water[i] - area.terrain[i] - drop);
      }
      report("SHEER", sheer, 3, area, out);
      // Columns the inland planner leaves to the coastal solver (unplanned water), every fourth column.
      int[] unplanned = new int[cells];
      Method planned = WaterSurfaceResolver.class.getDeclaredMethod("plannedInlandColumn", int.class, int.class, int.class);
      planned.setAccessible(true);
      Method inBand = WaterSurfaceResolver.class.getDeclaredMethod("inCoastalBand", int.class, int.class);
      inBand.setAccessible(true);
      for (int i = 0; i < cells; i++) {
         int x = i % size, z = i / size;
         if ((x & 3) != 0 || (z & 3) != 0 || area.kind[i] == DRY || area.kind[i] == OCEAN) continue;
         if ((boolean)inBand.invoke(resolver, area.minX + x, area.minZ + z)) continue;
         Object column = planned.invoke(resolver, area.minX + x, area.minZ + z, area.raw[i]);
         if (column == null || !((InlandHydrologyPlanner.Column)column).managed()) unplanned[i] = 1;
      }
      report("UNPLANNED", unplanned, 1, area, out);
      report("HARD", hard, 1, area, out);
      report("REVERSE", reverse, 1, area, out);
      int[] pit = new int[cells], mound = new int[cells];
      plateaus(area, pit, mound);
      report("PIT", pit, 1, area, out);
      report("MOUND", mound, 1, area, out);
      missingLines(resolver, area, missing);
      report("MISSING", missing, 1, area, out);
      ImageIO.write(render(area, 0, 0, size, 1, bridge, 2), "png", out.resolve("overview.png").toFile());
      // Optional close-ups at fixed points, every artifact kind flagged, to compare runs of different code.
      String focus = System.getenv("TELLUS_RIVER_SCAN_FOCUS");
      if (focus != null && !focus.isBlank()) {
         int[] flagged = new int[cells];
         for (int i = 0; i < cells; i++)
            flagged[i] = bridge[i] >= 2 || perched[i] >= 2 || wall[i] >= 2 || step[i] >= 2 || cliff[i] >= 3 ? 1 : 0;
         String[] points = focus.split(";");
         for (int k = 0; k < points.length; k++) {
            String[] latLon = points[k].split(",");
            int x = (int)Math.floor(EarthProjection.longitudeToBlockX(Double.parseDouble(latLon[1].trim()), scale)) - area.minX;
            int z = (int)Math.floor(EarthProjection.latToBlockZ(Double.parseDouble(latLon[0].trim()), scale)) - area.minZ;
            int half = Integer.parseInt(System.getenv().getOrDefault("TELLUS_RIVER_SCAN_FOCUS_HALF", "48"));
            int zoom = Math.max(1, 384 / (half * 2));
            int x0 = Math.max(0, Math.min(size - half * 2, x - half)), z0 = Math.max(0, Math.min(size - half * 2, z - half));
            ImageIO.write(render(area, x0, z0, half * 2, zoom, flagged, 1), "png", out.resolve("focus-" + k + ".png").toFile());
            BufferedImage levels = renderLevels(area, x0, z0, half * 2, zoom, hard, reverse, missing);
            drawCourses(levels, resolver, area, x0, z0, half * 2, zoom);
            ImageIO.write(levels, "png", out.resolve("levels-" + k + ".png").toFile());
            // Camera angles "azimuth,elevation;..." (default two opposite oblique views).
            String[] views = System.getenv().getOrDefault("TELLUS_RIVER_SCAN_VIEWS", "35,40;215,40").split(";");
            for (int view = 0; view < views.length; view++) {
               String[] angle = views[view].split(",");
               ImageIO.write(WaterRender3d.render(size, area.terrain, area.water, area.kind, x0, z0, half * 2, Double.parseDouble(angle[0].trim()),
                  Double.parseDouble(angle[1].trim()), Math.max(1.0, 640.0 / (half * 2))), "png", out.resolve("view-" + k + "-" + view + ".png").toFile());
            }
            ImageIO.write(WaterRender3d.render(size, area.raw, area.raw, area.kind, x0, z0, half * 2, 35.0, 40.0,
               Math.max(1.0, 640.0 / (half * 2))), "png", out.resolve("view-" + k + "-raw.png").toFile());
         }
      }
      ImageIO.write(WaterRender3d.render(size, area.terrain, area.water, area.kind, 0, 0, size, 35.0, 40.0, 1400.0 / size / Math.sqrt(2.0)),
         "png", out.resolve("view-overview.png").toFile());
      // The elevation model itself, mapped water drawn flush with it: what the terrain says the water surface is.
      ImageIO.write(WaterRender3d.render(size, area.raw, area.raw, area.kind, 0, 0, size, 0.0, 45.0, 1400.0 / size / Math.sqrt(2.0)),
         "png", out.resolve("view-raw.png").toFile());
      ImageIO.write(WaterRender3d.render(size, area.terrain, area.water, area.kind, 0, 0, size, 0.0, 45.0, 1400.0 / size / Math.sqrt(2.0)),
         "png", out.resolve("view-north.png").toFile());
      // Optional numeric dump around one point: per column its kind (. dry, b bank, w wet, f falling), terrain and water.
      String dump = System.getenv("TELLUS_RIVER_SCAN_DUMP");
      if (dump != null && !dump.isBlank()) {
         String[] latLon = dump.split(",");
         int cx = (int)Math.floor(EarthProjection.longitudeToBlockX(Double.parseDouble(latLon[1].trim()), scale)) - area.minX;
         int cz = (int)Math.floor(EarthProjection.latToBlockZ(Double.parseDouble(latLon[0].trim()), scale)) - area.minZ;
         StringBuilder b = new StringBuilder("RIVERSCAN_DUMP around " + (area.minX + cx) + "," + (area.minZ + cz) + " (kind terrain/water, raw)\n");
         for (int z = Math.max(0, cz - 12); z <= Math.min(size - 1, cz + 12); z++) {
            for (int x = Math.max(0, cx - 12); x <= Math.min(size - 1, cx + 12); x++) {
               int i = z * size + x;
               char kind = area.kind[i] == WET ? 'w' : area.kind[i] == FALLING ? 'f' : area.kind[i] == BANK ? 'b' : area.kind[i] == OCEAN ? 'o' : '.';
               b.append(String.format(Locale.ROOT, "%c%4d/%-4d", kind, area.terrain[i], area.kind[i] == WET || area.kind[i] == FALLING ? area.water[i] : area.raw[i]));
            }
            b.append('\n');
         }
         System.out.print(b);
      }
      // Optional sparse level map "lat,lon,half,step": water levels (b bank, . dry) beside the raw terrain, every step blocks.
      String grid = System.getenv("TELLUS_RIVER_SCAN_GRID");
      if (grid != null && !grid.isBlank()) {
         String[] g = grid.split(",");
         int cx = (int)Math.floor(EarthProjection.longitudeToBlockX(Double.parseDouble(g[1].trim()), scale)) - area.minX;
         int cz = (int)Math.floor(EarthProjection.latToBlockZ(Double.parseDouble(g[0].trim()), scale)) - area.minZ;
         int half = Integer.parseInt(g[2].trim()), every = Integer.parseInt(g[3].trim());
         StringBuilder b = new StringBuilder("RIVERSCAN_GRID around " + (area.minX + cx) + "," + (area.minZ + cz) + " step " + every + " (water, . when dry | raw terrain)\n");
         for (int z = Math.max(0, cz - half); z <= Math.min(size - 1, cz + half); z += every) {
            for (int x = Math.max(0, cx - half); x <= Math.min(size - 1, cx + half); x += every) {
               int i = z * size + x;
               b.append(area.kind[i] == WET || area.kind[i] == FALLING ? String.format(Locale.ROOT, "%5d", area.water[i]) : area.kind[i] == BANK ? "    b" : "    .");
            }
            b.append("  |");
            for (int x = Math.max(0, cx - half); x <= Math.min(size - 1, cx + half); x += every) b.append(String.format(Locale.ROOT, "%5d", area.raw[z * size + x]));
            b.append('\n');
         }
         System.out.print(b);
      }
      renderCoarse(resolver, area, out);
      // Optional: the level profile of the waterway nearest each point.
      String trace = System.getenv("TELLUS_RIVER_SCAN_TRACE");
      if (trace != null && !trace.isBlank()) {
         InlandHydrologyPlanner planner = (InlandHydrologyPlanner)field(WaterSurfaceResolver.class, "inlandHydrology").get(resolver);
         Object state = field(InlandHydrologyPlanner.class, "state").get(planner);
         Method channels = state.getClass().getDeclaredMethod("channels", int.class, int.class);
         channels.setAccessible(true);
         for (String point : trace.split(";")) {
            String[] latLon = point.replace("b", "").split(",");
            boolean blocks = point.startsWith("b");
            int px = blocks ? Integer.parseInt(latLon[0].trim()) : (int)Math.floor(EarthProjection.longitudeToBlockX(Double.parseDouble(latLon[1].trim()), scale));
            int pz = blocks ? Integer.parseInt(latLon[1].trim()) : (int)Math.floor(EarthProjection.latToBlockZ(Double.parseDouble(latLon[0].trim()), scale));
            RiverChannels plan = (RiverChannels)channels.invoke(state, Math.floorDiv(px, RiverChannels.TILE), Math.floorDiv(pz, RiverChannels.TILE));
            System.out.println("RIVERSCAN_TRACE " + point + " column=" + planner.sample(px, pz, 0) + " " + plan.profile(px, pz, Double.parseDouble(System.getenv().getOrDefault("TELLUS_RIVER_SCAN_TRACE_SPAN", "60"))));
         }
      }
      // Optional: the mapped water features covering points, as the planner classifies them.
      String probe = System.getenv("TELLUS_RIVER_SCAN_PROBE");
      if (probe != null && !probe.isBlank()) {
         com.yucareux.tellus.world.data.osm.TellusOsmWaterSource source = TellusWorldgenSources.osmWater();
         for (String point : probe.split(";")) {
            String[] latLon = point.replace("b", "").split(",");
            boolean blocks = point.startsWith("b");
            int px = blocks ? Integer.parseInt(latLon[0].trim()) : (int)Math.floor(EarthProjection.longitudeToBlockX(Double.parseDouble(latLon[1].trim()), scale));
            int pz = blocks ? Integer.parseInt(latLon[1].trim()) : (int)Math.floor(EarthProjection.latToBlockZ(Double.parseDouble(latLon[0].trim()), scale));
            int around = Integer.parseInt(System.getenv().getOrDefault("TELLUS_RIVER_SCAN_PROBE_RADIUS", "2"));
            var query = source.waterForAreaWithStatus(px - around, pz - around, px + around, pz + around, scale, 0, com.yucareux.tellus.world.data.osm.OsmQueryMode.BLOCKING);
            System.out.printf(Locale.ROOT, "RIVERSCAN_PROBE %s block=%d,%d features=%d%n", point, px, pz, query.features().size());
            for (var feature : query.features()) {
               HydrologyWaterMap.Shape shape = new HydrologyWaterMap.Shape(feature, scale);
               boolean inside = shape.contains(px, pz);
               System.out.printf(Locale.ROOT, "RIVERSCAN_PROBE   id=%d kind=%s line=%b point=%b ocean=%b flowing=%b river=%b inside=%b area=%.0f bounds=[%.0f,%.0f..%.0f,%.0f]%n",
                  feature.featureId(), feature.kind(), feature.lineGeometry(), feature.pointGeometry(), feature.oceanHint(), feature.flowingWater(),
                  shape.river, inside, shape.area(), shape.minX, shape.minZ, shape.maxX, shape.maxZ);
            }
         }
      }
   }

   /**
    * What distant LODs draw: the coarse river network at a few LOD cell sizes over the same area, one pixel per
    * cell, coloured by level band (a new colour every block), with downstream-rising neighbours counted.
    */
   private static void renderCoarse(WaterSurfaceResolver resolver, Area area, Path out) throws IOException, ReflectiveOperationException {
      InlandHydrologyPlanner planner = (InlandHydrologyPlanner)field(WaterSurfaceResolver.class, "inlandHydrology").get(resolver);
      for (int cell : new int[]{4, 16, 64, 128, 256}) {
         if (cell * area.scale <= 64.0) continue;
         int n = area.size / cell;
         int[] water = new int[n * n];
         boolean[] wet = new boolean[n * n];
         long started = System.nanoTime();
         IntStream.range(0, n).parallel().forEach(z -> {
            for (int x = 0; x < n; x++) {
               InlandHydrologyPlanner.Column column = planner.sampleCoarseRiver(area.minX + x * cell + cell / 2, area.minZ + z * cell + cell / 2, cell);
               if (column != null && column.wet()) { wet[z * n + x] = true; water[z * n + x] = column.water(); }
            }
         });
         double seconds = (System.nanoTime() - started) / 1e9;
         int cells = 0;
         for (boolean w : wet) if (w) cells++;
         System.out.printf(Locale.ROOT, "RIVERSCAN_COARSE cell=%d wet=%d seconds=%.2f%n", cell, cells, seconds);
         int zoom = Math.max(1, 1024 / n);
         BufferedImage image = new BufferedImage(n * zoom, n * zoom, BufferedImage.TYPE_INT_RGB);
         for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            int i = z * n + x;
            int rgb = wet[i] ? java.awt.Color.HSBtoRGB(Math.floorMod(water[i], 12) / 12.0F, 0.6F, 0.95F) : 0x203020;
            for (int dz = 0; dz < zoom; dz++) for (int dx = 0; dx < zoom; dx++) image.setRGB(x * zoom + dx, z * zoom + dz, rgb);
         }
         ImageIO.write(image, "png", out.resolve("coarse-" + cell + ".png").toFile());
      }
   }

   /** The generated columns of the area, read from the same region data chunk generation uses. */
   private static final class Area {
      final int minX, minZ, size;
      final double scale;
      final int[] raw, terrain, water;
      final byte[] kind, flow;

      Area(int minX, int minZ, int size, double scale) {
         this.minX = minX; this.minZ = minZ; this.size = size; this.scale = scale;
         raw = new int[size * size]; terrain = new int[size * size]; water = new int[size * size]; kind = new byte[size * size]; flow = new byte[size * size];
      }

      boolean dry(int i) { return kind[i] == DRY || kind[i] == BANK; }

      int count(byte k) {
         int total = 0;
         for (byte value : kind) if (value == k) total++;
         return total;
      }
   }

   private static void readRegions(WaterSurfaceResolver resolver, Area area, int regions) throws ReflectiveOperationException {
      Method resolve = WaterSurfaceResolver.class.getDeclaredMethod("resolveRegionData", int.class, int.class);
      resolve.setAccessible(true);
      Class<?> type = resolve.getReturnType();
      Field terrain = field(type, "terrainSurface"), water = field(type, "waterSurface"), flags = field(type, "waterFlags");
      Field raw = field(type, "rawSurface"), banks = field(type, "inlandBanks"), falling = field(type, "falling"), flows = field(type, "flowLevels");
      IntStream.range(0, regions * regions).parallel().forEach(r -> {
         int rx = Math.floorDiv(area.minX, REGION) + r % regions, rz = Math.floorDiv(area.minZ, REGION) + r / regions;
         try {
            Object region = resolve.invoke(resolver, rx, rz);
            int[] t = (int[])terrain.get(region), w = (int[])water.get(region), s = (int[])raw.get(region);
            byte[] f = (byte[])flags.get(region);
            boolean[] b = (boolean[])banks.get(region), fall = (boolean[])falling.get(region);
            byte[] levels = (byte[])flows.get(region);
            for (int z = 0; z < REGION; z++) for (int x = 0; x < REGION; x++) {
               int local = z * REGION + x;
               int index = (rz * REGION - area.minZ + z) * area.size + rx * REGION - area.minX + x;
               area.raw[index] = s[local]; area.terrain[index] = t[local]; area.water[index] = w[local]; area.flow[index] = levels[local];
               area.kind[index] = f[local] == 2 ? OCEAN : f[local] == 1 || f[local] == 3 ? (fall[local] ? FALLING : WET) : b[local] ? BANK : DRY;
            }
         } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
         }
      });
   }

   /**
    * Planner cost alone, with the map and DEM sources already warm: a fresh planner state samples
    * every block column, then LOD footprints of two-block cells, as detail 1 does at 1:30.
    */
   private static void timePlanner(WaterSurfaceResolver resolver, Area area) throws ReflectiveOperationException {
      InlandHydrologyPlanner planner = (InlandHydrologyPlanner)field(WaterSurfaceResolver.class, "inlandHydrology").get(resolver);
      int size = area.size;
      for (int round = 0; round < 2; round++) {
         planner.clear();
         long started = System.nanoTime();
         IntStream.range(0, size).parallel().forEach(z -> {
            for (int x = 0; x < size; x++) planner.sample(area.minX + x, area.minZ + z, area.raw[z * size + x]);
         });
         double columns = (System.nanoTime() - started) / 1e9;
         planner.clear();
         started = System.nanoTime();
         IntStream.range(0, size / 2).parallel().forEach(z -> {
            for (int x = 0; x < size / 2; x++)
               planner.sampleFootprint(area.minX + x * 2, area.minZ + z * 2, area.raw[z * 2 * size + x * 2], 2);
         });
         double footprints = (System.nanoTime() - started) / 1e9;
         System.out.printf(Locale.ROOT, "RIVERSCAN_PLANNER round=%d columns=%.2fs footprints2=%.2fs%n", round, columns, footprints);
      }
   }

   /**
    * Level steps between touching non-falling wet columns. A step whose upper column does not flow towards it
    * (no ramp of flowing water) is a hard edge; a step whose upper side lies downstream along the nearest
    * waterway runs against the flow.
    */
   /**
    * Level plateaus of wet water that water can only leave by flowing up (a pit: every wet or falling neighbour is
    * higher) or that sheds water to every side (a mound: every one is lower), by how far. Plateaus at the scan edge
    * or by the sea are skipped: their way out may lie beyond.
    */
   private static void plateaus(Area area, int[] pit, int[] mound) {
      int size = area.size;
      boolean[] seen = new boolean[size * size];
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      List<Integer> members = new ArrayList<>();
      for (int start = 0; start < size * size; start++) {
         if (area.kind[start] != WET || seen[start]) continue;
         int level = area.water[start], lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
         boolean open = false;
         members.clear();
         seen[start] = true; queue.add(start);
         while (!queue.isEmpty()) {
            int c = queue.removeFirst();
            members.add(c);
            int x = c % size, z = c / size;
            if (x == 0 || z == 0 || x == size - 1 || z == size - 1) open = true;
            for (int d = 0; d < 4; d++) {
               int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
               if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
               int n = nz * size + nx;
               if (area.kind[n] == OCEAN) open = true;
               if (area.kind[n] == WET && area.water[n] == level) {
                  if (!seen[n]) { seen[n] = true; queue.add(n); }
               } else if (area.kind[n] == WET || area.kind[n] == FALLING) {
                  lowest = Math.min(lowest, area.water[n]);
                  highest = Math.max(highest, area.water[n]);
               }
            }
         }
         if (open || lowest == Integer.MAX_VALUE) continue;
         if (lowest > level) for (int c : members) pit[c] = lowest - level;
         else if (highest < level) for (int c : members) mound[c] = level - highest;
      }
   }

   private static void levelSteps(WaterSurfaceResolver resolver, Area area, int[] hard, int[] reverse) throws ReflectiveOperationException {
      InlandHydrologyPlanner planner = (InlandHydrologyPlanner)field(WaterSurfaceResolver.class, "inlandHydrology").get(resolver);
      Object state = field(InlandHydrologyPlanner.class, "state").get(planner);
      Method channels = state.getClass().getDeclaredMethod("channels", int.class, int.class);
      channels.setAccessible(true);
      int size = area.size, steps = 0, ramped = 0, reversed = 0;
      for (int i = 0; i < size * size; i++) {
         int x = i % size, z = i / size;
         for (int d = 0; d < 2; d++) {
            int nx = x + (d == 0 ? 1 : 0), nz = z + (d == 1 ? 1 : 0);
            if (nx >= size || nz >= size) continue;
            int n = nz * size + nx;
            if (area.kind[i] != WET || area.kind[n] != WET || area.water[i] == area.water[n]) continue;
            steps++;
            int high = area.water[i] > area.water[n] ? i : n, low = high == i ? n : i;
            if (area.flow[high] > 0) ramped++;
            else hard[high] = hard[low] = 1;
            int bx = area.minX + x, bz = area.minZ + z, cx = area.minX + nx, cz = area.minZ + nz;
            RiverChannels plan = (RiverChannels)channels.invoke(state, Math.floorDiv(bx, RiverChannels.TILE), Math.floorDiv(bz, RiverChannels.TILE));
            double[] trace = plan.trace(bx, bz, cx, cz);
            if (trace == null || trace[3] > 96.0) continue;
            double sHigh = high == i ? trace[1] : trace[2], sLow = high == i ? trace[2] : trace[1];
            if (sHigh - sLow > 0.25) { reverse[high] = reverse[low] = 1; reversed++; }
         }
      }
      System.out.printf(Locale.ROOT, "RIVERSCAN_STEPS steps=%d ramped=%d hard=%d reverse=%d%n", steps, ramped, steps - ramped, reversed);
   }

   /**
    * Points of the drawn courses (after snapping to the valley floor) with no water within their channel's
    * half-width: a waterway that runs dry or vanished.
    */
   private static void missingLines(WaterSurfaceResolver resolver, Area area, int[] missing) throws ReflectiveOperationException {
      InlandHydrologyPlanner planner = (InlandHydrologyPlanner)field(WaterSurfaceResolver.class, "inlandHydrology").get(resolver);
      Object state = field(InlandHydrologyPlanner.class, "state").get(planner);
      Method channels = state.getClass().getDeclaredMethod("channels", int.class, int.class);
      channels.setAccessible(true);
      int size = area.size, total = 0, lost = 0, tile = RiverChannels.TILE;
      java.util.Map<Long, Integer> lostFeatures = new java.util.TreeMap<>();
      // How far drawn courses lie from their mapped lines (routing through the corridor), and where they lie furthest.
      int[] shifts = new int[5];
      double shiftSum = 0.0;
      List<double[]> furthest = new ArrayList<>();
      for (int tz = Math.floorDiv(area.minZ, tile); tz <= Math.floorDiv(area.minZ + size - 1, tile); tz++)
         for (int tx = Math.floorDiv(area.minX, tile); tx <= Math.floorDiv(area.minX + size - 1, tile); tx++) {
            RiverChannels plan = (RiverChannels)channels.invoke(state, tx, tz);
            for (double[] c : plan.courses()) {
               if (Math.floorDiv((int)Math.floor(c[0]), tile) == tx && Math.floorDiv((int)Math.floor(c[1]), tile) == tz
                  && c[0] >= area.minX && c[1] >= area.minZ && c[0] < area.minX + size && c[1] < area.minZ + size) {
                  double shift = c[10];
                  shifts[shift < 4 ? 0 : shift < 16 ? 1 : shift < 32 ? 2 : shift < 64 ? 3 : 4]++;
                  shiftSum += shift;
                  if (shift >= 16) furthest.add(new double[]{shift, c[0], c[1]});
               }
               int reach = (int)Math.max(1, Math.min(4, Math.ceil(c[7])));
               double dx = c[2] - c[0], dz = c[3] - c[1];
               int steps = (int)Math.ceil(Math.hypot(dx, dz));
               for (int j = 0; j < steps; j++) {
                  int x = (int)Math.floor(c[0] + dx * j / steps), z = (int)Math.floor(c[1] + dz * j / steps);
                  if (Math.floorDiv(x, tile) != tx || Math.floorDiv(z, tile) != tz) continue;
                  int lx = x - area.minX, lz = z - area.minZ;
                  if (lx < reach || lz < reach || lx >= size - reach || lz >= size - reach) continue;
                  total++;
                  boolean wet = false;
                  for (int oz = -reach; oz <= reach && !wet; oz++) for (int ox = -reach; ox <= reach && !wet; ox++) {
                     byte kind = area.kind[(lz + oz) * size + lx + ox];
                     wet = kind == WET || kind == FALLING || kind == OCEAN;
                  }
                  if (!wet) { missing[lz * size + lx] = 1; lost++; lostFeatures.merge((long)c[4], 1, Integer::sum); }
               }
            }
         }
      System.out.printf(Locale.ROOT, "RIVERSCAN_LINES points=%d missing=%d features=%s%n", total, lost, lostFeatures);
      int vertices = shifts[0] + shifts[1] + shifts[2] + shifts[3] + shifts[4];
      System.out.printf(Locale.ROOT, "RIVERSCAN_SHIFT vertices=%d mean=%.1f [..3]=%d [4-15]=%d [16-31]=%d [32-63]=%d [64+]=%d%n", vertices,
         vertices == 0 ? 0.0 : shiftSum / vertices, shifts[0], shifts[1], shifts[2], shifts[3], shifts[4]);
      furthest.sort((a, b) -> Double.compare(b[0], a[0]));
      List<double[]> listed = new ArrayList<>();
      for (double[] f : furthest) {
         boolean apart = true;
         for (double[] l : listed) apart &= Math.hypot(f[1] - l[1], f[2] - l[2]) > 128.0;
         if (!apart) continue;
         listed.add(f);
         System.out.printf(Locale.ROOT, "RIVERSCAN_SHIFT #%d block=%d,%d lat=%.5f lon=%.5f shift=%.0f%n", listed.size() - 1, (int)f[1], (int)f[2],
            EarthProjection.blockZToLat(f[2], area.scale), EarthProjection.blockXToLongitude(f[1], area.scale), f[0]);
         if (listed.size() == 6) break;
      }
   }

   /** Histogram, the worst clusters with their coordinates, and close-up images of the first few. */
   private static void report(String name, int[] magnitude, int threshold, Area area, Path out) throws IOException {
      int size = area.size;
      int[] histogram = new int[5];
      int total = 0;
      for (int value : magnitude) if (value >= threshold) { total++; histogram[value <= 3 ? 0 : value <= 7 ? 1 : value <= 15 ? 2 : value <= 31 ? 3 : 4]++; }
      System.out.printf(Locale.ROOT, "RIVERSCAN_%s cells=%d [..3]=%d [4-7]=%d [8-15]=%d [16-31]=%d [32+]=%d%n",
         name, total, histogram[0], histogram[1], histogram[2], histogram[3], histogram[4]);
      boolean[] seen = new boolean[magnitude.length];
      List<int[]> clusters = new ArrayList<>();
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      for (int start = 0; start < magnitude.length; start++) {
         if (magnitude[start] < threshold || seen[start]) continue;
         int cells = 0, worst = start;
         seen[start] = true; queue.add(start);
         while (!queue.isEmpty()) {
            int c = queue.removeFirst(); cells++;
            if (magnitude[c] > magnitude[worst]) worst = c;
            int x = c % size, z = c / size;
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
               int nx = x + dx, nz = z + dz;
               if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
               int n = nz * size + nx;
               if (magnitude[n] >= threshold && !seen[n]) { seen[n] = true; queue.add(n); }
            }
         }
         clusters.add(new int[]{worst, cells, magnitude[worst]});
      }
      clusters.sort(Comparator.comparingInt((int[] c) -> -(c[2] * 4 + Math.min(c[1], 400))));
      for (int k = 0; k < Math.min(12, clusters.size()); k++) {
         int[] c = clusters.get(k);
         int x = area.minX + c[0] % size, z = area.minZ + c[0] / size;
         System.out.printf(Locale.ROOT, "RIVERSCAN_%s #%d block=%d,%d lat=%.5f lon=%.5f cells=%d max=%d raw=%d terrain=%d water=%d kind=%d%n",
            name, k, x, z, EarthProjection.blockZToLat(z, area.scale), EarthProjection.blockXToLongitude(x, area.scale),
            c[1], c[2], area.raw[c[0]], area.terrain[c[0]], area.water[c[0]], area.kind[c[0]]);
         if (k < 6) {
            int half = 48, x0 = Math.max(0, Math.min(size - half * 2, c[0] % size - half)), z0 = Math.max(0, Math.min(size - half * 2, c[0] / size - half));
            ImageIO.write(render(area, x0, z0, half * 2, 4, magnitude, threshold), "png",
               out.resolve(name.toLowerCase(Locale.ROOT) + "-" + k + ".png").toFile());
         }
      }
   }

   /** Shaded terrain, inland water by depth, banks in tan, flagged cells in red. */
   private static BufferedImage render(Area area, int x0, int z0, int span, int zoom, int[] flagged, int threshold) {
      int size = area.size;
      BufferedImage image = new BufferedImage(span * zoom, span * zoom, BufferedImage.TYPE_INT_RGB);
      for (int z = 0; z < span; z++) for (int x = 0; x < span; x++) {
         int i = (z0 + z) * size + x0 + x;
         int left = (z0 + z) * size + Math.max(0, x0 + x - 1), up = Math.max(0, z0 + z - 1) * size + x0 + x;
         double shade = Math.max(0.25, Math.min(1.0, 0.65 + (area.terrain[left] - area.terrain[i] + area.terrain[up] - area.terrain[i]) * 0.08));
         int rgb;
         if (flagged[i] >= threshold) rgb = 0xFF2020;
         else if (area.kind[i] == FALLING) rgb = 0xFFFFFF;
         else if (area.kind[i] == WET) { int depth = Math.min(8, area.water[i] - area.terrain[i]); rgb = rgb(0.1, 0.35 + depth * 0.04, 0.95 - depth * 0.05); }
         else if (area.kind[i] == OCEAN) rgb = 0x1A3A8A;
         else if (area.kind[i] == BANK) rgb = rgb(0.75 * shade, 0.7 * shade, 0.45 * shade);
         else rgb = rgb(0.45 * shade, 0.6 * shade, 0.4 * shade);
         for (int dz = 0; dz < zoom; dz++) for (int dx = 0; dx < zoom; dx++) image.setRGB(x * zoom + dx, z * zoom + dz, rgb);
      }
      return image;
   }

   /**
    * Water by level band (a new shade every block, paler where the surface flows), hard steps yellow, reverse
    * steps magenta, drawn waterways without water orange.
    */
   private static BufferedImage renderLevels(Area area, int x0, int z0, int span, int zoom, int[] hard, int[] reverse, int[] missing) {
      int size = area.size;
      int[] bands = {0x1F4FD0, 0x2A8C9E, 0x3A6FE0, 0x1E9E6A, 0x5A50C8, 0x2E7FB8};
      BufferedImage image = new BufferedImage(span * zoom, span * zoom, BufferedImage.TYPE_INT_RGB);
      for (int z = 0; z < span; z++) for (int x = 0; x < span; x++) {
         int i = (z0 + z) * size + x0 + x;
         int left = (z0 + z) * size + Math.max(0, x0 + x - 1), up = Math.max(0, z0 + z - 1) * size + x0 + x;
         double shade = Math.max(0.25, Math.min(1.0, 0.65 + (area.terrain[left] - area.terrain[i] + area.terrain[up] - area.terrain[i]) * 0.08));
         int rgb;
         if (reverse[i] > 0) rgb = 0xFF00FF;
         else if (hard[i] > 0) rgb = 0xFFE000;
         else if (missing[i] > 0) rgb = 0xFF8000;
         else if (area.kind[i] == FALLING) rgb = 0xFFFFFF;
         else if (area.kind[i] == WET) {
            int band = bands[Math.floorMod(area.water[i], bands.length)];
            if (area.flow[i] > 0) band = (band & 0xFEFEFE) / 2 + 0x7F7F7F;
            rgb = band;
         } else if (area.kind[i] == OCEAN) rgb = 0x1A3A8A;
         else if (area.kind[i] == BANK) rgb = rgb(0.75 * shade, 0.7 * shade, 0.45 * shade);
         else rgb = rgb(0.45 * shade, 0.6 * shade, 0.4 * shade);
         for (int dz = 0; dz < zoom; dz++) for (int dx = 0; dx < zoom; dx++) image.setRGB(x * zoom + dx, z * zoom + dz, rgb);
      }
      return image;
   }

   /** The drawn courses over a close-up: black lines, with each segment's water block at its start in white. */
   private static void drawCourses(BufferedImage image, WaterSurfaceResolver resolver, Area area, int x0, int z0, int span, int zoom)
      throws ReflectiveOperationException {
      InlandHydrologyPlanner planner = (InlandHydrologyPlanner)field(WaterSurfaceResolver.class, "inlandHydrology").get(resolver);
      Object state = field(InlandHydrologyPlanner.class, "state").get(planner);
      Method channels = state.getClass().getDeclaredMethod("channels", int.class, int.class);
      channels.setAccessible(true);
      java.awt.Graphics2D g = image.createGraphics();
      g.setFont(g.getFont().deriveFont(9f));
      int minX = area.minX + x0, minZ = area.minZ + z0;
      java.util.Set<String> labelled = new java.util.HashSet<>();
      for (int tz = Math.floorDiv(minZ, RiverChannels.TILE); tz <= Math.floorDiv(minZ + span, RiverChannels.TILE); tz++)
         for (int tx = Math.floorDiv(minX, RiverChannels.TILE); tx <= Math.floorDiv(minX + span, RiverChannels.TILE); tx++) {
            RiverChannels plan = (RiverChannels)channels.invoke(state, tx, tz);
            for (double[] c : plan.courses()) {
               int ax = (int)((c[0] - minX) * zoom), az = (int)((c[1] - minZ) * zoom), bx = (int)((c[2] - minX) * zoom), bz = (int)((c[3] - minZ) * zoom);
               if (Math.max(ax, bx) < 0 || Math.max(az, bz) < 0 || Math.min(ax, bx) > span * zoom || Math.min(az, bz) > span * zoom) continue;
               g.setColor(java.awt.Color.BLACK);
               g.drawLine(ax, az, bx, bz);
               String label = String.valueOf((int)c[5]);
               if (ax >= 0 && az >= 0 && ax < span * zoom && az < span * zoom && labelled.add(ax / 24 + ":" + az / 24)) {
                  g.setColor(java.awt.Color.WHITE);
                  g.drawString(label, ax + 2, az - 2);
               }
            }
         }
      g.dispose();
   }

   private static int rgb(double r, double g, double b) {
      return (int)(Math.max(0, Math.min(1, r)) * 255) << 16 | (int)(Math.max(0, Math.min(1, g)) * 255) << 8 | (int)(Math.max(0, Math.min(1, b)) * 255);
   }

   private static Field field(Class<?> type, String name) throws NoSuchFieldException {
      Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field;
   }
}
