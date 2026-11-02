package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.geology.Lithology;

/**
 * Version-neutral rock for exposed bedrock, from the column's GLiM rock class.
 *
 * <p>Layered rock is cut into beds along a stratigraphic height in real metres, so a bed sits at the same real
 * height at every world scale and only its thickness in blocks changes. A family of beds whose mean thickness is
 * under one block is left out, because sampling it would only flicker block to block; coarse scales therefore show
 * one rock per slope. Massive rock such as granite has no beds: each region of a massif is one rock, with sparse
 * darker enclaves. Material indices are resolved to block states by the chunk generator.</p>
 */
public final class StrataPolicy {
   public static final int STONE = 0;
   public static final int ANDESITE = 1;
   public static final int DIORITE = 2;
   public static final int GRANITE = 3;
   public static final int CALCITE = 4;
   public static final int DEEPSLATE = 5;
   public static final int TUFF = 6;
   public static final int BASALT = 7;
   public static final int SMOOTH_BASALT = 8;
   public static final int BLACKSTONE = 9;
   public static final int SANDSTONE = 10;
   public static final int SMOOTH_SANDSTONE = 11;
   public static final int RED_SANDSTONE = 12;
   public static final int TERRACOTTA = 13;
   public static final int WHITE_TERRACOTTA = 14;
   public static final int LIGHT_GRAY_TERRACOTTA = 15;
   public static final int RED_TERRACOTTA = 16;
   public static final int ORANGE_TERRACOTTA = 17;
   public static final int PACKED_MUD = 18;
   public static final int COARSE_DIRT = 19;
   public static final int CLAY = 20;
   public static final int COBBLESTONE = 21;
   public static final int MATERIAL_COUNT = 22;

   private static final double BED_JITTER = 0.7;
   private static final long FORMATION_SALT = 0x3D9A61C7E2B54F08L;
   private static final long PICK_SALT = -0x5E2F7A14C9B3D6E1L;
   private static final long BED_SALT = 0x6B1C8E3F27A9D54CL;
   private static final long ACCENT_SALT = -0x29D4B7E61F3A8C05L;
   private static final long DIP_SALT = 0x7F3E2A9C5B1D6084L;
   private static final long DIP_DETAIL_SALT = -0x4A6C3E1B9F72D8A3L;
   private static final long PATCH_SALT = 0x12B4D6F8A3C5E709L;
   private static final long PATCH_DETAIL_SALT = -0x6D8F1A3C5E7B9024L;
   private static final long ZONE_SALT = 0x58E3A1C7924D6FB0L;
   // A massive rock body keeps one colour across a whole massif; it changes over tens of kilometres.
   private static final double ZONE_METERS = 20_000.0;
   // Value noise seldom reaches its top, so this leaves enclaves and dikes on only a few percent of a face.
   private static final double ENCLAVE_THRESHOLD = 0.72;

   private static final Palette GENERIC = massive(DIORITE, 80.0, STONE, 70, ANDESITE, 30);
   // Granite massifs are grey in most regions (diorite reads as grey granite) and pink in others.
   private static final Palette ACID_PLUTONIC = massive(ANDESITE, 80.0, DIORITE, 60, GRANITE, 40);
   private static final Palette INTERMEDIATE_PLUTONIC = massive(ANDESITE, 80.0, DIORITE, 60, STONE, 40);
   private static final Palette BASIC_PLUTONIC = massive(ANDESITE, 80.0, DEEPSLATE, 70, BLACKSTONE, 30);
   private static final Palette ACID_VOLCANIC = layered(
      TUFF, LIGHT_GRAY_TERRACOTTA, 25, 160.0, 8.0, WHITE_TERRACOTTA, 30, TUFF, 30, LIGHT_GRAY_TERRACOTTA, 20, GRANITE, 10, ANDESITE, 10
   );
   private static final Palette INTERMEDIATE_VOLCANIC = layered(
      ANDESITE, TUFF, 25, 140.0, 8.0, ANDESITE, 40, TUFF, 20, STONE, 15, DEEPSLATE, 10, TERRACOTTA, 10, BLACKSTONE, 5
   );
   private static final Palette BASIC_VOLCANIC = layered(
      BASALT, BLACKSTONE, 20, 100.0, 8.0, BASALT, 45, SMOOTH_BASALT, 25, DEEPSLATE, 15, BLACKSTONE, 10, RED_TERRACOTTA, 5
   );
   private static final Palette PYROCLASTIC = layered(
      TUFF, WHITE_TERRACOTTA, 20, 120.0, 6.0, TUFF, 50, LIGHT_GRAY_TERRACOTTA, 20, WHITE_TERRACOTTA, 15, TERRACOTTA, 10, ANDESITE, 5
   );
   private static final Palette CARBONATE = layered(STONE, ANDESITE, 20, 200.0, 6.0, STONE, 35, CALCITE, 35, DIORITE, 15, ANDESITE, 15);
   private static final Palette EVAPORITE = layered(
      WHITE_TERRACOTTA, CALCITE, 25, 120.0, 4.0, WHITE_TERRACOTTA, 40, CALCITE, 30, LIGHT_GRAY_TERRACOTTA, 15, RED_TERRACOTTA, 15
   );
   private static final Palette SILICICLASTIC = layered(
      STONE, LIGHT_GRAY_TERRACOTTA, 25, 200.0, 8.0, STONE, 30, SANDSTONE, 20, LIGHT_GRAY_TERRACOTTA, 15, TERRACOTTA, 15, DEEPSLATE, 10, ANDESITE, 10
   );
   // Desert sediment is oxidised: red beds with pale sandstone, as on the Colorado Plateau.
   private static final Palette ARID_SILICICLASTIC = layered(
      RED_SANDSTONE, TERRACOTTA, 25, 200.0, 8.0,
      RED_SANDSTONE, 25, SANDSTONE, 20, ORANGE_TERRACOTTA, 15, RED_TERRACOTTA, 15, TERRACOTTA, 15, WHITE_TERRACOTTA, 10
   );
   private static final Palette MIXED_SEDIMENTARY = layered(
      STONE, LIGHT_GRAY_TERRACOTTA, 25, 160.0, 6.0, STONE, 30, CALCITE, 15, SANDSTONE, 15, LIGHT_GRAY_TERRACOTTA, 15, TERRACOTTA, 15, ANDESITE, 10
   );
   private static final Palette ARID_MIXED_SEDIMENTARY = layered(
      SANDSTONE, TERRACOTTA, 25, 160.0, 6.0,
      SANDSTONE, 20, TERRACOTTA, 20, CALCITE, 15, ORANGE_TERRACOTTA, 15, RED_SANDSTONE, 15, WHITE_TERRACOTTA, 15
   );
   private static final Palette UNCONSOLIDATED = layered(
      PACKED_MUD, COARSE_DIRT, 30, 30.0, 3.0, PACKED_MUD, 30, COARSE_DIRT, 20, TERRACOTTA, 15, CLAY, 15, SMOOTH_SANDSTONE, 10, COBBLESTONE, 10
   );
   private static final Palette METAMORPHIC = new Palette(
      Family.FOLIATED, STONE, DEEPSLATE, 20, 60.0, 6.0, weights(STONE, 35, ANDESITE, 25, DEEPSLATE, 25, DIORITE, 15)
   );

   private StrataPolicy() {
   }

   /**
    * Everything about a column that does not change with height.
    *
    * @param horizontalMetersPerBlock world scale at the equator, which keeps the bed geometry seamless across latitudes
    * @param verticalMetersPerBlock real metres per block of terrain height at this column
    */
   public static Column column(
      Lithology lithology,
      boolean arid,
      int worldX,
      int worldZ,
      double horizontalMetersPerBlock,
      double verticalMetersPerBlock,
      int seaLevelY,
      long worldSeed
   ) {
      Palette palette = palette(lithology, arid);
      double xMeters = worldX * horizontalMetersPerBlock;
      double zMeters = worldZ * horizontalMetersPerBlock;
      double offset = switch (palette.family()) {
         case LAYERED -> dipOffset(xMeters, zMeters, worldSeed, 120.0, 24_000.0, 12.0, 1_600.0);
         // Foliation stands steep and folded rather than lying flat.
         case FOLIATED -> dipOffset(xMeters, zMeters, worldSeed, 500.0, 5_000.0, 150.0, 600.0);
         case MASSIVE -> 0.0;
      };
      double safeVertical = verticalMetersPerBlock > 0.0 && Double.isFinite(verticalMetersPerBlock) ? verticalMetersPerBlock : 1.0;
      boolean drawFormations = palette.family() == Family.MASSIVE
         ? palette.formationMeters() >= 2.0 * horizontalMetersPerBlock
         : palette.formationMeters() * 0.5 >= safeVertical;
      boolean drawBeds = palette.family() != Family.MASSIVE && palette.bedMeters() * 0.5 >= safeVertical;
      int main = palette.main();
      if (palette.family() == Family.MASSIVE) {
         // Stretch the zone noise away from its middle so a massif is one rock, with short transitions.
         double zone = noise(xMeters / ZONE_METERS, 0.0, zMeters / ZONE_METERS, worldSeed ^ ZONE_SALT);
         main = pick(palette, Math.min(Math.nextDown(1.0), Math.max(0.0, (zone - 0.5) * 2.5 + 0.5)));
      }
      return new Column(palette, main, xMeters, zMeters, offset, safeVertical, seaLevelY, worldSeed, drawFormations, drawBeds);
   }

   private static Palette palette(Lithology lithology, boolean arid) {
      return switch (lithology) {
         case ACID_PLUTONIC -> ACID_PLUTONIC;
         case INTERMEDIATE_PLUTONIC -> INTERMEDIATE_PLUTONIC;
         case BASIC_PLUTONIC -> BASIC_PLUTONIC;
         case ACID_VOLCANIC -> ACID_VOLCANIC;
         case INTERMEDIATE_VOLCANIC -> INTERMEDIATE_VOLCANIC;
         case BASIC_VOLCANIC -> BASIC_VOLCANIC;
         case PYROCLASTIC -> PYROCLASTIC;
         case CARBONATE_SEDIMENTARY -> CARBONATE;
         case EVAPORITE -> EVAPORITE;
         case SILICICLASTIC_SEDIMENTARY -> arid ? ARID_SILICICLASTIC : SILICICLASTIC;
         case MIXED_SEDIMENTARY -> arid ? ARID_MIXED_SEDIMENTARY : MIXED_SEDIMENTARY;
         case UNCONSOLIDATED_SEDIMENT -> UNCONSOLIDATED;
         // Bedrock under ice sheets is mostly shield gneiss.
         case METAMORPHIC, ICE_AND_GLACIERS -> METAMORPHIC;
         case UNKNOWN, UNDEFINED -> GENERIC;
      };
   }

   private static double dipOffset(
      double xMeters, double zMeters, long seed, double broadMeters, double broadWavelength, double detailMeters, double detailWavelength
   ) {
      double broad = noise(xMeters / broadWavelength, 0.0, zMeters / broadWavelength, seed ^ DIP_SALT) * 2.0 - 1.0;
      double detail = noise(xMeters / detailWavelength, 0.0, zMeters / detailWavelength, seed ^ DIP_DETAIL_SALT) * 2.0 - 1.0;
      return broad * broadMeters + detail * detailMeters;
   }

   /** Splits each period into two beds at a jittered cut, so bed thickness varies around half the period. */
   private static long bedIndex(double stratigraphicMeters, double period, long seed) {
      double position = stratigraphicMeters / period;
      double cellFloor = Math.floor(position);
      long cell = (long)cellFloor;
      double cut = 0.5 + (unit(cell, 0L, 0L, seed) - 0.5) * BED_JITTER;
      return position - cellFloor < cut ? cell * 2L : cell * 2L + 1L;
   }

   private static int pick(Palette palette, double roll) {
      int remaining = (int)(roll * 100.0);
      int[] weighted = palette.weighted();
      for (int i = 0; i < weighted.length; i += 2) {
         remaining -= weighted[i + 1];
         if (remaining < 0) {
            return weighted[i];
         }
      }
      return palette.main();
   }

   private static double noise(double x, double y, double z, long seed) {
      double floorX = Math.floor(x);
      double floorY = Math.floor(y);
      double floorZ = Math.floor(z);
      long x0 = (long)floorX;
      long y0 = (long)floorY;
      long z0 = (long)floorZ;
      double fx = fade(x - floorX);
      double fy = fade(y - floorY);
      double fz = fade(z - floorZ);
      double low = lerp(fz,
         lerp(fx, unit(x0, y0, z0, seed), unit(x0 + 1L, y0, z0, seed)),
         lerp(fx, unit(x0, y0, z0 + 1L, seed), unit(x0 + 1L, y0, z0 + 1L, seed)));
      if (fy == 0.0) {
         return low;
      }
      double high = lerp(fz,
         lerp(fx, unit(x0, y0 + 1L, z0, seed), unit(x0 + 1L, y0 + 1L, z0, seed)),
         lerp(fx, unit(x0, y0 + 1L, z0 + 1L, seed), unit(x0 + 1L, y0 + 1L, z0 + 1L, seed)));
      return lerp(fy, low, high);
   }

   private static double unit(long x, long y, long z, long seed) {
      long hash = seed ^ x * -7046029254386353131L ^ y * 7640891576956012809L ^ z * -4417276706812531889L;
      hash = (hash ^ hash >>> 33) * -49064778989728563L;
      hash = (hash ^ hash >>> 33) * -4265267296055464877L;
      hash ^= hash >>> 33;
      return (hash >>> 11) * 0x1.0p-53;
   }

   private static double fade(double value) {
      return value * value * (3.0 - 2.0 * value);
   }

   private static double lerp(double delta, double start, double end) {
      return start + delta * (end - start);
   }

   private static Palette layered(int main, int accent, int accentPercent, double formationMeters, double bedMeters, int... weighted) {
      return new Palette(Family.LAYERED, main, accent, accentPercent, formationMeters, bedMeters, weights(weighted));
   }

   /** @param zones the rocks a massif can be made of, one per region */
   private static Palette massive(int enclave, double patchMeters, int... zones) {
      return new Palette(Family.MASSIVE, zones[0], enclave, 0, patchMeters, 0.0, weights(zones));
   }

   private static int[] weights(int... weighted) {
      int total = 0;
      for (int i = 1; i < weighted.length; i += 2) {
         total += weighted[i];
      }
      if (weighted.length % 2 != 0 || total != 100) {
         throw new IllegalArgumentException("Strata weights must be material/weight pairs summing to 100");
      }
      return weighted;
   }

   private enum Family {
      LAYERED,
      FOLIATED,
      MASSIVE
   }

   private record Palette(
      Family family, int main, int accent, int accentPercent, double formationMeters, double bedMeters, int[] weighted
   ) {
   }

   /** Rock of one column; {@link #material(int)} gives the material index at a block height. */
   public static final class Column {
      private final Palette palette;
      private final int main;
      private final double xMeters;
      private final double zMeters;
      private final double offsetMeters;
      private final double verticalMetersPerBlock;
      private final int seaLevelY;
      private final long seed;
      private final boolean drawFormations;
      private final boolean drawBeds;

      private Column(
         Palette palette,
         int main,
         double xMeters,
         double zMeters,
         double offsetMeters,
         double verticalMetersPerBlock,
         int seaLevelY,
         long seed,
         boolean drawFormations,
         boolean drawBeds
      ) {
         this.palette = palette;
         this.main = main;
         this.xMeters = xMeters;
         this.zMeters = zMeters;
         this.offsetMeters = offsetMeters;
         this.verticalMetersPerBlock = verticalMetersPerBlock;
         this.seaLevelY = seaLevelY;
         this.seed = seed;
         this.drawFormations = drawFormations;
         this.drawBeds = drawBeds;
      }

      public int material(int y) {
         if (!this.drawFormations) {
            return this.main;
         }

         // Sample the block's centre, in real metres above sea level, shifted by the column's dip.
         double meters = (y + 0.5 - this.seaLevelY) * this.verticalMetersPerBlock + this.offsetMeters;
         if (this.palette.family() == Family.MASSIVE) {
            double patch = this.palette.formationMeters();
            double broad = noise(this.xMeters / patch, meters / patch, this.zMeters / patch, this.seed ^ PATCH_SALT);
            double detail = noise(this.xMeters * 3.0 / patch, meters * 3.0 / patch, this.zMeters * 3.0 / patch, this.seed ^ PATCH_DETAIL_SALT);
            return broad * 0.7 + detail * 0.3 > ENCLAVE_THRESHOLD ? this.palette.accent() : this.main;
         }

         long formation = bedIndex(meters, this.palette.formationMeters(), this.seed ^ FORMATION_SALT);
         int material = pick(this.palette, unit(formation, 1L, 0L, this.seed ^ PICK_SALT));
         if (this.drawBeds && material != this.palette.accent()) {
            long bed = bedIndex(meters, this.palette.bedMeters(), this.seed ^ BED_SALT);
            if (unit(bed, 2L, 0L, this.seed ^ ACCENT_SALT) * 100.0 < this.palette.accentPercent()) {
               material = this.palette.accent();
            }
         }
         return material;
      }

      /** The rock shown when beds or enclaves are too small to draw at this scale. */
      public int mainMaterial() {
         return this.main;
      }
   }
}
